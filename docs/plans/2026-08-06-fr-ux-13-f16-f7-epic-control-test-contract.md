# FR-UX-13 F16 후속 ⑦ — 백로그 에픽 컨트롤 짝 테스트 공유 셀렉터 모듈 승격

> slug: fr-ux-13-f16-f7-epic-control-test-contract
> type: **chore** (fast-track — Maxi 확정 2026-08-06)
> agent: qa-engineer (`chore` 기본값 `backend-engineer` 를 plan 메타로 오버라이드)
> primary_bc: null (BC 무관)
> task_count: 4 (착수 중 3 → 4, 아래 §범위 확장)
> branch: `qa/fr-ux-13-f16-f7-epic-control-test-contract` (접두사 표기만 구분류 잔존 — §분류 참조)
> 생성: 2026-08-06
> FR: **FR-UX-13** (§4.11 후속 ⑦ · 정본 `docs/plan/product/personalization.md`)

## Brief

### 사용자 원문

FR-UX-13 F16 후속 ⑦ — 백로그 에픽 컨트롤의 짝 테스트(`BacklogFilterBar.test.tsx` ↔
`BacklogEpicPanel.test.tsx`)가 셀렉터 헬퍼를 **글자 단위로 동일한 복사본 2벌**로 갖고 있고
동기화 강제가 주석 한 줄뿐이다. 패널이 Radix 메뉴로 바뀌어 role 이 `menuitemcheckbox` 가 되면
패널 테스트만 red 가 되고 **필터바 쪽은 존재하지 않는 role 을 0개 세며 영구 초록**이 된다.
`EPIC_CONTROL_ROLE` 과 `queryEpicControls()` 를 테스트 전용 공유 모듈 하나로 승격해
두 파일이 `import` 만 하게 한다. **프로덕션 코드 0줄.**

### 분류 (Maxi 확정 2026-08-06 · 2단계)

`classify-task.ts` 가 문구에 따라 `backend`↔`qa` 로 뒤집히고 slug 를
`fr-ux-13-f16-7-0`/`vitest` 로, `task_count` 를 `0` 으로 냈다.
「신호 0 → `backend` 기본값」은 사실상 unknown 이므로 **bts-start 단계 Maxi 확인 규칙**을 적용해
1차로 `type=qa` · `agent=qa-engineer` · `task_count=3` 으로 확정했다.

**그 뒤 `chore` 로 재확정했다 (Maxi 확정 A안).** 사유 — `type=qa` 는 fast-track 대상이 아니라
`/bts` 정식 경로가 **대화형 스킬 3종**(`grill-with-docs` · `office-hours` ·
`superpowers:brainstorming`)을 요구하는데, 이 작업은 **프로덕션 0줄 · 테스트 파일 2개 +
신규 모듈 1개**라 그릴링할 표면이 없다. 실체가 「기능 변경 없는 정리」라 `chore` 가 분류상으로도
정확하고, **임의 우회를 규칙 안으로 되돌린다** — 이 프로젝트가 반복해 데인
「안전장치가 실패가 아니라 부재로 빠지는 양식」을 피하기 위함이다.

- **정식 생략 (fast-track 규정).** `/bts-domain` · `/bts-spec` · `/bts-review-plan` · **게이트 1**.
- **게이트 2(머지 전 정지)는 생략하지 않는다.** 어떤 타입도 예외 없음.
- **단 `/bts-domain` 은 재분류 전에 이미 수행했고 결과를 아래에 보존한다** — 생략 규정이
  「해서는 안 된다」가 아니라 「안 해도 된다」이므로, 이미 나온 산출물은 버리지 않는다.
  실제로 그 단계가 `menuitemcheckbox` 실재 사례를 찾아냈다.
- **`agent` 오버라이드.** `detectAgent` 는 `chore → backend-engineer` 를 주지만 이 작업은
  `apps/web` 테스트 파일 전용이라 부적합하다. bts 규칙 **「plan task 메타 agent 지정이 우선」**에
  따라 `qa-engineer` 로 지정한다.
- **브랜치 접두사.** `qa/…` 로 이미 푸시돼 PR #345 가 열려 있다. GitHub 은 PR 의 head 브랜치를
  바꿀 수 없어 `chore/…` 로 개명하려면 **PR 재개설**이 필요하다. 접두사는 표기일 뿐이라
  브랜치를 유지하고 **PR 제목만 `[chore]` 로 정정**한다.

### ★ 범위 확장 — 복사본은 2개가 아니라 **8개**다 (착수 중 실측)

정본 §4.11 ⑦ 은 *"셀렉터 헬퍼가 글자 단위로 같지만 **복사본 2벌**"* 이라고 적었다.
실측 결과 헬퍼가 참조하는 **이름 상수 6개도 두 파일에 각각 따로 정의**돼 있다.

| 항목 | `BacklogEpicPanel.test.tsx` | `BacklogFilterBar.test.tsx` | 값 | 드리프트 |
|---|---|---|---|---|
| `EPIC_CONTROL_ROLE` | `:83` | `:116` | `'checkbox'` | 가능 |
| `queryEpicControls()` | `:86-96` | `:119-129` | 본문 동일 | 가능 |
| `EPIC_ALPHA` | `:40` | `:60` | `'ATLAS-100'` | 가능 |
| `EPIC_BETA` | `:41` | `:61` | `'ATLAS-200'` | 가능 |
| `EPIC_UNRESOLVED` | `:43` | `:63` | `'ATLAS-900'` | 가능 |
| `EPIC_ALPHA_NAME` | `:45` | `:65` | `'결제 개편'` | 가능 |
| `EPIC_BETA_NAME` | `:46` | `:66` | `'알림 리팩터'` | 가능 |
| `NO_EPIC_LABEL` | `:26` | `:58` | `backlogLabels.filter.noEpic` | **값은 i18n 정본 파생이라 안전 · 바인딩만 중복** |

