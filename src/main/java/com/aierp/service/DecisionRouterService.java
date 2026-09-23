package com.aierp.service;

import com.aierp.domain.ai.AccountingPattern;
import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import com.aierp.repository.AccountingPatternRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class DecisionRouterService {

    private final AccountingPatternRepository patternRepository;
    private final LlmInferenceService llmService;

    public DecisionRouterService(AccountingPatternRepository patternRepository, LlmInferenceService llmService) {
        this.patternRepository = patternRepository;
        this.llmService = llmService;
    }

    public JournalDistributionProposal resolveAccountingDistribution(String vendorName, String lineDescription, BigDecimal amount) {
        // Step 1: Check deterministic historical patterns (Bypasses LLM call)
        Optional<AccountingPattern> matchedPattern = patternRepository.findTopByVendorAndDescriptionFeature(vendorName, lineDescription);
        
        if (matchedPattern.isPresent()) {
            AccountingPattern pattern = matchedPattern.get();
            return new JournalDistributionProposal(
                List.of(new LineDistribution(pattern.getGlAccountId(), pattern.getDepartmentId(), amount, "DEBIT")),
                "HISTORICAL_PATTERN",
                1.00 // Maximum confidence for learned rules
            );
        }

        // Step 2: Fall back to selective LLM reasoning for ambiguous items
        JournalDistributionProposal aiProposal = llmService.inferGlDistribution(vendorName, lineDescription, amount);
        
        // Step 3: Flag low-confidence outputs for human review queue
        if (aiProposal.confidenceScore() < 0.90) {
            aiProposal.setRequiresHumanApproval(true);
        }

        return aiProposal;
    }
}