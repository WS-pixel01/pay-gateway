package com.wendy.paygateway.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Knife4j / OpenAPI3 docs: http://localhost:8080/doc.html */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI payGatewayOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Unified Payment Gateway API")
                .version("1.0.0")
                .description("Multi-channel checkout / refund / webhook / reconciliation. "
                        + "The local sandbox runs on H2 with a simulated channel and a simulated MQ."));
    }
}
