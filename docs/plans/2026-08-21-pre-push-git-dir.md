# pre-push 훅이 `GIT_DIR` 를 상속해 판별식이 저장소를 오염시킨다

> 티어: T2
> slug: pre-push-git-dir
> type: chore
> agent: backend-engineer
> 생성: 2026-08-21

## Brief

**FR 없음 — FR수 불변 143.** 프로덕션 Kotlin 0줄 · `apps/web` 0파일 · 마이그레이션 0 ·
신규 의존성 0. 하네스·가드 표면 전용 작업이다.

**사용자 원문.** 「pre-push 훅에서 판별식이 돌면 저장소가 오염된다. 고쳐라.」
PR #395 리베이스 뒤 푸시하다 실제로 당해서 발견했다.

### 실측 — 증상

`git push` 가 `.husky/pre-push` 를 깨우고, 그 안에서 판별식 전량이 돌면서 **작업 중이던
저장소를 파괴**했다.

| 확인한 것 | 결과 |
|---|---|
| 공유 `.git/config` | `core.bare = true` 가 박힘 → 메인 워크트리에서 `git status` 가 `must be run in a work tree` 로 죽음 |
| 브랜치 ref | 픽스처 커밋 3개가 얹힘 (`c0882cbfb` base → `d9aeba491` move → `3eaa83d23` base) |
| 인덱스 | 4,824 entries → **2 entries** (`TODOS.md`, `b/F.kt`) |
| 판별식 결과 | 셸 359/359 초록 → 훅에서 356/3 → 재실행 355/4 (오염이 누적돼 실패가 늘어남) |

복구는 `reflog` → `git update-ref` → `git read-tree --reset HEAD` → `core.bare` unset 으로 끝냈다.
**작업 트리 파일 자체는 안 다쳤다** — 깨진 것은 인덱스·ref·config 다.

### 실측 — 원인 연쇄

1. **git 은 훅 프로세스에 `GIT_DIR` 를 export 한다.**
2. 두 판별식의 git 픽스처가 **`cwd: tmp` 로만 격리하고 `GIT_DIR` 는 상속**한다.
   - `scripts/workflow/select-backend-modules.test.ts:700~715` — `spawnSync(..., { cwd: tmp, env: { ...process.env, HOME: tmp } })` · 커밋 `base`/`move` · `a/F.kt` → `b/F.kt`
   - `scripts/workflow/todos-reorder-integrity.test.ts:328~337` — `execFileSync(..., { cwd: tmp })` · 커밋 `base` · `TODOS.md`
   - 두 파일의 `GIT_DIR` 언급 **0건**. `.husky/` 전체도 **0건** (husky 래퍼가 안 지운다).
3. **`GIT_DIR` 가 `cwd` 를 이긴다.** 격리 저장소로 실측 — tmp 에서 `git init` 을 한 뒤에도
   `GIT_DIR` 를 걸면 `rev-parse --absolute-git-dir` 가 그쪽을 가리킨다.
4. 그래서 `git init` → 실저장소 config 에 `core.bare=true`, `git add -A` → 인덱스 파괴,
   `git commit` → 실브랜치 ref 에 픽스처 커밋.

**A/B 대조.** 같은 명령
(`node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`)이
일반 셸에서는 359/359 초록·오염 0, pre-push 훅에서는 저장소 오염.
**유일한 차이가 훅 컨텍스트(`GIT_DIR`)다.**

### 폭발 반경

- `eca4a9c7f`(2026-08-21 16:20)가 CI 자동 실행을 끄고 강제 지점을 `.husky/pre-push` 로 옮기면서
  들어왔다. 지금 **푸시 시점의 유일한 기계 강제 지점**인데, 그게 돌면 저장소가 깨진다.
- `.husky/post-merge` 가 훅 안에서 `git push` 를 부른다(대시보드 재생성 커밋).
  그 안쪽 푸시가 `pre-push` 를 깨우므로 **main 에 머지할 때마다 재현된다.**
- 지금은 `--no-verify` 로만 푸시가 가능하다. 즉 **강제 장치가 사실상 0**이다.

### 처방 방향 (확정 아님 — Plan 에서 판단)

① `.husky/pre-push` 에서 `GIT_DIR`·`GIT_WORK_TREE`·`GIT_INDEX_FILE`·`GIT_OBJECT_DIRECTORY`·
   `GIT_COMMON_DIR`·`GIT_NAMESPACE` 를 unset — 한 줄로 현재·미래 전 테스트의 폭발 반경을 막는다
