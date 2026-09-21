package com.mysearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MySearch application entry point.
 * Multi-provider meta search engine (SearXNG + Google + Bing).
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.mysearch")
public class MysearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MysearchApplication.class, args);
    }
}
