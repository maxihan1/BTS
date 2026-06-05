# FR-PM-04 — 워크플로우/자동화 관리 권한

> slug: fr-pm-04-workflow-automation
> type: auth
> agent: security-engineer
> 생성: 2026-06-03

## Brief

FR-PM-04 워크플로우/자동화 관리 권한 (identity-access §4.4). 선행 §4.2(FR-PM-02) 완료.
워크플로우/자동화 관리 동작에 대한 권한 가드 추가. FR-PM-02/03과 동형 구조 (MANAGE_* 권한코드 + prod resolver + 프론트 게이팅 + E2E).

## 도메인 정리

- **BC**: identity-access (권한 prod 판정 소관) + shared-kernel (포트 이동 대상)
- **권한 코드 (SDD 12.3 정본, 신규 생성 아님)**:
  - `MANAGE_WORKFLOW` — 워크플로우(스킴) 편집. **이번 범위**.
  - `MANAGE_AUTOMATION` — 자동화 규칙 관리. **이번 범위 제외** (automation BC 부재).
- **가드 대상 (실재 검증 완료)**:
  - `WorkflowSchemeController` (`/api/v1/workflow-schemes`) — create/update/delete + mapping CRUD. 이미 `WorkflowSchemePermissionResolver.requirePermission(...)` 결선됨 (Guard 패턴). non-prod는 `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile !prod`)가 통과. **prod 구현만 부재** → FR-PM-04가 채움.
  - 자동화: automation BC(FR-AT-01~07) 미구현 → 가드 대상 0.

### 결정 (Maxi 2026-06-03)

- **D1 범위**: 워크플로우 관리 권한(MANAGE_WORKFLOW)만 완결. 자동화 관리 권한은 automation BC(FR-AT) 착수 시 동반. `docs/plan/product/automation.md §0` 진입조건 문구를 "FR-PM-04 워크플로우 부분 완료"로 조정.
  - **근거**: automation BC 부재 → MANAGE_AUTOMATION 시드는 소비처 0인 dead 시드 (메모리 `no-cross-bc-deployment-assembly`). FR-PM-03 선례("기능 → 권한" 순서)와 일관.
- **D2 포트 이동**: `WorkflowSchemePermissionResolver` 포트 + `WorkflowSchemePermission` enum + `WorkflowSchemeScope`를 project-workflow → **shared-kernel**로 이동. actorId는 UUID로 단순화(BC 공통 분모). identity-access가 prod resolver(`@Profile prod`) 구현.
  - **근거**: identity-access는 project-workflow를 의존 불가(BC 격리 ArchUnit 룰). FR-PM-03 컴포넌트/버전 포트가 shared-kernel에 있던 선례 그대로.
  - **주의**: 이동 시 참조처(project-workflow service/adapter/test) 전수 grep 수정 필요 (메모리 `archunit-shared-class-move-repository-package`).
- **D3 전역(Global) scope 판정 모델**: **spec 단계에서 정밀 설계** (Maxi 결정). 워크플로우 스킴 생성/수정/삭제는 `WorkflowSchemeScope.Global`이나 현재 `role_permissions` 매트릭스는 프로젝트 단위 → 전역 자원 판정 모델 미정의. SDD 12.6 시스템 역할(OrgAdmin 등)·기존 테이블 근거로 spec에서 확정.

- **관련 ADR**: docs/decisions/2026-06-04-workflow-scheme-permission-prod-resolver.md (생성)
- **선례 ADR**: 2026-06-03-version-component-permission-prod-resolver (FR-PM-03 동형) · 2026-05-22-issue-permission-resolver-port
- **기존 결정 충돌**: 없음. WorkflowSchemePermissionResolver KDoc이 FR-PM-04를 명시적으로 예약함.

## 스펙 — ✅ 재개 완료 (FR-PM-08 인프라로 보류 해소, 2026-06-05)

전체 스펙. [docs/specs/2026-06-05-fr-pm-04-workflow-automation.md](../specs/2026-06-05-fr-pm-04-workflow-automation.md)

핵심 3줄 요약.
- 워크플로우 스킴 CRUD(`MANAGE_SCHEME`/Global)는 시스템 관리자 전용 → FR-PM-08 `SystemPermissionResolver.isSystemAdmin` 소비.
- 프로젝트 스킴 배정(`ASSIGN_SCHEME`/Project)은 프로젝트 관리자 → 멤버십+`role_permissions`의 `MANAGE_WORKFLOW`(Maxi 2026-06-05 확정), V013 시드 신규.
- 포트(+enum+scope) project-workflow→shared-kernel 이동(actor→UUID), Guard 예외는 shared-kernel 배치(BC 가로지름), identity-access `@Profile(prod)` 구현. non-prod AlwaysAllow 유지.