② 두 테스트에서 개별 스크럽 — 방어 2중. 훅이 아닌 경로에서 새도 막힌다
③ red-first 판별식 — `GIT_DIR` 를 건 채 픽스처를 만들어 **진짜 저장소가 안 바뀐다**를 실측
   (비-공허 짝 포함) + `pre-push` 의 unset 배선 앵커.
   `discriminant-hook-wiring.test.ts` 의 `HOOK` 상수 관례를 따른다.

**주의 — 가드 수정이므로 일부러 끊어 red 를 1회 본다.** 그리고 판별식 자체가 `GIT_DIR` 를 새게
하면 안 되므로 이 테스트가 스스로의 함정에 빠지지 않는지 확인한다.

### 착수 시점 분류 기록

`classify-task.ts --title` 이 **`tier: T1` · `type: backend`** 를 냈다 — 제목에 경로 신호가 없어
기본값으로 떨어졌다. 경로 실측(`detect-tier.ts`)은 **`TIER: T2` · `SURFACES: TEST, GUARD_CI`**.
경로 실측을 정본으로 삼아 **T2 로 선언**한다. 이 오배정은 게이트 2 요약에 그대로 싣는다
(같은 계열 선례 — `docs/plans/2026-08-17-classify-task-misroute-3-44-33-45.md`).

## 도메인 정리

**BC 없음.** 하네스·가드 표면 전용이다 — `classify.primary_bc` 가 `null` 이고, 9개 BC 어디에도
속하지 않는다. 영향 엔티티 0 · 새 도메인 용어 0.

**표면.** `detect-tier.ts` 실측 `SURFACES: TEST, GUARD_CI`.
`surfaces.ts:112~120` 의 `GUARD_CI` 가 `.husky/**` 와 `scripts/workflow/*.{ts,mjs}` 를 함께 문다.

**관련 ADR: 없음.** `docs/decisions/` 134건을 `pre-push`·`husky`·`GIT_DIR` 로 훑어 걸린 1건
(`2026-05-22-frontend-logging-policy.md`)은 프론트 로깅 정책이고 훅 배선과 무관하다.
기존 결정과의 충돌 0건.

**기존 결정과의 관계.** `eca4a9c7f`(2026-08-21)가 「CI 자동 실행을 끄고 강제 지점을 푸시 훅으로
옮긴다」를 결정했다. 이 작업은 그 결정을 **뒤집지 않는다** — 그 결정이 만든 강제 지점이 실제로
강제를 하도록 고치는 것이다.

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1 — 정상 푸시 (지금 깨져 있는 것).**
Given 개발자가 worktree 에서 작업을 커밋했다
When `git push` 를 한다
Then pre-push 가 판별식 전량 + 바뀐 백엔드 모듈 테스트를 돌리고, **저장소 상태는 푸시 전과 같다**
(인덱스 항목 수 불변 · 브랜치 ref 에 새 커밋 없음 · `core.bare` 미설정).

**S2 — main 머지 뒤 자동 대시보드 푸시.**
Given `gh pr merge --squash` 로 main 에 머지했다
When `git pull` 이 `post-merge` 를 깨우고 그 안에서 `git push` 가 일어난다
Then 그 안쪽 푸시의 pre-push 도 S1 과 같은 성질을 갖는다 — 저장소가 안 깨진다.

**S3 — 새 픽스처 테스트가 추가된다.**
Given 누군가 `scripts/**/*.test.ts` 에 임시 git 저장소를 만드는 테스트를 새로 쓴다
When 그 테스트가 스크럽 헬퍼를 안 거치고 `git init` 을 부른다
Then 판별식이 **red** 가 되어 머지 전에 잡힌다.

### Jira 대조

생략 — 비-UI 표면이다.

### 기능 요구사항

**FR 없음 — FR수 불변 143.** 대신 이 작업의 요구사항을 R 로 적는다.

| # | 요구사항 |
|---|---|
| **R1** | `.husky/pre-push` 가 판별식을 부르기 **전에** `GIT_*` 환경변수 네임스페이스를 지운다 |
| **R2** | R1 의 스크럽은 **조건에 매달리지 않는다** — 셸 블록 깊이 0 · `&&`/`\|\|` 로 앞 명령에 안 붙는다 |
| **R3** | 임시 git 저장소를 만드는 테스트 전량이 **스크럽된 env** 로 `git` 을 부른다 |
| **R4** | R3 의 「전량」은 사람이 유지하는 목록이 아니라 **소스에서 재계산**된다 |
| **R5** | 격리가 실제로 유지되는지를 **실측**하는 판별식이 있다 — 배선 문자열만 재지 않는다 |
| **R6** | R5 의 판별식이 **비-공허 짝**을 갖는다 — 스크럽을 끄면 red 가 된다 |
| **R7** | R5 의 판별식 자신이 `GIT_DIR` 를 새게 하지 않는다 |

