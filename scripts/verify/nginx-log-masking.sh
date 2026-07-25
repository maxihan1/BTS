#!/usr/bin/env bash
# nginx 접속로그 경로토큰 마스킹 봉인 — 3축(길이·알파벳·설정실효) + 문법·실효 검증
#
# 배경. infra/prod/nginx.conf 는 nginx 이미지 기본 `access_log … main` 을 상속했고,
#       log_format main 의 $request 가 요청 첫 줄 통째(쿼리 포함)를 담아 경로의 비밀 토큰이
#       매 요청 평문으로 표준출력에 적재됐다. 처방은 커스텀 log_format + map 구조적 마스킹.
#
# 이 스크립트가 지키는 불변식 3축. 하나라도 빠지면 봉인이 봉인 대상을 놓친다.
#   축 A(길이)   — 모든 불투명 토큰 발급 길이 > nginx 판별식 임계값
#                   없으면. 누가 토큰을 짧게 줄이면 조용히 샌다
#   축 B(알파벳) — 모든 토큰 인코딩 문자군 ⊆ 판별식 문자군 [A-Za-z0-9_-]
#                   없으면. 표준 base64(+·/)·JWT(.) 토큰은 구분자가 세그먼트를 쪼개
#                   40자 미만 조각으로 만들어 마스킹을 빠져나가는데 길이 검사는 통과한다
#   축 C(설정)   — nginx.conf 가 실제로 마스킹한다
#                   없으면. map 블록을 통째로 지워도 축 A·B 는 그대로 통과한다
#
# ★임계값은 하드코딩하지 않고 nginx.conf 에서 파싱한다(plan-eng-review 발견 2).
#   두 곳에 하드코딩하면 드리프트 시 봉인이 존재하지 않는 문을 점검하게 된다.
#
# 종료 코드. 0 = 통과 / 1 = 축 A 위반 / 2 = 축 B 위반 / 3 = 축 C 위반
#            4 = 파생 실패(하한 미달·미분류) / 5 = 문법 검증 실패 / 6 = 실효 검증 실패
#            7 = 전제조건 부재(docker 등)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NGINX_CONF="$REPO_ROOT/infra/prod/nginx.conf"
NGINX_IMAGE="nginx:1.27-alpine"

# 발급기 파생 하한. 현재 실측 9개(base64url 3 · hex 6). 파생기가 고장나 0건이 되면
# 모든 단언이 vacuous 하게 통과하므로 하한을 둔다. 발급기를 늘리는 건 통과, 줄면 실패.
MINTER_FLOOR=9

# 판별식이 허용하는 문자군. 이 집합 밖 문자를 쓰는 인코딩은 세그먼트가 쪼개져 마스킹을 빠져나간다.
SAFE_ALPHABET_DESC='[A-Za-z0-9_-]'

fail() { echo "❌ FAIL($2) $1" >&2; exit "$2"; }
ok()   { echo "✅ $1"; }

[ -f "$NGINX_CONF" ] || fail "nginx.conf 를 찾을 수 없다. $NGINX_CONF" 7

# ─────────────────────────────────────────────────────────────────────────────
# 0. 임계값 파싱 — nginx.conf 가 진실출처. 하드코딩 금지
# ─────────────────────────────────────────────────────────────────────────────
# 판별식 정규식에서 {N,} 의 N 을 뽑는다. 경로 map 과 Referer map 두 곳에 나오며,
# 둘이 다르면 한쪽만 마스킹되므로 동일성까지 확인한다(리뷰 발견 3).
# macOS 기본 bash 는 3.2 라 mapfile(bash 4+)이 없다. 배열 대신 개행 구분 문자열을 쓴다 —
# CI(우분투 bash 5)만 보고 짜면 로컬에서 깨진다.
THRESHOLDS="$(grep -oE '\[A-Za-z0-9_-\]\{[0-9]+,\}' "$NGINX_CONF" | grep -oE '[0-9]+')"
THRESHOLD_COUNT=$(printf '%s' "$THRESHOLDS" | grep -c . || true)