**역할·헬퍼 2개만 옮기면 나머지 6개가 그대로 어긋난 채 남는다** — 이 FR 이 PR #342·#343·#344
3연속으로 맞은 **「봉합이 절반」**의 네 번째 판이 된다. 공유 모듈이 **8개 전부**를 소유해야 한다.

→ `task_count` **3 → 4**.

동기화 강제 장치는 오늘도 **주석 문장 하나뿐**이다
(`"셀렉터를 여기서 바꾸면 BacklogFilterBar.test.tsx 도 같은 PR 에서 함께 고쳐야 한다"`).

### 착수 전 실측 (3라운드 21 에이전트 · 2026-08-06)

**결함 실재 확증.**
- `queryEpicControls()` 가 **두 파일에 복사본 2벌**로 실재
  (`BacklogEpicPanel.test.tsx:83-96` ⟺ `BacklogFilterBar.test.tsx:116-129`, 글자 단위 동일).
- 동기화 강제 장치는 **주석 한 줄뿐**.
- 프로덕션 소비처 **0** — 이 작업은 테스트 파일만 만진다.

**기준선 (실행 확인).**
- `vitest run` (apps/web) → **570 files / 9,231 tests 전량 통과, 실패 0** (소요 591s).
- DnD 유닛 4파일 → 59 passed.
- ⚠️ **정본 `personalization.md:443` 의 「E2E `backlog.spec.ts` 82/82 + 10파일 동반 47/47」은
  실측과 불일치** — `--list` 실측 **35 tests in 1 file**, 피어 10파일 **75 tests**,
  전체 스위트 696 tests / 144 files. 유닛도 정본 「9,214」 대비 실측 **9,231**(파일 570 은 일치).
  이 PR 에서 정정 대상.

**⑤ 는 이 PR 범위가 아니다 (기판정).**
1차 조사는 ⑤(라벨 5종 컴포넌트 모듈 잔류)를 ⑦ 과 한 PR 로 묶자고 했으나,
`create-entry-point-names.test.ts:10-15` 가 그 import 를 **관례의 처방으로 명시**하고 있어
`ALREADY_SEALED` 로 판정됐다. 재론하려면 후속이 아니라 상위 결정 재개정이다.
**따라서 이 PR 은 ⑦ 단독.**

### 선례 교훈 2건 (Obsidian `learnings.md`)

- **#22 (2026-05-26)** — E2E 셀렉터는 i18n 정본을 `import` 한다. 하드코딩 리터럴은
  라벨 변경 시 **silent cascade break** 를 만든다. → 정본 참조 = single source of truth.
- **#16 (2026-05-23)** — mirror data 는 helper 를 **호출**하게 만들어 drift 를 **본질 차단**한다.
  회귀 가드 테스트는 **보조**이고 본질 차단이 우선이다.

→ 두 교훈 모두 「대조 판별식 추가」가 아니라 **「공유 모듈 승격」**을 가리킨다.

### 완료 기준 (비-공허 확인)

공유 모듈의 `EPIC_CONTROL_ROLE` 을 `'menuitemcheckbox'` 로 바꾸고 두 파일을 함께 돌린다.

- **두 파일이 동시에 red** → 성공.
- **한쪽만 red** → 봉합 실패(복사본이 아직 남았다).
- **둘 다 green** → 셀렉터가 어느 단언에도 안 물린 것으로 **더 나쁘다**.

⚠️ 뮤테이션 검증은 **GREEN 을 먼저 커밋한 뒤** 수행한다 — 미커밋 상태에서 `git checkout --` 하면
작업이 날아간다 (`mutation-test-requires-committed-baseline`).

### 실행 환경 규율 (worktree)

`node_modules` 는 main 에서 심볼릭 연결돼 있다. **worktree 에서 `pnpm` 을 절대 실행하지 말 것** —
pnpm 의 deps 검사가 main 의 `.modules.yaml` 을 유령 경로로 박제해 **worktree 제거 후 main 훅이
깨진다**(`worktree-pnpm-verify-deps-symlink`). 검증은 바이너리 직접 호출로만 한다.

```
node_modules/.bin/vitest run <path>
node_modules/.bin/tsc -p tsconfig.app.json --noEmit
node_modules/.bin/eslint src
```

진단 시 **파이프 금지** — `cmd | head` 는 `head` 의 exit 0 을 뱉어 깨진 툴체인을 정상으로 오독시킨다.
`cmd > file 2>&1; echo "EXIT=$?"` 를 쓴다.

## 도메인 정리

- **BC. `null` — 미탐지가 아니라 의도된 값이다.** `classify-task.ts` 의 `detectBoundedContext` 가
  `type ∈ {migration, qa, design, chore}` 에 대해 명시적으로 `null` 을 반환한다(「BC 무관」).
  인접 도메인은 `agile-planning`(백로그·에픽)이나 **BC 코드는 0줄 건드리지 않는다.**
