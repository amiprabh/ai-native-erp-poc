package com.aierp.dto;

import java.util.List;

public class JournalDistributionProposal {
    private List<LineDistribution> lines;
    private String source; // "HISTORICAL_PATTERN" or "LLM_INFERENCE"
    private double confidenceScore;
    private boolean requiresHumanApproval;

    public JournalDistributionProposal(List<LineDistribution> lines, String source, double confidenceScore) {
        this.lines = lines;
        this.source = source;
        this.confidenceScore = confidenceScore;
        this.requiresHumanApproval = false;
    }

    public List<LineDistribution> lines() { return lines; }
    public String source() { return source; }
    public double confidenceScore() { return confidenceScore; }
    public boolean requiresHumanApproval() { return requiresHumanApproval; }
    public void setRequiresHumanApproval(boolean requiresHumanApproval) { 
        this.requiresHumanApproval = requiresHumanApproval; 
    }
}