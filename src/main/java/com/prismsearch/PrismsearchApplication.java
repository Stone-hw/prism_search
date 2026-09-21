package com.prismsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * PrismSearch application entry point.
 * Multi-provider meta search engine (SearXNG + Google + Bing).
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.prismsearch")
public class PrismsearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismsearchApplication.class, args);
    }
}
