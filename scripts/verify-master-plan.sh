#!/usr/bin/env bash
# 마스터 구현 계획(docs/plan/) 자동 검증 — FR ID 누락 + 체크박스 마커 형식
#
# 동작.
#   1) SDD docs/sdd/02-requirements.md 에서 FR ID 추출
#   2) docs/plan/product/*.md + docs/plan/fr-index.md 에서 FR ID 추출
#   3) diff 0행이어야 통과 (양방향 차집합 공집합)
#   4) 체크박스 마커 4종 외 거부 ([ ] / [~] / [x] / [!])
#
# 종료 코드. 0 = 통과, 1 = SDD 매핑 누락, 2 = 마커 위반, 3 = 파일 부재, 4 = 카운트 drift
#
# 카운트 정합(종료 4) — CLAUDE.md §명세/범위 변경 시 전수 동기화 강제.
#   FR ID 실집합(PLAN_COUNT)을 정본으로, fr-index 합계 / README 합계·BC테이블 /
#   product·fr-index 의 (FR-XX, N개) 헤더 / CLAUDE.md 'N FR' 표기가 모두 일치해야 함.

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

# --- 3) 카운트 정합 검증 (CLAUDE.md §전수 동기화 강제) ---
README="${PLAN_DIR}/README.md"
CLAUDE_MD="${REPO_ROOT}/CLAUDE.md"

count_fail() {
  echo "" >&2
  echo "FAIL. 카운트 drift — $1 (정본 FR=${PLAN_COUNT})." >&2
  echo "  → 변경 시 모든 정본·미러·카운트 전수 동기화 (CLAUDE.md §명세/범위 변경 시 전수 동기화)." >&2
  EXIT_CODE=4
}

# A) fr-index 합계 행: | **합계** | **N** |
FRIDX_TOTAL="$(grep -oE '\*\*합계\*\* \| \*\*[0-9]+\*\*' "$FR_INDEX" | grep -oE '[0-9]+' | head -1 || true)"
if [[ -n "$FRIDX_TOTAL" && "$FRIDX_TOTAL" != "$PLAN_COUNT" ]]; then
  count_fail "fr-index 합계 ${FRIDX_TOTAL}"
fi

# B) README 합계 + C) BC 테이블 행 합 (product 링크 행의 첫 '| N (')
if [[ -f "$README" ]]; then
  RDM_TOTAL="$(grep -oE '\*\*합계\*\*\. [0-9]+ FR' "$README" | grep -oE '[0-9]+' | head -1 || true)"
  if [[ -n "$RDM_TOTAL" && "$RDM_TOTAL" != "$PLAN_COUNT" ]]; then
    count_fail "README 합계 ${RDM_TOTAL}"
  fi
  RDM_SUM="$(grep -E '\]\(product/' "$README" | grep -oE '\| [0-9]+ \(' | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}')"
  if [[ "$RDM_SUM" != "0" && "$RDM_SUM" != "$PLAN_COUNT" ]]; then
    count_fail "README BC테이블 행 합 ${RDM_SUM}"
  fi
fi

# D) (FR-XX, N개) 헤더 — product/*.md + fr-index 의 파일 내 실제 FR-XX 카운트와 대조
while IFS= read -r hline; do
  [[ -z "$hline" ]] && continue
  hfile="${hline%%:*}"
  hrest="${hline#*:}"
  hprefix="$(printf '%s' "$hrest" | grep -oE 'FR-[A-Z]+' | head -1)"
  hdecl="$(printf '%s' "$hrest" | grep -oE '[0-9]+개' | grep -oE '[0-9]+' | head -1)"
  [[ -z "$hprefix" || -z "$hdecl" ]] && continue
  hactual="$(grep -hoE "${hprefix}-[0-9]+" "$hfile" | sort -u | wc -l | tr -d ' ')"
  if [[ "$hdecl" != "$hactual" ]]; then
    count_fail "$(basename "$hfile") 헤더 '${hprefix} ${hdecl}개' (실제 ${hactual})"
  fi
done < <(grep -rnE '\(FR-[A-Z]+,? *[0-9]+개\)' "$PRODUCT_DIR" "$FR_INDEX" || true)

# E) CLAUDE.md 'N FR' 표기 (2자리+ 수만 — 'PR #54' 등 오탐 방지)
if [[ -f "$CLAUDE_MD" ]]; then
  while IFS= read -r cnum; do
    [[ -n "$cnum" && "$cnum" != "$PLAN_COUNT" ]] && count_fail "CLAUDE.md '${cnum} FR'"
  done < <(grep -oE '[0-9]{2,} FR' "$CLAUDE_MD" | grep -oE '^[0-9]+' || true)
fi

# F) fr-index §A.2 BC 행 합 == 정본, product '소속 FR. N개' == §A.2 행 (per-BC drift)
A2_SUM="$(grep -E '^\| [a-z][a-z-]+ \| [0-9]+ \|' "$FR_INDEX" | awk -F'|' '{gsub(/[^0-9]/,"",$3); s+=$3} END{print s+0}')"
if [[ "$A2_SUM" != "0" && "$A2_SUM" != "$PLAN_COUNT" ]]; then
  count_fail "fr-index §A.2 BC 행 합 ${A2_SUM}"
fi
for pf in "$PRODUCT_DIR"/*.md; do
  bc="$(basename "$pf" .md)"
  pdecl="$(grep -m1 '소속 FR' "$pf" | grep -oE '[0-9]+개' | head -1 | grep -oE '[0-9]+' || true)"
  [[ -z "$pdecl" ]] && continue
  a2cnt="$(grep -E "^\| ${bc} \| [0-9]+ \|" "$FR_INDEX" | head -1 | awk -F'|' '{gsub(/[^0-9]/,"",$3); print $3}')"
  if [[ -n "$a2cnt" && "$pdecl" != "$a2cnt" ]]; then
    count_fail "${bc}.md 소속 FR ${pdecl} ≠ fr-index §A.2 ${a2cnt}"
  fi
done

# --- 4) 최종 결과 ---
if [[ "$EXIT_CODE" -eq 0 ]]; then
  echo ""
  echo "PASS. FR ID ${PLAN_COUNT}/${SDD_COUNT} 매핑 완료. 체크박스 마커 정상. 카운트 정합(fr-index/README/헤더/CLAUDE)."
fi

exit "$EXIT_CODE"
