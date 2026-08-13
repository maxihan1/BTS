#!/usr/bin/env bash
# 문서 인덱스 판별식 비-공허 확인 — 위반 5종을 주입해 red 를 보고 원복한다
#
# 왜 필요한가. 위반을 넣어보지 않은 판별식은 조용히 통과하는 장식일 수 있다.
# 2026-08-01 1차 주입에서 6종 중 5종이 green 이었다 —
#   · 룰 I 이 쓰기 모드로 재생성해 검사 대상을 덮어씀 (그 부작용이 룰 J-삭제·K 까지 연쇄 무력화)
#   · 룰 L 이 'docs/**' 를 무조건 OR 로 붙여 무관 경로도 커버 판정
# 판별식을 고칠 때마다 이 스크립트를 재실행한다.
#
# 전제. 커밋된 클린 상태 (dirty 에서 주입하면 원복이 불완전해진다).
# 서명. 「원복 후 클린인데 green」 — 원복 후에도 red 면 하네스가 고장난 것이다.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1

TEST="scripts/workflow/doc-index-coverage.test.ts"

run() {
  # ★`--experimental-strip-types` 없이는 Node 22.14 에서 `.ts` 가 ERR_UNKNOWN_FILE_EXTENSION 으로
  #   죽는다(2026-08-13 실측). 그러면 주입하든 안 하든 **항상 fail 1** 이라, 이 하네스가 재는 것이
  #   판별식이 아니라 자기 고장이 된다 — 원복 후에도 red 라서 「green 이어야 한다」는 서명이
  #   영영 성립하지 않는다. `pnpm test:workflow` 는 이 플래그를 이미 쓴다.
  node --experimental-strip-types --test "$TEST" 2>&1 | grep -E '^ℹ (pass|fail)' | tr '\n' ' '
  echo ""
}

restore() {
  git checkout -- docs/INDEX-recent.md docs/INDEX-fr.md docs/INDEX.md 2>/dev/null
  git checkout -- scripts/doc-index/config.mjs 2>/dev/null
  rm -f docs/plans/2099-12-31-mutation-probe.md
}

echo "###  전제 확인 — git status 가 비어야 한다"
if [ -n "$(git status --short)" ]; then
  echo "ABORT. dirty 상태다. 커밋 후 다시 실행할 것."
  git status --short
  exit 1
fi
echo "클린 ✓"
echo ""

echo "### 기준선 (green 이어야)"
run
echo ""

echo "### [I] 인덱스를 손으로 한 줄 고친다"
echo "| 2099-12-31 | 가짜항목 | ✔ | — | — | — | — |" >> docs/INDEX-recent.md
echo -n "  주입:   "; run
restore
echo -n "  원복:   "; run
echo ""

echo "### [J-추가] 인덱스에 없는 문서를 새로 만든다"
printf '# 판별식 주입 테스트\n' > docs/plans/2099-12-31-mutation-probe.md
echo -n "  주입:   "; run
restore
echo -n "  원복:   "; run
echo ""

echo "### [J-삭제] 인덱스에서 데이터 행 하나를 지운다"
echo "  지울 행(9): $(sed -n '9p' docs/INDEX-recent.md | cut -c1-60)"
sed -i '' '9d' docs/INDEX-recent.md
echo -n "  주입:   "; run
restore
echo -n "  원복:   "; run
echo ""

echo "### [K] 없는 파일을 가리키는 링크를 넣는다"
echo "| FR-CO-01 | [2099-12-31](/docs/specs/2099-12-31-ghost.md) | — | — | — |" >> docs/INDEX-fr.md
echo -n "  주입:   "; run
restore
echo -n "  원복:   "; run
echo ""

