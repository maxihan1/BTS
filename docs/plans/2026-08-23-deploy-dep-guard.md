# 배포 게이트의 의존성 확인이 shim 존재만 보고 모듈 실체를 안 본다

> 티어: T2
> slug: deploy-dep-guard
> type: chore
> agent: backend-engineer
> 생성: 2026-08-23

## Brief

**FR 없음 — FR수 불변 143.** 프로덕션 Kotlin 0줄 · `apps/web/src` 0파일 · 마이그레이션 0 ·
신규 의존성 0. 배포 게이트와 티어 표면 카탈로그 전용 작업이다.

**대상 부채 2건.** 장부 `TODOS.md` 와 매핑 `docs/plans/2026-08-12-debt24-master.md` 의
**95** 와 **81**. 95 항목의 「착수 시 주의」가 **81 을 함께 착수하라**고 지시하고 있어 한 PR 로 묶는다.

| 번호 | 제목 | 이 PR 에서 |
|---|---|---|
| **95** | 인프라 — 배포 게이트의 의존성 확인이 **shim 존재**만 보고 모듈 실체를 안 본다 | 해소 |
| **81** | 워크플로우 — `infra/deploy/bts-deploy.sh` 가 티어 표면 카탈로그에 없다 | 해소 |

### 실측 — 95 (2026-08-23, 등재 커밋 `8816ed53f`)

`apps/web/node_modules` 가 2026-08-21 05:41 이후 파괴돼 있었다. 선언 의존성 **50개 전부 부재**,
남은 41개 항목은 빈 스코프 디렉터리(`@radix-ui` 등)뿐이었다.

그런데 `apps/web/node_modules/.bin/vitest` 는 실재했다. pnpm 이 만드는 `.bin` 엔트리는
**모듈을 부르는 독립 셸 스크립트**라서 모듈이 사라져도 남는다. 게이트의 `-x` 는 그것을 보고 통과했고,
바로 다음 줄이 `Cannot find module '…/node_modules/vitest/vitest.mjs'` 로 죽었다.

**#395 배포가 백엔드 게이트(10m55s · 10,401 초록)를 통과한 직후 여기서 26초 만에 끝났다.**

**★그 상태가 이틀 동안 안 보였다.** 배포가 그 앞의 백엔드 게이트에서 매번 먼저 죽어 여기까지
온 적이 없었기 때문이다. 앞 관문이 뒤 관문의 고장을 가렸다 — 이 사실은 별건 부채로 등재한다(§신규 등재).

### 실측 — 81

```
$ node --experimental-strip-types scripts/workflow/detect-tier.ts infra/deploy/bts-deploy.sh
TIER: T1
SURFACES: (없음)
UNMAPPED: infra/deploy/bts-deploy.sh
```

`scripts/workflow/surfaces.ts` 전문에 `infra` 문자열이 **0건**이라 어느 글로브에도 안 걸린다.
프로덕션 직전의 마지막 검사대가 **계획 0 · 리뷰 1종**으로 고쳐질 수 있는 상태다.

### 왜 두 건이 같은 PR 인가

95 를 고치는 diff 는 `infra/deploy/bts-deploy.sh` 한 파일이다. 81 을 함께 닫지 않으면
**이 PR 자신이 그 파일을 T1 절차로 고치는 사례**가 된다. 판정 대상과 절차 강도가 갈린 채로
부채만 하나 닫는 셈이라, 95 항목이 「함께 착수」를 명시적으로 적어 둔 것이다.

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1. 모듈이 파괴된 상태에서 배포를 부른다.**
- Given `apps/web/node_modules/vitest` 실체가 없고 `.bin/vitest` 셰임만 남았다
- When `bash infra/deploy/bts-deploy.sh` 를 실행한다
- Then 게이트가 **의존성 부재를 이름으로 말하고** 종료한다. `MODULE_NOT_FOUND` 스택은 안 나온다
- And 메시지가 복구 명령과 **그 명령의 전제 조건**(워크트리 0개)을 함께 준다

**S2. 배포 스크립트만 바꾸는 PR 을 분류한다.**
- Given `infra/deploy/bts-deploy.sh` 한 파일만 바뀌었다
- When `detect-tier.ts` 를 부른다
- Then `TIER: T2` · `SURFACES: GUARD_CI` 가 나오고 `UNMAPPED` 줄이 없다

