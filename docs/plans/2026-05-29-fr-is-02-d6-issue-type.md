# FR-IS-02 D6 — 이슈 타입 표시 + 변경

> slug: fr-is-02-d6-issue-type
> type: feature (backend + frontend)
> agent: backend-engineer (primary) + frontend-engineer
> 생성: 2026-05-29

## Brief

FR-IS-02 D6 — 이슈 상세 화면에 이슈 타입 표시 + 변경 UI.

- **backend (issue-tracking)** — 개별 이슈의 타입(typeId) 변경 지원. 현재 PATCH `/issues/{key}` 는 summary + expectedVersion 만 받음 → typeId 변경 경로 신규. 계층/유효성 검증 동반.
- **frontend** — 이슈 상세 화면(`IssueMetaPanel.tsx` / `issues.$key.tsx`)에 타입별 아이콘 표시 + 타입 셀렉터로 변경.

backend `IssueResponse` 는 이미 `typeId: Long` / `typeKey: String` / `typeName: String` (non-null) 노출 확인 완료(grep 검증).
backend PATCH 는 typeId 변경 미지원 확인 완료 → 본 PR 에서 추가.

Maxi 결정: D6 스코프 = 표시 + 변경 (backend 동반). 경량 경로 (office-hours/autoplan skip).

## 도메인 정리

### 기존 코드 사실 확인 (grep 검증 완료)

- **backend `IssueResponse`** — `typeId: Long` / `typeKey: String` / `typeName: String` (모두 non-null) 이미 노출.
- **backend PATCH `/issues/{key}`** (`IssueController.update`) — 현재 `UpdateIssueRequest(summary?, expectedVersion)` 만 받음. typeId 변경 경로 없음 → 본 PR 신규.
  - app 레이어 `AppUpdateIssueRequest(summary, expectedVersion)` → `service.updateIssue(actor, key, req)`.
  - 권한 가드는 현재 `SYSTEM_ACTOR_UUID` 플레이스홀더 (초기 단계 — 별도 guard 없음). 기존 패턴 유지.
- **backend create** — `CreateIssueRequest.typeId: Long?` (null → task fallback). 식별은 typeId(숫자) 일관.
- **backend 카탈로그** `IssueTypeResponse.id: Long` 노출. `GET /api/v1/issue-types`.
- **frontend `issueResponseSchema`** (api/issues.ts) — type 3필드 **없음** (계약 갭 — 추가 필요).
- **frontend `issueTypeResponseSchema`** (api/issue-types.ts) — `key/name/description/iconUrl` 만, `id` **누락** (셀렉터가 typeId 보내려면 추가 필요).
- **frontend 타입 인프라 기존재** — `api/issue-types.ts`, `hooks/use-issue-types.ts`, MSW `issue-type-handlers.ts`/`issue-type-fixtures.ts` (admin MappingTable 용). 셀렉터에서 재사용.
- **frontend `IssueMetaPanel`** — 상태/보고자/프로젝트/버전/생성·수정일 세로 카드 + 삭제 버튼. 타입 행 신규 삽입.

### 도메인 규칙 결정 (Maxi 확정)

- **식별자** — PATCH 는 `typeId`(Long) 수신 (create 와 일관, FK 컬럼 issues.type_id). 프론트 카탈로그 Zod 에 `id` 추가해 셀렉터가 typeId 전송.
- **변경 정책** — 활성 타입(표준 5종 + 커스텀) 간 **자유 변경**. parent_id 계층 강제가 후속 FR 로 미루어져 깨질 계층 관계 없음 → 자유 변경이 현 상태와 일관. "표준 불변" 은 카탈로그 편집/삭제 금지일 뿐, 이슈에 표준 타입 부여는 허용.
- **유효성** — typeId 가 존재하지 않거나 soft-delete 된 타입이면 4xx 거부 (RFC 7807). 활성 타입만 수용.
- **낙관락(OCC)** — 기존 `expectedVersion` 재사용. 타입 변경도 version +1.
- **이벤트** — 기존 `updateIssue` 흐름의 이벤트 동작 그대로 따름 (별도 신규 이벤트 도입 안 함, 본 FR 범위 밖).
- **아이콘** — 표준 5종(epic/story/task/subtask/bug)은 lucide-react 내장 아이콘 매핑, 커스텀은 iconUrl (null 이면 기본 아이콘).

