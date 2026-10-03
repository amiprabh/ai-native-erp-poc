package com.aierp.dto;

import java.math.BigDecimal;

public record LedgerLineSummary(
    String glAccountId,
    String departmentId,
    BigDecimal amount,
    String side
) {}