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

## Plan

### 설계 결정 (task 앞에 확정한 것)

**D1 — 스크럽 헬퍼는 신규 모듈 `scripts/workflow/git-fixture-env.ts` 다.**
두 픽스처 테스트에 각자 스크럽을 복붙하면 그 둘이 서로를 검사하지 않는다. 공유 모듈이어야
**R4 의 소스 재계산이 「헬퍼를 임포트했는가」라는 하나의 신호**로 성립한다.
`scripts/workflow/*.{ts,mjs}` 는 이미 `GUARD_CI` 표면이라 티어가 안 바뀐다.

**D2 — 지우는 형태는 접두 스윕 한 줄이다.**

```sh
unset $(env | sed -n 's/^\(GIT_[A-Za-z0-9_]*\)=.*/\1/p')
```

루프를 안 쓴다 — `for` 는 `BLOCK_OPEN` 이라 기존 파서의 블록 깊이 판정과 얽힌다. 한 줄이면
**깊이 0 · 가드 연산자 없음**이 자명하다. `GIT_*` 가 하나도 없으면 `unset` 이 인자 없이 불려
무해한 no-op 이다(E7).

**D3 — R4 는 「파생 집합 ↔ 선언 집합」 양방향 대조다.**
`init`/`clone` 문자열만 찾으면 읽기 전용 파일이 우연히 그 문자열을 가질 때 오탐이 난다.
대신 **소스에서 파생**한 「git 을 spawn 하는 파일 전량」이
**(헬퍼 임포트 파일) ∪ (READ_ONLY 선언)** 과 양방향으로 같은지 본다.
새 파일이 어느 쪽에도 없으면 red, 선언에만 있고 실재하지 않아도 red.
그리고 **READ_ONLY 로 선언된 파일이 `init`·`clone` 을 갖게 되면 red** — 이 조항이 선언의
비-공허성을 지킨다(선언이 「읽기 전용」이라는 주장의 반증 가능성).

**D4 — 판정의 집을 둘로 나눈다.**
`discriminant-hook-wiring.test.ts` 는 **훅 배선**이 관심사다(R1·R2). 여기에 얹어 `HOOK` 상수와
블록 깊이·가드 연산자 파서를 재사용한다 — 사본을 만들면 두 벌이 갈린다.
**픽스처 격리**(R5·R6·R7)와 소스 재계산(R3·R4)은 관심사가 달라 신규
`scripts/workflow/git-fixture-isolation.test.ts` 로 분리한다. 300줄 상한 규율(부채 65)도
216줄짜리 기존 파일에 전부 얹지 말라고 말한다.

**E7 (스펙 엣지 케이스 추가).** `GIT_*` 가 환경에 하나도 없는 경우 — `unset` 이 인자 없이 불린다.
POSIX 상 무해하고 종료 코드 0 이다. 훅이 셸에서 직접 실행될 때(= `GIT_DIR` 부재)가 이 경우다.

---

### Task 1. 픽스처 격리를 실측하는 판별식과 스크럽 헬퍼

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/git-fixture-env.ts`, `scripts/workflow/git-fixture-isolation.test.ts`]
- depends-on: []

**RED**:
- 파일: `scripts/workflow/git-fixture-isolation.test.ts` (신규)
- 테스트 3종.
  ```ts
  // ① 판정 — 스크럽 env 로 픽스처를 만들면 victim 이 안 바뀐다
  test('GIT_DIR 가 걸려 있어도 픽스처가 진짜 저장소를 안 바꾼다', ...)
  // ② 비-공허 짝 — 스크럽을 끄면 victim 이 실제로 바뀐다
  test('★★스크럽을 끄면 실제로 오염된다 (비-공허 짝)', ...)
  // ③ 자기 함정 — 이 판별식이 REPO_ROOT 를 대상으로 삼지 않는다
  test('★victim 은 언제나 mkdtemp 아래이고 REPO_ROOT 가 아니다', ...)
  ```
- 판정 방법. victim 은 `fs.mkdtempSync(os.tmpdir())` 아래에 **스크럽된 env 로** 만든다.
  그 다음 `GIT_DIR=<victim>/.git` 를 건 채 픽스처 절차(`init`→`add`→`commit`)를 돌리고
  victim 의 **커밋 수 · `core.bare` 유무 · HEAD** 를 전후 비교한다.
- 실패 메시지 (예상): `Cannot find module '.../git-fixture-env.ts'`

**GREEN**:
- 파일: `scripts/workflow/git-fixture-env.ts` (신규)
- 최소 구현. `process.env` 사본에서 `GIT_` 로 시작하는 키를 전량 삭제해 돌려주는 함수 하나.
  **열거하지 않는다**(N1) — 접두로 판정한다.

**REFACTOR**:
- 파일 첫 줄에 역할 한국어 주석 1줄. 접두 문자열을 상수로.

**검증**: `node --experimental-strip-types --test scripts/workflow/git-fixture-isolation.test.ts`
→ 3종 pass · fail 0

---

### Task 2. git 을 부르는 파일 전량이 헬퍼 경유이거나 읽기 전용 선언이다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/git-fixture-isolation.test.ts`, `scripts/workflow/select-backend-modules.test.ts`, `scripts/workflow/todos-reorder-integrity.test.ts`]
- depends-on: [1]

