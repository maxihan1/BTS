#!/usr/bin/env bash
# self-hosted 러너의 실행 엔진(node·java)이 살아 있는지를 「실행」으로 확인한다
#
# 왜 존재 확인이 아니라 실행인가. 2026-08-04 사고에서 세 경우 모두 `test -f` 는 통과했다 —
# externals 는 corepack·npm·npx 심볼릭이 남고 node 만 없었고, 툴캐시는 arm64.complete 표식만
# 남아 setup-* 가 캐시 히트로 오판했으며, JDK 는 bin 의 실행파일 30개가 전부 있는데
# lib/modules 만 없어 java -version 만 실패했다.
#
# ## ★ 글로브는 「바이너리」가 아니라 「버전 디렉터리」에 건다
#
# `_tool/node/*/*/bin/node` 로 훑으면 **바이너리가 지워진 바로 그 순간 글로브가 0건이 되어
# 루프가 안 돌고 조용히 통과**한다 — 잡으려던 결함이 정확히 가드를 무력화한다.
# 그래서 아직 남아 있는 버전 디렉터리를 훑고 그 **안**의 바이너리를 검사한다.
#
# 루트는 BTS_RUNNER_ROOT 로 주입할 수 있다. 실제 러너를 건드리지 않고 결함 상태를 재현하는
# 유일한 이음매이고, scripts/workflow/verify-runner-health.test.ts 가 그 이음매로 검증한다.
#
# 정본 문서. docs/runbooks/self-hosted-runner.md §4
set -uo pipefail
shopt -s nullglob

ROOT="${BTS_RUNNER_ROOT:-$HOME/actions-runner-bts}"
FAILED=0

report() {
  echo "❌ $1"
  echo "   복구. $2"
  FAILED=1
}

# check_exec <라벨> <바이너리 경로> <버전 플래그> <복구 안내>
check_exec() {
  local label="$1" bin="$2" flag="$3" fix="$4"

  if [ ! -e "$bin" ]; then
    report "$label — 바이너리 부재 ($bin)" "$fix"
    return
  fi
  if ! "$bin" "$flag" >/dev/null 2>&1; then
    report "$label — 파일은 있으나 실행 실패 ($bin)" "$fix"
    return
  fi
  echo "✅ $label"
}

FIX_EXTERNALS="nodejs.org 공식 배포본을 SHASUMS256.txt 로 sha256 대조 후 bin/node 만 복원"
FIX_TOOLCACHE="그 버전 디렉터리의 arm64.complete 표식 삭제 → setup-* 가 자가 재설치"

# 1) 러너 내장 엔진 — 러너가 JavaScript 액션(actions/checkout 등)을 돌리는 데 쓴다.
#    여기가 죽으면 잡이 Checkout 스텝에서 죽고 테스트는 한 줄도 안 돈다.
for v in node20 node24; do
  check_exec "externals/$v" "$ROOT/externals/$v/bin/node" -v "$FIX_EXTERNALS"
done

# 2) 툴 캐시 — setup-node / setup-java 가 심는다.
#    표식만 남으면 캐시 히트로 오판해 시스템 엔진으로 조용히 흘러내린다.
TOOLCACHE_SEEN=0

for dir in "$ROOT"/_work/_tool/node/*/*/; do
  TOOLCACHE_SEEN=1
  check_exec "toolcache node ($(basename "$(dirname "$dir")"))" "${dir}bin/node" -v "$FIX_TOOLCACHE"
done

for dir in "$ROOT"/_work/_tool/Java_*/*/*/; do
  TOOLCACHE_SEEN=1
  check_exec "toolcache java ($(basename "$(dirname "$dir")"))" \
    "${dir}Contents/Home/bin/java" -version "$FIX_TOOLCACHE"
done

# 툴캐시가 통째로 비어 있는 것은 「갓 설치한 러너」의 정상 상태이기도 하다(setup-* 가 캐시
# 미스로 알아서 받는다). 그래서 실패가 아니라 경고로 남긴다 — 여기서 실패시키면 정상 상태에
# 빨간불이 뜨고, 그 빨간불이 무시되기 시작하면 진짜 결함도 같이 묻힌다.
if [ "$TOOLCACHE_SEEN" -eq 0 ]; then
  echo "⚠️  툴 캐시가 비어 있다 ($ROOT/_work/_tool) — 갓 설치한 러너면 정상이다."
fi

if [ "$FAILED" -ne 0 ]; then
  cat <<'MSG'

──────────────────────────────────────────────
러너 환경 결함 — 코드 문제 아님
이 러너에서 도는 CI 는 테스트를 한 줄도 실행하지 못한다.
자세한 내용. docs/runbooks/self-hosted-runner.md §4
──────────────────────────────────────────────
MSG
  exit 1
fi

echo "러너 엔진 정상."
