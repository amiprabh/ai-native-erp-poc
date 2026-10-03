
package com.aierp.domain.core;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entries", schema = "core_finance")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID entryId;

    private String sourceInvoiceId;
    private Instant postedAt = Instant.now();
    private String status; // "POSTED", "PENDING_APPROVAL", "POSTED_AFTER_APPROVAL", "REJECTED", "REVERSED"
    private String vendorName;
    private String descriptionFeature;
    private String source; // "HISTORICAL_PATTERN", "SEMANTIC_MATCH", or the LLM model name

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerLine> ledgerLines = new ArrayList<>();

    public JournalEntry() {}

    public void addLedgerLine(LedgerLine line) {
        ledgerLines.add(line);
        line.setJournalEntry(this);
    }

    public UUID getEntryId() { return entryId; }
    public void setEntryId(UUID entryId) { this.entryId = entryId; }
    public String getSourceInvoiceId() { return sourceInvoiceId; }
    public void setSourceInvoiceId(String sourceInvoiceId) { this.sourceInvoiceId = sourceInvoiceId; }
    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }
    public String getDescriptionFeature() { return descriptionFeature; }
    public void setDescriptionFeature(String descriptionFeature) { this.descriptionFeature = descriptionFeature; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public List<LedgerLine> getLedgerLines() { return ledgerLines; }
    public void setLedgerLines(List<LedgerLine> ledgerLines) { this.ledgerLines = ledgerLines; }
}