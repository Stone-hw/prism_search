package com.mysearch.service;

import com.mysearch.model.SearchRequest;
import com.mysearch.model.SearchResponse;

/**
 * Core search orchestration contract.
 */
public interface SearchOrchestrator {

    /**
     * Execute the full pipeline: cache lookup -> concurrent provider fan-out ->
     * normalize/dedup/rank -> pagination -> async cache write.
     */
    SearchResponse search(SearchRequest request);
}
