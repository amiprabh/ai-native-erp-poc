package com.aierp;

import com.aierp.domain.core.JournalEntry;
import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import com.aierp.repository.JournalEntryRepository;
import com.aierp.service.AccountingEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the three AccountingEngine behaviors not exercised by
 * AccountingEngineTest: Kafka at-least-once idempotency, the GL/department
 * whitelist guard, and PENDING_APPROVAL vs POSTED status assignment.
 *
 * Split into its own file rather than appended to AccountingEngineTest so
 * each file stays focused on one concern -- balance validation there,
 * everything else here.
 */
@SpringBootTest
@Transactional
class AccountingEngineAdditionalTest {

    @Autowired
    AccountingEngine accountingEngine;

    @Autowired
    JournalEntryRepository journalEntryRepository;

    private JournalDistributionProposal balancedProposal(String source, double confidence) {
        return new JournalDistributionProposal(
                List.of(
                        new LineDistribution("610500", "ENG", new BigDecimal("100.00"), "DEBIT"),
                        new LineDistribution("210000", "CORP", new BigDecimal("100.00"), "CREDIT")),
                source,
                confidence);
    }

    @Test
    @DisplayName("Should not create a duplicate journal entry when the same invoice is posted twice (Kafka at-least-once)")
    void testDuplicateInvoiceIsIdempotent() {

        JournalDistributionProposal proposal = balancedProposal("HISTORICAL_PATTERN", 1.00);

        JournalEntry first = accountingEngine.postJournalEntry(
                proposal, "INV-IDEMPOTENT-01", "AWS", "Cloud hosting services");

        // Simulate Kafka redelivering the same event -- same invoiceId, same proposal.
        JournalEntry second = accountingEngine.postJournalEntry(
                proposal, "INV-IDEMPOTENT-01", "AWS", "Cloud hosting services");

        assertThat(second.getEntryId()).isEqualTo(first.getEntryId());

        List<JournalEntry> allEntriesForInvoice =
                journalEntryRepository.findBySourceInvoiceId("INV-IDEMPOTENT-01");

        assertThat(allEntriesForInvoice).hasSize(1);
    }

    @Test
    @DisplayName("Should reject a proposal whose DEBIT line references a GL account outside the whitelist")
    void testInvalidGlAccountIsRejected() {

        JournalDistributionProposal proposal = new JournalDistributionProposal(
                List.of(
                        new LineDistribution("999998", "ENG", new BigDecimal("50.00"), "DEBIT"), // not in VALID_GL_ACCOUNTS
                        new LineDistribution("210000", "CORP", new BigDecimal("50.00"), "CREDIT")),
                "LLM_INFERENCE",
                0.95);

        assertThrows(
                IllegalArgumentException.class,
                () -> accountingEngine.postJournalEntry(
                        proposal, "INV-BADACCT-01", "Shady Vendor LLC", "Unclear service"));
    }

    @Test
    @DisplayName("Should reject a proposal whose DEBIT line references a department outside the whitelist")
    void testInvalidDepartmentIsRejected() {

        JournalDistributionProposal proposal = new JournalDistributionProposal(
                List.of(
                        new LineDistribution("610500", "NOT_A_REAL_DEPT", new BigDecimal("50.00"), "DEBIT"),
                        new LineDistribution("210000", "CORP", new BigDecimal("50.00"), "CREDIT")),
                "LLM_INFERENCE",
                0.95);

        assertThrows(
                IllegalArgumentException.class,
                () -> accountingEngine.postJournalEntry(
                        proposal, "INV-BADDEPT-01", "Shady Vendor LLC", "Unclear service"));
    }

    @Test
    @DisplayName("Should post with status POSTED when the proposal does not require human approval")
    void testAutoApprovedProposalIsPosted() {

        JournalDistributionProposal proposal = balancedProposal("HISTORICAL_PATTERN", 1.00);
        // requiresHumanApproval defaults to false -- not set here on purpose.

        JournalEntry entry = accountingEngine.postJournalEntry(
                proposal, "INV-AUTOPOST-01", "AWS", "Cloud hosting services");

        assertThat(entry.getStatus()).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("Should post with status PENDING_APPROVAL when the proposal requires human approval")
    void testLowConfidenceProposalIsPendingApproval() {

        JournalDistributionProposal proposal = balancedProposal("LLM_INFERENCE", 0.72);
        proposal.setRequiresHumanApproval(true);

        JournalEntry entry = accountingEngine.postJournalEntry(
                proposal, "INV-PENDING-01", "New Vendor Inc", "Unclassified expense");

        assertThat(entry.getStatus()).isEqualTo("PENDING_APPROVAL");
    }
}
