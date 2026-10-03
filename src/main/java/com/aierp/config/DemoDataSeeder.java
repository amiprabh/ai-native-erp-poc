package com.aierp.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.aierp.repository.AccountingPatternRepository;
import com.aierp.service.SemanticPatternMatchingService;
import com.aierp.domain.ai.AccountingPattern;

@Component
public class DemoDataSeeder implements CommandLineRunner {

    private final AccountingPatternRepository patternRepository;
    private final SemanticPatternMatchingService semanticMatcher;

    public DemoDataSeeder(
            AccountingPatternRepository patternRepository,
            SemanticPatternMatchingService semanticMatcher) {
        this.patternRepository = patternRepository;
        this.semanticMatcher = semanticMatcher;
    }

    @Override
    public void run(String... args) {

        long count = patternRepository.count();

        System.out.println("========== DEMO SEEDER ==========");
        System.out.println("Existing patterns: " + count);

        if (count > 0) {
            System.out.println("Patterns already exist. Skipping seed.");
            return;
        }

        System.out.println("Creating demo accounting patterns...");

        AccountingPattern aws = new AccountingPattern();
        aws.setVendorName("AWS");
        aws.setDescriptionFeature("Cloud hosting services");
        aws.setGlAccountId("610500");
        aws.setDepartmentId("ENG");

        AccountingPattern office = new AccountingPattern();
        office.setVendorName("Staples");
        office.setDescriptionFeature("Office supplies");
        office.setGlAccountId("680000");
        office.setDepartmentId("GEN");

        patternRepository.save(aws);
        patternRepository.save(office);

        System.out.println("Patterns saved. Creating embeddings...");

        semanticMatcher.indexPattern(aws);
        semanticMatcher.indexPattern(office);

        System.out.println("========== DEMO SEEDER COMPLETE ==========");
    }
}