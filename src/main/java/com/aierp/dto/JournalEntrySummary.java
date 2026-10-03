package com.aierp.dto;

import java.time.Instant;
import java.util.List;

public record JournalEntrySummary(
    String entryId,
    String sourceInvoiceId,
    String vendorName,
    String descriptionFeature,
    String source,
    String status,
    Instant postedAt,
    List<LedgerLineSummary> lines
) {}