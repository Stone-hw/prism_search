package com.prismsearch.rank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * URL normalization per TECH_DESIGN.md appendix B.2:
 * - lowercase scheme and host
 * - strip leading "www."
 * - drop tracking parameters (utm_*, fbclid, gclid, ...)
 * - remove trailing slash (except root)
 * - preserve remaining query parameters in sorted-stable insertion order
 */
@Component
public class UrlNormalizer {

    private static final Logger log = LoggerFactory.getLogger(UrlNormalizer.class);

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "fbclid", "gclid", "ref", "ref_src", "spm", "from", "source",
            "igshid", "mcid", "vero_id", "wt_mc", "yclid", "msclkid",
            "dclid", "gbraid", "wbraid", "_hsenc", "_hsmi", "mkt_tok"
    );

    private static final String UTM_PREFIX = "utm_";

    /**
     * @return normalized URL, or the original string when parsing fails (fail-open).
     */
    public String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        String trimmed = rawUrl.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                // relative or malformed; keep as-is after basic cleanup
                return trimmed;
            }

            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            Map<String, List<String>> params = parseQuery(uri.getRawQuery());
            params.keySet().removeIf(k -> {
                String lower = k.toLowerCase(Locale.ROOT);
                return lower.startsWith(UTM_PREFIX) || TRACKING_PARAMS.contains(lower);
            });

            String query = buildQuery(params);
            String fragment = ""; // drop fragments (they are client-side only)

            StringBuilder sb = new StringBuilder(96);
            sb.append(scheme).append("://").append(host);
            if (uri.getPort() != -1 && uri.getPort() != defaultPort(scheme)) {
                sb.append(':').append(uri.getPort());
            }
            sb.append(path);
            if (!query.isEmpty()) {
                sb.append('?').append(query);
            }
            sb.append(fragment);
            return sb.toString();
        } catch (URISyntaxException e) {
            log.debug("URL parse failed, keeping raw: {} ({})", trimmed, e.getMessage());
            return trimmed;
        }
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, StandardCharsets.UTF_8);
                v = URLDecoder.decode(v, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // keep raw on malformed % sequences
            }
            map.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return map;
    }

    private static String buildQuery(Map<String, List<String>> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            for (String v : e.getValue()) {
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
                if (!v.isEmpty()) {
                    sb.append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
                }
            }
        }
        return sb.toString();
    }
}
