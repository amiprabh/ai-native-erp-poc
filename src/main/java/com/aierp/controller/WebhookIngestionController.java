package com.aierp.controller;

import com.aierp.domain.integration.WebhookEvent;
import com.aierp.dto.WebhookEnvelope;
import com.aierp.repository.WebhookEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookIngestionController {

    private static final Logger log =
            LoggerFactory.getLogger(WebhookIngestionController.class);

    private static final String INVOICE_TOPIC =
            "invoice-ingestion-topic";

    private final WebhookEventRepository eventRepository;
    private final KafkaTemplate<String, WebhookEnvelope> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public WebhookIngestionController(
            WebhookEventRepository eventRepository,
            KafkaTemplate<String, WebhookEnvelope> kafkaTemplate,
            ObjectMapper objectMapper) {

        this.eventRepository = eventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/invoices")
    public ResponseEntity<Map<String, String>> ingestInvoiceWebhook(
            @RequestBody WebhookEnvelope envelope) {

        validateEnvelope(envelope);

        /*
         * First check whether we have already received this event.
         */
        var existing = eventRepository.findBySourceAndEventId(
                envelope.source(),
                envelope.eventId()
        );

        if (existing.isPresent()) {

            WebhookEvent existingEvent = existing.get();

            /*
             * If the event was already successfully published,
             * this is a normal duplicate webhook.
             */
            if ("PUBLISHED".equals(existingEvent.getStatus())
                    || "PROCESSED".equals(existingEvent.getStatus())) {

                log.info(
                        "Ignoring duplicate webhook. source={}, eventId={}, status={}",
                        envelope.source(),
                        envelope.eventId(),
                        existingEvent.getStatus()
                );

                return ResponseEntity.ok(
                        Map.of(
                                "status", "IGNORED_DUPLICATE",
                                "event_id", envelope.eventId()
                        )
                );
            }

            /*
             * If the previous attempt failed, allow the same webhook
             * to retry Kafka publication.
             */
            log.info(
                    "Retrying previously failed webhook. source={}, eventId={}, status={}",
                    envelope.source(),
                    envelope.eventId(),
                    existingEvent.getStatus()
            );

            return publishExistingEvent(existingEvent, envelope);
        }

        /*
         * Serialize and durably persist the raw webhook BEFORE
         * attempting asynchronous business processing.
         */
        final String payloadJson;

        try {
            payloadJson = objectMapper.writeValueAsString(envelope.payload());
        } catch (JsonProcessingException e) {

            log.error(
                    "Unable to serialize webhook payload. eventId={}",
                    envelope.eventId(),
                    e
            );

            return ResponseEntity.badRequest().body(
                    Map.of(
                            "status", "INVALID_PAYLOAD",
                            "event_id", envelope.eventId()
                    )
            );
        }

        WebhookEvent event = new WebhookEvent(
                envelope.source(),
                envelope.eventId(),
                envelope.eventType(),
                envelope.sourceAccountId(),
                envelope.sourceObjectId(),
                envelope.sourceObjectVersion(),
                Instant.now(),
                payloadJson
        );

        try {
            /*
             * saveAndFlush is intentional here.
             *
             * It forces the UNIQUE(source,event_id) constraint to be
             * evaluated now instead of potentially later at transaction
             * commit.
             */
            eventRepository.saveAndFlush(event);

        } catch (DataIntegrityViolationException e) {

            /*
             * Another request may have inserted the same event
             * concurrently.
             */
            log.info(
                    "Concurrent duplicate webhook detected. source={}, eventId={}",
                    envelope.source(),
                    envelope.eventId()
            );

            return ResponseEntity.ok(
                    Map.of(
                            "status", "IGNORED_DUPLICATE",
                            "event_id", envelope.eventId()
                    )
            );
        }

        return publishExistingEvent(event, envelope);
    }

    private ResponseEntity<Map<String, String>> publishExistingEvent(
            WebhookEvent event,
            WebhookEnvelope envelope) {

        try {

            /*
             * Wait for Kafka acknowledgement.
             *
             * This avoids returning HTTP 202 when Kafka rejected the
             * record immediately.
             */
            kafkaTemplate
                    .send(
                            INVOICE_TOPIC,
                            envelope.sourceAccountId(),
                            envelope
                    )
                    .get(5, TimeUnit.SECONDS);

            event.markPublished();
            eventRepository.save(event);

            log.info(
                    "Webhook persisted and published successfully. source={}, eventId={}",
                    envelope.source(),
                    envelope.eventId()
            );

            return ResponseEntity.accepted().body(
                    Map.of(
                            "status", "RECEIVED",
                            "event_id", envelope.eventId()
                    )
            );

        } catch (Exception e) {

            event.markFailed(e.getMessage());
            eventRepository.save(event);

            log.error(
                    "Webhook persisted but Kafka publication failed. " +
                    "eventId={}, status=FAILED",
                    envelope.eventId(),
                    e
            );

            /*
             * Do not pretend the event was successfully accepted.
             *
             * Because the raw event remains in PostgreSQL, a retry of
             * the same webhook can publish it again.
             */
            return ResponseEntity
                    .internalServerError()
                    .body(
                            Map.of(
                                    "status", "PERSISTED_BUT_KAFKA_FAILED",
                                    "event_id", envelope.eventId()
                            )
                    );
        }
    }

    private void validateEnvelope(WebhookEnvelope envelope) {

        if (envelope == null) {
            throw new IllegalArgumentException("Webhook envelope cannot be null");
        }

        if (isBlank(envelope.eventId())) {
            throw new IllegalArgumentException("eventId is required");
        }

        if (isBlank(envelope.source())) {
            throw new IllegalArgumentException("source is required");
        }

        if (isBlank(envelope.sourceObjectId())) {
            throw new IllegalArgumentException("sourceObjectId is required");
        }

        if (envelope.payload() == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}