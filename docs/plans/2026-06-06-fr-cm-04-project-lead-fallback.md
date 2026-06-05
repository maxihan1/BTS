# FR-CM-04 — 컴포넌트 리드 부재 시 프로젝트 리드 기본 담당자 폴백

> slug: fr-cm-04-project-lead-fallback
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking

## Brief

FR-CM-03 후속. 이슈 생성 시 컴포넌트 리드를 기본 담당자로 자동 할당하되,
리드가 없을 때 프로젝트 리드로 폴백한다. admin ≠ lead.
프로젝트 리드는 issue-tracking 소유 `projects.lead_user_id` 컬럼으로 표현(옵션 B, in-BC) —
도메인 단계에서 cross-BC 포트 대신 `components.lead_user_id` 동형 모델로 확정.

## 도메인 정리

- **BC**: issue-tracking (in-BC — 옵션 B 결정으로 cross-BC 포트 불필요)
- **영향 엔티티**:
  - `Project` / `projects` 테이블 — `lead_user_id UUID NULL` 컬럼 신설 (FK 미적용, BC 격리)
  - `DefaultAssigneeResolver` — 폴백 체인 확장 (컴포넌트 리드 → 프로젝트 리드 → 미할당)
  - `IssueApplicationService` — 프로젝트 리드 조회 후 resolver에 주입 (오케스트레이션)
- **새 용어**: "프로젝트 리드" (Project Lead) — 프로젝트 단위 **단일** 업무 책임자(자동배정 2순위 대상).
  `PROJECT_ADMIN`(다수·권한)과 구분 (admin ≠ lead). glossary 추가 후보 (Maxi 승인 대기).
- **모델 결정 (D2)**: 옵션 B — `projects.lead_user_id` 컬럼. `components.lead_user_id`(FR-CM-03) 동형.
  옵션 A(project_memberships PROJECT_LEAD 역할) 기각 — cross-BC 포트 + UNIQUE 제약 비용.
- **범위 결정**: 폴백 로직 + 프로젝트 리드 지정/해제 API 포함. 지정 UI는 후속 FR 분리.
- **폴백 체인**: 컴포넌트 리드(1순위, FR-CM-03) → 프로젝트 리드(2순위, FR-CM-04) → 미할당.
  `current != null`이면 덮어쓰지 않음(FR-CM-03 S3 규칙 유지).
- **기존 결정 충돌**: 없음. components.lead_user_id 패턴 재사용.
- **명세 deviation**: product §3.1.4 D2/D4의 cross-BC 포트 가정 → 옵션 B로 in-BC. 같은 PR에서 동기화.
- **관련 ADR**: [docs/adr/2026-06-06-project-lead-default-assignee-fallback.md](../adr/2026-06-06-project-lead-default-assignee-fallback.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-06-fr-cm-04-project-lead-fallback.md](../specs/2026-06-06-fr-cm-04-project-lead-fallback.md)

핵심 시나리오 요약.
- 폴백 체인: 컴포넌트 리드(1순위) → 프로젝트 리드(2순위, `projects.lead_user_id`) → 미할당
- 컴포넌트 없는 이슈(`componentIds.isEmpty()`)도 프로젝트 리드 폴백 적용 (resolveDefaultAssignee 수정)
- `current != null`이면 폴백 미적용 + 클론은 폴백 제외 (FR-CM-03 일관, 회귀 0)
- 프로젝트 리드 지정/해제 API: `PATCH /api/v1/projects/{idOrKey}/lead` 2-state (컴포넌트 리드 동형)
- 리드 실존 검증은 지정 시점(422), 자동배정은 저장값 신뢰

## Brainstorming Check

✅ 통과 (1회 iteration). gap 2건 보강 — 클론 폴백 제외 명시(EC6) + 확인 수단(EC7).
권한 범위(지정 주체)는 plan security-engineer 검토로 이관.

## Plan

> 공통 — agent 미기재 task는 헤더 `agent: backend-engineer` 기본값. 모든 경로는
> `backend/modules/issue-tracking/src/{main,test}/kotlin/com/bts/issue/...` 기준.
> Gradle 검증 경로는 `:modules:issue-tracking`(메모리 subagent-ktlint-false-green).

### Task 1. projects.lead_user_id 컬럼 마이그레이션 + init_codegen 미러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V0XX__project_lead.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/ProjectLeadColumnMigrationTest.kt`]
- depends-on: []

**RED**: Testcontainers 통합 테스트 — 마이그레이션 적용 후 `projects.lead_user_id` 컬럼이
존재하고 nullable인지 `information_schema.columns` 조회로 단언. 컬럼 없으므로 실패.

**GREEN**:
- `V0XX__project_lead.sql` — `ALTER TABLE projects ADD COLUMN lead_user_id UUID NULL;` +
  `COMMENT`(identity-access users.id 대응, FK 미적용 — BC 격리, components.lead_user_id 동형 문구).
