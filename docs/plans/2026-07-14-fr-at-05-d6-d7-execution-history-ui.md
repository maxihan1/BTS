# FR-AT-05 D6/D7 — 자동화 규칙 실행 이력 화면 + 단계별 trace + 재실행 버튼

> slug: fr-at-05-d6-d7-execution-history-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-14

## Brief

Maxi 원문. "FR-AT-05 D6/D7 실행 이력 화면 + 단계별 trace + 재실행 버튼 + Playwright E2E"

FR-AT-05 백엔드(D1~D5)는 PR #270로 머지 완료. 이번 작업은 D6/D7 = 프론트엔드 UI 마감 단계.
자동화 규칙 실행 이력을 조회하는 화면 + 실행 단건의 액션별 결과(trace) 펼쳐보기 + 동기 재실행(replay) 버튼 + Playwright E2E.

classify. classifier가 E2E 키워드로 qa 오분류 → Maxi 확인 후 ui/frontend-engineer 정정.
선례. automation BC D6/D7 UI PR — AT-01(#251→#254), AT-04(#268→#269).

백엔드 API(#270 제공, D1~D5).
- 실행 이력 조회 3종 (목록/단건/replay 관련) — 구체 엔드포인트는 /bts-spec에서 backend 소스 grep으로 확정
- 동기 replay API — MANAGE_AUTOMATION 가드, 소프트삭제 룰 409
- 응답 DTO nullable 필드: issueKey · replayedFrom · outcomes.error → 프론트 Zod .nullable() 필수

## 도메인 정리

- **BC**: automation (9번째 모듈 `com.bts.automation`, JdbcTemplate)
- **영향 엔티티(전부 기존, 실재 확인됨)**:
  - `RuleExecution` (`application/RuleExecution.kt`) — 자동화 룰 1회 실행의 영속 기록. `rule_executions` 테이블(V305).
  - `ActionOutcome` (`application/RuleExecution.kt`) — 실행 내 액션 1건 결과(position·actionType·success·error).
  - `RuleExecutionService` (`application/RuleExecutionService.kt`) — 조회 3종(listByRule/getById/replay) 유스케이스.
- **새 용어/유비쿼터스 언어**: **없음**. UI-facing 개념(실행 이력=RuleExecution, trace=단건 outcomes 상세, 재실행=replay)은 전부 #270 ADR가 접지. glossary의 [[트리거]]·[[액션]] 재사용.
- **기존 결정 충돌**: 없음. 이번 작업은 확정된 백엔드 도메인의 순수 UI 시각화(신규 도메인/스키마/ADR 0).
- **관련 ADR**: [docs/decisions/2026-07-14-fr-at-05-execution-history.md](../decisions/2026-07-14-fr-at-05-execution-history.md) (#270, 백엔드 D1~D5) — 재사용, 신규 ADR 불요.
- **grill-with-docs**: 경량 패스로 대체. 근거 — 신규 엔티티/용어 0 + ADR 충돌 0 인 D6/D7 UI-on-settled-backend는 대화형 도메인 검증 산출이 없음(선례 [[bts-review-plan-autoplan-overkill]] 정신).

### 백엔드 API 계약 (코드 실재 확인 — bts-spec 입력)

`AutomationExecutionController.kt` + `RuleExecutionResponses.kt` grep 확정.

| # | Method · Path | 응답 형태 | 쿼리/특이사항 |
|---|---|---|---|
| 1 | `GET /api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions` | **raw 배열** `List<RuleExecutionSummaryResponse>` (DataResponse 외피 없음) | `issueKey?` 필터 · `limit`(기본 50, 1~200 clamp) · `before?`(ISO Instant keyset 커서). 룰 존재 확인 안 함(소프트삭제 룰 이력도 조회) |
| 2 | `GET /api/v1/automation/executions/{id}` | bare `RuleExecutionDetailResponse` | 미존재/타프로젝트 모두 404 존재숨김 |
| 3 | `POST /api/v1/automation/executions/{id}/replay` | 새 실행의 bare `RuleExecutionDetailResponse` | 실제 재실행(dryRun=false, 실제 이슈 변경) |

**에러코드(RFC 7807 ProblemDetail `errorCode`, prefix `AUTOMATION_`)**: 401 `AUTOMATION_UNAUTHENTICATED` · 403 `AUTOMATION_ACCESS_DENIED` · 404 `AUTOMATION_EXECUTION_NOT_FOUND` · 409 `AUTOMATION_RULE_UNAVAILABLE`(소프트삭제 룰 replay) · 400 `AUTOMATION_MALFORMED_REQUEST`.

**DTO 필드 (Zod 계약)**:
- `RuleExecutionSummaryResponse`: id·ruleId(UUID) · triggerType(String) · **issueKey(String?, nullable)** · status(String enum) · actionCount·successCount(Int) · startedAt·finishedAt(Instant ISO) · **replayedFrom(UUID?, nullable)**
- `RuleExecutionDetailResponse`: 위 + projectKey(String) · **triggerEvent(JsonNode, 임의 JSON)** · outcomes(`ActionOutcomeResponse[]`)
- `ActionOutcomeResponse`: position(Int) · actionType(String) · success(Boolean) · **error(String?, nullable)**
- status enum: `SUCCESS` / `PARTIAL` / `FAILED` / `SKIPPED`
- **Zod 함정([[frontend-zod-backend-dto-contract-gap]])**: issueKey·replayedFrom·error는 `.nullable()`, triggerEvent는 `z.unknown()`. nullable 누락 시 실제 파싱에서 조용히 깨짐.

## 스펙

전체 스펙. [docs/specs/2026-07-14-fr-at-05-d6-d7-execution-history-ui.md](../specs/2026-07-14-fr-at-05-d6-d7-execution-history-ui.md)

**배치 결정(Maxi 확정)**: Radix Dialog 모달. automation 설정 페이지 각 룰 행에 "이력" 버튼 → Dialog로 목록+trace+replay. 라우터 변경 없음.

핵심 시나리오 요약.
- 룰 행 "이력" 버튼 → Dialog에 실행 이력 최신순 목록(status 배지·트리거·이슈키·시각·성공/전체)
- 행 클릭 → trace 인라인 펼침(액션별 ✓/✗·error 코드 + triggerEvent JSON)
- "재실행" → 인라인 2단계 확인 → 실제 replay → 새 실행 맨 위 추가 + 자동 펼침 + 토스트 (409 소프트삭제 룰 → 토스트)
- 빈 상태 · 더 보기(keyset before 커서) · issueKey 필터(Enter 적용)

**핵심 계약 함정**: Zod `issueKey`·`replayedFrom`·`outcomes[].error` = `.nullable()`, `triggerEvent` = `z.unknown()`. bare DTO(봉투 없음). MSW 신규 handlers.ts는 `mocks/handlers.ts` 전역 등록 필수([[msw-global-handler-registration-gap]]).

## Brainstorming Check

✅ 통과 (1회 gap 분석). Maxi 결정 불요 4건 기본값 해소 — replay 후 자동 펼침 · 인라인 확인(중첩 Dialog 회피) · 필터 Enter 적용+커서 리셋 · triggerType 라벨 맵.

## Plan

automation-rules.* 파일 구조를 미러(api `*.types.ts`+`*.ts`+`use*.ts`, `mocks/*-fixtures.ts`+`*-handlers.ts`, `components/automation/*`). 전부 프론트(pnpm) — Gradle 모듈 직렬화 무관. 각 task TDD RED→GREEN→REFACTOR.

### Task 1. 실행 이력 Zod 스키마 + 타입

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-executions.types.ts`, `apps/web/src/api/automation-executions.types.test.ts`]
- depends-on: []

**RED**: `automation-executions.types.test.ts` — `ruleExecutionSummarySchema.parse(fixture)` 성공 + issueKey/replayedFrom `null` 허용 + error `null` 허용 + triggerEvent 임의 JSON 허용 + status enum 4종. 실패: 스키마 없음.
**GREEN**: `ruleExecutionStatusSchema`(SUCCESS/PARTIAL/FAILED/SKIPPED) · `actionOutcomeSchema`(error `.nullable()`) · `ruleExecutionSummarySchema`(issueKey·replayedFrom `.nullable()`) · `ruleExecutionDetailSchema`(+ projectKey·triggerEvent `z.unknown()`·outcomes 배열) · `z.infer` 타입 export.
**REFACTOR**: KDoc(backend DTO 1:1 대응·nullable 사유 명시) + 파일 L1 한국어 주석.
**검증**: `pnpm --filter web test automation-executions.types`
**함정**: [[frontend-zod-backend-dto-contract-gap]]·[[zod-v4-uuid-fixture-strictness]](fixture UUID는 RFC4122 v4).

### Task 2. 실행 이력 API 클라이언트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-executions.ts`, `apps/web/src/api/automation-executions.test.ts`]
- depends-on: [1]

**RED**: `automation-executions.test.ts`(MSW) — `fetchRuleExecutions(projectKey, ruleId, {issueKey?, limit?, before?})` bare 배열 반환 · `fetchRuleExecution(id)` detail · `replayRuleExecution(id)` X-XSRF-TOKEN 포함 POST · 비-2xx → ApiError. 실패: 함수 없음.
**GREEN**: `apiGet(path, schema)` 목록/단건 · `apiFetch`+`throwIfNotOk`+`.parse` replay · 쿼리스트링 조립(issueKey·limit·before, undefined 생략) · `extractAutomationRuleErrorCode` 재사용(automation-rules.ts export) 또는 동형 추가.
**REFACTOR**: basePath 헬퍼 · KDoc(엔드포인트·에러코드).
**검증**: `pnpm --filter web test automation-executions`
**함정**: [[frontend-api-convention-per-bc]](bare DTO·automation 관례), 인증 헤더 readXsrfToken.

### Task 3. react-query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/useAutomationExecutions.ts`, `apps/web/src/api/useAutomationExecutions.test.tsx`]
- depends-on: [2]

**RED**: `useAutomationExecutions.test.tsx` — `useRuleExecutions(projectKey, ruleId, {issueKey})` 페이지 누적(더 보기=before 커서) · `useRuleExecutionDetail(id)`(enabled=펼침 시) · `useReplayRuleExecution()` mutation 성공 시 목록 캐시에 새 실행 prepend. 실패: 훅 없음.
**GREEN**: filter-aware queryKey(`['automation-executions', projectKey, ruleId, issueKey]`) · 더 보기 상태(누적 목록+마지막 startedAt 커서, 필터 변경 시 리셋) · detail queryKey per id · replay onSuccess setQueryData prepend(부분응답 플리커 주의 [[mutation-setquerydata-partial-response-flicker]] — detail→summary 매핑 후 prepend).
**리뷰 반영(E-gap1)**: 누적 모델 **하나 선택 후 KDoc 명시** — `useInfiniteQuery`(getNextPageParam=마지막 startedAt) 또는 컴포넌트 state 수동 누적. prepend·필터 리셋과의 상호작용이 단순한 쪽 선택(수동 누적이 prepend 제어 단순, useInfiniteQuery는 idiom). 선택 근거 KDoc.
**리뷰 반영(캐시 시드)**: replay 응답(detail)으로 `useRuleExecutionDetail` 쿼리 캐시 시드(setQueryData) → 새 실행 자동 펼침 시 재fetch 회피.
**REFACTOR**: 커서 계산 헬퍼 · KDoc.
**검증**: `pnpm --filter web test useAutomationExecutions`

### Task 4. MSW fixtures + handlers + 전역 등록

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/automation-execution-fixtures.ts`, `apps/web/src/mocks/automation-execution-handlers.ts`, `apps/web/src/mocks/automation-execution-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1]

**RED**: `automation-execution-handlers.test.ts`(setupServer) — list(최신순·issueKey 필터·limit·before 커서) · detail(404 존재숨김) · replay(stateful prepend·소프트삭제 룰 시나리오 409) · 빈 목록/충돌 시나리오 localStorage 토글. 실패: 핸들러 없음.
**GREEN**: 공유 store(`executionStore`) + 시드(automation-rule-fixtures 룰과 정합, RFC4122 v4 UUID) · 3 핸들러(bare DTO·ProblemDetail `detail` 필드·errorCode) · replay는 detail 반환+store prepend · `mocks/handlers.ts`에 `...automationExecutionHandlers` 등록.
**REFACTOR**: problemDetail 헬퍼 재사용 · KDoc(교훈 주석).
**검증**: `pnpm --filter web test automation-execution-handlers`
**함정**: [[msw-global-handler-registration-gap]](handlers.ts 전역 등록 필수)·[[msw-mutation-stateful-refetch]]·[[msw-derived-behavior-shared-store-e2e]]·[[e2e-msw-scenario-toggle-localstorage-flag]].

### Task 5. 실행 trace 행 컴포넌트 (펼침 + 인라인 replay 확인)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/RuleExecutionTraceRow.tsx`, `apps/web/src/components/automation/RuleExecutionTraceRow.test.tsx`]
- depends-on: [1, 3]

**RED**: `RuleExecutionTraceRow.test.tsx` — 요약 행(status 배지 색+텍스트·트리거 한국어 라벨·이슈키/"이슈 없음"·성공/전체·"재실행됨" 표식) · 클릭 시 detail 조회+펼침(액션별 ✓/✗·error 코드·triggerEvent JSON pre) · **detail fetch 중 로딩 표시**(펼침 영역 내, 리뷰 반영 D-gap) · "재실행" → 인라인 2단계 확인 → replay 호출 · 진행 중 disabled. 실패: 컴포넌트 없음.
**GREEN**: 펼침 로컬 상태 · `useRuleExecutionDetail`(enabled=펼침) · status→색맵(green/amber/red/gray) · triggerType 라벨맵(+원문 fallback, **기존 automation triggerType 라벨 맵 재사용 확인 후 없으면 신규**) · 인라인 confirm 토글 · replay mutation. aria-expanded·버튼 semantics(NFR1).
**REFACTOR**: 라벨/색맵 상수 추출 · KDoc.
**검증**: `pnpm --filter web test RuleExecutionTraceRow`
**함정**: 색상 단독 금지(색+텍스트), triggerEvent max-height 스크롤.

### Task 6. 실행 이력 Dialog (목록 + 필터 + 더 보기)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/RuleExecutionHistoryDialog.tsx`, `apps/web/src/components/automation/RuleExecutionHistoryDialog.test.tsx`]
- depends-on: [3, 5]

**RED**: `RuleExecutionHistoryDialog.test.tsx` — open 시 목록 조회 · 빈 상태("실행 이력이 없습니다") · 로딩 · 에러(403) · issueKey 필터 Enter 적용+리셋 · "더 보기" 커서 append · replay 성공 시 새 실행 맨 위+자동 펼침+토스트 · 409 토스트. 실패: 컴포넌트 없음.
**GREEN**: `Dialog as DialogPrimitive from 'radix-ui'` · props(open·onOpenChange·projectKey·ruleId·ruleName) · `useRuleExecutions` · TraceRow 매핑 · 필터 입력(Enter) · 더 보기 버튼(len===limit) · sonner 토스트 · replay 성공 새 행 자동 펼침.
**REFACTOR**: 문구 상수 · KDoc(자동 펼침·인라인 확인 사유).
**검증**: `pnpm --filter web test RuleExecutionHistoryDialog`
**함정**: [[react-usestate-stale-key-prop]](Dialog 토글/룰 전환 시 상태 stale — key 재마운트 또는 open 시 리셋), [[e2e-msw-serviceworker-block]] 회피(핸들러 기반).

### Task 7. 룰 목록에 "이력" 버튼 추가

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/AutomationRuleList.tsx`, `apps/web/src/components/automation/AutomationRuleList.test.tsx`]
- depends-on: []

**RED**: `AutomationRuleList.test.tsx` — 각 룰 행에 "이력" 버튼(aria-label `${rule.name} 실행 이력`) · 클릭 시 `onViewHistory(rule)` 콜백. 실패: 버튼 없음.
**GREEN**: `onViewHistory: (rule) => void` prop 추가 + 활성/수정/삭제 형제로 "이력" Button 추가.
**REFACTOR**: labels 상수에 `historyButton` 추가 · KDoc 갱신.
**검증**: `pnpm --filter web test AutomationRuleList`
**함정**: 기존 테스트 호출부 prop 추가 파급([[plan-files-constructor-injection-existing-tests]] 프론트판).
**리뷰 반영(E-gap2)**: prop 추가 전 `grep -rn "<AutomationRuleList" apps/web/src`로 **모든 렌더 소비처 확인**. required prop이면 route(T8) 외 소비처가 있으면 typecheck 파손 — 소비처가 route 단독이면 required 유지(T8이 전달), 다수면 optional(no-op 기본).

### Task 8. 설정 페이지 라우트 통합

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.automation.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx`]
- depends-on: [6, 7]