- **영향 엔티티. 없음.** 프로덕션 파일 0개. 변경 대상은 테스트 파일 2개 + 신규 테스트 전용 모듈 1개.
- **새 용어. 0건.** F16 ADR `## 신규 용어` 가 같은 판정을 이미 내렸다 — *"「에픽 패널」·「필터바」는
  UI 배치 용어라 유비쿼터스 언어 대상이 아니다"*. 테스트 셀렉터 계약은 그보다 더 멀다.
  `glossary.md` 의 8개 섹션(핵심 엔티티 · 관계/연결 · 워크플로우/자동화 · 권한 · 검색/AQL ·
  인증 · 데이터 무결성 키워드 · 변경 규칙)에 **테스트 관련 섹션 자체가 없다.**
  → `glossary.md` 갱신 **불필요**. `domain/agile-planning.md` 갱신 **불필요**.

### 기존 결정과의 관계 — 충돌 아니라 **이행**

`docs/decisions/2026-08-06-fr-ux-13-f16-backlog-filter-epic.md:84-86` (D-2 파생 결정).

> 가시성은 **양방향 짝 테스트**로 못박았다 — 숨김 단언만 있으면 컴포넌트가 아무것도 안 그려도
> 통과하기 때문이다(**같은 셀렉터**로 「없다」와 「있다」를 둘 다 잰다).

**그런데 「같은 셀렉터」가 오늘 이미 절반만 참이다.** 두 파일이 글자 단위로 같을 뿐 복사본 2벌이라
같음이 **아무것도 보장하지 않는다.** 이 PR 은 ADR 을 무효화하지 않고 **그 문장을 사실로 만든다.**
→ 신규 ADR 불필요. 대신 F16 ADR 에 이행 사실 1줄 보강이 적절하다(`/bts-plan` task 로 편입).

### ★ 도메인 검증이 찾아낸 것 — `menuitemcheckbox` 는 가정이 아니다

정본 §4.11 ⑦ 은 *"패널이 Radix 메뉴로 바뀌어 role 이 `menuitemcheckbox` 가 되면"* 이라는
**가정법**으로 위험을 서술한다. 실측 결과 **그 형태가 같은 저장소에 이미 실재한다.**

| 컴포넌트 | 프리미티브 | 렌더되는 role | 증거 |
|---|---|---|---|
| `BacklogEpicPanel` | shadcn `Checkbox` | `checkbox` | `BacklogEpicPanel.tsx:221` |
| `ColumnSelector` | Radix 메뉴 | **`menuitemcheckbox`** | `ColumnSelector.test.tsx:49,56,64,68,81` · `issues.index.test.tsx:1243,1264` |

즉 「목록에서 여러 개 고르기」라는 **같은 성격의 UI 두 개가 서로 다른 a11y role 로 이미 공존한다.**
가정된 미래가 아니라 현재 상태다 — ⑦ 의 위험 서술은 오히려 **과소평가**였다.

- **이 PR 의 범위 판정에 미치는 영향.** role 은 `docs/design/jira-parity-contract.md` 에
  **명시된 바가 없다**(에픽 언급 0건). 즉 제품 계약이 아니라 **선택한 프리미티브의 부산물**이다.
  → 공유 모듈은 **테스트 공간에 두는 것이 맞다**(프로덕션 계약 승격 불필요). 원안 유지.
- **범위 밖 후속 후보 1건 신규.** 「에픽 패널과 컬럼 셀렉터가 같은 성격인데 role 이 갈린다」는
  접근성·Jira 패리티 관점의 **디자인 일관성 문제**다. 이 PR 은 테스트 배선만 고치므로 건드리지
  않는다. §4.11 후속 목록에 등재 후보로 남긴다.

### 이 단계의 지위 — `chore` 재분류로 **정식 생략 대상**이 됐다

`/bts-domain` 은 `classify.type ∈ {bugfix, chore}` 에서 **단계 전체가 규정상 생략**된다.
즉 위 내용은 **의무가 아니라 이미 확보한 덤**이다.

경위 — 재분류 전(`type=qa`) 에는 이 단계가 의무였고, 그 안의 `grill-with-docs`(대화형 도메인
그릴링) 한 종만 임의 우회한 상태였다. 임의 우회를 계속 쌓는 대신 **fast-track 이라는 규정된
경로로 되돌린 것이 A안의 요지**다. 결과적으로 우회는 0건이 됐다.

**산출물은 버리지 않는다.** 실제로 이 단계가 정본이 가정법으로 적은 위험(`menuitemcheckbox`)이
**실재함**을 찾아냈다. 생략 규정은 「해서는 안 된다」가 아니라 「안 해도 된다」이므로 보존한다.

**게이트 2 요약에 명시할 것** — fast-track 으로 생략된 단계 목록
(`/bts-spec` · `/bts-review-plan` · 게이트 1)과, `/bts-domain` 은 생략 대상이나 **실제로는
수행됐다**는 사실.

## 스펙 — **fast-track 정식 생략** (`type == chore`)

`/bts-spec` 은 `classify.type ∈ {bugfix, chore}` 에서 **단계 전체가 규정상 생략**된다.
사유(스킬 정본) — *"버그 수정은 스펙이 「기존 동작 복원」으로 자명. office-hours/brainstorming
비용 > 효익"*. 이 작업은 **기능 변경 0 · 프로덕션 0줄**이라 같은 논거가 그대로 적용된다.

대신 스펙이 담았을 3가지를 여기에 못박는다.

**대상 (What).** 위 §범위 확장 표의 **8개 항목 전부**를 테스트 전용 공유 모듈 1개로 승격하고,
두 테스트 파일은 `import` 만 한다. 프로덕션 파일 0개.

