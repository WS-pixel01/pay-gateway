# Unified Payment Gateway (pay-gateway)

[![CI](https://github.com/WS-pixel01/pay-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/WS-pixel01/pay-gateway/actions/workflows/ci.yml)

A payment gateway covering multi-channel checkout, refunds, inbound webhook processing and daily
reconciliation, built around idempotency keys, the transactional outbox pattern and exactly-once
processing. **No middleware needed locally**: in-memory H2, a simulated third-party channel and a
simulated MQ, so the whole money flow runs as soon as you start it.

---

## 1. Quick start

### Run with Docker (no JDK or Maven needed)

```bash
docker compose up --build
```

The image is a multi-stage build: a Maven stage compiles the project, then a JRE-21 runtime stage
runs the exploded Spring Boot layered jar as a non-root user. The compose file wires a healthcheck
against `/actuator/health`, and CI boots the container on every push to prove the image actually
starts.

### Run from source

Prerequisites: JDK 21+, Maven 3.8+.

Build from the **project root** (`pay-gateway/`, where the parent pom lives):

```bash
mvn clean package
```

Then start the executable jar:

```bash
java -Dfile.encoding=UTF-8 -jar pay-bootstrap/target/pay-gateway.jar
```

During development you can also run straight from the bootstrap module (do `mvn install -DskipTests`
in the root once first, so the local dependencies are installed):

```bash
mvn -f pay-bootstrap/pom.xml spring-boot:run
```

| Entry point | URL |
| --- | --- |
| API docs (Knife4j) | http://localhost:8080/doc.html |
| H2 console | http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:paydb`, user `sa`, empty password) |

Run the tests:

```bash
mvn test
```

---

## 2. A five-minute end-to-end tour

### 1) Balance payment (synchronous, instant result)

```bash
curl -X POST http://localhost:8080/api/pay/create -H "Content-Type: application/json" -d "{\"bizSystem\":\"mall\",\"bizOrderNo\":\"MALL20260728001\",\"userId\":\"U10001\",\"amount\":99.90,\"subject\":\"iPhone case\",\"channelType\":\"BALANCE\"}"
```

Now send the **exact same request** again — you get back the same `payNo` and the balance is only
debited once. `bizSystem + bizOrderNo` acts as the **idempotency key**: `@PayIdempotent` claims it
and replays the first response for any duplicate, giving exactly-once processing on the create
endpoint.

### 2) Alipay payment (webhook path)

```bash
curl -X POST http://localhost:8080/api/pay/create -H "Content-Type: application/json" -d "{\"bizSystem\":\"mall\",\"bizOrderNo\":\"MALL20260728002\",\"userId\":\"U10001\",\"amount\":188.00,\"subject\":\"Bluetooth earphones\",\"channelType\":\"ALIPAY\"}"
```

The response carries a `payUrl`. About three seconds later the simulated channel delivers an
RSA2-signed **webhook**, and — by configuration — delivers the same webhook a second time. Webhook
delivery is at-least-once by nature, so the gateway deduplicates: the logs show the second delivery
classified as `DUPLICATE`:

```bash
curl "http://localhost:8080/api/ops/webhooks"
```

Check that the pay order is now `SUCCESS`:

```bash
curl "http://localhost:8080/api/pay/biz?bizSystem=mall&bizOrderNo=MALL20260728002"
```

Confirm the upstream system really received the result (via the transactional outbox plus MQ):

```bash
curl http://localhost:8080/api/ops/notifications
curl "http://localhost:8080/api/ops/messages?bizKey=<the payNo from above>"
```

### 3) WeChat payment (AES-GCM encrypted webhook)

Change `channelType` above to `WECHAT`. The business fields of the webhook arrive inside an
AES-GCM encrypted `resource` field; the gateway verifies the MD5 signature first and then decrypts,
mirroring the real shape of WeChat Pay v3.

### 4) No channel specified — automatic routing

Drop the `channelType` field and the gateway picks a channel by amount range, user region, priority
and fee rate. The WeChat channel is capped at 50,000 and only serves CN/HK, so an amount of `60000`
automatically lands on Alipay. Disabling a channel and ordering again demonstrates failover:

```bash
curl -X POST "http://localhost:8080/api/ops/channels/ALIPAY/switch?enabled=false"
```

### 5) Refunds (full and partial)

```bash
curl -X POST http://localhost:8080/api/refund/apply -H "Content-Type: application/json" -d "{\"payNo\":\"<pay order number>\",\"bizRefundNo\":\"MALLREF001\",\"amount\":50.00,\"reason\":\"user cancelled\"}"
```

The pay order becomes `PART_REFUNDED`, then `REFUNDED` once everything is refunded. Over-refunds are
rejected.

### 6) Reconciliation and discrepancy records

```bash
curl -X POST "http://localhost:8080/api/ops/reconcile/run"
curl "http://localhost:8080/api/ops/reconcile/diffs"
```

When the simulated channel generates the bill file it **injects three kinds of discrepancy on
purpose** (drops one entry, adds one entry, shaves a cent off one amount), so you immediately see
`CHANNEL_MISS`, `LOCAL_MISS` and `AMOUNT_MISMATCH` records. Bill files are archived under
`./data/bills/`.

### 7) Expiry-driven order closing

The simulated channel pays an order within three seconds by default, so to watch an order expire
you have to start with a long webhook delay:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--pay.mock.webhook-delay-seconds=600
```

Then create an order with `"expireMinutes":1`. Within one to two minutes the close task moves it to
`CLOSED` and publishes a `pay.closed` message so upstream releases the reserved stock.

---

## 3. Tech stack

Java 21 · Spring Boot 3.2 · MyBatis-Plus 3.5 · H2 · Redisson ·
Knife4j/OpenAPI3 · JJWT · Spring AOP · Spring Scheduling · Lombok · Maven multi-module

---

## 4. Project structure (Maven multi-module)

```
pay-gateway/                parent pom: pins Java 21, dependency versions and the module list
├── pay-common/             technical foundation, no business logic
│   ├── api/exception/util  unified response, error codes, money and signature utils (BigDecimal / RSA / MD5 / AES-GCM)
│   ├── infra/              IdempotentStore · DistributedLock · SlidingWindowRateLimiter
│   │                       ├── memory  in-memory implementations (default, zero dependencies)
│   │                       └── redis   Redisson implementations (pay.infra.type=redis)
│   ├── idempotent/         @PayIdempotent plus its aspect
│   ├── ratelimit/          @RateLimit plus the sliding-window aspect
│   ├── auth/               JWT issuing and the gateway auth interceptor
│   └── config/             PayProperties · infrastructure selection · MyBatis-Plus · RestTemplate
├── pay-account/            account domain: balance accounts, money ledger
├── pay-channel/            channel domain: config and routing, Alipay/WeChat/balance adapters, channel simulator
├── pay-message/            messaging domain: transactional outbox, simulated RabbitMQ, idempotent consumers, compensation
├── pay-trade/              trade domain: pay orders + state machine, refunds, inbound webhooks, expiry closing
├── pay-reconcile/          reconciliation domain: bill download/archive/parse, row-by-row compare, discrepancies
└── pay-bootstrap/          bootstrap assembly: application, web config, ops endpoints, yml and DDL
                            (the only module that produces an executable jar)
```

Dependencies flow strictly one way, with no cycles:

```
pay-common ←── pay-account ←── pay-channel ←┬── pay-trade ←── pay-reconcile ←── pay-bootstrap
                                            │      ↑
                        pay-common ←── pay-message ┘
```

**Why cut it this way**: these boundaries are where the system would be split into microservices.
They are modules today; giving `pay-trade` / `pay-channel` / `pay-reconcile` their own entry points
later turns them into standalone services without rewriting business code.

Two deliberately inconvenient choices keep those boundaries honest:

1. `RestTemplate` is defined in `pay-common` rather than the bootstrap module, because the channel
   domain needs it to call third parties — **a lower module must never depend on a bean defined in a
   higher one**;
2. the reconciliation domain only depends on the `PayChannelAdapter#downloadBill` interface for the
   raw bill and **knows nothing** about the sandbox simulator. Discrepancy injection lives on the
   channel side, because in the real world discrepancies originate at the channel and reconciliation's
   only job is to compare faithfully.

---

## 5. Core design

### 5.1 Pay order state machine

```
WAIT_PAY ──paid──▶ SUCCESS ──partial refund──▶ PART_REFUNDED ──fully refunded──▶ REFUNDED
   │                  └──────full refund───────────────────────────────────────▶ REFUNDED
   ├──payment failed──▶ FAIL
   └──expired / cancelled──▶ CLOSED
```

Every status change must pass through `PayOrderStateMachine`, which structurally rules out money
incidents like "a late webhook flips a closed order back to paid".

### 5.2 Exactly-once order creation: three layers

| Layer | Mechanism | Purpose |
| --- | --- | --- |
| Edge | `@RateLimit` sliding window | Holds back bulk/abusive pay-order creation |
| Application | `@PayIdempotent` — claims the **idempotency key** (`bizSystem + bizOrderNo`) via Redis/in-memory `SET NX EX` | **Replays the first result** for duplicates instead of just erroring |
| Storage | unique index on `pay_order (biz_system, biz_order_no)` | Backstop for concurrency outside the idempotency window |

### 5.3 The five gates of an inbound webhook

Verify signature (RSA2 / MD5) → parse (decrypt WeChat's AES-GCM `resource`) → deduplicate on the
webhook's idempotency key (channel + order number + channel trade number) → serialise under a
distributed lock per pay order → state machine plus amount check.

Channels deliver webhooks at-least-once; the dedup gate is what turns that into **exactly-once
processing**. On failure the idempotency key is **released deliberately**, so the channel's retry
can be processed again. Every webhook — successful or not — writes a `webhook_log` row (raw
payload, verification result, duplicate flag, latency), which is what production money
investigations rely on.

### 5.4 Transactional outbox pattern for reliable delivery

```
business tx { update pay order + insert local_message(NEW) } ──commit──▶ publish to MQ ──▶ consumed → CONSUMED
                                                                 │
                                              failure/crash ─────┴─▶ scheduled task rescans NEW/FAILED and republishes
```

The outbox row commits atomically with the business change, and the compensation task retries until
it is delivered — at-least-once delivery. Consumers dedupe on `messageId`, which upgrades the
end-to-end guarantee to exactly-once processing. Anything past its retry budget lands in `DEAD` for
a human to look at.

### 5.5 Money safety

- Every amount is a `BigDecimal` funnelled through `MoneyUtil` (2 decimals, HALF_UP, yuan/cent
  conversion), so floating-point error is impossible;
- all status transitions are **CAS updates** (`where status = <expected>`), making "read - check -
  write" a single atomic SQL statement;
- balance debits have three lines of defence: distributed lock → optimistic `version` → unique index
  on `account_flow (biz_no, direction)`;
- the lock **encloses** the transaction rather than the other way round (`AccountService` and
  `AccountTxService` are separate beans, avoiding the `@Transactional` self-invocation trap).

### 5.6 Reconciliation

Download the channel bill daily → parse into `channel_bill` → compare both ways against local
successful records → emit discrepancy records:

| Type | Meaning | Typical cause |
| --- | --- | --- |
| `CHANNEL_MISS` | present locally, absent at the channel | forged webhook / channel omission |
| `LOCAL_MISS` | present at the channel, absent locally (**one-sided entry**) | lost webhook; query the channel and backfill |
| `AMOUNT_MISMATCH` | amounts differ | tampered amount / unsynced partial refund |

Re-running reconciliation clears the previous records for the same (date, channel) first, so the job
is naturally idempotent.

---

## 6. Configuration

Everything lives under `pay.*` in `application.yml`:

| Property | Default | Meaning |
| --- | --- | --- |
| `pay.infra.type` | `memory` | Set to `redis` to switch to the Redisson-backed implementations |
| `pay.auth.enabled` | `false` | Once on, pay/refund endpoints need a JWT (obtain one via `POST /api/auth/token`) |
| `pay.order.expire-minutes` | `30` | Pay order lifetime |
| `pay.mock.webhook-delay-seconds` | `3` | Simulated webhook delivery delay |
| `pay.mock.duplicate-webhook` | `true` | Deliver the same webhook twice on purpose, to verify exactly-once processing |
| `pay.mock.fail-rate` | `0` | Simulated payment failure rate (0-100) |
| `pay.mock.notify-fail-times` | `0` | Fail the upstream's first N notifications, to demo message compensation |
| `pay.reconcile.inject-anomaly` | `true` | Inject discrepancies into the bill file so diffs have something to show |

Switching to Redis:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--pay.infra.type=redis
```

---

## 7. Demo script

`docs/demo.http` lists every request in order; the REST Client in IntelliJ or VS Code can run them
one click at a time.

---

## 8. Verification record

Under JDK 21 and Maven 3.8+ the 8-module reactor builds cleanly and `mvn test` passes 17/17
(10 in pay-common, 4 in pay-trade, 3 integration tests in pay-bootstrap). All of the following was
exercised against the packaged jar:

| Scenario | Observed result |
| --- | --- |
| Balance payment + duplicate checkout | Both calls returned the same `payNo`; the balance was debited exactly once |
| Alipay webhook | First delivery `PROCESSED` (RSA2 verified), duplicate delivery caught as `DUPLICATE` |
| WeChat webhook | MD5 verified and the AES-GCM `resource` decrypted; order became `SUCCESS` |
| Automatic routing | 60,000 exceeds WeChat's 50,000 cap → routed to Alipay; disabling Alipay → failed over to WeChat |
| Partial refund | Partial amount refunded → `PART_REFUNDED` with the remainder still refundable; over-refund rejected |
| Reconciliation | All three discrepancy types produced: `CHANNEL_MISS` / `LOCAL_MISS` / `AMOUNT_MISMATCH` |
| Expiry closing | Unpaid order closed by the scheduled task after expiry → `pay.closed` message `CONSUMED` |

Two real bugs were found and fixed during that verification:

1. **`pay_url` column overflow**: an Alipay checkout URL carries a URL-encoded RSA2 signature and
   measured 518–530 characters, so the original `VARCHAR(512)` failed intermittently with
   `Value too long`. The number of `+` and `/` characters in the Base64 signature varies, so the bug
   appeared and disappeared at random. Widened to 1024.
2. **`fail_reason` can be too long**: failure reasons are often long third-party or JDBC exception
   strings, and exceeding `VARCHAR(255)` meant "recording the failure itself failed". Now truncated
   in `PayOrderRepository` before persisting.

---

## 9. Known boundaries and trade-offs

- The third-party channels are a **local simulator**, not real sandbox network calls. Swapping in
  real SDKs only touches the implementations under `channel/adapter`; nothing above changes.
- The MQ is in-memory, so the queue is lost on restart — but the outbox table survives, and the
  compensation task republishes afterwards. That is precisely the value of the transactional outbox
  pattern.
- Scheduled tasks have no distributed mutual exclusion; a multi-instance deployment needs a Redisson
  lock or XXL-JOB sharding.
- H2 is for the local sandbox. Moving to MySQL means changing the datasource and the `DbType` passed
  to `PaginationInnerInterceptor`.
- The module split stops at a compile-time boundary inside one process: modules still call each other
  directly and share a single datasource and a single local transaction. Real service extraction
  would need RPC between modules and Seata or reliable messaging across databases — a deliberate
  trade-off, since forcing distributed transactions on a monolith only adds complexity.
