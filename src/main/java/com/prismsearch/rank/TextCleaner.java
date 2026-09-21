package com.prismsearch.rank;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Strip HTML tags, collapse whitespace and drop control characters from titles/snippets.
 */
@Component
public class TextCleaner {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_ENTITY_NBSP = Pattern.compile("&nbsp;?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_AMP = Pattern.compile("&amp;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_LT = Pattern.compile("&lt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_GT = Pattern.compile("&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_QUOT = Pattern.compile("&quot;", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\n\t]]");

    public String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw;
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = HTML_ENTITY_NBSP.matcher(s).replaceAll(" ");
        s = HTML_ENTITY_AMP.matcher(s).replaceAll("&");
        s = HTML_ENTITY_LT.matcher(s).replaceAll("<");
        s = HTML_ENTITY_GT.matcher(s).replaceAll(">");
        s = HTML_ENTITY_QUOT.matcher(s).replaceAll("\"");
        s = CONTROL_CHARS.matcher(s).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        return s.trim();
    }
}
