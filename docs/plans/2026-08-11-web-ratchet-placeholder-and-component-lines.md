# apps/web 강제 수단 래칫 2종 — 한글 placeholder ESLint 래칫 + 컴포넌트 200줄 계약 테스트 래칫

> slug: web-ratchet-placeholder-and-component-lines
> type: chore (분류기 `ui` 를 override — 아래 §분류 override 참조)
> agent: frontend-engineer
> primary_bc: null (apps/web 전역 도구 — 특정 BC 없음)
> 생성: 2026-08-11

## Brief

**사용자 원문.** `R3(placeholder 래칫)·R4(줄수 래칫)`

**R1~R4 시퀀스 맥락.** `TODOS.md` 「강제 수단 0」 계열 부채를 **개별 상환이 아니라 강제 수단 먼저**
닫는 순서다 (Maxi 확정 2026-08-11, `docs/plans/2026-08-11-todos-remeasure-3-debt-items.md:15`).

| 라벨 | 내용 | 상태 |
|---|---|---|
| R1 | TODOS 정본 수치 3항목 재측정 정정 + 신규 부채 3건 등재 | PR #362 머지 |
| R2 | pending mutation 누수 15건 봉합 + 전역 가드 승격 | PR #363 머지 |
| **R3** | **한글 리터럴 placeholder ESLint 래칫** (`TODOS.md:2092`) | **이 PR** |
| **R4** | **컴포넌트 200줄 계약 테스트 래칫** (`TODOS.md:2927`) | **이 PR** |

**Maxi 확정 2026-08-11 (착수 전).**
1. **R3·R4 를 한 PR 로 묶는다.** (선택지 = 두 PR 순차 / 한 PR / R3 만 → **한 PR**)
2. **fast-track 을 쓰지 않는다.** `type=chore` 기본 정책은 domain·spec·review-plan 스킵 + 게이트 1 생략인데,
   정본이 명시한 함정이 6종 이상이라 **domain 만 스킵**하고 spec·plan·review-plan 을 돌린다. 게이트 2회 유지.

### 분류 override

`node --experimental-strip-types scripts/workflow/classify-task.ts` 원출력.

```json
{ "slug": "apps-web-2-placeholder-eslint-200", "type": "ui",
  "agent": "frontend-engineer", "primary_bc": "issue-tracking", "task_count": 0 }
```

| 필드 | 원출력 | override | 사유 |
|---|---|---|---|
| `type` | `ui` | **`chore`** | `ui` 는 **시각 검증 트랙**(red-first 면제 + 브라우저 눈확인)을 붙인다. 이번 작업은 화면 변경 **0줄**이고 가드 작업이라 red-first 가 **필수**다 — 정반대 규율이 붙는다 |
| `primary_bc` | `issue-tracking` | **`null`** | `apps/web` 전역 lint/테스트 도구. 특정 바운디드 컨텍스트 없음 |
| `slug` | `apps-web-2-placeholder-eslint-200` | **`web-ratchet-placeholder-and-component-lines`** | 한글이 잘려 의미 불명 문자열이 됐다 (`classify-task.ts` 한국어 slug 처리 한계 — `TODOS.md:356·646·1183` 계열 기지 결함) |
| `agent` | `frontend-engineer` | 유지 | `apps/web/**` 주 작업 영역 |

선례. `docs/plans/2026-05-20-pr4-cleanup-and-obsidian-sync.md:25` — 분류기 오분류를 게이트 1 전 사용자 결정으로 override 한 관례.

### 이 PR 이 하는 것 / 하지 않는 것

| | 한다 | 하지 않는다 |
|---|---|---|
| **R3** | ESLint 셀렉터 추가 + 현재 히트 **예외목록 등재**(래칫) | 한글 placeholder 27곳을 i18n 으로 **옮기지 않는다** |
| **R4** | 줄수 강제 수단 신설 + 현재 초과분 **예외목록 등재**(래칫) | 200줄 초과 컴포넌트 18건을 **리팩터링하지 않는다** |

⇒ **프로덕션 동작 변경 0.** 신규 유입만 차단하고 기존 부채는 목록에 얼려 둔다.

### 정본이 미리 경고한 함정 (착수 전 필독)

**R3 (`TODOS.md:2092`).**
- ★★`no-restricted-syntax` 는 **4곳**이다 — 배열 2개(`eslint.config.js:59` 기본 · `:113` button 예외)와
  `'off'` 블록 2개(`:126` 테스트/mocks · `:137` `src/components/ui/**`). 이 규칙은 **병합이 아니라 대체**되므로
  두 배열만 고치면 `src/components/ui/**` **프로덕션 38파일이 래칫에서 영구 면제**된다 (뚫린 채로 시작).
- 미확정 ①. `ComponentMultiSelect.tsx:78` 은 **템플릿 리터럴**이라 `Literal` 선택자에 안 걸린다
  → `TemplateElement[value.raw=/[가-힣]/]` 병용 여부 판단 필요.
- 미확정 ②. **27 은 grep 기준**이라 ESLint 히트와 일치 보장이 없다 — **규칙을 한 번 돌려 실제 목록을 확정**한 뒤 등재.
  로컬 lint 목록 ≠ CI lint 목록 선례 (`[[fr-ux-14-b2-card-fields-done]]`).
- 제3 유형. `LabelAutocompleteInput.tsx:79` 는 **함수 기본 파라미터**라 어떤 JSX 선택자로도 안 잡힌다.
  변수형 사각지대 **10곳/9파일** — 세는 정의를 안 적으면 값이 2~56 사이로 흔들린다.
- 렌더 테스트로는 못 잡는다 — i18n 값과 하드코딩 값이 바이트 동일하면 **속성값은 출처를 싣지 않는다**.
- ★중복 추적. 같은 부류가 `docs/plan/product/personalization.md:479-486` ⑤ 로 따로 추적 중 →
  **이 항목으로 흡수**할 것 (`[[two-lists-never-check-each-other]]` 신규 생성 방지).

**R4 (`TODOS.md:2927`).**
- ★★**ESLint 단독 불가.** `max-lines-per-function` 은 **규칙당 임계값 1개**이고 겹치는 config 블록에서
  뒤가 앞을 **대체**한다. 「컴포넌트 200」과 「함수 30」은 같은 규칙 id 를 공유하고 **컴포넌트도 함수**다 —
  200 을 걸면 199줄 일반 함수가 통과하고, 30 을 걸면 모든 컴포넌트가 걸린다. 파일 단위 override 로 예외를 주면
  그 파일에서 **두 트랙이 함께 죽는다**. ⇒ 컴포넌트(대문자 시작 + JSX 반환) 구분에 **소스 훑기 계약 테스트** 필요.
- 선례 = `apps/web/src/components/__tests__/button-primitive-usage.test.ts` —
  ①목록을 손으로 적지 않고 디렉토리에서 **도출**(`:96 readdirSync`) ②도출이 실제 배선됐는지 **비-공허 짝**(`:305-307`)
  ③개수가 아니라 **목록 전수 비교**(`:325 toEqual`).
- ★착수 전 수치. raw 로 `max-lines-per-function: 200` 을 켜면 **CI 스코프 87건 / 78파일** red
  (비-테스트 18건/18파일 + **테스트 69건/60파일**). `apps/web/package.json:10` 의 `lint` 가 `eslint src` 이고
  `ignores` 가 `['dist']` 뿐이라 **테스트 파일도 린트 대상**이다. 「비-테스트 18」만 보고 짜면 CI 에서 87로 터진다.
- ★가드가 공허해질 경로 3종.
  ① `apps/web/src` 에 `eslint-disable` 주석 **57건** · `linterOptions.reportUnusedDisableDirectives` 미설정
     — 「마커 전수」와 「히트 전수」를 **양방향** 비교하지 않으면 주석 한 줄로 목록에도 없고 히트도 아닌 파일이 생긴다.
  ② 실행처가 CI 하나뿐. **worktree 에서 husky 훅은 구조적으로 부재**(`[[worktree-silently-disables-husky-hooks]]`)
     → 훅을 실행처로 계산에 넣지 말 것.
  ③ 소스를 훑는 계약 테스트는 **자기 파일을 스캔에서 빼거나** NEEDLE 을 런타임 조립해야 자기탐지에 안 걸린다
     (`msw-single-setupserver.test.ts:125` 선례).
