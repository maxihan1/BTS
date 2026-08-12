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
#   product·fr-index 의 (FR-XX, N개) 헤더 / CLAUDE.md·README·CHANGELOG 의 'N FR' 표기(룰 E)가
#   모두 일치해야 함. 더해 README §1 진척 열 ⟺ product 미완 D 마커의 양방향 정합(룰 H)과
#   DEVELOPMENT.md ⟺ docs/sdd/22 의 줄수 규칙 서술 정합(룰 I, 2026-08-12 PR #365 신설).

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
CHANGELOG_MD="${REPO_ROOT}/CHANGELOG.md"

count_fail() {
  echo "" >&2
  echo "FAIL. 카운트 drift — $1 (정본 FR=${PLAN_COUNT})." >&2
  echo "  → 변경 시 모든 정본·미러·카운트 전수 동기화 (CLAUDE.md §명세/범위 변경 시 전수 동기화)." >&2
  EXIT_CODE=4
}

# 카운트가 아니라 두 정본의 서술이 서로 어긋날 때 (룰 H). 종료 코드는 count_fail 과 같은 4다.
sync_fail() {
  echo "" >&2
  echo "FAIL. 정합 drift — $1." >&2
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
#    + BC 완료 게이트 "(N FR)" 전체합계 헤더(접두사 없음, 예: "§2~§7 (35 FR)") — fr-index §A.1 해당 BC 행 수와 대조
#    (2026-07-17 확장 — 기존 정규식은 "(30 FR)" 형식을 못 잡아 매칭 0 → 종료 0 으로 통과시켰음. CLAUDE.md §전수 동기화 강제)
while IFS= read -r hline; do
  [[ -z "$hline" ]] && continue
  hfile="${hline%%:*}"
  hrest="${hline#*:}"
  hprefix="$(printf '%s' "$hrest" | grep -oE 'FR-[A-Z]+' | head -1 || true)"
  if [[ -n "$hprefix" ]]; then
    hdecl="$(printf '%s' "$hrest" | grep -oE '[0-9]+개' | grep -oE '[0-9]+' | head -1)"
    [[ -z "$hdecl" ]] && continue
    hactual="$(grep -hoE "${hprefix}-[0-9]+" "$hfile" | sort -u | wc -l | tr -d ' ')"
    if [[ "$hdecl" != "$hactual" ]]; then
      count_fail "$(basename "$hfile") 헤더 '${hprefix} ${hdecl}개' (실제 ${hactual})"
    fi
  else
    hdecl="$(printf '%s' "$hrest" | grep -oE '[0-9]+ FR' | grep -oE '[0-9]+' | head -1)"
    [[ -z "$hdecl" ]] && continue
    hbc="$(basename "$hfile" .md)"
    hactual="$(grep -cE "^\| FR-[A-Z]+-[0-9]+ \|.*\| ${hbc} \|" "$FR_INDEX" || true)"
    if [[ "$hdecl" != "$hactual" ]]; then
      count_fail "$(basename "$hfile") BC 완료 게이트 '${hdecl} FR' (fr-index §A.1 '${hbc}' 실측 ${hactual})"
    fi
  fi
done < <(grep -rnE '\(FR-[A-Z]+,? *[0-9]+개\)|\([0-9]+ FR\)' "$PRODUCT_DIR" "$FR_INDEX" || true)

# E) 'N FR' 표기 — CLAUDE.md + docs/plan/README.md + CHANGELOG.md 의 **살아있는 구역**만
#    (2026-07-29 확대. 그전까지 CLAUDE.md 만 봐서 131→132 동기화가 README·CHANGELOG 에서 멈춘 것을
#     EXIT 0 으로 통과시켰다.)
#
#    2자리+ 수만 본다 — 이 가드를 떼면 README §0.5 헤더의 '§0.5 FR' 이 '5 FR' 로 오탐한다. 유지 필수.
#
#    동결 구역 제외.
#      README      — `## §7. 변경 이력` 은 append-only 라 '합계 131→132' 같은 과거 표기가 정상이다.
#      CHANGELOG   — `## [Unreleased]` 블록만 본다. 끊긴 릴리스 블록(장래 `## [0.1.0]`)은 그 시점의
#                    FR 수를 동결 기록하므로, 스캔하면 카운트가 오를 때마다 영구 EXIT 4 가 된다.
#      docs/poc/context-notes.md — 같은 이유(동결 이력)로 **의도적 제외**다. 우연히 빠진 것이 아니다.
#
#    fr-index.md 는 **의도적 미포함**. `:265` 의 'issue-tracking 29 FR' 처럼 BC 단위 값이 있어
#    총계와 대조하면 오탐 EXIT 4 가 난다 (그 값 자체의 드리프트는 별건이다).
for ef in "$CLAUDE_MD" "$README" "$CHANGELOG_MD"; do
  [[ -f "$ef" ]] || continue
  case "$(basename "$ef")" in
    README.md)    elive="$(sed -n '1,/^## §7\. 변경 이력/p' "$ef" | sed '$d')" ;;
    CHANGELOG.md) elive="$(awk '/^## \[Unreleased\]/{f=1;next} f&&/^## \[/{f=0} f' "$ef")" ;;
    *)            elive="$(cat "$ef")" ;;
  esac
  while IFS= read -r cnum; do
    [[ -n "$cnum" && "$cnum" != "$PLAN_COUNT" ]] && count_fail "$(basename "$ef") '${cnum} FR'"
  done < <(printf '%s\n' "$elive" | grep -oE '[0-9]{2,} FR' | grep -oE '^[0-9]+' || true)
