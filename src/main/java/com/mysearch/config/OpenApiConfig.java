package com.mysearch.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI metadata.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mysearchOpenApi() {
        return new OpenAPI().info(new Info()
                .title("MySearch API")
                .version("0.1.0")
                .description("Multi-provider meta search engine (SearXNG + Google + Bing)")
                .contact(new Contact().name("MySearch"))
                .license(new License().name("MIT")));
    }
}
