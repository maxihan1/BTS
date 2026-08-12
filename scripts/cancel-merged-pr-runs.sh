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
# ## 계약 — 현재 main 내용을 검증 중인 run 은 어떤 경우에도 건드리지 않는다
#
# 머지 직후에는 **main 의 push CI 가 막 시작**된다. 그것을 취소하면 이 스크립트가 고치려던
# 문제(현재 main 을 검증하는 run 이 0건)를 스스로 만든다. 대상 브랜치가 비었거나 보호
# 목록(`master`·`HEAD`)에 있으면 gh 를 **한 번도 호출하지 않고** 끝낸다.
#
# ## ★`main` 은 통째 무접촉이 아니라 「현재 main 내용 무접촉」이다 (2026-08-12 좁힘)
#
# `concurrency` 는 **같은 그룹에 새 run 이 생길 때만** 발화한다. 그런데 `paths` 필터 때문에
# 새 run 자체가 안 생기는 경우가 있다 — `backend-ci` 는 `backend/**` 에만 트리거되므로 뒤
# 머지들이 전부 프론트·문서만 건드리면 **낡은 main backend-ci 가 계속 러너를 점유한다.**
# 2026-08-10 실측. 커밋 `236ff3532` 의 run 이 **1시간 43분** 점유했고 그 뒤에 PR 4개가 섰다.
#
# 그래서 `main` 만 계약을 좁힌다 — **검증 대상 커밋이 아닌 것의 run 은 취소 대상**이다.
# `master`·`HEAD` 는 통째 무접촉 그대로다.
#
# ## ★★검증 대상 커밋은 HEAD 가 아니다 — `[skip ci]` 를 되감는다 (2026-08-12 넓힘)
#
# 위 좁히기의 초판은 기준을 **HEAD 하나**로 잡았다. 그런데 `.husky/post-merge` 가
# `[chore] dashboard regen [skip ci]` 를 만들어 **push** 하면 원격 HEAD 가 한 칸 앞으로 밀린다.
# 그 커밋은 `[skip ci]` 라 자기 run 이 0건이고, 머지 내용을 실제로 검증 중인 run 은 **부모**에
# 붙어 있다. 그래서 HEAD 만 보호하면 그 run 이 정확히 취소 대상이 되어, 이 스크립트가 막으려던
# 「현재 main 을 검증하는 run 이 0건」을 **스스로 만든다.**
#
# ★언제 그 훅이 도는가 — 정확히 말해 둔다. `post-merge` 는 **로컬** merge/pull 에서만 발화하고
#   `gh pr merge` 자체(원격 동작)로는 돌지 않는다. 실제 트리거는 `gh pr merge --delete-branch`
#   가 머지된 브랜치를 정리하며 **main 을 체크아웃하고 pull** 하는 경로다(2026-08-12 PR #376
#   에서 관측). 즉 **항상은 아니고, 그 pull 이 가드보다 먼저 일어난 경우**에 밀린다.
#   가드는 원격에 HEAD 를 물으므로, 훅이 **push 까지 마친 뒤**라면 어느 경로로 밀렸든 보인다.
#
# 2026-08-12 PR #376 머지 직후 실측. HEAD `af3978648`(run 0건) / in_progress 는 부모
# `ade4dd826`.
#
# 그래서 기준을 **HEAD 부터 거슬러 첫 non-`[skip ci]` 커밋**으로 잡고, 훑은 구간 전체를
# 보호한다. 못 찾으면(조회 실패 · 빈 응답 · 구간이 전부 `[skip ci]`) 아무것도 하지 않는다.
#
# ★판정은 **메시지 전체**로 한다. GitHub 이 그렇게 하기 때문이다 — 2026-08-07 PR #345 에서
#   squash 본문 3행의 `[skip ci]` 가 main push CI 를 0회로 만들었다. 제목만 보면 가드의
#   모델이 GitHub 과 어긋나 이 결함이 그대로 재발한다.
#
# ## ★검증 대상 커밋을 로컬 git 이 아니라 원격에 묻는 이유
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
# `main` 은 여기 없다 — 아래 「현재 main 내용 무접촉」 경로가 대신 지킨다.
PROTECTED=("master" "HEAD")
# 이 브랜치만 HEAD 비교 경로를 탄다. 나머지는 종전대로 전건 취소다.
HEAD_GUARDED_BRANCH="main"

# HEAD 에서 거슬러 올라가며 훑을 커밋 수. 실전에서 `[skip ci]` 재생성 커밋은 1개지만,
# 문서만 고치는 머지가 연달아 나면 여러 개가 겹칠 수 있어 여유를 둔다.
HEAD_SCAN_DEPTH=10

# ★GitHub 이 CI 를 건너뛰는 커밋 메시지 토큰 5종.
#   **제목이 아니라 메시지 전체**를 본다 — 2026-08-07 PR #345 실측에서 squash 본문 3행의
#   `[skip ci]` 가 main push CI 를 0회로 만들었다. 가드가 제목만 보면 GitHub 과 모델이
#   어긋나 이 결함이 그대로 재발한다.
SKIP_CI_TOKENS=("[skip ci]" "[ci skip]" "[no ci]" "[skip actions]" "[actions skip]")

