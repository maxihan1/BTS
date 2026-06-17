# FR-MV-01 후속 정리 (C2 preview 응답 DTO + C3 MoveIssueDialog 분리)

> slug: fr-mv-01-c2-c3-cleanup
> type: chore (후속 리팩토링, classify 자동판정 backend → 조정)
> agent: backend-engineer (C2) + frontend-engineer (C3)
> 생성: 2026-06-17

## Brief

FR-MV-01 D6/D7(#155) 코드리뷰에서 후속으로 미뤄둔 C2·C3 정리. 한 PR.

- **C2 (백엔드 issue-tracking)**: 이슈 이동 preview 응답(`MovePreviewService.MovePreview` → `IssueMoveController.preview`)이
  도메인 객체 `Version`·`Component`·`CustomFieldDefinition`을 그대로 직렬화한다.
  `Version.startDate/releaseDate: LocalDate?`엔 `@JsonFormat`이 없어 Spring 기본 설정에 우연히 의존
  (`write-dates-as-timestamps=true`면 배열 직렬화 → 프론트 ZodError). 도메인의 내부 필드(`deletedAt` 등)도 와이어 노출.
  → preview 전용 응답 DTO(`MovePreviewResponse`)를 web layer에 도입, Controller에서 매핑.
  이미 존재하는 `VersionResponse.from`/`ComponentResponse.from`/`CustomFieldResponse.from` 재사용.
  프론트 Zod는 이미 정식 스키마(versionResponseSchema 등)를 기대 → 계약 1:1 정합(프론트 무변경 검증).

- **C3 (프론트 apps/web)**: `MoveIssueDialog.tsx` 721줄에서 `NodeMappingSection` 컴포넌트(~232줄)+
  헬퍼(`buildInitialNodeState`/`isNodeMappingValid`)+타입(`NodeMappingState`)을 별도 파일로 추출.

## Maxi 확정 (게이트 전)
- C2 범위: Version·Component·CustomFieldDefinition 3개 전부 DTO화 (일관성)
- PR 구성: 한 PR (C2 백엔드 + C3 프론트)

## 도메인 정리 (← /bts-domain 채움 — fast-track skip)
신규 용어 0, ADR 0, 도메인 변경 0. skip.

## 스펙 (← /bts-spec 채움 — fast-track skip)
신규 FR 0, 동작 변경 0. 직접 기술 스펙은 Plan에 흡수. skip.

## Plan (← /bts-plan 채움)

> 두 task는 영역(백엔드 vs 프론트)이 달라 `files` 교집합이 없다 → 병렬 wave 1개로 dispatch 가능.

### Task 1. C2 — preview 응답 전용 DTO 도입 + Controller 매핑 (도메인 객체 와이어 노출 제거)

**메타**.
- agent: `backend-engineer`
- files:
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/dto/MovePreviewResponse.kt` (신규)
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueMoveController.kt` (수정)
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueMoveControllerTest.kt` (수정 — RED 테스트 + 반환타입 import)
- depends-on: []

**배경**. `IssueMoveController.preview()`가 application 모델 `MovePreview`를 `DataResponse`에 그대로 담아 반환한다.
그 안의 `ResourceMappingSection.current/target: List<Component>`, `VersionMappingSection.current/target: List<Version>`,
`CustomFieldPreviewSection.removed/requiredMissing: List<CustomFieldDefinition>`이 **도메인 객체째로 직렬화**된다.
문제 2가지. (1) `Version.startDate/releaseDate: LocalDate?`엔 `@JsonFormat`이 없어 날짜 직렬화가 Spring 전역 설정에 의존
(`write-dates-as-timestamps=true`면 `[2026,6,17]` 배열 → 프론트 `versionResponseSchema` ZodError). (2) 도메인 내부 필드(`deletedAt`, `Version.releasedAt: Instant` 등)가 와이어에 새어 나간다.
이미 존재하는 정식 응답 DTO(`VersionResponse`/`ComponentResponse`/`CustomFieldResponse`)는 `@JsonFormat(pattern="yyyy-MM-dd")` + `.from()` 매핑을 갖추고 있고, 프론트 Zod도 이 정식 스키마를 기대한다.

**RED**:
- 파일: `IssueMoveControllerTest.kt`
- `MovePreviewService` stub이 **Version·Component·CustomFieldDefinition이 채워진** `MovePreview`를 반환하도록 fixture 추가
  (Version은 `startDate`/`releaseDate`/`deletedAt` 채움).
- 신규 테스트(예: `preview 응답의 version·component·customField는 정식 DTO 형태(내부 필드 미노출)`):
  ```kotlin
  // 도메인 내부 필드가 노출되지 않아야 한다
  .andExpect(jsonPath("$.data.affectsVersions.target[0].deletedAt").doesNotExist())
  .andExpect(jsonPath("$.data.affectsVersions.target[0].startDate").value("2026-06-17"))
  .andExpect(jsonPath("$.data.components.target[0].deletedAt").doesNotExist())
  .andExpect(jsonPath("$.data.customFields.requiredMissing[0].id").exists())
  ```
- 실패 (예상): 도메인 `Version` 직접 직렬화라 `deletedAt` 키가 존재 → `doesNotExist()` 실패.

**GREEN**:
- 파일: `MovePreviewResponse.kt` (신규, 패키지 `...adapter.inbound.rest.dto`)
  - `MovePreviewResponse`(version, workflow, components, affectsVersions, fixVersions, customFields, subtasks)
  - 섹션 DTO: `WorkflowPreviewSectionResponse`(compatible, targetStates: `List<WorkflowStateView>`, suggestedStateKey)
    — `WorkflowStateView`는 shared-kernel view(key/name/isDone 원시 타입)라 @JsonFormat 무관, 그대로 노출(프론트 `workflowStateViewSchema`와 1:1).
  - `ResourceMappingSectionResponse`(current/target: `List<ComponentResponse>`, autoMapping: `Map<UUID, UUID?>`)
  - `VersionMappingSectionResponse`(current/target: `List<VersionResponse>`, autoMapping)
  - `CustomFieldPreviewSectionResponse`(removed/requiredMissing: `List<CustomFieldResponse>`)
  - `SubtaskPreviewNodeResponse`(issueKey, issueTypeKey, version, workflow, components, affectsVersions, fixVersions, customFields)
  - 각 DTO에 `companion object { fun from(...) }` — `VersionResponse.from`/`ComponentResponse.from`/`CustomFieldResponse.from` 재사용.
- 파일: `IssueMoveController.kt`
  - `preview()` 반환 타입 `ResponseEntity<DataResponse<MovePreview>>` → `ResponseEntity<DataResponse<MovePreviewResponse>>`
  - `MovePreviewResponse.from(result)` 매핑 후 응답.

**REFACTOR**:
- 매핑 로직은 각 DTO `companion from()`에 응집. Controller는 `MovePreviewResponse.from()` 1줄 호출만.
- KDoc(L1 한글 주석 + 각 프로퍼티) 정비. detekt/ktlint 통과.

**검증**: `cd backend && ./gradlew :issue-tracking:test --tests "*IssueMoveControllerTest" --tests "*IssueMoveControllerIntegrationTest" --rerun-tasks` + `ktlintCheck detekt`.
프론트 무변경 정합: `apps/web/src/api/issue-move.ts`의 Zod가 이미 정식 스키마이므로 백엔드 출력이 그에 수렴함을 통합테스트로 확인(프론트 코드 수정 0).

### Task 2. C3 — MoveIssueDialog.tsx에서 NodeMappingSection 별도 파일 추출

**메타**.
- agent: `frontend-engineer`
- files:
  - `apps/web/src/components/issues/NodeMappingSection.tsx` (신규)
  - `apps/web/src/components/issues/MoveIssueDialog.tsx` (수정 — import로 교체)
- depends-on: []

**배경**. `MoveIssueDialog.tsx` 721줄. 안에 `NodeMappingSection` 컴포넌트(~232줄, L138-370)+헬퍼(`buildInitialNodeState` L42-76, `isNodeMappingValid` L86-98)+타입(`NodeMappingState` L21-33, `NodeMappingSectionProps` L103-137)이 한 파일에 있다. 순수 추출 리팩토링(동작 불변).

**RED**: (순수 추출이라 신규 실패 테스트 불필요 — 기존 `MoveIssueDialog.test.tsx`가 green 가드. 추출 직후 동일 green이 회귀 안전망.)

**GREEN**:
- 파일: `NodeMappingSection.tsx` (신규, L1 한글 주석)
  - `NodeMappingState`, `NodeMappingSectionProps`, `buildInitialNodeState`, `isNodeMappingValid`, `NodeMappingSection`을 이동.
  - `MoveIssueDialog`가 사용하는 항목(`NodeMappingSection`, `buildInitialNodeState`, `isNodeMappingValid`, `NodeMappingState`)은 `export`.
- 파일: `MoveIssueDialog.tsx`
  - 이동한 정의 삭제 + `import { NodeMappingSection, buildInitialNodeState, isNodeMappingValid, type NodeMappingState } from './NodeMappingSection'`.
  - 추출 후 사용 안 하게 된 import(예: NodeMappingSection 전용 하위 컴포넌트/유틸) 정리.

**REFACTOR**: 두 파일 모두 라인 길이/import 정렬 ktlint/eslint 정합. 공유 타입 중복 없음 확인.

**검증**: `cd apps/web && pnpm test MoveIssueDialog && pnpm typecheck && pnpm lint`. 기존 테스트 전부 green 유지(동작 불변 증명).

## Plan 메타

- task 수: 2
- 예상 시간: 직렬 약 14분, 병렬 wave 1개(파일 비겹침)로 약 8분
- TDD 강제: Task 1 yes(RED 신규), Task 2 회귀 가드(기존 green 유지)
- 병렬 dispatch: Task 1(backend) ∥ Task 2(frontend) — files 교집합 0
- 추가 검증: ktlint/detekt(backend), typecheck/lint/vitest(frontend). E2E 신규 불요(동작 불변).

## 리뷰 결과 (← /bts-review-plan 채움 — fast-track skip)
