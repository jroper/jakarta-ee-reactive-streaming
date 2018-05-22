package com.example.auction.search.impl;

import akka.Done;
import com.example.auction.bidding.api.BidEvent;
import com.example.auction.item.api.ItemEvent;
import com.example.auction.search.IndexedStore;
import com.example.elasticsearch.IndexedItem;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class BrokerEventConsumer {

    private final IndexedStore indexedStore;

    @Inject
    public BrokerEventConsumer(IndexedStore indexedStore) {
        this.indexedStore = indexedStore;
    }

    @Incoming(topic = "item-ItemEvent")
    public CompletionStage<?> consumeItemEvents(ItemEvent event) {
        return toDocument(event).map(indexedStore::store).orElse(CompletableFuture.completedFuture(Done.getInstance()));
    }

    @Incoming(topic = "bidding-BidEvent")
    public CompletionStage<?> consumeBidEvents(BidEvent event) {
        return toDocument(event).map(indexedStore::store).orElse(CompletableFuture.completedFuture(Done.getInstance()));
    }

    private Optional<IndexedItem> toDocument(ItemEvent event) {
        if (event instanceof ItemEvent.AuctionStarted) {
            ItemEvent.AuctionStarted started = (ItemEvent.AuctionStarted) event;
            return Optional.of(IndexedItem.forAuctionStart(started.getItemId(), started.getStartDate(), started.getEndDate()));
        } else if (event instanceof ItemEvent.AuctionFinished) {
            ItemEvent.AuctionFinished finish = (ItemEvent.AuctionFinished) event;
            return Optional.of(IndexedItem.forAuctionFinish(finish.getItemId(), finish.getItem()));
        } else if (event instanceof ItemEvent.ItemUpdated) {
            ItemEvent.ItemUpdated details = (ItemEvent.ItemUpdated) event;
            return Optional.of(IndexedItem.forItemDetails(
                details.getItemId(),
                details.getCreator(),
                details.getTitle(),
                details.getDescription(),
                details.getItemStatus(),
                details.getCurrencyId()));
        } else {
            return Optional.empty();
        }
    }

    private Optional<IndexedItem> toDocument(BidEvent event) {
        if (event instanceof BidEvent.BidPlaced) {
            BidEvent.BidPlaced bid = (BidEvent.BidPlaced) event;
            return Optional.of(IndexedItem.forPrice(bid.getItemId(), bid.getBid().getPrice()));
        } else if (event instanceof BidEvent.BiddingFinished) {
            BidEvent.BiddingFinished bid = (BidEvent.BiddingFinished) event;
            return Optional.of(bid.getWinningBid()
                .map(winning -> IndexedItem.forWinningBid(bid.getItemId(), winning.getPrice(), winning.getBidder()))
                .orElse(IndexedItem.forPrice(bid.getItemId(), 0)));
        } else {
            return Optional.empty();
        }
    }
}