done

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

# G) 조립 앱 BC 카운트 정합 (automation-prod-assembly PR #259, 리뷰 C1)
#    :modules:app build.gradle 의 BC 모듈 의존 수 == 조립 관련 파일들의 'N개 BC' 표기.
#    (2026-06-05 카운트 drift 사고 클래스 — 조립 서술자는 verify 사각지대였음.)
APP_BUILD="${REPO_ROOT}/backend/modules/app/build.gradle.kts"
if [[ -f "$APP_BUILD" ]]; then
  BC_ACTUAL="$(grep -cE 'implementation\(project\(":modules:' "$APP_BUILD" || true)"
  ASM_FILES=(
    "$APP_BUILD"
    "${REPO_ROOT}/backend/modules/app/src/main/kotlin/com/bts/app/FlywayAssemblyConfig.kt"
    "${REPO_ROOT}/backend/modules/app/src/main/kotlin/com/bts/app/BtsApplication.kt"
    "${REPO_ROOT}/backend/modules/app/src/test/kotlin/com/bts/app/BtsApplicationContextTest.kt"
    "${REPO_ROOT}/backend/modules/app/src/main/resources/application.yml"
    "${REPO_ROOT}/backend/settings.gradle.kts"
    "${REPO_ROOT}/infra/docker-compose.prod.yml"
  )
  for af in "${ASM_FILES[@]}"; do
    [[ -f "$af" ]] || continue
    while IFS= read -r bcnum; do
      [[ -n "$bcnum" && "$bcnum" != "$BC_ACTUAL" ]] && \
        count_fail "$(basename "$af") '${bcnum}개 BC' ≠ 조립 BC 의존 ${BC_ACTUAL}"
    done < <(grep -oE '[0-9]+개 BC' "$af" | grep -oE '^[0-9]+' || true)
  done
fi

# H) README §1 진척 열 ⟺ product/<bc>.md 미완 D 마커 (양방향 ⟺, 2026-07-29 신설)
#    한 방향만 닫으면 절반 봉인이다. 역방향(미완 0인데 '☑' 가 아님)은 가설이 아니라 이미 발생한
#    사고다 — README §1 인용 블록이 "그전까지 9 BC 전부 ☐ 로 남아 실제와 어긋나 있었다" 로 기록한다.
#    진척 셀은 표의 마지막 칸이므로 열 개수와 무관하게 $(NF-1) 로 집는다.
if [[ -f "$README" ]]; then
  while IFS= read -r rline; do
    hrel="$(printf '%s' "$rline" | grep -oE '\]\(product/[a-z0-9-]+\.md\)' | head -1 | sed 's/^](//; s/)$//')"
    [[ -z "$hrel" ]] && continue
    hpf="${PLAN_DIR}/${hrel}"
    [[ -f "$hpf" ]] || continue
    hpending="$(grep -cE '^- \[[ ~!]\] D[0-9]+\.' "$hpf" || true)"
    hprog="$(printf '%s' "$rline" | awk -F'|' '{print $(NF-1)}' | sed 's/^ *//; s/ *$//')"
    if [[ "$hpending" -eq 0 && "$hprog" != "☑ D단계" ]]; then
      sync_fail "README §1 $(basename "$hpf") 진척 '${hprog}' — 미완 D 단계 0건이므로 '☑ D단계' 여야 한다"
    fi
    if [[ "$hpending" -gt 0 && "$hprog" == "☑ D단계" ]]; then
      sync_fail "README §1 $(basename "$hpf") 진척 '☑ D단계' — 실제 미완 D 단계 ${hpending}건"
    fi
  done < <(grep -E '\]\(product/' "$README" || true)