- ★계수 기준 = **raw**(빈 줄·주석 포함). Maxi 확정 2026-08-11. 빈 줄·주석을 빼면 컴포넌트 227→185 로
  **코드를 한 줄도 안 고쳤는데 부채가 사라진다**.
- ★「14위」와 「15위」가 둘 다 맞다 — 비-테스트 18건 중 17건이 컴포넌트, 1건이 훅
  (`use-mention-autocomplete.ts:116` 272줄). `IssueCreateForm` 은 전체 15위 · 컴포넌트 중 14위.
- 관련 미해결. `TODOS.md:2904` ㉮ `DEVELOPMENT.md §2.2` ↔ SDD §22.7.1 **파일 300줄 상한 정본 충돌**
  (Maxi 판단 대기) — 이 PR 은 **함수/컴포넌트 줄수만** 다루고 파일 상한은 건드리지 않는다.

## 실측 (2026-08-11 · HEAD `0b09abf38`)

정본이 「27 은 grep 기준이라 ESLint 히트와 일치 보장이 없다 — **규칙을 한 번 돌려 실제 목록을 확정**한 뒤 등재」를
요구해 스펙 작성 **전에** 쟀다. 측정용 임시 flat config 로 **기존 예외를 전부 무시하고** 원시 히트를 셌다.

### ★측정이 한 번 거짓이었다 (기록)

1차 측정은 `[Literal] 0건 · max-lines 1건` 을 냈고 이는 **「깨끗함」이 아니라 빈 결과**였다.
측정용 config 에 파서를 안 붙여 **1288 파일 중 1222건이 `Parsing error: Unexpected token <`** 로 죽었고,
집계기가 `ruleId` 로만 필터링해 `fatal` 메시지를 통째로 버렸다.
⇒ **집계 전에 `PARSE_ERROR: 0` 을 먼저 단언**한다. 2차부터 파서(`tseslint.parser`) 부착 + 파싱 실패 0 확인 후 집계.
(동형 교훈 `[[measured-the-wrong-thing-twice]]`.)

**비-공허 확인.** 선택자가 실제로 발화하는지를 정본이 인용한 좌표로 확인 —
`CreateBoardForm.tsx:109` 가 `PH-LITERAL` 로 잡혔다(정본 인용과 일치).

### R3. placeholder 실측

| 구분 | 히트 | 파일 |
|---|---|---|
| `Literal` — production | **27** | 17 |
| `Literal` — test (`*.test.tsx`) | 3 | 2 (`ui/badge.test.tsx:45·52` · `ui/command.test.tsx:27`) |
| `TemplateElement` — production | **1** | 1 (`ComponentMultiSelect.tsx:78`) |
| `Literal`/`Template` — **비-테스트 `src/components/ui/**`** | **0** | 0 |

⇒ **정본 수치 그대로다** (리터럴 27 · 템플릿 포함 28). 비-테스트 스코프 재실행에서도 `placeholder: 28` 로 재현.
⇒ 정본의 「지금은 `src/components/ui/**` 밑에 비-테스트 한글 placeholder 가 **0건**이라 즉시 피해가 없다」도 **실측 확인**.

**production 27건 전수** (파일:줄).

```
components/board/CreateBoardForm.tsx:109
components/board/ResolutionPickerModal.tsx:96
components/custom-fields/CustomFieldFormDialog.tsx:93 :107 :222 :244 :265
components/custom-fields/CustomFieldInput.tsx:174
components/dashboard/DashboardForm.tsx:161 :294 :317
components/dashboard/GadgetConfigForm.tsx:172
components/global-permissions/GlobalPermissionFormDialog.tsx:79
components/import/mapping/UserMappingStep.tsx:171
components/issue-templates/IssueTemplateFormDialog.tsx:228 :315
components/issue-templates/TemplateContentField.tsx:191
components/issue/ResolutionModal.tsx:136
components/issues/BulkTransitionDialog.tsx:214 :238
components/issues/cells/AssigneeCell.tsx:130
routes/admin.workflow-schemes.new.tsx:101 :123 :142
routes/projects.$projectKey.board.tsx:218
routes/projects.$projectKey.settings.workflow-scheme.tsx:173
routes/search.tsx:353
```

### R4. 줄수 실측

| 임계값 | production | test | 합 |
|---|---|---|---|
| `max-lines-per-function: 200` (raw) | **18건 / 18파일** | **69건 / 60파일** | **87** |
| `max-lines-per-function: 30` (raw) | **445건 / 269파일** | 1907건 / 562파일 | 2352 |

⇒ **87 은 정본 수치와 정확히 일치**한다 (비-테스트 18 + 테스트 69).
⇒ **★「함수 30줄」 트랙은 이 PR 의 대상이 될 수 없다** — production 만 445건/269파일이다.
   예외목록으로 얼릴 수 있는 규모가 아니다. 정본의 「동시 강제 불가」 결론이 수치로 뒷받침된다.

**비-테스트 18건 전수 (줄수 내림차순).**

| 줄 | 위치 | 함수 |
|---|---|---|
| 1041 | `routes/issues.$key.tsx:191` | `IssueDetailPage` |
| 367 | `routes/projects.$projectKey.board.tsx:249` | `BoardPage` |
| 337 | `components/issue/IssueDescription.tsx:261` | `EditMode` |
| 334 | `components/search/ExportDialog.tsx:130` | `ExportForm` |
| 332 | `routes/dashboards.$dashboardId.tsx:164` | `DashboardDetailPage` |
| 331 | `routes/issues.index.tsx:498` | `IssueListPage` |
| 326 | `components/issues/MoveIssueDialog.tsx:58` | `MoveIssueDialog` |
| 317 | `components/issue/IssueMetaPanel.tsx:174` | `IssueMetaPanel` |
| 288 | `components/import/mapping/ImportMappingWizard.tsx:782` | (Arrow) `ImportMappingWizard` |
| 272 | `components/issue/mention/use-mention-autocomplete.ts:116` | `useMentionAutocomplete` ← **훅** |
| 259 | `components/issue-templates/IssueTemplateFormDialog.tsx:101` | `FormBody` |
| 257 | `routes/settings.account-links.tsx:128` | `AccountLinksSettingsPage` |
| 232 | `components/issues/NodeMappingSection.tsx:134` | `NodeMappingSection` |
| 230 | `components/automation/AutomationRuleFormDialog.tsx:528` | `FormBody` |
| 227 | `components/issue/IssueCreateForm.tsx:92` | `IssueCreateForm` |
| 225 | `components/custom-fields/CustomFieldFormDialog.tsx:144` | `FormBody` |
| 218 | `components/auth/MfaSettings.tsx:169` | `MfaSettings` |
| 212 | `components/issues/BulkTransitionDialog.tsx:63` | `BulkTransitionDialog` |

- **17 컴포넌트 + 1 훅** — 정본의 「비-테스트 18건 중 17이 컴포넌트, 1이 훅」과 일치.
- `ImportMappingWizard` 는 `export const … = (…): JSX.Element =>` 형태라 ESLint 가 **이름 없이 「Arrow function」** 으로 표기한다.
  ⇒ 예외목록을 **함수명 기준으로 짜면 이 항목을 식별할 수 없다** (파일+줄 기준이 필요).
- `IssueCreateForm` 227줄 = `TODOS.md:2471` 개별 부채와 동일 값 (좌표 밀림 0).

### 부수 실측 — `eslint-disable` 주석 57건 확증

측정 실행에서 **「Definition for rule … was not found」 57건**(`@typescript-eslint/no-unused-vars` 35 ·
`react-refresh/only-export-components` 17 · `react-hooks/exhaustive-deps` 4 · `no-explicit-any` 1)이 나왔다.
측정 config 가 그 규칙들을 정의하지 않는데 소스의 `eslint-disable` 주석이 그것들을 참조하기 때문이다.
⇒ 정본의 「`eslint-disable` 주석 57건」이 **다른 경로로 재확인**됐다. R4 공허 경로 ①의 실물 근거.

### 성능 — 계약 테스트 안에서 ESLint 를 돌릴 수 있는가

