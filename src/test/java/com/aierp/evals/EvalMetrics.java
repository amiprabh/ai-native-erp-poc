package com.aierp.evals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class EvalMetrics {
    private EvalMetrics() { }

    static Map<String, Object> summary(List<EvalPrediction> predictions) {
        long total = predictions.size();
        long correct = predictions.stream().filter(EvalPrediction::correct).count();
        long review = predictions.stream().filter(EvalPrediction::requiresReview).count();
        long auto = total - review;
        long unsafe = predictions.stream().filter(EvalPrediction::unsafeAutoPost).count();
        long correctAuto = predictions.stream().filter(p -> p.autoPost() && p.correct()).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("correct", correct);
        out.put("accuracy", ratio(correct, total));
        out.put("reviewCount", review);
        out.put("reviewRate", ratio(review, total));
        out.put("autoPostCount", auto);
        out.put("autoPostPrecision", ratio(correctAuto, auto));
        out.put("unsafeAutoPostCount", unsafe);
        out.put("unsafeAutoPostRateOfAllLines", ratio(unsafe, total));
        out.put("unsafeAutoPostRateOfAutoPosts", ratio(unsafe, auto));
        out.put("unsafeAutoPostWilson95UpperBound", wilsonUpper(unsafe, auto));
        out.put("suspenseAutoPostCount", predictions.stream().filter(EvalPrediction::suspenseAutoPost).count());
        out.put("byTier", grouped(predictions, EvalPrediction::tier));
        Map<String, List<EvalPrediction>> byTag = new TreeMap<>();
        for (EvalPrediction p : predictions) {
            for (String tag : p.input().tags()) byTag.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(p);
        }
        Map<String, Object> tagMetrics = new LinkedHashMap<>();
        byTag.forEach((tag, values) -> tagMetrics.put(tag, compactGroup(values)));
        out.put("byTag", tagMetrics);
        out.put("llmCalibration", calibration(predictions));
        out.put("thresholdSweep", thresholdSweep(predictions));
        return out;
    }

    static Map<String, Object> compactGroup(List<EvalPrediction> values) {
        long total = values.size();
        long correct = values.stream().filter(EvalPrediction::correct).count();
        long review = values.stream().filter(EvalPrediction::requiresReview).count();
        long auto = total - review;
        long unsafe = values.stream().filter(EvalPrediction::unsafeAutoPost).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total); m.put("accuracy", ratio(correct, total));
        m.put("reviewRate", ratio(review, total)); m.put("autoPostCount", auto);
        m.put("autoPostPrecision", ratio(values.stream().filter(p -> p.autoPost() && p.correct()).count(), auto));
        m.put("unsafeAutoPostCount", unsafe); m.put("unsafeAutoPostRateOfAllLines", ratio(unsafe, total));
        m.put("suspenseAutoPostCount", values.stream().filter(EvalPrediction::suspenseAutoPost).count());
        return m;
    }

    static Map<String, Object> grouped(List<EvalPrediction> predictions, java.util.function.Function<EvalPrediction, String> keyFn) {
        Map<String, List<EvalPrediction>> groups = new TreeMap<>();
        predictions.forEach(p -> groups.computeIfAbsent(keyFn.apply(p), ignored -> new ArrayList<>()).add(p));
        Map<String, Object> result = new LinkedHashMap<>();
        groups.forEach((key, values) -> result.put(key, compactGroup(values)));
        return result;
    }

    static Map<String, Object> calibration(List<EvalPrediction> predictions) {
        List<EvalPrediction> llm = predictions.stream().filter(p -> "LLM".equals(p.tier())).toList();
        List<Map<String, Object>> bins = new ArrayList<>();
        double ece = 0.0;
        for (int i = 0; i < 10; i++) {
            final int bin = i;
            List<EvalPrediction> values = llm.stream().filter(p -> {
                double c = p.proposal().confidenceScore();
                return c >= bin / 10.0 && (bin == 9 ? c <= 1.0 : c < (bin + 1) / 10.0);
            }).toList();
            if (values.isEmpty()) continue;
            double confidence = values.stream().mapToDouble(p -> p.proposal().confidenceScore()).average().orElse(0);
            double accuracy = values.stream().filter(EvalPrediction::correct).count() / (double) values.size();
            ece += values.size() / (double) Math.max(1, llm.size()) * Math.abs(accuracy - confidence);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("lowerInclusive", bin / 10.0); entry.put("upperInclusive", (bin + 1) / 10.0);
            entry.put("count", values.size()); entry.put("meanConfidence", confidence); entry.put("accuracy", accuracy);
            bins.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", llm.size());
        result.put("expectedCalibrationError", llm.isEmpty() ? null : ece);
        result.put("bins", bins);
        return result;
    }

    static List<Map<String, Object>> thresholdSweep(List<EvalPrediction> predictions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int step = 50; step <= 99; step++) {
            double threshold = step / 100.0;
            long auto = 0, unsafe = 0, suspenseAutoPosts = 0;
            for (EvalPrediction p : predictions) {
                boolean isLlmTier = "LLM".equals(p.tier());
                boolean wouldAutoPost = isLlmTier
                        ? p.proposal().confidenceScore() >= threshold
                        : !p.requiresReview();
                if (wouldAutoPost) {
                    auto++;
                    if (!p.correct()) unsafe++;
                    if ("999999".equals(p.predictedGl()) || "SUSPENSE".equals(p.predictedDept())) suspenseAutoPosts++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("threshold", threshold); row.put("autoPostCount", auto); row.put("wrongAutoPostCount", unsafe);
            row.put("unsafeRateAmongAutoPosts", ratio(unsafe, auto));
            row.put("suspenseAutoPostCount", suspenseAutoPosts);
            row.put("wilson95UpperBound", wilsonUpper(unsafe, auto));
            result.add(row);
        }
        return result;
    }

    static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator / (double) denominator;
    }

    static Double wilsonUpper(long failures, long trials) {
        if (trials == 0) return null;
        final double z = 1.959963984540054;
        double n = trials, p = failures / n, z2 = z * z;
        return (p + z2 / (2 * n) + z * Math.sqrt((p * (1 - p) + z2 / (4 * n)) / n)) / (1 + z2 / n);
    }
}
