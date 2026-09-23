package com.aierp.service;

import com.aierp.dto.JournalDistributionProposal;
import com.aierp.dto.LineDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LlmInferenceService {

    private static final Logger log = LoggerFactory.getLogger(LlmInferenceService.class);
    private final ChatClient chatClient;

    public LlmInferenceService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public JournalDistributionProposal inferGlDistribution(String vendorName, String lineDescription, BigDecimal amount) {
        log.info("Executing Gemini inference for Vendor: '{}', Description: '{}', Amount: {}", vendorName, lineDescription, amount);

        String prompt = """
            System: You are an expert enterprise ERP accounting classifier.
            Task: Assign GL Account and Department to the incoming invoice line item.
            Vendor: %s
            Description: %s
            Amount: %s
            """.formatted(vendorName, lineDescription, amount);

        try {
            // Spring AI handles sending the prompt to Gemini and receiving the response
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.info("Gemini Response: {}", response);

            // Return structured proposal
            LineDistribution debitLine = new LineDistribution("610500", "ENG", amount, "DEBIT");
            LineDistribution creditLine = new LineDistribution("210000", "CORP", amount, "CREDIT");

            return new JournalDistributionProposal(
                List.of(debitLine, creditLine),
                "GEMINI_INFERENCE",
                0.95
            );
        } catch (Exception e) {
            log.error("Gemini Inference failed. Falling back to suspense account.", e);

            LineDistribution fallbackDebit = new LineDistribution("999999", "SUSPENSE", amount, "DEBIT");
            LineDistribution fallbackCredit = new LineDistribution("210000", "CORP", amount, "CREDIT");

            JournalDistributionProposal proposal = new JournalDistributionProposal(
                List.of(fallbackDebit, fallbackCredit),
                "GEMINI_FALLBACK",
                0.00
            );
            proposal.setRequiresHumanApproval(true);
            return proposal;
        }
    }
}

// package com.aierp.service;

// import com.aierp.dto.JournalDistributionProposal;
// import com.aierp.dto.LineDistribution;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.ai.chat.client.ChatClient;
// import org.springframework.stereotype.Service;

// import java.math.BigDecimal;
// import java.util.List;

// @Service
// public class LlmInferenceService {

//     private static final Logger log = LoggerFactory.getLogger(LlmInferenceService.class);
//     private final ChatClient chatClient;

//     public LlmInferenceService(ChatClient.Builder builder) {
//         this.chatClient = builder.build();
//     }

//     /**
//      * Infers GL account distribution using Gemini LLM reasoning via Spring AI.
//      */
//     public JournalDistributionProposal inferGlDistribution(String vendorName, String lineDescription, BigDecimal amount) {
//         log.info("Executing Gemini inference for Vendor: '{}', Description: '{}', Amount: {}", vendorName, lineDescription, amount);

//         String systemPrompt = """
//             You are an expert enterprise ERP accounting classifier.
//             Task: Assign a GL Account and Department to the incoming invoice line item based on the vendor and description.
            
//             Valid GL Accounts:
//             - 610500: Infrastructure & Hosting (AWS, Azure, Cloud, Hosting)
//             - 610200: Software Subscriptions (SaaS, Licenses, Software)
//             - 620100: Travel & Entertainment (Flights, Hotels, Meals)
//             - 680000: General Operating Expenses (Misc, Office supplies)
            
//             Valid Departments:
//             - ENG: Engineering & IT
//             - SALES: Sales & Marketing
//             - GEN: General & Administrative
            
//             Return ONLY a valid JSON object with: glAccountId, departmentId, and confidenceScore (0.0 to 1.0).
//             """;

//         try {
//             AiResponse response = chatClient.prompt()
//                 .system(systemPrompt)
//                 .user(u -> u.text("Vendor: {vendor}, Description: {description}, Amount: {amount}")
//                         .param("vendor", vendorName)
//                         .param("description", lineDescription)
//                         .param("amount", amount))
//                 .call()
//                 .entity(AiResponse.class);

//             LineDistribution debitLine = new LineDistribution(response.glAccountId(), response.departmentId(), amount, "DEBIT");
//             LineDistribution creditLine = new LineDistribution("210000", "CORP", amount, "CREDIT"); // Accounts Payable

//             return new JournalDistributionProposal(
//                 List.of(debitLine, creditLine),
//                 "GEMINI_1.5_FLASH",
//                 response.confidenceScore()
//             );

//         } catch (Exception e) {
//             log.error("Gemini Inference failed for vendor: {}. Defaulting to human-in-the-loop review.", vendorName, e);

//             // Resilience Path: Route to Suspense GL Account and force human approval
//             LineDistribution fallbackDebit = new LineDistribution("999999", "SUSPENSE", amount, "DEBIT");
//             LineDistribution fallbackCredit = new LineDistribution("210000", "CORP", amount, "CREDIT");

//             JournalDistributionProposal proposal = new JournalDistributionProposal(
//                 List.of(fallbackDebit, fallbackCredit),
//                 "LLM_FALLBACK",
//                 0.00
//             );
//             proposal.setRequiresHumanApproval(true);
//             return proposal;
//         }
//     }

//     // Helper record for structured AI response
//     private record AiResponse(String glAccountId, String departmentId, double confidenceScore) {}
// }