**RED**:
- 파일: `scripts/workflow/git-fixture-isolation.test.ts` (판정 추가)
- 테스트.
  ```ts
  test('★★git 을 spawn 하는 파일 전량이 헬퍼 경유이거나 READ_ONLY 로 선언돼 있다 (양방향)', ...)
  test('★READ_ONLY 선언 파일이 init·clone 을 갖게 되면 red (선언의 비-공허성)', ...)
  test('READ_ONLY 선언 파일이 전부 실재한다 (선언 부패 차단)', ...)
  ```
- 파생 집합. `scripts/**/*.{ts,mjs}` 소스에서 `spawnSync('git'` · `execFileSync('git'` ·
  `execSync('git` 을 부르는 파일을 **계산**한다. 사람이 적은 목록을 쓰지 않는다(D3).
- 실패 메시지 (예상): 두 픽스처 생성자가 헬퍼 임포트도 READ_ONLY 선언도 아니라서 차집합 2건.

**GREEN**:
- `select-backend-modules.test.ts:700~715` 의 `spawnSync('git', args, { cwd: tmp, env: { ...process.env, HOME: tmp } })`
  를 헬퍼 경유로 바꾼다. **`HOME: tmp` 는 유지**한다 — 전역 git config 격리 목적이라 이번 결함과 무관하다.
- `todos-reorder-integrity.test.ts:328~337` 의 `execFileSync('git', args, { cwd: tmp, ... })` 에
  스크럽된 `env` 를 넘긴다.
- 읽기 전용 6곳을 `READ_ONLY` 로 선언하고 **각 1줄 사유**를 붙인다 —
  `tier-floor.test.ts`(`ls-files`·`log`·`merge-base`) · `transition-term-guard.test.ts`(`ls-files`) ·
  `snapshot-baseline-guard.test.ts`(`ls-files`) · `changed-paths.ts` · `push-backend-tests.ts` ·
  `todos-reorder-integrity.mjs`. 이들은 **진짜 저장소를 보는 게 정상 동작**이다(E2).

**REFACTOR**:
- 파생 집합 계산과 선언 대조를 각각 함수로 분리. 실패 메시지가 **차집합 양쪽을 전량 열거**하게 한다
  (개수를 안 적는다).

**검증**: `node --experimental-strip-types --test scripts/workflow/git-fixture-isolation.test.ts scripts/workflow/select-backend-modules.test.ts scripts/workflow/todos-reorder-integrity.test.ts`

---

### Task 3. 훅이 판별식보다 먼저, 무조건으로 `GIT_*` 를 지운다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/discriminant-hook-wiring.test.ts`, `.husky/pre-push`]
- depends-on: []

**RED**:
- 파일: `scripts/workflow/discriminant-hook-wiring.test.ts` (판정 추가 — 기존 `HOOK` 상수와
  `BLOCK_OPEN`/`BLOCK_CLOSE`/`GUARD_OPERATORS` 파서를 **재사용**한다)
- 테스트.
  ```ts
  test('★푸시 훅이 판별식 전에 GIT_* 를 지운다', ...)
  test('★그 스크럽이 조건에 안 매달린다 (깊이 0 · 가드 연산자 없음)', ...)
  test('주석이 아니라 실행 줄을 본다 (산문 오탐 방지)', ...)
  ```
