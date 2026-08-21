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
| **R3** | `git` 을 spawn 하는 파일 **전량**이 스크럽된 env 로 부른다. 예외 선언 목록을 두지 않는다 |
| **R4** | R3 의 「전량」은 사람이 유지하는 목록이 아니라 **소스에서 재계산**된다 |
| **R5** | 격리가 실제로 유지되는지를 **실측**하는 판별식이 있다 — 배선 문자열만 재지 않는다 |
| **R6** | R5 가 **비-공허 짝**을 갖는다 — 스크럽을 끄면 red 가 된다 |
| **R7** | R5 의 판별식 자신이 `GIT_DIR` 를 새게 하지 않는다 |
| **R8** | 훅 스크럽이 **문법적 존재가 아니라 실효로** 판정된다 — 훅 파일에서 읽어낸 그 줄을 실제로 실행해 `GIT_*` 가 0개 남는지 잰다 |
| **R9** | R4 의 파생 집합 자체가 **비-공허 짝**을 갖는다 — 집합이 비어 있지 않고 알려진 픽스처 생성자를 실제로 문다 |

**★ R3 에서 예외 목록을 지운 이유 (리뷰 F1).** 종전 안은 「헬퍼 경유 ∪ READ_ONLY 선언」과
대조했다. 그러나 `READ_ONLY` 만 사람이 적는 목록이고, **red 를 끄는 가장 싼 방법**이 된다 —
새 픽스처 테스트로 red 가 나면 헬퍼 배선보다 그 목록에 한 줄 얹는 게 빠르다.
그 목록의 의미를 지키는 유일한 장치가 `init`·`clone` **문자열 검사**인데
`git worktree add` · `git clone --bare` · 변수에 담은 서브커맨드가 전부 빠져나간다
(`invariant-satisfied-by-helptext-not-logic` 계열).
전량을 헬퍼로 보내면 대조가 **파생집합 == 파생집합**이 되어 사람 목록이 0개다.

**읽기 전용 호출도 스크럽이 안전한 근거 (실측).** `git` 을 부르는 전량이 `cwd` 를 **명시적으로
넘기고**, `REPO_ROOT` 를 `path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')`
로 계산한다. `GIT_*` 가 없으면 git 은 그 `cwd` 에서 저장소를 찾으므로 같은 저장소다.
worktree 에서는 오히려 더 정확하다.

**★ R9 가 필요한 이유 (리뷰 F2).** 예외 목록을 없애면 대조가 `파생집합 == 헬퍼임포트집합` 이
된다. 파생 집합을 만드는 소스 정규식이 미래의 호출 형태를 놓쳐 집합이 비면 **`빈집합 == 빈집합`
으로 조용히 통과**한다 — `discriminant-erases-its-own-evidence` 그 자체다.

### 비기능 요구사항

| # | NFR |
|---|---|
| **N1** | 지울 변수를 **열거하지 않는다.** `GIT_*` 접두 전체를 쓴다 — 열거는 「git 이 넣는 목록」과 「우리가 지우는 목록」이라는 두 목록을 만들고, 둘은 서로를 검사하지 않는다 (`two-lists-never-check-each-other`) |
| **N2** | 스크럽이 훅의 **다른 동작을 안 깨뜨린다** |
| **N3** | 판별식 실행 시간이 눈에 띄게 늘지 않는다 (현재 전량 약 5초) |
| **N4** | 프로덕션 Kotlin 0줄 · `apps/web` 0파일 · 마이그레이션 0 · 신규 의존성 0 |
| **N5** | 판정 파서·상수를 **한 벌만** 둔다. 파일이 나뉘어도 복제하지 않는다 |

**N2 의 실측 근거 (착수 전 확인).**

| 확인 대상 | 결과 |
|---|---|
| `.husky/` 전체의 `GIT_` 언급 | **0건** — 래퍼(`.husky/_/h`)도 안 쓴다 |
| `scripts/workflow/push-backend-tests.ts` 의 `GIT_` 언급 | **0건** |
| `infra/deploy/bts-deploy.sh` 의 `GIT_` 언급 | **0건** |
| `scripts/build-dashboard.mjs` · `build-doc-index.mjs` 의 git 호출 | **0건** |
| 판별식의 `REPO_ROOT` 계산 | 전부 `import.meta.url` 기준 — git 환경변수에 안 기댄다 |
| D2 한 줄 스크럽을 `sh -e` 에서 실행 | `GIT_*` 있을 때 전량 제거·EXIT=0 · 하나도 없을 때(인자 없는 `unset`)도 EXIT=0 · 값에 공백이 든 `GIT_SSH_COMMAND` 도 이름만 추출 |

### API 인터페이스

