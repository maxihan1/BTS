#!/usr/bin/env bash
# 머지·닫힌 PR 이 큐에 남긴 run 을 취소한다 — 러너를 한 잡도 쓰지 않는다
#
# ## 무엇을 닫는가
#
# `concurrency: {group, cancel-in-progress}` 는 **같은 그룹에 새 run 이 생길 때만** 이전 run 을
# 취소한다. 이미 머지되고 브랜치까지 삭제된 PR 의 큐 잔존분은 그 ref 에 후속 run 이 영영
# 생기지 않아 **취소가 발화할 계기 자체가 없다.** 2026-08-07 큐 8건 중 3건이 정확히 그
# 경우(PR #346)였고, 그때 **현재 main 을 검증하는 run 은 0건**이었다.
#
# 러너가 1대(`maxi-mac-bts`)라 잡이 전부 한 줄로 선다. 좀비 run 하나가 러너를 점유하는 동안
# 현재 커밋의 검증은 시작조차 못 한다.
#
# ## ★왜 워크플로우가 아니라 스크립트인가
#
# TODOS 의 원안 ①은 `pull_request: types: [closed]` 트리거를 가진 얇은 워크플로우였다.
# **러너 1대 환경에서 성립하지 않는다** — 그 취소 잡 자신이 큐 맨 뒤에 서서, 풀어 주려던
# 바로 그 정체가 끝나기를 몇 시간 기다린다. 좀비를 치우는 도구가 좀비 뒤에 줄을 서는 셈이다.
# `ubuntu-latest` 로 빼는 우회는 불가하다 — 결제 차단으로 그 러너는 배정조차 되지 않는다
# (2026-07-29 이후, `docs/runbooks/self-hosted-runner.md`).
#
# 그래서 **머지를 수행하는 쪽**(`/bts-merge` Step 5)이 로컬에서 직접 취소한다. 러너 소모 0.
#
# ## 계약 — 이 스크립트는 절대 실패하지 않는다 (fail-open)
#
# 정리 도구가 머지 절차를 막으면 안 된다. `gh` 부재 · 인증 만료 · API 오류 · 대상 0건 —
# 어떤 경우에도 **exit 0** 이다. 실패를 삼키는 대신 사람이 읽을 한 줄을 남긴다.
#
# ## 계약 — 현재 HEAD 를 검증 중인 run 은 어떤 경우에도 건드리지 않는다
#
# 머지 직후에는 **main 의 push CI 가 막 시작**된다. 그것을 취소하면 이 스크립트가 고치려던
# 문제(현재 main 을 검증하는 run 이 0건)를 스스로 만든다. 대상 브랜치가 비었거나 보호
# 목록(`master`·`HEAD`)에 있으면 gh 를 **한 번도 호출하지 않고** 끝낸다.
#
# ## ★`main` 은 통째 무접촉이 아니라 「현재 HEAD 무접촉」이다 (2026-08-12 좁힘)
#
# `concurrency` 는 **같은 그룹에 새 run 이 생길 때만** 발화한다. 그런데 `paths` 필터 때문에
# 새 run 자체가 안 생기는 경우가 있다 — `backend-ci` 는 `backend/**` 에만 트리거되므로 뒤
# 머지들이 전부 프론트·문서만 건드리면 **낡은 main backend-ci 가 계속 러너를 점유한다.**
# 2026-08-10 실측. 커밋 `236ff3532` 의 run 이 **1시간 43분** 점유했고 그 뒤에 PR 4개가 섰다.
#
# 그래서 `main` 만 계약을 좁힌다 — **현재 HEAD 가 아닌 커밋의 run 은 취소 대상**이다.
# `master`·`HEAD` 는 통째 무접촉 그대로다.
#
# ## ★현재 HEAD 를 로컬 git 이 아니라 원격에 묻는 이유
#
# `git rev-parse origin/main` 은 **fetch 시점에 멈춘 값**이다. 그것이 뒤처져 있으면 새 HEAD 의
# run 을 「낡은 것」으로 오판해 죽인다 — 정확히 이 계약이 막으려는 사고다. 원격에 직접 묻고,
# **못 물으면 아무것도 하지 않는다.** 이음매도 BTS_GH_BIN 하나로 유지된다.
#
# 사용. scripts/cancel-merged-pr-runs.sh <머지된-브랜치-이름>
#       scripts/cancel-merged-pr-runs.sh main   # 낡은 main run 정리
# 이음매. BTS_GH_BIN 으로 gh 를 대체할 수 있다 — 실제 API 를 건드리지 않고 계약을 검증하는
#         유일한 통로이고, scripts/workflow/merged-pr-run-cleanup.test.ts 가 그 이음매를 쓴다.
set -uo pipefail

