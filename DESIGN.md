# DESIGN.md — AI-Native ERP POC

This document explains *why* the system is built the way it is, and is
explicit about what's still stubbed. See `SPEC.txt` for the original
requirements this was built against — this file is the retrospective
version: what I actually built, and what I'd reconsider.

## The problem

AP invoice coding is a high-volume, judgment-heavy task: assigning a GL
account and department to a line item based on vendor + description. Most
of it is routine (the same vendor codes the same way every time), but the
exceptions eat analyst time. This POC tests whether an LLM can absorb the
exception-handling work — proposing a classification — while a deterministic
engine keeps full control over what actually posts to the ledger. The AI
never gets write access to the ledger; it only ever proposes.

## Architecture

```
Webhook (vendor/AP system) --> WebhookIngestionController
                                  - idempotency check (source + eventId)
                                  - persist raw payload
                                  - publish to Kafka
                                        |
                                        v
                              invoice-ingestion-topic
                                        |
                                        v
                          InvoiceTransactionConsumer (@KafkaListener)
                                        |
                                        v
                          DecisionRouterService
                            - known pattern? -> use it, skip LLM
                            - unknown -> LlmInferenceService
                                        |
                                        v
                          AccountingEngine.postJournalEntry(...)
                            - re-validates debits == credits
                            - POSTED or PENDING_APPROVAL
                                        |
                                        v
                          core_finance.journal_entries / ledger_lines
                          (+ ai_intelligence.ai_decision_logs for every
                           AI-sourced decision, success or fallback)
```

### Why each piece is there

**Kafka between ingestion and processing.** Webhook delivery is bursty and
at-least-once by nature — vendor systems retry on timeout, and I don't want
the accounting logic coupled to the HTTP request lifecycle. Decoupling also
means a slow LLM call or a downstream Postgres hiccup doesn't turn into a
timed-out webhook response to a system I don't control.

**Rule-first, LLM-fallback routing.** Calling an LLM on every line item is
both slower and more expensive than it needs to be once a vendor/description
pattern has been seen and approved before. `DecisionRouterService` checks
`accounting_patterns` first and only calls the LLM on a miss — this is a
cost/latency decision as much as an accuracy one.

**Schema separation: `core_finance` vs `ai_intelligence`.** The ledger schema
should not know the AI layer exists. If I ever need to rip the AI assist out
entirely — regulatory reasons, a bad model, whatever — that should be a
matter of disabling a service, not a ledger migration. I haven't proven this
by actually deleting the AI layer and confirming the system still runs, but
the schema boundary was deliberately drawn with that in mind.

**Deterministic double-entry validation independent of proposal source.**
`AccountingEngine` re-checks debits == credits regardless of whether the
proposal came from a historical pattern or the LLM. It doesn't trust the
caller. This is the one invariant in the whole system I'm not willing to
relax for convenience anywhere.

## Trade-offs I'd reconsider at scale

- **pgvector is in `docker-compose` but not wired into pattern matching.**
  Current pattern lookup is exact-match on `(vendorName, descriptionFeature)`,
  which is brittle — "AWS" and "Amazon Web Services" are two different
  vendors as far as the lookup is concerned. Semantic similarity search is
  the obvious next step, but I wanted the exact-match version proven out
  first before adding fuzziness to something that touches the ledger.
- **No dead-letter topic on the Kafka consumer.** A malformed payload is
  currently logged and skipped rather than retried or routed anywhere
  durable. Fine for a POC's data volume; not fine once a source system
  starts sending payloads I haven't seen the shape of yet.
- **The webhook payload schema is informally assumed, not enforced.**
  `WebhookEnvelope.payload()` is `Map<String, Object>` with no schema
  validation. The consumer currently assumes `vendorName`/`lineDescription`/
  `amount` keys exist. A real integration needs an explicit contract here,
  probably per source system.
- **`WebhookEventRepository` is in-memory, not persisted.** It satisfies the
  idempotency check today but doesn't survive a restart, and it doesn't
  actually give me the durable raw-payload audit trail the spec calls for
  despite being named like it does. This is a real gap, not a rounding
  error — worth fixing before this touches anything beyond a demo.

## What's stubbed / what's next

- [ ] Approval workflow — `PENDING_APPROVAL` is currently a terminal status
      with no UI or endpoint to act on it
- [ ] Reconciliation batch job — nothing currently catches invoices the
      webhook path missed (source outage, delivery failure)
- [ ] Webhook authentication — `/api/v1/webhooks/invoices` has no signature
      verification; anyone who can reach it can post a journal entry
- [ ] Durable webhook event persistence — see `WebhookEventRepository` above
- [ ] Kafka consumer dead-letter handling
- [ ] pgvector-backed semantic pattern matching
- [ ] GL account / department whitelist validation lives in
      `LlmInferenceService`; it should probably be a shared, DB-backed
      chart-of-accounts table rather than a hardcoded `Set.of(...)`

I'm naming these here on purpose, before anyone reviewing this finds them
first.

## Demo

See `demo.sh` — three paths, matching the Golden Rules in `SPEC.txt`:

1. **Known vendor** → historical pattern match → auto-posts at confidence 1.00
2. **New/ambiguous vendor** → LLM proposes, confidence below 0.90 →
   `PENDING_APPROVAL`
3. **Simulated LLM outage** → suspense account, confidence 0.00, forced
   human review

Each one is a `curl` against the real webhook endpoint, run through the
real Kafka pipeline — not a shortcut that bypasses it.
