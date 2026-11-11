package com.aierp.evals;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class EvalDatasetLoader {
    static final Path DEFAULT_DATASET = Path.of("evals", "datasets", "ap_coding_v1.jsonl");
    static final Set<String> VALID_GL = Set.of("610500", "610200", "620100", "680000", "999999");
    static final Set<String> VALID_DEPARTMENTS = Set.of("ENG", "SALES", "GEN", "SUSPENSE");
    static final Set<String> REQUIRED_TAGS = Set.of(
            "easy", "ambiguous", "alias", "terse", "multilingual", "unclassifiable", "adversarial");

    private EvalDatasetLoader() { }

    static List<EvalCase> load(Path path, ObjectMapper mapper) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            try {
                cases.add(mapper.readValue(line, EvalCase.class));
            } catch (Exception ex) {
                throw new IllegalArgumentException("Invalid JSONL at " + path + ":" + lineNumber, ex);
            }
        }
        return List.copyOf(cases);
    }

    static List<String> validate(List<EvalCase> cases) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> foundTags = new HashSet<>();
        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            String where = "case index " + i + (c == null ? "" : " (" + c.id() + ")");
            if (c == null) { errors.add(where + ": case is null"); continue; }
            if (blank(c.id())) errors.add(where + ": id is required");
            else if (!ids.add(c.id())) errors.add(where + ": duplicate id");
            if (blank(c.vendor())) errors.add(where + ": vendor is required");
            if (blank(c.description())) errors.add(where + ": description is required");
            if (c.amount() == null || c.amount().signum() <= 0) errors.add(where + ": amount must be positive");
            if (!VALID_GL.contains(c.expectedGl())) errors.add(where + ": unsupported expected GL " + c.expectedGl());
            if (!VALID_DEPARTMENTS.contains(c.expectedDept())) errors.add(where + ": unsupported expected department " + c.expectedDept());
            if (c.tags() == null || c.tags().isEmpty()) errors.add(where + ": at least one tag is required");
            else {
                foundTags.addAll(c.tags());
                for (String tag : c.tags()) {
                    if (!REQUIRED_TAGS.contains(tag)) errors.add(where + ": unsupported tag " + tag);
                }
            }
        }
        Set<String> missing = new HashSet<>(REQUIRED_TAGS);
        missing.removeAll(foundTags);
        if (!missing.isEmpty()) errors.add("Missing required evaluation slices: " + missing);
        return errors;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