**RED**: 라우트 테스트 — "이력" 버튼 클릭 → RuleExecutionHistoryDialog 오픈(historyRule 상태) · 닫기 시 null. 실패: 미배선.
**GREEN**: `historyRule` useState + `AutomationRuleList onViewHistory={setHistoryRule}` + `RuleExecutionHistoryDialog`(open=historyRule!==null·ruleId/ruleName 전달·onOpenChange 닫힘 시 null).
**REFACTOR**: 핸들러 함수 분리 · KDoc(상태 5종으로 확장 사유).
**검증**: `pnpm --filter web test projects.\$projectKey.settings.automation`
**함정**: [[form-occ-409-parent-usestate-staleness]] 인접(부모 useState 관리) — Dialog 닫힘 시 상태 리셋.

### Task 9. E2E 시나리오 (Playwright)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/tests/e2e/automation-execution-history.spec.ts`]
- depends-on: [4, 8]

**RED/GREEN**: S1 목록 조회 · S2 trace 펼침 · S3 재실행 성공(새 실행 맨 위+토스트) · S4 409 토스트 · S5 빈 상태(localStorage 토글). MSW 핸들러 기반(serviceWorkers block 금지).
**검증**: `pnpm --filter web test:e2e automation-execution-history`
**함정**: [[ui-pr-defer-e2e-regression-latent]](기존 automation E2E 함께 실행)·[[playwright-getbyrole-exact-strict-mode]](텍스트 중복 시 exact/컨테이너)·[[e2e-orphan-vite-after-worktree-remove]](머지 후 5173 kill).

