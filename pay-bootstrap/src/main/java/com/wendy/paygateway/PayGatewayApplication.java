package com.wendy.paygateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the unified payment gateway.
 *
 * <p>Local sandbox: an in-memory H2 database, a simulated third-party channel and a simulated MQ,
 * so no external middleware is required.
 *
 * <p>API docs: http://localhost:8080/doc.html — H2 console: http://localhost:8080/h2-console
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan("com.wendy.paygateway.**.mapper")
public class PayGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayGatewayApplication.class, args);
    }
}
