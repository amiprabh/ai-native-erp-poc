package com.aierp;

import com.aierp.domain.ai.AiDecisionLog;
import com.aierp.dto.JournalDistributionProposal;
import com.aierp.repository.AiDecisionLogRepository;
import com.aierp.service.LlmInferenceService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmInferenceServiceTest {

    @Test
    @DisplayName("Should fall back to suspense account when the LLM call fails")
    void testLlmFailureFallsBackToSuspense() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        AiDecisionLogRepository decisionLogRepository = mock(AiDecisionLogRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("Simulated Gemini outage"));

        LlmInferenceService service = new LlmInferenceService(builder, decisionLogRepository, objectMapper);

        JournalDistributionProposal result = service.inferGlDistribution(
            "TEST-LIS-01", "Unknown Vendor", "???", new BigDecimal("42.00"));

        assertTrue(result.requiresHumanApproval());
        assertEquals(0.00, result.confidenceScore());
        assertTrue(result.lines().stream().anyMatch(l -> "SUSPENSE".equals(l.departmentId())));
        verify(decisionLogRepository).save(any(AiDecisionLog.class));
    }
}