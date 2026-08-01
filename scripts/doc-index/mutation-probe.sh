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
  node --test "$TEST" 2>&1 | grep -E '^ℹ (pass|fail)' | tr '\n' ' '
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

echo "### 최종 — 클린인데 green 이어야 한다"
git status --short
echo -n "  최종:   "; run
