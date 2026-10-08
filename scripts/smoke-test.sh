#!/usr/bin/env bash
# End-to-end smoke test against a local stack (docker compose up): POST /v1/emails, the worker
# sends through SMTP, and the email shows up in Mailpit (AC-15.1, AC-15.2). Mailpit never delivers
# outside, and the recipient is a unique address on the reserved example.test domain.
#
# Usage: [EMAIL_SERVICE_API_KEY=esk_...] [BASE_URL=...] [MAILPIT_URL=...] scripts/smoke-test.sh
# Without EMAIL_SERVICE_API_KEY it runs scripts/seed-local.sh and uses the product key it issues.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-30}"

fail() {
	echo "smoke-test: FAIL: $*" >&2
	exit 1
}

command -v jq > /dev/null || fail "jq is required (declared in mise.toml: run 'mise install')"
curl -fsS -o /dev/null "$BASE_URL/actuator/health/readiness" || fail "the service is not ready at $BASE_URL"
curl -fsS -o /dev/null "$MAILPIT_URL/api/v1/info" || fail "Mailpit is not reachable at $MAILPIT_URL"

API_KEY="${EMAIL_SERVICE_API_KEY:-}"
if [[ -z "$API_KEY" ]]; then
	API_KEY="$(./scripts/seed-local.sh | grep -o 'Product key.*' | grep -oE 'esk_[a-z]+_[0-9A-Za-z]{8}_[0-9A-Za-z]{43}')" \
		|| fail "could not obtain a product key from seed-local.sh"
fi

RUN="$(date +%s)-$RANDOM"
RECIPIENT="smoke-$RUN@example.test"
IDEMPOTENCY_KEY="smoke-test:$RUN"
BODY="$(jq -n --arg to "$RECIPIENT" '{
	templateKey: "password-reset",
	to: {email: $to, name: "Smoke Test"},
	variables: {firstName: "Smoke", resetUrl: "https://demo.localhost/reset?token=smoke", expiresInMinutes: 5}
}')"

send() {
	curl -sS -w '\n%{http_code}' -X POST "$BASE_URL/v1/emails" \
		-H "Authorization: Bearer $API_KEY" -H 'Content-Type: application/json' \
		-H "Idempotency-Key: $IDEMPOTENCY_KEY" -d "$BODY"
}

response="$(send)"
status="$(tail -n 1 <<< "$response")"
accepted="$(sed '$d' <<< "$response")"
[[ "$status" == 202 ]] || fail "POST /v1/emails answered $status: $accepted"
id="$(jq -r .id <<< "$accepted")"
echo "Accepted message $id ($(jq -r '.status + ", " + .locale' <<< "$accepted"))."

repeat="$(send)"
[[ "$(tail -n 1 <<< "$repeat")" == 200 ]] || fail "repeating with the same Idempotency-Key did not answer 200"
[[ "$(sed '$d' <<< "$repeat" | jq -r .id)" == "$id" ]] || fail "the idempotent repeat returned another id"
echo "Idempotent repeat returned the same message."

for ((i = 0; i < TIMEOUT_SECONDS; i++)); do
	found="$(curl -fsS "$MAILPIT_URL/api/v1/messages?limit=200" \
		| jq -r --arg to "$RECIPIENT" '[.messages[] | select(.To[0].Address == $to)] | first | .ID // empty')"
	[[ -n "$found" ]] && break
	sleep 1
done
[[ -n "${found:-}" ]] || fail "no email for $RECIPIENT reached Mailpit within ${TIMEOUT_SECONDS}s"

message="$(curl -fsS "$MAILPIT_URL/api/v1/message/$found")"
[[ "$(jq -r .Subject <<< "$message")" == "Smoke, restablece tu contraseña" ]] || fail "unexpected subject: $(jq -r .Subject <<< "$message")"
[[ "$(jq -r .MessageID <<< "$message")" == "$id@email-service" ]] || fail "Message-ID does not carry the message id"
jq -e '.HTML | contains("Hola Smoke")' <<< "$message" > /dev/null || fail "the HTML body was not rendered"
jq -e '.Text | contains("https://demo.localhost/reset?token=smoke")' <<< "$message" > /dev/null \
	|| fail "the text body is missing the link"

echo "OK: message $id delivered to Mailpit in about ${i}s ($MAILPIT_URL)."
