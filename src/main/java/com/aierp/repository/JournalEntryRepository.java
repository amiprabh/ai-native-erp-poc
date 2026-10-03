package com.aierp.repository;

import com.aierp.domain.core.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JournalEntryRepository
                extends JpaRepository<JournalEntry, UUID> {

        List<JournalEntry> findBySourceInvoiceId(String sourceInvoiceId);

        boolean existsBySourceInvoiceIdAndStatus(
                        String sourceInvoiceId,
                        String status);

        List<JournalEntry> findByStatus(String status);
}