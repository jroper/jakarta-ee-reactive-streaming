package com.example.auction.bidding.impl;

import akka.Done;
import akka.NotUsed;
import com.example.auction.bidding.api.*;
import com.example.auction.bidding.api.Bid;
import com.example.auction.bidding.api.PlaceBid;
import com.example.auction.bidding.impl.AuctionCommand.GetAuction;
import com.example.auction.item.api.ItemEvent;
import com.lightbend.lagom.javadsl.api.ServiceCall;
import com.lightbend.lagom.javadsl.microprofile.messaging.EventLog;
import com.lightbend.lagom.javadsl.microprofile.messaging.EventLogConsumer;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRef;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRegistry;
import com.lightbend.lagom.javadsl.server.cdi.LagomService;
import com.lightbend.microprofile.reactive.messaging.kafka.Kafka;
import com.lightbend.microprofile.reactive.messaging.kafka.KafkaProducerMessage;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.eclipse.microprofile.reactive.streams.operators.ProcessorBuilder;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.pcollections.PSequence;
import org.pcollections.TreePVector;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static com.example.auction.security.ServerSecurity.authenticated;

/**
 * Implementation of the bidding service.
 */
@ApplicationScoped
@LagomService
public class BiddingServiceImpl implements BiddingService {

    private final PersistentEntityRegistry registry;

    @Inject
    public BiddingServiceImpl(PersistentEntityRegistry registry) {
        this.registry = registry;
    }

    @Incoming(value = "item-ItemEvent", provider = Kafka.class)
    public CompletionStage<?> handleItemServiceEvents(ItemEvent itemEvent) {
        if (itemEvent instanceof ItemEvent.AuctionStarted) {
            ItemEvent.AuctionStarted auctionStarted = (ItemEvent.AuctionStarted) itemEvent;
            Auction auction = new Auction(auctionStarted.getItemId(), auctionStarted.getCreator(),
                auctionStarted.getReservePrice(), auctionStarted.getIncrement(), auctionStarted.getStartDate(),
                auctionStarted.getEndDate());

            return entityRef(auctionStarted.getItemId()).ask(new AuctionCommand.StartAuction(auction));
        } else if (itemEvent instanceof ItemEvent.AuctionCancelled) {
            return entityRef(itemEvent.getItemId()).ask(AuctionCommand.CancelAuction.INSTANCE);
        } else {
            return CompletableFuture.completedFuture(Done.getInstance());
        }
    }

    @Override
    public ServiceCall<PlaceBid, BidResult> placeBid(UUID itemId) {
        return authenticated(userId -> bid -> {
            AuctionCommand.PlaceBid placeBid = new AuctionCommand.PlaceBid(bid.getMaximumBidPrice(), userId);
            return entityRef(itemId).ask(placeBid).thenApply(result ->
                new BidResult(result.getCurrentPrice(),
                    result.getStatus().bidResultStatus, result.getCurrentBidder())
            );
        });
    }

    @Override
    public ServiceCall<NotUsed, PSequence<Bid>> getBids(UUID itemId) {
        return request -> {
            return entityRef(itemId).ask(GetAuction.INSTANCE).thenApply(auction -> {
                List<Bid> bids = auction.getBiddingHistory().stream()
                    .map(this::convertBid)
                    .collect(Collectors.toList());
                return TreePVector.from(bids);
            });
        };
    }

    public ProcessorBuilder<Message<AuctionEvent>, KafkaProducerMessage<String, BidEvent>> publishEventLog() {

        return ReactiveStreams.<Message<AuctionEvent>>builder()
            // Filter the event log events because we only want to publish
            // bid placed and bidding finished events
            .filter(msg ->
                    msg.getPayload() instanceof AuctionEvent.BidPlaced ||
                        msg.getPayload() instanceof AuctionEvent.BiddingFinished

            // Map the events to our public event API
            ).flatMapCompletionStage(event -> {

                if (event.getPayload() instanceof AuctionEvent.BidPlaced) {
                    // Bid placed events get converted to a simple BidPlaced event
                    AuctionEvent.BidPlaced bid = (AuctionEvent.BidPlaced) event.getPayload();
                    return CompletableFuture.completedFuture(
                        new KafkaProducerMessage<>(bid.getItemId().toString(),
                            new BidEvent.BidPlaced(bid.getItemId(), convertBid(bid.getBid())), event::ack)
                    );

                } else {
                    // Bidding finished events require us to look up further information
                    // in order to publish the public events
                    UUID itemId = ((AuctionEvent.BiddingFinished) event.getPayload()).getItemId();
                    return getBiddingFinish(itemId).thenApply(bf ->
                        new KafkaProducerMessage<>(bf.getItemId().toString(), bf, event::ack)
                    );
                }
            });
    }

    private CompletionStage<BidEvent.BiddingFinished> getBiddingFinish(UUID itemId) {
        return entityRef(itemId).ask(GetAuction.INSTANCE).thenApply(auction -> {
            Optional<Bid> winningBid = auction.lastBid()
                .filter(bid ->
                    bid.getBidPrice() >= auction.getAuction().get().getReservePrice()
                ).map(this::convertBid);
            return new BidEvent.BiddingFinished(itemId, winningBid);
        });
    }

    private Bid convertBid(com.example.auction.bidding.impl.Bid bid) {
        return new Bid(bid.getBidder(), bid.getBidTime(), bid.getBidPrice(), bid.getMaximumBid());
    }

    private PersistentEntityRef<AuctionCommand> entityRef(UUID itemId) {
        return registry.refFor(AuctionEntity.class, itemId.toString());
    }
}