해당 없음.

### 데이터 모델 변경

없음.

### 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| **E1** | `GIT_ASKPASS`·`GIT_SSH_COMMAND` 등 사용자 인증 설정도 함께 지워진다 | 무해하다. 실제 푸시는 **부모 git 프로세스**가 하고, 훅 스크립트는 node 테스트만 돌린다. unset 범위는 훅 셸과 그 자식뿐이다 |
| **E2** | 읽기 전용으로 진짜 저장소를 보는 호출(`ls-files`·`log`·`merge-base`) | **스크럽 대상이다**(R3). `cwd` 탐색으로 같은 저장소를 찾으므로 동작이 같고, 예외를 두면 그 예외가 탈출구가 된다 |
| **E3** | `git init` 이 아니라 `clone`·`worktree add`·`.git` 복사로 픽스처를 만드는 테스트가 새로 생긴다 | 서브커맨드를 신호로 쓰지 않으므로 무관하다. 그 파일도 「git 을 spawn 한다」는 이유만으로 헬퍼가 강제된다 |
| **E4** | 판별식이 자기가 만든 victim 을 진짜 저장소로 착각한다 | victim 은 항상 `mkdtemp` 아래다. `REPO_ROOT` 를 대상으로 삼는 경로를 **테스트가 스스로 금지**한다 |
| **E5** | 훅에서 스크럽 줄만 지우고 판별식 호출은 남긴다 | R1·R2 앵커가 red |
| **E6** | 파일 하나를 헬퍼 미경유로 되돌린다 | R3·R4 양방향 대조가 red |
| **E7** | `GIT_*` 가 환경에 하나도 없다 | `unset` 이 인자 없이 불린다. `sh -e` 에서 EXIT=0 · 무해(실측 완료) |
| **E8** | `.mjs` 파일이 `.ts` 헬퍼를 임포트한다 | `scripts/workflow/todos-reorder-integrity.mjs:201` 이 git 을 부른다. 러너가 `--experimental-strip-types` 로 도니 될 것으로 보이나 **미실측** — 착수 첫 단계에서 잰다. 안 되면 헬퍼를 `.mjs` 로 둔다 |
| **E9** | 스크럽 줄이 **문법적으로 있는데 실제로는 아무것도 안 지운다** (BSD/GNU `sed` 차이 등) | 배선 판정만으로는 못 잡는다. **R8 의 실효 실측**이 이 자리를 막는다 |

### 제약 조건

- **`pnpm` 을 거치지 않는다.** `discriminant-hook-wiring.test.ts` 가 이미 강제한다 — 워크트리가
  붙어 있으면 pnpm 이 모듈 재설치를 시도하다 무-TTY 로 죽고, `CI=true` 로 뚫으면 워크트리의
  심볼릭이 가리키는 실체가 지워져 **옆 세션 작업이 함께 깨진다**.
- **가드 수정이다 — 일부러 끊어 red 를 1회 본다.** 표면을 없애면 판별자도 함께 사라지므로,
  각 새 판정마다 「끊었을 때 red」를 실제로 관측하고 **어느 판정이 red 였는지 이름으로** 기록한다.
- **주석·도움말 문자열로 판정을 만족시키지 않는다.** 새 판정도 「주석이 아니라 실행 줄을 본다」
  규율을 따른다(`invariant-satisfied-by-helptext-not-logic`).
- **개수를 안 적는다.** 이 문서에도, 판정 메시지에도 「N건」을 쓰지 않는다 — 전수 열거·집합
  대조만 쓴다.
- **구조 변경과 동작 변경을 같은 커밋에 섞지 않는다.** 파서 추출(구조)을 먼저 하고 판정 추가(동작)를
  나중에 한다.

### 측정 가능한 완료 기준

| # | 기준 | 측정 방법 |
|---|---|---|
| **C1** | 훅이 판별식 호출 전에 무조건으로 `GIT_*` 를 지운다 | 배선 앵커 판정 green |
| **C2** | `git` 을 spawn 하는 파일 전량이 헬퍼 경유 (예외 목록 0개) | 파생 집합 양방향 대조 green |
| **C3** | 파생 집합이 비-공허 — 알려진 픽스처 생성자를 실제로 문다 | 비-공허 짝 green |
| **C4** | `GIT_DIR` 를 건 채 픽스처를 돌려도 victim 이 안 바뀐다 | 격리 실측 판정 green |
| **C5** | C4 의 비-공허 짝 — 스크럽을 끄면 victim 이 실제로 바뀐다 | 짝 판정 green |
| **C6** | 훅의 스크럽 줄이 **실효로** 0개를 남긴다 | 훅에서 읽어낸 줄을 실행해 `env \| grep -c '^GIT_'` == 0 |
| **C7** | 판정 파서·상수가 한 벌이다 | `hook-source.ts` 를 두 테스트가 임포트 · 복제 0 |
| **C8** | 각 뮤테이션이 **어느 판정을** red 로 만들었는지 이름으로 기록됐다 | plan `## red 관측` 표 (M1~M8) |
| **C9** | 판별식 전량 초록 | fail 0 · skipped 0 · EXIT=0 |
| **C10** | **최종 판별자 — `--no-verify` 없이 실제로 푸시해 오염 0** | 아래 5-2 |

