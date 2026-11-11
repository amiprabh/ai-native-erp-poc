package com.aierp.evals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EvalReportWriter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC);
    private EvalReportWriter() { }

    static Path[] write(Path outputDirectory, Path dataset, List<EvalPrediction> predictions,
                        ObjectMapper mapper) throws IOException {
        Files.createDirectories(outputDirectory);
        String stamp = STAMP.format(Instant.now());
        Path jsonPath = outputDirectory.resolve("eval-router-" + stamp + ".json");
        Path markdownPath = outputDirectory.resolve("eval-router-" + stamp + ".md");

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("generatedAt", Instant.now().toString());
        document.put("dataset", dataset.toString());
        document.put("metrics", EvalMetrics.summary(predictions));
        document.put("predictions", predictions.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.input().id()); row.put("vendor", p.input().vendor());
            row.put("description", p.input().description()); row.put("amount", p.input().amount());
            row.put("expectedGl", p.input().expectedGl()); row.put("expectedDept", p.input().expectedDept());
            row.put("predictedGl", p.predictedGl()); row.put("predictedDept", p.predictedDept());
            row.put("tags", p.input().tags()); row.put("tier", p.tier());
            row.put("source", p.proposal().source()); row.put("confidence", p.proposal().confidenceScore());
            row.put("requiresReview", p.requiresReview()); row.put("correct", p.correct());
            row.put("unsafeAutoPost", p.unsafeAutoPost());
            row.put("suspenseAutoPostPolicyViolation", p.suspenseAutoPost());
            return row;
        }).toList());
        ObjectMapper pretty = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        pretty.writeValue(jsonPath.toFile(), document);
        Files.writeString(markdownPath, toMarkdown(dataset, predictions, document.get("metrics")));
        return new Path[]{markdownPath, jsonPath};
    }

    private static String toMarkdown(Path dataset, List<EvalPrediction> predictions, Object metricsObject) {
        @SuppressWarnings("unchecked") Map<String, Object> metrics = (Map<String, Object>) metricsObject;
        StringBuilder md = new StringBuilder();
        md.append("# AP coding router evaluation\n\n");
        md.append("- Dataset: `").append(dataset).append("`\n");
        md.append("- Cases: ").append(metrics.get("total")).append("\n");
        md.append("- Accuracy: ").append(pct(metrics.get("accuracy"))).append("\n");
        md.append("- Auto-post precision: ").append(pct(metrics.get("autoPostPrecision"))).append("\n");
        md.append("- Review rate: ").append(pct(metrics.get("reviewRate"))).append("\n");
        md.append("- Unsafe auto-posts: ").append(metrics.get("unsafeAutoPostCount"))
                .append(" / ").append(metrics.get("total")).append(" (all-line rate ")
                .append(pct(metrics.get("unsafeAutoPostRateOfAllLines"))).append(")\n");
        md.append("- Unsafe auto-post Wilson 95% upper bound: ")
                .append(pct(metrics.get("unsafeAutoPostWilson95UpperBound"))).append("\n");
        md.append("- Suspense auto-posts (policy violations): ").append(metrics.get("suspenseAutoPostCount"))
                .append("\n\n");
        md.append("## Per-case results\n\n");
        md.append("| ID | Tier | Expected GL / Dept | Predicted GL / Dept | Confidence | Review? | Correct? |\n");
        md.append("|---|---|---|---|---:|---|---|\n");
        for (EvalPrediction p : predictions) {
            md.append("| ").append(p.input().id()).append(" | ").append(p.tier()).append(" | ")
                    .append(p.input().expectedGl()).append(" / ").append(p.input().expectedDept()).append(" | ")
                    .append(p.predictedGl()).append(" / ").append(p.predictedDept()).append(" | ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", p.proposal().confidenceScore())).append(" | ")
                    .append(p.requiresReview() ? "yes" : "no").append(" | ")
                    .append(p.correct() ? "yes" : "NO").append(" |\n");
        }
        md.append("\n## Interpretation notes\n\n")
                .append("- Unsafe auto-post rate of all lines = wrong predictions that were not flagged for review / all cases.\n")
                .append("- Auto-post precision uses only cases not flagged for review.\n")
                .append("- The Wilson upper bound is calculated over auto-posted cases, with wrong auto-posts as failures.\n")
                .append("- This is a small, hand-labeled demo dataset; metrics are diagnostic, not a production safety guarantee.\n")
                .append("- Live router evaluation calls the configured model and may vary across runs.\n");
        return md.toString();
    }

    private static String pct(Object value) {
        if (!(value instanceof Number n)) return "n/a";
        return String.format(java.util.Locale.ROOT, "%.2f%%", n.doubleValue() * 100.0);
    }
}
