#!/usr/bin/env bash
# springdoc(/v3/api-docs · /swagger-ui) 외부 미노출 봉인 — 3축(대조군·라우팅·포트)
#
# 배경. 백엔드는 springdoc 경로를 WebSecurityCustomizer.ignoring 으로 Spring Security
#       필터체인 **밖**에 둔다. 즉 애플리케이션 레벨에서는 인증이 없다. 등록 지점은 두 곳이다.
#         · backend/modules/issue-tracking/.../config/OpenApiSecurityConfig.kt
#         · backend/modules/search-export-import/.../config/OpenApiConfig.kt
#       (한 곳만 고치면 다른 빈이 그대로 ignoring 을 등록하므로 효과 0)
#
# 그런데 왜 지금 안전한가. **애플리케이션이 아니라 배포 토폴로지가 막고 있다.**
#         · infra/prod/nginx.conf — 백엔드로 프록시하는 location 정규식이 springdoc 경로를 포함하지 않는다.
#           매치되는 location 이 없으면 `location /` SPA fallback 으로 떨어져 index.html 이 나간다.
#         · infra/docker-compose.prod.yml — bts-backend 에 `ports:` 가 없다. 호스트 공개 진입점은
#           bts-web 의 18080 하나뿐이다(minio 콘솔은 127.0.0.1 루프백 한정).
#
# ⇒ 안전성이 **우연한 성질**에 얹혀 있다. 누가 nginx 프록시 정규식에 `v3|swagger` 를 한 단어 넣거나
#   bts-backend 에 `ports:` 를 열면 그 순간 미인증 OpenAPI 스펙이 인터넷에 노출된다. 어떤 Kotlin
#   테스트도 이것을 잡지 못한다 — MockMvc 는 nginx 를 모르고 Gradle 은 compose 파일을 읽지 않는다.
#   이 스크립트가 그 문을 지킨다.
#
# 3축. 하나라도 빠지면 봉인이 봉인 대상을 놓친다.
#   축 C(대조군)  — 알려진 프록시 경로는 실제로 매치된다. **가장 먼저 본다.**
#                    없으면. 정규식 추출이 실패해 빈 목록이 되면 축 A 가 **공허하게** 통과한다
#                    (메모리 archunit-vacuous-rule-silent-pass 와 같은 실패 양식)
#   축 A(라우팅)  — nginx 의 어떤 백엔드 location 도 springdoc 경로를 매치하지 않는다
#                    없으면. 정규식에 v3|swagger 가 추가돼도 아무도 모른다
#   축 B(포트)    — bts-backend 서비스에 호스트 발행 포트가 0개다
#                    없으면. nginx 를 우회해 8080 이 직접 열려도 축 A 는 그대로 통과한다
#
# ★ macOS 기본 bash 는 3.2 라 mapfile(bash 4+)이 없다. 배열 대신 개행 구분 문자열을 쓴다 —
#   CI(우분투 bash 5)만 보고 짜면 로컬에서 조용히 빈 목록이 되고 봉인이 공허해진다
#   (같은 이유로 scripts/verify/nginx-log-masking.sh 도 mapfile 을 쓰지 않는다).
#
# 종료 코드. 0 = 통과 / 1 = 축 A 위반(springdoc 이 프록시된다) / 2 = 축 B 위반(백엔드 포트 발행)
#            3 = 축 C 위반(판별식이 공허 — 대조군 미매치) / 7 = 전제조건 부재(파일 없음)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NGINX_CONF="${NGINX_CONF_OVERRIDE:-$REPO_ROOT/infra/prod/nginx.conf}"
COMPOSE_PROD="${COMPOSE_PROD_OVERRIDE:-$REPO_ROOT/infra/docker-compose.prod.yml}"

fail() { echo "❌ FAIL($2) $1" >&2; exit "$2"; }
ok()   { echo "✅ $1"; }

[ -f "$NGINX_CONF" ]   || fail "nginx.conf 를 찾을 수 없다. $NGINX_CONF" 7
[ -f "$COMPOSE_PROD" ] || fail "docker-compose.prod.yml 을 찾을 수 없다. $COMPOSE_PROD" 7

# springdoc 이 실제로 쓰는 경로. 백엔드 두 등록 지점의 requestMatchers 와 같은 집합이어야 한다.
SPRINGDOC_PATHS='/v3/api-docs
/v3/api-docs/swagger-config
/v3/api-docs.yaml
/swagger-ui/index.html
/swagger-ui.html'

# 백엔드로 프록시되는 것이 확실한 경로 (축 C 대조군). 하나라도 미매치면 판별식이 고장난 것이다.
PROXIED_PATHS='/api/v1/issues
/.well-known/openid-configuration
/ical/feed/abc.ics
/slack/events
/oauth2/authorization/keycloak
/saml2/authenticate/x
/login/oauth2/code/keycloak'

# ── location 추출 (개행 구분 문자열, bash 3.2 호환) ───────────────────────────
# `location ~ <regex>` / `location ~* <regex>` 두 형태.
REGEX_LOCATIONS="$(
  grep -oE '^[[:space:]]*location[[:space:]]+~\*?[[:space:]]+[^ ]+' "$NGINX_CONF" \
    | sed -E 's/^[[:space:]]*location[[:space:]]+~\*?[[:space:]]+//' || true
)"