## Plan 메타

- task 수: 9
- 예상 wave: 약 6~7 (api→hook→component 체인 직렬 + T7 조기 병렬·T4 MSW 병렬)
- 예상 시간: 직렬 ~30분, wave 병렬 적용 시 ~15분
- TDD 강제: yes (RED→GREEN→REFACTOR)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산
- 추가 검증: typecheck(tsconfig.app.json), eslint, vitest, playwright(qa-engineer)
- **impl 선행 확인**: worktree node_modules 상태([[worktree-pnpm-verify-deps-symlink]]·[[worktree-node-modules-partial-install]]) — pnpm 의존성 정상인지 첫 test 전 확인.

## 리뷰 결과

집중 리뷰(design + eng 렌즈, Maxi 확정 — 기존 UI 미러라 mockup 생성/autoplan 4-phase 생략, [[bts-review-plan-autoplan-overkill]]).

### plan-design-review (집중, 2026-07-14)
- **평점 8/10.** 상태 커버(빈/로딩/에러 ✓)·계층(status 배지 우선 ✓)·접근성(색+텍스트, SKIPPED 회색+"조건 불충족" ✓)·인라인 confirm(중첩 Dialog focus-trap 회피 ✓)·replayedFrom "재실행됨" 표식 ✓·Dialog 상태 stale 함정 인지(T6 [[react-usestate-stale-key-prop]]).
- ⚠️ 반영: trace 펼침 시 detail fetch **중간 로딩 상태** 명시(T5 반영).
- 기존 automation 설정 UI 미러 → AI 슬롭 위험 없음, DESIGN.md 이미 준수.
- BLOCKER: 없음.

### plan-eng-review (집중, 2026-07-14)
- ✅ 통과: Zod nullable 3필드+triggerEvent unknown 계약(T1)·bare DTO(T2)·MSW stateful+전역등록(T4)·wave DAG 순환 없음·기존 테스트 파급 인지(T7).
- ⚠️ E-gap1(반영 T3): 페이지네이션 누적 모델(useInfiniteQuery vs 수동) 선택+KDoc 명시.
- ⚠️ E-gap2(반영 T7): AutomationRuleList prop 추가 전 소비처 grep, required/optional 판정.
- 💡 반영: replay 응답 detail로 detail 쿼리 캐시 시드(재fetch 회피, T3)·triggerType 라벨 기존 맵 재사용 확인(T5).
- BLOCKER: 없음.

**종합**: BLOCKER 0, CONCERN 반영 완료(T3·T5·T7). 저위험 순수 프론트, 게이트 1 진입 가능.
