# FR-AT-04 D6/D7 — 규칙 충돌 경고 모달 UI + E2E

> slug: fr-at-04-d6-d7-conflict-warning-ui
> type: ui
> agent: frontend-engineer (D6 구현) + qa-engineer (D7 E2E)
> primary_bc: automation
> 생성: 2026-07-14

## Brief

FR-AT-04 D6/D7 마무리. automation 규칙 저장 응답의 `conflicts`(충돌 4종 soft WARNING)를 사용자에게 경고 모달로 표시하는 프론트 UI(D6) + 그 흐름을 검증하는 E2E 시나리오(D7).

백엔드 D1~D5는 PR #268로 이미 머지 완료 (`fbb52941d`). 규칙 저장(create/patch) 성공 응답에 `conflicts` 배열이 실려 오며(`@JsonInclude(NON_NULL)`), 충돌 없으면 필드 자체가 빠짐. 저장은 어떤 충돌에도 차단되지 않음 — 모달은 순수 정보성.

- classify: type=ui, agent=frontend-engineer (원래 qa 오판을 Maxi 확인 후 정정), primary_bc=automation
- 워크플로우 깊이: **경량 진행** (Maxi 확정) — domain 간략 + spec 간단(백엔드 계약 기반) + plan + eng/design 집중리뷰 + impl + codereview + gate2

## 도메인 정리

