package com.aierp.evals;

import com.aierp.dto.JournalDistributionProposal;

public record EvalPrediction(EvalCase input, JournalDistributionProposal proposal, String tier) {
    public String predictedGl() {
        return proposal.lines().stream()
                .filter(line -> "DEBIT".equalsIgnoreCase(line.type()))
                .map(line -> line.glAccountId())
                .findFirst().orElse("<missing>");
    }

    public String predictedDept() {
        return proposal.lines().stream()
                .filter(line -> "DEBIT".equalsIgnoreCase(line.type()))
                .map(line -> line.departmentId())
                .findFirst().orElse("<missing>");
    }

    public boolean correct() {
        return input.expectedGl().equals(predictedGl()) && input.expectedDept().equals(predictedDept());
    }

    public boolean requiresReview() {
        return proposal.requiresHumanApproval();
    }

    public boolean autoPost() {
        return !requiresReview();
    }

    public boolean unsafeAutoPost() {
        return autoPost() && !correct();
    }

    /** Suspense must never be treated as a straight-through posting decision. */
    public boolean suspenseAutoPost() {
        return autoPost() && ("999999".equals(predictedGl()) || "SUSPENSE".equals(predictedDept()));
    }
}
