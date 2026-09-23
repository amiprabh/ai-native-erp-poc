package com.aierp.controller;

import com.aierp.dto.WebhookEnvelope;
import com.aierp.repository.WebhookEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookIngestionController {

    private final WebhookEventRepository eventRepository;
    private final KafkaTemplate<String, WebhookEnvelope> kafkaTemplate;

    public WebhookIngestionController(WebhookEventRepository eventRepository,
                                        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate) {
        this.eventRepository = eventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/invoices")
    public ResponseEntity<Map<String, String>> ingestInvoiceWebhook(@RequestBody WebhookEnvelope envelope) {
        // Enforce idempotency on (source, event_id)
        if (eventRepository.existsBySourceAndEventId(envelope.source(), envelope.eventId())) {
            return ResponseEntity.ok(Map.of("status", "IGNORED_DUPLICATE"));
        }

        // Persist raw event durably
        eventRepository.saveRawEvent(envelope.source(), envelope.eventId(), envelope.payload(), Instant.now());

        // Dispatch to Kafka consumer queue for micro-batch processing
        kafkaTemplate.send("invoice-ingestion-topic", envelope.sourceAccountId(), envelope);

        return ResponseEntity.accepted().body(Map.of("status", "RECEIVED", "event_id", envelope.eventId()));
    }
}