package com.aierp;

import com.aierp.controller.WebhookIngestionController;
import com.aierp.domain.integration.WebhookEvent;
import com.aierp.dto.WebhookEnvelope;
import com.aierp.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the three failure/dedup paths that give WebhookIngestionController
 * its real value: dropping true duplicates without re-publishing, retrying
 * webhooks that previously failed to reach Kafka, and honestly reporting
 * (rather than hiding) a Kafka publish failure after the raw event is
 * already durably persisted.
 *
 * Pure Mockito, constructed directly -- no @WebMvcTest needed since nothing
 * here depends on Spring's request/serialization layer.
 */
class WebhookIngestionControllerTest {

    private WebhookEnvelope sampleEnvelope() {
        return new WebhookEnvelope(
                "demo-evt-001",
                "INVOICE_LINE_CREATED",
                "demo-ap-system",
                "demo-account-1",
                "DEMO-INV-001",
                1L,
                Instant.now().toString(),
                Map.of("vendorName", "AWS", "lineDescription", "Cloud hosting services", "amount", 250.00));
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, WebhookEnvelope> mockKafkaTemplateThatSucceeds() throws Exception {
        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate = mock(KafkaTemplate.class);
        SendResult<String, WebhookEnvelope> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, WebhookEnvelope>> future =
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(String.class), any(), any(WebhookEnvelope.class)))
                .thenReturn(future);
        return kafkaTemplate;
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, WebhookEnvelope> mockKafkaTemplateThatFails() throws Exception {
        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, WebhookEnvelope>> future = new CompletableFuture<>();
        future.completeExceptionally(new TimeoutException("Simulated broker timeout"));
        when(kafkaTemplate.send(any(String.class), any(), any(WebhookEnvelope.class)))
                .thenReturn(future);
        return kafkaTemplate;
    }