**C10 의 측정 (리뷰 F4 반영).** 푸시 출력을 **로그 파일로 캡처**하고 다음을 전부 확인한다.

| 축 | 확인 |
|---|---|
| 훅이 실제로 돌았다 | 로그에 판별식 요약 줄(`# pass`)과 `push-backend-tests` 출력이 **문자열로 존재**한다. 없으면 이 검증은 **무효** |
| worktree 저장소 | `git ls-files \| wc -l` 전후 동일 · `git status --porcelain` 전후 0 · `git rev-parse HEAD` 불변 |
| 공유 config | `git config --get core.bare` 전후 모두 부재 (worktree 에서 읽어도 공유 config 를 본다) |
| **메인 워크트리** | `git -C /Users/maxi.moff/Projects/BTS status --porcelain` 0건 · `ls-files` 건수 불변 |

**C10 이 이 작업의 진짜 완료 기준이다.** 나머지는 그것을 지속시키는 장치다.


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

> **개정 이력.** 초판(task 5)이 `/plan-eng-review` 에서 BLOCKER 2 · critical gap 1 · 주의 6 을 받았다.
> Maxi 가 게이트 1 에서 **A안(plan 수정 후 진행)** 을 선택해 아래로 개정했다. 리뷰 원문은 `## 리뷰 결과`.
> 바뀐 핵심 — `READ_ONLY` 예외 목록 삭제(F1) · 파생 집합 비-공허 짝 추가(F2) ·
> 훅 스크럽 실효 실측 추가(★) · 파서 선추출(F6) · 뮤테이션 M7·M8 추가(F3) ·
> C10 강화(F4) · `--no-verify` 규율 완화(F5) · 배포 게이트 보험(F8) · `.mjs` 임포트 실측(F7).

### 설계 결정

**D1 — 스크럽 헬퍼는 신규 모듈이다.**
두 곳에 복붙하면 그 둘이 서로를 검사하지 않는다. 공유 모듈이어야 R4 의 재계산이
「헬퍼를 임포트했는가」라는 **하나의 신호**로 성립한다. `scripts/workflow/*.{ts,mjs}` 는 이미
`GUARD_CI` 표면이라 티어가 안 바뀐다.

**★확장자는 `.mjs` 로 확정됐다 (E8 실측 · 2026-08-21).** 격리 디렉터리에서 실제로 실행해 쟀다.

```
node consumer.mjs                            → ERR_UNKNOWN_FILE_EXTENSION ".ts"  EXIT=1
node --experimental-strip-types consumer.mjs → IMPORT_OK function                EXIT=0
```

`.ts` 로 두면 **플래그 없는 경로가 죽는다.** `scripts/workflow/todos-reorder-integrity.mjs:237` 이
플래그 없는 CLI 진입점을 문서화하고 있고, 임포트 해석이 `main()` 을 부르기도 **전에 끝나므로**
인자와 무관하게 즉사한다. 게다가 `ERR_UNKNOWN_FILE_EXTENSION` 은 이 저장소가 `node-ts-invocation.test.ts` 를
세워 이미 막고 있는 바로 그 실패다. 역방향(`.ts` 테스트 → `.mjs` 헬퍼)은
`todos-reorder-integrity.test.ts:42` 가 in-repo 로 증명한다.
→ 정본은 `scripts/workflow/git-fixture-env.mjs` 다.

**D2 — 지우는 형태는 접두 스윕 한 줄이다.**

```sh
unset $(env | sed -n 's/^\(GIT_[A-Za-z0-9_]*\)=.*/\1/p')
```

루프를 안 쓴다 — `for` 는 `BLOCK_OPEN` 이라 블록 깊이 판정과 얽힌다. 한 줄이면 **깊이 0 ·
가드 연산자 없음**이 자명하다. `sh -e` 동작은 실측 완료(E7).

