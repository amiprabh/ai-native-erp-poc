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
    private final SemanticPatternMatchingService semanticMatcher;
    private final LlmInferenceService llmService;

    public DecisionRouterService(AccountingPatternRepository patternRepository,
                                  SemanticPatternMatchingService semanticMatcher,
                                  LlmInferenceService llmService) {
        this.patternRepository = patternRepository;
        this.semanticMatcher = semanticMatcher;
        this.llmService = llmService;
    }

    public JournalDistributionProposal resolveAccountingDistribution(String transactionId, String vendorName,
            String lineDescription, BigDecimal amount) {

        // Tier 1: exact historical pattern match -- free, instant, no AI call.
        Optional<AccountingPattern> matchedPattern =
                patternRepository.findTopByVendorAndDescriptionFeature(vendorName, lineDescription);

        if (matchedPattern.isPresent()) {
            AccountingPattern pattern = matchedPattern.get();
            LineDistribution debitLine = new LineDistribution(pattern.getGlAccountId(),
                pattern.getDepartmentId(), amount,
                "DEBIT");
            LineDistribution creditLine = new LineDistribution("210000", "CORP", amount, "CREDIT");
            // Accounts Payable
            return new JournalDistributionProposal(
                List.of(debitLine, creditLine),
                "HISTORICAL_PATTERN",
                1.00
            );
        }

        // Tier 2: semantic similarity match -- catches near-duplicates the
        // exact lookup missed ("AWS" vs "Amazon Web Services") without
        // paying for an LLM call.
        Optional<SemanticPatternMatchingService.MatchedPattern> semanticMatch =
                semanticMatcher.findSimilarPattern(vendorName, lineDescription);

        if (semanticMatch.isPresent()) {
            var match = semanticMatch.get();
            LineDistribution debitLine = new LineDistribution(match.glAccountId(),
                match.departmentId(), amount,
                "DEBIT");
            LineDistribution creditLine = new LineDistribution("210000", "CORP", amount, "CREDIT");
            // Accounts Payable
            return new JournalDistributionProposal(
                List.of(debitLine, creditLine),
                "SEMANTIC_MATCH",
                match.similarityScore()
            );
        }

        // Tier 3: no precedent at all -- fall back to LLM reasoning, same as
        // before.
        JournalDistributionProposal aiProposal =
                llmService.inferGlDistribution(transactionId, vendorName, lineDescription, amount);

        if (aiProposal.confidenceScore() <= 0.90) {
            aiProposal.setRequiresHumanApproval(true);
        }

        return aiProposal;
    }
}