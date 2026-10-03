package com.aierp.domain.ai;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounting_patterns", schema = "ai_intelligence")
public class AccountingPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID patternId;

    private String vendorName;
    private String descriptionFeature;
    private String glAccountId;
    private String departmentId;
    private Instant createdAt = Instant.now();

    public AccountingPattern() {}

    public AccountingPattern(String vendorName, String descriptionFeature, String glAccountId, String departmentId) {
        this.vendorName = vendorName;
        this.descriptionFeature = descriptionFeature;
        this.glAccountId = glAccountId;
        this.departmentId = departmentId;
    }

    public UUID getPatternId() { return patternId; }
    public String getVendorName() { return vendorName; }
    public String getDescriptionFeature() { return descriptionFeature; }
    public String getGlAccountId() { return glAccountId; }
    public String getDepartmentId() { return departmentId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setDescriptionFeature(String descriptionFeature) {
        this.descriptionFeature = descriptionFeature;
    }

	public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    public void setGlAccountId(String glAccountId) {
        this.glAccountId = glAccountId;        
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }



}