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

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
