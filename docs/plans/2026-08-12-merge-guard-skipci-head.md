# 머지 가드가 `[skip ci]` 대시보드 재생성 커밋에 눈이 먼다

> slug: merge-guard-skipci-head
> type: chore (fast-track — 게이트 1 생략, 게이트 2 만 정지)
> agent: backend-engineer
> 생성: 2026-08-12

## Brief

**사용자 원문.** PR #376 머지 절차를 밟다가 발견한 결함을 고친다.

`scripts/cancel-merged-pr-runs.sh` 는 `main` 을 대상으로 돌 때 「현재 HEAD 를 검증 중인 run 은
어떤 경우에도 건드리지 않는다」는 계약을 지킨다(PR #366). 그런데 post-merge 훅이 머지 직후
`[chore] dashboard regen [skip ci]` 커밋을 push 하면서 **HEAD 를 한 칸 앞으로 민다.**
그 커밋은 `[skip ci]` 라 자기 run 이 0건이고, 머지 내용을 실제로 검증 중인 run 은 **부모**
(머지 커밋)에 붙어 있다. 가드는 `headSha != HEAD_SHA` 를 취소하므로 **그 검증 run 이 정확히
취소 대상**이 된다. PR #366 이 막으려던 「현재 main 을 검증하는 run 이 0건」을 가드가 스스로
만든다.

**실측 (2026-08-12, PR #376 머지 직후).**

```
ade4dd826  머지 커밋. workflow-scripts-ci 가 여기서 in_progress
af3978648  훅의 regen 커밋 [skip ci]. run 0건. 그런데 이것이 HEAD

$ gh api repos/{owner}/{repo}/commits/main --jq .sha        → af3978648
$ gh run list --branch main --status in_progress            → ade4dd826  ← 취소 대상이 된다
$ gh run list --branch main --jq '[.[]|select(.headSha=="af3978648")]|length'  → 0
```

이번 머지에서는 Step 5 의 `cancel-merged-pr-runs.sh main` 을 **손으로 건너뛰어** 회피했다.
훅은 사실상 모든 머지에서 regen 커밋을 만들므로(`8dd0e7371`·`f90efaed7` 동형) 이 경로는
**예외가 아니라 기본값**이고, 사람이 매번 기억해서 건너뛰는 것은 처방이 아니다.

**채택 처방 (검토 대상).** 가드의 「현재 HEAD」를 **HEAD 부터 거슬러 첫 non-`[skip ci]` 커밋**
으로 정의한다. 대안 ①(훅에서 `[skip ci]` 제거)은 문서 1파일 변경마다 main push CI 를 1회
더 태우므로 러너 1대 환경에서 비용이 크다. 대안 ③(스킬 호출 순서 교환)은 훅이 `gh pr merge`
안에서 동기 실행되므로 **원리적으로 불가**.

**비-공허 짝 (필수).** `scripts/workflow/merged-pr-run-cleanup.test.ts` 의 가짜 `gh` 이음매에
「HEAD 가 `[skip ci]` 이고 부모에 in_progress run 이 있다」 케이스를 넣지 않으면 이 처방이
실제로 배선됐는지 **영영 못 잰다**. 뮤테이션으로 red 확인까지 간다.

**분류 오버라이드 기록.** `classify-task.ts` 원출력은 `type=backend` · `primary_bc=notification`
이었다. 둘 다 오분류다 — 이 작업은 `scripts/` 와 `.claude/skills/` 만 건드리고 Kotlin 모듈·BC 를
하나도 손대지 않는다. 「신호 0 이면 backend 기본값」 규칙이 발화한 것이고 notification 은 오탐.
같은 스크립트를 만든 PR #366 의 선례에 맞춰 `chore` 로 고정했다.

## 도메인 정리 (← /bts-domain 채움)

_fast-track (chore) — 생략._

## 스펙 (← /bts-spec Phase A 채움)

_fast-track (chore) — 생략._

## Brainstorming Check (← /bts-spec Phase B 채움)

_fast-track (chore) — 생략._

## Plan (← /bts-plan 채움)

**Goal.** `cancel-merged-pr-runs.sh` 가 `main` 을 정리할 때, 「현재 HEAD」 대신
**「HEAD 부터 거슬러 첫 non-`[skip ci]` 커밋까지의 구간」** 을 보호 집합으로 삼는다.

**Architecture.** `gh api` 를 단일 커밋 조회(`commits/main` → `.sha`)에서 **커밋 목록 조회**
(`commits?sha=main&per_page=10` → `sha + 개행 제거한 message`)로 바꾸고, skip-ci 판정은
**스크립트 안 bash** 에 둔다. `--jq` 는 필드 정형까지만 — 가짜 `gh` 는 jq 를 실제로 돌리지
않으므로 판정을 jq 에 넣으면 배선 여부를 영영 못 잰다(기존 파일 헤더 주석의 원칙).

**★후방호환이 공짜로 성립한다.** 가짜 gh 는 `api` 호출에 `$HEAD_SHA` **한 줄**을 뱉는다.
새 파싱(`read -r sha message`)에서 그 줄은 「sha 1개 + 빈 메시지」로 읽히고, 빈 메시지는
skip-ci 가 아니므로 곧 effective head 가 된다 — **기존 15 케이스는 손대지 않아도 그대로 통과**한다.

**Tech Stack.** bash (`set -uo pipefail`, errexit 없음) · `gh` CLI · `node --experimental-strip-types --test`

### 파일 구조

| 파일 | 책임 | 변경 |
|---|---|---|
| `scripts/cancel-merged-pr-runs.sh` | 정리 도구 본체. 보호 집합 판정이 여기 산다 | 수정 |
| `scripts/workflow/merged-pr-run-cleanup.test.ts` | 계약 판별식. 가짜 `gh` 이음매 | 수정 (하네스 + 4 케이스) |
| `.claude/skills/bts-merge/SKILL.md` | 호출부. Step 5 주석이 계약을 서술 | 수정 (문구 정정) |

---

### Task 1. 가짜 gh 하네스에 커밋 목록 응답을 추가하고 「skip-ci HEAD 의 부모 run 보호」를 RED 로 세운다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/merged-pr-run-cleanup.test.ts`, `scripts/cancel-merged-pr-runs.sh`]
- depends-on: []

**RED**.
- 파일. `scripts/workflow/merged-pr-run-cleanup.test.ts`
- ① `FakeGhBehavior` 에 필드 추가 (기존 `headSha` 는 그대로 둔다 — 후방호환).

  ```ts
  /**
   * `gh api` 가 돌려줄 커밋 목록 — HEAD 가 맨 앞이다.
   *
   * ★`message` 는 **본문까지 포함한 전체**다. GitHub 의 CI 건너뛰기 판정이 제목이 아니라
   * 메시지 전체를 보기 때문이다(2026-08-07 PR #345 실측 — squash 본문 3행의 `[skip ci]` 가
   * main push CI 를 0회로 만들었다). 스크립트는 개행을 공백으로 눕힌 한 줄을 받는다.
   */
  commits?: { sha: string; message: string }[]
  ```

- ② 가짜 gh 의 `api` 분기를 커밋 목록도 뱉도록 바꾼다. `commits` 가 없으면 종전대로
  `$HEAD_SHA` 한 줄 — 기존 케이스가 그대로 돈다.

  ```ts
  // runScript() 안, ghPath 작성 직전
  const apiLines = (behavior.commits ?? []).map(
    (c) => `${c.sha} ${c.message.split('\n').join(' ')}`,
  )
  ```

  ```ts
  // 가짜 gh 본문의 api 분기를 아래로 교체
  'if [ "$1" = "api" ]; then',
  '  if [ "$API_FAIL" = "1" ]; then exit 1; fi',
  ...(apiLines.length > 0
    ? apiLines.map((line) => `  echo "${line}"`)
    : ['  echo "$HEAD_SHA"']),
  '  exit 0',
  'fi',
  ```

- ③ 케이스 추가.

  ```ts
  test('★★HEAD 가 [skip ci] 면 그 부모(= 실제 검증 중인 커밋)의 run 을 취소하지 않는다', () => {
    // 이 PR 의 존재 이유. post-merge 훅이 머지 직후 `[chore] dashboard regen [skip ci]` 를
    // push 해 HEAD 를 한 칸 민다. 그 커밋은 run 이 0건이고, 머지 내용을 검증 중인 run 은
    // **부모**에 붙어 있다. HEAD 만 보호하면 그 run 이 정확히 취소 대상이 된다.
    // 실측 2026-08-12 PR #376 — HEAD af3978648(run 0건) / in_progress ade4dd826.
    //
    // ★`[skip ci]` 를 **본문**에 둔다. 제목만 보는 구현은 여기서 red 가 나야 한다.
    const REGEN = 'a'.repeat(40)
    const MERGE = 'b'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: 'chore: dashboard regen\n\n[skip ci]' },
        { sha: MERGE, message: 'docs: 부채 등재 (#376)' },
      ],
      runs: [{ id: '901', sha: MERGE }],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      `머지 내용을 검증 중인 run 을 취소했다 — 가드가 스스로 「현재 main 검증 0건」을 만든다.\n` +
        r.calls.join('\n'),
    )
  })
  ```

- 실패 메시지 (예상). `AssertionError: Expected values to be strictly deep-equal` —
  `actual: ['run cancel 901', 'run cancel 901']` (상태 2종 × 1건).

**GREEN**.
- 파일. `scripts/cancel-merged-pr-runs.sh`
- ① `HEAD_GUARDED_BRANCH` 선언 **아래**에 상수 + 판정 함수를 넣는다.

  ```bash
  # HEAD 에서 거슬러 올라가며 훑을 커밋 수. 실전에서 `[skip ci]` 재생성 커밋은 1개지만,
  # 문서만 고치는 머지가 연달아 나면 여러 개가 겹칠 수 있어 여유를 둔다.
  HEAD_SCAN_DEPTH=10

  # ★GitHub 이 CI 를 건너뛰는 커밋 메시지 토큰 5종.
  #   **제목이 아니라 메시지 전체**를 본다 — 2026-08-07 PR #345 실측에서 squash 본문 3행의
  #   `[skip ci]` 가 main push CI 를 0회로 만들었다. 가드가 제목만 보면 GitHub 과 모델이
  #   어긋나 이 결함이 그대로 재발한다.
  SKIP_CI_TOKENS=("[skip ci]" "[ci skip]" "[no ci]" "[skip actions]" "[actions skip]")

  # 메시지에 CI 건너뛰기 토큰이 있으면 0, 없으면 1.
  has_skip_ci_token() {
    for token in "${SKIP_CI_TOKENS[@]}"; do
      case "$1" in
        *"$token"*) return 0 ;;
      esac
    done
    return 1
  }

  # sha 가 보호 집합에 있으면 0, 없으면 1. 집합은 공백으로 구분된 문자열이다.
  is_protected_sha() {
    case " $PROTECTED_SHAS " in
      *" $1 "*) return 0 ;;
    esac
    return 1
  }
  ```

- ② `HEAD_SHA` 해석 블록을 통째로 교체한다.

  ```bash
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
    # ★판정을 `--jq` 에 넣지 않는다. 가짜 gh 는 jq 를 실제로 돌리지 않으므로 거기서 걸러
    #   버리면 **스크립트가 거르는지 아닌지를 영영 못 잰다.** jq 는 정형까지만 —
    #   메시지의 개행을 공백으로 눕혀 한 커밋이 한 줄이 되게 한다.
    commits=$("$GH" api \
      "repos/{owner}/{repo}/commits?sha=${HEAD_GUARDED_BRANCH}&per_page=${HEAD_SCAN_DEPTH}" \
      --jq '.[] | "\(.sha) \(.commit.message | split("\n") | join(" "))"' 2> /dev/null) || commits=""

    while read -r sha message; do
      case "$sha" in '') continue ;; esac
      # 훑은 커밋은 전부 보호한다. `[skip ci]` 커밋에도 (수동 dispatch 등으로) run 이 붙을 수
      # 있고, 그것 역시 지금 main 에 있는 내용을 검증 중이다.
      PROTECTED_SHAS="$PROTECTED_SHAS $sha"
      if ! has_skip_ci_token "$message"; then
        EFFECTIVE_HEAD="$sha"
        break
      fi
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
  ```

- ③ run 순회 안의 비교 한 줄을 교체한다.

  ```bash
  # ★현재 main 내용을 검증 중인 run 은 건너뛴다. 이 한 줄이 자기 발등 찍기를 막는다.
  if [ -n "$PROTECTED_SHAS" ] && is_protected_sha "$sha"; then
    PROTECTED_IDS="$PROTECTED_IDS $id"
    continue
  fi
  ```

- ④ 마지막 로그 줄을 교체한다. **`run ${PROTECTED_RUNS}건` 형태는 유지**한다 —
  기존 케이스 `★★사람이 읽는 건수는 run 개수다` 가 `/run 1건/` 으로 잰다.

  ```bash
  if [ "$PROTECTED_RUNS" -gt 0 ]; then
    echo "run-cleanup. 현재 main 내용을 검증 중인 run ${PROTECTED_RUNS}건은 건드리지 않았다 (기준 커밋 ${EFFECTIVE_HEAD})."
  fi
  ```

**REFACTOR**.
- 파일 헤더 주석의 `## ★`main` 은 통째 무접촉이 아니라 「현재 HEAD 무접촉」이다` 절 제목과
  본문을 **「현재 main 내용 무접촉」** 으로 고치고, `[skip ci]` 되감기 기전을 3줄로 적는다.
  주석이 옛 계약을 서술한 채 남으면 다음 사람이 되돌린다.
- `사용.` 예시 줄은 그대로 둔다 (인터페이스 불변).

**검증**.
```bash
node --experimental-strip-types --test scripts/workflow/merged-pr-run-cleanup.test.ts
```
기대. 신규 1건 포함 **16 pass / 0 fail**.

---

### Task 2. 「과하게 넓히지 않았다」 · 「전부 skip-ci 면 fail-open」 · 「skip-ci HEAD 자신의 run 도 보호」 3 케이스를 추가한다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/merged-pr-run-cleanup.test.ts`]
- depends-on: [1]

**RED**. Task 1 의 GREEN 이 이미 서 있으므로 이 셋은 **처음부터 통과할 수 있다.**
그래서 이 task 의 red 는 테스트가 아니라 **Task 4 의 뮤테이션**이 담당한다 — 여기서는
「가드가 반대 방향으로 새지 않는가」를 고정하는 것이 목적이다. (Task 4 에서 각 케이스가
어느 뮤테이션을 잡는지 표로 확인한다.)

- 파일. `scripts/workflow/merged-pr-run-cleanup.test.ts`

  ```ts
  test('★★[skip ci] 구간보다 낡은 커밋의 run 은 여전히 취소한다 (가드가 과하게 넓지 않다)', () => {
    // 반대 방향 사고. 보호를 넓히다가 「전부 보호」가 되면 낡은 run 이 러너를 계속 점유해
    // PR #366 이 닫은 부채가 되살아난다. 2026-08-10 실측 — 낡은 backend-ci 가 1시간 43분 점유.
    const REGEN = 'a'.repeat(40)
    const MERGE = 'b'.repeat(40)
    const OLD = 'c'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: 'chore: dashboard regen [skip ci]' },
        { sha: MERGE, message: 'feat: 뭔가 (#377)' },
        { sha: OLD, message: 'feat: 더 낡은 것 (#375)' },
      ],
      runs: [
        { id: '901', sha: MERGE },
        { id: '902', sha: OLD },
      ],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    // 상태 2종 × 낡은 run 1건 = 2회. 검증 중인 901 은 한 번도 없어야 한다.
    assert.deepEqual(
      cancels.sort(),
      ['run cancel 902', 'run cancel 902'],
      `낡은 run 만 정확히 취소해야 한다 (보호=901, 취소=902).\n${r.calls.join('\n')}`,
    )
  })

  test('★훑은 구간이 전부 [skip ci] 면 아무것도 취소하지 않는다 (fail-open)', () => {
    // 검증 대상 커밋을 특정하지 못한 상태다. 여기서 「전부 취소」로 새면 그것이 곧
    // 자기 발등 찍기다 — 모르면 손대지 않는다. 기존 HEAD 미확인 케이스와 같은 방향.
    const r = runScript('main', {
      commits: [
        { sha: 'a'.repeat(40), message: 'chore: regen [skip ci]' },
        { sha: 'b'.repeat(40), message: 'chore: doc index regen — 메모리 1건 등재 [skip ci]' },
      ],
      runs: [{ id: '901', sha: 'c'.repeat(40) }],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      `검증 대상 커밋을 모르는 상태에서 취소했다.\n${r.calls.join('\n')}`,
    )
  })

  test('★[skip ci] 커밋 자신에 run 이 붙어 있으면 그것도 보호한다', () => {
    // `[skip ci]` 는 push·PR 트리거만 막는다. 수동 dispatch 등으로 그 sha 에 run 이 생길 수
    // 있고, 그 run 도 **지금 main 에 있는 내용**을 검증 중이다. 보호 집합을 effective head
    // 하나로 좁히면 여기서 red 가 나야 한다.
    const REGEN = 'a'.repeat(40)
    const MERGE = 'b'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: 'chore: dashboard regen [skip ci]' },
        { sha: MERGE, message: 'feat: 뭔가 (#377)' },
      ],
      runs: [{ id: '901', sha: REGEN }],
    })
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      `HEAD 자신의 run 을 취소했다 — 그것도 현재 main 내용을 검증 중이다.\n${r.calls.join('\n')}`,
    )
  })
  ```

**GREEN**. 코드 변경 없음 (Task 1 의 구현이 이미 만족한다). 만족하지 못하면 Task 1 로 되돌아간다.

**REFACTOR**. 없음.

**검증**.
```bash
node --experimental-strip-types --test scripts/workflow/merged-pr-run-cleanup.test.ts
```
기대. **19 pass / 0 fail**.

---

### Task 3. 호출부 문서(`bts-merge` Step 5) 를 넓어진 계약에 맞춰 정정하고, 그 사실을 판별식으로 고정한다

**메타**.
- agent: `backend-engineer`
- files: [`.claude/skills/bts-merge/SKILL.md`, `scripts/workflow/merged-pr-run-cleanup.test.ts`]
- depends-on: [1]

**RED**.
- 파일. `scripts/workflow/merged-pr-run-cleanup.test.ts`
- 기존 케이스 `★계약이 좁아진 사실이 호출부 주석에 반영돼 있다 (문서 drift)` **아래**에 추가.

  ```ts
  test('★★호출부 주석이 「현재 HEAD 무접촉」이라고 말하지 않는다 (문서 drift)', () => {
    // 계약이 「현재 HEAD」에서 「현재 main 내용(= [skip ci] 를 되감은 구간)」으로 넓어졌다.
    // 옛 문구가 남으면 다음 사람이 이 PR 을 되돌린다 — 이 저장소가 여러 번 겪은 양식.
    const skill = fs.readFileSync(MERGE_SKILL, 'utf-8')
    assert.ok(
      !/현재 HEAD 무접촉/.test(skill),
      '.claude/skills/bts-merge/SKILL.md 가 아직 계약을 「현재 HEAD 무접촉」으로 설명한다 — ' +
        '넓어진 계약을 같은 커밋에서 반영해야 한다.',
    )
    assert.ok(
      /skip ci/.test(skill),
      '.claude/skills/bts-merge/SKILL.md 가 `[skip ci]` 되감기를 언급하지 않는다 — ' +
        '호출자가 이 가드의 실제 판정 기준을 알 수 없다.',
    )
  })
  ```

- 실패 메시지 (예상). `AssertionError: ... 아직 계약을 「현재 HEAD 무접촉」으로 설명한다`

**GREEN**.
- 파일. `.claude/skills/bts-merge/SKILL.md` — Step 5 의 `main` 인자 주석 블록에서
  「★`main` 인자는 「통째 무접촉」이 아니라 「현재 HEAD 무접촉」이다」로 시작하는 3줄을
  아래로 교체한다.

  ```
  #   ★`main` 인자는 「통째 무접촉」이 아니라 「현재 main 내용 무접촉」이다. 방금 시작된
  #   push CI 는 건드리지 않고, 그보다 낡은 커밋의 run 만 취소한다. 이때 기준은 HEAD 가
  #   아니라 **HEAD 부터 거슬러 첫 non-`[skip ci]` 커밋**이다 — post-merge 훅이 머지 직후
  #   `[chore] dashboard regen [skip ci]` 를 push 해 HEAD 를 한 칸 밀기 때문이다(2026-08-12
  #   PR #376 실측). 검증 대상 커밋을 못 찾으면 아무것도 하지 않는다(fail-open).
  ```

- ★같은 파일 Step 3 의 「post-merge 훅이 `[chore] dashboard regen` 커밋을 푸시한다」 서술은
  그대로 둔다 — 사실이고, 이 PR 이 바꾸는 것은 가드 쪽이다.

**REFACTOR**. 없음.

**검증**.
```bash
node --experimental-strip-types --test scripts/workflow/merged-pr-run-cleanup.test.ts
```
기대. **20 pass / 0 fail**. 기존 배선 케이스 2건(`cancel-merged-pr-runs.sh main` 호출 ·
`보호 브랜치(main/master/HEAD)` 부재)도 그대로 초록이어야 한다.

---

### Task 4. 뮤테이션 4종을 각각 넣고 red 를 확인한다 (비-공허 짝 증명)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/cancel-merged-pr-runs.sh`]
- depends-on: [1, 2, 3]

**★선행 조건.** Task 1~3 의 GREEN 이 **커밋돼 있어야** `git checkout --` 원복이 성립한다
([[mutation-test-requires-committed-baseline]]). 미커밋 상태에서 훼손하면 원복 시 구현이 날아간다.

- [ ] **Step 1. 기준선 확인**

```bash
node --experimental-strip-types --test scripts/workflow/merged-pr-run-cleanup.test.ts
```
기대. **20 pass / 0 fail**. 여기가 초록이 아니면 아래 red 는 의미가 없다.

- [ ] **Step 2. M1 — skip-ci 판정 제거**

`has_skip_ci_token()` 본문의 `for` 루프를 지우고 `return 1` 만 남긴다 (= 어떤 메시지도
skip-ci 가 아니라고 본다 → effective head 가 항상 HEAD).

기대 red. `★★HEAD 가 [skip ci] 면 그 부모(...)의 run 을 취소하지 않는다`
원복. `git checkout -- scripts/cancel-merged-pr-runs.sh`

- [ ] **Step 3. M2 — 보호 집합을 effective head 하나로 축소**

`PROTECTED_SHAS="$PROTECTED_SHAS $sha"` 줄을 지우고, `EFFECTIVE_HEAD="$sha"` 직후에
`PROTECTED_SHAS=" $sha"` 를 넣는다.

기대 red. `★[skip ci] 커밋 자신에 run 이 붙어 있으면 그것도 보호한다`
원복. 위와 동일.

- [ ] **Step 4. M3 — 메시지 전체 대신 제목만 검사**

`--jq` 를 `'.[] | "\(.sha) \(.commit.message | split("\n")[0])"'` 로 바꾼다.

기대 red. `★★HEAD 가 [skip ci] 면 ...` (그 케이스의 `[skip ci]` 는 **본문**에 있다)
원복. 위와 동일.

- [ ] **Step 5. M4 — 검증 대상 미확인에서 fail-open 제거**

`if [ -z "$EFFECTIVE_HEAD" ]; then ... exit 0; fi` 블록을 지운다.

기대 red. `★훑은 구간이 전부 [skip ci] 면 아무것도 취소하지 않는다 (fail-open)`
원복. 위와 동일.

- [ ] **Step 6. 원복 확인 + 전체 스위트**

```bash
git diff --stat scripts/cancel-merged-pr-runs.sh   # 출력 없어야 함 (원복 완료)
node --experimental-strip-types --test scripts
```
기대. **전체 217 pass / 0 fail EXIT=0** (기준선 213 + 신규 4).

- [ ] **Step 7. 뮤테이션 결과표를 plan 파일 §뮤테이션 결과에 기록**

각 뮤테이션이 **어느 케이스**에 잡혔는지 적는다. 「전부 red」만 적으면 한 케이스가 4종을
전부 잡는 경우(= 나머지 3 케이스가 공허)를 구분할 수 없다.

---

## Plan 메타

- task 수: 4
- 예상 시간: 약 15분 (직렬 — files 교집합이 커 병렬 wave 없음)
- 구현 규율: TDD (red-first 강제). Task 2 는 예외 — red 를 뮤테이션(Task 4)이 담당하고,
  그 사실을 plan 에 명시했다
- 병렬 dispatch: 없음. T1 → (T2 · T3) → T4
- 추가 검증: 전체 `node --experimental-strip-types --test scripts` · `verify-master-plan.sh`
- FR 영향: **없음** (139 불변). 프로덕션 코드 0줄, 백엔드 0줄, `apps/web` 0파일

## 뮤테이션 결과

기준선 **22 pass / 0 fail EXIT=0**. 각 뮤테이션은 하나씩 넣고 돌린 뒤
`git checkout -- scripts/cancel-merged-pr-runs.sh` 로 원복했다 (마지막에 `git diff --stat` 무출력 확인).

| 뮤테이션 | 결과 | 잡은 케이스 |
|---|---|---|
| M1. `has_skip_ci_token` 을 항상 `return 1` 로 | **RED** (fail 3) | HEAD-skip-ci 부모 보호 · 낡은 run 취소 · 전부-skip-ci fail-open |
| M2. 보호 집합을 검증 대상 커밋 하나로 축소 | **RED** (fail 1) | `[skip ci]` 커밋 자신의 run 보호 |
| M3. 검사 대상을 제목만으로 (`scanned="${message%%\n*}"`) | **RED** (fail 1) | HEAD-skip-ci 부모 보호 (그 케이스의 토큰이 **본문**에 있다) |
| M4. 검증 대상 미확인 시 fail-open 제거 | **RED** (fail 2) | 전부-skip-ci fail-open · HEAD 미확인 fail-open |
| M5. jq 정형을 `@json` → `split("\n")\|join(" ")` 으로 되돌림 | GREEN | — (아래 §M5 참조) |

**★네 뮤테이션을 각각 **다른** 케이스가 잡았다.** 한 케이스가 전부를 잡으면 나머지가 공허하다는
뜻인데, 그런 겹침이 없다. M1 이 3건을 동시에 깨는 것은 판정 자체를 제거했기 때문이고,
M2·M3 는 **서로 다른 단일 케이스**만 깬다 — 두 케이스가 각자 고유한 판별력을 가진다는 증거다.

### ★M3 는 처음에 살아남았다 — 그리고 그것이 이 PR 의 두 번째 산출물이다

초안은 개행 눕히기를 `--jq '... | split("\n") | join(" ")'` 안에 뒀다. 그 상태에서
「제목만 검사」 뮤테이션(당시 jq 를 `split("\n")[0]` 으로 훼손)이 **GREEN 으로 살아남았다.**

원인. 가짜 `gh` 는 jq 를 실제로 돌리지 않는다. **이미 눕혀진 줄을 뱉으므로 스크립트의 jq 를
어떻게 훼손해도 계약 테스트의 사정거리 밖**이었다. 이 파일 헤더가 이미 적어 둔 원칙
—「판정 로직은 스크립트 안에 있어야 한다」— 을 계획서가 스스로 어긴 것이다.
「메시지 전체를 보는가 제목만 보는가」는 정형이 아니라 **판정**이다.

처방. jq 는 `@json` 이스케이프까지만 하고(개행이 `\n` 으로 escape 된 한 줄), 검사 범위는
bash 의 `scanned="$message"` 한 줄이 정한다. 하네스도 실물과 같은 모양(`JSON.stringify`
+ 큰따옴표 문맥 이스케이프)으로 맞췄다. 그 뒤 M3 는 **RED** 가 됐다.

커밋. `d0a7964e3 fix: ... 「메시지 전체」 판정을 jq 에서 bash 로 옮겨 측정 가능하게`

### §M5 — 살아남는 것이 정상이다 (동작 보존 변형)

`@json` 대신 `join(" ")` 으로 되돌려도 **동작은 같다** — 개행이 공백이 되어도 `[skip ci]` 는
그대로 문자열 안에 있으므로 토큰 탐색 결과가 바뀌지 않는다. 즉 M5 는 결함 주입이 아니라
**측정가능성만 잃는 변형**이고, 판별식이 재는 것은 동작이지 측정가능성이 아니다.
그래서 red 를 요구하지 않고, 대신 그 자리를 스크립트 주석으로 못 박았다
(「한 번 틀렸던 자리다」 블록). 이 항목을 표에 남기는 이유는 **다음 사람이 같은 되돌림을
「무해하니 괜찮다」로 판단할 때 그 판단이 옳다는 근거와, 그럼에도 옮기지 말아야 할 이유를
함께 보게 하기 위해서**다.

## 계획 대비 실제 (deviation)

| 계획 | 실제 | 사유 |
|---|---|---|
| Task 3 의 둘째 단언을 `/skip ci/` 로 | `/되감/` 으로 교체 | `skip ci` 는 SKILL.md Step 3 의 수동 폴백 커맨드에 **이미 있어** 무엇을 고치든 통과하는 **공허한 단언**이 된다 (착수 전 grep 으로 확인) |
| 뮤테이션 4종 | 5종 (M5 추가) | M3 을 bash 쪽으로 옮기면서 「jq 로 다시 새는」 변형을 별도로 기록할 필요가 생겼다 |
| M3 = jq 훼손 | M3 = bash 훼손 | 위 §M3 참조. jq 훼손은 원리적으로 측정 불가 |
| 커밋 5개 (test/feat/refactor ×) | 7개 | 계획에 없던 `docs:`(plan 등재 + 인덱스 재생성)와 `fix:`(M3 처방) 2건 추가 |


## 리뷰 결과 (← /bts-review-plan 채움)

_fast-track (chore) — 생략. 게이트 2 의 /bts-codereview 는 그대로 실행한다._
