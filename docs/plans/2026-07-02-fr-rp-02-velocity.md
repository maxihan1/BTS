# FR-RP-02 벨로시티 차트 (Velocity Chart)

> slug: fr-rp-02-velocity
> type: feature (classify 원출력 qa→오분류 정정, E2E/recharts 키워드 반응)
> agent: backend-engineer (프론트 D6/D7은 plan 메타 agent로 frontend-engineer 지정)
> primary_bc: notification-dashboard (엔드포인트는 agile-planning co-located 가능, FR-RP-01 선례)
> 생성: 2026-07-02

## Brief

여러 과거 스프린트에 걸쳐 "완료한 작업량"을 막대 차트로 보여주는 벨로시티 리포트.
팀이 스프린트마다 얼마나 처리하는지 추세를 파악. 선행 FR-RP-01(번다운) 완료.

- 백엔드: `GET /api/v1/projects/{id}/velocity` (product 문서 §4.2)
- 프론트: recharts 바 차트 (WorklogAggregateChart/BurndownChart 선례 재사용)
- E2E: Playwright

**핵심 결정 포인트 (domain/spec에서 확정)**.
- 벨로시티 지표 = 스토리포인트 vs 완료? FR-RP-01에서 "스토리포인트 미구현 → 추정 시간(초)"으로 확정한 이력.
  벨로시티도 동일하게 추정 시간(초) 기반으로 갈지, 이슈 수 기반으로 갈지 결정 필요.
- "완료" 판정 = 워크플로우 상태 카테고리 DONE 기준 (FR-EP-02 선례: WorkflowStateCatalog.listStates → category).
- 대상 스프린트 = 완료(CLOSED)된 최근 N개 스프린트.
- PR 분할 = FR-RP-01 선례(백엔드 D1~D5 / 프론트 D6/D7 2 PR) 따를지 단일 PR로 갈지.

## 도메인 정리

### BC / 모듈
- 논리 BC: **notification-dashboard** (fr-index §4.2 라벨 유지)
- 구현 모듈: **agile-planning** (엔드포인트가 Sprint 애그리거트와 co-located, FR-RP-01 선례와 동일 판정). 총 FR 카운트 불변.

### 신규 용어 (glossary 추가 대상 — Maxi 승인 후 미러)
- **벨로시티 / Velocity** — 여러 완료(`COMPLETED`) 스프린트에 걸쳐 **계획량(commitment)** 과 **완료량(completed)** 을 추정 시간(초) 기준으로 스프린트별 집계한 애자일 리포트. 팀의 처리 추세 파악용. 스토리포인트 미구현이라 초 단위로 측정(FR-RP-01 번다운과 동일). `GET /api/v1/projects/{projectKey}/velocity`.

### Maxi 확정 결정 (2026-07-02 domain 게이트)
1. **지표 단위 = 추정 시간(초)**. `issues.original_estimate_seconds` 합. 스토리포인트 필드 미존재(전수 grep 0건) → 초 기반 확정. FR-RP-01 일관.
2. **막대 = 계획(Commitment) vs 완료(Completed) 2막대**. Jira 벨로시티 정석.
   - Commitment(계획량) = 스프린트에 현재 속한 **가시(visible)** 이슈들의 `Σ original_estimate_seconds`.
   - Completed(완료량) = 그중 **현재 워크플로우 상태 카테고리가 DONE** 인 이슈들의 `Σ original_estimate_seconds`.
3. **PR 분할 = 백엔드/프론트 2 PR**. **이번 세션 = 백엔드 PR(D1~D5)**. 프론트 D6/D7 + E2E는 후속 PR.

### 완료 판정 방식 (on-the-fly, FR-RP-01 deviation 계승)
- "완료" = **조회 시점의 현재 상태**가 DONE 카테고리(스냅샷/`issue_history` 미사용). 완료된 스프린트는 역사적으로 확정돼 이슈 상태 변동이 거의 없으므로 현재 상태 기준이 타당. `issue_history` 기반 "스프린트 종료 시점 완료"는 FR-RP-03/04 도구로 위임.
- DONE 판정 = `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` → `category==DONE` (FR-EP-02 선례). 타입별 캐싱으로 N+1 차단, `WorkflowSchemeNoDefaultException` 폴백(미할당 타입 이슈는 미완료 취급, 500 차단).

### 대상 스프린트
- `SprintRepository.findByProject(projectKey, COMPLETED)` 재사용. 최근 N개(기본 상한, spec에서 확정 — 예: 10), 정렬 = end_date/created_at desc.

