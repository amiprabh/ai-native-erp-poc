# AI-Native ERP Proof of Concept: AP Invoice-to-Ledger Pipeline

A proof of concept for an **Accounts Payable invoice-line-to-ledger** workflow. Incoming invoice lines are classified to a GL account and department by a three-tier router (exact pattern, semantic pattern, LLM), validated by deterministic Java code, and persisted as journal entries. Low-confidence or fallback proposals wait for a human to approve or reject them, and approved decisions feed back into the pattern store.

> **Status: working POC, not production-ready.** It is not exactly-once, not secured, not reconciled against source systems, and its confidence/similarity thresholds are uncalibrated. See [Known limitations](#-known-limitations) and `SPEC.txt` / `DESIGN.md` for detail.

## 🚀 Overview

The goal is to test one narrow hypothesis: an LLM, assisted by retrieval over previously approved decisions, can absorb the exception-handling work of invoice coding, while deterministic application code keeps authority over what is written to the ledger.

**AI proposes; deterministic code validates; the ledger remains authoritative.**

- **Event-driven:** a webhook receives invoice-line events and hands them to Kafka.
- **Decoupled:** Kafka separates ingestion from classification and posting.
- **Tiered routing:** cheapest and most predictable path first, LLM only for what is left.
- **Human in the loop:** low-confidence and fallback proposals go to `PENDING_APPROVAL`; approval promotes the decision into the pattern store.

## 🏗️ Architecture

Built with **Java 21** and **Spring Boot 3.3.4** (Spring AI 1.0.9), with a conventional layered layout plus event-driven and AI components.

- **`controller/`**: `WebhookIngestionController` (webhook entry point) and `JournalEntryApprovalController` (reviewer API).
- **`consumer/`**: `InvoiceTransactionConsumer`, the Kafka listener.
- **`service/`**: `DecisionRouterService` (tier routing), `SemanticPatternMatchingService` (pgvector retrieval), `LlmInferenceService` (Gemini), `AccountingEngine` (validation and posting), `JournalEntryApprovalService` (approve/reject and pattern promotion).
- **`domain/core/`**: authoritative ledger entities (`JournalEntry`, `LedgerLine`; `Invoice` exists but is not used yet).
- **`domain/ai/`**: `AccountingPattern`, `AiDecisionLog`.
- **`domain/integration/`**: `WebhookEvent`.
- **`repository/`**: Spring Data JPA repositories.
- **`dto/`**: webhook envelope, proposal, and summary records.
- **`config/`**: Kafka retry/dead-letter configuration and `DemoDataSeeder`.
- **Infrastructure** (`docker-compose.yml`): PostgreSQL with **pgvector** and a single-node **Apache Kafka** (KRaft). The main topic and a `.DLT` dead-letter topic are created at startup.

PostgreSQL schemas: `core_finance` (ledger), `ai_intelligence` (patterns, decision logs), `integration` (webhook events). They are created by Hibernate (`ddl-auto: update`); there is no migration tool yet. Spring AI's pgvector store also creates its own vector table.

## ⚖️ Core Logic and Golden Rules

The authoritative list of invariants, with current conformance notes, is in `SPEC.txt` (GR-1 to GR-9). In short:

### 1. AI is advisory
Neither the LLM nor a pattern/semantic match writes to the ledger. Every proposal goes through `AccountingEngine`, which checks the debit-side GL account/department allowlist and debit/credit equality before anything is saved.

### 2. Tiered routing
`DecisionRouterService` resolves each line in order:

| Tier | Method | Source value | Confidence | Review? |
|---|---|---|---|---|
| 1 | Exact vendor + description match | `HISTORICAL_PATTERN` | 1.00 | No |
| 2 | pgvector cosine similarity, top-1, threshold 0.85 | `SEMANTIC_MATCH` | similarity score | **No (see limitations)** |
| 3 | LLM (`gemini-3-flash-preview`) with allowlist check | model name | model-reported | Yes if below 0.90 |
| Fallback | LLM error or invalid output | `<model>_FALLBACK` | 0.00 | Always (suspense `999999`/`SUSPENSE`) |

The 0.90 and 0.85 thresholds are hand-picked starting values, not calibrated on data.

### 3. Feedback loop
Approving a `PENDING_APPROVAL` entry sets it to `POSTED`, saves a new accounting pattern from its debit line, and indexes it for semantic search, so similar future invoices resolve at Tier 1 or Tier 2.

### 4. Reliability mechanisms
- Webhook events are persisted with a unique `(source, eventId)` key; duplicates are ignored.
- The consumer lets exceptions reach Spring Kafka's error handler: three retries, 2-second fixed backoff, then `invoice-ingestion-topic.DLT`.
- Journal posting skips a second `POSTED` entry for the same source invoice ID.

These reduce duplicates but do **not** make processing exactly-once (see limitations).

## 🛠️ Technologies
- **Java 21 / Spring Boot 3.3.4**
- **Spring AI 1.0.9** (OpenAI-compatible client pointed at Gemini; pgvector vector store)
- **Apache Kafka** (event streaming and decoupling)
- **PostgreSQL 16 + pgvector** (relational storage and embeddings, HNSW index, cosine distance)
- **Spring Data JPA**

## 🔄 System Flow

### 1. Webhook ingestion (`POST /api/v1/webhooks/invoices`)
- `WebhookIngestionController.ingestInvoiceWebhook` validates required envelope fields and checks `WebhookEventRepository.findBySourceAndEventId`.
- A new event is saved as a `WebhookEvent` (serialized payload plus metadata) with `saveAndFlush`.
- The envelope is sent to `invoice-ingestion-topic` and the controller waits up to 5 seconds for acknowledgement. Success marks the event `PUBLISHED` and returns `202`; failure marks it `FAILED` and returns `500`, and a retry of the same event republishes it.

### 2. Classification and posting
- `InvoiceTransactionConsumer` validates `vendorName`, `lineDescription`, and a positive `amount`.
- `DecisionRouterService.resolveAccountingDistribution` runs the tiers above.
- `AccountingEngine.postJournalEntry` validates and persists the journal as `POSTED` or `PENDING_APPROVAL`.
- `LlmInferenceService` records LLM success and fallback in `ai_decision_logs`.

### 3. Review
- `JournalEntryApprovalService` approves or rejects pending entries and promotes approved ones to patterns.

## 🌐 API

### Webhook
`POST /api/v1/webhooks/invoices`

```json
{
  "eventId": "evt-001",
  "eventType": "INVOICE_LINE_CREATED",
  "source": "demo-ap-system",
  "sourceAccountId": "demo-account-1",
  "sourceObjectId": "INV-001",
  "sourceObjectVersion": 1,
  "occurredAt": "2026-10-02T12:00:00Z",
  "payload": {
    "vendorName": "AWS",
    "lineDescription": "Cloud hosting services",
    "amount": 250.00
  }
}
```

Responses: `202 RECEIVED`, `200 IGNORED_DUPLICATE`, `400 INVALID_PAYLOAD` (serialization failure), `500 PERSISTED_BUT_KAFKA_FAILED`. A missing required field currently returns `500` rather than `400`.

### Reviewer API (`/api/v1/journal-entries`) — no authentication yet
| Method | Path | Purpose |
|---|---|---|
| GET | `/pending` | List entries awaiting approval |
| GET | `/by-invoice/{invoiceId}` | List entries for a source invoice ID |
| POST | `/{entryId}/approve` | `PENDING_APPROVAL` → `POSTED`, promote to pattern |
| POST | `/{entryId}/reject` | `PENDING_APPROVAL` → `REJECTED` |

A non-pending entry returns `409`. An unknown entry currently returns `500` instead of `404`.

## 🚦 Getting Started

### Prerequisites
Java 21, Docker, and a Gemini API key. Tests and the application both need `GEMINI_API_KEY` set, and the key must never be committed.

### 1. Launch infrastructure
```bash
# Start Docker Desktop first
docker-compose up -d   # PostgreSQL (pgvector) + Kafka, topics created automatically
```
Postgres has no persistent volume, so data is lost when the container is removed.

### 2. Run the application
```bash
export GEMINI_API_KEY="your-key-here"   # PowerShell: $env:GEMINI_API_KEY="your-key-here"
./mvnw spring-boot:run
```
On startup `DemoDataSeeder` creates two demo patterns (AWS / "Cloud hosting services", Staples / "Office supplies") and indexes them, but only when the pattern table is empty. Indexing calls the embedding API.

### 3. Run tests
```bash
./mvnw clean test
```
The repository currently defines 28 tests. The two `@SpringBootTest` classes need PostgreSQL running and `GEMINI_API_KEY` set, and they share your local development database. Re-run the suite to confirm the current pass count; the tests cover unit and context behavior only, not the end-to-end pipeline.

### 4. Try the demo
```bash
./demo.sh   # requires curl and jq, stack up, app running on :8080
```
It walks through (1) an exact pattern match, (2) a new vendor that goes to the LLM and may land in `PENDING_APPROVAL`, followed by an approve command, and (3) instructions for simulating an LLM outage. The LLM path depends on live model output and is not deterministic. There is no scripted semantic-tier or rejection demo yet.

### 5. Query the database
```bash
docker compose exec postgres psql -U erp_user -d aierp_db
# e.g.: select * from ai_intelligence.accounting_patterns;
#       select entry_id, source_invoice_id, status, source from core_finance.journal_entries;
```

## ⚠️ Known limitations

Highest-impact items first. The full prioritized list is in `DESIGN.md` section 5.

- **Approval API is unauthenticated** and records no reviewer identity; reviewers can only accept or reject, not correct an account.
- **Approving a suspense/fallback entry promotes it into a pattern**, after which that vendor/description auto-posts to suspense without review.
- **Semantic matches (0.85+) post without human review**, and semantic search has no vendor pre-filter.
- **Not exactly-once:** the database write and Kafka publish are separate, journal idempotency is checked at application level only (and only for `POSTED` entries), and there is no transactional outbox.
- **Webhook has no authentication or signature validation.**
- **Webhook status stops at `PUBLISHED`:** the consumer does not record processed, retrying, or dead-letter outcomes, and there is no DLT replay tooling.
- **No reconciliation or backfill.** Missed source events are not detected. (A nightly reconciliation job is a design goal, not an implemented feature.)
- **Embedding/vector failures are not handled in routing;** they go through Kafka retry and the DLT instead of a fallback.
- **Single-line payload shape, hardcoded chart of accounts** (duplicated in two classes, debit side only), no currency, period, tax, or multi-line invoice handling.
- **Limited test coverage:** tests don't cover the semantic service, concurrency, or failure injection.
- Invoice text is sent to an external LLM and embedding provider; do not use real financial data without reviewing that.