if [ "$THRESHOLD_COUNT" -eq 0 ]; then
    fail "nginx.conf 에서 마스킹 판별식을 찾을 수 없다 — 마스킹이 제거됐거나 도입되지 않았다 (축 C)" 3
fi
if [ "$THRESHOLD_COUNT" -lt 2 ]; then
    fail "판별식이 ${THRESHOLD_COUNT}곳에만 있다 — 경로·Referer 양쪽에 필요하다 (축 C)" 3
fi
THRESHOLD="$(printf '%s' "$THRESHOLDS" | head -1)"
while IFS= read -r t; do
    [ -z "$t" ] && continue
    [ "$t" = "$THRESHOLD" ] || fail "판별식 임계값이 어긋난다($(printf '%s' "$THRESHOLDS" | tr '\n' ' ')) — 한쪽만 마스킹된다 (축 C)" 3
done <<EOF
$THRESHOLDS
EOF
ok "임계값 파싱 — ${THRESHOLD}자 (판별식 ${THRESHOLD_COUNT}곳 전부 일치)"

# ─────────────────────────────────────────────────────────────────────────────
# 1. 발급기 파생 + 축 A(길이) · 축 B(알파벳)
# ─────────────────────────────────────────────────────────────────────────────
# 하드코딩 목록이 아니라 코드에서 파생한다. 목록은 그 자체로 눈가리개다 —
# 이 작업 중에도 SPA 라우트를 실제로 놓쳤다.
MINTERS="$(grep -rl "TOKEN_BYTES" "$REPO_ROOT"/backend/modules/*/src/main --include="*.kt" 2>/dev/null | sort)"
MINTER_COUNT=$(printf '%s' "$MINTERS" | grep -c . || true)

if [ "$MINTER_COUNT" -lt "$MINTER_FLOOR" ]; then
    fail "토큰 발급기 파생 ${MINTER_COUNT}건 < 하한 ${MINTER_FLOOR}건 — 파생기가 고장났거나 발급기가 사라졌다" 4
fi

while IFS= read -r f; do
    [ -z "$f" ] && continue
    name="$(basename "$f")"

    bytes="$(grep -oE 'TOKEN_BYTES[^=]*= *[0-9]+' "$f" | grep -oE '[0-9]+$' | head -1)"
    [ -n "$bytes" ] || fail "$name — TOKEN_BYTES 값을 판정할 수 없다 (미분류)" 4

    # 난수 바이트를 소비하는 지점만 좁혀서 본다. 파일 전체를 보면 SHA-256 해시의
    # %02x 가 원문 인코딩으로 오인된다(실제로 1차 탐지에서 9건 전부 오분류됐다).
    window="$(grep -A4 'ByteArray(TOKEN_BYTES)' "$f" 2>/dev/null)"
    [ -n "$window" ] || fail "$name — 난수 소비 지점을 찾을 수 없다 (미분류)" 4

    if grep -q 'getUrlEncoder' <<<"$window"; then
        enc="base64url"; len=$(( (bytes + 2) / 3 * 4 ))
        grep -q 'withoutPadding' <<<"$window" && len=$(( (bytes * 8 + 5) / 6 ))
    elif grep -qE 'toHex\(|%02x' <<<"$window"; then
        enc="hex"; len=$(( bytes * 2 ))
    elif grep -qE 'getEncoder\(\)' <<<"$window"; then
        # 표준 base64 는 + 와 / 를 쓴다. / 는 경로 구분자라 세그먼트가 쪼개지고,
        # 길이 검사는 통과하는데 마스킹은 빠져나간다 — 가장 조용한 실패 경로.
        fail "$name — 표준 base64(+·/) 는 판별 문자군 $SAFE_ALPHABET_DESC 밖이다. base64url 을 쓸 것 (축 B)" 2
    else
        fail "$name — 토큰 인코딩을 판정할 수 없다 (미분류는 실패로 처리한다)" 4
    fi

    if [ "$len" -le "$THRESHOLD" ]; then
        fail "$name — 토큰 ${len}자 ≤ 임계값 ${THRESHOLD}자. nginx 판별식이 이 토큰을 못 가린다 (축 A)" 1
    fi
    printf '   · %-42s %s %s바이트 → %s자 > %s ✓\n' "$name" "$enc" "$bytes" "$len" "$THRESHOLD"
