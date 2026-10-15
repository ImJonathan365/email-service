#!/usr/bin/env bash
# Simulates a Resend webhook for a message, for local development (docs/12): no real provider is
# involved. The event takes the same path as a real one: POST /webhooks/resend, Svix signature,
# state precedence and suppressions. Nothing in the service knows it is simulated.
#
# Usage: [EMAIL_SERVICE_URL=http://localhost:8080] [MAIL_WEBHOOK_SIGNING_SECRET=whsec_...] \
#          scripts/simulate-event.sh {messageId} delivered|bounced|complained|delayed
# The secret comes from the environment, else from .env, else from .env.example (the fictional
# local secret docker-compose.yml passes to the app). Needs curl and openssl.
#
# The event correlates by the message_id tag, as real Resend events do first. data.email_id is
# left out unless EMAIL_ID is set: with SMTP (Mailpit) there is no provider id to match, and a
# made-up one would conflict with a stored one. data.to stays empty unless TO is set, so a typo in
# the message id can never suppress an address: an uncorrelated event only suppresses data.to.
set -euo pipefail

die() {
	echo "simulate-event: $*" >&2
	exit 1
}

[[ $# -eq 2 ]] || die "usage: $0 {messageId} delivered|bounced|complained|delayed"
MESSAGE_ID="$1"
[[ "$MESSAGE_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
	|| die "messageId must be the UUID that POST /v1/emails returned"
case "$2" in
	delivered) TYPE="email.delivered" EXTRA="" ;;
	bounced) TYPE="email.bounced" EXTRA=', "bounce": {"message": "Simulated hard bounce", "type": "Permanent", "subType": "General"}' ;;
	complained) TYPE="email.complained" EXTRA="" ;;
	delayed) TYPE="email.delivery_delayed" EXTRA="" ;;
	*) die "unknown event '$2': use delivered, bounced, complained or delayed" ;;
esac
command -v openssl > /dev/null || die "openssl is required"
command -v curl > /dev/null || die "curl is required"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${EMAIL_SERVICE_URL:-${BASE_URL:-http://localhost:8080}}"
SECRET="${MAIL_WEBHOOK_SIGNING_SECRET:-}"
for file in "$ROOT/.env" "$ROOT/.env.example"; do
	if [[ -z "$SECRET" && -f "$file" ]]; then
		SECRET="$(sed -n 's/^MAIL_WEBHOOK_SIGNING_SECRET=//p' "$file" | tail -n 1)"
	fi
done
# During a rotation the variable holds several secrets; any of them is accepted.
SECRET="${SECRET%%,*}"
[[ "$SECRET" =~ ^whsec_[A-Za-z0-9+/]+=*$ ]] || die "MAIL_WEBHOOK_SIGNING_SECRET is not set or is not a whsec_ secret"

[[ -z "${TO:-}" || "$TO" =~ ^[^\"\\[:space:]]+@[^\"\\[:space:]]+$ ]] || die "TO must be one plain email address"
[[ -z "${EMAIL_ID:-}" || "$EMAIL_ID" =~ ^[A-Za-z0-9_-]{1,100}$ ]] || die "EMAIL_ID has an unexpected format"

NOW="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
TIMESTAMP="$(date +%s)"
EVENT_ID="msg_$(openssl rand -hex 16)"
EMAIL_ID_FIELD="${EMAIL_ID:+\"email_id\": \"$EMAIL_ID\", }"
TO_FIELD="${TO:+\"$TO\"}"
BODY="{\"type\": \"$TYPE\", \"created_at\": \"$NOW\", \"data\": {\"created_at\": \"$NOW\", ${EMAIL_ID_FIELD}\"from\": \"no-reply@localhost\", \"to\": [$TO_FIELD], \"subject\": \"Simulated\", \"tags\": {\"message_id\": \"$MESSAGE_ID\"}$EXTRA}}"

# Svix: HMAC-SHA256 of "id.timestamp.body" keyed with the base64-decoded part after whsec_.
KEY_HEX="$(printf '%s' "${SECRET#whsec_}" | openssl base64 -d -A | od -An -v -tx1 | tr -d ' \n')"
SIGNATURE="$(printf '%s' "$EVENT_ID.$TIMESTAMP.$BODY" \
	| openssl dgst -sha256 -mac HMAC -macopt "hexkey:$KEY_HEX" -binary | openssl base64 -A)"

STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/webhooks/resend" \
	-H 'Content-Type: application/json' \
	-H "svix-id: $EVENT_ID" -H "svix-timestamp: $TIMESTAMP" -H "svix-signature: v1,$SIGNATURE" \
	--data-binary "$BODY")"
case "$STATUS" in
	2??) echo "Sent $TYPE for message $MESSAGE_ID: HTTP $STATUS." ;;
	401) die "HTTP 401: the signature was refused; the app and this script must use the same MAIL_WEBHOOK_SIGNING_SECRET" ;;
	404) die "HTTP 404: /webhooks/resend is off; start the app with MAIL_WEBHOOK_SIGNING_SECRET set (docker-compose.yml passes it)" ;;
	*) die "HTTP $STATUS from $BASE_URL/webhooks/resend" ;;
esac
