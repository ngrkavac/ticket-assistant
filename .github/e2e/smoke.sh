#!/usr/bin/env bash
# End-to-end smoke test (T6.5) against a running docker-compose stack, driven with curl the way a
# browser uses it: oauth2-proxy -> Keycloak login form -> session cookie -> nginx -> assistant.
#
#   1. sign in as a Keycloak demo user through oauth2-proxy (authorization code + PKCE, K2)
#   2. GET /customers lists exactly that user's customers (K3)
#   3. POST /ask for an allowed customer answers 200 with a non-empty answer (LLM + MCP + RAG)
#   4. the request shows up in GET /requests (history, written via the request-log MCP server)
#   5. the same user asking for, or reading the history of, another customer gets 403
#   6. without a session the API answers 401
#
# Needs bash, curl and jq. Used by .github/workflows/e2e-smoke.yml; also runs against a local
# `docker compose up` (README, "Local sign-in (Keycloak)").
#
# The realm only accepts the redirect URI http://localhost:5173/oauth2/callback and Keycloak's
# issuer is http://localhost:8180, so the URLs always use those. If the stack is published on
# other host ports (e.g. a second compose project), set E2E_UI_PORT / E2E_KEYCLOAK_PORT and curl
# connects there while keeping the URLs unchanged (--connect-to).
set -euo pipefail

UI=http://localhost:5173
E2E_UI_PORT=${E2E_UI_PORT:-5173}
E2E_KEYCLOAK_PORT=${E2E_KEYCLOAK_PORT:-8180}
E2E_USER=${E2E_USER:-acme-agent}
E2E_PASSWORD=${E2E_PASSWORD:-local-only-password}   # the realm's public demo password
E2E_CUSTOMER=${E2E_CUSTOMER:-acme}                  # E2E_USER's only customer
E2E_OTHER_CUSTOMER=${E2E_OTHER_CUSTOMER:-globex}    # a customer E2E_USER must not see
E2E_QUESTION=${E2E_QUESTION:-How do I reset my password?}
E2E_WAIT_SECONDS=${E2E_WAIT_SECONDS:-300}            # how long to wait for the stack to answer

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
jar=$work/cookies.txt

curl_args=(--silent --show-error --max-time 200 --cookie "$jar" --cookie-jar "$jar")
[[ $E2E_UI_PORT == 5173 ]] || curl_args+=(--connect-to "localhost:5173:127.0.0.1:$E2E_UI_PORT")
[[ $E2E_KEYCLOAK_PORT == 8180 ]] ||
  curl_args+=(--connect-to "localhost:8180:127.0.0.1:$E2E_KEYCLOAK_PORT")

step() { printf '\n==> %s\n' "$*"; }
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}
# req METHOD URL [curl args...]: body in $work/body, prints the HTTP status
req() {
  local method=$1 url=$2
  shift 2
  curl "${curl_args[@]}" -X "$method" -o "$work/body" -w '%{http_code}' "$@" "$url"
}
expect_status() { # expect_status WANT GOT WHAT
  [[ $2 == "$1" ]] || fail "$3: HTTP $2, expected $1. Body: $(head -c 500 "$work/body")"
  echo "ok: $3 -> HTTP $2"
}

step "waiting up to ${E2E_WAIT_SECONDS}s for oauth2-proxy and the Keycloak realm"
deadline=$((SECONDS + E2E_WAIT_SECONDS))
until [[ $(req GET "$UI/ping" || true) == 200 ]] &&
  [[ $(req GET "http://localhost:8180/realms/helpdesk" || true) == 200 ]]; do
  ((SECONDS < deadline)) || fail "stack not reachable after ${E2E_WAIT_SECONDS}s"
  sleep 5
done
echo "ok: stack is up"

step "without a session the API answers 401"
status=$(req GET "$UI/customers")
expect_status 401 "$status" "GET /customers without a session"

