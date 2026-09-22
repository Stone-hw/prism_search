package com.prismsearch.rank;

/**
 * Lightweight language detector based on Unicode character ratios.
 * No external dependencies — uses character block analysis of title + snippet text.
 */
public final class LanguageDetector {

    private LanguageDetector() {
    }

    /**
     * Detect the dominant language of the given text.
     *
     * @return "zh" for Chinese, "ja" for Japanese, "ko" for Korean, "en" for English/Latin,
     *         or "unknown" when text is null/empty or too short to determine.
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }

        int cjk = 0;
        int hiragana = 0;
        int katakana = 0;
        int hangul = 0;
        int latin = 0;
        int total = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || Character.isDigit(ch)) {
                continue;
            }
            total++;

            if (ch >= '\u4E00' && ch <= '\u9FFF'
                    || ch >= '\u3400' && ch <= '\u4DBF'
                    || ch >= '\uF900' && ch <= '\uFAFF') {
                cjk++;
            } else if (ch >= '\u3040' && ch <= '\u309F') {
                hiragana++;
            } else if (ch >= '\u30A0' && ch <= '\u30FF') {
                katakana++;
            } else if (ch >= '\uAC00' && ch <= '\uD7AF') {
                hangul++;
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '\u00C0' && ch <= '\u024F')) {
                latin++;
            }
        }

        if (total < 3) {
            return "unknown";
        }

        // Japanese: has hiragana or katakana (even if CJK is also present)
        if (hiragana + katakana > 0 && (hiragana + katakana) * 3 > total) {
            return "ja";
        }
        // Korean
        if (hangul > 0 && hangul * 3 > total) {
            return "ko";
        }
        // Chinese: CJK characters dominant
        if (cjk > 0 && cjk * 2 > total) {
            return "zh";
        }
        // English / Latin-based
        if (latin > 0 && latin * 2 > total) {
            return "en";
        }

        return "unknown";
    }

    /**
     * Detect language from a combination of title and snippet.
     */
    public static String detect(String title, String snippet) {
        String combined = (title == null ? "" : title) + " " + (snippet == null ? "" : snippet);
        return detect(combined.trim());
    }

    /**
     * Map a locale string like "zh-CN", "en-US" to its ISO 639-1 language code.
     */
    public static String toLangCode(String locale) {
        if (locale == null || locale.isBlank() || "auto".equalsIgnoreCase(locale)) {
            return null;
        }
        int idx = locale.indexOf('-');
        return (idx > 0 ? locale.substring(0, idx) : locale).toLowerCase();
    }
}
