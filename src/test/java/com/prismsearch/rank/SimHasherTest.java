package com.prismsearch.rank;

import com.prismsearch.config.PrismsearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimHasherTest {

    private SimHasher simHasher;

    @BeforeEach
    void setUp() {
        Tokenizer tokenizer = new Tokenizer();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        PrismsearchProperties props = new PrismsearchProperties();
        simHasher = new SimHasher(tokenizer, redis, props);
    }

    @Test
    void identicalTextsHaveZeroDistance() {
        String t = "Spring Boot makes it easy to create stand-alone production-grade applications.";
        long a = simHasher.compute(t);
        long b = simHasher.compute(t);
        assertEquals(a, b);
        assertEquals(0, simHasher.hammingDistance(a, b));
    }

    @Test
    void similarTextsHaveSmallDistance() {
        String a = "Spring Boot makes it easy to create stand-alone, production-grade Spring-based applications "
                + "that you can run. We take an opinionated view of the Spring platform and third-party libraries, "
                + "so you can get started with minimum fuss. Most Spring Boot applications need very little Spring configuration.";
        String b = "Spring Boot makes it easy to build stand-alone, production-grade Spring-based applications "
                + "that you can run. We take an opinionated view of the Spring platform and third-party libraries, "
                + "so you can get started with minimum fuss. Most Spring Boot applications need very little Spring configuration.";
        long ha = simHasher.compute(a);
        long hb = simHasher.compute(b);
        int dist = simHasher.hammingDistance(ha, hb);
        assertTrue(dist <= 8, "expected small hamming distance for near-identical long texts, got " + dist);
    }

    @Test
    void differentTextsHaveLargeDistance() {
        String a = "Spring Boot makes it easy to create stand-alone, production-grade Spring-based applications "
                + "that you can run. We take an opinionated view of the Spring platform and third-party libraries.";
        String b = "Kubernetes is a portable, extensible open-source platform for managing containerized workloads "
                + "and services, that facilitates both declarative configuration and automation. It has a large ecosystem.";
        long ha = simHasher.compute(a);
        long hb = simHasher.compute(b);
        int dist = simHasher.hammingDistance(ha, hb);
        assertTrue(dist > 10, "expected large hamming distance for unrelated texts, got " + dist);
    }

    @Test
    void chineseTextsAreTokenized() {
        String a = "Spring Boot 是一款用于构建生产级应用的框架";
        String b = "Spring Boot 是一款用于构建生产级 Spring 应用的框架";
        long ha = simHasher.compute(a);
        long hb = simHasher.compute(b);
        int dist = simHasher.hammingDistance(ha, hb);
        assertTrue(dist <= 8, "expected modest distance for slightly-different Chinese texts, got " + dist);
    }

    @Test
    void emptyTextYieldsZero() {
        assertEquals(0L, simHasher.compute(""));
        assertEquals(0L, simHasher.compute(null));
    }

    @Test
    void hammingDistanceIsSymmetric() {
        long a = simHasher.compute("hello world");
        long b = simHasher.compute("goodbye world");
        assertEquals(simHasher.hammingDistance(a, b), simHasher.hammingDistance(b, a));
    }

    @Test
    void murmurHashIsDeterministic() {
        assertEquals(SimHasher.murmurHash64("abc"), SimHasher.murmurHash64("abc"));
        assertTrue(SimHasher.murmurHash64("abc") != SimHasher.murmurHash64("abd"));
    }
}