**D3 — 대조는 「파생집합 == 파생집합」이다. 사람이 적는 목록이 0개다.**
`git` 을 spawn 하는 파일 집합(소스에서 계산)과 헬퍼를 임포트하는 파일 집합(소스에서 계산)이
**양방향으로 같다.** 예외 선언을 두지 않는다 — 예외는 red 를 끄는 탈출구가 된다(F1).
그리고 **파생 집합이 비-공허함을 함께 단언**한다 — 안 그러면 둘 다 비었을 때 조용히 통과한다(F2).

**D4 — 판정의 집은 둘이되 파서는 한 벌이다.**
훅 배선(R1·R2·R8)은 기존 `discriminant-hook-wiring.test.ts`, 픽스처 격리(R3~R7·R9)는 신규
`git-fixture-isolation.test.ts`. 두 파일이 같은 파서를 쓰므로 **`hook-source.ts` 로 먼저 추출**한다
(F6 — 복제 위험을 없앤다). 구조 변경을 동작 변경보다 앞에 둔다.

**★정정 (2026-08-21 · Task 4 가 실측해 적발).** 이 plan 의 초판과 `## 리뷰 결과` 주의 4 는
「파일 300줄 상한」을 근거로 들었는데 **그 상한은 TypeScript 에 적용되지 않는다.**
`DEVELOPMENT.md:55` 의 「함수 30줄, 파일 300줄」은 **§2.1 Kotlin** 절 안에 있고,
§2.2 TypeScript 는 「함수 30줄, **컴포넌트** 200줄」로 파일 상한이 없다.
`TODOS.md` 가 Maxi 확정으로 「⇒ TypeScript 에 파일 300줄 상한은 무효다」를 못박아 뒀다
(2026-08-12 · PR #365 에서 정본 충돌 해소). `scripts/**` 에 줄수를 강제하는 기계 판정도 0건이다.
→ **파서 추출의 근거는 복제 방지 하나로 충분하고, 실제로 그것만으로 정당하다.**
   `discriminant-hook-wiring.test.ts` 가 340줄이 된 것은 위반이 아니다.

**D5 — 훅 스크럽은 존재가 아니라 실효로 잰다.**
「그 줄이 있는가」만 보면 `sed` 방언 차이 하나로 0개를 지워도 초록이다(E9).
훅 파일에서 **읽어낸 그 줄을 실제로 실행**해 `GIT_*` 가 0개 남는지 본다.

---

### Task 1. 훅 소스 파서·상수를 공유 모듈로 추출한다 (구조 변경 · 동작 불변)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/hook-source.ts`, `scripts/workflow/discriminant-hook-wiring.test.ts`]
- depends-on: []

**RED**: 없음 — **동작을 안 바꾸는 순수 추출**이다. 기존 판정 전량이 추출 전후로 **같은 결과**여야
한다. 추출 전 `node --experimental-strip-types --test scripts/workflow/discriminant-hook-wiring.test.ts`
결과(tests/pass/fail)를 적어 두고, 추출 후 **같은 값**임을 대조한다.

**GREEN**:
- `scripts/workflow/hook-source.ts` (신규) — `HOOK` 경로 상수 · `BLOCK_OPEN`/`BLOCK_CLOSE` ·
  `GUARD_OPERATORS` · 「실행 줄만 뽑는」 파서 · 블록 깊이 계산을 export.
- `discriminant-hook-wiring.test.ts` 는 그것을 임포트하도록 바꾼다. **판정 로직은 안 건드린다.**

**REFACTOR**: 파일 첫 줄에 역할 한국어 주석. 추출 후 기존 파일 줄수를 기록한다(추이 관측용 —
위 ★정정대로 TypeScript 에 파일 상한은 없다).

**검증**: 추출 전후 tests/pass/fail 3값 일치 · EXIT=0

---

### Task 2. 픽스처 격리 실측 짝과 스크럽 헬퍼

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/git-fixture-env.mjs`, `scripts/workflow/git-fixture-isolation.test.ts`]  ← E8 실측으로 `.ts` → `.mjs`
- depends-on: []

**★ 착수 첫 동작 — E8 실측.** `.mjs` 에서 `.ts` 헬퍼를 임포트할 수 있는지 먼저 잰다
(`todos-reorder-integrity.mjs` 가 Task 3 에서 이 헬퍼를 써야 한다). 안 되면 헬퍼를 `.mjs` 로 만들고
그 사실을 plan 에 적는다. **추측하지 말고 실행해서 본다.**

**RED**:
- 파일: `scripts/workflow/git-fixture-isolation.test.ts` (신규)
- 테스트 3종.
  ```ts
  test('GIT_DIR 가 걸려 있어도 픽스처가 진짜 저장소를 안 바꾼다', ...)          // R5
  test('★★스크럽을 끄면 실제로 오염된다 (비-공허 짝)', ...)                   // R6
  test('★victim 은 언제나 mkdtemp 아래이고 REPO_ROOT 가 아니다 (자기 함정)', ...) // R7
  ```
- 판정 방법. victim 을 `fs.mkdtempSync(os.tmpdir())` 아래에 **스크럽된 env 로** 만든다.
  `GIT_DIR=<victim>/.git` 를 건 채 픽스처 절차(`init`→`add`→`commit`)를 돌리고 victim 의
  **커밋 수 · `core.bare` 유무 · HEAD** 를 전후 비교한다.
- 실패 메시지 (예상): `Cannot find module '.../git-fixture-env'`

**GREEN**:
- `scripts/workflow/git-fixture-env.mjs` — `process.env` 사본에서 `GIT_` 로 시작하는 키를 전량
  삭제해 돌려주는 함수. **열거하지 않는다**(N1).

**REFACTOR**: 역할 주석 1줄 + 접두 상수화.

**검증**: `node --experimental-strip-types --test scripts/workflow/git-fixture-isolation.test.ts`
→ 3종 pass · fail 0

---

### Task 3. `git` 을 spawn 하는 전량이 헬퍼를 거친다 (예외 목록 없음)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/git-fixture-isolation.test.ts`, `scripts/workflow/select-backend-modules.test.ts`, `scripts/workflow/todos-reorder-integrity.test.ts`, `scripts/workflow/todos-reorder-integrity.mjs`, `scripts/workflow/tier-floor.test.ts`, `scripts/workflow/transition-term-guard.test.ts`, `scripts/workflow/snapshot-baseline-guard.test.ts`, `scripts/workflow/changed-paths.ts`, `scripts/workflow/push-backend-tests.ts`]
- depends-on: [2]

