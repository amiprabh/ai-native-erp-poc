package com.aierp.service;

import com.aierp.domain.ai.AccountingPattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SemanticPatternMatchingService {

    // Below this cosine similarity, we don't trust the match enough to skip
    // the LLM -- tune this like the 0.90 LLM confidence threshold: a gut-feel
    // starting point, not derived from measured data yet.
    private static final double SIMILARITY_THRESHOLD = 0.85;

    private final VectorStore vectorStore;

    public SemanticPatternMatchingService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Indexes an approved pattern so future similar invoices can match against
     * it semantically. Call this whenever a pattern is created or promoted --
     * there is no promotion workflow built yet (see SPEC.txt FR-9), so today
     * this must be called manually / from a seed script.
     */
    public void indexPattern(AccountingPattern pattern) {
        String content = "Vendor: " + pattern.getVendorName()
                + ", Description: " + pattern.getDescriptionFeature();

        Document document = new Document(
                pattern.getPatternId().toString(),
                content,
                Map.of(
                        "patternId", pattern.getPatternId().toString(),
                        "glAccountId", pattern.getGlAccountId(),
                        "departmentId", pattern.getDepartmentId()));

        vectorStore.add(List.of(document));
    }

    /**
     * Returns the best semantic match above SIMILARITY_THRESHOLD, if any.
     * A miss here means "fall through to the LLM," same as an exact-match
     * miss does today.
     */
    public Optional<MatchedPattern> findSimilarPattern(String vendorName, String lineDescription) {
        String query = "Vendor: " + vendorName + ", Description: " + lineDescription;

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(1)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build());

        if (results.isEmpty()) {
            return Optional.empty();
        }

        Document match = results.get(0);
        return Optional.of(new MatchedPattern(
                (String) match.getMetadata().get("glAccountId"),
                (String) match.getMetadata().get("departmentId"),
                match.getScore() != null ? match.getScore() : 0.0));
    }

    public record MatchedPattern(String glAccountId, String departmentId, double similarityScore) {
    }
}