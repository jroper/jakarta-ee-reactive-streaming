package com.example.auction.search.impl;

import akka.Done;
import com.example.auction.search.IndexedStore;
import com.example.elasticsearch.*;
import com.lightbend.lagom.javadsl.client.cdi.LagomServiceClient;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.concurrent.CompletionStage;

/**
 *
 */
@ApplicationScoped
public class IndexedStoreImpl implements IndexedStore {

    public static final String INDEX_NAME = "auction-items";

    private final Elasticsearch elasticsearch;

    @Inject
    public IndexedStoreImpl(@LagomServiceClient Elasticsearch elasticsearch) {
        this.elasticsearch = elasticsearch;
    }


    public CompletionStage<Done> store(IndexedItem document) {
        return elasticsearch.updateIndex(INDEX_NAME, document.getItemId()).invoke(new UpdateIndexItem(document));
    }

    public CompletionStage<SearchResult> search(QueryRoot query) {
        return elasticsearch.search(INDEX_NAME).invoke(query);
    }
}
