package com.aierp.repository;

import com.aierp.domain.integration.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findBySourceAndEventId(
            String source,
            String eventId
    );

    boolean existsBySourceAndEventId(
            String source,
            String eventId
    );
}