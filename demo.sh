#!/usr/bin/env bash
# Demo script for the AP invoice-to-ledger pipeline.
# Requires: the full docker-compose stack up, the app running on :8080,
# and at least one seeded AccountingPattern (DemoDataSeeder handles this
# on first run). Requires `jq` for readable output.

set -e
BASE_URL="http://localhost:8080"

post_invoice() {
  local invoice_id="$1" vendor="$2" description="$3" amount="$4" event_id="$5"
  curl -s -X POST "$BASE_URL/api/v1/webhooks/invoices" \
    -H "Content-Type: application/json" \
    -d @- <<EOF
{
  "eventId": "$event_id",
  "eventType": "INVOICE_LINE_CREATED",
  "source": "demo-ap-system",
  "sourceAccountId": "demo-account-1",
  "sourceObjectId": "$invoice_id",
  "sourceObjectVersion": 1,
  "occurredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "payload": { "vendorName": "$vendor", "lineDescription": "$description", "amount": $amount }
}
EOF
}

echo "=============================================="
echo "PATH 1: Known vendor -> exact historical pattern match"
echo "Expect: status=POSTED, source=HISTORICAL_PATTERN, confidence 1.00"
echo "=============================================="
post_invoice "DEMO-INV-001" "AWS" "Cloud hosting services" 250.00 "demo-evt-001"
sleep 2
curl -s "$BASE_URL/api/v1/journal-entries/by-invoice/DEMO-INV-001" | jq .

echo ""
echo "=============================================="
echo "PATH 2: New/ambiguous vendor -> LLM proposes, low confidence -> PENDING_APPROVAL"
echo "This path depends on live LLM output and is not fully deterministic --"
echo "if the model is confident on the first try, this will show status=POSTED"
echo "instead. That's not a bug, just a different (valid) model response."
echo "=============================================="
post_invoice "DEMO-INV-002" "Quixotic Ventures LLC" "Miscellaneous business services Q3" 475.50 "demo-evt-002"
sleep 3
curl -s "$BASE_URL/api/v1/journal-entries/by-invoice/DEMO-INV-002" | jq .

echo ""
echo "Pending approvals right now:"
curl -s "$BASE_URL/api/v1/journal-entries/pending" | jq .

echo ""
echo "If DEMO-INV-002 above is PENDING_APPROVAL, grab its entryId from the"
echo "output and approve it to see FR-9 close the loop -- this also promotes"
echo "the decision into accounting_patterns for future straight-through use:"
echo '  curl -X POST $BASE_URL/api/v1/journal-entries/{entryId}/approve | jq .'

echo ""
echo "=============================================="
echo "PATH 3: LLM outage -> suspense account, forced human review"
echo "This path cannot be triggered by a single curl call -- it requires"
echo "the LLM call to actually fail. To demo it:"
echo "  1. Stop the app"
echo "  2. Temporarily set an invalid GEMINI_API_KEY"
echo "  3. Restart the app"
echo "  4. Run: post a new vendor/description (one with no existing pattern)"
echo "  5. Expect: status=PENDING_APPROVAL, lines include GL account 999999"
echo "            (SUSPENSE), confidence 0.00"
echo "  6. Restore the real key and restart before continuing"
echo "=============================================="