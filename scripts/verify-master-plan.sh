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
# 룰 I 대상. 부재를 조용히 통과시키지 않으려고 여기서 필수 파일로 잡는다 —
# 룰 I 안에서 `[[ -f ]]` 로만 감싸면 파일을 지우는 것만으로 가드가 죽는다(자기가 인용한 양식의 재생산).
SDD_ENV="${REPO_ROOT}/docs/sdd/22-claude-code-env.md"
DEV_MD="${REPO_ROOT}/DEVELOPMENT.md"

# --- 사전 점검 ---
for f in "$SDD_FILE" "$FR_INDEX" "$SDD_ENV" "$DEV_MD"; do
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
#
#    ★비-공허 하한 (2026-08-13 신설). 이 룰은 **매치가 있어야** 무언가를 검사한다.
#      CLAUDE.md 를 줄이면서 'N FR' 표기를 지우면 매치가 0 이 되고, 룰 E 는 실패가 아니라
#      **아무 말 없이 통과**한다 — 가드가 죽은 것을 초록이 감춘다. 파일을 지우거나 개명해도
#      아래 `[[ -f ]] || continue` 로 같은 결과가 된다.
#      그래서 CLAUDE.md 의 매치 수를 세어 0 이면 실패시킨다. 이 하한이 요구하는 것은
#      「CLAUDE.md 살아있는 구역에 2자리+ 'N FR' 표기가 최소 1회 존재」다.
E_CLAUDE_HITS=0
for ef in "$CLAUDE_MD" "$README" "$CHANGELOG_MD"; do
  [[ -f "$ef" ]] || continue
  case "$(basename "$ef")" in
    README.md)    elive="$(sed -n '1,/^## §7\. 변경 이력/p' "$ef" | sed '$d')" ;;
    CHANGELOG.md) elive="$(awk '/^## \[Unreleased\]/{f=1;next} f&&/^## \[/{f=0} f' "$ef")" ;;
    *)            elive="$(cat "$ef")" ;;
  esac
  ehits=0
  while IFS= read -r cnum; do
    [[ -z "$cnum" ]] && continue
    ehits=$((ehits + 1))
    if [[ "$cnum" != "$PLAN_COUNT" ]]; then
      count_fail "$(basename "$ef") '${cnum} FR'"
    fi
  done < <(printf '%s\n' "$elive" | grep -oE '[0-9]{2,} FR' | grep -oE '^[0-9]+' || true)
  if [[ "$(basename "$ef")" == "CLAUDE.md" ]]; then
    E_CLAUDE_HITS="$ehits"
  fi
done
if [[ "$E_CLAUDE_HITS" -eq 0 ]]; then
  sync_fail "룰 E 가 공허하다 — CLAUDE.md 의 살아있는 구역에서 '[0-9]{2,} FR' 표기를 0건 찾았다 (최소 1건 필요). 파일이 없거나, 개명됐거나, 축약하면서 FR 카운트 표기가 사라졌다. 정본 FR=${PLAN_COUNT} 를 본문에 1회 표기할 것"
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
#    ★「없으면 검사 안 함」을 세 층에서 모두 막았다 (`[[seal-blinds-existing-guard]]` 양식).
#      ①파일 부재 → 위 사전 점검에서 exit 3   ②행 부재 → I-1 이 각 행 1개를 요구
#      ③조항 이동 → I-4 가 절 구간을 잘라서 센다 (총계만 세면 §2.1→§2.2 이동을 못 본다)
#    1차 초안은 ①③을 다 열어 뒀고 게다가 I-1 의 정규식 대안이 **거부된 통째 태그 형태를
#    화이트리스트에 올려** 가장 흔한 회귀를 통과시켰다. 코드리뷰 뮤테이션 11종이 그것을 적발했다.
#
#    ★보지 않는 범위 (의도적 — 조용한 축소가 아니다).
#      이 룰의 계약은 **두 정본 사이의 정합**이다. 제3의 문서가 「TypeScript 도 파일 300줄」이라
#      적는 경우는 검사하지 않는다 — 전 문서 스캔은 오탐(인용·회고·`TODOS.md` 자신)이 많아
#      가드를 무력화하는 쪽이 더 크다. 그 층이 필요해지면 `TODOS.md` 에 별도 부채로 등재할 것.
#
# I-1) SDD 22.7.1 표의 줄수 3행을 **전수 열거로 정확 매치**한다. 각 정확히 1행.
#      부분 매치가 아니라 행 전체 형태를 고정하는 이유 — 「간결하게 정리」가 통째 태그
#      (`Kotlin: 함수 30줄 이내, 파일 300줄 이내`)로 되돌리는 것이 가장 흔한 회귀인데,
#      그 형태를 정규식 대안으로 허용하면 **가드가 거부된 설계를 화이트리스트에 올린다.**
#      한 행 안에 규칙을 덧붙이는 변형도 행 전체 매치가 깨져 여기서 걸린다.
SDD_ROW_COMMON='^\| 함수 30줄 이내 \(Kotlin · TypeScript 공통\) \|'
SDD_ROW_KT='^\| Kotlin: 파일 300줄 이내 \|'
SDD_ROW_TS='^\| TypeScript: 컴포넌트 200줄 이내 \|'
N_COMMON="$(grep -cE "$SDD_ROW_COMMON" "$SDD_ENV" || true)"
N_KT="$(grep -cE "$SDD_ROW_KT" "$SDD_ENV" || true)"
N_TS="$(grep -cE "$SDD_ROW_TS" "$SDD_ENV" || true)"
if [[ "$N_COMMON" -ne 1 || "$N_KT" -ne 1 || "$N_TS" -ne 1 ]]; then
  sync_fail "SDD 22.7.1 줄수 3행 — 공통 ${N_COMMON} · Kotlin ${N_KT} · TypeScript ${N_TS} (각 1 기대). 3행 분리 형태를 유지할 것 (통째 태그로 되돌리면 TS 의 '함수 30줄' 이 Kotlin 전용이 된다)"
