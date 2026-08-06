-- ===================================================================
-- Unified payment gateway DDL for H2 (MODE=MySQL, avoiding H2-dialect-specific syntax)
-- ===================================================================

-- Channel configuration: merchant id / keys / webhook URL / on-off switch / routing rules
DROP TABLE IF EXISTS pay_channel_config;
CREATE TABLE pay_channel_config
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_type    VARCHAR(20)    NOT NULL,
    channel_name    VARCHAR(64)    NOT NULL,
    merchant_id     VARCHAR(64),
    app_id          VARCHAR(64),
    private_key     VARCHAR(4000),
    public_key      VARCHAR(4000),
    api_key         VARCHAR(128),
    aes_key         VARCHAR(128),
    sign_type       VARCHAR(20)    DEFAULT 'MD5' NOT NULL,
    gateway_url     VARCHAR(255),
    notify_url      VARCHAR(255),
    enabled         INT            DEFAULT 1 NOT NULL,
    priority        INT            DEFAULT 0 NOT NULL,
    min_amount      DECIMAL(18, 2) DEFAULT 0.00 NOT NULL,
    max_amount      DECIMAL(18, 2) DEFAULT 99999999.00 NOT NULL,
    support_regions VARCHAR(255),
    fee_rate        DECIMAL(10, 6) DEFAULT 0.006000 NOT NULL,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Pay order: state machine WAIT_PAY -> SUCCESS/FAIL/CLOSED -> PART_REFUNDED/REFUNDED
DROP TABLE IF EXISTS pay_order;
CREATE TABLE pay_order
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    pay_no           VARCHAR(64)    NOT NULL,
    biz_system       VARCHAR(32)    NOT NULL,
    biz_order_no     VARCHAR(64)    NOT NULL,
    user_id          VARCHAR(64)    NOT NULL,
    channel_type     VARCHAR(20)    NOT NULL,
    subject          VARCHAR(128)   NOT NULL,
    body             VARCHAR(512),
    amount           DECIMAL(18, 2) NOT NULL,
    paid_amount      DECIMAL(18, 2) DEFAULT 0.00 NOT NULL,
    refunded_amount  DECIMAL(18, 2) DEFAULT 0.00 NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    region           VARCHAR(32)    DEFAULT 'CN' NOT NULL,
    client_ip        VARCHAR(64),
    channel_trade_no VARCHAR(64),
    -- Checkout URLs can be long: an Alipay page-pay URL carries a URL-encoded RSA2 signature,
    -- measured at 500+ characters in practice

    pay_url          VARCHAR(1024),
    expire_time      TIMESTAMP      NOT NULL,
    pay_time         TIMESTAMP,
    close_time       TIMESTAMP,
    fail_reason      VARCHAR(255),
    version          INT            DEFAULT 0 NOT NULL,
    create_time      TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time      TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uk_pay_no ON pay_order (pay_no);
CREATE UNIQUE INDEX uk_biz_order ON pay_order (biz_system, biz_order_no);
CREATE INDEX idx_pay_status ON pay_order (status, expire_time);

-- Refund order
DROP TABLE IF EXISTS refund_order;
CREATE TABLE refund_order
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no         VARCHAR(64)    NOT NULL,
    pay_no            VARCHAR(64)    NOT NULL,
    biz_refund_no     VARCHAR(64)    NOT NULL,
    channel_type      VARCHAR(20)    NOT NULL,
    amount            DECIMAL(18, 2) NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    reason            VARCHAR(255),
    channel_refund_no VARCHAR(64),
    refund_time       TIMESTAMP,
    fail_reason       VARCHAR(255),
    version           INT            DEFAULT 0 NOT NULL,
    create_time       TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time       TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uk_refund_no ON refund_order (refund_no);
CREATE UNIQUE INDEX uk_biz_refund_no ON refund_order (biz_refund_no);
CREATE INDEX idx_refund_pay_no ON refund_order (pay_no);

