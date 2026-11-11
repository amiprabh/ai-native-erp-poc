package com.aierp.evals;

import com.aierp.dto.JournalDistributionProposal;
import com.aierp.service.DecisionRouterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in integration evaluation. It calls the configured router and may use PostgreSQL,
 * pgvector, Kafka configuration, and the live model API. Never runs in the default test suite.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "eval", matches = "true")
class RouterEvalTest {
    @Autowired private DecisionRouterService router;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void evaluateRouterAgainstLabeledApCodingDataset() throws Exception {
        Path dataset = Path.of(System.getProperty("eval.dataset", EvalDatasetLoader.DEFAULT_DATASET.toString()));
        List<EvalCase> cases = EvalDatasetLoader.load(dataset, objectMapper);
        List<String> errors = EvalDatasetLoader.validate(cases);
        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));

        long delayMs = Long.parseLong(System.getProperty("eval.delayMs", "1500"));
        List<EvalPrediction> predictions = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            JournalDistributionProposal proposal = router.resolveAccountingDistribution(
                    "eval-" + c.id(), c.vendor(), c.description(), c.amount());
            predictions.add(new EvalPrediction(c, proposal, tierFor(proposal.source())));
            if (delayMs > 0 && i < cases.size() - 1) sleep(delayMs);
        }

        Path[] reports = EvalReportWriter.write(Path.of("target", "eval-reports"), dataset, predictions, objectMapper);
        System.out.println("Evaluation Markdown report: " + reports[0].toAbsolutePath());
        System.out.println("Evaluation JSON report: " + reports[1].toAbsolutePath());
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(EvalMetrics.summary(predictions)));

        String maxUnsafe = System.getProperty("eval.maxUnsafeRate");
        if (maxUnsafe != null && !maxUnsafe.isBlank()) {
            double actual = EvalMetrics.ratio(predictions.stream().filter(EvalPrediction::unsafeAutoPost).count(), predictions.size());
            assertTrue(actual <= Double.parseDouble(maxUnsafe),
                    () -> "Unsafe auto-post rate " + actual + " exceeds configured maximum " + maxUnsafe);
        }
    }

    private static String tierFor(String source) {
        if ("HISTORICAL_PATTERN".equals(source)) return "HISTORICAL_PATTERN";
        if ("SEMANTIC_MATCH".equals(source)) return "SEMANTIC_MATCH";
        if (source != null && (source.contains("FALLBACK") || source.contains("QUOTA_EXCEEDED"))) return "FALLBACK";
        return "LLM";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while throttling live evaluation calls", e);
        }
    }
}
