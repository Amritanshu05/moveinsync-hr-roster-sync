package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = "SELECT * FROM outbox_events " +
                   "WHERE ((status = 'PENDING' AND next_retry_at <= :now) " +
                   "    OR (status = 'PROCESSING' AND next_retry_at <= :now)) " +
                   "ORDER BY created_at ASC " +
                   "LIMIT :limit " +
                   "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findEventsToClaim(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "SELECT * FROM outbox_events " +
                   "WHERE status = 'PENDING' AND next_retry_at <= :now " +
                   "ORDER BY created_at ASC " +
                   "LIMIT :limit " +
                   "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingEventsForProcessing(@Param("now") Instant now, @Param("limit") int limit);

    long countByStatus(OutboxStatus status);
}