# 프리픽스 location 중 루트(`/`)가 아닌 것 — 이것들도 springdoc 경로를 삼킬 수 있다.
PREFIX_LOCATIONS="$(
  grep -oE '^[[:space:]]*location[[:space:]]+/[^ {]*' "$NGINX_CONF" \
    | sed -E 's/^[[:space:]]*location[[:space:]]+//' \
    | grep -vE '^/$' || true
)"

REGEX_N=$(printf '%s' "$REGEX_LOCATIONS" | grep -c . || true)
PREFIX_N=$(printf '%s' "$PREFIX_LOCATIONS" | grep -c . || true)
echo "→ 정규식 location  . ${REGEX_N}개"
printf '%s\n' "$REGEX_LOCATIONS" | sed 's/^/    /' | grep -v '^ *$' || true
echo "→ 프리픽스 location. ${PREFIX_N}개"
printf '%s\n' "$PREFIX_LOCATIONS" | sed 's/^/    /' | grep -v '^ *$' || true
echo

# 경로가 어떤 백엔드 location 에라도 걸리는가. 0 = 걸림, 1 = 안 걸림.
matches_backend() {
  path="$1"
  while IFS= read -r re; do
    [ -n "$re" ] || continue
    # nginx 는 PCRE, grep -E 는 POSIX ERE. 이 저장소가 쓰는 패턴 범위에서는 동등하다.
    if printf '%s' "$path" | grep -qE "$re"; then return 0; fi
  done <<EOF
$REGEX_LOCATIONS
EOF
  while IFS= read -r pre; do
    [ -n "$pre" ] || continue
    case "$path" in "$pre"*) return 0 ;; esac
  done <<EOF
$PREFIX_LOCATIONS
EOF
  return 1
}

# ── 축 C — 대조군 먼저. 판별식이 살아 있는지 확인한 뒤에야 축 A 의 "미매치"가 의미를 갖는다 ──
CTRL_N=0
while IFS= read -r p; do
  [ -n "$p" ] || continue
  CTRL_N=$((CTRL_N + 1))
  matches_backend "$p" \
    || fail "축 C — 대조군 '$p' 가 어떤 백엔드 location 에도 매치되지 않는다. 정규식 추출이 고장났거나 nginx.conf 라우팅이 바뀌었다. 이 상태에서는 축 A 의 통과가 공허하다." 3
done <<EOF
$PROXIED_PATHS
EOF
ok "축 C(대조군) — 알려진 프록시 경로 ${CTRL_N}개가 모두 매치된다. 판별식이 살아 있다."

# ── 축 A — springdoc 경로는 백엔드로 가면 안 된다 ─────────────────────────────
SD_N=0
while IFS= read -r p; do
  [ -n "$p" ] || continue
  SD_N=$((SD_N + 1))
  if matches_backend "$p"; then
    fail "축 A — springdoc 경로 '$p' 가 백엔드로 프록시된다. 백엔드는 이 경로를 WebSecurityCustomizer.ignoring 으로 필터체인 밖에 두므로(issue-tracking OpenApiSecurityConfig.kt + search-export-import OpenApiConfig.kt) 그대로 **미인증 공개**가 된다. nginx 라우팅을 되돌리거나, 두 빈 모두에 @Profile(\"!prod\") 를 붙여라 — 한 곳만 고치면 효과 0." 1
  fi
done <<EOF
$SPRINGDOC_PATHS
EOF
ok "축 A(라우팅) — springdoc 경로 ${SD_N}개가 백엔드로 프록시되지 않는다 (SPA fallback 으로 수렴)."

# ── 축 B — 백엔드 컨테이너가 호스트로 포트를 발행하면 nginx 를 우회한다 ──────
BACKEND_PORTS="$(
  awk '
    /^  [a-zA-Z][a-zA-Z0-9_-]*:[[:space:]]*$/ { svc=$1; sub(/:$/,"",svc); inports=0; next }
    svc=="bts-backend" && $1=="ports:" { inports=1; next }
    svc=="bts-backend" && inports && /^[[:space:]]+-[[:space:]]/ { print; next }
    svc=="bts-backend" && inports && /^[[:space:]]+[a-zA-Z_]+:/ { inports=0 }
  ' "$COMPOSE_PROD" | grep -vE '^[[:space:]]*#' || true
)"

if [ -n "$(printf '%s' "$BACKEND_PORTS" | tr -d '[:space:]')" ]; then
  fail "축 B — bts-backend 가 호스트로 포트를 발행한다. nginx 라우팅을 우회해 springdoc 이 직접 노출된다.
$BACKEND_PORTS
호스트 공개 진입점은 bts-web 하나여야 한다." 2
fi
ok "축 B(포트) — bts-backend 에 호스트 발행 포트가 0개다. 외부 도달 경로는 bts-web 뿐이다."

echo
echo "PASS. springdoc 외부 미노출 봉인 3축 통과."
echo "      ⚠️ 이 봉인은 '배포 토폴로지가 막는다'는 사실을 고정할 뿐,"
echo "         애플리케이션 레벨 인증을 대신하지 않는다. 프론트를 별 도메인으로 분리하거나"
echo "         백엔드를 직접 노출하는 순간 두 WebSecurityCustomizer 를 함께 손봐야 한다."