### 기능 요구사항

| ID | 내용 | 판정 |
|---|---|---|
| R1 | 전량 검증 게이트의 프론트 확인이 **모듈 실체**를 본다 | 판별식 |
| R2 | 프론트 빌드 폴백의 확인도 **모듈 실체**를 본다 | 판별식 |
| R3 | 실패 메시지가 복구 명령과 그 전제를 함께 준다 | 판별식 |
| R4 | `infra/deploy/**` 가 `GUARD_CI` 표면에 걸린다 | 판별식 |
| R5 | 배포 스크립트 안의 ★★ purge 금지 주석이 **조건을 밝힌다** | 판별식 |

**R5 는 이 PR 이 스스로 만든 결함이다.** R3 이 「복구는 `CI=true pnpm install --frozen-lockfile`」을
안내하는데, 같은 파일 `:77-79` 의 ★★ 주석은 그 명령을 **조건 없이** 금지한다. 내가 만든 모순이므로
내가 닫는다 — 금지의 근거는 「워크트리의 심볼릭이 지워지는 실체를 가리킨다」이고, 워크트리가 0개면
성립하지 않는다(2026-08-23 실측 — 워크트리 0개에서 17초 복구 · 796개 전부 스토어 재사용 · 다운로드 0).

### 비기능 요구사항

- 게이트 블록은 **`pnpm` 을 거치지 않는다.** 기존 판별식
  `push-backend-tests.test.ts §배포 전 전량 게이트 ★★게이트가 pnpm 을 거치지 않는다` 가 강제한다.
  → 부채 95 의 「처방 후보 ②(`pnpm list --depth -1`)」는 **이 제약과 충돌하므로 채택하지 않는다.**
  채택은 후보 ①(모듈 실체 확인)이다.
- 확인은 **자식 프로세스를 안 띄운다.** `[ -e … ]` 로 끝낸다 — `node -e "require.resolve(…)"` 는
  같은 판정을 하면서 배포마다 node 기동을 얹는다.

### 엣지 케이스

| 케이스 | 기대 |
|---|---|
| 모듈은 있는데 `.bin` 셰임이 없다 | 게이트는 통과하고 실행에서 「no such file」로 죽는다. **오진이 아니므로 다루지 않는다** |
| `node_modules/vitest` 가 끊어진 심볼릭이다 | `-e` 는 심볼릭을 **따라가므로** false → 잡힌다 |
| `BTS_SKIP_DEPLOY_TEST=1` | 게이트 전체를 건너뛴다. 기존 동작 그대로 |
| 빌드 폴백이 안 불린다(pnpm 성공) | R2 의 확인도 안 불린다. 기존 구조 그대로 |

### 측정 가능한 완료 기준

1. 판별식 전량 초록 — `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`
   (착수 시점 기준선 **393/393**)
2. 뮤테이션 M1~M5 전건이 **이름 붙은 red** 를 낸다 (§뮤테이션 계획)
3. `detect-tier.ts infra/deploy/bts-deploy.sh` 가 `T2` · `GUARD_CI` · `UNMAPPED` 없음
4. `node scripts/build-doc-index.mjs --check` exit 0
5. `bash scripts/verify-master-plan.sh` exit 0

## Plan

### 설계 결정

**D1. 판별식을 어디에 두나.** 기존 `scripts/workflow/push-backend-tests.test.ts` 의
`describe('배포 전 전량 게이트 …')` 안에 넣는다. 그 블록이 **이미 이 배포 스크립트를 판정하는 유일한 자리**다.
새 파일을 만들면 배포 게이트 판정이 두 곳으로 갈린다 — 이 저장소가 이름 붙인 지배 결함 양식
`two-lists-never-check-each-other` 를 자초하는 것이다.

**D2. `-e … /package.json` 을 본다.** 모듈 디렉터리 존재(`-d`)가 아니라 `package.json` 실체를 본다.
빈 스코프 디렉터리만 남는 파괴 양식이 실측이었기 때문이다(41개 항목이 전부 빈 디렉터리였다).