- `init_codegen.sql`의 `projects` 정의에 같은 컬럼 미러(jOOQ 상수 생성 — 메모리 jooq-init-codegen-mirror).
- **V번호는 머지 직전 fetch+ls로 재확인**(메모리 migration-vnumber-concurrent-branch-collision).

**REFACTOR**: COMMENT 문구를 컴포넌트 리드 컬럼과 표현 통일.

**검증**: `./gradlew :modules:issue-tracking:generateJooq :modules:issue-tracking:compileKotlin`
(PROJECTS.LEAD_USER_ID 상수 생성 확인) + 마이그레이션 테스트.

### Task 2. DefaultAssigneeResolver 폴백 파라미터 확장 (순수 함수, 독립)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/DefaultAssigneeResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/DefaultAssigneeResolverTest.kt`]
- depends-on: []

**RED**: `resolve(current, candidates, projectLeadUserId)` 신규 시그니처 단위 테스트.
- S1: 컴포넌트 리드 있음 + 프로젝트 리드 있음 → 컴포넌트 리드(1순위).
- S2: 컴포넌트 리드 없음 + 프로젝트 리드 있음 → 프로젝트 리드.
- S4: 둘 다 없음 → null.
- S5: `current != null` → current(폴백 미적용).
- EC1: 프로젝트 리드 == 컴포넌트 리드 동일인 → 컴포넌트 리드 경로(동일 결과).

**GREEN**: `projectLeadUserId: UUID? = null` **기본값 파라미터** 추가(기존 호출 호환).
컴포넌트 리드 결정이 null이면 `projectLeadUserId?.let { ActorId(it) }` 폴백.

**REFACTOR**: KDoc에 폴백 체인(컴포넌트 → 프로젝트 → 미할당) 명시 + ADR 링크.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*DefaultAssigneeResolverTest"`.

### Task 3. ProjectLeadRepository(find+update) + ProjectLeadApplicationService(지정/해제, 422 guard)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/repository/ProjectLeadRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/application/ProjectLeadApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/domain/ProjectLeadExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/application/ProjectLeadApplicationServiceTest.kt`]
- depends-on: [1]

**RED**: Testcontainers 통합 + `UserLookupPort` mock.
- S8: 유효 user 지정 → `projects.lead_user_id` 저장, 조회 일치.
- 422: 미존재 user 지정 → `ProjectLeadNotFoundException`(UserLookupPort.exists=false).
- S9: `leadUserId=null` 해제 → 컬럼 null.
- 404: 미존재/소프트삭제 프로젝트 → `ProjectLeadProjectNotFoundException`(신규, B3).
- `findProjectLead(projectId)` 조회 — 지정/미지정 반환.

**GREEN**:
- `ProjectLeadRepository` — `findLeadUserId(projectId): UUID?`, `updateLead(projectId, leadUserId?)` (jOOQ, `@Transactional`).
  활성 프로젝트만(`deleted_at IS NULL`). repository 레이어만 jOOQ 접촉(ArchUnit 룰2).
  **패키지는 `com.bts.issue.project.repository` 유지**(끝 `.repository` — ArchUnit 룰2, 메모리 archunit-shared-class-move-repository-package).
  KDoc로 `ProjectLookupRepository`(읽기 전용 id 해석)와 책임 분리 명시(둘 다 projects 접촉, C4).
- `ProjectLeadApplicationService` — `changeLead(actorId, projectIdOrKey, leadUserId)`.
  `ProjectLookup.resolve`는 `UUID?`만 반환(throw 안 함, `ProjectLookup.kt:33`) → **서비스가 null이면 직접 `throw ProjectLeadProjectNotFoundException`**
  (ComponentApplicationService.kt:259-260의 `?: throw ComponentProjectNotFoundException` 선례 동형) →
  `validateLead`(leadUserId non-null이면 `UserLookupPort.exists`, false면 422 `ProjectLeadNotFoundException`) → `updateLead`.
- **`ProjectLeadExceptions.kt`에 두 예외 신규 정의** (B3) — `ProjectLeadNotFoundException`(422, `ComponentLeadNotFoundException` 동형) +
  `ProjectLeadProjectNotFoundException`(404). 기존 generic `ProjectNotFoundException`은 **존재하지 않음**(슬라이스별 `ComponentProjectNotFoundException`/`VersionProjectNotFoundException`/`IssueProjectNotFoundException`로 분리) — 재사용 금지.

**REFACTOR**: KDoc + 컴포넌트 리드 동형 패턴 주석.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*ProjectLeadApplicationServiceTest"`.

### Task 4. 프로젝트 리드 지정/해제 REST API (Controller + DTO)