- 판정. 실행 줄만 훑어 ① `GIT_` 접두를 지우는 구문이 있고 ② 그 줄의 블록 깊이가 0 이며
  ③ `&&`·`||` 로 앞 명령에 안 붙고 ④ **첫 판별식 호출 줄보다 앞**인지 본다.
- 실패 메시지 (예상): `.husky/pre-push` 에 `GIT_*` 스크럽이 없다.

**GREEN**:
- 파일: `.husky/pre-push`
- D2 의 한 줄을 판별식 호출 **앞**에 넣는다. 왜 지우는지를 기존 주석 관례대로 위에 적되,
  **주석이 판정을 대신 만족시키지 못하게** 판정은 실행 줄만 본다.

**REFACTOR**:
- 파일이 300줄 상한(부채 65)에 걸리면 판정 묶음을 분리하고 그 판단 근거를 커밋 메시지에 남긴다.
  현재 216줄이므로 여유를 실측해 결정한다.

**검증**: `node --experimental-strip-types --test scripts/workflow/discriminant-hook-wiring.test.ts`

---

### Task 4. 각 신규 판정을 일부러 끊어 red 를 1회 관측한다

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-08-21-pre-push-git-dir.md`]
- depends-on: [1, 2, 3]

**RED/GREEN 없음 — 관측 task 다.**

**★순서 규율.** 뮤테이션 검증은 **GREEN 을 먼저 커밋한 뒤** 한다. 미커밋 상태에서 끊었다가
되돌리면 그 되돌림이 소실이다(저장소 함정 목록).

**관측 항목** — 각각 끊고 red 를 본 뒤 되돌린다.

| # | 끊는 것 | 기대 red |
|---|---|---|
| M1 | `.husky/pre-push` 의 스크럽 줄 삭제 | Task 3 의 R1 판정 |
| M2 | 스크럽 줄을 판별식 호출 **뒤로** 이동 | Task 3 의 순서 판정 |
| M3 | 스크럽 줄을 `&&` 로 앞 명령에 붙임 | Task 3 의 R2 판정 |
| M4 | `git-fixture-env.ts` 의 삭제 로직을 no-op 으로 | Task 1 의 판정 ① |
| M5 | 두 픽스처 중 하나를 헬퍼 미경유로 되돌림 | Task 2 의 양방향 판정 |
| M6 | `READ_ONLY` 선언 하나를 삭제 | Task 2 의 양방향 판정 |

**산출물**. plan 파일 `## red 관측` 절에 M1~M6 각 1줄 — 끊은 것 · 실제 실패 메시지 · 되돌림 확인.

**검증**: 관측 6건 전부 red 확인 후 원복. 원복 뒤 `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` 가 초록.

---

### Task 5. 전량 검증 + `--no-verify` 없이 실제 푸시해 오염 0 을 실측한다

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-08-21-pre-push-git-dir.md`]
- depends-on: [4]

**RED/GREEN 없음 — 최종 판별 task 다.**

**5-1. 전량 검증** (각 명령을 **개별 로그로** 돌린다 — 배경 묶음의 종료 코드는 마지막 명령 것이라
중간 실패를 가린다).

| 검사 | 명령 | 기준 |
|---|---|---|
| 판별식 | `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` | fail 0 · skipped 0 · EXIT=0 |
| 정본 정합 | `bash scripts/verify-master-plan.sh` | EXIT=0 · FR 143/143 |
| 문서 인덱스 | `node scripts/build-doc-index.mjs --check` | drift 0 · EXIT=0 |

**5-2. C7 — 진짜 판별자.** 여기서만 `--no-verify` 를 뺀다.

푸시 **전** 측정 → `git push` (플래그 없이) → 푸시 **후** 측정. 네 값이 전부 같아야 한다.

```
git ls-files | wc -l                 # 건수 불변
git config --get core.bare           # 전후 모두 부재
git rev-parse HEAD                   # 불변 (푸시는 로컬 ref 를 안 바꾼다)
git status --porcelain | wc -l       # 전후 모두 0
```

**★훅이 실제로 돌았는지 눈으로 확인한다.** worktree 가 husky 를 침묵 무력화하는 함정이 있어
「깨끗함」이 「훅이 안 돌아서 깨끗함」일 수 있다 — 훅 출력(판별식 tests/pass 줄)이 푸시 로그에
보이는지 확인한다. 안 보이면 이 검증은 **공허하다**.

**산출물**. plan 파일 `## C7 실측` 절에 전후 4값 표 + 훅 실행 증거 1줄.

