package com.example.auction.search.impl;

import com.example.auction.bidding.api.BidEvent;
import com.example.auction.item.api.ItemEvent;
import com.example.auction.search.IndexedStore;
import com.example.elasticsearch.IndexedItem;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.eclipse.microprofile.reactive.streams.operators.SubscriberBuilder;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class BrokerEventConsumer {

    private final IndexedStore indexedStore;

    @Inject
    public BrokerEventConsumer(IndexedStore indexedStore) {
        this.indexedStore = indexedStore;
    }

    @Incoming("item-ItemEvent")
    public SubscriberBuilder<Message<ItemEvent>, ?> consumeItemEvents() {

        return ReactiveStreams.<Message<ItemEvent>>builder()
            .map(this::itemEventToDocument)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .flatMapCompletionStage(message ->
                indexedStore.store(message.getPayload()).thenApply(done -> message)
            )
            .flatMapCompletionStage(Message::ack)
            .forEach(done -> {});

    }

    @Incoming("bidding-BidEvent")
    public SubscriberBuilder<Message<BidEvent>, ?> consumeBidEvents() {

        return ReactiveStreams.<Message<BidEvent>>builder()
            .map(this::bidEventToDocument)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .flatMapCompletionStage( message ->
                indexedStore.store(message.getPayload()).thenApply(done -> message)
            )
            .flatMapCompletionStage(Message::ack)
            .forEach(done -> {});

    }

    private Optional<Message<IndexedItem>> itemEventToDocument(Message<ItemEvent> message) {
        ItemEvent event = message.getPayload();
        Optional<IndexedItem> maybeItem;
        if (event instanceof ItemEvent.AuctionStarted) {
            ItemEvent.AuctionStarted started = (ItemEvent.AuctionStarted) event;
            maybeItem = Optional.of(IndexedItem.forAuctionStart(started.getItemId(), started.getStartDate(), started.getEndDate()));
        } else if (event instanceof ItemEvent.AuctionFinished) {
            ItemEvent.AuctionFinished finish = (ItemEvent.AuctionFinished) event;
            maybeItem = Optional.of(IndexedItem.forAuctionFinish(finish.getItemId(), finish.getItem()));
        } else if (event instanceof ItemEvent.ItemUpdated) {
            ItemEvent.ItemUpdated details = (ItemEvent.ItemUpdated) event;
            maybeItem = Optional.of(IndexedItem.forItemDetails(
                details.getItemId(),
                details.getCreator(),
                details.getTitle(),
                details.getDescription(),
                details.getItemStatus(),
                details.getCurrencyId()));
        } else {
            maybeItem = Optional.empty();
        }
        return maybeItem.map(item -> Message.of(item, message::ack));
    }

    private Optional<Message<IndexedItem>> bidEventToDocument(Message<BidEvent> message) {
        BidEvent event = message.getPayload();
        Optional<IndexedItem> maybeItem;
        if (event instanceof BidEvent.BidPlaced) {
            BidEvent.BidPlaced bid = (BidEvent.BidPlaced) event;
            maybeItem = Optional.of(IndexedItem.forPrice(bid.getItemId(), bid.getBid().getPrice()));
        } else if (event instanceof BidEvent.BiddingFinished) {
            BidEvent.BiddingFinished bid = (BidEvent.BiddingFinished) event;
            maybeItem = Optional.of(bid.getWinningBid()
                .map(winning -> IndexedItem.forWinningBid(bid.getItemId(), winning.getPrice(), winning.getBidder()))
                .orElse(IndexedItem.forPrice(bid.getItemId(), 0)));
        } else {
            maybeItem = Optional.empty();
        }
        return maybeItem.map(item -> Message.of(item, message::ack));
    }
}