**메타**.
- agent: `backend-engineer` (권한 가드 범위는 security-engineer 검토 — 아래 메모)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/web/ProjectLeadController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/web/ProjectLeadExceptionHandler.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/web/dto/ChangeProjectLeadRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/web/dto/ProjectLeadResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/web/ProjectLeadControllerTest.kt`]
- depends-on: [3]

**RED**: MVC/통합 테스트. **status 단언을 먼저 틀리게 넣어 핸들러 결선 검증**(메모리 archunit-vacuous-rule 정신).
- S8: `PATCH /api/v1/projects/{idOrKey}/lead {leadUserId}` → 200 + `{ projectId, leadUserId }`.
- S9: `{leadUserId: null}` → 200, 해제.
- 404: 미존재 프로젝트(→ 500 아님을 명시 단언). 422: 미존재 user(→ 500 아님). EC5: 잘못된 UUID body → 400.

**GREEN**:
- `ProjectLeadController`(`com.bts.issue.project.web`) — `@PatchMapping("/lead")` → `service.changeLead` 위임.
  `ChangeProjectLeadRequest{leadUserId: UUID?}`, `ProjectLeadResponse`. `ComponentController.changeLead`(placeholder actor) 동형.
- **`ProjectLeadExceptionHandler` 신규 생성** (B2) — `@RestControllerAdvice(basePackages=["com.bts.issue.project.web"])`.
  이 모듈의 advice는 **패키지별로 분리**돼 있어(ComponentExceptionHandler=component.web, IssueExceptionHandler=adapter.inbound.rest 등)
  `project.web`을 잡는 핸들러가 **없으면 404/422가 조용히 500으로 샌다**. `ComponentExceptionHandler` 템플릿으로
  `ProjectLeadProjectNotFoundException`→404 · `ProjectLeadNotFoundException`→422 · 검증오류→400 매핑 + 에러코드 상수.

**REFACTOR**: KDoc.

**보안 메모(security-engineer 검토)**: actorId는 현행 `ComponentController` 동형 placeholder(`SYSTEM_ACTOR_UUID`).
"누가 프로젝트 리드를 지정할 수 있는가"(PROJECT_ADMIN/시스템 admin) 권한 가드는 컴포넌트 리드 지정과
동일 수준 — 이번 PR은 컴포넌트 리드 지정과 동형으로 맞추고, 권한 강화는 FR-PM 권한 결선 시점에 일괄.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*ProjectLeadControllerTest"`.

### Task 5. IssueApplicationService.resolveDefaultAssignee 폴백 주입 + 통합 테스트