- **BC**: automation (프론트 view layer, same-BC — cross-BC 없음)
- **성격**: 백엔드가 이미 확정한 계약(PR #268 머지)의 **프론트 미러 + 렌더**. WorkflowTransitionView 미러 선례와 동형. 새 도메인 개념 없음.
- **새 용어**: 없음 — `RuleConflict`/`ConflictType`(CYCLE·FIELD_CONFLICT·PRIORITY_AMBIGUITY·PERMISSION_MISSING)/`ConflictSeverity`(WARNING) 전부 백엔드 도메인에 이미 존재. 프론트는 대칭 Zod 스키마로 미러링만.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: 기존 `docs/decisions/2026-07-13-fr-at-04-conflict-analysis.md`(백엔드). 프론트 신규 ADR 불필요 — 렌더링 방식은 결정 사항이 아니라 계약 소비.
- **grill-with-docs**: 생략(경량 진행·Maxi 확정). 도메인이 백엔드 머지로 완전 확정된 영역이라 대화형 그릴링은 office-hours-mismatch 안티패턴.

### 백엔드 계약 (UI가 소비할 정확한 shape)

`RuleConflictResponse` (backend `AutomationRuleResponses.kt`):
- `type`: `"CYCLE" | "FIELD_CONFLICT" | "PRIORITY_AMBIGUITY" | "PERMISSION_MISSING"`
- `severity`: `"WARNING"` (현재 단일 값)
- `ruleIds`: `string[]` (UUID)
- `detail`: `string` (사용자 노출용 한국어 메시지 — 백엔드가 완성해 내려줌)

**응답 위치 비대칭** (핵심):
- **POST create** → `CreateAutomationRuleResponse { rule, webhookToken }` → conflicts는 `response.rule.conflicts` (중첩)
- **PATCH** → `AutomationRuleResponse` → conflicts는 `response.conflicts` (최상위)
- **GET** (목록/단건) → conflicts 키 **부재**(`@JsonInclude(NON_NULL)`) → Zod에서 `.optional()`

**결정적 갭**: 현재 프론트 Zod 스키마(`automation-rules.types.ts`)에 `conflicts` 필드가 **없음** → Zod가 모르는 키를 조용히 버려 백엔드가 내려준 conflicts가 소실됨. D6 첫 과제 = Zod 계약에 conflicts 추가 (learnings [[frontend-zod-backend-dto-contract-gap]] 역방향).

### 저장 성공 wiring 지점 (기존 코드)

- `AutomationRuleFormDialog.tsx` `onValid`: create=`createRule.mutateAsync`(반환 `{rule, webhookToken}`), update=`updateRule.mutateAsync`(반환 `AutomationRule`, 현재 반환값 미수신).
- 모달 선례: `WebhookTokenModal` — 페이지 레벨 nullable state(`token: string|null`) + 저장 성공 콜백 1회 세팅 + 닫으면 null. 충돌 모달도 이 패턴 그대로 미러링.
- 페이지: `projects.$projectKey.settings.automation.tsx` — WebhookTokenModal 조립 지점에 신규 RuleConflictWarningModal 추가.


## 스펙

전체 스펙. [docs/specs/2026-07-14-fr-at-04-d6-d7-conflict-warning-ui.md](../specs/2026-07-14-fr-at-04-d6-d7-conflict-warning-ui.md)

핵심 시나리오 요약.
- 규칙 저장(생성/수정) 성공 후 응답 conflicts가 비어있지 않으면 경고 모달 표시(저장은 이미 성공, 정보성).
- Zod 스키마에 conflicts 계약 추가(현재 소실 중) — API 레이어 코드는 무변경(스키마 확장만).
- WebhookTokenModal 패턴 미러: 페이지 레벨 nullable state + 저장 성공 콜백 1회 + 닫으면 null.
- MSW create/patch 핸들러에 conflicts 시나리오 토글 → E2E 결정적 재현.

## Brainstorming Check

✅ 통과 (1회, gap 없음). API 무변경·`.optional()` 정확성·하위호환 확인.
**게이트1 확인 대상 1건**: WEBHOOK 토큰+충돌 동시 순차 표시(S6/E3, 기본값=토큰 우선).

## Plan

> 전부 순수 프론트(백엔드/DB 무변경). API 레이어(`automation-rules.ts`) 코드도 무변경 — Zod 스키마 확장만으로 conflicts가 흐른다. TDD red→green→refactor 강제.

### Task 1. FR-1 — Zod conflicts 계약 미러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-rules.types.ts`, `apps/web/src/api/automation-rules.types.test.ts`]
- depends-on: []

**RED**: `automation-rules.types.test.ts`에 추가.
- create 응답 fixture(`rule.conflicts` = 4종 각 1건 + severity WARNING)를 `createAutomationRuleResponseSchema.parse` → `rule.conflicts` 길이/type/detail 검증.
- GET 응답 fixture(conflicts 키 부재)를 `automationRuleResponseSchema.parse` → `conflicts === undefined`.
- create 빈 배열(`conflicts: []`) → 파싱 통과, 빈 배열 보존.
- 실패(예상): `ruleConflictResponseSchema` 미존재 / `conflicts` 필드 없어 값 소실.

**GREEN**: `automation-rules.types.ts`.
- `conflictTypeSchema = z.enum(['CYCLE','FIELD_CONFLICT','PRIORITY_AMBIGUITY','PERMISSION_MISSING'])`
- `conflictSeveritySchema = z.enum(['WARNING'])`
- `ruleConflictResponseSchema = z.object({ type, severity, ruleIds: z.array(z.string().uuid()), detail: z.string() })`
- `automationRuleResponseSchema`에 `conflicts: z.array(ruleConflictResponseSchema).optional()` 추가.
- 추론 타입 `RuleConflict` export(파일 스코프, 백엔드명 겹침 무해).

**REFACTOR**: 백엔드 `RuleConflictResponse` 대칭 KDoc + optional 이유(GET 부재/create·patch 배열) 주석.

**검증**: `cd apps/web && node_modules/.bin/vitest run src/api/automation-rules.types.test.ts`

### Task 2. FR-3 — RuleConflictWarningModal 컴포넌트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/RuleConflictWarningModal.tsx`, `apps/web/src/components/automation/RuleConflictWarningModal.test.tsx`]
- depends-on: [1]

**RED**: `RuleConflictWarningModal.test.tsx`.
- `conflicts={null}` → 아무것도 렌더 안 함(null 반환).
- 단건 → 종류 한국어 라벨(예 "순환 참조") + `detail` 텍스트 렌더.
- 다건 → 모든 충돌 나열(개수만큼 항목).
- 닫기 버튼 클릭 → `onClose` 호출.
- 실패(예상): 컴포넌트 미존재.

**GREEN**: `RuleConflictWarningModal.tsx` — WebhookTokenModal 패턴.
- props `{ conflicts: RuleConflict[] | null; onClose: () => void }`, null이면 `return null`.
- Radix `DialogPrimitive`(open 고정 + onOpenChange로 onClose), amber 경고 톤(`text-amber-800 dark:text-amber-200`), 본문 `role="alert"`.
- `CONFLICT_TYPE_LABELS: Record<ConflictType, string>` (순환 참조/필드 충돌/우선순위 모호/권한 부족). 각 항목 = 종류 배지 + detail. ruleIds는 개수 보조 표기.
- data-testid: `rule-conflict-warning-modal`, `rule-conflict-close-button`.

**REFACTOR**: labels 상수 분리 + KDoc(1회성 정보 모달, 저장 비차단 명시).

**검증**: `cd apps/web && node_modules/.bin/vitest run src/components/automation/RuleConflictWarningModal.test.tsx`

### Task 3. FR-2 — FormDialog onConflicts 콜백 배선

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/AutomationRuleFormDialog.tsx`, `apps/web/src/components/automation/AutomationRuleFormDialog.test.tsx`]
- depends-on: [1]

**RED**: `AutomationRuleFormDialog.test.tsx` 추가(기존 mock 훅 패턴 재사용).
- create 응답에 conflicts 있음 → `onConflicts`가 그 배열로 호출.
- create 응답 conflicts 빈 배열/부재 → `onConflicts` 미호출.
- patch 응답에 conflicts 있음 → `onConflicts` 호출(update 반환값 수신).
- 실패(예상): `onConflicts` prop 미존재.

**GREEN**: `AutomationRuleFormDialog.tsx`.
- `AutomationRuleFormDialogProps` + `FormBodyProps`에 `onConflicts?: (conflicts: RuleConflict[]) => void` 추가, 전달.
- `onValid`: create는 `response.rule.conflicts`, update는 `const updated = await updateRule.mutateAsync(...)` 반환 수신 후 `updated.conflicts`. 공통 헬퍼 `emitConflicts(conflicts, onConflicts)` — `length > 0`일 때만 호출.

**REFACTOR**: `emitConflicts` 헬퍼 분리(webhookToken 처리와 대칭), KDoc.

**검증**: `cd apps/web && node_modules/.bin/vitest run src/components/automation/AutomationRuleFormDialog.test.tsx`

### Task 4. FR-4/FR-5 — 페이지 배선 + 토큰·충돌 순차

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.automation.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx`]
- depends-on: [2, 3]

