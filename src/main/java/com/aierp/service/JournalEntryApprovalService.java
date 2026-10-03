package com.aierp.service;

import com.aierp.domain.ai.AccountingPattern;
import com.aierp.domain.core.JournalEntry;
import com.aierp.dto.JournalEntrySummary;
import com.aierp.dto.LedgerLineSummary;
import com.aierp.repository.AccountingPatternRepository;
import com.aierp.repository.JournalEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JournalEntryApprovalService {

    private static final Logger log = LoggerFactory.getLogger(JournalEntryApprovalService.class);

    private final JournalEntryRepository journalRepository;
    private final AccountingPatternRepository patternRepository;
    private final SemanticPatternMatchingService semanticMatcher;

    public JournalEntryApprovalService(JournalEntryRepository journalRepository,
                                        AccountingPatternRepository patternRepository,
                                        SemanticPatternMatchingService semanticMatcher) {
        this.journalRepository = journalRepository;
        this.patternRepository = patternRepository;
        this.semanticMatcher = semanticMatcher;
    }

    @Transactional(readOnly = true)
    public List<JournalEntrySummary> listPending() {
        return journalRepository.findByStatus("PENDING_APPROVAL").stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<JournalEntrySummary> findByInvoiceId(String invoiceId) {
        return journalRepository.findBySourceInvoiceId(invoiceId).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    /**
     * Approves a PENDING_APPROVAL entry and promotes it into accounting_patterns
     * so future identical/similar invoices skip straight to Tier 1 or Tier 2
     * instead of hitting the LLM again.
     */
    @Transactional
    public JournalEntrySummary approve(UUID entryId) {
        JournalEntry entry = journalRepository.findById(entryId)
                .orElseThrow(() -> new NoSuchElementException("No journal entry found: " + entryId));

        if (!"PENDING_APPROVAL".equalsIgnoreCase(entry.getStatus())) {
            throw new IllegalStateException(
                    "Entry " + entryId + " is not pending approval (status=" + entry.getStatus() + ")");
        }

        entry.setStatus("POSTED");

        if (entry.getVendorName() != null && entry.getDescriptionFeature() != null) {
            var debitLine = entry.getLedgerLines().stream()
                    .filter(l -> "DEBIT".equalsIgnoreCase(l.getSide()))
                    .findFirst();

            debitLine.ifPresentOrElse(line -> {
                AccountingPattern newPattern = new AccountingPattern(
                        entry.getVendorName(),
                        entry.getDescriptionFeature(),
                        line.getGlAccountId(),
                        line.getDepartmentId());
                AccountingPattern saved = patternRepository.save(newPattern);
                semanticMatcher.indexPattern(saved);
                log.info("Promoted approved entry {} into a new accounting pattern {}", entryId, saved.getPatternId());
            }, () -> log.warn("Approved entry {} has no DEBIT line -- skipping pattern promotion", entryId));
        } else {
            log.warn("Approved entry {} has no vendorName/descriptionFeature -- skipping pattern promotion", entryId);
        }

        return toSummary(journalRepository.save(entry));
    }

    @Transactional
    public JournalEntrySummary reject(UUID entryId) {
        JournalEntry entry = journalRepository.findById(entryId)
                .orElseThrow(() -> new NoSuchElementException("No journal entry found: " + entryId));

        if (!"PENDING_APPROVAL".equalsIgnoreCase(entry.getStatus())) {
            throw new IllegalStateException(
                    "Entry " + entryId + " is not pending approval (status=" + entry.getStatus() + ")");
        }

        entry.setStatus("REJECTED");
        return toSummary(journalRepository.save(entry));
    }

    private JournalEntrySummary toSummary(JournalEntry entry) {
        List<LedgerLineSummary> lines = entry.getLedgerLines().stream()
                .map(l -> new LedgerLineSummary(l.getGlAccountId(), l.getDepartmentId(), l.getAmount(), l.getSide()))
                .collect(Collectors.toList());

        return new JournalEntrySummary(
                entry.getEntryId().toString(),
                entry.getSourceInvoiceId(),
                entry.getVendorName(),
                entry.getDescriptionFeature(),
                entry.getSource(),
                entry.getStatus(),
                entry.getPostedAt(),
                lines);
    }
}