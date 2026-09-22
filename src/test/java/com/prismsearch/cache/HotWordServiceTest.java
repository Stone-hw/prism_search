package com.prismsearch.cache;

import com.prismsearch.common.Constants;
import com.prismsearch.config.PrismsearchProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HotWordServiceTest {

    private StringRedisTemplate redis;
    private PrismsearchProperties props;
    private MeterRegistry meters;
    private ExecutorService executor;
    private HotWordService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        props = new PrismsearchProperties();
        props.getSuggest().setEnabled(true);
        props.getSuggest().getHotWords().addAll(List.of("spring boot", "redis", "kubernetes"));
        meters = new SimpleMeterRegistry();
        executor = Executors.newVirtualThreadPerTaskExecutor();

        service = new HotWordService(redis, props, meters, executor);
    }

    @Test
    void suggestFallsBackToStaticListWhenRedisEmpty() {
        ZSetOperations<String, String> zOps = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zOps);
        when(zOps.reverseRangeWithScores(eq(Constants.HOTWORD_AUTO_KEY), eq(0L), eq(-1L)))
                .thenReturn(null);
        when(zOps.reverseRangeWithScores(eq(Constants.HOTWORD_MANUAL_KEY), eq(0L), eq(-1L)))
                .thenReturn(null);

        List<String> result = service.suggest("s", 5);
        // Should fall back to static list: "spring boot" starts with "s"
        assertTrue(result.contains("spring boot"));
    }

    @Test
    void suggestReturnsStaticListWhenDisabled() {
        props.getSuggest().setEnabled(false);
        List<String> result = service.suggest("r", 5);
        assertTrue(result.contains("redis"));
        assertFalse(result.contains("kubernetes"));
    }

    @Test
    void suggestReturnsStaticListWhenRedisThrows() {
        when(redis.opsForZSet()).thenThrow(new RuntimeException("Redis down"));

        List<String> result = service.suggest("k", 5);
        assertTrue(result.contains("kubernetes"));
    }

    @Test
    void staticSuggestFiltersByPrefix() {
        props.getSuggest().setEnabled(false);
        List<String> result = service.suggest("k", 5);
        assertEquals(1, result.size());
        assertEquals("kubernetes", result.get(0));
    }

    @Test
    void staticSuggestReturnsAllWhenPrefixEmpty() {
        props.getSuggest().setEnabled(false);
        List<String> result = service.suggest("", 10);
        assertEquals(3, result.size());
    }

    @Test
    void recordSearchDoesNothingWhenDisabled() {
        props.getSuggest().setEnabled(false);
        // Should not throw
        service.recordSearch("test query");
    }

    @Test
    void recordSearchDoesNothingForBlankQuery() {
        // Should not throw
        service.recordSearch("");
        service.recordSearch(null);
        service.recordSearch("   ");
    }
}