step "sign in as $E2E_USER through oauth2-proxy and Keycloak"
# A page load without a session is sent to the Keycloak login page (skip-provider-button).
login_page=$work/login.html
curl "${curl_args[@]}" --location -o "$login_page" "$UI/"
action=$(grep -o 'action="[^"]*login-actions/authenticate[^"]*"' "$login_page" | head -n 1 |
  sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g') ||
  true
[[ -n $action ]] || fail "no Keycloak login form at $UI/. Page: $(head -c 500 "$login_page")"
# Keycloak redirects to /oauth2/callback, where oauth2-proxy redeems the code, sets the session
# cookie and redirects to the UI.
final=$(curl "${curl_args[@]}" --location -o "$work/body" -w '%{http_code} %{url_effective}' \
  --data-urlencode "username=$E2E_USER" --data-urlencode "password=$E2E_PASSWORD" \
  --data-urlencode "credentialId=" "$action")
[[ $final == "200 $UI/" ]] ||
  fail "sign-in ended at '$final', expected '200 $UI/'. Body: $(head -c 500 "$work/body")"
grep -q '<div id="root">' "$work/body" || fail "the UI page after sign-in is not the SPA"
status=$(req GET "$UI/oauth2/userinfo")
expect_status 200 "$status" "GET /oauth2/userinfo"
user=$(jq -r '.preferredUsername // .user' "$work/body")
[[ $user == "$E2E_USER" ]] || fail "signed in as '$user', expected '$E2E_USER'"
echo "ok: signed in as $user"

step "GET /customers lists only $E2E_CUSTOMER"
status=$(req GET "$UI/customers")
expect_status 200 "$status" "GET /customers"
customers=$(jq -c . "$work/body")
[[ $customers == "[\"$E2E_CUSTOMER\"]" ]] || fail "customers $customers, expected [\"$E2E_CUSTOMER\"]"
echo "ok: customers $customers"

step "POST /ask for $E2E_CUSTOMER"
request_id=e2e-$(date +%s)-$RANDOM
started=$SECONDS
status=$(req POST "$UI/ask" -H 'Content-Type: application/json' -H "X-Request-Id: $request_id" \
  --data "$(jq -n --arg q "$E2E_QUESTION" --arg c "$E2E_CUSTOMER" '{question: $q, customer: $c}')")
expect_status 200 "$status" "POST /ask ($((SECONDS - started))s, request id $request_id)"
answer=$(jq -r '.answer // empty' "$work/body")
[[ -n ${answer//[[:space:]]/} ]] || fail "empty answer: $(head -c 500 "$work/body")"
printf 'answer (first 300 chars): %s\n' "${answer:0:300}"

step "the request is in the history of $E2E_CUSTOMER"
# The assistant writes the request log in the background, so give it a moment.
row=
for _ in $(seq 1 15); do
  status=$(req GET "$UI/requests?customer=$E2E_CUSTOMER&limit=100")
  expect_status 200 "$status" "GET /requests?customer=$E2E_CUSTOMER" >/dev/null
  row=$(jq -c --arg id "$request_id" '.[] | select(.requestId == $id)' "$work/body")
  [[ -z $row ]] || break
  sleep 2
done
[[ -n $row ]] || fail "request $request_id not in the history: $(head -c 500 "$work/body")"
jq -e --arg c "$E2E_CUSTOMER" --arg q "$E2E_QUESTION" \
  '.customer == $c and .question == $q and .status == "SUCCESS"' <<<"$row" >/dev/null ||
  fail "unexpected history row: $row"
echo "ok: history row $row"

step "$E2E_USER may not use $E2E_OTHER_CUSTOMER"
status=$(req POST "$UI/ask" -H 'Content-Type: application/json' \
  --data "$(jq -n --arg c "$E2E_OTHER_CUSTOMER" '{question: "List my open tickets", customer: $c}')")
expect_status 403 "$status" "POST /ask for $E2E_OTHER_CUSTOMER"
status=$(req GET "$UI/requests?customer=$E2E_OTHER_CUSTOMER")
expect_status 403 "$status" "GET /requests?customer=$E2E_OTHER_CUSTOMER"

printf '\nE2E smoke test passed.\n'