**완료 기준 (Done).** 아래 §완료 기준의 비-공허 확인 3분기.

**범위 밖 (Not).**
- ⑤ 라벨 5종 i18n 이관 — `ALREADY_SEALED`(`create-entry-point-names.test.ts:10-15` 가 그
  import 를 관례의 **처방**으로 명시). 1차 조사의 「⑤+⑦ 한 PR」 권고를 실측으로 기각.
- 에픽 패널 ↔ 컬럼 셀렉터의 a11y role 불일치 — 도메인 단계 신규 발견이나 **디자인 일관성**
  문제이지 테스트 배선 문제가 아니다. §4.11 등재 후보로만 남긴다.
- 프로덕션 `BacklogEpicPanel.tsx` 의 role 변경 — 이 PR 은 **현재 role 을 사실로 고정**할 뿐
  바꾸지 않는다.

## Brainstorming Check — **fast-track 정식 생략** (`type == chore`)

`/bts-spec` Phase B 이므로 위와 함께 생략된다. 이 단계가 찾았어야 할 gap 은 **착수 중 실측이
이미 하나 찾아냈다** — 정본이 「복사본 2벌」이라 적은 것이 실제로는 **8개**였다(§범위 확장).

## Plan

**Goal.** 짝 테스트 2파일이 각각 소유한 셀렉터 자산 8종을 **테스트 전용 공유 모듈 1개**로
승격하고, 로컬 복사본 부활을 **판별식 테스트**가 영구 차단한다. 프로덕션 0줄.

**Architecture.** 본질 차단(공유 모듈 = 단일 정본)이 1차, 회귀 가드(판별식)가 2차인
**이중 안전망**이다 — learnings `#16`(2026-05-23) 의 *"본질 차단이 우선, 회귀 가드는 보조"*
그대로다. 판별식은 `active-project-contract.test.ts` 의 `readFileSync` + 정규식 방식을 승계하고,
그 파일이 남긴 교훈(*"봉인이 자기 목적을 못 잡는 상태였다"*)을 따라 **비-공허 짝을 테스트 안에
내장**한다.

**결정 — 모듈 위치 `src/test/`.** 실측 근거. ① `__tests__/` 디렉토리는 저장소 전체에서
**테스트 파일 전용**이고 헬퍼 모듈 선례가 0건이다 ② `components/backlog/*.ts` 비-테스트 파일은
전부 프로덕션 모듈이라, 거기에 `@testing-library` 를 import 하는 파일을 두면 프로덕션으로
오인된다 ③ `src/test/` 는 이미 `setup.ts`·`server.ts`·`handlers.ts` 를 담은 **테스트 전용
모듈의 유일한 거처**이고 `setup.ts` 가 `@testing-library/jest-dom` 을 import 한다
④ eslint 에 devDependencies 경계 규칙이 없어 lint 위험 0.

**결정 — `screen` 은 인자가 아니라 직접 import.** `@testing-library/react` 의 `screen` 은
`document.body` 에 바인딩된 **싱글턴**이고 두 소비처가 동일하게 쓴다. 주입은 이득 없는 의식이다
(YAGNI).

**★ RED 를 무엇으로 잡는가 (지어내지 않은 근거).** 이 작업은 동작을 바꾸지 않으므로 「기능
테스트가 red」는 성립하지 않는다. **실제로 오늘 없는 것은 「두 파일이 한 정본을 쓴다」는 보장**이고,
그것은 판별식으로 **정확히 red 가 된다**(오늘 두 파일 모두 8종을 지역 정의하고 있다).
형식을 맞추려 의미 없는 RED 를 만들지 않았다.

**부수 실측 — 드리프트는 이론이 아니라 이미 진행 중이다.** 두 복사본의 KDoc 이 갈라져 있다.
`EPIC_UNRESOLVED` 가 Panel `:42` 는 *"조회 상한을 넘은"*, Filter `:62` 는 *"아직 로딩 중인"*.
값은 아직 같지만 **설명이 먼저 갈렸다.**

---

### Task 1. 계약 판별식 신설 — 오늘 red 인 봉인

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/src/components/backlog/epic-control-contract.test.ts`]
- depends-on: []

**RED**.
- 파일 (신규). `apps/web/src/components/backlog/epic-control-contract.test.ts`

```ts
// 에픽 컨트롤 테스트 계약 회귀 봉인 — 짝 테스트 2파일의 로컬 복사본 부활을 막는다 (FR-UX-13 F16 후속 ⑦)
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/** 이 파일이 사는 디렉토리 = 짝 테스트 2파일이 사는 곳 */
const BACKLOG_DIR = resolve(import.meta.dirname)

/**
 * 짝을 이루는 두 소비처.
 *
 * 한쪽만 고치면 나머지가 **존재하지 않는 role 을 0개 세며 영구 초록**이 되는 자리다.
 * 부재 단언(`toHaveLength(0)`)은 컴포넌트가 아무것도 안 그려도 통과하므로,
 * 두 파일이 **같은 셀렉터**를 써야 「어디에도 없음」과 「패널에만 있음」이 구분된다.
 */
const PAIRED_TEST_FILES = ['BacklogEpicPanel.test.tsx', 'BacklogFilterBar.test.tsx'] as const