fi

# I-2) SDD 표에서 '파일 300줄' 을 말하는 행은 위 Kotlin 행 **하나뿐**이어야 한다.
#      표 행(`^|`)으로 범위를 좁힌다 — 산문이 그 규칙을 설명하는 것까지 막으면 오탐이다.
SDD_300_ROWS="$(grep -cE '^\|.*파일 300줄' "$SDD_ENV" || true)"
if [[ "$SDD_300_ROWS" -ne 1 ]]; then
  sync_fail "SDD 22.7.1 표에서 '파일 300줄' 을 말하는 행 ${SDD_300_ROWS}개 (1 기대) — 'Kotlin: 파일 300줄 이내' 한 행만 허용"
fi

# I-3) 무효화된 주장의 직접 봉쇄 — TypeScript 와 파일 300줄이 같은 줄에 오면 안 된다.
#      2026-08-12 Maxi 확정으로 TS 파일 상한은 무효다. 산문까지 포함해 검사한다.
for f in "$SDD_ENV" "$DEV_MD"; do
  N_TS300="$(grep -cE 'TypeScript.*파일 300줄|파일 300줄.*TypeScript' "$f" || true)"
  if [[ "$N_TS300" -ne 0 ]]; then
    sync_fail "$(basename "$f") 에 TypeScript 와 '파일 300줄' 이 같은 줄에 ${N_TS300}건 — TS 파일 상한은 2026-08-12 확정으로 무효다"
  fi
done

# I-4) DEVELOPMENT.md 는 **절 구간을 잘라서** 센다. 출현 횟수만 세면 조항을 §2.1 에서 §2.2 로
#      옮겨도(= 이 PR 이 무효라 선언한 상태로 되돌려도) 총계가 같아 통과한다.
#      절 헤딩이 바뀌면 구간이 비어 카운트 0 이 되고 여기서 걸린다 — 그것도 알아야 할 변경이다.
DEV_S21="$(awk '/^### §2\.1 /{f=1;next} /^### §2\.[2-9] /{f=0} f' "$DEV_MD")"
DEV_S22="$(awk '/^### §2\.2 /{f=1;next} /^### §2\.[3-9] /{f=0} f' "$DEV_MD")"
S21_300="$(printf '%s\n' "$DEV_S21" | grep -cE '파일 300줄 이내' || true)"
S22_300="$(printf '%s\n' "$DEV_S22" | grep -cE '파일 300줄' || true)"
S22_200="$(printf '%s\n' "$DEV_S22" | grep -cE '컴포넌트 200줄 이내' || true)"
if [[ "$S21_300" -ne 1 || "$S22_300" -ne 0 || "$S22_200" -ne 1 ]]; then
  sync_fail "DEVELOPMENT.md 절 귀속 — §2.1 '파일 300줄' ${S21_300}행(1 기대) · §2.2 '파일 300줄' ${S22_300}행(0 기대) · §2.2 '컴포넌트 200줄' ${S22_200}행(1 기대)"
fi

