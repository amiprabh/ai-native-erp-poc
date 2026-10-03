package com.aierp;

import com.aierp.consumer.InvoiceTransactionConsumer;
import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import com.aierp.dto.WebhookEnvelope;
import com.aierp.service.AccountingEngine;
import com.aierp.service.DecisionRouterService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InvoiceTransactionConsumer is deliberately thin: it does NOT catch
 * exceptions, by design (see its own comment), so that Spring Kafka's error
 * handler can drive retry/DLQ behavior. The only thing worth proving here is
 * that a malformed event actually throws rather than silently no-oping --
 * a silent no-op would be a lost invoice with no DLQ trace.
 */
class InvoiceTransactionConsumerTest {

        private WebhookEnvelope envelopeWithPayload(Map<String, Object> payload) {
                return new WebhookEnvelope(
                                "evt-1", "INVOICE_LINE_CREATED", "demo-ap-system",
                                "demo-account-1", "INV-CONSUMER-01", 1L,
                                "2026-01-01T00:00:00Z", payload);
        }

        @Test
        @DisplayName("Should throw when vendorName is missing, so the Kafka error handler can retry/DLQ it")
        void testMissingVendorNameThrows() {

                DecisionRouterService router = mock(DecisionRouterService.class);
                AccountingEngine accountingEngine = mock(AccountingEngine.class);

                InvoiceTransactionConsumer consumer = new InvoiceTransactionConsumer(router, accountingEngine);

                WebhookEnvelope badEnvelope = envelopeWithPayload(
                                Map.of("lineDescription", "Cloud hosting services", "amount", 250.00));

                assertThrows(IllegalArgumentException.class,
                                () -> consumer.handleInvoiceEvent(badEnvelope));

                verifyNoInteractions(router, accountingEngine);
        }

        @Test
        @DisplayName("Should throw when amount is not a valid number, rather than posting a corrupt entry")
        void testInvalidAmountThrows() {

                DecisionRouterService router = mock(DecisionRouterService.class);
                AccountingEngine accountingEngine = mock(AccountingEngine.class);

                InvoiceTransactionConsumer consumer = new InvoiceTransactionConsumer(router, accountingEngine);

                WebhookEnvelope badEnvelope = envelopeWithPayload(
                                Map.of("vendorName", "AWS", "lineDescription", "Cloud hosting", "amount",
                                                "not-a-number"));

                assertThrows(IllegalArgumentException.class,
                                () -> consumer.handleInvoiceEvent(badEnvelope));

                verifyNoInteractions(router, accountingEngine);
        }

        @Test
        @DisplayName("Should throw when amount is zero or negative")
        void testNonPositiveAmountThrows() {

                DecisionRouterService router = mock(DecisionRouterService.class);
                AccountingEngine accountingEngine = mock(AccountingEngine.class);

                InvoiceTransactionConsumer consumer = new InvoiceTransactionConsumer(router, accountingEngine);

                WebhookEnvelope badEnvelope = envelopeWithPayload(
                                Map.of("vendorName", "AWS", "lineDescription", "Cloud hosting", "amount", -50.00));

                assertThrows(IllegalArgumentException.class,
                                () -> consumer.handleInvoiceEvent(badEnvelope));

                verifyNoInteractions(router, accountingEngine);
        }

        @Test
        @DisplayName("Should route to DecisionRouterService then AccountingEngine for a valid payload")
        void testValidPayloadFlowsThroughRouterThenEngine() {

                DecisionRouterService router = mock(DecisionRouterService.class);
                AccountingEngine accountingEngine = mock(AccountingEngine.class);

                JournalDistributionProposal proposal = new JournalDistributionProposal(
                                List.of(
                                                new LineDistribution("610500", "ENG", new BigDecimal("250.00"),
                                                                "DEBIT"),
                                                new LineDistribution("210000", "CORP", new BigDecimal("250.00"),
                                                                "CREDIT")),
                                "HISTORICAL_PATTERN",
                                1.00);

                when(router.resolveAccountingDistribution(
                                anyString(), anyString(), anyString(), any(BigDecimal.class)))
                                .thenReturn(proposal);

                InvoiceTransactionConsumer consumer = new InvoiceTransactionConsumer(router, accountingEngine);

                WebhookEnvelope goodEnvelope = envelopeWithPayload(
                                Map.of("vendorName", "AWS", "lineDescription", "Cloud hosting services", "amount",
                                                250.00));

                consumer.handleInvoiceEvent(goodEnvelope);

                verify(router).resolveAccountingDistribution(
                                eq("INV-CONSUMER-01"),
                                eq("AWS"),
                                eq("Cloud hosting services"),
                                argThat(amount -> amount.compareTo(new BigDecimal("250.00")) == 0));

                verify(accountingEngine).postJournalEntry(
                                eq(proposal), eq("INV-CONSUMER-01"), eq("AWS"), eq("Cloud hosting services"));
        }
}