/** 공유 계약 모듈이 단독 소유해야 하는 심볼 8종 */
const SHARED_SYMBOLS = [
  'EPIC_CONTROL_ROLE',
  'queryEpicControls',
  'EPIC_ALPHA',
  'EPIC_BETA',
  'EPIC_UNRESOLVED',
  'EPIC_ALPHA_NAME',
  'EPIC_BETA_NAME',
  'NO_EPIC_LABEL',
] as const

const CONTRACT_MODULE = '@/test/backlog-epic-control-contract'

function readPaired(fileName: string): string {
  return readFileSync(resolve(BACKLOG_DIR, fileName), 'utf8')
}

/**
 * `const X =` / `function X(` 형태의 **지역 정의**를 찾는다.
 *
 * `import { X } from …` 은 정의가 아니므로 잡히면 안 된다 — 그 구분이 이 판별식의 전부다.
 * 접미사 오탐 없음. `EPIC_ALPHA` 로 `const EPIC_ALPHA_NAME =` 를 찾으면
 * `EPIC_ALPHA` 뒤가 `_` 라 `\s*[:=]` 에 걸리지 않는다.
 */
function hasLocalDefinition(source: string, symbol: string): boolean {
  return [
    new RegExp(`^\\s*(?:export\\s+)?const\\s+${symbol}\\s*[:=]`, 'm'),
    new RegExp(`^\\s*(?:export\\s+)?function\\s+${symbol}\\s*\\(`, 'm'),
  ].some((re) => re.test(source))
}

describe('에픽 컨트롤 테스트 계약 — 단일 정본 봉인', () => {
  it.each(PAIRED_TEST_FILES)('%s 는 공유 심볼을 지역 정의하지 않는다', (fileName) => {
    const source = readPaired(fileName)
    const redefined = SHARED_SYMBOLS.filter((symbol) => hasLocalDefinition(source, symbol))
    expect(redefined).toEqual([])
  })

  it.each(PAIRED_TEST_FILES)('%s 는 공유 계약 모듈에서 읽는다', (fileName) => {
    expect(readPaired(fileName)).toContain(CONTRACT_MODULE)
  })

  // ★ 비-공허 짝 — 판별식이 자기 목적을 실제로 잡는지 증명한다.
  //   `active-project-contract.test.ts` 가 남긴 교훈(초안 판별식이 없애려던 대상을
  //   매치하지 못해 봉인이 무의미했다)의 직접 처방이다.
  it('판별식 비-공허 — 지역 정의는 잡고 import 는 안 잡는다', () => {
    expect(hasLocalDefinition(`const EPIC_CONTROL_ROLE = 'checkbox' as const`, 'EPIC_CONTROL_ROLE')).toBe(true)
    expect(hasLocalDefinition(`function queryEpicControls(): HTMLElement[] {`, 'queryEpicControls')).toBe(true)
    expect(hasLocalDefinition(`import { EPIC_CONTROL_ROLE } from '${CONTRACT_MODULE}'`, 'EPIC_CONTROL_ROLE')).toBe(false)
    expect(hasLocalDefinition(`const EPIC_ALPHA_NAME = '결제 개편'`, 'EPIC_ALPHA')).toBe(false)
  })
})
```

- **예상 실패.** 첫 두 `it.each` 가 **4건 전부 red**.
  - `… 지역 정의하지 않는다` → `expected [ 'EPIC_CONTROL_ROLE', 'queryEpicControls', 'EPIC_ALPHA', 'EPIC_BETA', 'EPIC_UNRESOLVED', 'EPIC_ALPHA_NAME', 'EPIC_BETA_NAME', 'NO_EPIC_LABEL' ] to deeply equal []` (두 파일 각각)
  - `… 공유 계약 모듈에서 읽는다` → 모듈이 아직 없으므로 문자열 미포함 (두 파일 각각)
  - 세 번째 `it`(비-공허)는 **처음부터 green** — 판별식 자체의 정확성만 재므로 정상이다.

- [ ] **Step 1.** 위 파일을 그대로 생성한다.
- [ ] **Step 2.** red 를 눈으로 확인한다.

```bash
cd apps/web
node_modules/.bin/vitest run src/components/backlog/epic-control-contract.test.ts > /tmp/t1.txt 2>&1; echo "EXIT=$?"
cat /tmp/t1.txt
```
  기대. `EXIT=1` · 실패 4건 · 통과 1건(비-공허).

- [ ] **Step 3.** 커밋한다.

```bash
git add apps/web/src/components/backlog/epic-control-contract.test.ts
git commit -m "test: 에픽 컨트롤 계약 판별식 — 짝 테스트 2파일의 로컬 복사본 봉인 (RED)"
```

**검증**. `cd apps/web && node_modules/.bin/vitest run src/components/backlog/epic-control-contract.test.ts`
→ RED 4건 (Task 2 에서 green 전환).

---

### Task 2. 공유 계약 모듈 신설 + 두 파일 전환 — GREEN

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/src/test/backlog-epic-control-contract.ts`, `apps/web/src/components/backlog/BacklogEpicPanel.test.tsx`, `apps/web/src/components/backlog/BacklogFilterBar.test.tsx`]
- depends-on: [1]

**GREEN**.
- 파일 (신규). `apps/web/src/test/backlog-epic-control-contract.ts`

