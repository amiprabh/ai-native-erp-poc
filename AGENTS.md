# AGENTS.md

## Project
Spring Boot 3.3.4 / Java 21 / Spring AI 1.0.9. AP invoice-to-ledger POC. Invoice
lines are classified by a three-tier router (exact pattern, semantic pgvector
match, LLM) and a deterministic engine enforces posting rules. A reviewer API
approves or rejects pending entries and promotes approved ones into the
pattern store.

Read `SPEC.txt` (invariants GR-1 to GR-9, API contract) before changing
anything under `service/`, `domain/`, `consumer/`, or either controller. Read
`DESIGN.md` for known gaps. This is a POC: do not describe it as
production-ready, exactly-once, secured, or reconciled in code comments, docs,
or commit messages.

## Setup
- Requires Postgres (pgvector image) + Kafka: `docker-compose up -d`
- Requires `GEMINI_API_KEY` env var (Gemini via the OpenAI-compatible endpoint,
  used for both chat and embeddings). It has no default in `application.yml`;
  never hardcode it in any file, including docs, scripts, and tests.
- Postgres has no persistent volume; data is lost when the container is removed.

## Build / test / run
- Build: `./mvnw clean install`
- Test: `./mvnw test`
- Run: `./mvnw spring-boot:run` (app on :8080)
- Demo: `./demo.sh` (needs stack up, app running, `jq`)

Test caveats:
- `@SpringBootTest` classes load the full context: they need Postgres and
  `GEMINI_API_KEY`, share the dev database, and `DemoDataSeeder` runs at
  startup (it calls the embedding API when the pattern table is empty).
- Do not quote a test pass count in docs without running the suite first.
- The suite has no coverage for the webhook controller, consumer, semantic
  service, approval flow, or concurrency. Add tests when you touch those.

## Package layout and conventions
- `controller/` REST entry points (`WebhookIngestionController`,
  `JournalEntryApprovalController`).
- `consumer/` Kafka listeners.
- `service/` routing, semantic retrieval, LLM inference, accounting engine,
  approval.
- `domain/core/` authoritative ledger schema (`core_finance`).
- `domain/ai/` AI-assist layer (`ai_intelligence`): patterns, decision logs.
- `domain/integration/` webhook receipt state (`integration`).
- `repository/`, `dto/`, `config/` as named.
- Keep new classes in the matching package. Keep `domain/core` independent of
  `domain/ai` so the AI layer stays removable. Note that
  `JournalEntryApprovalService` currently touches both; do not add more
  coupling.
- Schemas are managed by Hibernate `ddl-auto: update` (no migrations). Adding
  a unique constraint or column changes live schema behavior; call it out in
  your change description.

## Hard rules (do not violate; see SPEC.txt Golden Rules)
- AI, semantic matches, and exact patterns only produce proposals. Every
  proposal goes through `AccountingEngine.postJournalEntry`. Nothing else
  writes `journal_entries` or `ledger_lines`, except the approval service
  changing the status of an existing entry.
- `debits == credits` and the GL/department allowlist are enforced in
  `AccountingEngine`, not the caller. Do not add a path that bypasses them, and
  do not loosen the checks to make a test or demo pass.
- The chart of accounts is currently hardcoded in `AccountingEngine` and
  `LlmInferenceService` (and the LLM prompt). If you change an account or
  department, update all three together, or better, consolidate them.
- Event idempotency is `(source, eventId)`, checked and persisted before
  publishing. Business idempotency (the journal) is a separate concern and is
  not yet enforced at the database level; do not assume event dedup prevents
  duplicate journals.
- LLM/vendor/description text is untrusted. Keep invoice content out of
  static system prompts, and keep allowlist validation on model output.
- Never promote a suspense (`999999`/`SUSPENSE`) or `*_FALLBACK` entry into
  `accounting_patterns`, and do not weaken review requirements (LLM below
  0.90, fallback always) without updating SPEC.txt. Known gaps in the current
  code: semantic matches skip review and approval promotes suspense entries;
  do not copy those behaviors into new code.
- Do not swallow exceptions in the Kafka consumer. They must reach
  `DefaultErrorHandler` (retry, then `<topic>.DLT`).
- Do not log full invoice payloads, API keys, or other sensitive data.
- Never commit secrets. `.env` is gitignored; use it for local keys.

## Documentation upkeep
- When behavior changes, update the matching section of `SPEC.txt`,
  `DESIGN.md`, `AI-ERP-DOC.txt`, and `README.md` in the same change. Keep
  "implemented" and "planned" clearly separated.
- Stale items to fix when you touch them: the "FR-9" comments in
  `SemanticPatternMatchingService` and `demo.sh` (there is no FR-9 in the spec;
  the approval/promotion section is SPEC.txt section 8), the proposal-source
  comment in `JournalDistributionProposal`, and the unused `POSTED_AFTER_APPROVAL`
  status mentioned in `JournalEntry`.
