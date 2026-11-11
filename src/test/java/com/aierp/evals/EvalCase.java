package com.aierp.evals;

import java.math.BigDecimal;
import java.util.List;

public record EvalCase(
        String id,
        String vendor,
        String description,
        BigDecimal amount,
        String expectedGl,
        String expectedDept,
        List<String> tags) {
}