**D3. `GUARD_CI` 에 글로브를 더한다** (부채 81 처방 후보 ①). 새 표면 이름을 만들지 않는다 —
이름을 늘리면 `docs/rules/behavior-rules.md` 표면 표와의 차집합 0 을 맞추는 일이 따라오고
(`tier-floor.test.ts` 가 양방향 강제), 그것은 「배포 게이트의 성격이 강제 장치가 아니다」라는
주장을 전제로 해야 하는데 그 주장이 성립하지 않는다. 배포 게이트는 **프로덕션 직전의 마지막 강제 장치**다.

**D4. 표면 note 와 문서 설명을 같은 커밋에서 고친다.** `GUARD_CI.note` 는 「강제 장치 3층」이라 적혀 있고
`behavior-rules.md:25` 는 「워크플로우 · 훅 · 판별식 · 생성기 스크립트」라 적는다. 글로브를 더하면
둘 다 거짓이 된다. 사본을 남기면 그것이 다음 부채다.

### Task 1. 판별식 red — 게이트가 모듈 실체를 본다 (R1·R2·R3)

`scripts/workflow/push-backend-tests.test.ts` §배포 전 전량 게이트 에 3건 추가.

- `★★프론트 게이트가 셰임 존재만으로 통과하지 않는다` — `.bin/vitest` 를 `-x` 로만 보는 형태가 없다
- `★프론트 게이트가 모듈 실체를 본다` — `node_modules/vitest/package.json` 확인이 있다
- `★빌드 폴백도 모듈 실체를 본다` — `node_modules/vite/package.json` 확인이 있다
- `★부재 메시지가 복구 명령과 전제를 함께 준다` — 복구 명령 문자열 + 워크트리 전제 문자열

**red 확인.** 착수 시점 코드에 대해 4건 전부 fail 하는 것을 본다.

### Task 2. green — 두 자리를 실체 확인으로 바꾼다 (R1·R2·R3)

`infra/deploy/bts-deploy.sh` 두 자리.

### Task 3. green — ★★ purge 금지 주석에 조건을 붙인다 (R5)

Task 2 가 만든 모순을 닫는다. 금지를 **없애지 않는다** — 조건을 밝힌다.

### Task 4. 판별식 red — 배포 게이트가 표면 카탈로그에 걸린다 (R4)

`scripts/workflow/tier-floor.test.ts` §표면 카탈로그 에 1건 추가.
`surfaceOf('infra/deploy/bts-deploy.sh') === 'GUARD_CI'` 와 `detectTier` 가 T2 를 내는 것.

**red 확인.** 글로브 추가 전에 fail 하는 것을 본다.

### Task 5. green — `GUARD_CI` 에 `infra/deploy/**` 를 더한다 (R4·D4)

`surfaces.ts` 글로브 + note, `docs/rules/behavior-rules.md:25` 설명.

### Task 6. 뮤테이션 관측 — 판별식이 비-공허임을 잰다

§뮤테이션 계획 M1~M5. **GREEN 선커밋 뒤**에 돌린다 — 미커밋 상태의 원복은 소실이다.

### Task 7. 장부 갱신 + 신규 등재

- `TODOS.md` — 95·81 을 ✅ 로. 신규 부채 등재.
- `docs/plans/2026-08-12-debt24-master.md` — 같은 두 행의 상태·담당 PR, 신규 행 추가.
- 두 목록의 양방향 차집합 0 을 `debt-ledger-mapping.test.ts` 가 잰다.

### 뮤테이션 계획

| 뮤테이션 | 기대 |
|---|---|
| M1. `bts-deploy.sh` 의 vitest 확인을 `-x .bin/vitest` 로 되돌린다 | Task 1 의 셰임 판정 + 실체 판정 red |
| M2. vite 폴백 확인만 되돌린다 | Task 1 의 폴백 판정만 red (M1 과 자리가 갈리는지 확인) |
| M3. 복구 명령 문구를 지운다 | Task 1 의 메시지 판정 red |
| M4. `surfaces.ts` 에서 `infra/deploy/**` 를 뺀다 | Task 4 red |
| M5. `GUARD_CI` 글로브를 `infra/nonexistent/**` 로 바꾼다 | `tier-floor` 의 「모든 글로브가 실제 파일을 하나 이상 문다」 red |

**M5 의 목적.** 글로브를 더하는 변경이 「아무 파일도 안 무는 죽은 규칙」을 심을 수 있는데,
그 백스톱이 살아 있는지를 이 PR 에서 한 번 실측한다.

