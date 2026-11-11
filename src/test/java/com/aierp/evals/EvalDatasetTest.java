package com.aierp.evals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvalDatasetTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void datasetHasUniqueIdsValidLabelsAndAllRequiredSlices() throws Exception {
        List<EvalCase> cases = EvalDatasetLoader.load(EvalDatasetLoader.DEFAULT_DATASET, mapper);
        assertEquals(60, cases.size(), "Update this assertion intentionally when the benchmark dataset changes");
        List<String> errors = EvalDatasetLoader.validate(cases);
        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
    }

    @Test
    void datasetPathCanBeLoadedExplicitly() throws Exception {
        List<EvalCase> cases = EvalDatasetLoader.load(Path.of("evals/datasets/ap_coding_v1.jsonl"), mapper);
        assertFalse(cases.isEmpty());
        assertEquals("ap-001", cases.get(0).id());
    }
}
