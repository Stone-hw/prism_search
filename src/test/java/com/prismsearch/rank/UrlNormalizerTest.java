package com.prismsearch.rank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlNormalizerTest {

    private final UrlNormalizer normalizer = new UrlNormalizer();

    @Test
    void lowercasesSchemeAndHost() {
        assertEquals("https://example.com/path",
                normalizer.normalize("HTTPS://EXAMPLE.COM/path"));
    }

    @Test
    void stripsLeadingWww() {
        assertEquals("https://example.com/a",
                normalizer.normalize("https://www.example.com/a"));
    }

    @Test
    void removesTrailingSlashExceptRoot() {
        assertEquals("https://example.com/", normalizer.normalize("https://example.com/"));
        assertEquals("https://example.com/a", normalizer.normalize("https://example.com/a/"));
        assertEquals("https://example.com/a/b", normalizer.normalize("https://example.com/a/b/"));
    }

    @Test
    void dropsUtmAndCommonTrackingParams() {
        String raw = "https://example.com/a?utm_source=news&utm_medium=email&fbclid=xyz&id=42";
        String expected = "https://example.com/a?id=42";
        assertEquals(expected, normalizer.normalize(raw));
    }

    @Test
    void preservesNonTrackingParams() {
        assertEquals("https://example.com/search?q=hello&page=2",
                normalizer.normalize("https://example.com/search?q=hello&page=2"));
    }

    @Test
    void dropsDefaultPort() {
        assertEquals("https://example.com/a",
                normalizer.normalize("https://example.com:443/a"));
        assertEquals("http://example.com/a",
                normalizer.normalize("http://example.com:80/a"));
    }

    @Test
    void keepsNonDefaultPort() {
        assertEquals("https://example.com:8443/a",
                normalizer.normalize("https://example.com:8443/a"));
    }

    @Test
    void dropsFragment() {
        assertEquals("https://example.com/a",
                normalizer.normalize("https://example.com/a#section-2"));
    }

    @Test
    void addsRootPathWhenMissing() {
        assertEquals("https://example.com/", normalizer.normalize("https://example.com"));
    }

    @Test
    void returnsBlankForBlank() {
        assertEquals("", normalizer.normalize(""));
        assertEquals(null, normalizer.normalize(null));
    }

    @Test
    void failsOpenOnMalformedUrl() {
        String malformed = "not a url at all";
        String out = normalizer.normalize(malformed);
        // We keep the input as-is when it can't be parsed as absolute URI.
        assertTrue(out.equals(malformed) || out.startsWith("not"));
    }
}