**검증**: 위 표의 4값 전후 일치 + 훅 출력 관측.

## Plan 메타

- **task 수**. 5 (Task 1~3 은 TDD 사이클 · Task 4~5 는 관측·검증)
- **예상 wave**. 4
  - wave 1. Task 1 · Task 3 (파일 교집합 0 — 병렬)
  - wave 2. Task 2 (Task 1 의 헬퍼 API 에 의존)
  - wave 3. Task 4 (1·2·3 전부 GREEN 커밋된 뒤)
  - wave 4. Task 5
- **구현 규율**. TDD red-first. T2 이므로 `test:` → `feat:` 커밋 순서가 대조된다.
  Task 4·5 는 사이클이 아니므로 `docs:` 커밋.
- **추가 검증**. typecheck·ktlint·detekt·vitest·playwright **해당 없음** — 프로덕션 Kotlin 0줄 ·
  `apps/web` 0파일이다. 판별식·정본 정합·문서 인덱스가 이 작업의 전량 검증이다.
- **병렬 상한**. 동시 dispatch 2건 (wave 1). 스왑 압박 이력 때문에 3건을 안 넘긴다.
- **`--no-verify` 규율**. Task 5 의 C7 을 제외한 이 체인의 모든 푸시는 `--no-verify` 다.
  고치려는 훅이 푸시마다 저장소를 깨뜨리기 때문이다.

## 리뷰 결과

**렌즈**. `/plan-eng-review` 1종 (`type == chore` → 분기 표의 `{bugfix, chore, qa}` 행).
**발행 못 한 렌즈 없음.** outside voice(codex)는 이 환경에 없어 돌지 않았다 — 조용히 줄이지 않고 여기 적는다.

**판정. BLOCKER 2 · 주의 6 · 통과 항목 다수.**

### 이미 있는 것 (재발명 금지)

`scripts/workflow/discriminant-hook-wiring.test.ts` 가 이번에 필요한 파서를 **이미 전부** 갖고 있다 —
`HOOK` 상수 · `BLOCK_OPEN`/`BLOCK_CLOSE`(셸 블록 깊이) · `GUARD_OPERATORS`(`&&`·`||`) ·
「판별식이 합성 위반을 실제로 잡아낸다(양성 대조군)」 · 「주석이 아니라 실행 줄을 본다」.
plan 의 D4 가 이 파일에 얹기로 한 것은 옳다. 새로 파서를 쓰면 두 벌이 갈린다.

### 🚫 BLOCKER 1 — `READ_ONLY` 는 세 번째 목록이고, 무엇보다 **탈출구**다

D3 의 대조는 `파생집합 D == (헬퍼 임포트 H) ∪ (READ_ONLY R)` 다.
`D` 와 `H` 는 소스에서 계산되지만 **`R` 만 사람이 적는다.**

양방향이라 `R` 이 *조용히* 썩지는 않는다(없는 파일·git 을 더 안 부르는 파일은 red).
문제는 다른 데 있다 — **`R` 은 red 를 끄는 가장 싼 방법이다.**
다음 사람이 픽스처 테스트를 새로 쓰고 이 판정이 red 가 되면, 헬퍼로 배선하는 것보다
`R` 에 파일 이름 한 줄 얹는 게 빠르다. 그리고 그 순간 이 PR 이 막으려던 결함이 되살아난다.

`R` 의 의미(「이 파일은 읽기만 한다」)를 지키는 유일한 장치가 plan 의
「`R` 파일이 `init`·`clone` 을 가지면 red」인데, 이건 **소스 문자열 검사**다.
`git worktree add` · `git clone --bare` · `.git` 디렉터리 복사 · 변수에 담은 서브커맨드는
전부 이 검사를 통과한다. 이 저장소가 `invariant-satisfied-by-helptext-not-logic` 로 이름 붙인
계열이다.

**처방 — `R` 을 없앤다.** git 을 부르는 **전량**이 헬퍼를 거치게 한다. 읽기 전용 6곳도 스크럽해도
안전하다. 근거는 실측이다 — 8곳 전부 `cwd` 를 **명시적으로 넘기고**, `REPO_ROOT` 를
`import.meta.url` 로 계산한다. `GIT_*` 가 없으면 git 은 그 `cwd` 에서 저장소를 찾는다.
그러면 대조가 **`파생집합 == 파생집합`** 이 되어 **사람이 유지하는 목록이 0개**다.
더 강하면서 동시에 더 단순하다.

