package com.prismsearch.provider;

import com.prismsearch.model.RawSearchResult;
import com.prismsearch.model.SearchRequest;

import java.util.List;

/**
 * Uniform contract implemented by all upstream search engines.
 * See TECH_DESIGN.md section 2.5.
 */
public interface SearchProvider {

    /** Engine identifier: searxng / google / bing. */
    String name();

    /** Whether the provider should participate in the current search. */
    boolean enabled();

    /** Per-provider weight used by RRF fusion. */
    double weight();

    /** Per-call timeout in milliseconds. */
    long timeoutMs();

    /**
     * Execute the search. Implementations must return an empty list rather than
     * {@code null} when there is nothing to return, and must throw on hard failure
     * (the caller catches everything).
     */
    List<RawSearchResult> search(SearchRequest request);
}