**RED**: 페이지 테스트.
- FormDialog의 onConflicts 발화 → RuleConflictWarningModal 표시.
- 모달 onClose → conflicts state null 복귀(모달 사라짐).
- webhookToken + conflicts 동시 → 토큰 모달 표시, 충돌 모달은 미렌더(webhookToken 살아있는 동안 대기).
- 실패(예상): conflicts state/모달 미존재.

**GREEN**: 페이지.
- `const [conflicts, setConflicts] = useState<RuleConflict[] | null>(null)` 추가.
- FormDialog에 `onConflicts={setConflicts}` 전달.
- `RuleConflictWarningModal` 조립: `conflicts={webhookToken === null ? conflicts : null}`(FR-5 순차) + `onClose={() => setConflicts(null)}`.

**REFACTOR**: KDoc에 상태 4종(dialogOpen/editingRule/webhookToken/conflicts) + 순차 규칙 명시.

**검증**: `cd apps/web && node_modules/.bin/vitest run src/routes/__tests__/projects.\$projectKey.settings.automation.test.tsx`

### Task 5. FR-6 — MSW conflicts 시나리오 토글

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/automation-rule-handlers.ts`, `apps/web/src/mocks/automation-rule-fixtures.ts`, `apps/web/src/mocks/automation-rule-handlers.test.ts`]
- depends-on: [1]

**RED**: `automation-rule-handlers.test.ts` 추가.
- `SCENARIO_KEY.WITH_CONFLICTS` 플래그 on → create 응답 `rule.conflicts` 비어있지 않음.
- 플래그 off → create 응답 `conflicts: []`.
- GET(목록/단건)은 플래그 무관 conflicts 키 부재.
- patch도 플래그 on → conflicts 실림.
- 실패(예상): 핸들러가 conflicts 미주입.

**GREEN**.
- `automation-rule-fixtures.ts`: `SCENARIO_KEY.WITH_CONFLICTS` 상수 + 시드 conflicts 배열 빌더(결정적, 4종 중 2~3건).
- `automation-rule-handlers.ts`: create/patch 핸들러가 플래그 읽어 응답에만 conflicts 주입(store rule에는 미저장 — GET 오염 방지). 기본은 create/patch `conflicts: []`.

**REFACTOR**: `buildSeededConflicts()` 헬퍼 + 교훈 주석([[e2e-msw-scenario-toggle-localstorage-flag]]·[[msw-derived-behavior-shared-store-e2e]]).

**검증**: `cd apps/web && node_modules/.bin/vitest run src/mocks/automation-rule-handlers.test.ts`

### Task 6. FR-7 — E2E 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/automation-conflict-warning.spec.ts`]
- depends-on: [4, 5]