### D3 해소 (전역 scope 판정 모델 — 보류 당시 미정)
FR-PM-08이 `system_role_assignments` + `SystemRole.SYSTEM_ADMIN` + `SystemPermissionResolver.isSystemAdmin`를 제공 → Global scope는 isSystemAdmin로 판정. D3는 이로써 확정.

## Brainstorming Check

✅ 통과 (1회). 갭2건(actor 시그니처 변경 / Guard 예외 BC 가로지름) 보강 — 스펙 FR-4·FR-7·EC7 참조.

---

## (구) 스펙 보류 기록 — 전역 admin FR 선행 필요 (Maxi 2026-06-04, FR-PM-08로 해소됨)

### 보류 사유 — spec 단계에서 발견한 구조적 공백

워크플로우 스킴 생성/수정/삭제(`MANAGE_SCHEME`)는 `WorkflowSchemeScope.Global`(시스템 전역) 권한이다.
그런데 조사 결과 **전역(시스템/조직) 관리자 역할을 판정할 데이터가 시스템에 전혀 없다**.

- 현재 권한 모델은 전부 프로젝트 단위 — `ProjectRole(PROJECT_ADMIN, MEMBER)`, `role_permissions(scheme_id, role, permission_code)`는 프로젝트 스킴에 종속.
- users 테이블에 전역 역할 컬럼 없음. 별도 시스템 역할 테이블 없음. JWT 토큰에 역할 클레임 없음(userId만).
- SDD 12.3 시스템 권한(`ADMIN_SYSTEM`/`MANAGE_USERS`) + 12.6 `OrgAdmin` 역할은 **문서로만** 존재, 구현 FR 부재.
- 과거 FR-AU-05/FR-PM-01 노트가 "회원가입=전역 admin(FR-PM-01) 선행"을 기대했으나 FR-PM-01은 프로젝트 단위로만 구현됨 → 전역 admin은 미구현 공백.

### 결정 (Maxi 2026-06-04)

**Jira Cloud 모델**(워크플로우 스킴 = 사이트/전역 관리자 관리)을 따르기로 함.
이를 위해 **"시스템/조직 관리자 역할 + 전역 권한" 신규 FR을 먼저 정의·구현**하고, FR-PM-04는 그 위에서 재개한다.

신규 선행 FR이 갖춰야 할 것:
1. 사용자 전역 역할 저장 (users.system_role 또는 system_role_assignments — SDD 12.6 OrgAdmin 구현)
2. JWT 클레임에 전역 역할/권한 추가
3. 시스템 권한코드(`ADMIN_SYSTEM`/전역 `MANAGE_WORKFLOW` 등) 판정 인프라
4. 최초 시스템 관리자 부트스트랩

→ FR-PM-04 worktree/draft PR #73은 유지. 선행 FR 완료 후 본 spec 재개.
도메인 정리 + ADR(D1/D2 포트 이동, 범위 결정)은 유효하게 보존됨.

## (이하 보류 — 선행 FR 완료 후 재개) 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan

> 모든 task agent: `security-engineer` (auth FR). 검증 경로: `./gradlew :modules:<module>:test`.
> TDD red→green→refactor 강제. 포트 이동(T1)이 3모듈 컴파일 가로지름 → 사실상 직렬 선행.

### Task 1. 포트 계약을 shared-kernel로 이동 (actor→UUID) + 예외 정의 + 참조 전수 갱신

**메타**.
- agent: `security-engineer`
- files: [
  `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/WorkflowSchemePermissionResolver.kt`,
  `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/WorkflowSchemePermission.kt`,
  `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/WorkflowSchemeScope.kt`,
  `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/WorkflowSchemeAccessDeniedException.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/port/outbound/WorkflowSchemePermissionResolver.kt` (삭제),
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/port/outbound/WorkflowSchemePermission.kt` (삭제),
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/port/outbound/WorkflowSchemeScope.kt` (삭제),
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/outbound/AlwaysAllowWorkflowSchemePermissionResolver.kt` (import+actor 타입 갱신),
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeController.kt` (호출부 actor→UUID, 4곳),
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/ProjectWorkflowSchemeController.kt` (2곳),
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/application/WorkflowSchemeApplicationService.kt` (호출부),
  project-workflow 측 기존 테스트(WorkflowSchemeControllerTest / ProjectWorkflowSchemeControllerTest / AlwaysAllow…Test / ApplicationServiceTest — import+actor 갱신),
  shared-kernel ArchUnit 룰 테스트(있으면)
  ]