### learnings 회귀 가드 (적용 대상)

- `frontend-zod-backend-dto-contract-gap` — Zod 스키마를 backend DTO 와 1:1 맞춤 (type 3필드 + 카탈로그 id). ✅ 본 PR 핵심.
- `e2e-msw-serviceworker-block` — 새 mock 은 MSW 핸들러 추가, 분기 순서 backend 와 일치. issue PATCH 핸들러가 typeId 처리하도록 갱신.
- `react-usestate-stale-key-prop` — 셀렉터 현재값을 issue.typeId 로 useState 초기화 시 issue 변경에 key prop 재마운트 또는 props 직접 파생.
- `playwright-getbyrole-exact-strict-mode` — E2E 셀렉터 exact:true / 좁은 컨테이너 한정.
- `bts-plan-wave-gradle-module-compile` — 같은 Gradle 모듈(issue-tracking) test 컴파일 단위 공유 → backend task 직렬화.
- 병렬 dispatch race — implementer `git add <file>` 단위 명시.

## 스펙

### 사용자 시나리오

1. 사용자가 이슈 상세 화면을 연다 → 메타 패널 상단에 **유형(Type)** 행이 아이콘 + 타입명(예: 🐞 Bug)으로 표시된다.
2. 사용자가 유형 행의 셀렉터를 열면 활성 타입 목록(표준 5종 + 커스텀)이 아이콘과 함께 나온다.
3. 다른 타입을 선택하면 PATCH 로 저장되고, 메타 패널이 새 타입으로 갱신된다. version 이 +1 된다.
4. 동시에 다른 사용자가 먼저 수정해 version 이 어긋나면 409 → 사용자에게 충돌 안내.

### FR (기능 요구사항)

**Backend**
- FR-B1. REST `UpdateIssueRequest` (`adapter/inbound/rest/UpdateIssueRequest.kt`) 에 `typeId: Long?` 추가 (`@Positive`, null = 변경 안 함 / RFC 7396 merge-patch).
- FR-B2. app `UpdateIssueRequest` (`application/IssueApplicationRequests.kt`) + `IssueApplicationService.updateIssue` 가 typeId 변경 처리. **typeId 영속 경로 신규** — 현 `IssueRepository.updateSummary` 는 SUMMARY 만 set → typeId 를 함께 set 하는 OCC update 필요.
- FR-B3. typeId 가 존재하지 않거나 비활성(soft-deleted) → 4xx (RFC 7807). 활성 타입만 수용.
  - **검증 수단** — `issueTypeRepository.findById(typeId)` 사용 (`deleted_at IS NULL` 필터 내장 → soft-delete 자동 차단). cross-BC `IssueTypeLookup` 아님. null 이면 `IssueTypeNotFoundException`.
  - **null 의미 구분** — create 는 `typeId=null → task fallback`(`resolveTypeId`). PATCH 는 merge-patch 라 `typeId=null → 변경 안 함`. PATCH 는 `resolveTypeId`(fallback 포함) **사용 금지**, non-null 일 때만 위 검증 후 적용.
  - **예외 매핑 갭(리뷰 B1)** — `IssueTypeNotFoundException` 은 `IssueExceptionHandler`(basePackages `...adapter.inbound.rest`)에 핸들러가 **없어** 현재 500 으로 떨어짐(타입 핸들러는 `...type.web` 한정). 본 PR 에서 `IssueExceptionHandler` 에 `@ExceptionHandler(IssueTypeNotFoundException::class)` → 404 추가.
- FR-B4. 타입 변경 시 version +1 (OCC). expectedVersion 불일치 → 409. summary=null + typeId=non-null 케이스가 no-op(`buildChangedFields` 빈 분기)로 빠지지 않게 로직 보강.
- FR-B5. 응답 `IssueResponse` 에 변경된 typeId/typeKey/typeName 반영.

