#!/usr/bin/env bash

set -u
set -o pipefail

HOST="${1:-192.168.0.102}"
PORT="${2:-17171}"
TEXT="${3:-你好，ACTL 输入测试 123}"
BASE_URL="http://${HOST}:${PORT}"
URL="${BASE_URL}/v1/control/input"
ERR_FILE="/tmp/actl_input_tester_err.$$"

print_line() {
  printf '%s\n' "------------------------------------------------------------"
}

build_payload() {
  local text="$1"
  local enter_action="$2"
  local press_enter="$3"
  python3 - "$text" "$enter_action" "$press_enter" <<'PY'
import json, sys
text = sys.argv[1]
enter_action = sys.argv[2]
press_enter = sys.argv[3].lower() == "true"
print(json.dumps({
    "text": text,
    "pressEnter": press_enter,
    "enterAction": enter_action
}, ensure_ascii=False))
PY
}

run_case() {
  local name="$1"
  local text="$2"
  local enter_action="$3"
  local press_enter="$4"

  local payload
  payload="$(build_payload "$text" "$enter_action" "$press_enter")"

  local tmp_body
  tmp_body="$(mktemp)"

  local curl_status
  local http_code
  http_code="$(curl -sS -o "$tmp_body" -w "%{http_code}" \
    --connect-timeout 4 --max-time 20 \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$payload" \
    "$URL" 2>"$ERR_FILE")"
  curl_status=$?

  print_line
  printf '[TEST] %s\n' "$name"
  printf '  POST %s\n' "$URL"
  printf '  Text : %s\n' "$text"
  printf '  Body : %s\n' "$payload"

  if [ "$curl_status" -ne 0 ]; then
    printf '  Result: FAIL (curl error)\n'
    printf '  Error : %s\n' "$(cat "$ERR_FILE")"
    rm -f "$tmp_body"
    return 1
  fi

  local body
  body="$(cat "$tmp_body")"
  printf '  HTTP : %s\n' "$http_code"
  printf '  Resp : %s\n' "$body"

  if [ "$http_code" = "200" ] && printf '%s' "$body" | grep -Fq '"code":0' && printf '%s' "$body" | grep -Fq '"mode":"'; then
    printf '  Result: PASS\n'
    rm -f "$tmp_body"
    return 0
  fi

  printf '  Result: FAIL\n'
  rm -f "$tmp_body"
  return 1
}

print_line
printf 'ACTL Input API Tester\n'
printf 'Target: %s\n' "$BASE_URL"
print_line

PASS=0
FAIL=0

if run_case "IME Inject (Chinese)" "$TEXT" "auto" "false"; then
  PASS=$((PASS + 1))
else
  FAIL=$((FAIL + 1))
fi

if run_case "Newline Keep (No Enter Action)" $'第一段\n第二段保留换行' "none" "false"; then
  PASS=$((PASS + 1))
else
  FAIL=$((FAIL + 1))
fi

if run_case "Press Enter With Auto Action" "发送测试" "auto" "true"; then
  PASS=$((PASS + 1))
else
  FAIL=$((FAIL + 1))
fi

print_line
printf 'Summary: PASS=%d FAIL=%d\n' "$PASS" "$FAIL"
print_line

rm -f "$ERR_FILE"

if [ "$FAIL" -ne 0 ]; then
  exit 1
fi

exit 0
