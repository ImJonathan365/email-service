#!/usr/bin/env bash
# Seeds a local stack (docker compose up) through the admin API: a demo tenant, a product key
# (emails:send, emails:read) and an operator key (templates:write, suppressions:write, emails:read).
# Local use only. Keys are printed once and never written to disk; each run issues new keys.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
SLUG="${SLUG:-demo}"

if [[ -z "${ADMIN_API_KEYS:-}" && -f .env ]]; then
	ADMIN_API_KEYS="$(grep -E '^ADMIN_API_KEYS=' .env | head -n 1 | cut -d= -f2-)"
fi
ADMIN_KEY="${ADMIN_API_KEYS%%,*}"
if [[ -z "$ADMIN_KEY" ]]; then
	echo "ADMIN_API_KEYS is not set (environment or .env)" >&2
	exit 1
fi

admin() {
	local method="$1" path="$2" body="${3:-}"
	curl --silent --show-error --write-out '\n%{http_code}' --request "$method" \
		--header "X-Admin-Key: $ADMIN_KEY" --header 'Content-Type: application/json' \
		${body:+--data "$body"} "$BASE_URL$path"
}

status_of() { tail -n 1 <<<"$1"; }
body_of() { sed '$d' <<<"$1"; }

response="$(admin POST /admin/v1/tenants "$(cat <<JSON
{"slug": "$SLUG", "name": "Demo", "fromEmail": "no-reply@demo.localhost", "fromName": "Demo",
 "allowedFromDomains": ["demo.localhost"], "allowedLinkHosts": ["demo.localhost"]}
JSON
)")"
case "$(status_of "$response")" in
	201) echo "Created tenant '$SLUG'." ;;
	409) echo "Tenant '$SLUG' already exists; issuing new keys." ;;
	*) echo "Creating the tenant failed:" >&2; body_of "$response" >&2; exit 1 ;;
esac

issue() {
	local name="$1" scopes="$2" response
	response="$(admin POST "/admin/v1/tenants/$SLUG/api-keys" "{\"name\": \"$name\", \"scopes\": $scopes}")"
	if [[ "$(status_of "$response")" != 201 ]]; then
		echo "Issuing '$name' failed:" >&2
		body_of "$response" >&2
		exit 1
	fi
	body_of "$response" | grep -o '"apiKey":"[^"]*"' | cut -d'"' -f4
}

product_key="$(issue "local product" '["emails:send", "emails:read"]')"
operator_key="$(issue "local operator" '["templates:write", "suppressions:write", "emails:read"]')"

cat <<EOF

Store these now; they are not shown again.
  Product key  (emails:send, emails:read):                   $product_key
  Operator key (templates:write, suppressions:write, read):  $operator_key

EOF
# TODO(H3): create and publish a sample template once the template endpoints exist.
