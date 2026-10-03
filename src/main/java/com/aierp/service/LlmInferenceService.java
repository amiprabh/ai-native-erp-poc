package com.aierp.service;

import com.aierp.domain.ai.AiDecisionLog;
import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import com.aierp.repository.AiDecisionLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
public class LlmInferenceService {

        @Value("${spring.ai.openai.chat.options.model}")
        private String modelName;
        private static final Logger log = LoggerFactory.getLogger(LlmInferenceService.class);
        private final ChatClient chatClient;
        private final AiDecisionLogRepository decisionLogRepository;
        private final ObjectMapper objectMapper;
        private static final String SYSTEM_PROMPT = """
                        You are an expert enterprise ERP accounting classifier.
                        Task: Assign a GL Account and Department to the incoming invoice line item
                        based on the vendor and description.

                        Valid GL Accounts:
                        - 610500: Infrastructure & Hosting (AWS, Azure, Cloud, Hosting)
                        - 610200: Software Subscriptions (SaaS, Licenses, Software)
                        - 620100: Travel & Entertainment (Flights, Hotels, Meals)
                        - 680000: General Operating Expenses (Misc, Office supplies)
                        - 999999: Suspense Account

                        Valid Departments:
                        - ENG: Engineering & IT
                        - SALES: Sales & Marketing
                        - GEN: General & Administrative
                        - SUSPENSE: Suspense Account Entries

                        Return ONLY a valid JSON object with: glAccountId, departmentId, and
                        confidenceScore (0.0 to 1.0).
                        """;
        private static final Set<String> VALID_GL_ACCOUNTS = Set.of("610500", "610200", "620100", "680000", "999999");
        private static final Set<String> VALID_DEPARTMENTS = Set.of("ENG", "SALES", "GEN", "SUSPENSE");

        public LlmInferenceService(ChatClient.Builder chatClientBuilder,
                        AiDecisionLogRepository decisionLogRepository,
                        ObjectMapper objectMapper) {
                this.chatClient = chatClientBuilder.build();
                this.decisionLogRepository = decisionLogRepository;
                this.objectMapper = objectMapper;
        }

        public JournalDistributionProposal inferGlDistribution(String transactionId, String vendorName,
                        String lineDescription, BigDecimal amount) {
                log.info("Executing Gemini inference for Vendor: '{}', Description: '{}', Amount: {}", vendorName,
                                lineDescription, amount);

                try {
                        String rawJson = chatClient.prompt()
                                        .system(SYSTEM_PROMPT)
                                        .user(u -> u.text(
                                                        "Vendor: {vendor}, Description: {description}, Amount: {amount}")
                                                        .param("vendor", vendorName)
                                                        .param("description", lineDescription)
                                                        .param("amount", amount.toString()))
                                        .call()
                                        .content(); // raw String from the model, no parsing yet

                        AiResponse response = objectMapper.readValue(rawJson, AiResponse.class); // manual parse

                        // system prompt 100% static (rules, valid-value lists, output format — nothing
                        // invoice-specific) and let .user(...) carry the only copy of the per-invoice
                        // data possibly containing prompt-injection risk

                        if (!VALID_GL_ACCOUNTS.contains(response.glAccountId())
                                        || !VALID_DEPARTMENTS.contains(response.departmentId())) {
                                throw new IllegalStateException("LLM returned an out-of-range GL account/department: "
                                                + response.glAccountId() + " / " + response.departmentId());
                        }
                        // Checking what Dept/Acct AI returned (or malformed, or null) and throwing
                        // error to route to the suspense-account fallback

                        LineDistribution debitLine = new LineDistribution(response.glAccountId(),
                                        response.departmentId(), amount,
                                        "DEBIT");
                        LineDistribution creditLine = new LineDistribution("210000", "CORP", amount, "CREDIT");
                        // Accounts Payable

                        logDecision(transactionId, modelName, response.confidenceScore(), rawJson); // logging AI
                                                                                                    // response
                        return new JournalDistributionProposal(
                                        List.of(debitLine, creditLine),
                                        modelName,
                                        Math.max(0.0, Math.min(1.0, response.confidenceScore())));
                        // ensures score (0.0 to 1.0)

                } catch (Exception e) {
                         if (e instanceof NonTransientAiException) {
                                logDecision(transactionId, modelName + "_AI_QUOTA_EXCEEDED", 0.00, null);
                                log.error("Gemini AI quota exceeded. Falling back to suspense account.", e);
                         }
                         else {
                                logDecision(transactionId, modelName + "_FALLBACK", 0.00, null);
                                log.error("Gemini Inference failed. Falling back to suspense account.", e);
                         }
                        LineDistribution fallbackDebit = new LineDistribution("999999", "SUSPENSE", amount, "DEBIT");
                        LineDistribution fallbackCredit = new LineDistribution("210000", "CORP", amount, "CREDIT");

                        JournalDistributionProposal proposal = new JournalDistributionProposal(
                                        List.of(fallbackDebit, fallbackCredit),
                                        modelName + "_FALLBACK",
                                        0.00);
                        proposal.setRequiresHumanApproval(true);
                        return proposal;
                }
        }

        private void logDecision(String transactionId, String model, double confidence, String rawResponseJson) {
                AiDecisionLog logEntry = new AiDecisionLog();
                logEntry.setTransactionId(transactionId);
                logEntry.setModelName(model);
                logEntry.setPromptVersion("v1");
                logEntry.setConfidenceScore(confidence);
                logEntry.setRawOutputJson(rawResponseJson); // exactly what the model returned, or null on failure
                decisionLogRepository.save(logEntry);
        }

        // Helper record for structured AI response
        private record AiResponse(String glAccountId, String departmentId, double confidenceScore) {
        }
}