**Frontend**
- FR-F1. `issueResponseSchema` += `typeId`(int positive) / `typeKey`(string) / `typeName`(string). 주석 "9 필드"→"12 필드".
- FR-F2. `issueTypeResponseSchema` += `id`(int positive). **`iconUrl` → `iconName` 정정**(리뷰 B3 — backend 는 `iconName: String?` 식별자 노출, URL 아님. 현 프론트 `iconUrl` 은 MSW 위에서만 녹색인 계약 갭). `UpdateIssueInput` += `typeId?: number`. 기존 `iconUrl` 참조처(`MappingTable.tsx`, 픽스처, use-issue-types 테스트) 함께 정정.
- FR-F3. `IssueTypeIcon` 컴포넌트 — `iconName` 식별자(epic/story/task/subtask/bug + 커스텀) → lucide-react 아이콘 매핑, 미지정/미상은 기본 아이콘. (URL `<img>` 아님)
- FR-F4. `IssueMetaPanel` 에 유형 행 + 셀렉터. route 가 `use-issue-types` 로 활성 목록 조회 후 `availableTypes` props 전달. 현재 타입 아이콘은 availableTypes 에서 issue.typeId 매칭해 iconName 해석. 선택 시 `onTypeChange(typeId)` → route 가 `updateIssue(key, { typeId, expectedVersion: issue.version })` + invalidate.
- FR-F5. 셀렉터 현재값은 issue.typeId 로 props 파생 (stale key prop 회귀 가드).
- FR-F6. MSW: issue PATCH 핸들러가 typeId 수신 → 카탈로그에서 typeKey/typeName 조회해 응답 반영. 분기 순서 backend 일치(404 not-found → typeId 검증 4xx → 409 conflict → success). issue fixtures 에 type 3필드 추가. (기존 body `version` 드리프트도 `expectedVersion` 으로 정합)
- FR-F7. i18n `issueDetailStrings.typeLabel` 추가 (ko).

### NFR / 엣지

- 비활성 타입 선택 불가 (셀렉터 목록은 활성만).
- version 충돌(409) 시 한국어 에러 노출.
- 접근성 — 셀렉터 WCAG AA (min-h 44px, 키보드 조작, aria-label).

### 범위 제외 (명시)

- parent_id 계층 강제 / Epic-Subtask 부모 관계 검증 — 후속 FR.
- 이슈 생성 화면의 타입 선택 — 본 PR 은 상세 화면 변경만.
- 타입 변경 전용 이벤트 / 감사 로그 — 본 FR 범위 밖.

