package com.aierp;

import com.aierp.domain.core.JournalEntry;
import com.aierp.domain.core.LedgerLine;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class AccountingEngineTest {

    @Autowired
    AccountingEngine accountingEngine;

    @Autowired
    JournalEntryRepository journalEntryRepository;

    @Test
    @DisplayName("Should reject unbalanced journal proposal where Debits != Credits")
    void testUnbalancedJournalRejection() {

        // Unbalanced Proposal: Debit $100 vs Credit $85
        JournalDistributionProposal proposal = new JournalDistributionProposal(
                List.of(
                        new LineDistribution(
                                "610500",
                                "ENG",
                                new BigDecimal("100.00"),
                                "DEBIT"),
                        new LineDistribution(
                                "210000",
                                "AP",
                                new BigDecimal("85.00"),
                                "CREDIT")),
                "LLM_INFERENCE",
                0.95);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> accountingEngine.postJournalEntry(
                        proposal,
                        "INV-99001", "Test Vendor", "Test line item"));

        assertTrue(
                exception.getMessage().contains("Unbalanced Journal Entry"));
    }

    @Test
    @DisplayName("Should process journal proposal where Debits equals Credits")
    void testBalancedJournalProcessing() {

        // Balanced Proposal: Debit $100 vs Credit $100
        JournalDistributionProposal proposal = new JournalDistributionProposal(
                List.of(
                        new LineDistribution(
                                "610500",
                                "ENG",
                                new BigDecimal("100.00"),
                                "DEBIT"),
                        new LineDistribution(
                                "210000",
                                "AP",
                                new BigDecimal("100.00"),
                                "CREDIT")),
                "LLM_INFERENCE",
                1.00);

        assertDoesNotThrow(
                () -> accountingEngine.postJournalEntry(
                        proposal,
                        "INV-99002", "Test Vendor", "Test line item"));

        // Verify the JournalEntry was persisted.
        List<JournalEntry> entries = journalEntryRepository.findBySourceInvoiceId("INV-99002");

        assertThat(entries).hasSize(1);

        JournalEntry entry = entries.get(0);

        assertThat(entry.getLedgerLines())
                .extracting(
                        LedgerLine::getGlAccountId,
                        LedgerLine::getAmount,
                        LedgerLine::getSide)
                .containsExactlyInAnyOrder(
                        tuple(
                                "610500",
                                new BigDecimal("100.00"),
                                "DEBIT"),
                        tuple(
                                "210000",
                                new BigDecimal("100.00"),
                                "CREDIT"));

        // Verify double-entry balance.
        BigDecimal totalDebit = entry.getLedgerLines().stream()
                .filter(l -> "DEBIT".equals(l.getSide()))
                .map(LedgerLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = entry.getLedgerLines().stream()
                .filter(l -> "CREDIT".equals(l.getSide()))
                .map(LedgerLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebit)
                .isEqualByComparingTo(totalCredit);
    }
}