```ts
// 백로그 에픽 선택 컨트롤의 테스트 계약 — 짝 테스트 2파일이 쓰는 셀렉터 자산의 단일 정본
import { screen } from '@testing-library/react'
import { backlogLabels } from '@/i18n/backlog-labels'

/**
 * 에픽 선택 컨트롤의 a11y role.
 *
 * `BacklogEpicPanel` 이 shadcn `Checkbox`(radix 기반, `role="checkbox"`)를 쓴다는
 * **현재 사실**을 고정한다. 제품 계약이 아니라 **선택한 프리미티브의 부산물**이다 —
 * `docs/design/jira-parity-contract.md` 에 에픽 컨트롤 role 명시는 0건이고,
 * 같은 성격의 `ColumnSelector` 는 Radix 메뉴라 `menuitemcheckbox` 로 **이미 갈려 있다**.
 *
 * ★ 이 값이 바뀌면 **두 소비처가 동시에** red 여야 한다.
 *   한쪽만 red 면 어딘가에 복사본이 남은 것이고, 둘 다 green 이면 셀렉터가 어느 단언에도
 *   물리지 않은 것이라 더 나쁘다.
 */
export const EPIC_CONTROL_ROLE = 'checkbox' as const

/** 짝 테스트 전용 에픽 키 픽스처 — MSW 시드 값이 아니다 */
export const EPIC_ALPHA = 'ATLAS-100'
export const EPIC_BETA = 'ATLAS-200'
/**
 * 이름 해석에 실패했거나 조회 상한(`EPIC_NAME_LOOKUP_LIMIT` = 50)을 넘은 에픽.
 * F16-6 은 이때 **키를 그대로** 보이라고 한다.
 *
 * (구 복사본 2벌은 이 설명이 이미 갈라져 있었다 — 한쪽은 「조회 상한을 넘은」,
 *  다른 쪽은 「아직 로딩 중인」. 값보다 설명이 먼저 드리프트한 실례다.)
 */
export const EPIC_UNRESOLVED = 'ATLAS-900'

export const EPIC_ALPHA_NAME = '결제 개편'
export const EPIC_BETA_NAME = '알림 리팩터'

/** 「에픽 없음」 표시명 — 정본(`i18n/backlog-labels.ts`)에서 읽는다. 리터럴 재타이핑 금지 */
export const NO_EPIC_LABEL = backlogLabels.filter.noEpic

/**
 * 에픽 선택 컨트롤 후보를 이름별로 전부 긁는다. 해석 이름·미해석 키·센티널 라벨 전부.
 *
 * `screen` 을 인자로 받지 않고 직접 import 한다 — `@testing-library/react` 의 `screen` 은
 * `document.body` 에 바인딩된 싱글턴이고 두 소비처가 동일하게 쓴다. 주입은 이득 없는 의식이다.
 */
export function queryEpicControls(): HTMLElement[] {
  const names = [
    EPIC_ALPHA_NAME,
    EPIC_BETA_NAME,
    EPIC_ALPHA,
    EPIC_BETA,
    EPIC_UNRESOLVED,
    NO_EPIC_LABEL,
  ]
  return names.flatMap((name) => screen.queryAllByRole(EPIC_CONTROL_ROLE, { name }))
}
```

- 파일 (수정). `apps/web/src/components/backlog/BacklogEpicPanel.test.tsx`
  - `:26` `const NO_EPIC_LABEL = …` · `:40-46` 키/이름 5종 · `:83` role · `:86-96` 헬퍼를 **삭제**
  - 상단 import 에 아래를 추가한다. `backlogLabels` import 는 다른 소비가 남아 있으면 유지하고,
    없으면 **같은 커밋에서 제거**한다 (내 변경이 만든 고아만 정리 — 사전 dead code 는 건드리지 않는다)

```ts
import {
  EPIC_CONTROL_ROLE,
  EPIC_ALPHA,
  EPIC_BETA,
  EPIC_UNRESOLVED,
  EPIC_ALPHA_NAME,
  EPIC_BETA_NAME,
  NO_EPIC_LABEL,
  queryEpicControls,
} from '@/test/backlog-epic-control-contract'
```

  - **주석 처리.** 삭제 구간의 「셀렉터를 여기서 바꾸면 …도 같은 PR 에서 함께 고쳐야 한다」
    문장은 **더 이상 참이 아니므로 삭제**한다(공유 모듈이 그 의무를 대신한다). 대신 한 줄로
    바꾼다 — `// 셀렉터 자산은 `@/test/backlog-epic-control-contract` 가 단독 소유한다.`
    남겨 두면 `create-entry-point-names.test.ts` 헤더가 그랬듯 **stale 주석**이 된다.

- 파일 (수정). `apps/web/src/components/backlog/BacklogFilterBar.test.tsx`
  - `:58` · `:60-66` · `:116` · `:119-129` 를 같은 방식으로 삭제하고 같은 import 를 추가한다.
  - `:57` `BACKLOG_SEARCH_LABEL` 은 **이 짝의 자산이 아니므로 그대로 둔다.**

- [ ] **Step 1.** 공유 모듈을 생성한다.
- [ ] **Step 2.** `BacklogEpicPanel.test.tsx` 에서 8종을 지우고 import 로 바꾼다.
- [ ] **Step 3.** `BacklogFilterBar.test.tsx` 에서 같은 작업을 한다.
- [ ] **Step 4.** 판별식 + 짝 테스트 2파일이 전부 green 인지 본다.

```bash
cd apps/web
node_modules/.bin/vitest run \
  src/components/backlog/epic-control-contract.test.ts \
  src/components/backlog/BacklogEpicPanel.test.tsx \
  src/components/backlog/BacklogFilterBar.test.tsx > /tmp/t2.txt 2>&1; echo "EXIT=$?"
cat /tmp/t2.txt
```
  기대. `EXIT=0` · 3파일 전량 통과 · 판별식 5건 green.

