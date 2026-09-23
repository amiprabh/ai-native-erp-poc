# AI-Native ERP Proof of Concept: AP Invoice-to-Ledger Pipeline

This project is a Proof of Concept (POC) demonstrating a modern, AI-integrated Enterprise Resource Planning (ERP) system. It focuses specifically on the **Accounts Payable (AP) Invoice-to-Ledger** pipeline, transforming incoming raw invoices into validated accounting ledger entries using a hybrid of deterministic rules and Large Language Model (LLM) reasoning.

## 🚀 Overview
The goal of this POC is to automate the manual effort typically required when invoices fail deterministic ERP rules. By leveraging AI to categorize complex or novel transactions and keeping a human-in-the-loop for approvals, we significantly reduce the workload for accountants while maintaining 100% financial accuracy.

In an ideal AI-native ERP:
- **Event-Driven:** Webhooks transmit real-time transaction events.
- **Decoupled:** Kafka separates ingestion from processing, allowing for scale and varied processing speeds (real-time vs. batch).
- **Intelligent Routing:** Transactions are categorized for straight-through processing via rules or AI-assisted inference for exceptions.

## 🏗️ Architecture
The system is built with **Spring Boot 3** and follows a standard enterprise layered architecture enhanced with event-driven and AI capabilities.

- **`controller/`**: REST API endpoints for webhooks or UI data ingestion.
- **`service/`**: Core business logic, including the decision router and LLM integration.
- **`domain/`**: The authoritative domain model (Invoices, Ledger Entries, Accounts).
- **`repository/`**: Persistence layer using PostgreSQL.
- **`dto/`**: Data Transfer Objects for cross-layer and network communication.
- **Infrastructure**: Managed via `docker-compose.yml`, featuring **PostgreSQL** (with **pgvector** for semantic search) and **Apache Kafka**.

## ⚖️ Core Logic & "Golden Rules"

### 1. AI as an Assistant, Human-in-the-Loop
The AI suggests classifications but cannot write directly to the ledger. Every entry must pass the **Java Accounting Engine**, which enforces strict financial rules (e.g., Credits = Debits). If a transaction fails or confidence is low, it is routed for human approval.

### 2. Strategic Cost Management (Selective Invocation)
To minimize LLM API costs:
- **Rule-First:** If an invoice matches a known vendor pattern, the system uses a deterministic Rule Engine.
- **Feedback Loop:** Once AI successfully categorizes a new pattern, it can be codified into an "Accounting Distribution Template" for future straight-through processing.

### 3. Dual Ingestion Strategy
- **Real-time:** Webhooks trigger immediate processing as events occur.
- **Safety Net:** Nightly batch processes reconcile source systems to ensure no transactions were missed.

## 🛠️ Technologies
- **Java 17 / Spring Boot 3**: Backend framework.
- **Apache Kafka**: High-volume event streaming and decoupling.
- **PostgreSQL + pgvector**: Relational storage with vector capabilities for AI memory/similarity search.
- **Spring Data JPA**: For persistence management.

## 🔄 System Flow

### 1. Webhook Ingestion (HTTP POST)
- **`WebhookIngestionController.ingestInvoiceWebhook`**: REST entry point; enforces idempotency and hands off to Kafka.
- **`WebhookEventRepository.existsBySourceAndEventId`**: Prevents duplicate processing of the same event.
- **`WebhookEventRepository.saveRawEvent`**: Persists the original JSON for auditability.
- **`KafkaTemplate.send`**: Dispatches the event to the `invoice-ingestion-topic`.

### 2. Accounting Decision & Ledger Flow
- **`DecisionRouterService.resolveAccountingDistribution`**: Prioritizes historical patterns before falling back to LLM inference.
- **`AccountingPatternRepository.findTopByVendorAndDescriptionFeature`**: Targeted lookup for learned mappings.
- **`LlmInferenceService.inferGlDistribution`**: Uses semantic reasoning to predict GL accounts and departments.
- **`AccountingEngine.postJournalEntry`**: Validates the double-entry balance and builds ledger entities.
- **`JournalEntryRepository.save`**: Persists the validated entry to the authoritative ledger.

## 🚦 Getting Started

### 1. Launch Infrastructure
```bash
docker-compose up -d # Spins up PostgreSQL (pgvector) + Apache Kafka
```

### 2. Run the Application
```bash
./mvnw spring-boot:run # Runs the application server
```