### 비기능 요구사항

| # | NFR |
|---|---|
| **N1** | 지울 변수를 **열거하지 않는다.** `GIT_*` 접두 전체를 쓴다 — 열거는 「git 이 넣는 목록」과 「우리가 지우는 목록」이라는 두 목록을 만들고, 둘은 서로를 검사하지 않는다 (`two-lists-never-check-each-other`) |
| **N2** | 스크럽이 훅의 **다른 동작을 안 깨뜨린다** |
| **N3** | 판별식 실행 시간이 눈에 띄게 늘지 않는다 (현재 전량 약 5초) |
| **N4** | 프로덕션 Kotlin 0줄 · `apps/web` 0파일 · 마이그레이션 0 · 신규 의존성 0 |

**N2 의 실측 근거 (착수 전 확인).**

| 확인 대상 | 결과 |
|---|---|
| `.husky/` 전체의 `GIT_` 언급 | **0건** — 래퍼(`.husky/_/h`)도 안 쓴다 |
| `scripts/workflow/push-backend-tests.ts` 의 `GIT_` 언급 | **0건** |
| `infra/deploy/bts-deploy.sh` 의 `GIT_` 언급 | **0건** |
| 판별식의 `REPO_ROOT` 계산 | 전부 `path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')` — **파일 위치 기준이라 git 환경변수에 안 기댄다** |

즉 `GIT_*` 를 지우면 git 이 `cwd` 로 저장소를 탐색하는데, pre-push 의 `cwd` 는 작업 트리
루트이므로 같은 저장소를 찾는다. **오히려 worktree 에서 더 정확하다.**

### API 인터페이스

해당 없음.

### 데이터 모델 변경

없음.

### 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| **E1** | `GIT_ASKPASS`·`GIT_SSH_COMMAND` 등 사용자 인증 설정도 함께 지워진다 | 무해하다. 실제 푸시는 **부모 git 프로세스**가 하고, 훅 스크립트는 node 테스트만 돌린다. unset 범위는 훅 셸과 그 자식뿐이다 |
| **E2** | 읽기 전용으로 진짜 저장소를 보는 판별식 6곳(`ls-files`·`log`·`merge-base`) | 스크럽 대상이 **아니다.** 그것들은 실저장소를 보는 게 정상 동작이고, `cwd` 탐색으로 같은 저장소를 찾는다 |
| **E3** | `git init` 이 아니라 `git clone`·`git worktree add` 로 픽스처를 만드는 테스트가 새로 생긴다 | R4 의 소스 재계산이 `init` 과 `clone` **둘 다** 신호로 본다. `worktree add` 는 실저장소를 대상으로 하므로 별개 — **알려진 한계로 남기고** 스펙에 적는다 |
| **E4** | 판별식이 자기가 만든 victim 을 진짜 저장소로 착각한다 | victim 은 항상 `mkdtemp` 아래다. `REPO_ROOT` 를 victim 으로 삼는 경로를 **테스트가 스스로 금지**한다 |
| **E5** | 훅에서 스크럽 줄만 지우고 판별식 호출은 남긴다 | R1·R2 앵커가 red |
| **E6** | 헬퍼는 남기고 두 테스트가 헬퍼를 안 쓰게 되돌린다 | R4 의 소스 재계산이 red |

### 제약 조건

- **`pnpm` 을 거치지 않는다.** `discriminant-hook-wiring.test.ts` 가 이미 강제한다 — 워크트리가
  붙어 있으면 pnpm 이 모듈 재설치를 시도하다 무-TTY 로 죽고, `CI=true` 로 뚫으면 워크트리의
  심볼릭이 가리키는 실체가 지워져 **옆 세션 작업이 함께 깨진다**.
- **가드 수정이다 — 일부러 끊어 red 를 1회 본다.** 표면을 없애면 판별자도 함께 사라지므로,
  각 새 판정마다 「끊었을 때 red」를 실제로 관측하고 그 사실을 plan 에 기록한다.
- **주석·도움말 문자열로 판정을 만족시키지 않는다.** `discriminant-hook-wiring.test.ts` 가 이미
  「주석이 아니라 실행 줄을 본다」를 갖고 있다. 새 판정도 같은 규율을 따른다
  (`invariant-satisfied-by-helptext-not-logic`).
- **개수를 안 적는다.** 이 문서에도, 판별식 메시지에도 「N건」을 쓰지 않는다 — 전수 열거·집합
  대조만 쓴다.