### 재사용 자원 (신규 스키마 0)
- Sprint 모델: `sprints`, `sprint_issues`(조인). `findByProject(projectKey, COMPLETED)` + `findIssueKeysByProject`(N+1 차단 배치).
- 추정치: `issues.original_estimate_seconds`(INT NULL, V027).
- 보안: 프로젝트 **BROWSE** 권한 + **이슈별 가시성 필터**(`filterVisibleIssueKeys` → `buildActiveSecureWhere` 재사용). 기밀 이슈 누출 차단.
- 완료 판정: `WorkflowStateCatalog` 포트(shared-kernel 정의, project-workflow 구현), `StateCategory{TODO,IN_PROGRESS,DONE}`.

### 신규 cross-BC 포트 (필요)
- 기존 `SprintBurndownLookupPort.fetchBurndownSource`는 "스프린트 전체 이슈의 추정시간 합(scope)"만 반환 → **완료분 미구분**. 벨로시티는 이슈별 (estimate, DONE 여부)가 필요.
- **신규 포트 `SprintVelocityLookupPort`**(shared-kernel 정의, **issue-tracking 구현**). 시그니처(초안): 스프린트별 issue-key 집합 + viewer → 스프린트별 `{commitmentSeconds, completedSeconds}` 반환. issue-tracking 어댑터가 가시성 필터 + `original_estimate_seconds` 합 + `WorkflowStateCatalog` DONE 판정을 한 곳에서 수행(FR-EP-02가 issue-tracking에서 WorkflowStateCatalog 소비하는 선례 존재). 정확한 시그니처는 plan에서 확정.

### 기존 결정 충돌
- 없음. FR-RP-01/FR-EP-02 패턴의 자연스러운 확장.

### 관련 ADR
- [docs/decisions/2026-07-02-fr-rp-02-velocity.md](../decisions/2026-07-02-fr-rp-02-velocity.md) (생성됨)
- 선행: `2026-07-02-fr-rp-01-burndown-burnup`, FR-EP-02 진행률 결정.

## 스펙

전체 스펙. [docs/specs/2026-07-02-fr-rp-02-velocity.md](../specs/2026-07-02-fr-rp-02-velocity.md)

핵심 계약 요약.
- `GET /api/v1/projects/{projectKey}/velocity?limit=10` → `DataResponse<VelocityResponse>`.
- 스프린트별 `commitmentSeconds`(현재 속한 가시 이슈 추정합) vs `completedSeconds`(그중 DONE 카테고리 추정합), 시간순 오름차순 + 평균 2개.
- 신규 스키마 0. 신규 포트 `SprintVelocityLookupPort`(shared-kernel 정의·issue-tracking 구현)가 가시성 필터+추정합+DONE 판정을 한 곳에서.
- 보안: 프로젝트 BROWSE(403) + actor-first(401) + 이슈별 가시성 필터(기밀 누출 0). 날짜 불필요(422 없음).

## Brainstorming Check

✅ 통과 (1회 iteration). 자체 적대 검토 3개 보강(`unit` 필드 제거·commitment deviation 명시·포트 부분반환 (0,0) 기본). 잔여 확인은 plan에서(프로젝트 미존재 404 vs 403, 포트 최종 시그니처).

## Plan

> 모듈 3곳: **shared-kernel**(포트 정의) · **issue-tracking**(포트 구현 어댑터) · **agile-planning**(도메인 VO·서비스·컨트롤러·DTO). 신규 DB 스키마 0.
> 포트 default 메서드로 fail-safe(빈 결과) 제공 → agile-planning 통합테스트는 `AgilePlanningTestcontainersConfig` 스텁 빈으로 값 주입(실 어댑터 미부팅). 어댑터는 issue-tracking Testcontainers로 독립 검증.