# J) 표면 정본 대조 — scripts/workflow/surfaces.ts ⟺ docs/rules/behavior-rules.md (2026-08-13 신설)
#    작업 티어 판정의 글로브 정본은 `surfaces.ts` 의 `SURFACES` 하나다. 사람이 읽는 표면 표는
#    `docs/rules/behavior-rules.md` 에 있고, 둘은 **서로를 안 보는 두 목록**이 되기 쉽다 —
#    이 저장소의 지배 결함 양식이다. 표면 하나를 코드에서 개명하고 문서를 안 고치면
#    문서가 조용히 썩고, 사람은 없는 표면을 근거로 티어를 선언한다.
#
#    ★비교 대상은 **표면 이름**이지 글로브가 아니다. 글로브를 문서에 다시 적으면 그것이
#      세 번째 목록이 된다. 문서는 이름 + 설명만 갖고, 글로브는 코드에만 있다.
#
#    ★부재를 조용히 넘기지 않는다. 두 파일 중 하나만 없어도 차집합은 「양쪽 다 0」으로
#      공집합이 되어 통과한다 — 룰 E 가 뚫렸던 방식 그대로다. 그래서 부재를 먼저 실패시킨다.
SURFACES_TS="${REPO_ROOT}/scripts/workflow/surfaces.ts"
BEHAVIOR_RULES="${REPO_ROOT}/docs/rules/behavior-rules.md"
#
#    파싱 계약 (양쪽이 이 서식을 지켜야 한다 — 어기면 여기서 걸린다).
#      surfaces.ts        . `export const SURFACES` 블록 안, 들여쓴 `KEY:` 형태의 대문자 키
#      behavior-rules.md  . 표면 표의 각 행이 `| `KEY` | …` 로 시작 (첫 칸이 백틱 감싼 대문자 키)
if [[ ! -f "$SURFACES_TS" || ! -f "$BEHAVIOR_RULES" ]]; then
  sync_fail "표면 정본 파일 부재 — surfaces.ts $( [[ -f "$SURFACES_TS" ]] && echo 있음 || echo 없음 ) · behavior-rules.md $( [[ -f "$BEHAVIOR_RULES" ]] && echo 있음 || echo 없음 ). 한쪽만 있으면 차집합이 공허하게 통과한다"
else
  # `|| true` 는 「매치 0건」을 파이프 실패로 죽이지 않으려는 것이다 (`set -o pipefail`).
  # 0건 자체는 아래 하한 단언이 명시적으로 잡는다 — 조용히 넘어가는 경로가 아니다.
  { awk '/^export const SURFACES/{f=1;next} f&&/^}/{exit} f' "$SURFACES_TS" \
    | grep -oE '^[[:space:]]+[A-Z][A-Z0-9_]*:' | tr -d ' :' | sort -u > "$TMP/surf-code.txt"; } || true
  { grep -oE '^\|[[:space:]]*`[A-Z][A-Z0-9_]*`' "$BEHAVIOR_RULES" \
    | grep -oE '[A-Z][A-Z0-9_]*' | sort -u > "$TMP/surf-doc.txt"; } || true
  touch "$TMP/surf-code.txt" "$TMP/surf-doc.txt"

  SURF_CODE_COUNT="$(wc -l < "$TMP/surf-code.txt" | tr -d ' ')"
  SURF_DOC_COUNT="$(wc -l < "$TMP/surf-doc.txt" | tr -d ' ')"

  # 비-공허 하한. 티어 4종 중 어느 하나라도 표면이 전멸하면 판정이 성립하지 않는다.
  # 현재 15키에서 3키 여유 — 여유가 아니라 **파서 고장 검출 마진**이다. 파서가 절반만
  # 죽어도(15→7) 차집합은 여전히 0 일 수 있으므로 개수 하한이 따로 필요하다.
  SURF_MIN=12
  if [[ "$SURF_CODE_COUNT" -lt "$SURF_MIN" || "$SURF_DOC_COUNT" -lt "$SURF_MIN" ]]; then
    sync_fail "표면 이름을 surfaces.ts ${SURF_CODE_COUNT}개 · behavior-rules.md ${SURF_DOC_COUNT}개만 찾았다 (각 ${SURF_MIN} 이상 기대) — 파서가 고장났거나 표면이 실제로 줄었다. 0 이면 아래 차집합이 공허하게 통과한다"
  fi

  SURF_MISSING_DOC="$(comm -23 "$TMP/surf-code.txt" "$TMP/surf-doc.txt")"
  SURF_MISSING_CODE="$(comm -13 "$TMP/surf-code.txt" "$TMP/surf-doc.txt")"
  if [[ -n "$SURF_MISSING_DOC" ]]; then
    sync_fail "behavior-rules.md 표면 표에 없는 표면 — $(printf '%s' "$SURF_MISSING_DOC" | tr '\n' ' ')(surfaces.ts 에는 있음)"
  fi
  if [[ -n "$SURF_MISSING_CODE" ]]; then
    sync_fail "surfaces.ts 에 없는 표면 — $(printf '%s' "$SURF_MISSING_CODE" | tr '\n' ' ')(behavior-rules.md 표면 표에는 있음. 개명했거나 오타다)"
  fi
fi

# --- 4) 최종 결과 ---
if [[ "$EXIT_CODE" -eq 0 ]]; then
  echo ""
  echo "PASS. FR ID ${PLAN_COUNT}/${SDD_COUNT} 매핑 완료. 체크박스 마커 정상. 카운트 정합(fr-index/README/헤더/CLAUDE)."
fi

exit "$EXIT_CODE"