| 스코프 | 파일 | 소요 |
|---|---|---|
| `src` 전량 | 1288 | (수십 초) |
| 비-테스트만 (`*.test.*`·`src/test/**`·`src/mocks/**` 제외) | **600** | **6초** |

⇒ 비-테스트 스코프면 계약 테스트 1건이 ESLint 를 **프로그래매틱 실행**해도 6초다.

### 재현 방법

측정용 config 2개는 worktree 에 **untracked** 로 남아 있다
(`apps/web/eslint.measure.mjs` · `eslint.measure30.mjs` — 이 세션의 `rm`/`mv` 가 권한 정책으로 차단됨).
**커밋 금지** — 모든 `git add` 는 명시 pathspec 만 쓴다. worktree 제거 시 함께 사라진다.

```bash
cd apps/web && node_modules/.bin/eslint src --config eslint.measure.mjs --format json > out.json
# ★집계 전 반드시. PARSE_ERROR === 0 단언
```

## 도메인 정리 (← /bts-domain 채움)

**스킵** — Maxi 확정 2026-08-11. 신규 도메인 용어·ADR 0건 (lint/테스트 도구 작업).

## 스펙

전체 스펙. [`docs/specs/2026-08-11-web-ratchet-placeholder-and-component-lines.md`](../specs/2026-08-11-web-ratchet-placeholder-and-component-lines.md)

**Maxi 확정 2건 (Phase 4 대안 중 선택).**
- **D1 = 27+1곳 전부 i18n 이전** → placeholder 예외목록 **0**
- **D2 = 계약 테스트 단독** → `eslint.config.js` 무변경, 계약 테스트가 ESLint 프로그래매틱 실행

**핵심 시나리오 3줄.**
- 새 한글 placeholder 하드코딩은 **`pnpm lint` 에서 막힌다** (기본·button예외·프리미티브 세 적용면 전부)
- 새 200줄 초과 컴포넌트, **그리고 기존 초과분의 증가**도 `pnpm test` 에서 막힌다 (감소는 통과 — 단조 래칫)
- **사용자가 보는 화면은 바이트 단위로 불변** — placeholder 문구는 값을 보존한 채 모듈로 옮기기만 한다

**이 PR 이 손대는 것.**

| 대상 | 변경 |
|---|---|
| `apps/web/eslint.config.js` | 셀렉터 2종 추가 (블록 2곳) · 테스트 `'off'` 블록을 맨 끝으로 이동 |
| `apps/web/src/i18n/**` | 28개 문자열 신규 키 (8파일은 기존 모듈 · 9파일은 배치 결정 필요) |
| `apps/web/src/**` 17+1파일 | placeholder 리터럴 → 상수 참조 (27 리터럴 + 1 템플릿) |
| 계약 테스트 1개 (신규) | R3 대조군 6종 + R4 베이스라인 18건 |
| `TODOS.md` · `docs/plan/product/personalization.md` | 항목 2건 해소 표기 + 중복 추적 흡수 |

⚠️ **PR #364 본문 정정 필요** — 「프로덕션 코드 0줄」은 D1 채택으로 거짓이 됐다 (17파일 27줄).

## Brainstorming Check

✅ **통과 (2회 iteration · gap 11건 발견 후 전량 보강)**

**★초안 처방이 `pnpm lint` 를 깨뜨렸다 (G1).** 정본의 「`no-restricted-syntax` 4곳을 고친다」를 그대로 옮기면
`src/components/ui/**` 블록이 **config 의 마지막**이라 그 밑 `*.test.tsx` 3건까지 error 가 된다.
⇒ 처방을 「배열 교체 + 테스트 블록 맨 끝 이동」으로 교체하고 음성 대조군으로 회귀를 봉인했다.

**★정본 한 문장을 실측이 정정했다 (G10).** 「이 규칙은 병합이 아니라 대체된다」는 **배열 지정에만 참**이다.
`'off'` 는 **앞 블록 옵션을 유지한 채 심각도만 0** 으로 바꾼다 (`--print-config` 로 두 형태 모두 확인).

나머지 gap 9건(G2~G9·G11)과 의도적 미해결 3건은 스펙 §Brainstorming Check 참조.

## Plan

**Goal.** apps/web 에 강제 수단 2종을 설치한다 — 한글 placeholder 하드코딩은 `pnpm lint` 가,
200줄 초과 컴포넌트의 신규·증가는 `pnpm test` 가 막는다. 사용자가 보는 화면은 바이트 단위로 불변.

**Architecture.** R3 은 ESLint `no-restricted-syntax` AST 선택자(규칙이 사는 곳 = `eslint.config.js`),
R4 는 계약 테스트가 ESLint 를 **프로그래매틱 실행**해 동결 베이스라인과 비교(규칙이 사는 곳 = 테스트).
두 래칫의 검증은 **계약 테스트 파일 하나**를 공유하되 ESLint 인스턴스는 2개다 (실제 config 로드 / 자체 config).

**Tech Stack.** ESLint 9 flat config · `ESLint` Node API (`lintText`·`lintFiles`) · `typescript-eslint` 파서 · vitest.

### 파일 구조

| 파일 | 책임 | 상태 |
|---|---|---|
| `apps/web/eslint.config.js` | R3 선택자 2종 · 블록 순서 | 수정 |
| `apps/web/src/test/lint-ratchet.test.ts` | R3 대조군 6종 + R4 판정 3종 + 비-공허 단언 | **신규** |
| `apps/web/src/test/lint-ratchet-baseline.ts` | R4 동결 베이스라인 18건 (데이터만) | **신규** |
| `apps/web/src/i18n/resolution-labels.ts` | 「결의안…」 3파일 공용 | **신규** |
| `apps/web/src/i18n/global-permission-labels.ts` | 전역 권한 다이얼로그 | **신규** |
| `apps/web/src/i18n/search-labels.ts` | AQL 검색 | **신규** |
| `apps/web/src/i18n/{board,custom-field,dashboard,import,issue-template,bulk-operation,workflow-scheme,ko}-labels?.ts` | 기존 모듈에 키 추가 | 수정 |
| 컴포넌트 18파일 | 리터럴 → 상수 참조 | 수정 |
| `TODOS.md` · `docs/plan/product/personalization.md` | 항목 해소 · 중복 흡수 | 수정 |

베이스라인을 **별도 파일**로 두는 이유. 래칫이 조여지는 순간이 diff 에서 한눈에 보여야 하고,
테스트 로직 변경과 데이터 변경이 섞이면 리뷰가 어느 쪽인지 구분하지 못한다.

---

### Task 1. R3 — ESLint 래칫 설치 + 대조군 6종

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/test/lint-ratchet.test.ts`, `apps/web/eslint.config.js`]
- depends-on: []

**RED**.
- 파일. `apps/web/src/test/lint-ratchet.test.ts` (신규)
- 첫 줄에 한국어 헤더 주석 필수 (`CLAUDE.md §6`).

```ts
// apps/web 강제 수단 래칫 2종의 계약 테스트 — R3 placeholder 규칙 발화 확인 + R4 줄수 베이스라인 판정
import { describe, expect, it } from 'vitest'
import { ESLint } from 'eslint'
import { resolve } from 'node:path'

/** `apps/web` 루트. 이 파일은 `src/test/` 에 있다. */
const WEB_ROOT = resolve(__dirname, '../..')

/**
 * 실제 `eslint.config.js` 를 로드하는 인스턴스.
 * ★자체 config 로 대체하면 「저장소의 진짜 설정이 막는가」를 못 본다 — 그게 이 테스트의 존재 이유다.
 */
const realEslint = new ESLint({ cwd: WEB_ROOT })

/**
 * placeholder 락 위반인지 판정하는 **단일 술어**.
 * ★리뷰 ③A — 헬퍼마다 기준이 다르면(하나는 규칙 전체를 세고 하나는 메시지로 거른다)
 *   같은 것을 두 방식으로 세게 되고, 한쪽만 고쳐지는 순간 두 수치가 조용히 갈라진다.
 */
const isPlaceholderLockViolation = (m: { ruleId: string | null; message: string }): boolean =>
  m.ruleId === 'no-restricted-syntax' && m.message.includes(PLACEHOLDER_LOCK_TAG)

