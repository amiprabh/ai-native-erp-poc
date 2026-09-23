package com.aierp;

import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import com.aierp.repository.JournalEntryRepository;
import com.aierp.service.AccountingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountingEngineTest {

    private JournalEntryRepository journalRepository;
    private AccountingEngine accountingEngine;

    @BeforeEach
    void setUp() {
        journalRepository = Mockito.mock(JournalEntryRepository.class);
        accountingEngine = new AccountingEngine(journalRepository);
    }

    @Test
    @DisplayName("Should reject unbalanced journal proposal where Debits != Credits")
    void testUnbalancedJournalRejection() {
        // Unbalanced Proposal: Debit $100 vs Credit $85
        JournalDistributionProposal proposal = new JournalDistributionProposal(
            List.of(
                new LineDistribution("610500", "ENG", new BigDecimal("100.00"), "DEBIT"),
                new LineDistribution("210000", "AP", new BigDecimal("85.00"), "CREDIT")
            ),
            "LLM_INFERENCE",
            0.95
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> accountingEngine.postJournalEntry(proposal, "INV-99001")
        );

        assertTrue(exception.getMessage().contains("Unbalanced Journal Entry"));
    }
}