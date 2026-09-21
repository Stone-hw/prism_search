package com.mysearch.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Virtual-thread executor used to fan out Provider calls.
 * JDK 21 virtual threads scale to thousands of concurrent blocking HTTP calls
 * without tuning core/max pool sizes.
 */
@Configuration
public class ExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutorConfig.class);

    private ExecutorService providerExecutor;

    @Bean(name = "providerExecutor")
    public ExecutorService providerExecutor() {
        this.providerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("Initialized virtual-thread executor for Provider fan-out");
        return this.providerExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (providerExecutor == null) {
            return;
        }
        providerExecutor.shutdown();
        try {
            if (!providerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                providerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            providerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
