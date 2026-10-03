#!/usr/bin/env bash
# Walk through every API behaviour once, printing expected vs actual status.
#   ADMIN_KEY=... ./demo.sh https://your-app.up.railway.app
set -uo pipefail
URL="${1:?usage: ADMIN_KEY=... ./demo.sh <BASE_URL>}"
URL="${URL%/}"
ADMIN_KEY="${ADMIN_KEY:-dev-admin-key}"
RUN=$(date +%s)
pass=0; fail=0

field() { sed -nE "s/.*\"$1\":\"([^\"]+)\".*/\1/p"; }

# step <description> <expected status> <curl args...>; leaves body in $BODY
step() {
  local desc="$1" expected="$2"; shift 2
  local out; out=$(curl -s -w $'\n%{http_code}' "$@")
  BODY="${out%$'\n'*}"; local code="${out##*$'\n'}"
  if [ "$code" = "$expected" ]; then mark="PASS"; pass=$((pass+1)); else mark="FAIL"; fail=$((fail+1)); fi
  printf '%-4s %-62s expected %s got %s\n' "$mark" "$desc" "$expected" "$code"
  printf '     %s\n' "$(echo "$BODY" | cut -c1-160)"
}

token() {
  curl -s -X POST "$URL/auth/token" -H 'Content-Type: application/json' -d "$1" | field access_token
}

echo "== $URL"
step "readiness (DB reachable)" 200 "$URL/actuator/health/readiness"

ADMIN=$(token "{\"user_id\":\"demo-admin\",\"admin_key\":\"$ADMIN_KEY\"}")
ALICE=$(token "{\"user_id\":\"alice-$RUN\"}")
BOB=$(token "{\"user_id\":\"bob-$RUN\"}")
if [ -z "$ADMIN" ] || [ -z "$ALICE" ] || [ -z "$BOB" ]; then
  echo "could not get tokens (wrong ADMIN_KEY?)"; exit 1
fi

J=(-H 'Content-Type: application/json')
step "create show as a normal user -> forbidden" 403 -X POST "$URL/shows" "${J[@]}" -H "Authorization: Bearer $ALICE" \
  -d '{"name":"x","seats":["A1"],"price_paise":100}'
step "create show (admin), 4 seats, limit 2" 201 -X POST "$URL/shows" "${J[@]}" -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"demo","seats":["A1","A2","A3","A4"],"price_paise":25000,"per_user_limit":2}'
SHOW=$(echo "$BODY" | field id)

reserve() { # token key seats-json
  curl -s -w $'\n%{http_code}' -X POST "$URL/shows/$SHOW/reserve" "${J[@]}" -H "Authorization: Bearer $1" \
    -d "{\"seats\":$3,\"idempotency_key\":\"$2\"}"
}
rstep() { # desc expected token key seats-json
  local out; out=$(reserve "$3" "$4" "$5"); BODY="${out%$'\n'*}"; local code="${out##*$'\n'}"
  if [ "$code" = "$2" ]; then mark="PASS"; pass=$((pass+1)); else mark="FAIL"; fail=$((fail+1)); fi
  printf '%-4s %-62s expected %s got %s\n' "$mark" "$1" "$2" "$code"
  printf '     %s\n' "$(echo "$BODY" | cut -c1-160)"
}

rstep "alice reserves A1" 201 "$ALICE" k1 '["A1"]'
RES=$(echo "$BODY" | field reservation_id)
rstep "alice retries same key -> replay of same reservation" 200 "$ALICE" k1 '["A1"]'
rstep "alice reuses key for different seat -> rejected" 409 "$ALICE" k1 '["A2"]'
rstep "bob tries A1 -> seat taken" 409 "$BOB" k2 '["A1"]'
rstep "bob asks for A1+A2 -> all-or-nothing decline" 409 "$BOB" k3 '["A1","A2"]'
rstep "bob reserves A2 (proves A2 was not taken above)" 201 "$BOB" k4 '["A2"]'
rstep "alice reserves A3 (now at limit 2)" 201 "$ALICE" k5 '["A3"]'
rstep "alice tries A4 -> per-user limit" 409 "$ALICE" k6 '["A4"]'
rstep "unknown seat -> 400" 400 "$ALICE" k7 '["Z9"]'

step "bob spoofs user_id=alice in body -> booked as bob anyway" 201 -X POST "$URL/shows/$SHOW/reserve" "${J[@]}" \
  -H "Authorization: Bearer $BOB" -d '{"seats":["A4"],"idempotency_key":"k8","user_id":"alice"}'
echo "     -> user_id in response: $(echo "$BODY" | field user_id)"

step "no token -> 401" 401 -X POST "$URL/shows/$SHOW/reserve" "${J[@]}" -d '{"seats":["A4"],"idempotency_key":"k9"}'
step "bob cancels alice's reservation -> 404" 404 -X POST "$URL/reservations/$RES/cancel" -H "Authorization: Bearer $BOB"
step "alice cancels her A1 reservation" 200 -X POST "$URL/reservations/$RES/cancel" -H "Authorization: Bearer $ALICE"
step "alice cancels again -> idempotent" 200 -X POST "$URL/reservations/$RES/cancel" -H "Authorization: Bearer $ALICE"
step "show state (A1 available again, reconciled)" 200 "$URL/shows/$SHOW"
echo "     -> counts: $(echo "$BODY" | sed -nE 's/.*"counts":(\{[^}]*\}).*/\1/p')"

echo
echo "metrics (counters are service-wide; gauges refresh every 5s):"
curl -s "$URL/actuator/prometheus" | grep -E '^reservations_(confirmed|cancelled)_total|^reservations_declined_total' | sed 's/^/  /'
echo
echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