- 비용. 건드리는 파일이 2개 → 8개 (human ~1h / CC ~10분)
- 위험. 읽기 전용 6곳의 동작 변화 — 위 근거로 없다고 보나 각 파일 실행으로 확인해야 한다

### 🚫 BLOCKER 2 — 파생 집합 자체에 **비-공허 짝이 없다**

`D` 를 만드는 것은 소스 정규식이다. 그 정규식이 미래의 호출 형태를 놓치면
(`spawn('git')` · 변수에 담은 커맨드 · 래퍼 함수 경유) **`D` 가 조용히 줄어든다.**

지금 구조(`R` 있음)에서는 `D` 가 비면 `R` 과 어긋나 red 가 난다. 그러나 **BLOCKER 1 을 채택해
`R` 을 없애면 `D == H` 가 되고, 둘 다 비면 판정이 공허하게 통과한다.**
`discriminant-erases-its-own-evidence` 그 자체다.

**처방.** 파생 집합이 ① 비어 있지 않고 ② **알려진 픽스처 생성자 2곳을 경로로 실제 문다**를
단언한다. 그것이 이 스윕의 비-공허 짝이다.

참고 실측 — 서로 다른 정규식 2벌로 파생 집합을 재봤고 **같은 8파일**이 나왔다. 현재 집합이
안정적이라는 증거이지, 파서가 미래에도 안 놓친다는 증거는 아니다.

### ⚠️ 주의 1 — 뮤테이션 M1 이 판정을 분리하지 못한다

M1(스크럽 줄 삭제)은 Task 3 의 판정 **3종을 한꺼번에** red 로 만든다 — 존재·순서·비가드가
전부 「그 줄」을 대상으로 하기 때문이다. 그러면 「끊었더니 red」가 **어느 판정이 살아 있는지
증명하지 못한다.** 순서(M2)·비가드(M3)는 분리되지만 「존재」만 단독으로 죽이는 뮤테이션이 없다.

빠진 뮤테이션도 있다. M4 는 헬퍼를 통째 no-op 으로 만드는데, **현실적 회귀는 부분 스크럽**이다
(누군가 「`GIT_DIR` 만 지우면 되잖아」로 좁힌다). 그게 red 가 나야 N1(네임스페이스 전체)이
실제로 지켜진다.

**처방.** ① 관측 기록이 **각 뮤테이션이 red 로 만든 판정 이름을 전량 열거**하게 한다
② `M7 — GIT_DIR 만 지우도록 좁힘` ③ `M8 — 파생 집합 정규식을 무력화` 를 추가한다.

### ⚠️ 주의 2 — C7 의 반증 장치가 약하고, 측정 범위가 좁다

「훅 출력을 눈으로 확인」은 기록이 안 남는다. 다음 사람이 재현할 수 없고, 안 봤어도 봤다고
적을 수 있다.

그리고 **측정 범위가 worktree 로컬뿐이다.** 실제 피해에는 메인 워크트리와 공유 config 가 포함됐다.

**처방.** ① 푸시 출력을 로그 파일로 **캡처**하고 판별식 요약 줄(`# pass`)과
`push-backend-tests` 출력이 그 로그에 **문자열로 존재하는지 단언**한다 — 없으면 이 검증은
무효라고 기록한다 ② 측정에 메인 워크트리를 추가한다
(`git -C /Users/maxi.moff/Projects/BTS status --porcelain` · `ls-files` 건수)
③ `git config --get core.bare` 는 **공유 config 를 읽으므로** worktree 에서 재도 유효하다 —
그 근거를 기록에 적는다.

### ⚠️ 주의 3 — `--no-verify` 구간이 무검증인데 plan 이 감당하지 않는다

Task 1~4 의 푸시는 기계 강제가 0이다. plan 은 그 사실을 적기만 하고 대체 장치를 안 둔다.

**처방.** ① 각 `--no-verify` 푸시 **직전에** 판별식 전량을 수동 실행하고 pass/fail/EXIT 를
기록하도록 규율에 명시 ② **Task 3 GREEN 이 커밋된 뒤에는 훅이 이미 안전하다** —
「Task 5 만 예외」는 과하다. Task 3 이후 푸시는 플래그를 뗄 수 있고, 그렇게 하면 C7 이
한 번이 아니라 여러 번 관측된다.

