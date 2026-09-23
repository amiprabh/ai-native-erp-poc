package com.aierp.repository;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class WebhookEventRepository {

    // Thread-safe in-memory store for idempotency evaluation during testing/POC execution
    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();

    public boolean existsBySourceAndEventId(String source, String eventId) {
        return processedKeys.contains(source + ":" + eventId);
    }

    public void saveRawEvent(String source, String eventId, Map<String, Object> payload, Instant receivedAt) {
        processedKeys.add(source + ":" + eventId);
    }
}