- depends-on: []

**RED**:
- shared-kernel 신규 테스트 `WorkflowSchemePermissionContractTest` — 포트/enum/scope/예외가 `com.bts.shared.permission`에 존재하고 `requirePermission(actorId: UUID, …)` 시그니처임을 단언. (이동 전 → 컴파일 실패)
- 기존 project-workflow scheme 테스트는 import 미갱신 상태에서 RED.

**GREEN**:
- 4개 계약 타입을 shared-kernel로 생성(이동), 시그니처 `actor: ActorId` → `actorId: UUID`.
- `WorkflowSchemeAccessDeniedException`(Guard 예외, errorCode 상수 보유) shared-kernel 정의.
- project-workflow 원본 3파일 삭제, AlwaysAllow stub import+actor 타입 갱신.
- 호출부 **13곳** `requirePermission(actor, …)` → `requirePermission(UUID.fromString(actor.raw), …)` (permission/scope 무변경). 내역: `WorkflowSchemeController` 5곳(`:79,139,166,188,222`) + `ProjectWorkflowSchemeController` 2곳(`:83,107`) + `WorkflowSchemeApplicationService` 6곳(`:96,170,208,268,335,369`). (W1: spec의 "8곳"은 카운트 오류 — 실제 13곳.)
- 기존 테스트 import/actor 인자 갱신. **특히 `ProjectWorkflowSchemeControllerTest`의 포트 직접 구현 2곳** — `CapturingPermissionResolverStub`(`:64`, override `:69`) + 익명 `object`(`:98`, override `:99`) — 의 `requirePermission(actor: ActorId…)` override를 `actorId: UUID`로 갱신(B3). `WorkflowSchemeControllerTest`는 `mockk(relaxed=true)`라 자동 적응.
- **AlwaysAllow stub은 project-workflow 패키지에 그대로 둠**(이동 안 함) → issue-tracking 6개 테스트의 import 경로 무변경. 단 override 시그니처 변경이 issue-tracking 컴파일을 가로지름(B2).

**REFACTOR**:
- actor.raw→UUID 변환을 컨트롤러 공통 헬퍼로 추출(중복 제거). KDoc에 BC 격리/이동 사유.

**검증**: `./gradlew :modules:shared-kernel:test :modules:project-workflow:test :modules:issue-tracking:test` + ArchUnit BC 격리 그린. **issue-tracking test 컴파일 필수 포함**(B2 — AlwaysAllow override 시그니처 변경이 issue-tracking 테스트 컴파일 가로지름). **회귀 0**(거부 동작은 stub이라 non-prod 무변경).

---

### Task 2. V013 — `MANAGE_WORKFLOW` permission_code를 PROJECT_ADMIN에 시드

**메타**.
- agent: `security-engineer`
- files: [
  `backend/modules/identity-access/src/main/resources/db/migration/V013__manage_workflow_permission.sql`,
  `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/PermissionSchemaMigrationTest.kt` (카운트 +1 갱신)
  ]
- depends-on: []

**RED**:
- `PermissionSchemaMigrationTest`에 기본 스킴 PROJECT_ADMIN이 `MANAGE_WORKFLOW`를 보유한다는 단언 추가 → V013 부재로 RED. (기존 정확-카운트 단언도 +1 — 메모리 `fr-pm-permission-seed-migration-test-coupling`)

**GREEN**:
- V013 마이그레이션 1행 INSERT (V009 `MANAGE_COMPONENTS` 미러). 기본 스킴 `00000000-…-001`, role `PROJECT_ADMIN`, code `MANAGE_WORKFLOW`.

**REFACTOR**:
- 마이그레이션 헤더 주석에 FR-PM-04/SDD 12.3 근거. (DDL 없음 → init_codegen.sql 미러 불요)

**검증**: `./gradlew :modules:identity-access:test --tests *PermissionSchemaMigrationTest` (Testcontainers) 그린.

---

