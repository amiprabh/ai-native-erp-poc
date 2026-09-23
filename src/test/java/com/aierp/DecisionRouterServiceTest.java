package com.aierp;

import com.aierp.domain.ai.AccountingPattern;
import com.aierp.dto.JournalDistributionProposal;
import com.aierp.repository.AccountingPatternRepository;
import com.aierp.service.DecisionRouterService;
import com.aierp.service.LlmInferenceService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DecisionRouterServiceTest {

    @Test
    @DisplayName("Should use historical pattern and bypass LLM when matching rule exists")
    void testPatternMatchBypassesLlm() {
        AccountingPatternRepository patternRepo = mock(AccountingPatternRepository.class);
        LlmInferenceService llmService = mock(LlmInferenceService.class);

        AccountingPattern existingPattern = new AccountingPattern("Amazon Web Services", "EC2", "610500", "ENG");
        when(patternRepo.findTopByVendorAndDescriptionFeature("Amazon Web Services", "EC2 Compute"))
            .thenReturn(Optional.of(existingPattern));

        DecisionRouterService router = new DecisionRouterService(patternRepo, llmService);

        JournalDistributionProposal proposal = router.resolveAccountingDistribution(
            "Amazon Web Services", "EC2 Compute", new BigDecimal("500.00")
        );

        assertEquals("HISTORICAL_PATTERN", proposal.source());
        assertEquals(1.00, proposal.confidenceScore());
        
        // Assert LLM was never called
        verify(llmService, never()).inferGlDistribution(any(), any(), any());
    }
}