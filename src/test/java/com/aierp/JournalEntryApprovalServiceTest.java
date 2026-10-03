package com.aierp;

import com.aierp.domain.ai.AccountingPattern;
import com.aierp.domain.core.JournalEntry;
import com.aierp.domain.core.LedgerLine;
import com.aierp.dto.JournalEntrySummary;
import com.aierp.repository.AccountingPatternRepository;
import com.aierp.repository.JournalEntryRepository;
import com.aierp.service.JournalEntryApprovalService;
import com.aierp.service.SemanticPatternMatchingService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the approve/reject state machine and -- most importantly -- the
 * pattern-promotion learning loop: an approved LLM-sourced entry should be
 * promoted into AccountingPatternRepository and indexed in the vector store
 * so the next identical invoice hits Tier 1/2 instead of the LLM again.
 *
 * Pure Mockito unit tests (no @SpringBootTest) since JournalEntryApprovalService
 * has no framework-specific behavior worth paying Spring context startup for.
 */
class JournalEntryApprovalServiceTest {

    private JournalEntry pendingEntryWithDebitLine() {
        JournalEntry entry = new JournalEntry();
        entry.setEntryId(UUID.randomUUID());
        entry.setSourceInvoiceId("INV-APPROVE-01");
        entry.setVendorName("Quixotic Ventures LLC");
        entry.setDescriptionFeature("Miscellaneous business services Q3");
        entry.setSource("gemini-3-flash-preview");
        entry.setStatus("PENDING_APPROVAL");

        LedgerLine debit = new LedgerLine();
        debit.setGlAccountId("610500");
        debit.setDepartmentId("ENG");
        debit.setAmount(new BigDecimal("475.50"));
        debit.setSide("DEBIT");
        entry.addLedgerLine(debit);

        LedgerLine credit = new LedgerLine();
        credit.setGlAccountId("210000");
        credit.setDepartmentId("CORP");
        credit.setAmount(new BigDecimal("475.50"));
        credit.setSide("CREDIT");
        entry.addLedgerLine(credit);

        return entry;
    }