**RED**:
- 파일: `scripts/workflow/git-fixture-isolation.test.ts` (판정 추가)
- 테스트.
  ```ts
  test('★★git 을 spawn 하는 파일 집합과 헬퍼 임포트 집합이 양방향으로 같다', ...)   // R3·R4
  test('★★파생 집합이 비어 있지 않고 알려진 픽스처 생성자를 실제로 문다 (비-공허 짝)', ...) // R9
  ```
- 파생 집합. `scripts/**/*.{ts,mjs}` 소스에서 git 을 spawn 하는 파일을 **계산**한다.
  실패 메시지가 **차집합 양쪽을 전량 열거**한다(개수를 안 적는다).
- 실패 메시지 (예상): 헬퍼를 임포트하지 않은 파일이 차집합으로 전량 열거된다.

**GREEN**:
- git 을 spawn 하는 전량을 헬퍼 경유로 바꾼다. **`cwd` 인자는 전부 그대로 둔다** — 그것이
  스크럽 후 저장소를 찾는 근거다.
- `select-backend-modules.test.ts` 의 `HOME: tmp` 는 **유지**한다 — 전역 git config 격리 목적이라
  이번 결함과 무관하다.
- 로직·서브커맨드·단언은 **한 줄도 바꾸지 않는다.** 이 task 는 env 배선만 바꾼다.

**REFACTOR**: 파생 집합 계산과 대조를 각각 함수로 분리.

**검증**: `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`
→ fail 0 (읽기 전용 6곳의 동작이 안 바뀌었다는 것이 여기서 증명된다)

---

### Task 7. 호출부 단위로도 판정한다 (Task 3 이 실측으로 찾은 구멍)