BRANCH="${1:-}"
GH="${BTS_GH_BIN:-gh}"

# 보호 브랜치. 여기 있는 이름은 이 스크립트가 절대 취소 대상으로 삼지 않는다.
# `main` 은 여기 없다 — 아래 「현재 HEAD 무접촉」 경로가 대신 지킨다.
PROTECTED=("master" "HEAD")
# 이 브랜치만 HEAD 비교 경로를 탄다. 나머지는 종전대로 전건 취소다.
HEAD_GUARDED_BRANCH="main"

if [ -z "$BRANCH" ]; then
  echo "run-cleanup. 대상 브랜치가 비었다 — 아무것도 하지 않는다."
  exit 0
fi

for p in "${PROTECTED[@]}"; do
  if [ "$BRANCH" = "$p" ]; then
    echo "run-cleanup. '$BRANCH' 는 보호 브랜치다 — 아무것도 하지 않는다."
    exit 0
  fi
done

if ! command -v "$GH" > /dev/null 2>&1; then
  echo "run-cleanup. gh 를 찾지 못했다 — 건너뛴다. (남은 run 은 러너를 계속 점유한다)"
  exit 0
fi

# 빈 값이면 sha 비교를 하지 않는다 — 즉 조회된 run 을 전부 취소한다(PR 브랜치의 종전 동작).
HEAD_SHA=""
if [ "$BRANCH" = "$HEAD_GUARDED_BRANCH" ]; then
  HEAD_SHA=$("$GH" api "repos/{owner}/{repo}/commits/${HEAD_GUARDED_BRANCH}" \
    --jq '.sha' 2> /dev/null) || HEAD_SHA=""
  if [ -z "$HEAD_SHA" ]; then
    echo "run-cleanup. '$BRANCH' 의 현재 HEAD 를 확인하지 못했다 — 아무것도 하지 않는다."
    exit 0
  fi
fi

# queued 와 in_progress 를 **둘 다** 본다. queued 만 보면 이미 러너를 잡은 좀비를 놓치는데,
# 러너가 1대라 정확히 그것이 가장 아픈 경우다.
CANCELLED=0
PROTECTED_RUNS=0
for status in queued in_progress; do
  # 실패해도 계속 간다 — 한 상태 조회가 죽어도 다른 상태는 정리한다.
  # ★headSha 를 함께 받아 **이 스크립트 안에서** 비교한다. jq 쪽에서 걸러 버리면 판정
  #   로직이 gh 안으로 숨어 계약 테스트가 그것을 잴 수 없다.
  runs=$("$GH" run list --branch "$BRANCH" --status "$status" --limit 50 \
    --json databaseId,headSha --jq '.[] | "\(.databaseId) \(.headSha)"' 2> /dev/null) || runs=""
  while read -r id sha; do
    # id 가 숫자가 아니면 무시한다 — 예기치 못한 출력 형태에 `gh run cancel` 을 먹이지 않는다.
    case "$id" in
      '' | *[!0-9]*) continue ;;
    esac
    # ★현재 HEAD 를 검증 중인 run 은 건너뛴다. 이 한 줄이 자기 발등 찍기를 막는다.
    if [ -n "$HEAD_SHA" ] && [ "$sha" = "$HEAD_SHA" ]; then
      PROTECTED_RUNS=$((PROTECTED_RUNS + 1))
      continue
    fi
    if "$GH" run cancel "$id" > /dev/null 2>&1; then
      CANCELLED=$((CANCELLED + 1))
    fi
  done << EOF
$runs
EOF
done

if [ "$CANCELLED" -gt 0 ]; then
  echo "run-cleanup. '$BRANCH' 의 잔존 run ${CANCELLED}건을 취소했다 — 러너가 그만큼 빨리 풀린다."
else
  echo "run-cleanup. '$BRANCH' 에 취소할 run 이 없다."
fi

# ★건드리지 않은 것도 숫자로 남긴다. 「0건 취소」가 「대상이 없었다」인지 「전부 보호됐다」인지
#   구분되지 않으면, 계약이 과하게 넓어져도 로그만 봐서는 알 수 없다.
if [ "$PROTECTED_RUNS" -gt 0 ]; then
  echo "run-cleanup. 그중 ${PROTECTED_RUNS}건은 현재 HEAD(${HEAD_SHA}) 검증이라 건드리지 않았다."
fi

exit 0