## Brainstorming Check
- 스코프 확정(표시+변경, backend 동반)은 Maxi 결정. office-hours/autoplan skip (경량 경로 — 정의된 FR, 메모리 `bts-spec-office-hours-mismatch`).
- 계약 갭(Zod) 사전 grep 검증으로 phantom 차단 (learnings #frontend-zod-backend-dto-contract-gap).

## Plan

> wave 설계 — backend(issue-tracking Gradle 모듈) ↔ frontend(pnpm) 는 다른 모듈이라 병렬 가능.
> 단 같은 issue-tracking test source set 공유하는 backend task 는 직렬(learnings `bts-plan-wave-gradle-module-compile`).
> frontend 는 MSW mock 위에서 동작 → backend 코드 컴파일에 의존 안 함(계약은 스펙에서 확정). 단 F3 는 F1/F2/F5 산출물 의존.

### Task 1. backend — service.updateIssue 가 typeId 변경 처리 + 영속

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceUpdateTest.kt`]
- depends-on: []

**RED**: `IssueApplicationServiceUpdateTest` 에 케이스 추가 — (a) typeId 변경 happy(version+1, 응답 typeKey/typeName 갱신) (b) 존재하지 않는/비활성 typeId → `IssueTypeNotFoundException` (c) typeId=null + summary 변경 → 타입 변경 없음(merge-patch) (d) **summary=null + typeId=non-null → 타입만 변경, no-op 아님**(리뷰 C3 회귀 가드).
**GREEN**:
- app `UpdateIssueRequest`(`IssueApplicationRequests.kt`) 에 `typeId: IssueTypeId?` 추가.
- `updateIssue` 가 typeId non-null 일 때만 `issueTypeRepository.findById`(deleted_at IS NULL 내장)로 검증 → null 이면 `IssueTypeNotFoundException`. `resolveTypeId` fallback 사용 금지(리뷰 C1).
- **typeId 영속(리뷰 B2)** — `IssueRepository` 에 type_id 를 set 하는 OCC update 신규(또는 `updateSummary` 확장). 현 `updateSummary` 는 SUMMARY 만 set 하므로 그대로는 typeId 못 바꿈.
- `buildChangedFields`/no-op 빈 분기/`requireNotNull(summary)` 가 typeId-only 변경을 무시·예외내지 않게 보강. version +1.
**REFACTOR**: 검증 로직 private 함수 추출 + KDoc.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*IssueApplicationServiceUpdateTest" --rerun-tasks`

### Task 2. backend — REST PATCH 가 typeId 수신 + RFC 7807 매핑

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/UpdateIssueRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerUpdateTest.kt`]
- depends-on: [1]   # 컨트롤러가 Task1의 service typeId 경로 호출

**RED**: `IssueControllerUpdateTest` — PATCH body 에 typeId 포함 시 200+갱신, 음수 typeId → 400(@Positive), 비활성/미존재 typeId → **실제 status 404 단언**(RFC 7807 body 포함).
**GREEN**:
- REST `UpdateIssueRequest` 에 `@field:Positive val typeId: Long? = null` 추가. 컨트롤러가 `AppUpdateIssueRequest(summary, typeId, expectedVersion)` 매핑.
- **예외 핸들러 신규(리뷰 B1)** — `IssueExceptionHandler` 에 `@ExceptionHandler(IssueTypeNotFoundException::class)` → 404 + RFC 7807 추가(이게 없으면 500 으로 떨어짐).
**REFACTOR**: KDoc typeId 항목 추가.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*IssueControllerUpdateTest" --rerun-tasks`

### Task 3. frontend — Zod 스키마 type 필드 + 카탈로그 id + UpdateIssueInput

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issue-types.ts`, `apps/web/src/api/issues.test.ts`]
- depends-on: []

**RED**: `issues.test.ts` — issueResponseSchema 가 typeId/typeKey/typeName 파싱(누락 시 reject), issueTypeResponseSchema 가 id + iconName 파싱, updateIssue 가 typeId 를 body 에 포함.
**GREEN**: `issueResponseSchema` += `typeId: z.number().int().positive()` / `typeKey: z.string().min(1)` / `typeName: z.string().min(1)` (주석 9→12 필드). `issueTypeResponseSchema` += `id: z.number().int().positive()` + **`iconUrl`→`iconName: z.string().nullable()` 정정**(리뷰 B3, backend 진실). `UpdateIssueInput` += `typeId?: number`. 기존 `iconUrl` 참조처(`MappingTable.tsx` 등) 함께 정정.
**REFACTOR**: 타입 추론 정리.
**검증**: `pnpm --filter web test issues.test.ts && pnpm --filter web typecheck`

### Task 4. frontend — IssueTypeIcon 컴포넌트 (iconName 식별자 → lucide)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueTypeIcon.tsx`, `apps/web/src/components/issue/IssueTypeIcon.test.tsx`]
- depends-on: []

**RED**: `IssueTypeIcon.test.tsx` — 표준 iconName(epic/story/task/subtask/bug)마다 대응 lucide 아이콘 렌더, 미상 iconName/null 이면 기본 아이콘. aria-label = 타입명.
**GREEN**: `IssueTypeIcon({ iconName, typeName })` — TYPE_ICON_BY_NAME 매핑 상수(lucide-react) + fallback 기본 아이콘. (URL `<img>` 아님 — iconName 은 식별자)
**REFACTOR**: 매핑 상수 추출 + JSX 정리.
**검증**: `pnpm --filter web test IssueTypeIcon.test.tsx`

