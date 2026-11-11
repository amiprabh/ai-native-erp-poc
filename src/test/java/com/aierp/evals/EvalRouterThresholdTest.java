package com.aierp.evals;

import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import com.aierp.repository.AccountingPatternRepository;
import com.aierp.service.DecisionRouterService;
import com.aierp.service.LlmInferenceService;
import com.aierp.service.SemanticPatternMatchingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalRouterThresholdTest {
    @Test
    void configuredThresholdRoutesConfidenceBelowPointNinetyThreeToReview() {
        AccountingPatternRepository patterns = mock(AccountingPatternRepository.class);
        SemanticPatternMatchingService semantic = mock(SemanticPatternMatchingService.class);
        LlmInferenceService llm = mock(LlmInferenceService.class);
        when(patterns.findTopByVendorAndDescriptionFeature(any(), any())).thenReturn(Optional.empty());
        when(semantic.findSimilarPattern(any(), any())).thenReturn(Optional.empty());
        when(llm.inferGlDistribution(any(), any(), any(), any())).thenReturn(new JournalDistributionProposal(
                List.of(new LineDistribution("610500", "ENG", new BigDecimal("125.00"), "DEBIT")),
                "test-model", 0.92));

        DecisionRouterService router = new DecisionRouterService(patterns, semantic, llm);
        router.setLlmReviewThreshold(0.93);

        var result = router.resolveAccountingDistribution("eval-threshold", "Example Vendor", "Cloud service",
                new BigDecimal("125.00"));
        assertTrue(result.requiresHumanApproval());
    }
}
