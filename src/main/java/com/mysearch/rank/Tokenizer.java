package com.mysearch.rank;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tokenizer used by SimHash. Routes Chinese text through HanLP and English through
 * whitespace splitting plus a small stopword filter.
 */
@Component
public class Tokenizer {

    private static final Logger log = LoggerFactory.getLogger(Tokenizer.class);

    private static final Set<String> EN_STOPWORDS = Set.of(
            "the", "a", "an", "of", "to", "and", "or", "in", "on", "for", "with",
            "is", "are", "was", "were", "be", "been", "being", "at", "by", "from",
            "as", "into", "about", "that", "this", "these", "those", "it", "its"
    );

    /** Chinese punctuation and general whitespace to strip before splitting. */
    private static final String PUNCT_REGEX = "[\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF\\s]+";

    private volatile boolean hanlpReady = false;

    /**
     * Split text into normalized lowercase tokens.
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = text.replaceAll(PUNCT_REGEX, " ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }

        if (containsChinese(cleaned)) {
            try {
                List<Term> terms = HanLP.segment(cleaned);
                List<String> out = new ArrayList<>(terms.size());
                for (Term t : terms) {
                    String w = t.word.trim().toLowerCase(Locale.ROOT);
                    if (w.isEmpty() || w.length() == 1 && !containsChinese(w)) {
                        continue;
                    }
                    if (EN_STOPWORDS.contains(w)) {
                        continue;
                    }
                    out.add(w);
                }
                hanlpReady = true;
                return out;
            } catch (Throwable t) {
                // HanLP dictionary missing or classloader issue - fall back to whitespace.
                if (hanlpReady) {
                    log.warn("HanLP segmentation failed, falling back to whitespace: {}", t.toString());
                } else {
                    log.debug("HanLP not available, using whitespace tokenizer: {}", t.toString());
                }
            }
        }
        return englishTokens(cleaned);
    }

    private List<String> englishTokens(String cleaned) {
        String[] parts = cleaned.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p.isEmpty() || EN_STOPWORDS.contains(p)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static boolean containsChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }
}