### Task 1. shared-kernel — SprintVelocityLookupPort 포트 정의

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/velocity/SprintVelocityLookupPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/velocity/SprintVelocityLookupPortDefaultTest.kt`]
- depends-on: []

**RED**.
- 파일: `SprintVelocityLookupPortDefaultTest.kt`
- 테스트: default 구현(`object : SprintVelocityLookupPort {}`)의 `fetchVelocitySource(비어있지 않은 map, "PROJ", uuid)`가 **빈 map** 반환(fail-safe). `VelocityContribution` data class equality.
- 실패 메시지(예상): `SprintVelocityLookupPort` / `VelocityContribution` 클래스 없음.

**GREEN**.
- `SprintVelocityLookupPort` 인터페이스 + fail-safe default 메서드:
  ```kotlin
  fun fetchVelocitySource(
      issueKeysBySprint: Map<UUID, Set<String>>,
      projectKey: String,
      viewerUserId: UUID,
  ): Map<UUID, VelocityContribution> = emptyMap()
  ```
- `VelocityContribution(commitmentSeconds: Long, completedSeconds: Long)` VO.

**REFACTOR**.
- KDoc: BC 격리 사유(shared-kernel 배치), 가시성 필터 요구(C1), 빈 집합 early-return, viewer 스코프 부분값 의도. `SprintBurndownLookupPort` KDoc 톤 답습.

**검증**: `./gradlew :backend:modules:shared-kernel:test --tests '*SprintVelocityLookupPortDefault*'`

### Task 2. agile-planning — 순수 벨로시티 VO + 평균 계산

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/velocity/VelocityPoint.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/velocity/SprintVelocityResult.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/velocity/SprintVelocityResultTest.kt`]
- depends-on: []

**RED**.
- 파일: `SprintVelocityResultTest.kt`
- 테스트:
  - `of(projectKey, points)`가 `averageCommitmentSeconds`/`averageCompletedSeconds`를 산술평균(정수 나눗셈 반내림, 번다운 rounding 관례 확인 후 통일)으로 계산.
  - 빈 points → 평균 0, `sprints` 빈 리스트.
  - points 순서 보존(시간순 오름차순 입력 그대로).
- 실패 메시지(예상): `SprintVelocityResult`/`VelocityPoint` 없음.

**GREEN**.
- `VelocityPoint(sprintId: UUID, name: String, startDate: LocalDate?, endDate: LocalDate?, commitmentSeconds: Long, completedSeconds: Long)` — 순수 data class.
- `SprintVelocityResult(projectKey: String, averageCommitmentSeconds: Long, averageCompletedSeconds: Long, points: List<VelocityPoint>)` + `companion of(projectKey, points)` 평균 계산(외부 의존 0).

**REFACTOR**.
- 평균 계산 헬퍼 private 추출 + KDoc. `EpicProgress.of` 순수 집계 톤 답습.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*SprintVelocityResultTest*'`

### Task 3. issue-tracking — SprintVelocityLookupAdapter (포트 구현)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/velocity/SprintVelocityLookupAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/velocity/SprintVelocityLookupAdapterTest.kt`]
- depends-on: [1]

**RED** (Testcontainers).
- 파일: `SprintVelocityLookupAdapterTest.kt`
- 시드: 2개 스프린트 issue-key 집합, 이슈에 `original_estimate_seconds`, 워크플로우 상태(DONE/비-DONE 카테고리), 기밀 이슈(보안 등급), 스킴 미할당 타입.
- 테스트:
  - 스프린트별 `commitmentSeconds` = 가시 이슈 추정합, `completedSeconds` = DONE 카테고리 가시 이슈 추정합.
  - 기밀 이슈 estimate가 두 합 모두에서 제외(C1).
  - 스킴 미할당 타입 이슈 → completed 미포함, commitment 포함(500 없음).
  - NULL estimate=0, soft-deleted 이슈 제외.
  - 빈 이슈키 집합 map → 해당 sprintId 미반환 또는 (0,0)(early-return 함정 확인).

**GREEN**.
- `@Component @Transactional(readOnly = true)` 어댑터. `WorkflowStateCatalog.listStates` MANDATORY 전파 충족.
- 흐름: 전체 이슈키 flatten → `accessibleLevels` + `filterVisibleIssueKeys`(정본 보안술어 재사용) → 가시 이슈의 (key, estimate, currentStateKey, issueTypeKey) 조회(1쿼리, deleted_at 제외) → 타입별 `listStates` 캐싱 DONE 판정(N+1 차단, `WorkflowSchemeNoDefaultException` simpleName catch 폴백) → issueKey→sprintId 역맵(UNIQUE(issue_key))으로 스프린트별 commitment/completed 집계.
- 주입: `IssueRepository`(또는 정본 조회), `SecurityDirectory`(accessibleLevels), `WorkflowStateCatalog`. 번다운 어댑터 주입 대조.

**REFACTOR**.
- SQL 상수·DONE 판정 헬퍼 추출 + KDoc(가시성 필터 재사용 사유, DONE 폴백 사유). detekt MaxLineLength/NestedBlockDepth 사전 점검.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*SprintVelocityLookupAdapter*'`