/** 가상 경로로 소스를 던져 위반 수만 센다. 파일을 만들지 않으므로 자기탐지가 없다. */
async function violations(code: string, filePath: string): Promise<number> {
  const [result] = await realEslint.lintText(code, { filePath, warnIgnored: false })
  return (result?.messages ?? []).filter(isPlaceholderLockViolation).length
}

const KO_LITERAL = '<input placeholder="검색어" />'
const KO_TEMPLATE = '<input placeholder={`${x} 검색`} />'
const EN_LITERAL = '<input placeholder="Search" />'

describe('R3. 한글 placeholder 래칫', () => {
  it('양성 ① 기본 적용면에서 한글 리터럴을 막는다', async () => {
    expect(await violations(KO_LITERAL, 'src/components/__probe__/Probe.tsx')).toBe(1)
  })

  it('양성 ② button 예외 블록 안의 파일에서도 막는다', async () => {
    // 이 블록은 배열이라 앞 블록을 **대체**한다. 안 고치면 DashboardForm 3건·
    // GlobalPermissionFormDialog 1건 = 28건 중 4건이 영구 면제된다.
    //
    // ★리뷰 ②A — 경로를 손으로 적으면, 그 파일이 나중에 예외 목록에서 빠지는 순간
    //   이 대조군은 「button 예외 블록」을 전혀 검사하지 않는데도 계속 green 이 된다.
    //   목록을 적지 않고 **설정에서 도출**한다 (button-primitive-usage.test.ts 선례).
    const [exemptFile] = BUTTON_PRIMITIVE_EXEMPT_FILES
    expect(exemptFile).toBeDefined() // 비-공허 짝. 도출이 빈 배열이면 대조군이 사라진다
    expect(await violations(KO_LITERAL, exemptFile)).toBe(1)
  })

  it('양성 ③ 프리미티브 레이어에서도 막는다', async () => {
    expect(await violations(KO_LITERAL, 'src/components/ui/__probe__.tsx')).toBe(1)
  })

  it('양성 ④ 템플릿 리터럴 우회를 막는다', async () => {
    expect(await violations(KO_TEMPLATE, 'src/components/__probe__/Probe.tsx')).toBe(1)
  })

  it('음성 ⑤ 영문 placeholder 는 막지 않는다', async () => {
    // 이게 없으면 「무조건 위반」 규칙도 위 4종을 통과한다.
    expect(await violations(EN_LITERAL, 'src/components/__probe__/Probe.tsx')).toBe(0)
  })

  it('음성 ⑥ 테스트 파일은 대상이 아니다 (블록 순서 회귀 가드)', async () => {
    // src/components/ui/** 블록을 배열로 바꾸면서 그 블록이 마지막에 남으면
    // ui 아래 테스트까지 켜져 badge.test.tsx 2건·command.test.tsx 1건이 red 가 된다.
    expect(await violations(KO_LITERAL, 'src/components/ui/__probe__.test.tsx')).toBe(0)
  })
})
```

- 실행. `cd apps/web && node_modules/.bin/vitest run src/test/lint-ratchet.test.ts`
- 기대. **양성 ①②③④ 4건 FAIL** (`expected 0 to be 1` — 규칙 미존재) · 음성 ⑤⑥ PASS

**GREEN**.
- 파일. `apps/web/eslint.config.js`
- ⓪ **named export 2개를 추가한다** (ESLint 는 default export 만 읽으므로 무해하다).
  테스트가 목록·태그를 **손으로 베끼지 않고 도출**하게 하는 것이 목적이다 (리뷰 ②A·③A).

```js
/** 위반 메시지에 심는 식별 태그. 테스트가 이 태그로 placeholder 락 위반만 골라낸다. */
export const PLACEHOLDER_LOCK_TAG = 'R3 래칫'

/**
 * 원시 `<button>` 을 남기기로 판정한 파일 전수 (FR-UX-06 PR22).
 * ★아래 예외 블록과 계약 테스트가 **같은 배열을 참조**한다 — 손으로 두 벌 적으면
 *   한쪽만 바뀌어도 아무도 모른다(`[[two-lists-never-check-each-other]]`).
 */
export const BUTTON_PRIMITIVE_EXEMPT_FILES = [
  /* 기존 :85-109 목록을 그대로 옮긴다 (내용 변경 0) */
]
```

- ① 공용 상수를 파일 상단(`import` 아래)에 둔다. 세 블록이 **같은 배열 리터럴을 복붙**하면
  한 곳만 고쳐지는 사고가 난다 (`[[two-lists-never-check-each-other]]`).
  메시지 끝에 `PLACEHOLDER_LOCK_TAG` 를 포함시킨다.

```js
// 한글 placeholder 하드코딩 락 (R3). 세 적용면이 공유한다 — 복붙하면 한 곳만 고쳐지는 사고가 난다.
// AST 를 보므로 출처 판별이 성립한다. 렌더 단언은 i18n 값과 하드코딩 값이 바이트 동일하면
// DOM 속성 문자열이 같아 「속성값은 출처를 싣지 않는다」 — 못 잡는다.
const PLACEHOLDER_I18N_LOCK = [
  {
    selector: "JSXAttribute[name.name='placeholder'] Literal[value=/[가-힣]/]",
    message:
      '한글 placeholder 를 하드코딩하지 마세요. src/i18n/<feature>-labels.ts 에 키를 만들고 참조하세요 (R3 래칫).',
  },
  {
    selector: "JSXAttribute[name.name='placeholder'] TemplateElement[value.raw=/[가-힣]/]",
    message:
      '한글 placeholder 를 템플릿 리터럴로도 하드코딩하지 마세요. src/i18n/<feature>-labels.ts 를 쓰세요 (R3 래칫).',
  },
]
```

- ② 기본 배열(`:59`)에 전개한다. `'no-restricted-syntax': ['error', {button…}, {animate-pulse…}, ...PLACEHOLDER_I18N_LOCK]`
- ③ button 예외 배열(`:113`)에 전개한다. `['error', {animate-pulse…}, ...PLACEHOLDER_I18N_LOCK]`
- ④ `src/components/ui/**` 블록(`:128-139`)의 `'no-restricted-syntax': 'off'` 를
  `'no-restricted-syntax': ['error', ...PLACEHOLDER_I18N_LOCK]` 로 바꾼다.
  원시 `<button>`·`animate-pulse` 는 이 레이어가 **정의처**라 빠진 채로 둔다(= 계속 허용).
- ⑤ **테스트 `'off'` 블록(현재 `:123-127`)을 config 배열의 맨 끝으로 옮긴다.**
  ★이동은 동작 보존이다 — 지금도 `src/components/ui/**/*.test.tsx` 는 두 블록 모두 `'off'` 라
  `--print-config` 결과가 같다. 옮겨 두면 뒤 블록이 테스트를 다시 켜는 순서 함정이 영구히 닫힌다.

- 실행. 같은 vitest 명령
- 기대. **6/6 PASS**

**REFACTOR**.
- `'off'` 와 배열의 동작 차이를 블록 위 주석으로 남긴다 — 「`'off'` 는 앞 블록 **옵션을 유지**하고
  심각도만 0 으로 바꾼다. 배열은 **완전 대체**다. 이 차이를 모르면 잘못된 처방으로 간다」
  (2026-08-11 `--print-config` 실측).

**검증**.
```bash
cd apps/web
node_modules/.bin/vitest run src/test/lint-ratchet.test.ts   # 6/6 PASS
node_modules/.bin/eslint --print-config src/components/ui/badge.test.tsx \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s).rules["no-restricted-syntax"];console.log("severity",r[0])})'
# 기대. severity 0  (테스트는 여전히 off)
```
⚠️ **이 task 종료 시점에 `node_modules/.bin/eslint src` 는 28건 red 다.** 의도된 중간 상태이고 Task 2 가 해소한다.

**커밋**. `test:` 커밋(RED) → `feat:` 커밋(GREEN) 순서 필수.

---

### Task 2. R3 — 한글 placeholder 28곳 i18n 이전

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/test/lint-ratchet.test.ts`, `apps/web/src/i18n/resolution-labels.ts`, `apps/web/src/i18n/global-permission-labels.ts`, `apps/web/src/i18n/search-labels.ts`, `apps/web/src/i18n/board-labels.ts`, `apps/web/src/i18n/custom-field-labels.ts`, `apps/web/src/i18n/dashboard-labels.ts`, `apps/web/src/i18n/import-labels.ts`, `apps/web/src/i18n/issue-template-labels.ts`, `apps/web/src/i18n/bulk-operation-labels.ts`, `apps/web/src/i18n/workflow-scheme-labels.ts`, `apps/web/src/i18n/ko.ts`, `apps/web/src/components/board/CreateBoardForm.tsx`, `apps/web/src/components/board/ResolutionPickerModal.tsx`, `apps/web/src/components/custom-fields/CustomFieldFormDialog.tsx`, `apps/web/src/components/custom-fields/CustomFieldInput.tsx`, `apps/web/src/components/dashboard/DashboardForm.tsx`, `apps/web/src/components/dashboard/GadgetConfigForm.tsx`, `apps/web/src/components/global-permissions/GlobalPermissionFormDialog.tsx`, `apps/web/src/components/import/mapping/UserMappingStep.tsx`, `apps/web/src/components/issue-templates/IssueTemplateFormDialog.tsx`, `apps/web/src/components/issue-templates/TemplateContentField.tsx`, `apps/web/src/components/issue/ResolutionModal.tsx`, `apps/web/src/components/issue/ComponentMultiSelect.tsx`, `apps/web/src/components/issues/BulkTransitionDialog.tsx`, `apps/web/src/components/issues/cells/AssigneeCell.tsx`, `apps/web/src/routes/admin.workflow-schemes.new.tsx`, `apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/projects.$projectKey.settings.workflow-scheme.tsx`, `apps/web/src/routes/search.tsx`]
- depends-on: [1]

