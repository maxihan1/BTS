#!/usr/bin/env bash
# 마스터 구현 계획(docs/plan/) 자동 검증 — FR ID 누락 + 체크박스 마커 형식
#
# 동작.
#   1) SDD docs/sdd/02-requirements.md 에서 FR ID 추출
#   2) docs/plan/product/*.md + docs/plan/fr-index.md 에서 FR ID 추출
#   3) diff 0행이어야 통과 (양방향 차집합 공집합)
#   4) 체크박스 마커 4종 외 거부 ([ ] / [~] / [x] / [!])
#
# 종료 코드. 0 = 통과, 1 = SDD 매핑 누락, 2 = 마커 위반, 3 = 파일 부재

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SDD_FILE="${REPO_ROOT}/docs/sdd/02-requirements.md"
PLAN_DIR="${REPO_ROOT}/docs/plan"
PRODUCT_DIR="${PLAN_DIR}/product"
FR_INDEX="${PLAN_DIR}/fr-index.md"

# --- 사전 점검 ---
for f in "$SDD_FILE" "$FR_INDEX"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR. 필수 파일 부재. $f" >&2
    exit 3
  fi
done
if [[ ! -d "$PRODUCT_DIR" ]]; then
  echo "ERROR. product/ 디렉토리 부재. $PRODUCT_DIR" >&2
  exit 3
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- 1) FR ID 추출 ---
grep -hoE "FR-[A-Z]+-[0-9]+" "$SDD_FILE" | sort -u > "$TMP/sdd.txt"
SDD_COUNT="$(wc -l < "$TMP/sdd.txt" | tr -d ' ')"

grep -rhoE "FR-[A-Z]+-[0-9]+" "$PRODUCT_DIR" "$FR_INDEX" | sort -u > "$TMP/plan.txt"
PLAN_COUNT="$(wc -l < "$TMP/plan.txt" | tr -d ' ')"

echo "→ SDD FR ID 카운트     . $SDD_COUNT"
echo "→ plan FR ID 카운트    . $PLAN_COUNT"

# 양방향 차집합
MISSING_IN_PLAN="$(comm -23 "$TMP/sdd.txt" "$TMP/plan.txt")"
EXTRA_IN_PLAN="$(comm -13 "$TMP/sdd.txt" "$TMP/plan.txt")"

EXIT_CODE=0

if [[ -n "$MISSING_IN_PLAN" ]]; then
  echo "" >&2
  echo "FAIL. plan에 빠진 FR ID (SDD에는 있음)." >&2
  echo "$MISSING_IN_PLAN" | sed 's/^/  - /' >&2
  EXIT_CODE=1
fi

if [[ -n "$EXTRA_IN_PLAN" ]]; then
  echo "" >&2
  echo "FAIL. plan에 있지만 SDD에 없는 FR ID (오타 의심)." >&2
  echo "$EXTRA_IN_PLAN" | sed 's/^/  - /' >&2
  EXIT_CODE=1
fi

# --- 2) 체크박스 마커 검증 (4종 외 거부) ---
# 허용. `- [ ]` `- [~]` `- [x]` `- [!]`
# 거부. `- [X]` (대문자), `- [.]`, `- [v]`, `- [-]` 등
INVALID_MARKERS="$(grep -rnE '^\s*-\s+\[[^ x~!]\]' "$PRODUCT_DIR" "$PLAN_DIR/README.md" "$FR_INDEX" 2>/dev/null || true)"
if [[ -n "$INVALID_MARKERS" ]]; then
  echo "" >&2
  echo "FAIL. 허용 체크박스 마커 4종 (\` \`, \`~\`, \`x\`, \`!\`) 외 사용 발견." >&2
  echo "$INVALID_MARKERS" | sed 's/^/  /' >&2
  EXIT_CODE=2
fi

# --- 3) 최종 결과 ---
if [[ "$EXIT_CODE" -eq 0 ]]; then
  echo ""
  echo "PASS. FR ID ${PLAN_COUNT}/${SDD_COUNT} 매핑 완료. 체크박스 마커 정상."
fi

exit "$EXIT_CODE"
