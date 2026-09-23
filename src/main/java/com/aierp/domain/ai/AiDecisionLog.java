package com.aierp.domain.ai;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_decision_logs", schema = "ai_intelligence")
public class AiDecisionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID decisionId;

    private String transactionId;
    private String promptVersion;
    private String modelName;
    private double confidenceScore;
    
    @Column(columnDefinition = "text")
    private String rawOutputJson;
    
    private Instant createdAt = Instant.now();

    public AiDecisionLog() {}

    public UUID getDecisionId() { return decisionId; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }
    public String getRawOutputJson() { return rawOutputJson; }
    public void setRawOutputJson(String rawOutputJson) { this.rawOutputJson = rawOutputJson; }
    public Instant getCreatedAt() { return createdAt; }
}