### 신규 등재 예정 — 부채 96

**인프라 — 배포 게이트가 순차라 앞 관문의 실패가 뒤 관문의 고장을 이틀 동안 가렸다.**

95 의 실측에서 분리해 나온 별건이다. 배포 스크립트의 전량 검증은 판별식 → 백엔드 → 프론트 순서로
**첫 실패에서 멈춘다**(`set -euo pipefail`). 앞 관문이 반복해 죽는 동안 뒤 관문은 한 번도 실행되지
않았고, 그래서 뒤 관문의 고장이 이틀 동안 관측되지 않았다. 부채 95 는 그 뒤 관문 **하나**를 고친다 —
「앞이 죽으면 뒤가 안 보인다」는 구조는 그대로 남는다.

**이 PR 에서 안 고치는 이유.** 처방이 게이트 실행 모델의 변경(수집 후 일괄 보고 · 또는 관문별 독립 실행)이라
배포 스크립트의 실패 의미론을 통째로 바꾼다. 부채 95·81 과 범위가 다르다.

### 신규 등재 예정 — 부채 97

**워크플로우 — worktree 배선 판별식이 스킬 본문만 읽고 실제 worktree 를 안 본다.**

`scripts/workflow/worktree-hook-wiring.test.ts` 의 두 판정
(`bts-start 가 worktree 에 훅을 연결한다` · `★bts-start 가 worktree 에 필요한 심볼릭을 전부 건다`)은
**`.claude/skills/bts-start/SKILL.md` 의 코드블록에 `ln -s` 세 줄이 적혀 있는가**만 본다.
살아 있는 `.worktrees/*` 디렉터리를 한 번도 열지 않는다.

**실측 (2026-08-23, 이 PR 착수 시점).** `.worktrees/deploy-dep-guard` 는 18:04 에 생성된 뒤
`.husky/_` · `node_modules` · `apps/web/node_modules` **세 심볼릭이 전부 부재**한 상태로 있었다.
`core.hooksPath` 는 `.husky/_` 를 가리키는데 그 디렉터리가 없어 **git 이 훅을 침묵으로 건너뛴다.**
그 상태에서 판별식 전량은 **393/393 초록**이었다.

**방치하면.** 그 worktree 의 모든 커밋이 pre-commit 무방비다 — 문서 인덱스 drift 와 lint 위반이
전부 통과해 CI 에서야 빨간불이 된다. `bts-start` 본문이 스스로 적은 전례가 그것이다
(PR #331·#333 · 5커밋 구간 red).

선언(스킬 본문)과 생성(파일시스템)이 서로를 검사하지 않는다 —
이 저장소가 이름 붙인 지배 결함 양식 `two-lists-never-check-each-other` 그대로다.

**이 PR 에서 안 고치는 이유.** 처방이 「살아 있는 worktree 를 열어 3요소를 대조하는 판별식」인데,
worktree 가 0개인 CI 에서 그 판정이 **공허하게 초록**이 되지 않도록 비-공허 짝을 함께 설계해야 한다.
배포 게이트·티어 카탈로그와 범위가 다르다.

**Task 0 기록.** 이 PR 은 착수하면서 그 세 심볼릭을 `bts-start` 절차 그대로 손으로 걸었다.
그러지 않으면 아래 모든 커밋이 훅 없이 나간다.

### 범위 밖 (NOT in scope)

- 부채 95 의 처방 후보 ②(`pnpm list --depth -1`) — 게이트의 pnpm 금지와 충돌. §비기능 참조
- 게이트 실행 모델 변경 — 부채 96 으로 등재만 한다
- `apps/web/node_modules` 를 무엇이 파괴했는가의 추적 — 원인 미상. 별건
- 부채 96·97 의 처방 — 등재만 한다
- 나머지 부채 전건

### 절차 이탈 기록

**sub-agent dispatch 를 하지 않는다.** 이 세션의 하네스가 「사용자가 요청하지 않으면 Agent 툴을
부르지 않는다」로 걸려 있다. `/bts` 체인 [5] `bts-impl` 이 규정한 wave dispatch 대신 **컨트롤러가
인라인으로 구현**한다. TDD red-first · 뮤테이션 관측 · 리뷰 2종은 그대로 지킨다.
게이트 2 요약에 이 이탈을 그대로 싣는다.