### ⚠️ 주의 4 — 300줄 결정을 착수 전에 못 박아야 한다

216줄 + 판정 3종. 이 저장소의 주석 밀도면 60~100줄이라 **상한 초과가 유력**하다.
REFACTOR 에 「넘으면 분리」로 미루면 구현 중 즉흥 결정이 되고, 더 나쁘게 —
**분리하면 `HOOK`·`BLOCK_OPEN`·`GUARD_OPERATORS`·실행줄 파서를 두 벌로 복제**하게 된다
(테스트 파일은 export 를 안 한다). 이 PR 이 없애려는 바로 그 양식이다.

**처방.** 「쉽게 만들고 나서 쉬운 변경을 한다」 순서로 간다 — 파서·상수를
`scripts/workflow/hook-source.ts` 로 **먼저 추출**하고, 판정은 기존 파일에 얹는다.
추출 자체가 기존 파일을 줄이므로 상한 문제도 함께 풀린다.

### ⚠️ 주의 5 — `.mjs` 가 `.ts` 헬퍼를 임포트하게 된다

`scripts/workflow/todos-reorder-integrity.mjs:201` 이 `execFileSync('git', ...)` 를 부른다.
BLOCKER 1 을 채택하면 이 `.mjs` 가 `.ts` 헬퍼를 임포트해야 한다. 판별식 러너는
`--experimental-strip-types` 로 도니 동작할 것으로 보이나 **실측 안 됐다.**
이 파일이 다른 경로로 실행되면 깨진다.

**처방.** 착수 첫 단계에서 실측한다. 안 되면 헬퍼를 `.mjs` 로 두거나 `.mjs`/`.ts` 양쪽
진입점을 만든다.

### ⚠️ 주의 6 — `infra/deploy/bts-deploy.sh` 에 같은 보험이 없다

배포 게이트가 **같은 판별식을 돌린다.** 지금은 셸에서 호출되니 노출 0 이지만,
훅·`git bisect run`·`git rebase --exec` 같은 경로로 불리면 같은 결함이 그대로 산다.
헬퍼 층(BLOCKER 1)이 덮기는 한다 — 이건 심층 방어의 한 줄짜리 보험이다.

**처방.** 같은 스크럽 한 줄을 배포 게이트 앞에도 넣는다. 비용 1줄.

### ✅ 통과한 것

- **D2 한 줄 스크럽.** `sh -e` 에서 GIT_* 있을 때 전량 제거·EXIT=0, 없을 때도 EXIT=0,
  값에 공백이 든 `GIT_SSH_COMMAND` 도 이름만 추출. 실측 완료.
- **N2(다른 동작을 안 깨뜨림).** `.husky/` 전체 · `push-backend-tests.ts` · `bts-deploy.sh` 의
  `GIT_` 언급이 전부 0건이고, 판별식 `REPO_ROOT` 는 전부 `import.meta.url` 계산이다.
- **D4 의 관심사 분리.** 훅 배선과 픽스처 격리를 다른 파일에 두는 판단은 옳다.
- **Task 4 를 별도 task 로 세운 것.** 뮤테이션을 GREEN 선커밋 뒤에 하는 순서 규율도 옳다.
- **성능.** 판별식 전량 약 5초. 스크럽은 `env` 1회 + `sed` 1회다. 픽스처 격리 판정이
  임시 저장소를 만들지만 기존 두 판별식이 이미 하던 것과 같은 규모다. N3 위협 없음.
- **아키텍처.** 새 서비스·의존성·배포 산출물 0. 폭발 반경은 `scripts/workflow` + `.husky` 안이다.

### 범위 밖 (NOT in scope)

| 항목 | 미루는 이유 |
|---|---|
| 훅 이외 경로(`git bisect run`·`git rebase --exec`·`git worktree add`)의 `GIT_DIR` 노출 전수 판별식 | 헬퍼 층이 실질적으로 덮는다. 별도 판별식은 표면이 불명확해 오탐이 크다 |
| 읽기 전용 6곳의 로직 변경 | 이 PR 은 **env 배선만** 바꾼다. 로직을 건드리면 리뷰 표면이 폭발한다 |
| 부채 65(파일 300줄 초과 실측 7건) 전반 해소 | 이 PR 은 자기가 만든 초과만 책임진다 |
| `core.bare` 오염 자동 감지·복구 스크립트 | 원인을 없애는 게 먼저다. 원인 제거 후에도 필요하면 별도 부채 |