> **번호와 위치.** 의존 순서상 Task 3 바로 뒤에 오지만 번호는 `7` 이다 —
> `### Task N.` 서식이 아니면 `/bts-plan` 의 task 카운터(`^### Task [0-9]+[.:]`)가 세지 못해
> 캐시의 `task_count` 와 조용히 갈린다. 커밋 슬러그는 `task-3b` 로 뒀다. Task 3 의 후속임을
> git 이력에서 읽히게 하려는 것이고, 커밋 슬러그는 어떤 판별식의 입력도 아니다.

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/git-spawn-sweep.ts`, `scripts/workflow/git-fixture-isolation.test.ts`]
- depends-on: [3]

**★왜 생겼나.** Task 3 이 R3 을 **파일 집합** 양방향 대조로 구현했는데, 배선 중에
`select-backend-modules.test.ts` 의 **여러 줄로 쓴 두 번째** `spawnSync('git', …)` 가 스크럽 없이
남았다. 그 파일은 이미 헬퍼를 임포트했으므로 **양방향 대조는 초록이었다.** 잡아낸 것은 오직
`GIT_DIR` 를 건 모사 실행이다.

**요구는 「호출 단위」인데 판정이 「파일 단위」였다.** 선언한 속성과 검사하는 속성이 어긋난
자리이고, 그 틈으로 이 PR 안에서 실제 결함이 한 번 빠져나갔다.

**규칙 — 예외 목록 없이 성립한다.**
`scripts/**/*.{ts,mjs}` 의 **모든 git spawn 호출부**가 옵션 객체에 **명시적 `env:` 키**를 갖는다.
`spawnSync`·`execFileSync`·`execSync` 세 형태 전부, **여러 줄에 걸친 호출도** 잡는다.

「헬퍼를 부른다」로 쓰지 않는 이유. 비-공허 짝이 **일부러** 스크럽 없이 부르는 자리가 있다
(`runFixtureProcedure` 가 `env` 를 파라미터로 받는다). 헬퍼 호출을 요구하면 그 자리를 살리려고
**사람이 적는 예외 목록**이 되살아나고, 그것이 다시 red 를 끄는 가장 싼 방법이 된다 — F1 이
없앤 탈출구다. 「`env:` 가 명시돼 있다」는 그 자리도 자연히 통과하므로 **예외가 0개**다.

**파일 단위 대조는 그대로 둔다.** 층위가 다르다 — 파일 단위는 「헬퍼를 아예 안 쓰는 파일」을,
호출 단위는 「쓰는데 일부 호출을 빠뜨린 파일」을 잡는다. 둘 다 소스에서 계산되므로 사람 목록은
여전히 0개다.

**커밋 2개.**
1. `refactor:` 스윕 로직을 `git-spawn-sweep.ts` 로 추출 (동작 불변 · `hook-source.ts` 선례를 따라
   **아무것도 판정하지 않는** 모듈로). 판별자는 추출 전후 결과 동일.
2. `test:` 호출부 판정 + 비-공허 짝 추가.

**★RED 가 안 난다.** 지금 호출부 전량이 이미 `env:` 를 갖고 있다. 그래서 `feat:` 커밋이 없고,
**커밋 뒤 일부러 끊어 red 를 관측하는 것**이 그 자리의 판별자다.

| # | 끊는 것 | 기대 |
|---|---|---|
| MB1 | 배선된 호출부 하나에서 `env:` 삭제 | 호출부 판정 red · **그리고 파일 단위 대조는 red 가 안 난다** ← 이 task 의 존재 이유를 값으로 증명하는 자리 |
| MB2 | 호출부 파생 집합 정규식 무력화 | 비-공허 짝 red |

**검증**: 전량 `fail 0 · skipped 0 · EXIT=0`. 기준선은 Task 4 종료 시점의 `368 tests · pass 368`.

---

### Task 4. 훅과 배포 게이트가 `GIT_*` 를 지우고, 그것이 **실효**로 판정된다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/discriminant-hook-wiring.test.ts`, `.husky/pre-push`, `infra/deploy/bts-deploy.sh`]
- depends-on: [1]

**RED**:
- 파일: `discriminant-hook-wiring.test.ts` (판정 추가 — Task 1 이 추출한 `hook-source.ts` 를 쓴다)
- 테스트.
  ```ts
  test('★푸시 훅이 판별식 전에 GIT_* 를 지운다', ...)                        // R1
  test('★그 스크럽이 조건에 안 매달린다 (깊이 0 · 가드 연산자 없음)', ...)      // R2
  test('★★훅에서 읽어낸 그 줄을 실행하면 GIT_* 가 0개 남는다 (실효 실측)', ...) // R8
  test('주석이 아니라 실행 줄을 본다 (산문 오탐 방지)', ...)
  ```
- **R8 의 판정 방법.** 훅 파일에서 스크럽 줄을 **문자열로 뽑아** `sh -e -c '<그 줄>; env | grep -c "^GIT_"'`
  를 `GIT_*` 를 건 환경에서 실행하고 결과가 `0` 인지 본다. 손으로 적은 사본을 실행하면 안 된다 —
  **훅 파일에서 읽어낸 것**을 실행해야 배선과 실효가 같은 대상을 가리킨다.
- 실패 메시지 (예상): `.husky/pre-push` 에 `GIT_*` 스크럽이 없다.

**GREEN**:
- `.husky/pre-push` — D2 의 한 줄을 판별식 호출 **앞**에.
- `infra/deploy/bts-deploy.sh` — 전량 검증 게이트 앞에 같은 한 줄(F8 · 심층 방어).

**REFACTOR**: 최종 줄수를 기록한다. **상한 판정은 하지 않는다** — 위 ★정정대로 TypeScript 에
파일 300줄 상한은 무효다(Kotlin 전용).

**검증**: `node --experimental-strip-types --test scripts/workflow/discriminant-hook-wiring.test.ts`

---

