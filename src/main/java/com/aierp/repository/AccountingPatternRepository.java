package com.aierp.repository;

import com.aierp.domain.ai.AccountingPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountingPatternRepository extends JpaRepository<AccountingPattern, UUID> {

    Optional<AccountingPattern> findTopByVendorNameAndDescriptionFeature(String vendorName, String descriptionFeature);

    default Optional<AccountingPattern> findTopByVendorAndDescriptionFeature(String vendorName, String descriptionFeature) {
        return findTopByVendorNameAndDescriptionFeature(vendorName, descriptionFeature);
    }
}