**메타**.
- agent: `backend-engineer`
- files (main): [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`]
- files (생성자 주입으로 깨지는 기존 테스트 22개 — B1, 메모리 plan-files-constructor-injection-existing-tests. `mockk(relaxed=true)` 또는 `findLeadUserId`=null stub로 회귀0):
  `src/test/kotlin/com/bts/issue/` 하위 —
  `repository/IssueComponentsRepositoryTest.kt`, `bulk/integration/BulkOperationIntegrationTest.kt`,
  `bulk/application/BulkTransitionResolutionTest.kt`, `integration/IssueComponentsIntegrationTest.kt`,
  `integration/IssueTransitionValidatorEndToEndIntegrationTest.kt`, `integration/IssueTransitionGuardFilterIntegrationTest.kt`,
  `component/repository/ComponentRepositoryTest.kt`, `adapter/inbound/rest/IssueControllerTransitionIntegrationTest.kt`,
  `application/IssueApplicationServiceSoftDeleteTest.kt`, `application/IssueApplicationServicePermissionTest.kt`,
  `application/IssueApplicationServiceTest.kt`, `application/IssueTransitionResolutionIntegrationTest.kt`,
  `application/IssueChangeComponentsAutoAssignIntegrationTest.kt`, `application/IssueApplicationServiceUpdateTest.kt`,
  `application/IssueApplicationServiceTransitionTest.kt`, `application/IssueApplicationServiceCreateTest.kt`,
  `application/IssueApplicationServiceListTest.kt`, `application/IssueApplicationServiceCloneTest.kt`,
  `application/IssueApplicationServiceFindTest.kt`, `application/IssueCreateComponentAutoAssignIntegrationTest.kt`,
  `application/IssueApplicationServiceAvailableTransitionsTest.kt`, `application/IssueChangeComponentsServiceTest.kt`
  (impl 착수 시 `grep -rln "IssueApplicationService(" src/test`로 목록 재확인 — 위는 리뷰 시점 ground-truth)
- 신규 폴백 통합 단언이 들어갈 테스트: `application/IssueCreateComponentAutoAssignIntegrationTest.kt`(생성 S2/S3/S4·실재 파일명) +
  `application/IssueChangeComponentsAutoAssignIntegrationTest.kt`(변경 S7) + `application/IssueApplicationServiceCloneTest.kt`(EC6 회귀)
- depends-on: [1, 2, 3]

**RED**: Testcontainers 통합.
- S2: 컴포넌트 리드 없음 + 프로젝트 리드 지정 → 생성 시 프로젝트 리드 배정.
- S3: 컴포넌트 없는 이슈 + 프로젝트 리드 → 프로젝트 리드(`componentIds.isEmpty()` 경로).
- S4: 둘 다 없음 → 미할당.
- S7: `changeComponents`로 리드 없는 컴포넌트 교체 + 프로젝트 리드 → 재배정.
- EC6: 클론은 폴백 미적용(회귀) — 기존 클론 테스트 그린 유지.

**GREEN**: `resolveDefaultAssignee(projectId, componentIds, current)`에서
`projectLeadRepository.findLeadUserId(projectId)` 조회 후 `DefaultAssigneeResolver.resolve(null, candidates, projectLead)` 호출.
`if (componentIds.isEmpty()) return null` 가지를 제거하고 빈 candidates로도 폴백이 작동하게 수정.
(단 `current != null`이면 조회 전에 early-return 유지 — 불필요 쿼리 회피 + 덮어쓰기 금지.)

**REFACTOR**: KDoc 6~8단계 설명에 프로젝트 리드 폴백 반영.

**검증**: `./gradlew :modules:issue-tracking:test` (모듈 전체 — FR-CM-03 회귀 0 확인).

## Plan 메타

- task 수: 5
- 예상 wave: 3 (wave0: T1·T2 병렬 / wave1: T3 / wave2: T4·T5 병렬)
- TDD 강제: yes (test 커밋 → feat 커밋 순서 자동 검증)
- 명세 동기화(머지 전): product §3.1.4 D1~D5 체크 + cross-BC 가정 제거(옵션 B), fr-index/README/CLAUDE
  카운트 영향 0(FR 추가 아님, 기존 스텁 구현) — `bash scripts/verify-master-plan.sh` 통과 필수.
- 추가 검증: ktlintMain+TestSourceSetCheck + detekt + 모듈 전체 test (메모리 subagent-ktlint-false-green).

## 리뷰 결과

### code-reviewer ground-truth plan 리뷰 (2026-06-06)

backend FR → autoplan 대신 code-reviewer 집중 dispatch(메모리 bts-review-plan-autoplan-overkill).
실제 코드 22개 호출처·핸들러·예외 슬라이스 대조.

**BLOCKER 3건 — 모두 plan 반영 완료**.
- **B1**. 생성자에 `ProjectLeadRepository` 주입 → `IssueApplicationService` 생성 테스트 **22개** 컴파일 깨짐.
  plan은 1개만(+ 환각 파일명 `IssueCreateIntegrationTest.kt`) 명시했음 → T5 files에 22개 전수 열거 + 실재
  파일명(`IssueCreateComponentAutoAssignIntegrationTest.kt`)으로 정정. ✅
- **B2**. `ProjectLeadController`가 `project.web` 패키지인데 이 모듈 `@RestControllerAdvice`는 패키지별 분리 →
  담당 핸들러 부재로 404/422가 조용히 500. → T4에 `ProjectLeadExceptionHandler`(basePackages=project.web) 신규 추가. ✅
- **B3**. 재사용하려던 generic `ProjectNotFoundException` 미존재(슬라이스별 다른 이름) + `ProjectLookup.resolve`는
  null만 반환(throw 안 함) → T3에 `ProjectLeadProjectNotFoundException`(404) 신규 정의 + 서비스가 직접 throw 명시. ✅

**CONCERN 반영**.
- C2. V013 현재 안전하나 동시 진행 `auth/fr-pm-06`(issue-tracking 접촉)이 먼저 V013 소비 시 충돌 → 머지 직전 재확인 유지.
- C3. `current != null` early-return을 프로젝트 리드 조회 **전에** 유지(불필요 쿼리+덮어쓰기 방지) — plan 명시됨.
- C4. `ProjectLeadRepository`는 `project.repository` 패키지 유지 + KDoc 책임 분리 → T3 반영. ✅

**✅ ground-truth 확인됨**.
- T2 기본값 파라미터 안전(프로덕션 `resolve` 직접 호출 단 1곳, named-arg).
- EC6 클론 회귀0(클론 경로는 `resolveDefaultAssignee` 미경유, 코드상 폴백 샐 경로 없음).
- `UserLookupPort.exists(UUID): Boolean` 실재, 컴포넌트 리드 422 guard 동형 차용 가능.
- `projects` 컬럼 카운트 가드 부재 → 새 컬럼이 기존 마이그레이션 테스트 안 깸.

**BLOCKER**: 없음 (3건 모두 plan 수정으로 해소).
