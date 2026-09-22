package com.prismsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PrismSearch application entry point.
 * Multi-provider meta search engine (SearXNG + Google + Bing).
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.prismsearch")
@EnableScheduling
public class PrismsearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismsearchApplication.class, args);
    }
}