- [ ] **Step 5.** 타입·린트를 본다 (파이프 금지).

```bash
cd apps/web
node_modules/.bin/tsc -p tsconfig.app.json --noEmit > /tmp/tc.txt 2>&1; echo "TSC EXIT=$?"
node_modules/.bin/eslint src > /tmp/lint.txt 2>&1; echo "LINT EXIT=$?"
```
  기대. 둘 다 `EXIT=0`.

- [ ] **Step 6.** 전체 유닛으로 기준선 불변을 확인한다.

```bash
cd apps/web
node_modules/.bin/vitest run > /tmp/full.txt 2>&1; echo "EXIT=$?"
tail -8 /tmp/full.txt
```
  기대. `EXIT=0` · **571 files**(판별식 1파일 신설) · **9,236 tests**(판별식 5건 추가).
  기존 9,231 건은 **한 건도 줄지 않아야 한다** — 줄면 셀렉터가 물리던 단언이 사라진 것이다.

- [ ] **Step 7.** 커밋한다 (뮤테이션 검증 전에 **반드시** 커밋 — Task 3 전제).

```bash
git add apps/web/src/test/backlog-epic-control-contract.ts \
        apps/web/src/components/backlog/BacklogEpicPanel.test.tsx \
        apps/web/src/components/backlog/BacklogFilterBar.test.tsx
git commit -m "test: 에픽 컨트롤 셀렉터 자산 8종을 공유 계약 모듈로 승격 (GREEN)"
```

**검증**. 위 Step 4·5·6 전부 `EXIT=0`.

---

### Task 3. 비-공허 확인 2종 — 봉인이 실제로 무는지 (커밋 후)

**메타**.
- agent: `qa-engineer`
- files: []  ← 최종 상태 변경 0. 뮤테이션 후 **역방향 Edit 으로 원복**한다
- depends-on: [2]

**전제.** Task 2 가 **커밋된 뒤에만** 수행한다. 미커밋 상태에서 원복하면 작업이 날아간다
(`mutation-test-requires-committed-baseline`).
**원복은 `git checkout --` 가 아니라 역방향 Edit** 을 쓴다 — 병렬 작업의 미커밋 산출물을
휩쓸지 않기 위함이다(`parallel-wave-mutation-revert-destroys-peers`).

- [ ] **Step 1. 뮤테이션 A — 런타임 공유 증명.**
  `src/test/backlog-epic-control-contract.ts` 의
  `export const EPIC_CONTROL_ROLE = 'checkbox' as const` 를
  `export const EPIC_CONTROL_ROLE = 'menuitemcheckbox' as const` 로 바꾼다.

```bash
cd apps/web
node_modules/.bin/vitest run \
  src/components/backlog/BacklogEpicPanel.test.tsx \
  src/components/backlog/BacklogFilterBar.test.tsx > /tmp/mutA.txt 2>&1; echo "EXIT=$?"
grep -E '❯|×|✓|Test Files' /tmp/mutA.txt
```
  **판정.**
  - **두 파일이 동시에 red** → 성공. 셀렉터가 하나의 정본에서 나옴이 증명됐다.
  - **한쪽만 red** → 봉합 실패. 어딘가에 복사본이 남았다. Task 2 로 돌아간다.
  - **둘 다 green** → 더 나쁘다. 셀렉터가 어느 단언에도 물리지 않는다는 뜻이므로
    **짝 테스트 자체가 공허**하다. 이 경우 Maxi 에게 보고하고 멈춘다 (범위 재검토).

- [ ] **Step 2. 역방향 Edit 으로 원복** 후 두 파일 green 재확인.

- [ ] **Step 3. 뮤테이션 B — 판별식 비-공허 증명.**
  `BacklogFilterBar.test.tsx` 에 로컬 복사본을 **한 줄 되살린다**.
  `const EPIC_CONTROL_ROLE = 'checkbox' as const`

```bash
cd apps/web
node_modules/.bin/vitest run src/components/backlog/epic-control-contract.test.ts > /tmp/mutB.txt 2>&1; echo "EXIT=$?"
grep -E '×|✓' /tmp/mutB.txt
```
  **판정.** 판별식이 **red** 여야 한다(`redefined` 가 `['EPIC_CONTROL_ROLE']`).
  green 이면 판별식이 공허하므로 Task 1 로 돌아간다.

- [ ] **Step 4. 역방향 Edit 으로 원복** 후 판별식 green 재확인.

- [ ] **Step 5.** 트리가 깨끗한지 확인한다 (뮤테이션 잔재 0).

```bash
git status --porcelain
git diff --stat
```
  기대. **둘 다 출력 없음.**

**검증**. 뮤테이션 A 에서 두 파일 동시 red · 뮤테이션 B 에서 판별식 red · 원복 후 전량 green ·
`git status --porcelain` 빈 출력.

---

### Task 4. 정본 동기화 — ⑦ 완료 + 실측 수치 정정

**메타**.
- agent: `qa-engineer`
- files: [`docs/plan/product/personalization.md`, `docs/decisions/2026-08-06-fr-ux-13-f16-backlog-filter-epic.md`, `docs/plans/2026-08-06-fr-ux-13-f16-backlog-filter-epic.md`]
- depends-on: [3]

**근거.** CLAUDE.md §명세/범위 변경 시 전수 동기화 — 정본 서술이 실측과 다르면 같은 PR 에서 고친다.

