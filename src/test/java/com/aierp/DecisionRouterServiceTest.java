package com.aierp;

import com.aierp.domain.ai.AccountingPattern;
import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import com.aierp.repository.AccountingPatternRepository;
import com.aierp.service.DecisionRouterService;
import com.aierp.service.LlmInferenceService;
import com.aierp.service.SemanticPatternMatchingService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionRouterServiceTest {

    @Test
    @DisplayName("Should use historical pattern and bypass semantic matching and LLM")
    void testPatternMatchBypassesSemanticAndLlm() {

        AccountingPatternRepository patternRepo =
            mock(AccountingPatternRepository.class);

        SemanticPatternMatchingService semanticMatcher =
            mock(SemanticPatternMatchingService.class);

        LlmInferenceService llmService =
            mock(LlmInferenceService.class);

        AccountingPattern existingPattern =
            new AccountingPattern(
                "Amazon Web Services",
                "EC2",
                "610500",
                "ENG"
            );

        when(
            patternRepo.findTopByVendorAndDescriptionFeature(
                "Amazon Web Services",
                "EC2 Compute"
            )
        ).thenReturn(Optional.of(existingPattern));

        DecisionRouterService router =
            new DecisionRouterService(
                patternRepo,
                semanticMatcher,
                llmService
            );

        JournalDistributionProposal proposal =
            router.resolveAccountingDistribution(
                "Test-DR-01",
                "Amazon Web Services",
                "EC2 Compute",
                new BigDecimal("500.00")
            );

        assertEquals(
            "HISTORICAL_PATTERN",
            proposal.source()
        );

        assertEquals(
            1.00,
            proposal.confidenceScore()
        );

        // Tier 1 should bypass both Tier 2 and Tier 3.
        verify(semanticMatcher, never()).findSimilarPattern(
            any(),
            any()
        );

        verify(llmService, never()).inferGlDistribution(
            any(),
            any(),
            any(),
            any()
        );
    }


    @Test
    @DisplayName("Should use semantic pattern and bypass LLM")
    void testSemanticMatchBypassesLlm() {

        AccountingPatternRepository patternRepo =
            mock(AccountingPatternRepository.class);

        SemanticPatternMatchingService semanticMatcher =
            mock(SemanticPatternMatchingService.class);

        LlmInferenceService llmService =
            mock(LlmInferenceService.class);

        // Tier 1: no exact match.
        when(
            patternRepo.findTopByVendorAndDescriptionFeature(
                "Amazon Web Services",
                "EC2 Compute"
            )
        ).thenReturn(Optional.empty());

        // Tier 2: semantic match found.
        SemanticPatternMatchingService.MatchedPattern semanticMatch =
            new SemanticPatternMatchingService.MatchedPattern(
                "610500",
                "ENG",
                0.92
            );

        when(
            semanticMatcher.findSimilarPattern(
                "Amazon Web Services",
                "EC2 Compute"
            )
        ).thenReturn(Optional.of(semanticMatch));

        DecisionRouterService router =
            new DecisionRouterService(
                patternRepo,
                semanticMatcher,
                llmService
            );

        JournalDistributionProposal proposal =
            router.resolveAccountingDistribution(
                "Test-DR-SEM-01",
                "Amazon Web Services",
                "EC2 Compute",
                new BigDecimal("500.00")
            );

        assertEquals(
            "SEMANTIC_MATCH",
            proposal.source()
        );

        assertEquals(
            0.92,
            proposal.confidenceScore()
        );

        assertEquals(
            "610500",
            proposal.lines().get(0).glAccountId()
        );

        assertEquals(
            "ENG",
            proposal.lines().get(0).departmentId()
        );

        // Semantic match should bypass the LLM.
        verify(llmService, never()).inferGlDistribution(
            any(),
            any(),
            any(),
            any()
        );
    }


    @Test
    @DisplayName("Should flag human approval when LLM confidence is below threshold")
    void testLowConfidenceRoutesToHumanApproval() {

        AccountingPatternRepository patternRepo =
            mock(AccountingPatternRepository.class);

        SemanticPatternMatchingService semanticMatcher =
            mock(SemanticPatternMatchingService.class);

        LlmInferenceService llmService =
            mock(LlmInferenceService.class);

        // Tier 1: no exact match.
        when(
            patternRepo.findTopByVendorAndDescriptionFeature(
                any(),
                any()
            )
        ).thenReturn(Optional.empty());

        // Tier 2: no semantic match.
        when(
            semanticMatcher.findSimilarPattern(
                any(),
                any()
            )
        ).thenReturn(Optional.empty());

        JournalDistributionProposal lowConfidence =
            new JournalDistributionProposal(
                List.of(
                    new LineDistribution(
                        "610500",
                        "ENG",
                        new BigDecimal("500.00"),
                        "DEBIT"
                    )
                ),
                "gemini-3-flash-preview",
                0.72
            );

        when(
            llmService.inferGlDistribution(
                eq("Test-DR-02"),
                any(),
                any(),
                any()
            )
        ).thenReturn(lowConfidence);

        DecisionRouterService router =
            new DecisionRouterService(
                patternRepo,
                semanticMatcher,
                llmService
            );

        JournalDistributionProposal result =
            router.resolveAccountingDistribution(
                "Test-DR-02",
                "New Vendor Inc",
                "Unclassified expense",
                new BigDecimal("500.00")
            );

        assertTrue(result.requiresHumanApproval());

        verify(llmService).inferGlDistribution(
            eq("Test-DR-02"),
            eq("New Vendor Inc"),
            eq("Unclassified expense"),
            eq(new BigDecimal("500.00"))
        );
    }


    @Test
    @DisplayName("Should NOT flag human approval when LLM confidence meets threshold")
    void testHighConfidenceSkipsApproval() {

        AccountingPatternRepository patternRepo =
            mock(AccountingPatternRepository.class);

        SemanticPatternMatchingService semanticMatcher =
            mock(SemanticPatternMatchingService.class);

        LlmInferenceService llmService =
            mock(LlmInferenceService.class);

        // Tier 1: no exact match.
        when(
            patternRepo.findTopByVendorAndDescriptionFeature(
                any(),
                any()
            )
        ).thenReturn(Optional.empty());

        // Tier 2: no semantic match.
        when(
            semanticMatcher.findSimilarPattern(
                any(),
                any()
            )
        ).thenReturn(Optional.empty());

        JournalDistributionProposal highConfidence =
            new JournalDistributionProposal(
                List.of(
                    new LineDistribution(
                        "610500",
                        "ENG",
                        new BigDecimal("500.00"),
                        "DEBIT"
                    )
                ),
                "gemini-3-flash-preview",
                0.95
            );

        when(
            llmService.inferGlDistribution(
                eq("Test-DR-03"),
                any(),
                any(),
                any()
            )
        ).thenReturn(highConfidence);

        DecisionRouterService router =
            new DecisionRouterService(
                patternRepo,
                semanticMatcher,
                llmService
            );

        JournalDistributionProposal result =
            router.resolveAccountingDistribution(
                "Test-DR-03",
                "New Vendor Inc",
                "Unclassified expense",
                new BigDecimal("500.00")
            );

        assertFalse(result.requiresHumanApproval());

        verify(llmService).inferGlDistribution(
            eq("Test-DR-03"),
            eq("New Vendor Inc"),
            eq("Unclassified expense"),
            eq(new BigDecimal("500.00"))
        );
    }
}