echo "### [L] SOURCES 에 CI 가 모르는 경로를 추가한다"
# docs/** 가 이미 트리거에 있으므로 docs 밖 경로를 써야 red 가 뜬다
node -e "
const fs=require('fs'); const p='scripts/doc-index/config.mjs';
let s=fs.readFileSync(p,'utf8');
s=s.replace(\"  { key: 'adr',\", \"  { key: 'probe', dir: 'other/probe', repoRelative: true },\n  { key: 'adr',\");
fs.writeFileSync(p,s);"
grep -q "other/probe" scripts/doc-index/config.mjs && echo "  주입 확인: SOURCES 에 other/probe 추가됨"
echo -n "  주입:   "; run
restore
echo -n "  원복:   "; run
echo ""

# ── [★정원] ★(critical) 정원 상한·하한 ────────────────────────────────────────
#
# 위 5종과 두 가지가 다르다.
#  1. **검사 주체가 다르다.** ★ 정원은 판별식 테스트가 아니라 생성기 자가진단
#     (`build-doc-index.mjs`)이 본다. `doc-index-coverage.test.ts` 는 이 판정을 안 읽으므로
#     위 `run()` 으로는 주입해도 아무 변화가 없다 — 전용 러너가 필요하다.
#  2. **대상이 git 밖이다.** 메모리는 `~/.claude/projects/…/memory/` 에 있어
#     `git checkout --` 가 안 듣는다. 원복 수단은 cp 백업의 역방향 복사뿐이고, 중간에 죽으면
#     사람이 손으로 되돌려야 한다. 그래서 백업 경로를 먼저 찍고, 끝에 건수를 다시 세어 확인한다.
#
# ★종료 코드만 보면 안 된다. ★ 를 전부 강등하면 라우터 본문이 함께 바뀌어 **drift 도** exit 1 을
#   만든다. 원인이 둘로 겹치므로 신호는 종료 코드가 아니라 **메시지 줄**이다.

# 메모리 경로는 생성기 상수에서 받는다. 여기 다시 적으면 그것이 세 번째 목록이 된다.
MEMORY_DIR="$(node -e "import('./scripts/doc-index/config.mjs').then((m) => console.log(m.MEMORY_DIR))")"
STAR_BACKUP="${TMPDIR:-/tmp}/bts-star-quota-$$"
# 상한 값도 정본에서 읽는다. 숫자를 여기 적으면 상수를 고칠 때 이 파일이 조용히 낡는다.
STAR_MAX="$(grep -oE 'STAR_QUOTA = \{[^}]*max:[[:space:]]*[0-9]+' scripts/build-doc-index.mjs | grep -oE '[0-9]+$')"

star_count() {
  grep -lE '^[[:space:]]*priority:[[:space:]]*critical' "$MEMORY_DIR"/*.md 2>/dev/null | wc -l | tr -d ' '
}

# ★ 정원 메시지 줄만 뽑는다. 종료 코드는 참고로 함께 찍되 단독 신호로 쓰지 않는다.
quota_run() {
  local out rc
  out="$(node scripts/build-doc-index.mjs --check 2>&1)"
  rc=$?
  printf '%s\n' "$out" | grep -E '★\(critical\)' | sed 's/^/      /'
  echo "      → exit ${rc}"
}

echo "### [★정원] 준비 — 대상이 git 밖이라 백업이 유일한 원복 수단이다"
if [ -z "$MEMORY_DIR" ] || [ ! -d "$MEMORY_DIR" ]; then
  echo "  SKIP. 메모리 디렉터리를 못 찾았다: '${MEMORY_DIR}'"
else
  STAR_BASE="$(star_count)"
  echo "  메모리: $MEMORY_DIR"
  echo "  백업:   $STAR_BACKUP"
  echo "  상한:   ${STAR_MAX:-'(못 읽음)'} · 기준선 ★ ${STAR_BASE}건"
  mkdir -p "$STAR_BACKUP"
  cp "$MEMORY_DIR"/*.md "$STAR_BACKUP"/
  echo ""

  echo "### [★정원-하한] ★ 를 전부 강등하면 red 여야 한다"
  # critical 이 0 이면 위쪽 '라우터 렌더' 차집합 가드는 **빈 집합끼리** 비교하며 영구 통과한다.
  # 이 주입이 곧 그 시나리오다 — 하한이 없으면 여기서 green 이 나오고, 그게 이 상수의 존재 이유다.
  sed -i '' -E 's/^([[:space:]]*)priority:[[:space:]]*critical/\1priority: normal/' "$MEMORY_DIR"/*.md
  echo "  주입 확인: ★ $(star_count)건"
  echo "  주입:"; quota_run
  cp "$STAR_BACKUP"/*.md "$MEMORY_DIR"/
  echo "  원복:"; quota_run
  echo ""

  echo "### [★정원-상한] 상한 분기가 죽은 코드가 아닌지"
  # ⓐ 기본은 **비활성**이다 — WARN 만 뜨고 정원 때문에 죽지 않아야 한다.
  # ⓑ 켜면 FAIL 이 떠야 한다. 안 뜨면 상한 분기는 한 번도 안 도는 장식이다.
  echo "  ⓐ 기본(상한 비활성 · WARN 기대):"; quota_run
  echo "  ⓑ 상한 강제(BTS_STAR_QUOTA_MAX=1 · FAIL 기대):"
  export BTS_STAR_QUOTA_MAX=1; quota_run; unset BTS_STAR_QUOTA_MAX
  echo ""

  echo "### [★정원-상한 주입형] ★ 를 상한 위로 밀어 올린다"
  if [ -n "$STAR_MAX" ] && [ "$STAR_BASE" -le "$STAR_MAX" ]; then
    # 승격 주입은 「상한 이하에서 넘긴다」가 성립할 때만 판정을 바꾼다.
    need=$(( STAR_MAX - STAR_BASE + 1 ))
    for f in $(grep -lE '^[[:space:]]*priority:[[:space:]]*normal' "$MEMORY_DIR"/*.md | head -n "$need"); do
      sed -i '' -E 's/^([[:space:]]*)priority:[[:space:]]*normal/\1priority: critical/' "$f"
    done
    echo "  주입 확인: ★ $(star_count)건 (상한 ${STAR_MAX})"
    echo "  주입:"
    export BTS_STAR_QUOTA_MAX=1; quota_run; unset BTS_STAR_QUOTA_MAX
    cp "$STAR_BACKUP"/*.md "$MEMORY_DIR"/
    echo "  원복:"; quota_run
  else
    echo "  보류. ★ ${STAR_BASE}건이 이미 상한 ${STAR_MAX:-?} 를 넘어서, 더 늘려도 판정이 안 바뀐다."
    echo "        판정이 안 바뀌는 주입은 red 를 봐도 그 red 가 주입 때문인지 알 수 없다."
    echo "        ★ 를 상한 이하로 내리는 강등 단계 뒤에 다시 실행하면 이 갈래가 켜진다."
  fi
  echo ""

  echo "### [★정원] 원복 확인 — git 밖이라 git status 가 증인이 못 된다"
  STAR_AFTER="$(star_count)"
  if [ "$STAR_AFTER" != "$STAR_BASE" ]; then
    echo "  ⚠ 원복 실패. ★ ${STAR_BASE} → ${STAR_AFTER}."
    echo "     백업이 ${STAR_BACKUP} 에 남아 있다. 손으로 되돌릴 것."
  else
    echo "  원복 확인: ★ ${STAR_AFTER}건 — 기준선과 같다."
    # 변수 경로에 `rm -rf` 를 쓰지 않는다 (오타 한 글자의 비용이 비대칭이다).
    node -e "require('node:fs').rmSync(process.argv[1], { recursive: true, force: true })" "$STAR_BACKUP"
  fi
fi
echo ""

echo "### 최종 — 클린인데 green 이어야 한다"
git status --short
echo -n "  최종:   "; run
