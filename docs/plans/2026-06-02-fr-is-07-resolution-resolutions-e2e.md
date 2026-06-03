# FR-IS-07 — 이슈 Resolution(해결 결과) 필드

> slug: fr-is-07-resolution-resolutions-e2e
> type: migration
> agent: db-engineer (primary; backend/frontend/qa 혼합)
> 생성: 2026-06-02

## Brief

종료(DONE 카테고리) 상태로 전이할 때 Resolution(Fixed/Won't Fix/Duplicate 등)을 필수로
선택하게 하고, Resolution 미설정 시 종료 전이를 거부한다.

- 데이터 모델: resolutions 테이블 + issues.resolution_id
- 백엔드: 종료 전이 가드(Resolution 미설정 시 reject)
- 프론트: 종료 모달(전이 시 Resolution 선택)
- E2E: 종료 시 Resolution 필수 흐름
- BC: issue-tracking. 선행 FR-IS-01(완료) + project-workflow 전이.
- classify: type=migration, agent=db-engineer
- 참조: docs/plan/product/issue-tracking.md §2.1.5

## 도메인 정리

- **BC**: issue-tracking (Resolution 데이터/엔티티). 단, "종료 시 필수" 강제는 project-workflow 게이트 프레임워크와 연관(아래 갈림길).
- **새 엔티티**: `Resolution`(resolutions 테이블: id, key, name, description?, display_order, is_standard) + `Issue.resolutionId`(nullable FK 미적용, BC격리). 표준 세트(Fixed/Won't Fix/Duplicate/Cannot Reproduce/Done 등) 불변 + 커스텀 추가 — IssueType 표준 5종 패턴과 동형.
- **새 용어**: glossary "해결 결과(Resolution)" 추가 완료. 상태(open/closed)와 별개 축.
- **핵심 발견 (아키텍처 갈림길)**: project-workflow에 이미 **전이 게이트 검증 프레임워크**가 존재. `RequiredFieldValidator`(`com.bts.workflow.validator.RequiredFieldValidator`)의 docstring 예시가 **정확히 `{ "field": "resolution" }`** — resolution을 염두에 두고 설계됨. `StateCategory.DONE` enum도 존재. 전이 시 `TransitionContext.request.issueFields[field]`로 이슈 필드 검사.
  - **옵션 A (워크플로우 게이트, Jira식)**: DONE 전이에 RequiredFieldValidator(field=resolution) 설정 + issue-tracking이 전이 시 issueFields에 resolution 전달. 기존 프레임워크 재사용, 워크플로우별 설정 가능(유연). 단 project-workflow YAML/seed 설정 변경 동반(2 BC 걸침 가능).
  - **옵션 B (issue-tracking 하드코딩 가드)**: 전이 목표 상태 category==DONE && resolutionId==null → reject(issue-tracking 단독). 단순·단일 BC. 단 항상 강제(워크플로우별 opt-out 불가), 기존 검증 프레임워크 미활용.
  - → **결정: 옵션 A 채택** (2026-06-03 Maxi). RequiredFieldValidator(config 예시가 정확히 `{ "field": "resolution" }`) 재사용 + Jira 방식. issue-tracking은 전이 시 `issueFields`에 resolution 전달(`IssueApplicationService` 전이/가용전이 경로, 현재 summary만 전달). 2 BC 걸침은 bts-plan에서 PR 분리 또는 learning 2026-05-22 선례 적용으로 처리.
- **기존 결정**: 충돌 없음. StateCategory.DONE([[domain/project-workflow]]), 워크플로우 게이트/validator 프레임워크 활용. IssueType 표준 불변 패턴 재사용.
- **관련 ADR**: docs/adr/2026-06-03-resolution-required-on-done-transition.md (옵션 A, 채택).
- **grill-with-docs 스킵**: 백엔드 구조 직접 grep으로 엔티티·게이트 프레임워크·StateCategory 확인. 핵심 결정은 갈림길 AskUserQuestion으로.

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-is-07-resolution-resolutions-e2e.md](../specs/2026-06-03-fr-is-07-resolution-resolutions-e2e.md)

핵심 시나리오.
- 종료(DONE) 전이 선택 시 Resolution 모달 → 선택 후 전이(미선택 시 거부, RequiredField validator).
- 재오픈(DONE→비DONE) 시 resolution_id 자동 clear(모든 전이가 resolution_id=request.resolutionId 영속, 비DONE은 null).
- 표준 5종 seed(Fixed/Won't Fix/Duplicate/Cannot Reproduce/Done) + `GET /api/v1/resolutions`. 커스텀 CRUD는 후속(IssueType 선례).

확정 결정.
- 결정 1: 가용 전이 응답에 `toCategory` additive 노출(프론트가 DONE 판별 → 모달 트리거). "서버가 정답지" ADR 일관.
- 결정 2: 커스텀 Resolution CRUD는 이번 범위 제외(후속).
- 결정 3 [Brainstorming BLOCKER]: **옵션 A — validator 단계(availability/execution) 구분 채택**. RequiredField=execution-only, availableTransitions는 availability만 평가(안 그러면 DONE 전이가 목록에서 사라져 이슈를 못 닫는 critical 버그). project-workflow SPI 변경 동반.
- 결정 4: 재오픈 clear는 카테고리 불필요(resolutionId 영속만으로 충족).
- 마이그레이션 = **V010**(V009는 components 선점). init_codegen 미러 필수.

## Brainstorming Check

✅ 통과 (1회 iteration). Critical gap 1건 발견·해소 — availableTransitions가 RequiredField로 DONE 전이를 숨겨 이슈를 못 닫는 버그(결정 3, 옵션 A로 해결). 부수 발견 — 재오픈 clear는 카테고리 불필요(결정 4), 마이그레이션 V009→V010 정정.

## 재개 갱신 (2026-06-03, FR-WF-03 PR #66 머지 후)

선행 FR(validator 런타임 결선 = FR-WF-03 PR #66)이 머지되어 보류 해제. main 머지 후 코드 직접 재검증한 결과, 이전 BLOCKER가 모두 해소되고 PR-A가 대폭 축소됨.

**이전 BLOCKER 해소 (코드 재검증 완료).**
- **B-1 해소.** `DefaultWorkflowValidatorFactory`(@Component) + `DefaultWorkflowDefinitionRepository`(@Component) + `WorkflowEngineConfig` production 결선 완료. 옵션 A "프레임워크 재사용" 전제 복구.
- **B-2 해소.** `YamlSeedService`가 transitions의 `validators`/`postActions`를 시드(`ValidatorYamlDto`), 부팅 시 type dry-run 검증(미지원 type fail-fast). `applyIfChanged`/`differsInValidators`가 전이별 validator type 목록 비교 → DONE 전이에 validator 추가 시 변경 감지·재시드. (메모리 "isDirty config 미감지"는 *동일 type+config만 변경* 경우 — 우리는 type 추가라 감지됨)
- **B-3 해소.** B-1+B-2 귀결로 자동 해소.

**이미 구현된 task (제거).**
- **A2 (ValidatorPhase + RequiredField=EXECUTION 분류): 완료.** `WorkflowValidator.phase: ValidatorPhase`(기본 AVAILABILITY), `RequiredFieldValidator.phase = EXECUTION` 이미 존재(FR-WF-03).
- **A3 (availableTransitions가 EXECUTION validator skip): 완료.** `WorkflowEngine.kt:349` `if (validator.phase == ValidatorPhase.EXECUTION) continue` 이미 존재.

**전제 변경 (현 코드 반영).**
- **B1 마이그레이션: V010 → V011.** V010은 FR-VR-01(versions, PR #67)이 선점. resolutions는 V011.
- **B7: 마이그레이션(V203) 폐기 → production 워크플로우 YAML 편집.** `src/main/resources/workflows/*.yaml`의 DONE 카테고리 대상 전이에 `validators: [{type: RequiredField, config: {field: resolution}}]` 추가. YamlSeedService가 부팅 시 시드(변경 감지·재시드). seed INSERT 마이그레이션 불필요.
- **B6 영속 경로(B-4 해소안): raw jOOQ `IssueRepository.applyTransition`(line 209) 확장.** 전이 영속은 *원래부터* 도메인 우회(낙관락 raw UPDATE)가 정본 — resolution 필수 불변식은 Issue Aggregate가 아니라 워크플로우 RequiredField validator(`plan()` 호출 시점, EXECUTION phase)가 강제하므로 patch-merge-domain-bypass 위배 아님. applyTransition에 `resolutionId: UUID?` 파라미터 추가해 같은 UPDATE에서 `ISSUES.RESOLUTION_ID` set(null이면 clear).
- **C-3 신규 task (B11): IssueResponse.resolution 노출.** 프론트(B9)가 표시할 resolution을 backend 읽기 모델(`findByKeyWithType`/IssueResponse)에 추가. 누락 시 frontend-zod-backend-dto-contract-gap 재발.

**게이트1에서 Maxi 확인 필요한 설계 갈림길** — 본문 "재개 설계 결정" 참조.

## Plan

> **PR 전략 (2 PR, 2026-06-03 Maxi 결정).**
> - **PR-A** (project-workflow + shared-kernel): validator 단계(availability/execution) 구분 + 가용전이 toCategory 노출. 순수 인프라, 동작 변화 없음, 독립 머지. Task A1~A4.
> - **PR-B** (issue-tracking + project-workflow DONE seed + frontend + E2E): Resolution 데이터/전이/모달/E2E + DONE 필수 seed. 기능 원자적 활성화, PR-A 의존. Task B1~B10.
> - 회귀 윈도우 방지: DONE 필수 seed(B7)는 issue-tracking이 resolution을 보내는 변경(B6)과 같은 PR-B에 묶임.
> - depends-on의 `A*`는 PR-A 머지 선행을 의미(cross-PR). PR-B 내부 의존은 `B*`.

### ── PR-A: validator 단계 구분 + toCategory (project-workflow + shared-kernel) ──

### Task A1. shared-kernel AvailableTransitionView에 toCategory 추가

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/AvailableTransitionsResult.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/workflow/AvailableTransitionViewTest.kt`]
- depends-on: []

**RED**: `AvailableTransitionViewTest` — `AvailableTransitionView`가 `toCategory: String?`(DONE/IN_PROGRESS/TODO, nullable) 필드를 보유하고, **기존 3-인자 생성도 그대로 컴파일**(default null)되는지. 컴파일 실패(필드 없음).

**GREEN**: `AvailableTransitionView`에 `val toCategory: String? = null` **nullable + default** additive 추가. nullable인 이유: 생성지점이 production 1곳(WorkflowEngine:212)+테스트 7파일 — default null이면 테스트 생성지점 무변경, 실데이터는 A4가 항상 채움. published language 최소 표면(enum 직접 노출 대신 string — BC 격리).

**REFACTOR**: KDoc에 toCategory 의미(목표 상태 카테고리, 프론트 종료 판별용. 실 응답은 항상 non-null, null은 미계산 테스트 픽스처) 명시.

**검증**: `./gradlew :modules:shared-kernel:test --tests "*AvailableTransitionViewTest"`

### Task A2. WorkflowValidator SPI에 적용 단계(phase) 속성 추가 + 기존 4종 분류 — ✅ FR-WF-03에서 완료 (재개 시 제거)

> **완료(FR-WF-03 PR #66).** `WorkflowValidator.phase: ValidatorPhase`(기본 AVAILABILITY), `RequiredFieldValidator.phase = EXECUTION` 존재. 신규 작업 없음. 아래는 이력 보존용.

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/spi/WorkflowValidator.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/RequiredFieldValidator.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/*Validator.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorPhaseTest.kt`]
- depends-on: []

**RED**: `ValidatorPhaseTest` — `RequiredFieldValidator.phase == EXECUTION`, `PermissionValidator.phase == AVAILABILITY`, `NotStatusCategoryValidator.phase == AVAILABILITY`, `CustomExpressionValidator.phase == AVAILABILITY`(보수적 기본). 컴파일 실패(phase 없음).

**GREEN**: `WorkflowValidator`에 `val phase: ValidatorPhase`(enum AVAILABILITY/EXECUTION) 추가. 각 구현체 분류. RequiredField=EXECUTION(필드는 실행 시 채워짐), 나머지=AVAILABILITY.

**REFACTOR**: `ValidatorPhase` enum KDoc — availability=목록 노출 게이트, execution=실행 시점 게이트(Jira transition screen 시맨틱).

**검증**: `./gradlew :modules:project-workflow:test --tests "*ValidatorPhaseTest"`

### Task A3. availableTransitions가 EXECUTION 단계 validator를 건너뜀 — ✅ FR-WF-03에서 완료 (재개 시 제거)

> **완료(FR-WF-03 PR #66).** `WorkflowEngine.kt:349` `if (validator.phase == ValidatorPhase.EXECUTION) continue` 존재 + `WorkflowEngineAvailabilityPhaseTest` 통과. 신규 작업 없음. 아래는 이력 보존용.

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/WorkflowEngine.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineAvailabilityPhaseTest.kt`]
- depends-on: [A2]

**RED**: `WorkflowEngineAvailabilityPhaseTest` — RequiredField(resolution) validator가 걸린 DONE 전이가, issueFields에 resolution 없어도 `availableTransitions` 결과에 **포함**됨. 동시에 `plan`(실행)은 resolution 없으면 여전히 `WorkflowValidatorFailureException`. 현재는 availableTransitions가 제거 → 테스트 실패.

**GREEN**: `WorkflowEngine.availableTransitions`의 validator 루프(line 303~)에서 `validator.phase == AVAILABILITY`인 것만 평가. `plan`(line 241~)은 전부 평가(변경 없음).

**REFACTOR**: 두 루프의 validator 평가 공통 부분 추출 검토(phase 필터만 차이). 과도 추상화 금지 — 작으면 그대로.

**검증**: `./gradlew :modules:project-workflow:test --tests "*WorkflowEngineAvailabilityPhaseTest"`

### Task A4. availableTransitions/plan 결과에 toCategory 채움

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/WorkflowEngine.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineToCategoryTest.kt`]
- depends-on: [A1, A3]

**RED**: `WorkflowEngineToCategoryTest` — `availableTransitions` 결과의 각 `AvailableTransitionView.toCategory`가 목표 상태(toStateKey)의 StateCategory와 일치(DONE 전이는 "DONE"). 실패(현재 미설정).

**GREEN**: WorkflowEngine이 transition.toStateKey로 workflow.states에서 목표 상태를 찾아 그 category를 AvailableTransitionView.toCategory에 매핑.

**REFACTOR**: 상태 조회 헬퍼 정리(이미 있으면 재사용).

**검증**: `./gradlew :modules:project-workflow:test --tests "*WorkflowEngineToCategoryTest"`

### ── PR-B: issue-tracking Resolution + DONE seed + frontend + E2E ──

### Task B1. V011 마이그레이션 — resolutions 테이블 + issues.resolution_id + init_codegen 미러

> **버전 정정: V010 → V011.** V010은 FR-VR-01(versions)이 선점(현 main 확인). resolutions는 V011.

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V011__resolutions.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/resolution/repository/ResolutionMigrationTest.kt`]
- depends-on: []

**RED**: Testcontainers 통합 — `resolutions` 테이블 + 표준 5종 seed row 존재, `issues.resolution_id` 컬럼 존재. 마이그레이션 미작성 → 실패.

**GREEN**: V011 작성 — `resolutions(id uuid pk, key text unique not null, name text not null, description text null, display_order int not null, is_standard boolean not null default false, created_at timestamptz, updated_at timestamptz, deleted_at timestamptz null)` + 표준 5종 INSERT(fixed/wontfix/duplicate/cannotreproduce/done) + `ALTER TABLE issues ADD COLUMN resolution_id uuid null`. **init_codegen.sql에 동일 미러**(메모리 jooq-init-codegen-mirror — 누락 시 jOOQ 상수 미생성, repository 컴파일 불가).

**REFACTOR**: seed display_order 정렬값 정리, 주석으로 FK 미적용(BC 격리) 명시.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*ResolutionMigrationTest"` + jOOQ codegen 성공. (V010 versions와 충돌 없음 — V011)

### Task B2. Resolution 도메인 엔티티 (표준 5종 불변 + factory)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/resolution/domain/Resolution.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/resolution/domain/ResolutionTest.kt`]
- depends-on: []

**RED**: `ResolutionTest` — `Resolution.create`가 빈 name 거부, 표준 5종 companion 상수(FIXED/WONT_FIX/DUPLICATE/CANNOT_REPRODUCE/DONE) isStandard=true. 컴파일 실패.

**GREEN**: IssueType.kt 패턴 동형 — data class + companion factory + 표준 5종 상수.

**REFACTOR**: KDoc, key 슬러그 규칙 명시.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*ResolutionTest"`

### Task B3. Resolution 리포지토리 (jOOQ, 활성 목록 조회)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/resolution/repository/ResolutionRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/resolution/repository/ResolutionRepositoryTest.kt`]
- depends-on: [B1, B2]

**RED**: Testcontainers — `findAllActive()`가 표준 5종을 display_order asc로 반환, deletedAt 있는 건 제외. 실패.

**GREEN**: jOOQ 기반 repository. RESOLUTIONS 테이블 상수 사용(B1 codegen 산출).

**REFACTOR**: 매핑 함수 추출.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*ResolutionRepositoryTest"`

### Task B4. GET /api/v1/resolutions 엔드포인트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/resolution/web/ResolutionController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/resolution/application/ResolutionApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/resolution/web/ResolutionControllerTest.kt`]
- depends-on: [B3]

**RED**: `ResolutionControllerTest`(MockMvc) — `GET /api/v1/resolutions`가 200 + `{ data: [ {id,key,name,description,displayOrder,isStandard} x5 ] }`. 실패(컨트롤러 없음).

**GREEN**: 컨트롤러 + 서비스. 인증 필요(JWT/PAT — 일반 조회). 새 빈 추가 시 모듈 전체 test로 부팅 확인(메모리 profile-scoped-bean-boot-failure).

**REFACTOR**: DTO 매핑, 에러코드 컨벤션 정렬.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*ResolutionControllerTest"` + 모듈 전체 test(부팅 확인).

### Task B5. 전이 요청 DTO에 resolutionId 추가 (transport + application)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/TransitionIssueRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/TransitionIssueRequestTest.kt`]
- depends-on: []

**RED**: `TransitionIssueRequestTest` — transport DTO가 `resolutionId: UUID?`(nullable) 보유 + application DTO로 매핑. 실패.

**GREEN**: 두 DTO에 `resolutionId: UUID?` 추가(nullable, 비DONE 전이는 null). transport→application 매핑 갱신.

**REFACTOR**: KDoc — resolutionId 의미(DONE 전이 시 필수, 비DONE은 무시/clear).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*TransitionIssueRequestTest"`

### Task B6. transitionIssue — resolution을 issueFields 전달 + resolution_id 영속 + 재오픈 clear

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/*Repository*.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueTransitionResolutionIntegrationTest.kt`]
- depends-on: [B1, B5]

**RED**: Testcontainers 통합 `IssueTransitionResolutionIntegrationTest` — (1) DONE 전이 + resolutionId → resolution_id 영속, (2) DONE 전이 + resolutionId 누락 → **409 TRANSITION_NOT_ALLOWED**(validator reject, B7 seed 전제 — `WorkflowValidatorFailureException`→`IssueTransitionNotAllowedException`→409, 코드 확인: IssueExceptionHandler.kt:37/224. 422 아님), (3) DONE→비DONE 재전이 → resolution_id null, **(4) [Q3] 존재하지 않는 resolutionId → 거부(권고 404 RESOLUTION_NOT_FOUND, 영속 전 검증)**. 단위 mock 아닌 통합으로(메모리 advisory-lock/transaction self-invocation류 — 통합만 표면화).

**GREEN**: `IssueApplicationService.transitionIssue`(현 line 354)와 `availableTransitions`(현 line 303) `issueFields`에 `"resolution" to request.resolutionId?.toString()` 추가 — 이게 EXECUTION phase RequiredField validator의 검사 입력(`plan()` 호출 시점). **[Q3] resolutionId가 non-null이면 plan() 전에 존재성 검증(B3 findById류) → 없으면 거부.** 전이 성공 시 `resolution_id = request.resolutionId` 영속: **`IssueRepository.applyTransition`(현 line 203)에 `resolutionId: UUID?` 파라미터 추가해 같은 낙관락 UPDATE에서 `ISSUES.RESOLUTION_ID` set**(null이면 clear). **B-4 해소 근거**: 전이 영속은 원래부터 raw jOOQ(도메인 우회)가 정본 설계 — resolution 필수 불변식은 Issue Aggregate가 아니라 워크플로우 RequiredField validator가 `plan()`에서 강제하므로 patch-merge-domain-bypass(도메인 검증 우회) 위배 아님. applyTransition은 current_state_key/version/resolution_id를 한 트랜잭션·한 UPDATE로 원자 영속.

**REFACTOR**: resolution 전달/영속 로직 명료화, KDoc 흐름 갱신.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueTransitionResolutionIntegrationTest"` + 모듈 전체 test.

### Task B7. DONE 전이에 RequiredField(resolution) seed — production 워크플로우 YAML 편집 (project-workflow)

> **방식 변경: 마이그레이션(V203) 폐기 → YAML 편집.** FR-WF-03이 YamlSeedService에 validators 시드를 결선했으므로, production YAML의 DONE 대상 전이에 validator 블록을 추가하면 부팅 시 시드된다(`applyIfChanged`/`differsInValidators`가 변경 감지·재시드). 별도 seed 마이그레이션 불필요.

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/workflows/software-default.yaml`, `.../bug-tracking.yaml`, `.../simple.yaml`, `.../kanban-basic.yaml`(각 DONE 대상 전이 존재 시), `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/DoneResolutionValidatorSeedTest.kt`]
- depends-on: [B6]

**RED**: Testcontainers — 표준 워크플로우 시드 후, DONE 카테고리 **대상**(toState category==DONE) 전이에 `RequiredField`(config `{field: resolution}`) validator가 DB에 존재. `plan()`(실행) 시 resolution 없으면 `WorkflowValidatorFailureException`, `availableTransitions`엔 노출(A3 — 이미 구현). 실패(YAML 미편집).

**GREEN**: 각 production YAML에서 **toState가 DONE 카테고리인 전이 전부**에 `validators: [{type: RequiredField, config: {field: resolution}}]` 추가([Q1/Q2 확정=권고A]). 대상 = software-default(`in_review→done`, `done→closed`, `open→closed`), bug-tracking(`in_progress→resolved`, `resolved→closed`), simple(`doing→done`), kanban-basic(`in_progress→done`). type 토큰은 정확히 `RequiredField`(DefaultWorkflowValidatorFactory.kt:59 확인됨). 기존 validators 있는 전이는 배열에 append.

**REFACTOR**: YAML 주석 — FR-IS-07 resolution 강제 근거(ADR 링크).

**검증**: `./gradlew :modules:project-workflow:test --tests "*DoneResolutionValidatorSeedTest"` + 모듈 전체 test(시드 부팅 fail-fast 확인).

### Task B8. 프론트 — resolutions API + useResolutions 훅 + MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/resolutions.ts`, `apps/web/src/hooks/use-resolutions.ts`, `apps/web/src/mocks/handlers/resolution-handlers.ts`, `apps/web/src/api/__tests__/resolutions.test.ts`]
- depends-on: [B4]

**RED**: `resolutions.test.ts` — `fetchResolutions()`가 Zod 스키마(id/key/name/description?/displayOrder/isStandard)로 파싱. Zod 스키마는 백엔드 DTO(B4)와 정합(메모리 frontend-zod-backend-dto-contract-gap). MSW 핸들러는 백엔드 응답 형태 그대로. 실패.

**GREEN**: api + 훅 + MSW 핸들러. Zod UUID는 v4 형식 fixture(메모리 zod-v4-uuid-fixture-strictness).

**REFACTOR**: 쿼리키 컨벤션 정렬.

**검증**: `pnpm --filter @bts/web test resolutions` + `pnpm --filter @bts/web typecheck`

### Task B9. 프론트 — 종료 모달 (toCategory===DONE 트리거 + resolution 드롭다운 + 전이 resolutionId)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/ResolutionModal.tsx`, `apps/web/src/routes/issues.$key.tsx`, `apps/web/src/api/issues.ts`, `apps/web/src/hooks/use-issue-transitions.ts`, `apps/web/src/components/issue/__tests__/ResolutionModal.test.tsx`]
- depends-on: [B8, B11, B12]
- (toCategory는 shared-kernel(A1)이 아니라 REST TransitionItem(B12)에서 읽음 — 프론트는 REST만 소비)

**RED**: `ResolutionModal.test.tsx` — 전이 셀렉터에서 `toCategory === 'DONE'`인 전이 선택 시 모달 표시, resolution 미선택 시 확인 비활성, 선택 후 transitionMutation이 `{toStatusKey, expectedVersion, resolutionId}` 전송. 비DONE 전이는 모달 없이 즉시 전이. 실패.

**GREEN**: ResolutionModal 컴포넌트 + issues.$key.tsx 전이 흐름 연결. useIssueTransitions의 TransitionItem에 toCategory 반영(A1 응답). transitionIssue api에 resolutionId 추가.

**REFACTOR**: 모달 접근성(aria), 셀렉터 strict mode 회피(메모리 playwright-getbyrole-exact / ui-pr-defer-e2e).

**검증**: `pnpm --filter @bts/web test ResolutionModal` + `pnpm --filter @bts/web typecheck` + 기존 issues.$key E2E 동반 실행(메모리 ui-pr-defer-e2e-regression-latent).

### Task B10. E2E — 종료 시 Resolution 필수 흐름 (S1/S2/S4 + 일괄 S5)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-resolution.spec.ts`, `apps/web/src/mocks/handlers/resolution-handlers.ts`, `apps/web/src/mocks/handlers/issue-transition-handlers.ts`, `apps/web/src/mocks/handlers/bulk-operation-handlers.ts`]
- depends-on: [B9, B14]

**RED**: Playwright `issue-resolution.spec.ts` — S1(단건 종료 전이→모달→resolution 선택→성공), S2(미선택 시 확인 비활성/거부), S4(재오픈 시 resolution clear), **S5(일괄 종료 전이→일괄 resolution 드롭다운→선택→전체 성공)**. MSW 핸들러 stateful(메모리 msw-mutation-stateful-refetch — PATCH 결과 영속). 실패.

**GREEN**: E2E + MSW stateful 핸들러. localStorage 토글로 시나리오 분기(메모리 e2e-msw-scenario-toggle). serviceWorker block 금지(메모리 e2e-msw-serviceworker-block).

**REFACTOR**: 셀렉터 컨테이너 한정, fixture UUID v4 형식.

**검증**: `pnpm --filter @bts/web test:e2e issue-resolution` + 전체 E2E 회귀 0. worktree E2E 후 5173 정리(메모리 e2e-orphan-vite).

### Task B11. IssueResponse에 resolution 노출 (C-3 — 신규) (issue-tracking)

> **C-3 신규 task.** 프론트(B9)가 표시할 resolution을 backend 읽기 모델이 안 주면 frontend-zod-backend-dto-contract-gap 재발. B9의 backend 선행.

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`(findByKeyWithType), `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/.../IssueResponseResolutionTest.kt`]
- depends-on: [B1, B3]

**RED**: 단건 조회(`findByKeyWithType`) 결과 IssueResponse가 `resolution: {id,key,name}?`(nullable) 보유 — resolution_id 설정 시 채워지고, null이면 null. 실패(필드 없음).

**GREEN**: IssueResponse에 nullable resolution 요약 DTO 추가. 읽기 모델이 issues.resolution_id로 resolutions JOIN(또는 B3 repository 재사용)해 매핑. 카테시안곱 주의(메모리 cartesian-product-jooq — 단건이라 위험 낮으나 LEFT JOIN 1건).

**REFACTOR**: DTO 매핑 헬퍼 정리.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueResponseResolutionTest"` + 모듈 전체 test.

### ── 일괄 전이 연동 (Q4 — 2026-06-03 추가) ──

### Task B12. TransitionItem(REST)에 toCategory 노출 — 단건/일괄 공유 (issue-tracking)

> **C-1 전파.** 단건(`AvailableTransitionsResponse`)·일괄(`BulkAvailableTransitionsResponse`) 둘 다 `TransitionItem.from(AvailableTransitionView)` 경유(AvailableTransitionsResponse.kt:55) → 한 곳 매핑으로 양쪽 노출. 프론트(B9 모달·B14 일괄)가 toCategory로 DONE 판별.

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/AvailableTransitionsResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTransitionsTest.kt`]
- depends-on: [A1, A4]

**RED**: 단건 `GET /api/v1/issues/{key}/transitions` 응답 TransitionItem에 `toCategory` 포함(DONE 전이는 "DONE"). 실패(필드 없음).

**GREEN**: `TransitionItem`에 `val toCategory: String?` 추가 + `TransitionItem.from`이 `view.toCategory` 매핑. 일괄 응답은 같은 TransitionItem 재사용이라 자동 전파.

**REFACTOR**: KDoc.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueControllerTransitionsTest"` + bulk available 테스트 회귀 0.

### Task B13. 일괄 전이 payload + Applier에 resolutionId 전달 (issue-tracking bulk)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/application/BulkUpdateRequest.kt`(BulkTransitionPayload), `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/application/BulkItemApplier.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/web/*Request*.kt`(일괄 요청 DTO), `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/application/BulkTransitionResolutionTest.kt`]
- depends-on: [B5, B6]

**RED**: Testcontainers/통합 — 일괄 전이 payload에 `resolutionId: UUID?` 보유, `BulkItemApplier`가 `transitionIssue`에 resolutionId 전달. DONE 대상 일괄 + resolutionId → 각 이슈 resolution_id 영속. DONE 대상 일괄 + resolutionId 누락 → 각 항목 실패(409, 기존 부분실패 기록 경로). 실패.

**GREEN**: `BulkTransitionPayload`에 `resolutionId: UUID?` 추가 + 웹 요청 DTO 매핑 + `BulkItemApplier.kt:79` `TransitionIssueRequest(toStateKey, expectedVersion, resolutionId = payload.resolutionId)`. 전체 일괄에 동일 resolution 적용.

**REFACTOR**: KDoc — 일괄 resolution 시맨틱(전체 적용).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*BulkTransitionResolutionTest"` + bulk 기존 테스트 회귀 0.

### Task B14. 프론트 일괄 전이 UI — 대상 DONE 시 resolution 드롭다운 (frontend bulk)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/BulkTransitionDialog.tsx`(또는 기존 일괄 액션 Dialog), `apps/web/src/api/bulk.ts`(또는 일괄 api), `apps/web/src/mocks/handlers/bulk-operation-handlers.ts`, `apps/web/src/components/issue/__tests__/*BulkTransition*.test.tsx`]
- depends-on: [B8, B12, B13]

**RED**: 일괄 전이 Dialog에서 선택 전이의 `toCategory === 'DONE'`이면 resolution 드롭다운 표시 + 미선택 시 확인 비활성, 선택 시 일괄 payload에 resolutionId 포함. 비DONE은 드롭다운 없음. 실패.

**GREEN**: 기존 일괄 전이 Dialog(FR-IS-05 PR #58)에 resolution 드롭다운 조건부 추가 + useResolutions(B8) 재사용 + 일괄 payload resolutionId. MSW bulk 핸들러는 resolutionId 수용.

**REFACTOR**: 단건 모달(B9)과 resolution 드롭다운 컴포넌트 공유 검토(과도 추상화 금지).

**검증**: `pnpm --filter @bts/web test BulkTransition` + typecheck + 기존 일괄 E2E 동반(메모리 ui-pr-defer-e2e-regression-latent).

## 재개 설계 결정 (게이트1 — 2026-06-03 Maxi 확정 ✅)

> **확정 결과.**
> - **Q1/Q2 → 권고(A) 채택.** DONE 카테고리 대상 전이 *전부*에 RequiredField(resolution). `done→closed`·`resolved→closed`(DONE→DONE) 포함. 프론트는 toCategory==DONE 전이 시 모달, **기존 resolution이 있으면 pre-fill**(B9 — 기능 정확성 요건: RequiredField가 값 존재만 검사하므로 재전송 필요).
> - **Q3 → 권고(A) 채택.** B6에서 영속 전 resolutionId 존재성 검증(B3 repository 재사용) → 없으면 거부. 에러코드는 기존 컨벤션 정합(권고 404 RESOLUTION_NOT_FOUND, 구현 시 IssueErrorCodes 확인). E2 테스트로 표면화.
> - **Q4 → 일괄 전이도 포함(2026-06-03 Maxi).** 일괄 전이(FR-IS-05)는 `BulkItemApplier`가 단건과 동일한 `transitionIssue`→`plan()` 경로를 타므로, B7 RequiredField가 일괄 DONE 전이도 막는다(회귀). 일괄 전이 payload에 resolutionId 추가 + 일괄 UI가 대상=DONE이면 resolution 드롭다운(전체 일괄 적용). toCategory는 단건/일괄 공유 DTO `TransitionItem`로 자동 전파(아래 B12). 신규 task B12~B14 + E2E 확장.

> **코드 근거(재검증).** AvailableTransitionView 생성 production 1곳(WorkflowEngine.kt:212), 테스트 7파일 — non-null 필드 추가 시 컴파일 영향 → **A1은 `toCategory: String?` nullable additive**(테스트 생성지점 무변경, 실데이터는 A4가 항상 채움). 단건/일괄 REST는 둘 다 `TransitionItem.from(AvailableTransitionView)` 경유(AvailableTransitionsResponse.kt:55) → B12 한 곳에서 toCategory 매핑하면 양쪽 노출. 일괄 실행=`BulkItemApplier.kt:79` `transitionIssue` 직호출 → B13에서 resolutionId 전달.

옵션 A(워크플로우 게이트) 방향은 확정·승인됨. 재개하며 코드 정합 과정에서 드러난 **좁은 설계 갈림길 2건**을 게이트1에서 확정했다(위 확정 결과).

- **Q1. resolution 필수를 거는 전이 범위.** "DONE 카테고리 상태로 들어가는 모든 전이"가 자연스러운 해석. software-default 기준 = `in_review→done`(완료), `open→closed`(취소), `done→closed`(이미 DONE→DONE). RequiredField는 전이별 무조건 게이트라 fromCategory 조건 분기 불가.
  - **권고(A)**: DONE 대상 전이 전부에 RequiredField. `done→closed`는 프론트 모달이 기존 resolution을 pre-fill해 재확인. 단순·일관.
  - 대안(B): 비DONE→DONE 진입 전이에만(첫 해결 시점). `done→closed`는 resolution 미요구(기존 resolution_id 유지). RequiredField로는 표현 불가 → done→closed 전이에 validator 미부착으로 근사하나, 프론트는 toCategory==DONE이라 여전히 모달 띄움 → 불일치 소지.
- **Q2. `done→closed` 재프롬프트 UX.** 권고(A) 채택 시 이미 해결된 이슈를 closed로 옮길 때 모달이 다시 뜸. pre-fill로 마찰 최소화하되, "이미 resolution 있으면 모달 skip하고 그대로 전송"도 가능. → Q1과 묶어 결정. **(주의: 재리뷰 B-NEW-3 — RequiredField는 resolution *값 존재*만 검사하므로 done→closed 시 프론트가 기존 resolution_id를 다시 안 보내면 거부됨. B9 pre-fill은 UX가 아니라 기능 정확성 요건.)**
- **Q3. 위조 resolutionId 존재성 검증 (재리뷰 B-NEW-4).** `RequiredFieldValidator`는 null/blank만 검사 — 존재하지 않는 UUID도 통과시킨다. 존재하지 않는 resolution_id가 그대로 영속될 위험.
  - **권고(A)**: B6에서 영속 전 issue-tracking이 resolutionId 존재성 확인(B3 repository 재사용) → 없으면 400/404 거부. 책임을 B6에 명시 task로 추가. 단순·단일 BC.
  - 대안(B): 검증 생략(프론트가 GET /resolutions 목록에서만 고르므로 위조는 악의적 직접 호출뿐). 범위 최소화하나 데이터 무결성 약함.
  - → 채택 시 spec E2(현재 "404/422")를 확정 코드로 정합.

(범위 밖이라 변경 안 하는 것: 커스텀 Resolution CRUD 제외=결정2 유지, toCategory additive 노출=결정1 유지, 재오픈 clear=결정4 유지.)

## Plan 메타

- **task 수(재개+Q4 갱신): 16 활성** (PR-A 2 = A1·A4 [A2·A3 완료 제거] + PR-B 14 = B1~B14). 원래 14 → A2/A3 제거 + B11(C-3) + B12~B14(Q4 일괄) 추가.
- PR 전략: 2 PR (PR-A 인프라 선행 → PR-B 기능). PR-B의 A* depends-on은 PR-A 머지 선행.
- 모듈 분포: shared-kernel(A1) · project-workflow(A4 · B7 YAML) · issue-tracking(B1~B6, B11, B12, B13) · frontend(B8/B9/B14) · E2E(B10)
- 예상 wave (PR-A): wave1=[A1] → wave2=[A4]. (A2/A3 FR-WF-03 완료). shared-kernel(A1)→project-workflow(A4) 모듈 경계.
- 예상 wave (PR-B): wave1=[B1, B2, B5] → wave2=[B3] → wave3=[B4, B6, B11] → wave4=[B7, B8, B12, B13] → wave5=[B9, B14] → wave6=[B10]. (issue-tracking 다수 task는 같은 모듈 test 컴파일 공유로 실제론 더 직렬화될 수 있음 — 메모리 bts-plan-wave-gradle-module-compile)
- TDD 강제: yes (모든 task test→feat 순서, controller가 git log 검증 — 메모리 subagent-ktlint-false-green / parallel-dispatch-precommit-hook-race)
- 추가 검증: ktlintMain+TestSourceSetCheck + detekt(4모듈) + typecheck(tsconfig.app) + vitest + playwright

## 리뷰 결과

### code-reviewer 적대적 리뷰 (2026-06-03) — 🛑 BLOCKER, ADR 무효화

**검증 방식**: plan/spec/ADR 주장된 코드 경로를 직접 grep/read.

**BLOCKER (진행 불가, 직접 재검증 완료)**.
- **B-1. validator 프레임워크가 production에 결선돼 있지 않음.** `WorkflowEngine`(@Service)이 요구하는 `WorkflowValidatorFactory`/`WorkflowDefinitionRepository` 구현체가 main에 **0개**. 유일 구현은 테스트 익명 object(`IssueTransitionGuardFilterIntegrationTest.kt:212,257`). 옵션 A의 "기존 프레임워크 재사용" 전제 붕괴.
- **B-2. validator는 YAML로 시드 불가.** `YamlSeedService`는 workflows/states/transitions 3테이블만 시드, validator 언급 0건. `Workflow` aggregate에 validator 필드 없음. `workflow_validators` 테이블(V200)은 정의만 있고 런타임에 아무도 안 읽음. B7 seed INSERT는 무력.
- **B-3. 핵심 FR(종료 시 resolution 필수 강제)이 plan 전체 구현해도 동작 안 함** (B-1+B-2 귀결).
- **B-4. 전이 영속이 도메인 우회(raw jOOQ UPDATE, IssueRepository.applyTransition:209).** 결정4/FR5의 "도메인 경유" 문구와 모순. resolution_id 영속 경로 재설계 필요.

**CONCERN**.
- C-1. bulk 교집합 응답 toCategory 전파 task 누락(스펙 결정1은 동반 명시).
- C-3. **IssueResponse에 resolution 노출 task 누락** — 프론트(B9)가 표시할 필드를 backend가 안 줌(frontend-zod-backend-dto-contract-gap 재발 위험).
- C-5. project-workflow 마이그레이션은 V010이 아니라 **V203**(별도 시퀀스, V200~V202 존재).

**OK (검증 통과)**: issue-tracking V010 + init_codegen 미러 정확 / toCategory string 노출 BC격리 타당 / transport DTO `toStatusKey` 필드명 정확 / issue-tracking→project-workflow는 이미 의존 존재(포트 경유라 추가 위반 없음).

**리뷰어 권고**: ADR이 옵션 B를 기각한 사유("프레임워크 재사용")가 사실은 존재하지 않는 프레임워크였음 → **옵션 B(issue-tracking 하드코딩 가드) 재검토 권장**. toCategory를 이미 노출하므로 issue-tracking이 category==DONE && resolutionId==null → 거부하면 단일 BC로 닫힘.

**→ 게이트 1 진입 불가. Maxi 방향 결정 필요(아래 D5).**

### Maxi 방향 결정 (2026-06-03) — 선행 FR 먼저

옵션 B(issue-tracking 하드코딩 가드)로 우회하지 않고, **선행으로 "워크플로우 validator 런타임 결선" project-workflow FR을 먼저 완성**한다. 그 FR이 끝나면 FR-IS-07은 옵션 A(이 plan의 A1/A4 toCategory + DONE seed RequiredField + issueFields resolution 전달)로 재개하며, 그 시점엔 B-1/B-2/B-3가 해소돼 사소해진다.

**FR-IS-07 현재 상태: 보류.** worktree `.worktrees/fr-is-07-resolution-resolutions-e2e` + draft PR #62 유지. 재개 조건 = 선행 FR(validator 런타임 결선) 머지. 재개 시 본 plan을 옵션 A 기준으로 갱신(C-3 IssueResponse resolution 노출, B-4 영속 경로, C-5 V203 반영).

### 보류 해제 + plan 재갱신 (2026-06-03, 본 세션)

선행 FR-WF-03(PR #66) 머지 확인 → 보류 해제. main 머지 후 코드 직접 재검증으로 BLOCKER 전수 해소 확인.

- **B-1/B-2/B-3 해소.** DefaultWorkflowValidatorFactory·DefaultWorkflowDefinitionRepository @Component 결선 + YamlSeedService validators 시드(dry-run fail-fast) + applyIfChanged validator 변경 감지. (상단 "재개 갱신" 섹션 근거)
- **B-4 해소안 확정.** 전이 영속은 raw jOOQ applyTransition이 정본 — resolution 불변식은 워크플로우 RequiredField validator(`plan()`, EXECUTION phase)가 강제. applyTransition에 resolutionId 파라미터 추가(B6 갱신).
- **C-3 → B11 task 신설.** IssueResponse.resolution 노출.
- **C-5 정정 무효화.** V203 seed 마이그레이션 자체가 폐기(B7 YAML 편집으로 대체) — project-workflow 마이그레이션 추가 없음.
- **신규 전제.** B1 V010→V011(versions 선점). A2/A3 완료로 PR-A 2 task로 축소.
- **남은 게이트1 확인.** 재개 설계 결정 Q1(필수 전이 범위)/Q2(done→closed 재프롬프트)만 Maxi 확정 후 구현 착수.
