package com.aierp.consumer;

import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.WebhookEnvelope;
import com.aierp.service.AccountingEngine;
import com.aierp.service.DecisionRouterService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class InvoiceTransactionConsumer {

        private static final Logger log = LoggerFactory.getLogger(InvoiceTransactionConsumer.class);

        private final DecisionRouterService router;
        private final AccountingEngine accountingEngine;

        public InvoiceTransactionConsumer(
                        DecisionRouterService router,
                        AccountingEngine accountingEngine) {

                this.router = router;
                this.accountingEngine = accountingEngine;
        }

        @KafkaListener(topics = "invoice-ingestion-topic", groupId = "erp-group")
        public void handleInvoiceEvent(WebhookEnvelope envelope) {

                String transactionId = envelope.sourceObjectId();

                log.info(
                                "Processing invoice event. eventId={}, transactionId={}",
                                envelope.eventId(),
                                transactionId);

                /*
                 * Do NOT catch the exception here.
                 *
                 * If processing fails, the exception must reach the Spring Kafka
                 * error handler so that retry/DLQ processing can occur.
                 */

                String vendorName = (String) envelope.payload().get("vendorName");

                String lineDescription = (String) envelope.payload().get("lineDescription");

                Object amountValue = envelope.payload().get("amount");

                if (vendorName == null || vendorName.isBlank()) {
                        throw new IllegalArgumentException(
                                        "Missing vendorName. eventId=" + envelope.eventId());
                }

                if (lineDescription == null || lineDescription.isBlank()) {
                        throw new IllegalArgumentException(
                                        "Missing lineDescription. eventId=" + envelope.eventId());
                }

                if (amountValue == null) {
                        throw new IllegalArgumentException(
                                        "Missing amount. eventId=" + envelope.eventId());
                }

                BigDecimal amount;

                try {
                        amount = new BigDecimal(amountValue.toString());
                } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                        "Invalid invoice amount: " + amountValue,
                                        e);
                }

                if (amount.signum() <= 0) {
                        throw new IllegalArgumentException(
                                        "Invoice amount must be positive. eventId="
                                                        + envelope.eventId());
                }

                JournalDistributionProposal proposal = router.resolveAccountingDistribution(
                                transactionId,
                                vendorName,
                                lineDescription,
                                amount);

                /*
                 * AccountingEngine remains the authoritative component that
                 * creates/validates the journal.
                 *
                 * The LLM does NOT directly create a journal entry.
                 */
                accountingEngine.postJournalEntry(
                                proposal,
                                transactionId,
                                vendorName,
                                lineDescription);

                log.info(
                                "Processed invoice event successfully. " +
                                                "eventId={}, transactionId={}, status={}",
                                envelope.eventId(),
                                transactionId,
                                proposal.requiresHumanApproval()
                                                ? "PENDING_APPROVAL"
                                                : "POSTED");
        }
}