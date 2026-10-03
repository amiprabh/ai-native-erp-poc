package com.aierp.domain.integration;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "webhook_events",
    schema = "integration",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_webhook_source_event",
            columnNames = {"source", "event_id"}
        )
    }
)
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source", nullable = false, length = 100)
    private String source;

    @Column(name = "event_id", nullable = false, length = 200)
    private String eventId;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "source_account_id", length = 200)
    private String sourceAccountId;

    @Column(name = "source_object_id", length = 200)
    private String sourceObjectId;

    @Column(name = "source_object_version")
    private Long sourceObjectVersion;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    protected WebhookEvent() {
    }

    public WebhookEvent(
            String source,
            String eventId,
            String eventType,
            String sourceAccountId,
            String sourceObjectId,
            Long sourceObjectVersion,
            Instant receivedAt,
            String payloadJson) {

        this.source = source;
        this.eventId = eventId;
        this.eventType = eventType;
        this.sourceAccountId = sourceAccountId;
        this.sourceObjectId = sourceObjectId;
        this.sourceObjectVersion = sourceObjectVersion;
        this.receivedAt = receivedAt;
        this.payloadJson = payloadJson;
        this.status = "RECEIVED";
    }

    public UUID getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getSourceObjectId() {
        return sourceObjectId;
    }

    public Long getSourceObjectVersion() {
        return sourceObjectVersion;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getLastError() {
        return lastError;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.lastError = null;
    }

    public void markProcessing() {
        this.status = "PROCESSING";
        this.attemptCount++;
    }

    public void markProcessed() {
        this.status = "PROCESSED";
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.status = "FAILED";
        this.lastError = error;
    }

    public void markRetrying(String error) {
        this.status = "RETRYING";
        this.lastError = error;
    }

    public void markDeadLetter(String error) {
        this.status = "DEAD_LETTER";
        this.lastError = error;
    }
}