package com.aierp.evals;

import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalMetricsTest {
    @Test
    void unsafeAutoPostCountsWrongUnreviewedPredictionButNotReviewedWrongPrediction() {
        EvalCase correctCase = new EvalCase("1", "A", "desc", BigDecimal.ONE, "610500", "ENG", List.of("easy"));
        EvalCase wrongCase = new EvalCase("2", "B", "desc", BigDecimal.ONE, "610500", "ENG", List.of("ambiguous"));
        EvalPrediction correct = prediction(correctCase, "610500", "ENG", "HISTORICAL_PATTERN", 1.0, false);
        EvalPrediction unsafe = prediction(wrongCase, "610200", "GEN", "LLM", 0.95, false);
        EvalPrediction reviewed = prediction(wrongCase, "610200", "GEN", "LLM_FALLBACK", 0.0, true);

        Map<String, Object> summary = EvalMetrics.summary(List.of(correct, unsafe, reviewed));
        assertEquals(3L, summary.get("total"));
        assertEquals(1L, summary.get("unsafeAutoPostCount"));
        assertEquals(1L, summary.get("reviewCount"));
        assertEquals(2L, summary.get("autoPostCount"));
        assertEquals(0.5, summary.get("autoPostPrecision"));
        assertEquals(1.0 / 3.0, summary.get("unsafeAutoPostRateOfAllLines"));
    }

    @Test
    void wilsonUpperBoundIsNullWhenThereAreNoAutoPosts() {
        assertNull(EvalMetrics.wilsonUpper(0, 0));
        assertTrue(EvalMetrics.wilsonUpper(1, 10) > 0.1);
        assertTrue(EvalMetrics.wilsonUpper(0, 10) > 0.0);
    }

    private EvalPrediction prediction(EvalCase input, String gl, String dept, String source,
                                      double confidence, boolean review) {
        JournalDistributionProposal proposal = new JournalDistributionProposal(List.of(
                new LineDistribution(gl, dept, BigDecimal.ONE, "DEBIT")), source, confidence);
        proposal.setRequiresHumanApproval(review);
        String tier = source.equals("HISTORICAL_PATTERN") ? "HISTORICAL_PATTERN"
                : source.contains("FALLBACK") ? "FALLBACK" : "LLM";
        return new EvalPrediction(input, proposal, tier);
    }
}
