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
import java.util.List;
import java.util.Set;

@Service
public class AccountingEngine {

        // Only the DEBIT side is ever chosen by a tier (pattern/semantic/LLM).
        // The CREDIT side is always the fixed AP control account (210000/CORP)
        // set by LlmInferenceService -- it was never meant to be in this list,
        // and checking it here would reject every legitimate proposal.
        private static final Set<String> VALID_GL_ACCOUNTS = Set.of("610500", "610200", "620100", "680000","999999");
        private static final Set<String> VALID_DEPARTMENTS = Set.of("ENG", "SALES", "GEN", "SUSPENSE");

        private final JournalEntryRepository journalRepository;

        public AccountingEngine(JournalEntryRepository journalRepository) {
                this.journalRepository = journalRepository;
        }

        @Transactional
        public JournalEntry postJournalEntry(
                        JournalDistributionProposal proposal,
                        String invoiceId,
                        String vendorName,
                        String lineDescription) {
                /*
                 * 0. Idempotency Protection
                 *
                 * Kafka provides at-least-once delivery, so the same invoice event
                 * can potentially reach this method more than once.
                 *
                 * If this invoice already has a POSTED journal entry, do not create
                 * another journal entry.
                 */
                List<JournalEntry> existingEntries = journalRepository.findBySourceInvoiceId(invoiceId);
                boolean alreadyPosted = existingEntries.stream()
                                .anyMatch(entry -> "POSTED".equalsIgnoreCase(entry.getStatus()));
                if (alreadyPosted) {
                        return existingEntries.stream()
                                        .filter(entry -> "POSTED".equalsIgnoreCase(entry.getStatus()))
                                        .findFirst()
                                        .orElseThrow();
                }

                // 1. Whitelist validation -- debit lines only. Applies uniformly to
                // every tier (pattern/semantic/LLM), not just the LLM path.
                for (LineDistribution line : proposal.lines()) {
                        if (!"DEBIT".equalsIgnoreCase(line.type())) {
                                continue;
                        }
                        if (!VALID_GL_ACCOUNTS.contains(line.glAccountId())
                                        || !VALID_DEPARTMENTS.contains(line.departmentId())) {
                                throw new IllegalArgumentException(
                                                "Proposal from source=" + proposal.source()
                                                                + " references an invalid GL account/department: "
                                                                + line.glAccountId() + " / " + line.departmentId());
                        }
                }

                // 2. Double-Entry Validation Rule:
                // Sum(Debits) must equal Sum(Credits)
                BigDecimal totalDebit = proposal.lines().stream()
                                .filter(line -> "DEBIT".equalsIgnoreCase(line.type()))
                                .map(LineDistribution::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal totalCredit = proposal.lines().stream()
                                .filter(line -> "CREDIT".equalsIgnoreCase(line.type()))
                                .map(LineDistribution::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                if (totalDebit.compareTo(totalCredit) != 0) {
                        throw new IllegalArgumentException(
                                        "Unbalanced Journal Entry: Debits [" + totalDebit
                                                        + "] != Credits [" + totalCredit + "]");
                }

                // 3. Persist to authoritative append-only ledger
                JournalEntry entry = new JournalEntry();
                entry.setSourceInvoiceId(invoiceId);
                entry.setPostedAt(Instant.now());
                entry.setVendorName(vendorName);
                entry.setDescriptionFeature(lineDescription);
                entry.setSource(proposal.source());
                entry.setStatus(proposal.requiresHumanApproval() ? "PENDING_APPROVAL" : "POSTED");

                for (var lineDto : proposal.lines()) {
                        LedgerLine line = new LedgerLine();
                        line.setGlAccountId(lineDto.glAccountId());
                        line.setDepartmentId(lineDto.departmentId());
                        line.setAmount(lineDto.amount());
                        line.setSide(lineDto.type());
                        entry.addLedgerLine(line);
                }

                return journalRepository.save(entry);
        }
}