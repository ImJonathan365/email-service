#!/usr/bin/env bash
# Publishes a template kept as code in templates/{tenant}/{templateKey}/ (docs/05 §9):
#   template.json            key, name, description, category, trackingEnabled, subject per locale,
#                            variablesSchema and previewVariables
#   {locale}.html            HTML body per locale (required)
#   {locale}.txt             plain-text body per locale (optional)
#
# Usage: EMAIL_SERVICE_TEMPLATES_KEY=esk_... [BASE_URL=...] scripts/publish-template.sh {tenant} {templateKey}
# The key needs templates:write and emails:read and must belong to {tenant}. Locales whose content
# equals the published version are skipped; the others become drafts, are previewed with
# previewVariables and are published only if every preview succeeds, all locales in one atomic
# request (ADR-0020).
set -euo pipefail

cd "$(dirname "$0")/.."

die() {
	echo "publish-template: $*" >&2
	exit 1
}

[[ $# -eq 2 ]] || die "usage: $0 {tenant} {templateKey}"
TENANT="$1"
KEY="$2"
DIR="templates/$TENANT/$KEY"
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${EMAIL_SERVICE_TEMPLATES_KEY:-}"

command -v jq > /dev/null || die "jq is required (it is declared in mise.toml: run 'mise install')"
[[ -n "$API_KEY" ]] || die "EMAIL_SERVICE_TEMPLATES_KEY is not set"
[[ -f "$DIR/template.json" ]] || die "$DIR/template.json not found"
jq -e 'type == "object"' "$DIR/template.json" > /dev/null || die "$DIR/template.json is not a JSON object"
[[ "$(jq -r .key "$DIR/template.json")" == "$KEY" ]] || die "template.json key does not match the folder name '$KEY'"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CREATED=()

# api METHOD PATH [BODY_FILE] -> response body in $WORK/response, HTTP status in $STATUS
api() {
	local method="$1" path="$2" body="${3:-}"
	local args=(--silent --show-error --output "$WORK/response" --write-out '%{http_code}' --request "$method"
		--header "Authorization: Bearer $API_KEY")
	if [[ -n "$body" ]]; then
		args+=(--header 'Content-Type: application/json' --data-binary "@$body")
	fi
	STATUS="$(curl "${args[@]}" "$BASE_URL$path")"
}

problem() {
	jq -r '"\(.status) \(.code): \(.detail)", ((.errors // [])[] | "  \(.field): \(.message)")' "$WORK/response" 2> /dev/null \
		|| cat "$WORK/response"
}

# Deletes the drafts this run created, so a failed run leaves no drafts behind. Publication is a
# single request (one version, or all locales jointly), so a failed run never publishes anything.
abort() {
	echo "publish-template: $1" >&2
	problem >&2
	for version in "${CREATED[@]}"; do
		api DELETE "/v1/templates/$KEY/versions/$version" || true
	done
	exit 1
}

api GET "/v1/templates/$KEY"
case "$STATUS" in
	200)
		cp "$WORK/response" "$WORK/detail"
		existing="$(jq -r .category "$WORK/detail")"
		wanted="$(jq -r .category "$DIR/template.json")"
		[[ "$existing" == "$wanted" ]] \
			|| die "category is $existing in the service but $wanted in template.json; a category change needs a new template key (AC-36.4)"
		;;
	404)
		jq '{key, name, description, category, trackingEnabled}' "$DIR/template.json" > "$WORK/create"
		api POST /v1/templates "$WORK/create"
		[[ "$STATUS" == 201 ]] || { problem >&2; die "creating template '$KEY' failed"; }
		echo "Created template '$KEY'."
		echo '{"versions": []}' > "$WORK/detail"
		;;
	*)
		problem >&2
		die "reading template '$KEY' failed"
		;;
esac

changed=()
for locale in $(jq -r '.subject | keys[]' "$DIR/template.json"); do
	html="$DIR/$locale.html"
	text="$DIR/$locale.txt"
	[[ -f "$html" ]] || die "$html not found (every locale in template.json needs one)"
	text_args=(--argjson text null)
	[[ -f "$text" ]] && text_args=(--rawfile text "$text")
	jq --arg locale "$locale" --rawfile html "$html" "${text_args[@]}" \
		'{locale: $locale, subjectTemplate: .subject[$locale], htmlTemplate: $html, textTemplate: $text,
		  variablesSchema: .variablesSchema}' "$DIR/template.json" > "$WORK/version-$locale"

	content='{subjectTemplate, htmlTemplate, textTemplate, variablesSchema}'
	candidate="$(jq -S -c "$content" "$WORK/version-$locale")"
	published="$(jq -S -c --arg l "$locale" \
		"[.versions[] | select(.locale == \$l and .status == \"PUBLISHED\")] | first | if . == null then null else $content end" \
		"$WORK/detail")"
	if [[ "$candidate" == "$published" ]]; then
		echo "$locale: unchanged, already published."
	else
		changed+=("$locale")
	fi
done

if [[ ${#changed[@]} -eq 0 ]]; then
	echo "Template '$KEY' is up to date."
	exit 0
fi

declare -A versions
for locale in "${changed[@]}"; do
	api POST "/v1/templates/$KEY/versions" "$WORK/version-$locale"
	[[ "$STATUS" == 201 ]] || abort "creating the $locale draft failed"
	versions[$locale]="$(jq -r .version "$WORK/response")"
	CREATED+=("${versions[$locale]}")
done

for locale in "${changed[@]}"; do
	jq --argjson v "${versions[$locale]}" '{templateVersion: $v, variables: .previewVariables}' "$DIR/template.json" \
		> "$WORK/preview"
	api POST "/v1/templates/$KEY/preview" "$WORK/preview"
	[[ "$STATUS" == 200 ]] || abort "preview of the $locale draft (version ${versions[$locale]}) failed"
	echo "$locale: preview OK, subject: $(jq -r .subject "$WORK/response")$(jq -r 'if (.warnings | length) > 0 then " (warnings: \(.warnings | join(", ")))" else "" end' "$WORK/response")"
done

if [[ ${#changed[@]} -eq 1 ]]; then
	locale="${changed[0]}"
	api POST "/v1/templates/$KEY/versions/${versions[$locale]}/publish"
	[[ "$STATUS" == 200 ]] || abort "publishing the $locale draft (version ${versions[$locale]}) failed"
	echo "$locale: published version ${versions[$locale]}."
	exit 0
fi

# Several locales: one atomic publication (ADR-0020), so changing the required variables of a
# multi-locale template works and a failure publishes nothing.
joint='{"versions": {}}'
for locale in "${changed[@]}"; do
	joint="$(jq --arg l "$locale" --argjson v "${versions[$locale]}" '.versions[$l] = $v' <<< "$joint")"
done
echo "$joint" > "$WORK/joint"
api POST "/v1/templates/$KEY/publish" "$WORK/joint"
[[ "$STATUS" == 200 ]] || abort "publishing ${changed[*]} together failed; nothing was published"
jq -r '.published[] | "\(.locale): published version \(.version)."' "$WORK/response"
