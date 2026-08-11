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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