**RED→GREEN**(E2E는 impl(T4·T5) 완료 후 통과).
- S1: WITH_CONFLICTS 플래그(addInitScript localStorage) → 룰 저장 → 충돌 경고 모달 표시 검증.
- S2: 플래그 off → 룰 저장 → 모달 미표시.
- S4: 모달 닫기 → 사라짐.
- S5: 다중 충돌 → 항목 개수 검증.
- 회귀: 기존 `automation-rules.spec.ts` 동반 실행([[ui-pr-defer-e2e-regression-latent]]).
- 함정 주의: worktree E2E 5173 orphan([[e2e-orphan-vite-after-worktree-remove]]), MSW serviceWorker block 금지([[e2e-msw-serviceworker-block]]).

**검증**: `cd apps/web && node_modules/.bin/playwright test automation-conflict-warning automation-rules`

## Plan 메타

- task 수: 6
- wave: 4 (W1[T1] → W2[T2,T3,T5] → W3[T4] → W4[T6])
- 예상 시간: 병렬 wave 기준 약 12~16분
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- agent: T1~T5 frontend-engineer, T6 qa-engineer
- 추가 검증: typecheck(tsconfig.app.json [[ci-typecheck-tsconfig-app-vs-local]]), lint, vitest, playwright, pnpm verify

## 리뷰 결과

### plan-eng-review (2026-07-14, 집중 리뷰 — 저위험 ui)
- ✅ 통과: TDD 구조·의존성 그래프·wave(4) 계산 정확. conflicts 위치 비대칭(create=rule.conflicts/patch=conflicts) 정확 반영. API 레이어 무변경 확인.
- ✅ FR-5 순차 가드 타이밍 정합 확인(토큰 닫힘 재렌더 → 충돌 노출, conflicts state 지속).
- ⚠️ 보강 1 (T1): `ConflictType` 타입도 z.infer로 export — T2 `CONFLICT_TYPE_LABELS: Record<ConflictType,string>`가 소비.
- ⚠️ 보강 2 (T2/T6): 신규 파일 L1 한국어 주석 필수(DEVELOPMENT §6). 함수 30줄 상한·빈 catch 금지 준수.
- ⚠️ 보강 3 (W2 병렬): T2·T3·T5 pre-commit lint-staged race — 자기 파일만 stage([[parallel-dispatch-precommit-hook-race]]).
- BLOCKER: 없음.

### plan-design-review (2026-07-14, 집중 리뷰)
- ✅ 통과: amber 경고 톤·`role="alert"`·WebhookTokenModal 미러 일관.
- ⚠️ 보강 4 (T2 카피): 모달 제목/문구는 **"저장은 성공했다"를 먼저 알리고** 충돌을 부가 경고로. 예 제목 "규칙이 저장되었습니다" + 경고 소제목 "다음 충돌이 감지되었습니다(저장은 유지됩니다)". 비차단 성격 명확화.
- ⚠️ 보강 5 (T2 ruleIds): "관련 규칙 N개" 축약 표기(UUID 직접 노출 금지). 이름 조회 안 함은 스코프상 타당.
- BLOCKER: 없음.

### 게이트1 taste decision (Maxi 결정)
- **D-A. WEBHOOK 토큰+충돌 동시 표시 순서** (S6/E3/FR-5). 기본값 = 토큰 모달 먼저(보안 1회 노출 우선), 닫으면 충돌 모달. 대안 = 동시 표시 / 충돌만.
