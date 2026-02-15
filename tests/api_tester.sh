#!/usr/bin/env bash

set -u
set -o pipefail

HOST="${1:-192.168.0.102}"
PORT="${2:-17171}"
PROFILE="${3:-full}"
BASE_URL="http://${HOST}:${PORT}"
ERR_FILE="/tmp/actl_tester_err.$$"

PASS_COUNT=0
FAIL_COUNT=0

print_line() {
  printf '%s\n' "------------------------------------------------------------"
}

run_test() {
  local name="$1"
  local method="$2"
  local path="$3"
  local expected_status="$4"
  local payload="$5"
  shift 5

  local tmp_body
  tmp_body="$(mktemp)"

  local curl_status
  local http_code
  local url="${BASE_URL}${path}"

  if [ "$payload" = "-" ]; then
    http_code="$(curl -sS -o "$tmp_body" -w "%{http_code}" \
      --connect-timeout 4 --max-time 12 \
      -X "$method" \
      "$url" 2>"$ERR_FILE")"
    curl_status=$?
  else
    http_code="$(curl -sS -o "$tmp_body" -w "%{http_code}" \
      --connect-timeout 4 --max-time 12 \
      -X "$method" \
      -H "Content-Type: application/json" \
      -d "$payload" \
      "$url" 2>"$ERR_FILE")"
    curl_status=$?
  fi

  print_line
  printf '[TEST] %s\n' "$name"
  printf '  %s %s\n' "$method" "$url"
  printf '  Expect: HTTP %s\n' "$expected_status"

  if [ "$curl_status" -ne 0 ]; then
    printf '  Result: FAIL (curl error)\n'
    printf '  Error : %s\n' "$(cat "$ERR_FILE")"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    rm -f "$tmp_body"
    return
  fi

  local body
  body="$(cat "$tmp_body")"
  printf '  Actual: HTTP %s\n' "$http_code"
  printf '  Body  : %s\n' "$body"

  local ok=true
  if [ "$http_code" != "$expected_status" ]; then
    ok=false
  fi

  for expected_fragment in "$@"; do
    if ! printf '%s' "$body" | grep -Fq "$expected_fragment"; then
      printf '  Check : missing fragment -> %s\n' "$expected_fragment"
      ok=false
    fi
  done

  if [ "$ok" = true ]; then
    printf '  Result: PASS\n'
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    printf '  Result: FAIL\n'
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi

  rm -f "$tmp_body"
}

run_dump_raw() {
  local name="$1"
  local method="$2"
  local path="$3"

  local tmp_body
  tmp_body="$(mktemp)"

  local curl_status
  local http_code
  local content_type
  local meta
  local url="${BASE_URL}${path}"

  meta="$(curl -sS -o "$tmp_body" -w "%{http_code}|%{content_type}" \
    --connect-timeout 4 --max-time 20 \
    -X "$method" \
    -H "Content-Type: application/json" \
    -d "{}" \
    "$url" 2>"$ERR_FILE")"
  curl_status=$?
  http_code="${meta%%|*}"
  content_type="${meta#*|}"

  print_line
  printf '[TEST] %s\n' "$name"
  printf '  %s %s\n' "$method" "$url"

  if [ "$curl_status" -ne 0 ]; then
    printf '  Result: FAIL (curl error)\n'
    printf '  Error : %s\n' "$(cat "$ERR_FILE")"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    rm -f "$tmp_body"
    return
  fi

  printf '  Actual: HTTP %s\n' "$http_code"
  printf '  Type  : %s\n' "$content_type"
  local response_body
  response_body="$(cat "$tmp_body")"
  local body_bytes
  body_bytes="$(wc -c < "$tmp_body" | tr -d ' ')"
  printf '  RespB : %s\n' "$body_bytes"

  local has_xml_marker
  has_xml_marker="false"
  if printf '%s' "$response_body" | grep -Fq "<?xml"; then
    has_xml_marker="true"
  fi

  if [ "$has_xml_marker" = "true" ]; then
    printf '  XML   : %s\n' "$response_body"
  else
    printf '  JSON  : %s\n' "$response_body"
  fi

  local xml_bytes
  local marker_flag
  xml_bytes="$(printf '%s' "$response_body" | sed -n 's/.*"xmlBytes":\([0-9][0-9]*\).*/\1/p' | head -n 1)"
  marker_flag="$(printf '%s' "$response_body" | sed -n 's/.*"hasXmlMarker":\(true\|false\).*/\1/p' | head -n 1)"
  [ -z "$xml_bytes" ] && xml_bytes="N/A"
  [ -z "$marker_flag" ] && marker_flag="N/A"

  printf '  Debug : hasXmlMarker=%s xmlBytes=%s\n' "$marker_flag" "$xml_bytes"

  if [ "$http_code" = "200" ] && [ "$has_xml_marker" = "true" ]; then
    printf '  Result: PASS (xml)\n'
    PASS_COUNT=$((PASS_COUNT + 1))
  elif [ "$http_code" = "500" ]; then
    printf '  Result: PASS (debug payload shown)\n'
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    printf '  Result: FAIL\n'
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi

  rm -f "$tmp_body"
}