# ★★되감기 대상은 「CI 를 건너뛴 커밋」 **전부가 아니다** — 우리가 스스로 만드는 생성 커밋뿐이다.
#
#   왜 좁히나. 토큰만으로 판정하면 **내용이 있는 머지 커밋**도 되감기 대상이 된다. squash 본문은
#   브랜치 커밋 메시지를 그대로 싣기 때문에(2026-08-07 PR #345 가 정확히 그 사고였다) 머지
#   커밋이 `[skip ci]` 를 물고 오는 일이 실제로 있다. 그러면 스캔이 그 머지를 지나쳐 **훨씬
#   낡은 커밋**을 검증 대상으로 잡고, 거기 붙은 낡은 run 을 보호한다 — PR #366 이 닫은
#   「낡은 main run 이 러너를 몇 시간 점유」(2026-08-10 실측 1시간 43분) 부채가 되살아난다.
#
#   반대로 내용 있는 머지가 `[skip ci]` 를 물고 오면 GitHub 도 그 커밋의 CI 를 돌리지 않으므로
#   run 이 0건이다. 되감지 않고 그것을 기준으로 삼아도 **보호할 run 이 없어** 손해가 없고,
#   그보다 낡은 run 은 예정대로 취소된다. 좁히는 쪽이 두 방향 모두에서 안전하다.
#
#   ⚠️ 이 목록은 `.husky/post-merge` 가 쓰는 커밋 메시지와 **짝을 이뤄야 한다.** 한쪽만 바뀌면
#      가드가 조용히 되감기를 멈춘다 — `merged-pr-run-cleanup.test.ts` 의
#      「post-merge 훅이 만드는 커밋이 되감기 대상으로 분류된다」 판별식이 그 어긋남을 막는다.
REWIND_MESSAGE_PATTERNS=("dashboard regen")

# 패턴 목록 중 하나라도 메시지에 있으면 0, 없으면 1.
#
# ★대소문자를 가리지 않는다. GitHub 의 건너뛰기 키워드 판정이 그렇기 때문이다 — 가드가
#   소문자만 보면 `[SKIP CI]` 커밋을 놓치고, GitHub 은 CI 를 건너뛰었는데 가드는 그것을
#   검증 대상으로 잡아 **이 스크립트가 고치려는 결함이 그대로 재현된다.**
message_matches_any() {
  local message="$1"
  shift
  local pattern
  local matched=1
  shopt -s nocasematch
  for pattern in "$@"; do
    if [[ "$message" == *"$pattern"* ]]; then
      matched=0
      break
    fi
  done
  shopt -u nocasematch
  return "$matched"
}

# 메시지에 CI 건너뛰기 토큰이 있으면 0, 없으면 1.
has_skip_ci_token() {
  message_matches_any "$1" "${SKIP_CI_TOKENS[@]}"
}

# 우리가 만드는 생성 커밋(대시보드 재생성 등)이면 0, 아니면 1.
is_generated_commit() {
  message_matches_any "$1" "${REWIND_MESSAGE_PATTERNS[@]}"
}

# sha 가 보호 집합에 있으면 0, 없으면 1. 집합은 공백으로 구분된 문자열이다.
is_protected_sha() {
  case " $PROTECTED_SHAS " in
    *" $1 "*) return 0 ;;
  esac
  return 1
}

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
#
# ★「현재 HEAD」가 아니라 「현재 main 내용을 검증 중인 커밋 구간」을 보호한다.
#   post-merge 훅이 머지 직후 `[chore] dashboard regen [skip ci]` 를 push 해 HEAD 를 한 칸
#   민다. 그 커밋은 `[skip ci]` 라 자기 run 이 0건이고, 머지 내용을 검증 중인 run 은
#   **부모**에 붙어 있다. HEAD 하나만 보호하면 그 run 이 취소 대상이 되어, PR #366 이
#   막으려던 「현재 main 을 검증하는 run 이 0건」을 이 가드가 스스로 만든다.
#   2026-08-12 PR #376 머지에서 실측됐고, 훅은 사실상 모든 머지에서 저 커밋을 만든다.
PROTECTED_SHAS=""
EFFECTIVE_HEAD=""
if [ "$BRANCH" = "$HEAD_GUARDED_BRANCH" ]; then
  # ★판정을 `--jq` 에 넣지 않는다. 가짜 gh 는 jq 를 실제로 돌리지 않으므로 거기서 판정하면
  #   **스크립트가 그렇게 하는지 아닌지를 영영 못 잰다.** jq 는 **정형까지만** 한다 —
  #   `@json` 으로 메시지를 개행이 `\n` 으로 이스케이프된 한 줄짜리 문자열로 만든다.
  #
  #   ★한 번 틀렸던 자리다. 초안은 `split("\n") | join(" ")` 로 **jq 안에서** 개행을 눕혔다.
  #   그런데 「메시지 전체를 보는가, 제목만 보는가」는 정형이 아니라 **판정**이다. jq 에
  #   두었더니 「제목만 검사」 뮤테이션이 살아남았다 — 가짜 gh 가 이미 눕혀진 줄을 뱉으므로
  #   그 줄을 어떻게 훼손해도 계약 테스트의 사정거리 밖이었다. 지금은 이스케이프만 jq 가
  #   하고, 「어디까지가 검사 대상인가」는 아래 bash 가 정한다.
  commits=$("$GH" api \
    "repos/{owner}/{repo}/commits?sha=${HEAD_GUARDED_BRANCH}&per_page=${HEAD_SCAN_DEPTH}" \
    --jq '.[] | "\(.sha) \(.commit.message | @json)"' 2> /dev/null) || commits=""

  while read -r sha message; do
    case "$sha" in '') continue ;; esac
    # ★검사 대상은 **메시지 전체**다 — 제목이 아니다. GitHub 이 그렇게 판정하기 때문이고
    #   (2026-08-07 PR #345), 여기서 제목만 남기면 본문에 `[skip ci]` 가 있는 커밋을
    #   놓쳐 이 스크립트가 고치려던 결함이 그대로 재발한다. 이 한 줄이 그 결정이다.
    scanned="$message"
    # 훑은 커밋은 전부 보호한다. 생성 커밋에도 (수동 dispatch 등으로) run 이 붙을 수
    # 있고, 그것 역시 지금 main 에 있는 내용을 검증 중이다.
    PROTECTED_SHAS="$PROTECTED_SHAS $sha"
    # ★되감기 조건은 **둘 다** 참일 때다 — CI 를 건너뛴 커밋이면서, 우리가 만든 생성 커밋일 때.
    #   토큰만으로 판정하면 내용 있는 머지 커밋까지 지나쳐 낡은 run 을 보호한다(위 §REWIND 주석).
    if has_skip_ci_token "$scanned" && is_generated_commit "$scanned"; then
      continue
    fi
    EFFECTIVE_HEAD="$sha"
    break
  done << EOF
