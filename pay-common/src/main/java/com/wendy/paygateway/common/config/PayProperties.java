package com.wendy.paygateway.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Every tunable of the payment gateway, gathered under {@code pay.*}. */
@Data
@Component
@ConfigurationProperties(prefix = "pay")
public class PayProperties {

    private Infra infra = new Infra();
    private Auth auth = new Auth();
    private Order order = new Order();
    private Ratelimit ratelimit = new Ratelimit();
    private Mock mock = new Mock();
    private Reconcile reconcile = new Reconcile();

    @Data
    public static class Infra {
        /** memory: zero-dependency local sandbox; redis: the Redisson-backed implementations. */
        private String type = "memory";
        private Redis redis = new Redis();
    }

    @Data
    public static class Redis {
        private String address = "redis://127.0.0.1:6379";
        private String password;
        private int database = 0;
    }

    @Data
    public static class Auth {
        private boolean enabled = false;
        private String secret = "pay-gateway-demo-secret-key-must-be-at-least-32-bytes!!";
        private int expireMinutes = 120;
    }

    @Data
    public static class Order {
        /** Pay order lifetime in minutes; it is closed automatically once it expires. */
        private int expireMinutes = 30;
        private String closeTaskCron = "0 */1 * * * ?";
    }

    @Data
    public static class Ratelimit {
        private boolean enabled = true;
    }

    @Data
    public static class Mock {
        /** Base URL the simulated channel delivers webhooks to, i.e. this gateway itself. */
        private String selfBaseUrl = "http://localhost:8080";
        private int webhookDelaySeconds = 3;
        /** Deliberately push the same webhook twice, to prove idempotency works. */
        private boolean duplicateWebhook = true;
        /** 0-100, the simulated payment failure rate. */
        private int failRate = 0;
        /** Make the upstream system fail the first N notifications, to demo MQ retry + outbox compensation. */
        private int notifyFailTimes = 0;
    }

    @Data
    public static class Reconcile {
        private String billDir = "./data/bills";
        private String cron = "0 0 2 * * ?";
        /** Inject discrepancies into the generated bill file so the diff records have something to show. */
        private boolean injectAnomaly = true;
    }
}