-- Webhook log: signature verification result / duplicate flag / latency
DROP TABLE IF EXISTS webhook_log;
CREATE TABLE webhook_log
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_type     VARCHAR(20)   NOT NULL,
    webhook_type    VARCHAR(20)   NOT NULL,
    out_trade_no     VARCHAR(64),
    channel_trade_no VARCHAR(64),
    raw_body         VARCHAR(4000),
    sign_verified    INT           DEFAULT 0 NOT NULL,
    result           VARCHAR(20)   NOT NULL,
    remark           VARCHAR(512),
    cost_ms          BIGINT,
    create_time      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX idx_wh_trade ON webhook_log (out_trade_no);

-- Outbox table: eventually-consistent delivery of payment results
DROP TABLE IF EXISTS local_message;
CREATE TABLE local_message
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id      VARCHAR(64)   NOT NULL,
    topic           VARCHAR(64)   NOT NULL,
    biz_key         VARCHAR(64)   NOT NULL,
    payload         VARCHAR(4000) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    retry_count     INT           DEFAULT 0 NOT NULL,
    max_retry       INT           DEFAULT 5 NOT NULL,
    next_retry_time TIMESTAMP,
    last_error      VARCHAR(512),
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uk_message_id ON local_message (message_id);
CREATE INDEX idx_msg_status ON local_message (status, next_retry_time);

-- Balance accounts (used by the balance payment channel)
DROP TABLE IF EXISTS user_account;
CREATE TABLE user_account
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       VARCHAR(64)    NOT NULL,
    balance       DECIMAL(18, 2) DEFAULT 0.00 NOT NULL,
    frozen_amount DECIMAL(18, 2) DEFAULT 0.00 NOT NULL,
    version       INT            DEFAULT 0 NOT NULL,
    create_time   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uk_account_user ON user_account (user_id);

-- Money ledger (unique on biz_no + direction, the database-level idempotency backstop)
DROP TABLE IF EXISTS account_flow;
CREATE TABLE account_flow
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       VARCHAR(64)    NOT NULL,
    biz_no        VARCHAR(64)    NOT NULL,
    direction     VARCHAR(10)    NOT NULL,
    amount        DECIMAL(18, 2) NOT NULL,
    balance_after DECIMAL(18, 2) NOT NULL,
    remark        VARCHAR(255),
    create_time   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uk_flow_biz ON account_flow (biz_no, direction);

-- Rows parsed out of the channel bill file
DROP TABLE IF EXISTS channel_bill;
CREATE TABLE channel_bill
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_date        VARCHAR(10)    NOT NULL,
    channel_type     VARCHAR(20)    NOT NULL,
    out_trade_no     VARCHAR(64)    NOT NULL,
    channel_trade_no VARCHAR(64)    NOT NULL,
    amount           DECIMAL(18, 2) NOT NULL,
    fee              DECIMAL(18, 2) DEFAULT 0.00 NOT NULL,
    trade_status     VARCHAR(20)    NOT NULL,
    trade_time       TIMESTAMP,
    create_time      TIMESTAMP      DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX idx_bill_date ON channel_bill (bill_date, channel_type);

-- Discrepancy records: missing entry / amount mismatch / status mismatch / one-sided entry
DROP TABLE IF EXISTS reconcile_diff;
CREATE TABLE reconcile_diff
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_date        VARCHAR(10) NOT NULL,
    channel_type     VARCHAR(20) NOT NULL,
    out_trade_no     VARCHAR(64),
    channel_trade_no VARCHAR(64),
    local_amount     DECIMAL(18, 2),
    channel_amount   DECIMAL(18, 2),
    local_status     VARCHAR(20),
    channel_status   VARCHAR(20),
    diff_type        VARCHAR(30) NOT NULL,
    handled          INT         DEFAULT 0 NOT NULL,
    remark           VARCHAR(512),
    create_time      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX idx_diff_date ON reconcile_diff (bill_date, channel_type);

-- Reconciliation job execution results
DROP TABLE IF EXISTS reconcile_task_log;
CREATE TABLE reconcile_task_log
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_date     VARCHAR(10) NOT NULL,
    channel_type  VARCHAR(20) NOT NULL,
    local_count   INT         DEFAULT 0 NOT NULL,
    channel_count INT         DEFAULT 0 NOT NULL,
    diff_count    INT         DEFAULT 0 NOT NULL,
    status        VARCHAR(20) NOT NULL,
    cost_ms       BIGINT,
    remark        VARCHAR(512),
    create_time   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL
);