- [ ] **Step 1.** `docs/plan/product/personalization.md` §4.11 후속 ⑦ 을 정정한다.
  - 「복사본 2벌」 → **8개**(role · 헬퍼 · 키 3 · 이름 2 · 라벨 1). 표로 명시.
  - 「동기화 강제는 주석 한 줄뿐」 → **해소**. 공유 모듈 `src/test/backlog-epic-control-contract.ts`
    + 판별식 `components/backlog/epic-control-contract.test.ts`.
  - 「패널이 Radix 메뉴로 바뀌면」이라는 **가정법을 사실로 교체** — `ColumnSelector` 가
    이미 `menuitemcheckbox` 를 쓴다(`ColumnSelector.test.tsx:49` 외 4곳).
  - ⑦ 을 **완료 표시**하고 후속 잔여 건수를 갱신한다.

- [ ] **Step 2.** 같은 파일 `:443` 의 F16 검증 수치를 실측으로 정정한다.
  - 「E2E `backlog.spec.ts` 82/82 + 10파일 동반 47/47」 → **35 + 75** (전체 696/144파일).
  - 「유닛 9,214건 / 570파일」 → **9,231 / 570** (이 PR 후 **9,236 / 571**).

- [ ] **Step 3.** `docs/decisions/2026-08-06-…-f16-backlog-filter-epic.md:84-86` 에 이행 1줄을 보강한다.
  - 「**같은 셀렉터**로 「없다」와 「있다」를 둘 다 잰다」가 당시엔 **복사본 2벌이라 절반만 참**이었고,
    F16 후속 ⑦ 에서 공유 모듈 승격으로 **문자 그대로 참이 됐다**는 사실.

- [ ] **Step 4.** `docs/plans/2026-08-06-…-f16-backlog-filter-epic.md:561` 의
  「두 파일이 글자 단위로 같은 헬퍼를 쓴다」 서술을 갱신한다 — 처방 후 거짓이 된다.

- [ ] **Step 5.** 신규 후속 후보 1건을 §4.11 에 등재한다.
  - 「에픽 패널(`checkbox`) ↔ 컬럼 셀렉터(`menuitemcheckbox`) a11y role 불일치」 —
    같은 성격 UI 인데 role 이 갈린다. 접근성·Jira 패리티 관점. **이 PR 범위 밖**.

- [ ] **Step 6.** 동기화 게이트를 통과시킨다.

```bash
bash scripts/verify-master-plan.sh > /tmp/vmp.txt 2>&1; echo "EXIT=$?"
tail -12 /tmp/vmp.txt
node scripts/build-doc-index.mjs --check > /tmp/di.txt 2>&1; echo "EXIT=$?"
```
  기대. 둘 다 `EXIT=0`. `verify-master-plan.sh` 는 **종료 4 로 자동 차단**하므로 반드시 통과해야 한다.
  FR 개수는 **139 불변**(후속 항목 증감은 FR 수를 바꾸지 않는다).

- [ ] **Step 7.** 커밋한다.

```bash
git add docs/
git commit -m "docs: FR-UX-13 §4.11 ⑦ 완료 — 복사본 2→8 정정 + F16 검증 수치 실측 정정"
```

**검증**. `bash scripts/verify-master-plan.sh` EXIT=0 · `build-doc-index.mjs --check` EXIT=0.

---

## Plan 메타

- **task 수.** 4
- **예상 시간.** 직렬 약 20분 (Task 2 의 전체 유닛 1회가 약 10분을 차지)
- **wave.** 전 task 직렬 (`depends-on` 1→2→3→4). 파일 교집합도 있어 병렬 불가
- **구현 규율.** TDD (`chore` 지만 판별식이 실제 RED 를 갖는다 — 시각 검증 트랙 아님)
- **추가 검증.** `tsc --noEmit` · `eslint src` · 전체 vitest · 뮤테이션 2종 ·
  `verify-master-plan.sh` · `build-doc-index.mjs --check`
- **프로덕션 변경.** 0파일 (`git diff --name-only main...HEAD | grep -v -E '\.test\.|/test/|^docs/'` → 0 이어야 한다)

### Self-Review (writing-plans §Self-Review)

1. **스펙 커버리지.** 8개 승격 → Task 2 · 비-공허 확인 → Task 3 · 정본 정정 → Task 4 ·
   RED 근거 → Task 1. **범위 밖 3건**(⑤ i18n 이관 · role 불일치 · 프로덕션 role 변경)은
   어느 task 에도 없다 — 의도대로다.
2. **플레이스홀더 스캔.** "TBD"/"적절히"/"등등" 0건. 모든 코드 단계에 실제 코드가 있다.
3. **타입 일관성.** `EPIC_CONTROL_ROLE`·`queryEpicControls`·6개 상수 이름이 Task 1 판별식의
   `SHARED_SYMBOLS`, Task 2 의 모듈 `export`, Task 2 의 import 문에서 **철자 동일**.
   모듈 경로도 세 곳 모두 `@/test/backlog-epic-control-contract` 로 일치.
4. **발견해 고친 것.** Task 2 Step 6 의 기대 수치를 「9,231 불변」에서
   **「571파일 / 9,236건」**으로 고쳤다 — 판별식 파일 1개와 테스트 5건이 늘기 때문이다.
   그대로 뒀으면 정상 통과가 실패로 오독됐다.

## 리뷰 결과 (← /bts-review-plan 채움)
