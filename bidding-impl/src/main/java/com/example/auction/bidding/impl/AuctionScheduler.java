package com.example.auction.bidding.impl;

import akka.Done;
import akka.actor.ActorSystem;
import akka.stream.Materializer;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.lightbend.lagom.javadsl.persistence.AggregateEventTag;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRegistry;
import com.lightbend.lagom.javadsl.persistence.ReadSide;
import com.lightbend.lagom.javadsl.persistence.ReadSideProcessor;
import com.lightbend.lagom.javadsl.persistence.cassandra.CassandraReadSide;

import static com.lightbend.lagom.javadsl.persistence.cassandra.CassandraReadSide.*;

import com.lightbend.lagom.javadsl.persistence.cassandra.CassandraSession;
import com.lightbend.lagom.javadsl.persistence.cdi.PersistenceScoped;
import org.pcollections.PSequence;
import com.example.auction.bidding.impl.AuctionEvent.*;
import com.example.auction.bidding.impl.AuctionCommand.FinishBidding;
import org.pcollections.TreePVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Maintains a read side view of all auctions that gets used to schedule FinishBidding events.
 */
@ApplicationScoped
public class AuctionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionScheduler.class);

    private final CassandraSession session;
    private final ActorSystem system;
    private final PersistentEntityRegistry registry;
    private final Materializer materializer;
    private final FiniteDuration finishBiddingDelay;

    @Inject
    public AuctionScheduler(CassandraSession session, ActorSystem system, ReadSide readSide, PersistentEntityRegistry registry, Materializer materializer) {
        this.session = session;
        this.system = system;
        this.registry = registry;
        this.materializer = materializer;
        finishBiddingDelay = Duration.create(
            this.system.settings().config().getDuration("auctionSchedulerDelay", TimeUnit.MILLISECONDS),
            TimeUnit.MILLISECONDS);
    }

    public void startScheduler(@Observes @Initialized(ApplicationScoped.class) Object obj) {
        system.scheduler().schedule(finishBiddingDelay, finishBiddingDelay,
            this::checkFinishBidding, system.dispatcher());
    }

    /**
     * Check whether there are any auctions that are due to finish, and if so, send a command to finish them.
     */
    private void checkFinishBidding() {
        try {
            session.select("SELECT itemId FROM auctionSchedule WHERE endAuction < toTimestamp(now()) allow filtering")
                .runForeach(row -> {
                    UUID uuid = row.getUUID("itemId");
                    registry.refFor(AuctionEntity.class, uuid.toString())
                        .ask(FinishBidding.INSTANCE);
                }, materializer).exceptionally(t -> {
                log.warn("Error running finish bidding query", t);
                return Done.getInstance();
            });
        } catch (IllegalStateException iae) {
            // Ignore materializer illegal state exceptions that get thrown when the system shuts down.

        }
    }

    @PersistenceScoped
    public static class AuctionSchedulerProcessor extends ReadSideProcessor<AuctionEvent> {

        private final CassandraReadSide readSide;
        private final CassandraSession session;

        private PreparedStatement insertAuctionStatement;
        private PreparedStatement deleteAuctionStatement;

        @Inject
        public AuctionSchedulerProcessor(CassandraReadSide readSide, CassandraSession session) {
            this.readSide = readSide;
            this.session = session;
        }

        @Override
        public ReadSideHandler<AuctionEvent> buildHandler() {
            return readSide.<AuctionEvent>builder("auctionSchedulerOffset")
                .setGlobalPrepare(this::createTable)
                .setPrepare(tag ->
                    prepareInsertAuctionStatement()
                        .thenCompose(d -> prepareDeleteAuctionStatement())
                )
                .setEventHandler(AuctionStarted.class, this::insertAuction)
                .setEventHandler(BiddingFinished.class, e -> deleteAuction(e.getItemId()))
                .setEventHandler(AuctionCancelled.class, e -> deleteAuction(e.getItemId()))
                .build();
        }

        private CompletionStage<Done> createTable() {
            return session.executeCreateTable(
                "CREATE TABLE IF NOT EXISTS auctionSchedule ( " +
                    "itemId uuid, " +
                    "endAuction timestamp, " +
                    "PRIMARY KEY (itemId)" +
                    ")").thenCompose(d ->
                session.executeCreateTable(
                    "CREATE INDEX IF NOT EXISTS auctionScheduleIndex " +
                        "on auctionSchedule (endAuction)"
                )
            );

        }

        private CompletionStage<Done> prepareInsertAuctionStatement() {
            return session.prepare("INSERT INTO auctionSchedule(itemId, endAuction) VALUES (?, ?)")
                .thenApply(s -> {
                    insertAuctionStatement = s;
                    return Done.getInstance();
                });
        }

        private CompletionStage<Done> prepareDeleteAuctionStatement() {
            return session.prepare("DELETE FROM auctionSchedule where itemId = ?")
                .thenApply(s -> {
                    deleteAuctionStatement = s;
                    return Done.getInstance();
                });
        }

        private CompletionStage<List<BoundStatement>> insertAuction(AuctionStarted started) {
            return completedStatement(insertAuctionStatement.bind(
                started.getItemId(),
                Date.from(started.getAuction().getEndTime())
            ));
        }

        private CompletionStage<List<BoundStatement>> deleteAuction(UUID itemId) {
            return completedStatement(deleteAuctionStatement.bind(itemId));
        }

        @Override
        public PSequence<AggregateEventTag<AuctionEvent>> aggregateTags() {
            return TreePVector.singleton(AuctionEvent.TAG);
        }
    }
}