### Task 3. `IdentityAccessWorkflowSchemePermissionResolver` (@Profile prod) 구현 + Testcontainers 통합테스트

**메타**.
- agent: `security-engineer`
- files: [
  `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessWorkflowSchemePermissionResolver.kt`,
  `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessWorkflowSchemePermissionResolverIntegrationTest.kt`
  ]
- depends-on: [1, 2]

**RED** (Testcontainers 통합, prod 판정 ground-truth):
- Global/MANAGE_SCHEME — SYSTEM_ADMIN 보유 actor → 통과(예외 없음, S1) / 미보유 → `WorkflowSchemeAccessDeniedException`(S2).
- Project/ASSIGN_SCHEME — PROJECT_ADMIN 멤버+MANAGE_WORKFLOW → 통과(S3) / MEMBER → 거부(S4) / 비멤버 → 거부(S5) / 미해석 키 → 거부(S6).
- → 구현 부재로 RED.

**GREEN**:
- `@Component @Profile("prod")` 구현. 생성자: `SystemPermissionResolver`(shared-kernel, Global 판정 캡슐화) + `ProjectDirectory`(key→id, FR-PM-02 선례) + `ProjectMembershipRepository` + `PermissionSchemeRepository`.
- Global → `systemPermissionResolver.isSystemAdmin(actorId)` false면 throw. (B1/W4: `SystemRoleAssignmentRepository` 직접 호출 금지, spec FR-2와 합치.)
- Project(key) → `projectDirectory.resolveKeyToId(key)` null이면 throw, `membershipRepo.findByProjectAndUser(projectId, actorId)` null이면 throw, `permissionSchemeRepo.roleHasPermission(projectId, membership.role.name, "MANAGE_WORKFLOW")` false면 throw. (FR-PM-03 `IdentityAccessComponentPermissionResolver` 패턴, `roleHasPermission`은 projectId(UUID) 인자.)

**REFACTOR**:
- `WorkflowSchemePermission`→permission_code 매핑을 `when`(else 없이) 헬퍼로(FR-PM-03 `toPermissionCode` 선례). deny-by-default 주석.

**검증**: `./gradlew :modules:identity-access:test --tests *IdentityAccessWorkflowSchemePermissionResolver*` 그린.

---

### Task 4. project-workflow 예외 핸들러 — shared-kernel 예외 → 403 매핑

**메타**.
- agent: `security-engineer`
- files: [
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeExceptionHandler.kt` (핸들러 1건 추가 + 인라인 `SchemeErrorCodes` object(`:313`)에 `WORKFLOW_SCHEME_ACCESS_DENIED` 상수 추가 — W3: 별도 파일 아님),
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeExceptionHandlerTest.kt` (또는 컨트롤러 테스트)
  ]
- depends-on: [1]

**RED**:
- 핸들러 테스트 — `WorkflowSchemeAccessDeniedException` throw 시 403 + errorCode `WORKFLOW_SCHEME_ACCESS_DENIED` 단언 → 핸들러 부재로 RED.

**GREEN**:
- `@ExceptionHandler(WorkflowSchemeAccessDeniedException)` → `ResponseEntity.status(FORBIDDEN)` + errorCode.

**REFACTOR**:
- 핸들러 KDoc 표에 신규 errorCode 1행 추가.

**검증**: `./gradlew :modules:project-workflow:test --tests *WorkflowSchemeExceptionHandler*` 그린.

---

### Task 5. prod 프로파일 빈 해소 검증 (W2 재정의)

> **W2 정정**: identity-access는 `WorkflowSchemePermissionResolver`를 어디서도 주입하지 않고(소비자 0), AlwaysAllow는 project-workflow 패키지라 identity-access가 스캔도 안 함 → "AlwaysAllow 미등록" 단언은 identity-access 컨텍스트에서 vacuous(항상참, 검증력 0). 의미 있는 검증은 **prod 프로파일에서 `IdentityAccessWorkflowSchemePermissionResolver` 빈이 `@Profile("prod")`로 실제 등록되는지**뿐이다.

**메타**.
- agent: `security-engineer`
- files: [
  `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessWorkflowSchemePermissionResolverBootTest.kt`
  ]
- depends-on: [3]

**RED**:
- prod 프로파일 컨텍스트에서 `IdentityAccessWorkflowSchemePermissionResolver` 빈이 1개 존재(`@Profile("prod")` 활성)함을 단언. 비-prod 프로파일에선 그 빈이 미등록임을 단언(profile 배타성 검증 — vacuous 아님, 실제 분기). → 빈 부재로 RED.