    @Test
    @DisplayName("approve() should set status to POSTED and promote a new AccountingPattern from the DEBIT line")
    void testApprovePromotesPattern() {

        JournalEntryRepository journalRepository = mock(JournalEntryRepository.class);
        AccountingPatternRepository patternRepository = mock(AccountingPatternRepository.class);
        SemanticPatternMatchingService semanticMatcher = mock(SemanticPatternMatchingService.class);

        JournalEntry entry = pendingEntryWithDebitLine();
        UUID entryId = entry.getEntryId();

        when(journalRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(journalRepository.save(any(JournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountingPattern savedPattern = new AccountingPattern(
                "Quixotic Ventures LLC", "Miscellaneous business services Q3", "610500", "ENG");
        when(patternRepository.save(any(AccountingPattern.class))).thenReturn(savedPattern);

        JournalEntryApprovalService service =
                new JournalEntryApprovalService(journalRepository, patternRepository, semanticMatcher);

        JournalEntrySummary result = service.approve(entryId);

        assertThat(result.status()).isEqualTo("POSTED");

        // The learning loop: a new pattern must be saved with the DEBIT line's
        // GL account/department, and indexed so Tier 2 semantic search can find it.
        ArgumentCaptor<AccountingPattern> patternCaptor = ArgumentCaptor.forClass(AccountingPattern.class);
        verify(patternRepository).save(patternCaptor.capture());
        AccountingPattern promoted = patternCaptor.getValue();
        assertThat(promoted.getVendorName()).isEqualTo("Quixotic Ventures LLC");
        assertThat(promoted.getDescriptionFeature()).isEqualTo("Miscellaneous business services Q3");
        assertThat(promoted.getGlAccountId()).isEqualTo("610500");
        assertThat(promoted.getDepartmentId()).isEqualTo("ENG");

        verify(semanticMatcher).indexPattern(savedPattern);
    }

    @Test
    @DisplayName("approve() should throw IllegalStateException when the entry is not PENDING_APPROVAL")
    void testApproveRejectsNonPendingEntry() {

        JournalEntryRepository journalRepository = mock(JournalEntryRepository.class);
        AccountingPatternRepository patternRepository = mock(AccountingPatternRepository.class);
        SemanticPatternMatchingService semanticMatcher = mock(SemanticPatternMatchingService.class);

        JournalEntry alreadyPosted = pendingEntryWithDebitLine();
        alreadyPosted.setStatus("POSTED");
        UUID entryId = alreadyPosted.getEntryId();

        when(journalRepository.findById(entryId)).thenReturn(Optional.of(alreadyPosted));

        JournalEntryApprovalService service =
                new JournalEntryApprovalService(journalRepository, patternRepository, semanticMatcher);

        assertThrows(IllegalStateException.class, () -> service.approve(entryId));

        // Guard must fail fast -- no pattern should ever be promoted from a
        // non-pending entry.
        verify(patternRepository, never()).save(any());
        verify(semanticMatcher, never()).indexPattern(any());
    }

    @Test
    @DisplayName("approve() should throw NoSuchElementException when the entry does not exist")
    void testApproveUnknownEntryThrows() {

        JournalEntryRepository journalRepository = mock(JournalEntryRepository.class);
        AccountingPatternRepository patternRepository = mock(AccountingPatternRepository.class);
        SemanticPatternMatchingService semanticMatcher = mock(SemanticPatternMatchingService.class);

        UUID unknownId = UUID.randomUUID();
        when(journalRepository.findById(unknownId)).thenReturn(Optional.empty());

        JournalEntryApprovalService service =
                new JournalEntryApprovalService(journalRepository, patternRepository, semanticMatcher);

        assertThrows(NoSuchElementException.class, () -> service.approve(unknownId));
    }

    @Test
    @DisplayName("reject() should set status to REJECTED and must NOT promote a pattern")
    void testRejectDoesNotPromotePattern() {

        JournalEntryRepository journalRepository = mock(JournalEntryRepository.class);
        AccountingPatternRepository patternRepository = mock(AccountingPatternRepository.class);
        SemanticPatternMatchingService semanticMatcher = mock(SemanticPatternMatchingService.class);

        JournalEntry entry = pendingEntryWithDebitLine();
        UUID entryId = entry.getEntryId();

        when(journalRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(journalRepository.save(any(JournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        JournalEntryApprovalService service =
                new JournalEntryApprovalService(journalRepository, patternRepository, semanticMatcher);

        JournalEntrySummary result = service.reject(entryId);

        assertThat(result.status()).isEqualTo("REJECTED");

        // A rejected AI suggestion was wrong -- it must never become a
        // future auto-posted pattern.
        verify(patternRepository, never()).save(any());
        verify(semanticMatcher, never()).indexPattern(any());
    }

    @Test
    @DisplayName("reject() should throw IllegalStateException when the entry is not PENDING_APPROVAL")
    void testRejectRejectsNonPendingEntry() {

        JournalEntryRepository journalRepository = mock(JournalEntryRepository.class);
        AccountingPatternRepository patternRepository = mock(AccountingPatternRepository.class);
        SemanticPatternMatchingService semanticMatcher = mock(SemanticPatternMatchingService.class);

        JournalEntry alreadyRejected = pendingEntryWithDebitLine();
        alreadyRejected.setStatus("REJECTED");
        UUID entryId = alreadyRejected.getEntryId();

        when(journalRepository.findById(entryId)).thenReturn(Optional.of(alreadyRejected));

        JournalEntryApprovalService service =
                new JournalEntryApprovalService(journalRepository, patternRepository, semanticMatcher);

        assertThrows(IllegalStateException.class, () -> service.reject(entryId));
    }
}
