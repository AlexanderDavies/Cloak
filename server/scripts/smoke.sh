#!/usr/bin/env bash
# Cloak server — local smoke test.
#
# Drives the running server (./gradlew bootRun) against the local infra (./dev.sh up).
# Mints real Keycloak tokens via the seeded `cloak-test` client (direct-access grant)
# and exercises /v1/me and the /ws WebSocket round-trip.
#
# Deps: curl, jq, python3; `roundtrip` also needs websocat (brew install websocat).
# Env overrides: KC_URL, API_URL, WS_URL, CLIENT_ID, PASSWORD.
set -euo pipefail

KC_URL="${KC_URL:-http://localhost:8081/realms/cloak}"
API_URL="${API_URL:-http://localhost:8080}"
WS_URL="${WS_URL:-ws://localhost:8080/ws}"
CLIENT_ID="${CLIENT_ID:-cloak-test}"
PASSWORD="${PASSWORD:-password}"

die() { echo "error: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing dependency: $1${2:+ ($2)}"; }

# mint <user> -> prints an access token
mint() {
  curl -sf -X POST "$KC_URL/protocol/openid-connect/token" \
    -d grant_type=password -d "client_id=$CLIENT_ID" \
    -d "username=$1" -d "password=$PASSWORD" -d scope=openid \
    | jq -r .access_token
}

# sub_of <jwt> -> prints the `sub` claim
sub_of() {
  python3 -c "import sys,json,base64;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);print(json.loads(base64.urlsafe_b64decode(p))['sub'])" "$1"
}

cmd_health() {
  need curl
  curl -sf "$API_URL/actuator/health"; echo
}

cmd_token() {
  need curl; need jq
  mint "${1:-alice}"
}

cmd_me() {
  need curl; need jq
  local user="${1:-alice}" token
  curl -s -o /dev/null -w "no token   -> %{http_code} (expect 401)\n" "$API_URL/v1/me"
  token="$(mint "$user")"
  printf 'bearer %-5s -> ' "$user"
  curl -s -o /dev/null -w "%{http_code} " "$API_URL/v1/me" -H "Authorization: Bearer $token"
  curl -sf "$API_URL/v1/me" -H "Authorization: Bearer $token"; echo " (expect 200 + sub)"
}

cmd_roundtrip() {
  need curl; need jq; need python3; need websocat "brew install websocat"
  local msg="${1:-hello from alice}" alice bob bobsub ct bobpid
  alice="$(mint alice)"; bob="$(mint bob)"; bobsub="$(sub_of "$bob")"
  ct="$(printf '%s' "$msg" | base64)"

  echo "bob ($bobsub) connecting and listening..."
  # websocat's -H is multi-value; use the -H=... form so it doesn't swallow the URL.
  websocat -H="Authorization: Bearer $bob" "$WS_URL" | sed 's/^/[bob received] /' &
  bobpid=$!
  trap 'kill "$bobpid" 2>/dev/null || true' EXIT
  sleep 1

  echo "alice sending to bob (ciphertext base64=$ct)..."
  { printf '{"messageId":"%s","toSub":"%s","deviceId":null,"ciphertext":"%s"}\n' \
      "$(uuidgen)" "$bobsub" "$ct"; sleep 1; } \
    | websocat -H="Authorization: Bearer $alice" "$WS_URL"

  sleep 2
  echo "done — bob should have received the delivery frame above (fromSub = alice, ciphertext unchanged)."
}

usage() {
  cat <<'EOF'
Cloak server local smoke test. Run `./dev.sh up` (infra) and `./gradlew bootRun` (server) first.

usage: ./scripts/smoke.sh <command>
  health              GET /actuator/health
  token [user]        mint and print a Keycloak access token (default: alice)
  me [user]           call /v1/me with no token (401) then a valid one (200 + sub)
  roundtrip [message] full alice->bob WebSocket round-trip (needs websocat)

deps: curl, jq, python3; roundtrip also needs websocat (brew install websocat)
env overrides: KC_URL, API_URL, WS_URL, CLIENT_ID, PASSWORD
EOF
}

case "${1:-}" in
  health)    cmd_health ;;
  token)     shift; cmd_token "$@" ;;
  me)        shift; cmd_me "$@" ;;
  roundtrip) shift; cmd_roundtrip "$@" ;;
  ""|-h|--help|help) usage ;;
  *) usage; exit 1 ;;
esac