### Task 8. 배포 게이트의 스크럽에도 판별자를 붙인다 (이 PR 이 스스로 만든 결함)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/discriminant-hook-wiring.test.ts`, `scripts/workflow/hook-source.ts`]
- depends-on: [4]

**★왜 생겼나 (Task 4 verifier 적발).** Task 4 가 `.husky/pre-push:22` 와
`infra/deploy/bts-deploy.sh:35` **두 자리**에 같은 스크럽을 넣었는데, 판정 3종(R1·R2·R8)이
**`.husky/pre-push` 만 읽는다.** 배포 쪽 줄은 지워도·`&&` 로 매달아도·`GIT_DIR` 만 지우도록
좁혀도 red 가 안 난다.

더 나쁜 것 — 그 자리 주석이 스스로 「두 자리가 다른 형태를 쓰면 그 둘은 서로를 검사하지 않는
두 목록이 된다」고 **위험을 적어 놓고** 기계가 그 동일성을 안 본다.
**이 PR 이 없애려는 양식을 이 PR 이 하나 만들었다.**

**처방.** 두 자리의 스크럽 줄이 **문자열로 같음**을 단언한다. 그러면 배포 쪽이 훅 쪽 판정 3종에
**무임승차**한다 — 훅 줄이 R1·R2·R8 로 검증되고 배포 줄이 그것과 같으므로 함께 보장된다.
사본을 두 벌 검증하는 것보다 강하고 짧다.
추가로 배포 쪽 **자리**(전량 검증 게이트보다 앞인가 · 조건에 안 매달렸는가)도 잰다 —
동일성만으로는 위치가 안 잡힌다. `hook-source.ts` 의 `commandLines`·`blockDepthAt`·
`GUARD_OPERATORS` 를 **그대로 재사용**한다(그 모듈은 훅 전용이 아니라 셸 소스 어휘다).

**★RED 가 안 난다.** 지금 두 줄이 이미 같다. `test:` 를 먼저 커밋하고 **커밋 뒤** 아래를 관측한다.

| # | 끊는 것 | 기대 |
|---|---|---|
| MC1 | 배포 스크럽 줄 삭제 | 동일성 판정 red |
| MC2 | 배포 줄만 다른 형태로 변형 | 동일성 판정 red · **훅 쪽 R1·R2·R8 은 red 가 안 난다** ← 이 task 의 존재 이유를 값으로 증명하는 자리 |
| MC3 | 배포 스크럽을 검증 게이트 뒤로 이동 | 순서 판정 red |
| MC4 | 배포 스크럽을 `&&` 로 매달기 | 비가드 판정 red |

**비-공허 짝.** 두 파일에서 스크럽 줄을 **실제로 찾았는지** 단언한다 —
못 찾았는데 `undefined === undefined` 로 통과하면 공허하다.

**검증**: 전량 `fail 0 · skipped 0 · EXIT=0`. 기준선은 Task 7 종료 시점의 `370 tests · pass 370`.

---

### Task 5. 뮤테이션 M1~M8 을 관측하고 **red 판정 이름을 전수 기록**한다

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-08-21-pre-push-git-dir.md`]
- depends-on: [1, 2, 3, 4]

**RED/GREEN 없음 — 관측 task 다.**

**★순서 규율.** 뮤테이션 검증은 **GREEN 을 먼저 커밋한 뒤** 한다. 미커밋 상태에서 끊었다가
되돌리면 그 되돌림이 소실이다.

| # | 끊는 것 | 기대 |
|---|---|---|
| M1 | `.husky/pre-push` 의 스크럽 줄 삭제 | red — **어느 판정이 red 였는지 전량 적는다.** 3종이 함께 죽는 것이 정상이며, 그 사실 자체를 기록한다 |
| M2 | 스크럽 줄을 판별식 호출 **뒤로** 이동 | 순서 판정만 red |
| M3 | 스크럽 줄을 `&&` 로 앞 명령에 붙임 | 비가드 판정만 red |
| M4 | `git-fixture-env` 의 삭제 로직을 no-op 으로 | 격리 실측 판정 red |
| M5 | 파일 하나를 헬퍼 미경유로 되돌림 | 양방향 대조 red |
| M6 | 헬퍼 임포트는 남기고 실제 사용만 제거 | — **관측 결과를 그대로 적는다.** red 가 안 나면 그것이 이 판별식의 알려진 한계다 |
| **M7** | 스크럽을 `GIT_DIR` 하나만 지우도록 **좁힘** | R8 실효 실측 red (N1 네임스페이스가 지켜지는지) |
| **M8** | 파생 집합 정규식을 무력화 | R9 비-공허 짝 red |

**산출물**. plan `## red 관측` 절에 M1~M8 각 1행 — 끊은 것 · **red 가 된 판정 이름 전량** ·
실제 실패 메시지 첫 줄 · 되돌림 확인.