### 측정 가능한 완료 기준

| # | 기준 | 측정 방법 |
|---|---|---|
| **C1** | 훅이 판별식 호출 전에 무조건으로 `GIT_*` 를 지운다 | `discriminant-hook-wiring.test.ts` 신규 판정 green |
| **C2** | `git init`/`clone` 을 부르는 테스트 전량이 스크럽 헬퍼 경유 | 소스 재계산 판정 green |
| **C3** | `GIT_DIR` 를 건 채 픽스처를 돌려도 victim 이 안 바뀐다 | 신규 실측 판정 green |
| **C4** | C3 의 비-공허 짝 — 스크럽을 끄면 victim 이 실제로 바뀐다 | 짝 판정 green |
| **C5** | 각 신규 판정이 끊었을 때 red 였다 | plan 에 red 관측 기록 |
| **C6** | 판별식 전량 초록 | `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` · fail 0 · skipped 0 · EXIT=0 |
| **C7** | **최종 판별자 — `--no-verify` 없이 실제로 푸시해서 저장소 오염 0** | 푸시 전후 `git ls-files \| wc -l` 동일 · `git config --get core.bare` 부재 · `git log --oneline -1` 불변 · `git status --porcelain` 0건 |

**C7 이 이 작업의 진짜 완료 기준이다.** 나머지는 그것을 지속시키는 장치다.

## Sanity Check

**❓ 발견 1 — 「어느 변수를 지우나」를 스펙이 열거할 뻔했다.**
착수 지시는 `GIT_DIR`·`GIT_WORK_TREE`·`GIT_INDEX_FILE`·`GIT_OBJECT_DIRECTORY`·`GIT_COMMON_DIR`·
`GIT_NAMESPACE` 를 후보로 줬고, 「git 이 실제로 넣는 변수 전량을 실측하라」고 했다.
그 실측(훅에 env 덤프 설치)은 이 환경의 도구 정책에 세 번 막혔다.
**그런데 실측이 됐더라도 열거는 틀린 처방이다** — 그 목록은 git 버전에 따라 변하고,
우리 목록과 git 의 목록은 서로를 검사하지 않는다. 이 저장소가 이미 이름 붙인 지배 결함 양식이다.
→ **N1 로 승격**해 접두 전체 스크럽으로 확정했다. 실측 불가가 설계를 더 낫게 만들었다.

**❓ 발견 2 — 픽스처 생성자가 2곳이라는 전제가 좁았다.**
착수 지시는 두 파일을 지목했다. 실측해 보니 `git` 을 부르는 판별식은 **8곳**이고,
그중 `git init` 을 부르는(= 픽스처를 만드는) 곳이 2곳, 나머지 6곳은 읽기 전용이었다.
→ **E2 로 분리**하고, R3 의 대상을 「지목된 2곳」이 아니라 「`git init`/`clone` 을 부르는 전량」으로
바꿨다. R4(소스 재계산)가 이 구분을 사람 손에서 뺏는다.

**❓ 발견 3 — 판별식이 스스로의 함정에 빠질 수 있다.**
격리를 실측하려면 이 판별식 자신이 `git init` 을 부른다. 그 순간 자기도 `GIT_DIR` 를 상속하면
진짜 저장소를 깨뜨린다 — **고치려는 결함을 고치는 코드가 저지른다.**
→ **R7·E4 로 명시**했다. victim 은 항상 `mkdtemp` 아래이고, 판별식이 `REPO_ROOT` 를 대상으로
삼는 경로를 스스로 금지한다.

**함정 대조 (이 저장소가 이미 이름 붙인 것).**

| 함정 | 이 스펙이 걸리는가 |
|---|---|
| `two-lists-never-check-each-other` | **N1**(열거 금지) · **R4**(소스 재계산)로 차단 |
| `invariant-satisfied-by-helptext-not-logic` | 제약 조건에 명시 · 기존 「주석이 아니라 실행 줄을 본다」 규율 승계 |
| `unreachable-state-fixture-is-fake-green` | **C4** 비-공허 짝이 「스크럽을 끄면 실제로 오염된다」를 실측하므로 도달 가능한 조합만 지킨다 |
| 가드 수정 시 표면 소실 | **C5** 가 각 판정의 red 1회 관측을 요구 |
| `partial-column-parser-lets-unread-column-rot` | R4 가 소스 전량을 읽는다 — 일부만 읽는 파서를 안 쓴다 |

**Sanity Check ✅ 통과** — gap 3건 발견, 전부 스펙에 반영(N1 승격 · E2 분리 · R7/E4 명시).
Maxi 결정이 필요한 항목 0건.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