$commits
EOF

  # 못 찾으면 아무것도 하지 않는다 — 모르는 상태에서 취소로 새는 것이 곧 자기 발등 찍기다.
  # 조회 실패 · 빈 응답 · 훑은 구간이 전부 `[skip ci]` 인 경우가 모두 여기로 온다.
  if [ -z "$EFFECTIVE_HEAD" ]; then
    echo "run-cleanup. '$BRANCH' 의 검증 대상 커밋을 확인하지 못했다 — 아무것도 하지 않는다."
    exit 0
  fi
fi

# ★건수는 **run 개수**로 센다 — 호출 횟수가 아니다.
# 상태 2종을 각각 조회하므로 같은 run 이 두 번 나온다. 그대로 세면 run 1개가 「2건」이 되고,
# 로그를 읽는 사람은 「생각보다 많이 죽었나」로 오독한다. id 를 모아 마지막에 중복을 없앤다.
CANCELLED_IDS=""
PROTECTED_IDS=""

# queued 와 in_progress 를 **둘 다** 본다. queued 만 보면 이미 러너를 잡은 좀비를 놓치는데,
# 러너가 1대라 정확히 그것이 가장 아픈 경우다.
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
    # ★현재 main 내용을 검증 중인 run 은 건너뛴다. 이 한 줄이 자기 발등 찍기를 막는다.
    if [ -n "$PROTECTED_SHAS" ] && is_protected_sha "$sha"; then
      PROTECTED_IDS="$PROTECTED_IDS $id"
      continue
    fi
    if "$GH" run cancel "$id" > /dev/null 2>&1; then
      CANCELLED_IDS="$CANCELLED_IDS $id"
    fi
  done << EOF
$runs
EOF
done

# 공백으로 구분된 id 목록에서 **고유** 개수를 센다. 빈 목록이면 0.
count_unique_runs() {
  if [ -z "$1" ]; then
    echo 0
    return
  fi
  # shellcheck disable=SC2086 — 단어 분할이 목적이다. 각 id 를 한 줄로 펼쳐 중복을 없앤다.
  printf '%s\n' $1 | sort -u | grep -c .
}

CANCELLED=$(count_unique_runs "$CANCELLED_IDS")
PROTECTED_RUNS=$(count_unique_runs "$PROTECTED_IDS")

if [ "$CANCELLED" -gt 0 ]; then
  echo "run-cleanup. '$BRANCH' 의 잔존 run ${CANCELLED}건을 취소했다 — 러너가 그만큼 빨리 풀린다."
else
  echo "run-cleanup. '$BRANCH' 에 취소할 run 이 없다."
fi

# ★건드리지 않은 것도 숫자로 남긴다. 「0건 취소」가 「대상이 없었다」인지 「전부 보호됐다」인지
#   구분되지 않으면, 계약이 과하게 넓어져도 로그만 봐서는 알 수 없다.
#   ★취소분과 보호분은 **서로소인 집합**이다. 「그중」으로 이으면 자기모순이 된다.
if [ "$PROTECTED_RUNS" -gt 0 ]; then
  echo "run-cleanup. 현재 main 내용을 검증 중인 run ${PROTECTED_RUNS}건은 건드리지 않았다 (기준 커밋 ${EFFECTIVE_HEAD})."
fi

exit 0