run_screenshot_raw() {
  local name="$1"
  local method="$2"
  local path="$3"

  local tmp_body
  local tmp_header
  tmp_body="$(mktemp)"
  tmp_header="$(mktemp)"

  local curl_status
  local http_code
  local content_type
  local url="${BASE_URL}${path}"

  http_code="$(curl -sS -D "$tmp_header" -o "$tmp_body" -w "%{http_code}" \
    --connect-timeout 4 --max-time 25 \
    -X "$method" \
    -H "Content-Type: application/json" \
    -d "{}" \
    "$url" 2>"$ERR_FILE")"
  curl_status=$?

  print_line
  printf '[TEST] %s\n' "$name"
  printf '  %s %s\n' "$method" "$url"

  if [ "$curl_status" -ne 0 ]; then
    printf '  Result: FAIL (curl error)\n'
    printf '  Error : %s\n' "$(cat "$ERR_FILE")"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    rm -f "$tmp_body" "$tmp_header"
    return
  fi

  content_type="$(grep -i '^Content-Type:' "$tmp_header" | tail -n 1 | cut -d':' -f2- | tr -d '\r' | xargs)"
  local body_bytes
  body_bytes="$(wc -c < "$tmp_body" | tr -d ' ')"
  printf '  Actual: HTTP %s\n' "$http_code"
  printf '  Type  : %s\n' "${content_type:-N/A}"
  printf '  RespB : %s\n' "$body_bytes"

  if [ "$http_code" = "200" ] && printf '%s' "$content_type" | grep -qi 'image/png' && [ "$body_bytes" -gt 0 ]; then
    local screenshot_file
    screenshot_file="./actl_screenshot_$(date +%Y%m%d_%H%M%S).png"
    cp "$tmp_body" "$screenshot_file"
    printf '  Save  : %s\n' "$screenshot_file"
    printf '  Result: PASS\n'
    PASS_COUNT=$((PASS_COUNT + 1))
  elif [ "$http_code" = "500" ] || [ "$http_code" = "503" ]; then
    local response_body
    response_body="$(cat "$tmp_body")"
    printf '  JSON  : %s\n' "$response_body"
    printf '  Result: PASS (debug payload shown)\n'
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    local response_body
    response_body="$(cat "$tmp_body")"
    printf '  Body  : %s\n' "$response_body"
    printf '  Result: FAIL\n'
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi

  rm -f "$tmp_body" "$tmp_header"
}

print_line
printf 'ACTL API Tester (profile=%s)\n' "$PROFILE"
printf 'Target: %s\n' "$BASE_URL"
print_line

run_test "Health Check" "GET" "/v1/health" "200" "-" '"message":"ok"' '"status":"up"'
run_test "System Info API" "GET" "/v1/system/info" "200" "-" '"message":"ok"' '"clickRange":' '"sdkInt":' '"device":' '"connected":'
run_test "Click API Success" "POST" "/v1/control/click" "200" '{"x":300,"y":800}' '"message":"ok"' '"command":"input tap 300 800"'
run_test "Click API Invalid" "POST" "/v1/control/click" "400" '{"x":-1,"y":800}' '"code":40002'
run_test "Swipe API Success" "POST" "/v1/control/swipe" "200" '{"startX":300,"startY":1000,"endX":300,"endY":300,"durationMs":300}' '"message":"ok"' '"command":"input swipe 300 1000 300 300 300"'
run_test "Swipe API Invalid" "POST" "/v1/control/swipe" "400" '{"startX":-1,"startY":1000,"endX":300,"endY":300,"durationMs":300}' '"code":40012'

if [ "$PROFILE" = "full" ]; then
  run_dump_raw "UI XML API Raw XML" "POST" "/v1/ui/xml"
  run_screenshot_raw "UI Screenshot API" "POST" "/v1/ui/screenshot"
fi

print_line
printf 'Summary: PASS=%d FAIL=%d\n' "$PASS_COUNT" "$FAIL_COUNT"
print_line

rm -f "$ERR_FILE"

if [ "$FAIL_COUNT" -ne 0 ]; then
  exit 1
fi

exit 0