**RED**.
- 파일. `apps/web/src/test/lint-ratchet.test.ts` (Task 1 파일에 describe 추가)

```ts
/**
 * 소스 전량을 **실제 config** 로 훑어 placeholder 락 위반 좌표를 모은다.
 * ★리뷰 ③A — 메모화. 이 린트는 1288파일이라 가장 비싸다. 두 번 돌 이유가 없다.
 */
let placeholderCache: Promise<string[]> | undefined
const productionPlaceholderHits = () => (placeholderCache ??= computePlaceholderHits())

async function computePlaceholderHits(): Promise<string[]> {
  const results = await realEslint.lintFiles(['src'])
  // ★집계 전에 파싱 실패 0 을 먼저 단언한다. 2026-08-11 이 세션의 1차 측정이
  //   파서 미부착으로 1222건 파싱 실패했고 히트 0 을 「깨끗함」으로 오독했다.
  expect(results.flatMap((r) => r.messages).filter((m) => m.fatal).map((m) => m.message)).toEqual([])
  expect(results.length).toBeGreaterThan(500) // 비-공허. 실측 1288
  return results.flatMap((r) =>
    r.messages
      .filter(isPlaceholderLockViolation) // ★리뷰 ③A — 대조군과 **같은 술어**를 쓴다
      .map((m) => `${r.filePath.replace(`${WEB_ROOT}/`, '')}:${m.line}`),
  )
}

it('비-테스트 소스에 한글 placeholder 하드코딩이 0건이다', async () => {
  // 개수가 아니라 목록 전수 비교 — 개수 가드는 하나 고치고 하나 늘리면 통과한다.
  expect(await productionPlaceholderHits()).toEqual([])
}, 60_000)
```

- 실행. `cd apps/web && node_modules/.bin/vitest run src/test/lint-ratchet.test.ts`
- 기대. **FAIL** — 28개 좌표가 배열로 출력된다

**GREEN**. 아래 표대로 28건을 옮긴다. **값은 바이트 단위로 보존한다** (한 글자도 바꾸지 않는다).