### Task 5. frontend — MSW issue PATCH 핸들러 typeId 처리 + fixtures type 필드

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/issue-fixtures.ts`, `apps/web/src/mocks/issue-type-fixtures.ts`, `apps/web/src/mocks/__tests__/issue-handlers.test.ts`]
- depends-on: []

**RED**: issue-handlers 테스트 — PATCH body 에 typeId 오면 카탈로그(issue-type-fixtures)에서 typeKey/typeName 조회해 응답 반영, 비활성/미존재 typeId → 4xx. **분기 순서 backend 일치: 404 not-found → typeId 검증 4xx → 409 conflict → success**.
**GREEN**: `issue-handlers` PATCH 가 typeId 처리(+ body `version`→`expectedVersion` 정합). `issue-fixtures` 각 이슈에 typeId/typeKey/typeName 추가. `issue-type-fixtures` 의 `iconUrl`→`iconName` 정정(리뷰 B3).
**REFACTOR**: 카탈로그 lookup 헬퍼 추출.
**검증**: `pnpm --filter web test issue-handlers.test.ts`

### Task 6. frontend — IssueMetaPanel 유형 행 + 셀렉터 + route 배선 + i18n

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/issue/IssueMetaPanel.test.tsx`, `apps/web/src/routes/issues.$key.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [3, 4, 5]   # 스키마 타입 + IssueTypeIcon + MSW 응답 필요

**RED**: `IssueMetaPanel.test.tsx` — 유형 행이 IssueTypeIcon+typeName 표시(현재 타입 iconName 은 availableTypes 에서 issue.typeId 매칭해 해석), 셀렉터에 availableTypes 노출(활성만), 변경 시 `onTypeChange(typeId)` 호출, 셀렉터 현재값은 issue.typeId props 파생(stale key prop 회귀 가드). route 통합 — 타입 변경 시 updateIssue PATCH + invalidate, 409 시 한국어 에러.
**GREEN**: `IssueMetaPanel` 에 유형 행 + 셀렉터 추가. props `availableTypes: IssueTypeResponse[]` + `onTypeChange: (typeId: number) => void`. 현재 타입 iconName = `availableTypes.find(t => t.id === issue.typeId)?.iconName`. route(issues.$key.tsx)가 `useIssueTypes()` + 타입변경 mutation(updateIssue→invalidate) 소유. i18n `issueDetailStrings.typeLabel` 추가. WCAG AA(min-h 44px, aria-label).
**REFACTOR**: 셀렉터 컴포넌트 분리 검토 + 정리.
**검증**: `pnpm --filter web test IssueMetaPanel.test.tsx` + `pnpm --filter web typecheck`

## Plan 메타

- task 수: 6 (각 TDD 사이클)
- wave 예상: Wave1 [1, 3, 4, 5] 병렬(backend 1건 + frontend 3건, 파일/모듈 안 겹침) → Wave2 [2(after 1), 6(after 3,4,5)] → Wave3 통합 검증
- 예상 시간: 직렬 약 18분 / wave 병렬 약 8분
- TDD 강제: yes (test 커밋 먼저)
- 병렬 dispatch: bts-impl 이 depends-on + files 로 wave 계산. backend task(1,2) 같은 모듈 → 직렬 보장
- 추가 검증: ktlint/detekt(backend, --rerun-tasks), typecheck/lint/vitest(frontend)
- E2E(Playwright): **본 PR 범위 밖** — FR-IS-02 D7 후속. D6 는 backend + frontend UI + 단위/컴포넌트 + MSW 까지.

## 리뷰 결과

독립 eng 리뷰(code-reviewer dispatch, autoplan skip — 경량 경로). worktree 실제 코드 대조. **BLOCKER 3 + CONCERN 4 발견 → plan 전부 반영 완료.**

- **B1 (해결)** — `IssueTypeNotFoundException` 이 `IssueExceptionHandler`(rest 패키지)에 미매핑 → 현재 500. 타입 핸들러는 `type.web` 한정. → Task 2 에 핸들러 추가 + files 에 `IssueExceptionHandler.kt` 포함.
- **B2 (해결)** — `IssueRepository.updateSummary` 가 SUMMARY 만 set → typeId 영속 경로 없음 + `buildChangedFields` no-op 분기. → Task 1 에 repo OCC update 신규 + files 에 `IssueRepository.kt` 포함 + no-op/requireNotNull 보강.
- **B3 (해결)** — backend `IssueTypeResponse.iconName`(식별자) vs 프론트 `iconUrl` 미정합(MSW 위에서만 녹색, learnings #frontend-zod-backend-dto-contract-gap 재발). → Task 3/4/5 에서 프론트 `iconName` 으로 정정 + IssueTypeIcon 은 식별자→lucide 매핑.
- **C1 (해결)** — 검증 수단을 cross-BC `IssueTypeLookup` 아닌 `issueTypeRepository.findById`(deleted_at 필터 내장)로 명시.
- **C2 (해결)** — app DTO 경로 `application/UpdateIssueRequest.kt` → 실재 `IssueApplicationRequests.kt` 정정.
- **C3 (해결)** — Task 1 RED 에 "summary=null + typeId=non-null → 타입만 변경" 케이스 추가.
- **C4 (해결)** — Task 5 에 MSW 분기 순서(404→typeId 4xx→409→success) + body `expectedVersion` 정합 명시.
- **OK** — 계약 실재, soft-delete 경로, stale key prop 가드, wave/직렬화, BC 격리/스코프 모두 통과.
