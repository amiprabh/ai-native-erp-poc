package com.aierp.domain.core;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ledger_lines", schema = "core_finance")
public class LedgerLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id")
    private JournalEntry journalEntry;

    private String glAccountId;
    private String departmentId;
    private BigDecimal amount;
    private String side; // "DEBIT" or "CREDIT"

    public LedgerLine() {}

    public UUID getLineId() { return lineId; }
    public void setLineId(UUID lineId) { this.lineId = lineId; }
    public JournalEntry getJournalEntry() { return journalEntry; }
    public void setJournalEntry(JournalEntry journalEntry) { this.journalEntry = journalEntry; }
    public String getGlAccountId() { return glAccountId; }
    public void setGlAccountId(String glAccountId) { this.glAccountId = glAccountId; }
    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
}