package com.aierp.service;

import com.aierp.domain.core.JournalEntry;
import com.aierp.domain.core.LedgerLine;
import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import com.aierp.repository.JournalEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class AccountingEngine {

    private final JournalEntryRepository journalRepository;

    public AccountingEngine(JournalEntryRepository journalRepository) {
        this.journalRepository = journalRepository;
    }

    @Transactional
    public JournalEntry postJournalEntry(JournalDistributionProposal proposal, String invoiceId) {
        // 1. Double-Entry Validation Rule: Sum(Debits) must equal Sum(Credits)
        BigDecimal totalDebit = proposal.lines().stream()
            .filter(line -> "DEBIT".equalsIgnoreCase(line.type()))
            .map(LineDistribution::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = proposal.lines().stream()
            .filter(line -> "CREDIT".equalsIgnoreCase(line.type()))
            .map(LineDistribution::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException("Unbalanced Journal Entry: Debits [" + totalDebit + "] != Credits [" + totalCredit + "]");
        }

        // 2. Persist to authoritative append-only ledger
        JournalEntry entry = new JournalEntry();
        entry.setSourceInvoiceId(invoiceId);
        entry.setPostedAt(Instant.now());
        entry.setStatus(proposal.requiresHumanApproval() ? "PENDING_APPROVAL" : "POSTED");

        for (var lineDto : proposal.lines()) {
            LedgerLine line = new LedgerLine();
            line.setGlAccountId(lineDto.glAccountId());
            line.setAmount(lineDto.amount());
            line.setSide(lineDto.type());
            entry.addLedgerLine(line);
        }

        return journalRepository.save(entry);
    }
}