**GREEN**:
- T3의 `@Profile("prod")` 등록으로 충족. 누락 시 컴포넌트 스캔 경로 보완.

**REFACTOR**:
- 테스트 주석에 "이 포트는 project-workflow가 소비, identity-access는 prod adapter만 제공" 명시.

**검증**: `./gradlew :modules:identity-access:test --tests *IdentityAccessWorkflowSchemePermissionResolverBootTest` 그린.

> **참고**: 거부(403) end-to-end 검증은 Task3의 prod-프로파일 Testcontainers 통합테스트(S1~S6)가 ground-truth. Task5는 빈 등록/배타성만 가볍게 검증(Task3에 흡수 가능하나 부팅 관심사 분리 위해 유지).

## Plan 메타

- task 수: 5
- 예상 시간: 직렬 기준 약 30~40분(통합테스트 Testcontainers 포함). T1 포트 이동이 3모듈 컴파일 직렬화 요인 → 병렬성 낮음.
- 예상 wave: Wave1 {T1, T2}(T2는 코드의존 없으나 identity-access 모듈 test 컴파일 공유로 사실상 직렬) → Wave2 {T3, T4} → Wave3 {T5}.
- TDD 강제: yes (T3/T5는 prod-프로파일 통합테스트가 ground-truth, non-prod AlwaysAllow가 거부경로 가림 — 메모리 `issue-scope-global-prod-hard-deny`).
- 리스크: ① 포트 이동 참조 누락(테스트 @Bean 포함 전수 grep 필수 — 메모리 `archunit-shared-class-move-repository-package`) ② key→id 해석 포트 cross-BC 회색지대(EC5, 기존 stub 유지) ③ project-workflow가 shared-kernel 의존하는지 build.gradle 확인 ④ identity-access 통합테스트가 prod 판정 ground-truth인지(non-prod 마스킹 주의).

## 리뷰 결과

### code-reviewer ground-truth 리뷰 (2026-06-05)

auth FR → eng 집중 ground-truth 리뷰(autoplan 대신, 메모리 `bts-review-plan-autoplan-overkill`). BLOCKER 3 + WARN 4 발견, **전부 plan/spec에 반영 완료**.

- **B1 (BLOCKER, 해소)** key→id 해석이 project-workflow `JdbcProjectLookupAdapter`(BC 격리 위반·컴파일 불가) 대신 identity-access `ProjectDirectory.resolveKeyToId`(FR-PM-02 선례)여야 함 → spec EC5 + Task3 GREEN 정정.
- **B2 (BLOCKER, 해소)** AlwaysAllow override 시그니처 변경이 issue-tracking 6개 테스트 컴파일을 가로지름 → Task1 검증에 `:modules:issue-tracking:test` 추가.
- **B3 (BLOCKER, 해소)** `ProjectWorkflowSchemeControllerTest`의 포트 직접 구현 2곳(`:64`,`:98`) override 갱신 → Task1 files 명시.
- **W1 (해소)** 호출부 "8곳" → 실제 13곳(5+2+6) 정정.
- **W2 (해소)** Task5 부팅 테스트가 선례(non-prod fallback) 오인용 + 비소비 포트라 vacuous → prod 빈 해소/배타성 검증으로 재정의.
- **W3 (해소)** `SchemeErrorCodes`는 별도 파일 아님 → `WorkflowSchemeExceptionHandler.kt:313` 인라인 object에 상수 추가.
- **W4 (해소)** Global 판정은 `SystemPermissionResolver.isSystemAdmin` 경유(spec 합치), repo 직접 호출 X.

**OK 확인됨**: actor.raw→UUID 변환 안전(value class, 선례 존재), `roleHasPermission(projectId:UUID,…)`/`findByProjectAndUser` 재사용 가능, Guard 예외 shared-kernel 배치 시 양 BC 참조 가능(둘 다 shared-kernel 의존)+ArchUnit 통과, V013 전모듈 미사용, DDL 없어 init_codegen 미러 불요, depends-on DAG 순환 없음.

**머지 직전 주의**: 동시 진행 브랜치(`origin/feat/fr-cm-02-issue-components` 등) 있으므로 identity-access V013 번호 머지 전 재확인(메모리 `migration-vnumber-concurrent-branch-collision`).

- **BLOCKER: 없음** (3건 전부 해소).
