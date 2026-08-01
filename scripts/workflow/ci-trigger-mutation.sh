#!/usr/bin/env bash
# backend-ci 트리거 봉인의 비-공허 확인 — 뺀 경로를 되돌려 넣어 red 가 뜨는지 본다
#
# 무엇을 지키나. backend-ci 는 `./gradlew` 만 실행하는데, 판별식 전용 경로
# (scripts/workflow/** · vite/playwright config · package.json)가 트리거에 걸려 있으면
# 그 경로만 바꾸는 PR 이 backend 12잡을 통째로 끌고 온다 (러너 1대 직렬 50~60분).
# PR #329 에서 실측된 비용이라 가설이 아니다.
#
# 전제. 커밋된 클린 상태. 서명. 「원복 후 클린인데 green」.
# `ci-runner-label-alignment.test.ts` 의 DISCRIMINANT_ONLY_PATHS 룰을 고칠 때 재실행한다.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1

TEST="scripts/workflow/ci-runner-label-alignment.test.ts"
CI=".github/workflows/backend-ci.yml"

run() {
  node --test "$TEST" 2>&1 | grep -E '^ℹ (pass|fail)' | tr '\n' ' '
  echo ""
}

if [ -n "$(git status --short)" ]; then
  echo "ABORT. dirty 상태다. 커밋 후 다시 실행할 것."
  git status --short
  exit 1
fi
echo "클린 ✓"
echo ""
echo "### 기준선 (green 이어야)"
echo -n "  "; run
echo ""

for P in "scripts/workflow/**" "apps/web/vite.config.ts" "apps/web/playwright.config.ts" "package.json"; do
  echo "### [되돌림 주입] pull_request 에 '$P' 추가"
  node -e "
const fs=require('fs'); const f='$CI';
let s=fs.readFileSync(f,'utf8');
s=s.replace(/(  pull_request:\n    paths:\n      - 'backend\/\*\*'\n)/, \"\$1      - '$P'\n\");
fs.writeFileSync(f,s);"
  echo -n "  주입:   "; run
  git checkout -- "$CI"
  echo -n "  원복:   "; run
  echo ""
done

echo "### push 블록만 주입 — 절반 봉인도 잡는가"
node -e "
const fs=require('fs'); const f='$CI';
let s=fs.readFileSync(f,'utf8');
s=s.replace(/(    paths:\n      - 'backend\/\*\*'\n      - '\.github\/workflows\/backend-ci\.yml'\n)$/m, \"\$1      - 'scripts/workflow/**'\n\");
fs.writeFileSync(f,s);"
echo -n "  주입:   "; run
git checkout -- "$CI"
echo -n "  원복:   "; run
echo ""

echo "### 최종 — 클린인데 green 이어야 한다"
git status --short
echo -n "  최종:   "; run