### 실패 양식 (새 코드 경로별 프로덕션 실패 1종씩)

| 경로 | 실패 | 테스트 | 오류 처리 | 사용자에게 보이나 |
|---|---|---|---|---|
| 훅 스크럽 한 줄 | `sed` 부재·BSD/GNU 차이로 이름 추출 실패 → 스크럽 0개 | ❌ 없음 (배선만 잰다) | 없음 | **조용하다** |
| 헬퍼 | 접두 판정이 대소문자·`GIT` 정확 일치를 잘못 봐 일부만 지움 | ⚠️ M7 추가 시 잡힘 | 없음 | 조용하다 |
| 파생 집합 스윕 | 정규식이 새 호출 형태를 놓쳐 집합이 줌 | ⚠️ BLOCKER 2 채택 시 잡힘 | 없음 | 조용하다 |

**★ 첫 행이 critical gap 이다.** 스크럽 줄이 **문법적으로 존재하는데 실제로는 아무것도 안 지우는**
경우를 아무 판정도 안 잡는다 — 배선 판정은 「그 줄이 있는가」만 보기 때문이다.
`invariant-satisfied-by-helptext-not-logic` 의 정확한 재현이다.
**처방** — 훅 스크럽에도 **실측 짝**을 붙인다. `sh -e -c '<훅에서 뽑아낸 그 줄>; env | grep -c "^GIT_"'`
를 `GIT_*` 를 건 채 돌려 결과가 0 인지 본다. 즉 **훅 파일에서 실제로 읽어낸 문자열을 실행**한다.

### 병렬화 전략

wave 1 의 Task 1·Task 3 은 파일 교집합 0 이라 병렬 가능하다. 다만 BLOCKER 1·2 와
주의 4 를 반영하면 Task 3 이 「파서 추출」을 먼저 하게 되어 Task 1 과 여전히 독립이다.
동시 dispatch 2건 상한은 유지한다.

## GSTACK REVIEW REPORT

| Runs | Status | Findings |
|---|---|---|
| plan-eng-review (1/1) | COMPLETE | BLOCKER 2 · 주의 6 · 통과 6 |
| outside voice (codex) | NOT RUN | 이 환경에 codex 없음 — 조용히 생략하지 않고 기록 |

**주요 지적.**

| # | 등급 | 요지 |
|---|---|---|
| F1 | BLOCKER | `READ_ONLY` 가 사람이 적는 세 번째 목록이자 red 를 끄는 탈출구다. 없애고 git 호출 전량을 헬퍼 경유로 |
| F2 | BLOCKER | 파생 집합에 비-공허 짝이 없다. F1 채택 시 둘 다 비면 공허 통과 |
| F3 | 주의 | M1 이 판정 3종을 한꺼번에 끊어 분리 증명이 안 된다 · 부분 스크럽(M7)·파서 무력화(M8) 뮤테이션 누락 |
| F4 | 주의 | C7 이 눈확인에 의존하고 메인 워크트리를 안 잰다 |
| F5 | 주의 | `--no-verify` 구간 무검증 · Task 3 이후엔 플래그를 뗄 수 있다 |
| F6 | 주의 | 300줄 결정을 착수 전에. 파서를 `hook-source.ts` 로 먼저 추출 |
| F7 | 주의 | `.mjs` → `.ts` 헬퍼 임포트 가능성 미실측 |
| F8 | 주의 | `bts-deploy.sh` 에 같은 한 줄 보험 없음 |
| ★ | critical gap | 스크럽 줄이 **있는데 아무것도 안 지우는** 경우를 아무 판정도 안 잡는다 — 훅 스크럽에도 실측 짝 필요 |

**VERDICT. CHANGES REQUESTED** — BLOCKER 2건과 critical gap 1건은 plan 수정 없이 착수하면
이 PR 이 막으려는 결함 양식을 그대로 재생산한다.

**UNRESOLVED DECISIONS:**
- F1·F2·critical gap 을 반영해 plan 을 고칠지, 위험을 인정하고 그대로 갈지, 작업을 보류할지 — Maxi 판정 필요