| # | 문자열 | 대상 모듈 | 키 | 참조 위치 |
|---|---|---|---|---|
| 1 | `스프린트 보드` | `board-labels` | `createFormNamePlaceholder` | `components/board/CreateBoardForm.tsx:109` |
| 2 | `결의안을 선택하세요` | **`resolution-labels`**(신규) | `selectPlaceholder` | `components/board/ResolutionPickerModal.tsx:96` |
| 3 | `값` | `custom-field-labels` | `optionValuePlaceholder` | `components/custom-fields/CustomFieldFormDialog.tsx:93` |
| 4 | `라벨` | `custom-field-labels` | `optionLabelPlaceholder` | `…CustomFieldFormDialog.tsx:107` |
| 5 | `예: priority` | `custom-field-labels` | `keyPlaceholder` | `…CustomFieldFormDialog.tsx:222` |
| 6 | `예: 우선순위` | `custom-field-labels` | `namePlaceholder` | `…CustomFieldFormDialog.tsx:244` |
| 7 | `선택 입력` | `custom-field-labels` | `descriptionPlaceholder` | `…CustomFieldFormDialog.tsx:265` |
| 8 | `선택하세요` | `custom-field-labels` | `inputSelectPlaceholder` | `components/custom-fields/CustomFieldInput.tsx:174` |
| 9 | `사용자 이름 검색` | `dashboard-labels` | `ownerSearchPlaceholder` | `components/dashboard/DashboardForm.tsx:161` |
| 10 | `대시보드 이름` | `dashboard-labels` | `namePlaceholder` | `…DashboardForm.tsx:294` |
| 11 | `대시보드 설명 (선택)` | `dashboard-labels` | `descriptionPlaceholder` | `…DashboardForm.tsx:317` |
| 12 | `마크다운 텍스트를 입력하세요...` | `dashboard-labels` | `markdownGadgetPlaceholder` | `components/dashboard/GadgetConfigForm.tsx:172` |
| 13 | `이름 또는 아이디로 검색 (2자 이상)` | **`global-permission-labels`**(신규) | `subjectSearchPlaceholder` | `components/global-permissions/GlobalPermissionFormDialog.tsx:79` |
| 14 | `다른 사용자 검색` | `import-labels` | `userMappingSearchPlaceholder` | `components/import/mapping/UserMappingStep.tsx:171` |
| 15 | `예: 버그 리포트 기본 템플릿` | `issue-template-labels` | `namePlaceholder` | `components/issue-templates/IssueTemplateFormDialog.tsx:228` |
| 16 | `예: 버그 리포트 기본 템플릿` | `issue-template-labels` | `namePlaceholder` (**#15 와 동일 키 재사용**) | `…IssueTemplateFormDialog.tsx:315` |
| 17 | `Markdown 형식으로 본문을 입력하세요.` | `issue-template-labels` | `contentPlaceholder` | `components/issue-templates/TemplateContentField.tsx:191` |
| 18 | `결의안을 선택하세요` | **`resolution-labels`** | `selectPlaceholder` (**#2 재사용**) | `components/issue/ResolutionModal.tsx:136` |
| 19 | `상태를 선택하세요` | `bulk-operation-labels` | `statusSelectPlaceholder` | `components/issues/BulkTransitionDialog.tsx:214` |
| 20 | `결의안을 선택하세요` | **`resolution-labels`** | `selectPlaceholder` (**#2 재사용**) | `…BulkTransitionDialog.tsx:238` |
| 21 | `이름으로 검색` | `ko.ts` → `issueDetailStrings` | `assigneeCellSearchPlaceholder` | `components/issues/cells/AssigneeCell.tsx:130` |
| 22 | `예: my-scheme-01` | `workflow-scheme-labels` | `keyPlaceholder` | `routes/admin.workflow-schemes.new.tsx:101` |
| 23 | `스킴 이름을 입력하세요` | `workflow-scheme-labels` | `namePlaceholder` | `…admin.workflow-schemes.new.tsx:123` |
| 24 | `스킴 설명을 입력하세요` | `workflow-scheme-labels` | `descriptionPlaceholder` | `…admin.workflow-schemes.new.tsx:142` |
| 25 | `보드 선택` | `board-labels` | `boardSelectPlaceholder` | `routes/projects.$projectKey.board.tsx:218` |
| 26 | `스킴 선택...` | `workflow-scheme-labels` | `schemeSelectPlaceholder` | `routes/projects.$projectKey.settings.workflow-scheme.tsx:173` |
| 27 | `AQL 쿼리를 입력하세요. 예: status = open AND priority IN (1, 2), text ~ "로그인"` | **`search-labels`**(신규) | `aqlPlaceholder` | `routes/search.tsx:353` |
| 28 | ``${issueDetailStrings.componentsLabel} 검색`` (템플릿) | `ko.ts` → `issueDetailStrings` | `componentsSearchPlaceholder(label: string)` **함수형** | `components/issue/ComponentMultiSelect.tsx:78` |

- **#28 주의.** 보간이 있으므로 문자열이 아니라 **함수**로 만든다.
  `componentsSearchPlaceholder: (label: string) => \`${label} 검색\`` 로 두고
  호출부는 `placeholder={issueDetailStrings.componentsSearchPlaceholder(issueDetailStrings.componentsLabel)}`.
  ★템플릿을 그대로 두면 `TemplateElement` 선택자에 계속 걸린다.
- **신규 모듈 3개**는 기존 모듈 형식을 따른다 — 첫 줄 한국어 헤더 주석 + `export const <feature>Labels = { … } as const`.

- 실행. 같은 vitest 명령 → **PASS**

**REFACTOR**.
- `CustomFieldFormDialog.test.tsx:136·147·159` 의 `getAllByPlaceholderText('값')` 를
  `getAllByPlaceholderText(customFieldLabels.optionValuePlaceholder)` 로 바꾼다.
  ★값을 보존하므로 **바꾸지 않아도 통과한다.** 그래도 바꾸는 이유는 다음에 문구를 고칠 때
  이 3줄이 조용히 깨지기 때문이다.

**검증**.
```bash
cd apps/web
node_modules/.bin/eslint src > /tmp/lint.txt 2>&1; echo "EXIT=$?"   # 기대 0
node_modules/.bin/vitest run                                        # 전량 green
node_modules/.bin/tsc -p tsconfig.app.json --noEmit; echo "EXIT=$?" # 기대 0
```
**값 보존 확인 (D-4 · 기계적 절차).** 위 표 28개 문자열 각각에 대해.
```bash
# 각 문자열이 저장소에서 정확히 1회, 그 1회가 i18n 모듈 안에서 발견돼야 한다.
grep -rF '<문자열>' apps/web/src --include='*.ts' --include='*.tsx' | grep -v '\.test\.'
# 0회 → 이전 중 오타 · 2회 이상 → 미이전 잔류
```
(#15·#16 은 같은 문자열을 키 하나로 합치므로 **1회**가 맞다. #2·#18·#20 도 동일.)

**커밋**. `test:`(RED) → `feat:`(GREEN) → `refactor:` 순.

---

### Task 3. R4 — 줄수 베이스라인 + 단조 판정

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/test/lint-ratchet-baseline.ts`, `apps/web/src/test/lint-ratchet.test.ts`]
- depends-on: [1]

**RED**.
- 파일 ①. `apps/web/src/test/lint-ratchet-baseline.ts` (신규) — **빈 상태로 먼저 만든다**

```ts
// R4 줄수 래칫의 동결 베이스라인 — 200줄을 넘는 비-테스트 함수의 현재 상태를 얼린다.
//
// 키 = `<apps/web 기준 상대경로>::<ESLint 서술자>`. 값 = raw 줄수(빈 줄·주석 포함).
// ★줄인 뒤에는 이 숫자를 **함께 낮춰라.** 낮추지 않으면 그만큼 다시 늘릴 여지가 남는다.
// ★새 항목을 여기 추가하는 것은 「200줄 넘는 컴포넌트를 하나 더 승인한다」는 뜻이다. 리뷰에서 그렇게 읽어라.
export const OVERSIZED_FUNCTION_BASELINE: Readonly<Record<string, number>> = {}
```

- 파일 ②. `lint-ratchet.test.ts` 에 R4 describe 추가

```ts
import tseslint from 'typescript-eslint'
import { OVERSIZED_FUNCTION_BASELINE } from './lint-ratchet-baseline'

const MAX_COMPONENT_LINES = 200

/**
 * R4 전용 인스턴스. `eslint.config.js` 를 **로드하지 않는다**(R4-FR5) —
 * 줄수 규칙은 이 테스트가 들고 있고 저장소 설정은 건드리지 않는다.
 * `noInlineConfig` 로 소스의 `eslint-disable` 주석 우회를 무력화한다(현재 57건 존재).
 */
const ratchetEslint = new ESLint({
  cwd: WEB_ROOT,
  overrideConfigFile: true,
  overrideConfig: [
    { ignores: ['**/*.test.ts', '**/*.test.tsx', 'src/test/**', 'src/mocks/**', 'dist/**'] },
    {
      files: ['**/*.{ts,tsx}'],
      languageOptions: {
        // ★파서를 빠뜨리면 .tsx 가 통째로 파싱 실패하고 히트 0 이 「깨끗함」으로 읽힌다.
        parser: tseslint.parser,
        parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
      },
      linterOptions: { noInlineConfig: true },
      rules: { 'max-lines-per-function': ['error', { max: MAX_COMPONENT_LINES }] },
    },
  ],
})

const LINES_RE = /^(.*?) has too many lines \((\d+)\)/

/**
 * ★리뷰 ③A — 판정 3개가 각자 부르면 600파일 린트가 3번 돈다.
 * 모듈 레벨에서 **한 번만** 계산해 돌려쓴다. `beforeAll` 이 아니라 메모화된 Promise 인 이유는
 * R3 쪽 헬퍼와 호출 시점이 달라도 같은 결과를 공유해야 하기 때문이다.
 */
let oversizedCache: Promise<{ entries: Map<string, number>; dupes: string[] }> | undefined
const oversizedFunctions = () => (oversizedCache ??= computeOversizedFunctions())

async function computeOversizedFunctions(): Promise<{
  entries: Map<string, number>
  dupes: string[]
}> {
  const results = await ratchetEslint.lintFiles(['src'])
  expect(results.flatMap((r) => r.messages).filter((m) => m.fatal).map((m) => m.message)).toEqual([])
  expect(results.length).toBeGreaterThan(500) // 비-공허. 실측 600

  const entries = new Map<string, number>()
  const dupes: string[] = []
  for (const r of results) {
    const rel = r.filePath.replace(`${WEB_ROOT}/`, '') // ★상대 경로 — worktree·CI 절대경로가 다르다
    for (const m of r.messages) {
      if (m.ruleId !== 'max-lines-per-function') continue
      const parsed = LINES_RE.exec(m.message)
      if (!parsed) throw new Error(`메시지 형식이 바뀌었다. 파서를 갱신하라: ${m.message}`)
      const key = `${rel}::${parsed[1]}`
      if (entries.has(key)) dupes.push(key)
      entries.set(key, Number(parsed[2]))
    }
  }
  return { entries, dupes }
}

describe('R4. 컴포넌트 200줄 래칫 (단조)', () => {
  it('베이스라인에 없는 신규 위반이 없다', async () => {
    const { entries } = await oversizedFunctions()
    expect(entries.size).toBeGreaterThan(0) // 비-공허. 스캔이 비면 모든 단언이 참이 된다
    const unknown = [...entries.keys()].filter((k) => !(k in OVERSIZED_FUNCTION_BASELINE)).sort()
    // 목록 전수 비교 — 개수 상한은 하나 고치고 하나 늘리면 통과한다.
    expect(unknown).toEqual([])
  }, 60_000)

  it('베이스라인 대비 늘어난 함수가 없다', async () => {
    const { entries } = await oversizedFunctions()
    const grown = [...entries]
      .filter(([k, n]) => k in OVERSIZED_FUNCTION_BASELINE && n > OVERSIZED_FUNCTION_BASELINE[k])
      .map(([k, n]) => `${k}: ${OVERSIZED_FUNCTION_BASELINE[k]} → ${n}`)
      .sort()
    expect(grown).toEqual([])
  }, 60_000)

  it('같은 키가 두 번 나오지 않는다 (키 충돌 감지)', async () => {
    // 익명 화살표가 한 파일에 둘 이상 200줄을 넘기면 키가 겹쳐 하나가 조용히 사라진다.
    expect((await oversizedFunctions()).dupes).toEqual([])
  }, 60_000)
})
```

- 실행. `cd apps/web && node_modules/.bin/vitest run src/test/lint-ratchet.test.ts`
- 기대. **첫 테스트 FAIL** — `unknown` 에 18개 키가 나열된다

**GREEN**. 실패 출력의 18개 키를 그대로 베이스라인에 옮긴다.

```ts
export const OVERSIZED_FUNCTION_BASELINE: Readonly<Record<string, number>> = {
  "src/routes/issues.$key.tsx::Function 'IssueDetailPage'": 1041,
  "src/routes/projects.$projectKey.board.tsx::Function 'BoardPage'": 367,
  "src/components/issue/IssueDescription.tsx::Function 'EditMode'": 337,
  "src/components/search/ExportDialog.tsx::Function 'ExportForm'": 334,
  "src/routes/dashboards.$dashboardId.tsx::Function 'DashboardDetailPage'": 332,
  "src/routes/issues.index.tsx::Function 'IssueListPage'": 331,
  "src/components/issues/MoveIssueDialog.tsx::Function 'MoveIssueDialog'": 326,
  "src/components/issue/IssueMetaPanel.tsx::Function 'IssueMetaPanel'": 317,
  'src/components/import/mapping/ImportMappingWizard.tsx::Arrow function': 288,
  "src/components/issue/mention/use-mention-autocomplete.ts::Function 'useMentionAutocomplete'": 272,
  "src/components/issue-templates/IssueTemplateFormDialog.tsx::Function 'FormBody'": 259,
  "src/routes/settings.account-links.tsx::Function 'AccountLinksSettingsPage'": 257,
  "src/components/issues/NodeMappingSection.tsx::Function 'NodeMappingSection'": 232,
  "src/components/automation/AutomationRuleFormDialog.tsx::Function 'FormBody'": 230,
  "src/components/issue/IssueCreateForm.tsx::Function 'IssueCreateForm'": 227,
  "src/components/custom-fields/CustomFieldFormDialog.tsx::Function 'FormBody'": 225,
  "src/components/auth/MfaSettings.tsx::Function 'MfaSettings'": 218,
  "src/components/issues/BulkTransitionDialog.tsx::Function 'BulkTransitionDialog'": 212,
}
```

⚠️ **Task 2 가 `IssueTemplateFormDialog`·`CustomFieldFormDialog` 등에 import 줄을 더하므로 줄수가 바뀔 수 있다.**
숫자는 **손으로 적지 말고 RED 실행 출력에서 복사**한다.

- 실행. 같은 명령 → **3/3 PASS**

**REFACTOR**. 실패 메시지에 rename 안내를 넣는다 — 「파일을 옮겼다면 베이스라인 키도 함께 고쳐라」.

**검증 (뮤테이션 3종 — 가드가 진짜 잡는지)**. 각 실행 후 **반드시 원복**한다.
| # | 주입 | 기대 |
|---|---|---|
| M1 신규 | `src/components/board/CreateBoardForm.tsx` 의 컴포넌트에 빈 줄 250개 추가 | 「신규 위반」 red |
| M2 증가 | 베이스라인의 `IssueCreateForm` 값을 `227` → `210` 으로 낮춤 | 「늘어난 함수」 red |
| M3 감소 | 베이스라인의 `IssueCreateForm` 값을 `227` → `900` 으로 올림 | **green** (단조 — 감소는 통과) |
| **M4 파서** | `ratchetEslint` 의 `languageOptions.parser` 줄을 지움 | **PARSE_ERROR 단언이 red** |

★**M4 가 리뷰 ④A 의 산물이다.** 이 세션의 1차 측정이 정확히 그 함정에 빠졌다 — 파서를 안 붙여
1288 중 1222 파일이 조용히 파싱 실패했고 히트 0 이 「깨끗함」으로 읽혔다.
그래서 `PARSE_ERROR === 0` 단언을 넣었는데, **그 단언 자체가 공허하지 않은지는 따로 재야 한다.**
M4 가 green 이면 진단 장치가 고장 나 있는 것이고, 그건 이번 세션이 실제로 겪은 상태다.

★M2·M3 은 소스가 아니라 **베이스라인을 흔들어** 판정 방향을 확인한다. 원복은
`git checkout -- src/test/lint-ratchet-baseline.ts` 이며 **GREEN 이 먼저 커밋돼 있어야 한다**
(`[[mutation-test-requires-committed-baseline]]`).

**커밋**. `test:`(RED) → `feat:`(GREEN) 순.

---

### Task 4. 정본 문서 동기화

**메타**.
- agent: `frontend-engineer`
- files: [`TODOS.md`, `docs/plan/product/personalization.md`, `docs/plans/2026-08-11-web-ratchet-placeholder-and-component-lines.md`]
- depends-on: [2, 3]

TDD 대상 아님 (문서). **전수 동기화 원칙** (`CLAUDE.md §명세/범위 변경 시 전수 동기화`) 적용.

- ① `TODOS.md:2092` 「한글 리터럴 placeholder 27곳」 → `⬜` 를 `✅` 로, 해소 요약 추가.
  **남는 것을 명시한다** — 함수 기본 파라미터 1건(`LabelAutocompleteInput.tsx:79`) ·
  변수 경유 10곳/9파일은 **차단 범위 밖**이며 별도 부채다.
- ② `TODOS.md:2927` 「200줄 초과 컴포넌트 18건 · 강제 수단 0」 → `✅`.
  **강제 수단이 생겼을 뿐 18건은 그대로 남아 있음**을 명시. `TODOS.md:2471`
  (`IssueCreateForm` 227줄 개별 부채)은 **닫지 않는다** — 상환이 아니라 동결이다.
- ③ `docs/plan/product/personalization.md:479-486` ⑤ 「라벨 5종이 컴포넌트 모듈 잔류」를
  이 항목으로 흡수하고 상호 링크를 남긴다 (`[[two-lists-never-check-each-other]]` 신규 생성 방지).
- ④ PR #364 본문의 「프로덕션 코드 0줄」을 정정한다 (D1 채택으로 17파일 27줄).

**검증**.
```bash
bash scripts/verify-master-plan.sh; echo "EXIT=$?"   # 기대 0
node scripts/build-doc-index.mjs                     # 인덱스 재생성 (훅이 강제)
```

---

## Plan 메타

- **task 수: 4**
- 예상 시간. 직렬 기준 약 40분 (T2 의 28곳 이전이 가장 큼). **wave 는 1개 — 전 task 직렬**
  (T2·T3 이 `lint-ratchet.test.ts` 를 공유해 파일 겹침으로 자동 직렬화, T4 는 `depends-on: [2,3]`)
- 구현 규율. **TDD red→green→refactor** (type=chore override — `ui` 시각 검증 트랙 **아님**. 화면 변경 0)
- 추가 검증. `tsc --noEmit` · `eslint src` · `vitest run` 전량 · 뮤테이션 3종 · `verify-master-plan.sh`
- **worktree 실행 규율**. `pnpm` 래퍼 금지, `node_modules/.bin/*` 직접 호출
  (`[[worktree-pnpm-verify-deps-symlink]]` — pnpm 이 main 의 `.modules.yaml` 을 오염시킨다)
- **미정리 잔여물**. `apps/web/eslint.measure.mjs` · `eslint.measure30.mjs` 가 untracked 로 남아 있다
  (이 세션의 `rm`/`mv` 가 권한 정책으로 차단됨). **커밋 금지 — 모든 `git add` 는 명시 pathspec.**

## 리뷰 결과

### plan-eng-review (2026-08-11)

`TYPE == chore` 는 `bts-review-plan` 표상 skip 이지만 **Maxi 가 fast-track 을 명시적으로 껐다.**
분류기 원출력 `ui` 의 `plan-design-review`(디자이너 눈)는 시각 변경 0 이라 공회전이므로 eng-review 로 갔다.
Codex 외부 목소리는 Maxi 선택으로 미실행 (표준 5단계).

**Step 0 · 범위 도전.** 복잡도 트리거 **발동**(35파일 > 8). 재론하지 않고 진행했다 —
35 중 28 은 **한 줄짜리 기계적 치환**이고 새 기전은 2개뿐이라 트리거의 취지(움직이는 부품 수)에 해당하지 않는다.
D1 선택 시 「프로덕션 diff 17파일 27줄」이 옵션 preview 에 명시돼 있었다.

**발견 4건 — 전부 Maxi 승인 후 반영 완료.**

| # | 심각도 | 신뢰도 | 발견 | 처방 | 반영 |
|---|---|---|---|---|---|
| **①A** | P2 | 9/10 | **ESLint 9.39 에 네이티브 래칫(`--suppress-all`)이 실재한다.** 손수 만든 베이스라인이 재발명일 수 있다 | **커스텀 유지.** 실측으로 억제 파일이 `{"count":1}` 형태 = **위반 개수만** 기록임을 확인 → 1041→1500 줄수 증가를 **못 잡는다**. 그게 D2 에서 ESLint 단독을 기각한 바로 그 이유다 | 유지 (근거 기록) |
| **②A** | P2 | 8/10 | 대조군 ②가 `DashboardForm.tsx` **경로를 손으로 적어** button 예외 블록을 검사한다. 그 파일이 목록에서 빠지면 **아무것도 검사하지 않는데 계속 green** | `eslint.config.js` 가 `BUTTON_PRIMITIVE_EXEMPT_FILES` 를 **named export** 하고 테스트가 그걸 도출해 쓴다 + `toBeDefined()` 비-공허 짝 | T1 RED·GREEN |
| **③A** | P2 | 9/10 | 같은 린트를 **4회** 실행(600×3 + 1288×1). 게다가 두 헬퍼의 **판정 기준이 불일치**(규칙 전체 카운트 vs 메시지 필터) | 메모화된 Promise 로 인스턴스당 1회 + `isPlaceholderLockViolation` **단일 술어**로 통일. `PLACEHOLDER_LOCK_TAG` 도 config 에서 도출 | T1·T2·T3 |
| **④A** | P2 | 8/10 | 「PARSE_ERROR 0」 단언 **자체의 비-공허를 안 쟀다.** 이 세션 1차 측정이 정확히 그 함정에 빠졌었다 | 뮤테이션 **M4**(파서 제거 → red 여야 함) 추가 | T3 검증 |

**섹션별.** ①아키텍처 2건(①A·②A) · ②코드 품질 1건(③A) · ③테스트 1 gap(④A) · ④성능 0건(③A 에 흡수).
**BLOCKER: 없음.**

### 필수 산출물

**NOT in scope (고려했으나 명시적으로 제외).**

| 항목 | 사유 |
|---|---|
| 「함수 30줄」 강제 | 비-테스트 445건/269파일. 예외목록으로 얼릴 규모가 아님 |
| 변수 경유 placeholder 10곳/9파일 · 함수 기본 파라미터 1곳 | 어떤 JSX 선택자로도 안 잡히는 제3 유형. 차단 범위 밖(C-2) |
| 200줄 초과 18건 **리팩터링** | 이 PR 은 **동결**이지 상환이 아니다. `TODOS.md:2471` 은 열어 둔다 |
| 파일 300줄 상한 정본 충돌(`TODOS.md:2904` ㉮) | Maxi 판단 대기 항목. 이 PR 은 함수/컴포넌트 줄수만 |
| ESLint 네이티브 억제 도입 | ①A 에서 검토·기각 (줄수 증가 미포착) |
| 테스트 파일의 한글 placeholder 3건 | 프리미티브 단언용으로 정당 (C-4) |
| `pnpm lint` 시점에 R4 노출 | D2 에서 계약 테스트 단독을 선택. lint 는 R3 만 |

**What already exists (재사용 vs 재구축).**

| 기존 자산 | 이 plan 의 처리 |
|---|---|
| `eslint.config.js` 의 `no-restricted-syntax` 락 2종 | **재사용** — 같은 규칙에 셀렉터만 추가. 신규 플러그인 0 |
| `button-primitive-usage.test.ts` (도출·비-공허 짝·전수 비교) | **패턴 재사용.** ②A 처방이 그 선례의 「목록을 적지 말고 도출」을 그대로 적용 |
| `msw-single-setupserver.test.ts:125` (NEEDLE 런타임 조립) | **패턴 재사용** — 자기탐지 회피 |
| `pending-mutation-guard.test.ts` (R2, 배선·비-공허·양성·음성 4종) | **패턴 재사용** — 대조군 6종의 원형 |
| `src/i18n/` 39개 feature 모듈 | **재사용** — 28건 중 24건이 기존 모듈행. 신규 3개만 생성 |
| `frontend-ci.yml:63 pnpm --filter @bts/web lint` | **재사용** — CI 배선 변경 0 |
| ESLint 네이티브 억제 | **의도적 미사용** (①A) |

**Failure modes (신규 코드경로별 현실적 실패 1개씩).**

| 코드경로 | 실패 시나리오 | 테스트 | 에러 처리 | 사용자에게 보이나 |
|---|---|---|---|---|
| `realEslint.lintText` 대조군 | 도출된 예외 목록이 비어 대조군이 사라짐 | ✅ ②A 비-공허 짝 | — | ✅ red |
| `ratchetEslint.lintFiles` | 파서 누락으로 전량 파싱 실패 → 위반 0 을 「깨끗함」으로 오독 | ✅ ④A M4 | ✅ PARSE_ERROR 단언 | ✅ red |
| 〃 | 글롭 오타로 스캔 0파일 | ✅ 파일 수 하한 + `entries.size > 0` | ✅ | ✅ red |
| `LINES_RE` 메시지 파싱 | ESLint 가 메시지 문구를 바꿈 | ⚠️ 테스트 없음 | ✅ `throw new Error('메시지 형식이 바뀌었다')` | ✅ red (명시 메시지) |
| 베이스라인 키 | 파일 rename → 신규+소멸 동시 발생 | ⚠️ 테스트 없음 | ✅ 실패 메시지에 안내 | ✅ red |
| i18n 이전 28건 | 값 오타 (25/28 은 렌더 테스트 없음) | ⚠️ 테스트 없음 | — | ⚠️ **D-4 grep 절차로만 잡힘** |

**critical gap (테스트 없음 + 에러 처리 없음 + 무음) — 0건.**
마지막 행이 유일한 회색지대이나 **무음이 아니다** — D-4 의 기계적 `grep -rF` 절차가 0회/2회를 잡는다.

**병렬화 전략.** **순차 구현, 병렬 기회 없음.**
T2·T3 이 `lint-ratchet.test.ts` 를 공유하고 T2·T3 모두 T1 에 의존하며 T4 는 `depends-on [2,3]` 이다.

**TODOS 제안 — 0건.** 이 리뷰의 발견 4건은 전부 이 PR 안에서 닫는다.
이연 항목(NOT in scope 7종)은 이미 `TODOS.md` 또는 스펙 C-1~C-6 에 등재돼 있어 신규 등재가 없다.

### 구현 tasks (리뷰 발견 → 작업)

- [ ] **T1 (P2, human: ~30min / CC: ~5min)** — `eslint.config.js` — named export 2종 추가 후 테스트가 도출해 쓰게 한다
  - Surfaced by. 아키텍처 ②A — 경로 하드코딩이 대조군을 공허하게 만든다
  - Files. `apps/web/eslint.config.js` · `apps/web/src/test/lint-ratchet.test.ts`
  - Verify. `node_modules/.bin/vitest run src/test/lint-ratchet.test.ts` 6/6
- [ ] **T2 (P2, human: ~20min / CC: ~3min)** — 계약 테스트 — 린트 결과 메모화 + placeholder 판정 술어 통일
  - Surfaced by. 코드 품질 ③A — 린트 4회 실행 · 두 헬퍼 기준 불일치
  - Files. `apps/web/src/test/lint-ratchet.test.ts`
  - Verify. 실행 시간 기록 (린트 4회 → 2회)
- [ ] **T3 (P2, human: ~10min / CC: ~2min)** — 검증 — 뮤테이션 M4(파서 제거) 추가
  - Surfaced by. 테스트 ④A — PARSE_ERROR 단언의 비-공허 미검증
  - Files. (검증 절차, 커밋 산출물 없음)
  - Verify. 파서 줄 제거 시 red, 원복 시 green

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 4 issues, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | n/a | 시각 변경 0 — 대상 아님 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**VERDICT:** ENG CLEARED — 발견 4건 전부 Maxi 승인 후 plan 에 반영됨. BLOCKER 0 · critical gap 0.

NO UNRESOLVED DECISIONS
