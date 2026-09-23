package com.aierp.dto;

import java.math.BigDecimal;

public record LineDistribution(
    String glAccountId,
    String departmentId,
    BigDecimal amount,
    String type // "DEBIT" or "CREDIT"
) {}