    @Test
    @DisplayName("Should persist and publish a genuinely new webhook event")
    void testNewEventIsPersistedAndPublished() throws Exception {

        WebhookEventRepository repository = mock(WebhookEventRepository.class);
        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate = mockKafkaTemplateThatSucceeds();
        ObjectMapper objectMapper = new ObjectMapper();

        when(repository.findBySourceAndEventId("demo-ap-system", "demo-evt-001"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(WebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(repository.save(any(WebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebhookIngestionController controller =
                new WebhookIngestionController(repository, kafkaTemplate, objectMapper);

        ResponseEntity<Map<String, String>> response =
                controller.ingestInvoiceWebhook(sampleEnvelope());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsEntry("status", "RECEIVED");

        verify(kafkaTemplate).send(eq("invoice-ingestion-topic"), eq("demo-account-1"), any(WebhookEnvelope.class));
    }

    @Test
    @DisplayName("Should ignore a duplicate webhook that was already PUBLISHED and must NOT call Kafka again")
    void testPublishedDuplicateIsIgnoredWithoutRepublishing() throws Exception {

        WebhookEventRepository repository = mock(WebhookEventRepository.class);
        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate = mockKafkaTemplateThatSucceeds();
        ObjectMapper objectMapper = new ObjectMapper();

        WebhookEvent existing = new WebhookEvent(
                "demo-ap-system", "demo-evt-001", "INVOICE_LINE_CREATED",
                "demo-account-1", "DEMO-INV-001", 1L, Instant.now(), "{}");
        existing.markPublished();

        when(repository.findBySourceAndEventId("demo-ap-system", "demo-evt-001"))
                .thenReturn(Optional.of(existing));

        WebhookIngestionController controller =
                new WebhookIngestionController(repository, kafkaTemplate, objectMapper);

        ResponseEntity<Map<String, String>> response =
                controller.ingestInvoiceWebhook(sampleEnvelope());

        assertThat(response.getBody()).containsEntry("status", "IGNORED_DUPLICATE");

        // The critical assertion: a true duplicate must never re-hit Kafka.
        verify(kafkaTemplate, never()).send(any(String.class), any(), any(WebhookEnvelope.class));
    }

    @Test
    @DisplayName("Should ignore a duplicate webhook that was already PROCESSED and must NOT call Kafka again")
    void testProcessedDuplicateIsIgnoredWithoutRepublishing() throws Exception {

        WebhookEventRepository repository = mock(WebhookEventRepository.class);
        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate = mockKafkaTemplateThatSucceeds();
        ObjectMapper objectMapper = new ObjectMapper();

        WebhookEvent existing = new WebhookEvent(
                "demo-ap-system", "demo-evt-001", "INVOICE_LINE_CREATED",
                "demo-account-1", "DEMO-INV-001", 1L, Instant.now(), "{}");
        existing.markPublished();
        existing.markProcessed();

        when(repository.findBySourceAndEventId("demo-ap-system", "demo-evt-001"))
                .thenReturn(Optional.of(existing));

        WebhookIngestionController controller =
                new WebhookIngestionController(repository, kafkaTemplate, objectMapper);

        ResponseEntity<Map<String, String>> response =
                controller.ingestInvoiceWebhook(sampleEnvelope());

        assertThat(response.getBody()).containsEntry("status", "IGNORED_DUPLICATE");
        verify(kafkaTemplate, never()).send(any(String.class), any(), any(WebhookEnvelope.class));
    }

    @Test
    @DisplayName("Should retry publishing a webhook that previously FAILED to reach Kafka")
    void testFailedEventIsRetried() throws Exception {

        WebhookEventRepository repository = mock(WebhookEventRepository.class);
        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate = mockKafkaTemplateThatSucceeds();
        ObjectMapper objectMapper = new ObjectMapper();

        WebhookEvent previouslyFailed = new WebhookEvent(
                "demo-ap-system", "demo-evt-001", "INVOICE_LINE_CREATED",
                "demo-account-1", "DEMO-INV-001", 1L, Instant.now(), "{}");
        previouslyFailed.markFailed("Simulated broker timeout");

        when(repository.findBySourceAndEventId("demo-ap-system", "demo-evt-001"))
                .thenReturn(Optional.of(previouslyFailed));
        when(repository.save(any(WebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebhookIngestionController controller =
                new WebhookIngestionController(repository, kafkaTemplate, objectMapper);

        ResponseEntity<Map<String, String>> response =
                controller.ingestInvoiceWebhook(sampleEnvelope());

        assertThat(response.getBody()).containsEntry("status", "RECEIVED");

        // Unlike the duplicate case, a retry of a FAILED event MUST hit Kafka.
        verify(kafkaTemplate).send(eq("invoice-ingestion-topic"), eq("demo-account-1"), any(WebhookEnvelope.class));
    }

    @Test
    @DisplayName("Should persist the event but report failure honestly when Kafka publish fails")
    void testKafkaPublishFailureIsReportedNotHidden() throws Exception {

        WebhookEventRepository repository = mock(WebhookEventRepository.class);
        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate = mockKafkaTemplateThatFails();
        ObjectMapper objectMapper = new ObjectMapper();

        when(repository.findBySourceAndEventId("demo-ap-system", "demo-evt-001"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(WebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(repository.save(any(WebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebhookIngestionController controller =
                new WebhookIngestionController(repository, kafkaTemplate, objectMapper);

        ResponseEntity<Map<String, String>> response =
                controller.ingestInvoiceWebhook(sampleEnvelope());

        // Must NOT pretend success: 500, explicit status explaining the
        // event is durably saved but not yet published.
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("status", "PERSISTED_BUT_KAFKA_FAILED");

        // The raw event must still have been saved before the Kafka attempt --
        // that's what makes a later retry (see testFailedEventIsRetried) possible.
        verify(repository).saveAndFlush(any(WebhookEvent.class));

        // And the failure must be recorded on the entity (markFailed), proven
        // indirectly here via a second save() call after the exception.
        verify(repository, atLeastOnce()).save(any(WebhookEvent.class));
    }

    @Test
    @DisplayName("Should reject a webhook missing a required field before touching the repository or Kafka")
    void testMissingRequiredFieldIsRejected() {

        WebhookEventRepository repository = mock(WebhookEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, WebhookEnvelope> kafkaTemplate = mock(KafkaTemplate.class);

        WebhookIngestionController controller =
                new WebhookIngestionController(repository, kafkaTemplate, objectMapper);

        WebhookEnvelope missingEventId = new WebhookEnvelope(
                null, "INVOICE_LINE_CREATED", "demo-ap-system", "demo-account-1",
                "DEMO-INV-001", 1L, Instant.now().toString(), Map.of("vendorName", "AWS"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> controller.ingestInvoiceWebhook(missingEventId));

        verifyNoInteractions(repository);
    }
}
