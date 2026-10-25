#!/usr/bin/env bash
# Creates sample data through the API, so every row also goes through validation, the cache and the outbox.
# Usage: scripts/seed.sh [base-url]
set -euo pipefail

BASE="${1:-http://localhost:8080/user-points-service}"
REASONS=(signup_bonus daily_login referral purchase event_reward)

add() {
  local user="$1" amount="$2" reason="$3"
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/points" \
    -H 'Content-Type: application/json' -H "X-Request-Id: seed-$user-$amount" \
    -d "{\"userId\":\"$user\",\"amount\":$amount,\"reason\":\"$reason\"}")
  if [ "$status" != "201" ]; then
    echo "POST for $user returned $status; is the service running at $BASE?" >&2
    exit 1
  fi
}

# Fixed amounts keep the leaderboard the same on every run against an empty database.
for i in $(seq 1 15); do
  user=$(printf 'seed_user_%02d' "$i")
  for j in 1 2 3; do
    add "$user" $(( (i * 37 + j * 11) % 500 + 10 )) "${REASONS[$(( (i + j) % ${#REASONS[@]} ))]}"
  done
done
# Two users with the same total, to show the userId tie-break.
add seed_tie_a 1000 event_reward
add seed_tie_b 1000 event_reward

echo "Created 47 records for 17 users."
echo "Leaderboard:"
curl -s "$BASE/points/leaderboard"
echo