**검증**: 원복 뒤 판별식 전량 초록.

---

### Task 6. 전량 검증 + `--no-verify` 없이 실제 푸시해 오염 0 을 실측한다

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-08-21-pre-push-git-dir.md`]
- depends-on: [5]

**RED/GREEN 없음 — 최종 판별 task 다.**

**6-1. 전량 검증** (명령마다 **개별 로그** — 배경 묶음의 종료 코드는 마지막 명령 것이라
중간 실패를 가린다).

| 검사 | 기준 |
|---|---|
| 판별식 전량 | fail 0 · skipped 0 · EXIT=0 |
| `bash scripts/verify-master-plan.sh` | EXIT=0 · FR 143/143 |
| `node scripts/build-doc-index.mjs --check` | drift 0 · EXIT=0 |

**6-2. C10 — 진짜 판별자.** 푸시 출력을 **로그로 캡처**하고 스펙 §측정 가능한 완료 기준의
C10 표 4축을 전부 확인한다. 훅 실행 증거(`# pass` 문자열 · `push-backend-tests` 출력)가
로그에 없으면 **이 검증은 무효**라고 기록한다.

**산출물**. plan `## C10 실측` 절에 4축 전후 표 + 로그 경로 + 훅 실행 증거 인용 1줄.

## Plan 메타

- **task 수**. 8 (Task 1~4·7·8 은 사이클 · Task 5~6 은 관측·검증). **Task 7·8 은 착수 후 추가됐다** — Task 3 이 파일 단위 판정의 구멍을 실측으로 찾아서다
- **예상 wave**. 4
  - wave 1. Task 1 · Task 2 (파일 교집합 0 — 병렬 2건)
  - wave 2. Task 3 (Task 2 헬퍼 의존) · Task 4 (Task 1 파서 의존) — 병렬 2건
  - wave 2b. Task 7 (Task 3 의 스윕에 의존 · 단독 · 커밋 슬러그 `task-3b`)
  - wave 2c. Task 8 (Task 4 에 의존 · 단독)
  - wave 3. Task 5
  - wave 4. Task 6
- **구현 규율**. TDD red-first. Task 1 은 **동작 불변 추출**이라 red 가 없고, 전후 결과 동일이
  그 자리의 판별자다. T2 이므로 `test:` → `feat:` 커밋 순서가 대조된다. Task 5·6 은 `docs:`.
- **추가 검증**. typecheck·ktlint·detekt·vitest·playwright **해당 없음** — 프로덕션 Kotlin 0줄 ·
  `apps/web` 0파일이다.
- **병렬 상한**. 동시 dispatch 2건. 스왑 압박 이력 때문에 3건을 안 넘긴다.
- **`--no-verify` 규율 (F5 반영)**. Task 4 GREEN 이 커밋되기 **전까지만** `--no-verify` 다.
  그 구간의 각 푸시는 **직전에 판별식 전량을 수동 실행**하고 pass/fail/EXIT 를 기록한다 —
  기계 강제가 없는 구간을 무검증으로 두지 않는다.
  Task 4 이후의 푸시는 플래그를 뗀다. 그러면 C10 이 한 번이 아니라 **여러 번** 관측된다.


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

### ⚠️ 주의 4 — 파서를 먼저 추출해야 한다  ~~(300줄 결정을 착수 전에)~~

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
| F6 | 주의 | 파서를 `hook-source.ts` 로 먼저 추출 (~~300줄~~ → **근거 정정**. 복제 방지가 진짜 이유이고, TypeScript 에 파일 300줄 상한은 무효다) |
| F7 | 주의 | `.mjs` → `.ts` 헬퍼 임포트 가능성 미실측 |
| F8 | 주의 | `bts-deploy.sh` 에 같은 한 줄 보험 없음 |
| ★ | critical gap | 스크럽 줄이 **있는데 아무것도 안 지우는** 경우를 아무 판정도 안 잡는다 — 훅 스크럽에도 실측 짝 필요 |

**VERDICT. CHANGES REQUESTED** — BLOCKER 2건과 critical gap 1건은 plan 수정 없이 착수하면
이 PR 이 막으려는 결함 양식을 그대로 재생산한다.

**게이트 1 판정 (2026-08-21).** Maxi 가 **A안 — plan 수정 후 진행** 을 선택했다.
F1·F2·critical gap 과 주의 6건을 전부 `## Plan` 에 반영했다(개정 이력은 그 절 머리).
task 5 → 6 · 건드리는 파일 2 → 12.

NO UNRESOLVED DECISIONS