done <<EOF
$MINTERS
EOF
ok "축 A(길이)·축 B(알파벳) — 발급기 ${MINTER_COUNT}건 전부 통과 (하한 ${MINTER_FLOOR})"

# 다중 비밀값 라우트 부재. nginx map 은 전역 치환을 못 해 한 경로에 토큰이 2개면
# 하나만 가려진다. 그런 라우트가 생기면 설계를 다시 봐야 하므로 여기서 막는다.
MULTI="$(grep -rhoE '"/[^"]*\{token\}[^"]*\{[a-zA-Z]*[Tt]oken\}[^"]*"' "$REPO_ROOT"/backend/modules/*/src/main --include="*.kt" 2>/dev/null | head -3)"
[ -z "$MULTI" ] || fail "한 경로에 비밀값 2개 이상. map 은 전역 치환을 못 한다 — 설계 재검토 필요.\n$MULTI" 4
ok "다중 비밀값 라우트 부재"

# ─────────────────────────────────────────────────────────────────────────────
# 2. 축 C(설정 실효) — nginx.conf 가 실제로 마스킹하는가
# ─────────────────────────────────────────────────────────────────────────────
grep -qE '^\s*log_format\s+bts_masked' "$NGINX_CONF" \
    || fail "log_format bts_masked 미정의 — 이미지 기본 main 포맷을 상속한다 (축 C)" 3
grep -qE '^\s*access_log\s+\S+\s+bts_masked' "$NGINX_CONF" \
    || fail "access_log 가 bts_masked 를 지정하지 않는다 — 이미지 기본 상속 (축 C)" 3

# 마스킹을 우회하는 변수들. 하나라도 로그 포맷에 있으면 원문이 그대로 나간다.
# $request = 요청 첫 줄 통째, $request_uri = 쿼리 포함 원본, $args = 쿼리 전체.
FORMAT_BLOCK="$(sed -n '/log_format[[:space:]]\+bts_masked/,/;[[:space:]]*$/p' "$NGINX_CONF")"
for banned in '\$request[^_a-z]' '\$request_uri' '\$args'; do
    if grep -qE "$banned" <<<"$FORMAT_BLOCK"; then
        fail "log_format 에 마스킹 우회 변수가 있다(${banned}) — 원문이 그대로 로깅된다 (축 C)" 3
    fi
done
grep -q '\$bts_masked_uri' <<<"$FORMAT_BLOCK" \
    || fail "log_format 이 \$bts_masked_uri 를 쓰지 않는다 — 경로가 안 가려진다 (축 C)" 3
grep -q '\$bts_masked_referer' <<<"$FORMAT_BLOCK" \
    || fail "log_format 이 \$bts_masked_referer 를 쓰지 않는다 — Referer 로 토큰이 샌다 (축 C)" 3
ok "축 C(설정 실효) — log_format·access_log·마스킹 변수·금지 변수 전부 확인"

# ─────────────────────────────────────────────────────────────────────────────
# 3. RQ-6 문법 검증 + RQ-7 실효 검증 (실제 nginx)
# ─────────────────────────────────────────────────────────────────────────────
# docker 부재를 SKIP 으로 넘기지 않는다 — 조용한 스킵은 vacuous 통과 경로다.
command -v docker >/dev/null 2>&1 || fail "docker 가 필요하다(문법·실효 검증). 조용히 건너뛰지 않는다" 7

docker run --rm -v "$NGINX_CONF:/etc/nginx/conf.d/bts.conf:ro" "$NGINX_IMAGE" nginx -t >/dev/null 2>&1 \
    || fail "nginx 문법 검증 실패 — 이 설정으로 배포하면 프론트 전체가 뜨지 않는다 (RQ-6)" 5
ok "RQ-6 문법 검증 (nginx -t)"

# 실측 표본. 실제 발급 형식 그대로 — base64url 43자 · hex 64자.
T_B64="Xy7Kd9QwErTyUiOpAsDfGhJkLzXcVbNm123-_QZaBcD"
T_HEX="a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
T_QRY="SUPERSECRETCODEVALUE"
T_UUID="3f2504e0-4f89-11d3-9a0c-0305e82c3301"

LOGS="$(docker run --rm -v "$NGINX_CONF:/etc/nginx/conf.d/bts.conf:ro" "$NGINX_IMAGE" sh -c "
rm -f /etc/nginx/conf.d/default.conf
nginx 2>/dev/null
i=0; while [ \$i -lt 50 ]; do wget -q -O /dev/null http://127.0.0.1/ 2>/dev/null && break; i=\$((i+1)); done
wget -q -O /dev/null 'http://127.0.0.1/api/v1/public/dashboards/$T_B64'
wget -q -O /dev/null 'http://127.0.0.1/dashboards/shared/$T_B64'
wget -q -O /dev/null 'http://127.0.0.1/ical/feed/$T_HEX.ics'
wget -q -O /dev/null 'http://127.0.0.1/api/v1/webhooks/git/$T_B64'
wget -q -O /dev/null 'http://127.0.0.1/api/v1/automation/webhooks/$T_B64'
wget -q -O /dev/null 'http://127.0.0.1/slack/install/callback?code=$T_QRY&state=x'
wget -q -O /dev/null --header='Referer: https://bts.example.com/dashboards/shared/$T_B64' 'http://127.0.0.1/api/v1/x'
wget -q -O /dev/null 'http://127.0.0.1/api/v1/issues/$T_UUID'
nginx -s quit 2>/dev/null
" 2>&1)"

# 판별자 = 로그 전문에 원문 토큰 문자열이 존재하지 않을 것.
# 상태코드나 *** 존재는 판별자가 못 된다("여전히 401" 류의 vacuous 단언 금지).
for secret in "$T_B64" "$T_HEX" "$T_QRY"; do
    if grep -qF "$secret" <<<"$LOGS"; then
        printf '%s\n' "$LOGS" >&2
        fail "로그에 원문 비밀값이 남았다: ${secret:0:16}… (RQ-7)" 6
    fi
done
ok "RQ-7 실효 — 5 라우트 × 2 인코딩 + 쿼리 + Referer, 원문 0회"

# 역방향 단언. 마스킹이 관측성까지 죽이지 않았는지 본다.
# 이게 없으면 "전부 가려서 아무것도 안 남았다" 도 통과해 버린다.
grep -qF "$T_UUID" <<<"$LOGS" \
    || { printf '%s\n' "$LOGS" >&2; fail "UUID(36자)까지 마스킹됐다 — 과잉마스킹으로 디버깅 상관성을 잃는다 (RQ-3)" 6; }
grep -q 'rt=' <<<"$LOGS" \
    || { printf '%s\n' "$LOGS" >&2; fail "응답시간(rt=)이 로그에 없다 — 관측성 손실 (RQ-3)" 6; }
grep -q '"GET ' <<<"$LOGS" \
    || { printf '%s\n' "$LOGS" >&2; fail "요청 메서드가 로그에 없다 — 관측성 손실 (RQ-3)" 6; }
ok "RQ-3 역방향 — UUID 보존 · 메서드 · 응답시간 전부 잔존"

echo
echo "🔒 봉인 통과 — 3축 + 문법 + 실효 + 역방향"