fi

# I) 줄수 규칙 정본 대조 — DEVELOPMENT.md ⟺ docs/sdd/22-claude-code-env.md (2026-08-12 신설, PR #365)
#    두 정본이 같은 규칙을 다르게 적으면 어느 쪽을 고쳐도 반대편에 drift 가 남는다.
#    2026-08-11 재측정이 그 충돌을 발견했지만, 25일 전 같은 양식(Obsidian 동기화 서술)이
#    장부에 등재되지 않아 그대로 재발했다 — 「맞는 진단을 적었는데도 안 퍼졌다」.
#    그래서 사람의 재측정이 아니라 여기서 잡는다.
#
#    ★행 부재도 FAIL 이다. 「없으면 검사 안 함」으로 짜면 행을 지우는 것만으로 가드가 죽는다
#    (`[[seal-blinds-existing-guard]]` 양식). 그래서 전체 건수와 태그된 건수를 함께 잰다.
SDD_ENV="${REPO_ROOT}/docs/sdd/22-claude-code-env.md"
DEV_MD="${REPO_ROOT}/DEVELOPMENT.md"
if [[ -f "$SDD_ENV" && -f "$DEV_MD" ]]; then
  # I-1) '파일 300줄' 은 Kotlin 전용이다 (DEVELOPMENT.md §2.1). SDD 표에도 태그가 있어야 한다.
  #      §2.2 TypeScript 에는 파일 상한 조항이 없으므로, 태그 없는 행은 TS 에도 적용되는 것으로 오독된다.
  SDD_300_ANY="$(grep -cE '파일 300줄' "$SDD_ENV" || true)"
  SDD_300_TAG="$(grep -cE '^\| *Kotlin: *함수 30줄 이내, 파일 300줄 이내|^\| *Kotlin: *파일 300줄 이내' "$SDD_ENV" || true)"
  if [[ "$SDD_300_ANY" -ne 1 || "$SDD_300_TAG" -ne 1 ]]; then
    sync_fail "SDD 22.7.1 '파일 300줄' — 전체 ${SDD_300_ANY}행 / 'Kotlin:' 태그 ${SDD_300_TAG}행 (각 1 기대). DEVELOPMENT.md:55 는 §2.1 Kotlin 전용 조항이다"
  fi
  # I-2) 반대 방향 — TypeScript 컴포넌트 200줄이 SDD 에도 있어야 한다.
  #      DEVELOPMENT.md:72 에만 있고 SDD 에 없으면 SDD 만 읽는 에이전트가 그 규칙을 모른다.
  SDD_200_TAG="$(grep -cE '^\| *TypeScript: *컴포넌트 200줄 이내' "$SDD_ENV" || true)"
  if [[ "$SDD_200_TAG" -ne 1 ]]; then
    sync_fail "SDD 22.7.1 'TypeScript: 컴포넌트 200줄 이내' ${SDD_200_TAG}행 (1 기대) — DEVELOPMENT.md:72 와 어긋난다"
  fi
  # I-3) DEVELOPMENT.md 쪽 사본은 각 1건이어야 한다. 사본이 늘면 어느 쪽이 정본인지 모호해진다.
  DEV_300="$(grep -cE '파일 300줄 이내' "$DEV_MD" || true)"
  DEV_200="$(grep -cE '컴포넌트 200줄 이내' "$DEV_MD" || true)"
  if [[ "$DEV_300" -ne 1 || "$DEV_200" -ne 1 ]]; then
    sync_fail "DEVELOPMENT.md 줄수 규칙 사본 — '파일 300줄' ${DEV_300}행 · '컴포넌트 200줄' ${DEV_200}행 (각 1 기대)"
  fi
fi

# --- 4) 최종 결과 ---
if [[ "$EXIT_CODE" -eq 0 ]]; then
  echo ""
  echo "PASS. FR ID ${PLAN_COUNT}/${SDD_COUNT} 매핑 완료. 체크박스 마커 정상. 카운트 정합(fr-index/README/헤더/CLAUDE)."
fi

exit "$EXIT_CODE"
