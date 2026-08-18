# FR-WF-01 후속 — WorkflowDiagram 영역 정리 (C-2 + C-3) — 스펙

> slug. workflow-diagram-c2-c3-followup
> type. ui (frontend-engineer)
> primary BC. project-workflow (frontend view layer)
> 베이스. main `623a6da` (PR #15 머지 이후)
> 생성. 2026-05-22

## 0. 배경

PR #13 (FR-WF-01 frontend) `/bts-codereview` 의 CONCERN 후속.

- **C-2** — MSW `workflow-fixtures.ts` 의 `transition.key` 형식 (`"open-to-in_progress"`) 이 backend computed property `WorkflowTransition.key = "${fromStateKey}__${toStateKey}"` (`"open__in_progress"`) 와 다름. 현재 frontend (WorkflowDiagram + workflows.$key route) 는 `transition.key` 를 미사용해서 즉시 버그 없으나, 후속 UI (전환 클릭 추적 / 라우팅 / 디바이스 분석) 도입 시 silent fail 위험.
- **C-3** — mermaid `classDef` 이름 (`todo` / `in_progress` / `done`) 이 일부 state id (예. simple 의 `done`, kanban-basic 의 `todo`, software-default 의 `in_progress`) 와 동일 토큰. mermaid stateDiagram-v2 의 일부 파서 버전에서 `class todo todo` 같은 동일 토큰 라인 비일관 처리 보고 (mermaid issue #4XXX 계열). 현재 v11 에서는 정상 동작하나 회귀 방지 prefix 부여 권장.

PR #13 head 머지 entry (history.md 2026-05-22 #13) 에서 "C-2/C-3/SUGGESTION 후속 PR 위임" 명시.

## 1. 사용자 시나리오 (Given-When-Then)

본 PR 은 view layer 내부 정리. 외부 사용자 (FE/BE 개발자 / Maxi) 의 직접 행동 변화는 없으나 다음 시나리오 보장.

### S1. mermaid 다이어그램 렌더 회귀 0

- **Given**. WorkflowDiagram 컴포넌트가 4 표준 워크플로우 (software-default / bug-tracking / simple / kanban-basic) 중 하나를 받는다.
- **When**. 페이지가 마운트되어 mermaid 가 stateDiagram-v2 를 그린다.
- **Then**. 노드 수 / 전환 수 / 카테고리별 색상이 PR #13 시점과 동일하게 렌더된다. 사용자가 시각적으로 차이 인지 불가.

### S2. transition.key 형식 통일

- **Given**. 페이지가 `/api/v1/workflows/:key` 응답에서 `transitions[].key` 를 수신한다 (production = backend computed, dev/test = MSW fixture).
- **When**. 같은 워크플로우의 같은 전환을 production / dev 양쪽에서 본다.
- **Then**. `transition.key` 값이 동일 형식 (`"${fromStateKey}__${toStateKey}"`) 으로 표시된다. 형식 불일치 0.

### S3. mermaid classDef prefix 적용

- **Given**. WorkflowDiagram 이 카테고리 (TODO / IN_PROGRESS / DONE) 별 색상을 부여한다.
- **When**. mermaid 코드를 생성한다.
- **Then**. classDef 이름이 `category-todo` / `category-in-progress` / `category-done` 형식이고, state id 와 동일 토큰 0. `class <stateKey> category-<category>` 라인 명확 분리.

## 2. 기능 요구사항 (FR)

### FR-1. MSW `workflow-fixtures.ts` transition.key 형식 통일

**FR-1-a**. `workflow-fixtures.ts` 의 4 워크플로우 fixture (`softwareDefaultFixture` / `bugTrackingFixture` / `simpleFixture` / `kanbanBasicFixture`) 의 모든 `transition.key` 를 `"${fromStateKey}__${toStateKey}"` 형식으로 변경. backend `WorkflowTransition.key` computed property (`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/WorkflowTransition.kt:18`) 와 정확 일치.

영향 transition. 4 워크플로우 × 평균 4.25 전환 = 총 17건.
- software-default 6건 (open-to-in_progress / in_progress-to-in_review / in_review-to-done / in_review-to-in_progress / done-to-closed / open-to-closed)
- bug-tracking 5건 (reported-to-triaged / triaged-to-in_progress / in_progress-to-resolved / resolved-to-closed / resolved-to-in_progress)
- simple 3건 (todo-to-doing / doing-to-done / done-to-doing)
- kanban-basic 3건 (backlog-to-ready / ready-to-in_progress / in_progress-to-done)

**FR-1-a 추가 영향 파일 (D4 결정)**. `apps/web/src/api/workflows.test.ts` 의 inline 4 워크플로우 fixture (라인 23-28 / 44-48 / 62-64 / 79-81) 도 동일 형식으로 일괄 변경 — 총 17건. (workflows.test.ts 가 workflow-fixtures.ts import 로 일관화 결정은 본 PR 후속 후보, 본 PR scope 외.)

**FR-1-b (D1 결정)**. transition.key 생성 helper 신규. 함수명 `transitionKey(fromStateKey: string, toStateKey: string): string`. 위치 **`apps/web/src/components/workflow/workflow.types.ts`** (view layer 타입 정의 파일 인접 — fixture / Zod schema / API call 세 곳에서 동일 import 경로). 단순 합성. `return \`\${fromStateKey}__\${toStateKey}\``. helper 자체 단위 테스트 2건 (기본 케이스 + 언더스코어 포함 stateKey 케이스).

**FR-1-c (D2 결정)**. fixture 가 직접 helper 호출 — 옵션 B 채택. fixture 의 transition 정의가 `{ key: transitionKey('open', 'in_progress'), name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' }` 패턴. 정적 문자열 (`'open__in_progress'`) 직접 작성 0. drift 본질 차단 — fixture 추가 시 형식 어긋날 수 없음.

**FR-1-d (회귀 가드)**. workflow-fixtures.ts 의 17건 + workflows.test.ts inline 17건 모두 helper output 과 일치 검증 — 단위 테스트로 자동 검증. 다만 옵션 B (fixture 가 helper 호출) 채택으로 자동 보장 — 별도 회귀 가드 테스트는 안전망 1건만.

**FR-1-e**. helper export 후 향후 프론트 코드에서 transition.key 비교 / 라우팅 시 동일 helper 재사용 (silent fail 방지 — production wiring 시점에 backend computed key 와 frontend 비교 시점 helper 일관).

### FR-2. mermaid `classDef` prefix 적용 (D3 결정 — 언더스코어 채택)

**FR-2-a**. `WorkflowDiagram.tsx` 의 `categoryToClass(category)` 가 다음 반환.
- `'TODO'` → `'category_todo'`
- `'IN_PROGRESS'` → `'category_in_progress'`
- `'DONE'` → `'category_done'`

언더스코어 prefix 결정 근거. mermaid v11 stateDiagram-v2 의 classDef 식별자 정규식 `[a-zA-Z][a-zA-Z0-9_-]*` 가 하이픈 허용하나 일부 버전 (이슈 #5263 등) 비일관 보고. 언더스코어는 100% 호환. state id 와의 토큰 충돌 회피가 본 PR 의 핵심 — prefix 만 다르면 OK이므로 안전 옵션 채택.

**FR-2-b**. `generateMermaidCode()` 의 classDef 라인 3건도 동일 prefix 사용.
```
classDef category_todo fill:var(--muted),stroke:var(--border)
classDef category_in_progress fill:oklch(from var(--primary) l c h / 0.15),stroke:var(--primary)
classDef category_done fill:oklch(0.94 0.05 160 / 0.15),stroke:oklch(0.5 0.12 160)
```

**FR-2-c**. `class <stateKey> category_<category>` 라인 형식 유지. state id 자체 (예. `todo`, `done`, `in_progress`) 변경 0 — backend 와 일관 유지. 결과 예시.
- software-default `class in_progress category_in_progress` (prefix 만 차이로 동일 토큰 회피)
- simple `class done category_done`
- kanban-basic `class todo category_todo`

### FR-3. 테스트 / snapshot 갱신

**FR-3-a (G-4 정정)**. `WorkflowDiagram.test.tsx` 에 `categoryToClass` 명시적 단위 테스트 **3건 신규 작성** (TODO → 'category_todo', IN_PROGRESS → 'category_in_progress', DONE → 'category_done'). 현재 categoryToClass 검증은 4 워크플로우 snapshot 의 `class todo todo` 같은 라인을 통한 간접 검증만 — 명시적 assertion 0건. 회귀 가드 강화 (snapshot 변경 + 명시적 단위 테스트 둘 다 검증).

**FR-3-b**. `__snapshots__/WorkflowDiagram.test.tsx.snap` 4 워크플로우 snapshot 갱신 (`vitest -u` 로 일괄). 갱신 후 diff 검토 — classDef 3 라인 + class N 라인 (workflow 별 다름) 만 변경. 노드 / 전환 라인 변경 0 검증.

**FR-3-c**. transitionKey helper 단위 테스트 2건 신규 (api/workflows.test.ts 또는 별도 파일).

**FR-3-d**. fixture transition.key 형식 검증 단위 테스트 1건 신규 — 4 fixture × 17 transition 모두 `transitionKey()` helper 결과와 일치 검증. fixture 의 manually-written key 가 helper output 과 drift 발생 시 회귀.

**FR-3-e**. Playwright E2E (`workflow.spec.ts`) — 시각 회귀 검증. classDef prefix 변경이 mermaid SVG 렌더에 영향 0 (mermaid 가 classDef 이름을 SVG class 속성으로 변환, 색상은 동일). 4 happy path E2E 전부 기대값 유지 (5/5/3/4 state, transitions 시각 표시).

## 3. 비기능 요구사항 (NFR)

- **NFR-1 회귀 0**. 사용자 시각 결과 (mermaid 다이어그램 색상 / 노드 위치 / 화살표) 변경 0. classDef 이름은 SVG class 속성 — 사용자 비가시.
- **NFR-2 번들 크기 영향 0**. helper 함수 1개 추가 (수 bytes), categoryToClass return 문자열 길이 증가 ≈ 30 bytes — 무시 가능.
- **NFR-3 TypeScript strict 통과**. helper 의 입력 stateKey 타입 string — 추가 제약 없음. 기존 generateMermaidCode 시그니처 변경 0.
- **NFR-4 빌드 시간 영향 0**. test/snapshot 갱신만 — 신규 패키지 / 신규 의존성 0.

## 4. API 인터페이스 (REST)

본 PR 에서 신규 / 변경 endpoint 0. backend 변경 0.

## 5. 데이터 모델 변경

본 PR 에서 데이터베이스 변경 0. Flyway 마이그레이션 0. backend `WorkflowTransition.key` computed property 는 이미 PR #13 Task 7 에서 도입 — 본 PR 은 그 형식에 frontend fixture 를 맞추는 작업.

## 6. 엣지 케이스

### EC-1. state key 가 언더스코어 포함 (`in_progress`)

transitionKey helper output. `in_progress__done`. 언더스코어 2개 + `__` 구분자라 토큰 분리는 backend 계약 (`.kt:18`) 과 동일. 일관성 유지.

### EC-2. state key 가 하이픈 포함 (현재 4 fixture 에는 없음, 미래 가능성)

transitionKey helper 가 backend 계약대로 단순 합성. 하이픈은 mermaid 가 식별자로 받지 못해 별도 처리 (현재 backend / frontend 둘 다 영문 + 언더스코어만 허용 — workflow.types.ts spec §6 명시). 현 시점 회귀 없음.

### EC-3. mermaid v11+ 의 classDef prefix 동작 (D3 결정 후)

언더스코어 prefix 채택으로 호환성 100% 확보. mermaid stateDiagram-v2 의 classDef 식별자 정규식 `[a-zA-Z][a-zA-Z0-9_-]*` — 언더스코어 명시적 허용. `category_todo` / `category_in_progress` / `category_done` 모두 부합 + 일부 mermaid 버전의 하이픈 비일관 처리 (issue #5263 등) 위험 회피.

### EC-4. snapshot 갱신 후 diff 검증

snapshot 갱신 시 diff 가 의도된 변경 (classDef 3 라인 + class N 라인) 만 포함하는지 git diff 로 검증. 노드 / 전환 라인 변경 0 — 변경 시 generateMermaidCode 의 의도치 않은 부수 변경 의심.

### EC-5. helper export 위치 — 순환 import 회피

`transitionKey` 를 `workflow.types.ts` 에 두면 `api/workflows.ts` 가 import — 정상. fixture 가 `api/workflows.ts` import 했던 (현재 코드 `mocks/workflow-fixtures.ts:11`) 그대로 유지. 순환 0.

## 7. 제약 조건

- `apps/web/src/components/workflow/WorkflowDiagram.tsx` 시그니처 변경 0 — 외부 호출자 (workflows.$key 라우트) 영향 0.
- backend 변경 0 (frontend-only PR).
- 신규 패키지 도입 0.
- 절대 규칙 (`DEVELOPMENT.md §1`) 준수. 특히 NEVER-15 (console.error/warn 만 허용, 본 PR 영역 코드에 console.log 추가 0).
- 본 PR 머지 베이스. main `623a6da` (PR #15 머지 이후). 머지 직전 main fast-forward 시 PR #14 의 `333b709` 도 포함 — 영역 겹침 0 (issue-tracking BC 신규 모듈).

## 8. 측정 가능한 완료 기준

- [ ] `pnpm test` — 4 표준 워크플로우 snapshot + categoryToClass 단위 테스트 + transitionKey helper 단위 테스트 + fixture key 형식 검증 단위 테스트 전부 pass.
- [ ] `pnpm test:e2e` — 4 happy path (software-default / bug-tracking / simple / kanban-basic) 전부 pass. 시각 회귀 0.
- [ ] `pnpm typecheck` — 0 error.
- [ ] `pnpm lint` — 0 error / 0 warning.
- [ ] `pnpm build` — 0 error. 번들 크기 < PR #13 시점 + 50 bytes.
- [ ] backend 변경 0 — `git diff --stat main backend/` empty.
- [ ] snapshot diff 검토 — classDef 3 라인 + class N 라인 외 변경 0 (git diff 검토).
- [ ] fixture transition.key 17건 전부 helper output 과 일치 (자동 테스트 + 수동 grep 보강).

## 9. 작업 외 사항 (Out of scope)

- PR #13 의 다른 후속 (D4 axe 접근성 / SUGGESTION-1 fixture description yaml seed 일치) — 본 PR scope 외.
- backend `WorkflowTransition.key` 형식 변경 — 본 PR 은 frontend fixture 를 backend 에 맞추는 방향 (역방향 0).
- 새 워크플로우 추가 / 워크플로우 정의 변경 — 본 PR scope 외.
- mermaid 버전 업그레이드 — 본 PR scope 외 (현재 v11 유지).

## Brainstorming Check ✅

**Phase B sanity check 1회**. gap 6건 발견 — Maxi 결정 4건 (D1 helper 위치 / D2 fixture 결합 / D3 classDef prefix 형식 / D4 workflows.test.ts inline 처리) + spec 자동 보강 2건 (G-2 영향 파일 추가 / G-4 표현 정정).

결정 사항 4건 inline 반영 완료 (위 FR / EC 본문). 추가 Phase A↔B loop back 없음.

검증된 가정.
- frontend 어느 코드도 `transition.key` 를 production 사용 0 (grep 결과). silent fail 잠재 위험만 해소 — 즉시 깨지는 사용처 0.
- workflows.test.ts inline 17건도 본 PR scope (D4 결정).
- mermaid v11 언더스코어 classDef 호환성 100% (D3 결정 — TDD red 단계 추가 검증 불필요).
- backend `WorkflowTransition.key` computed property (line 18) 형식이 본 PR 의 frontend fixture 형식의 정본.