### Task 4. agile-planning — SprintVelocityService (오케스트레이션)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/SprintVelocityService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/SprintVelocityExceptions.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/SprintVelocityServiceTest.kt`]
- depends-on: [1, 2]

**RED** (mockk 단위).
- 파일: `SprintVelocityServiceTest.kt`
- mock: `SprintVelocityLookupPort`, `SprintRepository`(findByProject/findIssueKeysByProject), `IssuePermissionResolver`(BROWSE).
- 테스트:
  - BROWSE 미충족 → 403(BacklogApplicationService 예외 패턴 대조 후 통일).
  - `findByProject(projectKey, COMPLETED)` 결과 최근 `limit`개만, 시간순 오름차순 정렬.
  - `limit` 클램프 [1,50](0·음수·51 입력).
  - 포트가 일부 sprintId 미반환 → 서비스가 (0,0) 기본 처리(E2).
  - 결과가 `SprintVelocityResult.of`로 평균 계산.
- 실패 메시지(예상): `SprintVelocityService` 없음.

**GREEN**.
- `@Service` `SprintVelocityService(velocityPort, sprintRepository, permissionResolver)`.
- 흐름: actor BROWSE 판정(403) → `findByProject(projectKey, COMPLETED)` → 최근 limit 선택 + 오름차순 → `findIssueKeysByProject` 배치로 sprintId→issueKeys map → 포트 1회 호출 → 미반환 sprintId (0,0) 기본 → `VelocityPoint` 매핑 → `SprintVelocityResult.of`.
- `SprintVelocityExceptions.kt`: 필요 시 403 예외(백로그 패턴과 동일 타입 재사용 우선, 신설 최소화).

**REFACTOR**.
- limit 클램프 상수, KDoc. BROWSE 판정 헬퍼는 백로그/번다운 서비스 대조.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*SprintVelocityServiceTest*'`

### Task 5. agile-planning — 컨트롤러 + DTO + 예외핸들러 + 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/SprintVelocityController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/VelocityResponse.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/SprintExceptionHandler.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/AgilePlanningTestcontainersConfig.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/integration/SprintVelocityIntegrationTest.kt`]
- depends-on: [4]

**RED** (통합, Testcontainers + 스텁 포트).
- 파일: `SprintVelocityIntegrationTest.kt`
- `AgilePlanningTestcontainersConfig`에 `SprintVelocityLookupPort` 스텁 빈 추가(원하는 sprintId→contribution 반환, 번다운 스텁 대조).
- 시드: COMPLETED 스프린트 N개(+PLANNED/ACTIVE 섞기), BROWSE 부여/미부여 사용자.
- 테스트: S1(200 happy, 시간순·평균), S2(완료 스프린트 0개→빈 리스트), S4(403), S5(401), E6(limit param), E7(날짜 null 포함).
- 실패 메시지(예상): `SprintVelocityController` 없음.

**GREEN**.
- `SprintVelocityController` `@RequestMapping("/api/v1/projects/{projectKey}/velocity")` + `@GetMapping`(`@RequestParam limit`) → actor-first 401 → `service` 위임 → `DataResponse(VelocityResponse.from(result))`. BacklogController 헬퍼(currentActorId) 대조.
- `VelocityResponse`/`VelocityPointResponse` + `from(result)` companion(BurndownResponse 대조).
- `SprintExceptionHandler`의 `@RestControllerAdvice(assignableTypes=[...])`에 `SprintVelocityController::class` 추가(ResponseStatusException passthrough + 500 폴백 스코프).

**REFACTOR**.
- 컨트롤러/DTO KDoc. 신규 full-boot 테스트가 포트 빈 부재로 NoSuchBean 나지 않도록 다른 agile-planning boot 테스트(OpenApi/Context) 점검 — 필요 시 @MockkBean 동반(memory: new-crossbc-dep-openapi-mockbean-regression).

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*SprintVelocity*'`

## Plan 메타

- task 수: 5
- 예상 wave: 3 (Wave1: T1·T2 병렬 / Wave2: T3·T4 병렬 / Wave3: T5)
- 예상 시간: 직렬 ~15분, wave 적용 ~9분
- TDD 강제: yes (각 task test→green→refactor 커밋 순서)
- 추가 검증: ktlint/detekt(--rerun-tasks) green, verify-master-plan(카운트 불변·D1~D5 마킹)
- 프론트 D6/D7 + E2E: **이 PR 범위 밖**(후속 PR)

## 리뷰 결과 (← /bts-review-plan 채움)
