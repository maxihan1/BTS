# FR-TM-01 — 프로젝트+타입별 본문 템플릿 (백엔드 D1~D5)

> slug: fr-tm-01-issue-templates
> type: backend
> agent: backend-engineer
> 생성: 2026-06-12

## Brief

FR-TM-01 (issue-tracking BC, §5.2.1) — 프로젝트와 이슈 타입 조합마다 이슈 본문 기본
템플릿을 정의하고, 이슈 생성 시 자동으로 적용한다.

이번 /bts 실행 범위 = **백엔드 D1~D5** (Maxi 확정).
- D1. 도메인 — IssueTemplate
- D2. 명세
- D3. 데이터 모델 — `issue_templates(project_id, type_id, body)`
- D4. 백엔드 — CRUD API + 이슈 생성 시 적용
- D5. 백엔드 테스트

프론트 관리 페이지(D6) + E2E(D7)는 후속 /bts로 별도 PR.

classify 결과: type=backend, agent=backend-engineer, primary_bc=issue-tracking.

## 도메인 정리

- **BC**: issue-tracking
- **신규 엔티티**: IssueTemplate — `issue_templates(id, project_id, type_id, body, created_at, updated_at)`, `UNIQUE(project_id, type_id)` (프로젝트+타입 조합당 본문 템플릿 1개). body = Markdown(이슈 description 형식). FR-TM-02 변수 치환은 후속.
- **신규 용어**: "이슈 템플릿 / IssueTemplate" — 프로젝트+타입별 이슈 본문 기본 템플릿. (glossary 추가 후보 — Maxi 승인 후 머지 시 동기화)
- **신규 권한코드**: `MANAGE_TEMPLATES` (CustomField `MANAGE_CUSTOM_FIELDS` 동형). `TemplatePermission`(CREATE/UPDATE/DELETE) + `TemplatePermissionResolver`. READ 미게이트. → role_permissions 시드 + `PermissionSchemaMigrationTest` 카운트 같은 PR 동기화 필요.
- **적용 방식 (Maxi 확정 = 옵션 C)**: 프론트 프리필(B) + 서버 안전망. CreateIssueRequest 에 `description` 추가 + resolve 조회 엔드포인트 + createIssue 에서 description blank 이고 (project,type) 템플릿 존재 시 서버가 template.body 주입. Jira Cloud 동작 조사 근거(프리필-편집 권장, 사후주입 열등).
- **기존 결정 충돌**: 없음 (신규 영역). createIssue 에 description 추가는 기존 생성 테스트 영향 → plan 에서 회귀 범위 명시.
- **관련 ADR**: [docs/adr/2026-06-12-issue-template-model-and-application.md](../adr/2026-06-12-issue-template-model-and-application.md) (생성됨)
- **선례 참조**: CustomFieldPermission.kt(권한 동형), IssueApplicationService.createIssue(적용 지점), 2026-06-02-issue-clone-semantics(생성 시 필드 채움)

## 스펙

전체 스펙. [docs/specs/2026-06-12-fr-tm-01-issue-templates.md](../specs/2026-06-12-fr-tm-01-issue-templates.md)

핵심 요약.
- issue_templates(project_id UUID, issue_type_id BIGINT, name, content) + 소프트삭제 + 활성 UNIQUE(project,type) 1개.
- CRUD API `/api/v1/projects/{key}/issue-templates` (CUD=MANAGE_TEMPLATES, READ/resolve 미게이트) + resolve 엔드포인트.
- CreateIssueRequest 에 description 추가 + createIssue 안전망(blank이고 템플릿 존재 시 서버 주입).
- cross-BC: identity-access prod resolver + 권한시드 + PermissionSchemaMigrationTest 카운트+1 + MyProjectPermission 노출.

주의(plan 단계 강조).
- 마이그레이션 V번호: issue-tracking V019 + identity-access V025(추정, FR-MF-04 V024 충돌 경계 — 머지 직전 재확인).
- init_codegen.sql 미러(issue_templates), IssueApplicationService nullable-default 주입(기존 테스트 호환).
- 정본 동기화: SDD §5.7 ↔ product D3 ↔ fr-index ↔ ADR.

## Brainstorming Check

✅ 통과 (1회 iteration, 자체 gap 분석). MyProjectPermission 노출·cloneIssue 무영향·content NotBlank 보강. Maxi 결정 필요 gap 없음(멀티플리시티·적용방식은 도메인 단계 확정).

## Plan

> 전 task custom-field(FR-IS-10) 동형 미러. 패키지 `com.bts.issue.template.{domain,application,repository,web,web.dto,adapter}`.
> 권한 포트는 shared-kernel, prod 판정은 identity-access(BC 격리 — 직접 import 금지, 포트만 의존).

### Task 1. shared-kernel — TemplatePermission + TemplatePermissionResolver 포트

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/TemplatePermission.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/TemplatePermissionResolver.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/permission/TemplatePermissionTest.kt`]
- depends-on: []

**RED**: `TemplatePermissionTest` — `TemplatePermission.CREATE.toPermissionCode() == "MANAGE_TEMPLATES"` 검증. 클래스 없음 → 실패.
**GREEN**: `TemplatePermission` enum(CREATE/UPDATE/DELETE) + companion `MANAGE_TEMPLATES`. `fun interface TemplatePermissionResolver { fun hasPermission(actorId: UUID, permission: TemplatePermission, projectId: UUID): Boolean }`. CustomFieldPermission/Resolver 동형 미러.
**REFACTOR**: KDoc(권한 표 + ArchUnit 강제 주석) custom-field 동형.
**검증**: `./gradlew :backend:modules:shared-kernel:test --tests TemplatePermissionTest`

### Task 2. issue-tracking — IssueTemplate 도메인 + 예외

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/domain/IssueTemplate.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/domain/IssueTemplateExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/template/domain/IssueTemplateTest.kt`]
- depends-on: []

**RED**: `IssueTemplateTest` — `IssueTemplate.create(name, content)` 생성 + name 공백/100자 초과·content 공백 시 도메인 예외 검증. 실패.
**GREEN**: `IssueTemplate`(id UUID, projectId UUID, issueTypeId Long, name, content, createdAt, updatedAt, deletedAt) data class + `create` 팩토리(검증) + `IssueTemplateNotFoundException`/`DuplicateIssueTemplateException`/`InvalidIssueTemplateException`.
**REFACTOR**: 검증 상수(NAME_MAX=100) 추출, KDoc.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests IssueTemplateTest`

### Task 3. issue-tracking — V019 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V019__issue_templates.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`]
- depends-on: []

**작업(비-TDD 마이그레이션)**: issue_templates 테이블(스펙 §데이터 모델 DDL 그대로) — UUID PK, project_id UUID FK→projects, issue_type_id BIGINT FK→issue_types, name VARCHAR(100), content TEXT, created/updated/deleted_at. FK 인덱스 2개 + 활성 부분 UNIQUE(project_id, issue_type_id) WHERE deleted_at IS NULL. **init_codegen.sql 에 동일 테이블 미러 필수**(jOOQ codegen).
**검증**: `./gradlew :backend:modules:issue-tracking:generateJooq` 성공 + `ISSUE_TEMPLATES` jOOQ 테이블 생성 확인. 머지 직전 V번호 재확인(FR-MF-04 충돌 경계).

### Task 4. issue-tracking — IssueTemplateRepository (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/repository/IssueTemplateRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/template/repository/IssueTemplateRepositoryIntegrationTest.kt`]
- depends-on: [2, 3]

**RED**: `IssueTemplateRepositoryIntegrationTest`(Testcontainers) — insert/findById/findByProject(활성)/update/softDelete/existsActive(project,type)/findActiveContentByProjectAndType. 구현 없음 → 실패.
**GREEN**: jOOQ DSLContext 기반 구현. 소프트삭제(deleted_at), 활성 필터. `findActiveContentByProjectAndType(projectId, issueTypeId): String?`(안전망용). CustomFieldDefinitionRepository 동형.
**REFACTOR**: 매핑 함수 추출, KDoc.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests IssueTemplateRepositoryIntegrationTest`

### Task 5. issue-tracking — IssueTemplateApplicationService (CRUD+resolve) + AlwaysAllow stub

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/application/IssueTemplateApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/adapter/AlwaysAllowTemplatePermissionResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/template/application/IssueTemplateApplicationServiceTest.kt`]
- depends-on: [1, 2, 4]

**RED**: 서비스 테스트(mockk repo+resolver) — create(권한OK→insert, 중복→409, 권한없음→403), update/delete(미존재→404), resolve(활성 content/없으면 null), READ 미게이트. 실패.
**GREEN**: `@Service @Transactional`. 각 mutation 진입 직후 `resolver.hasPermission(actor.value, TemplatePermission.X, projectId)` 검증(false→권한 예외). resolve/list/get 미게이트. `AlwaysAllowTemplatePermissionResolver`(@Component @Profile("!prod")) stub. CustomFieldApplicationService 동형.
**REFACTOR**: 권한 가드 헬퍼, KDoc.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests IssueTemplateApplicationServiceTest`

### Task 6. issue-tracking — IssueTemplateController + DTO + 예외핸들러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/web/IssueTemplateController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/web/IssueTemplateErrorCodes.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/web/IssueTemplateExceptionHandler.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/web/dto/CreateIssueTemplateRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/web/dto/UpdateIssueTemplateRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/web/dto/IssueTemplateResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/template/web/IssueTemplateControllerIntegrationTest.kt`]
- depends-on: [5]

**RED**: `IssueTemplateControllerIntegrationTest`(@SpringBootTest) — `/api/v1/projects/{projectIdOrKey}/issue-templates` GET목록/GET단건/GET resolve/POST(201,409)/PATCH(200,404)/DELETE(204) + 권한 403(prod resolver는 테스트서 AlwaysAllow라 별도 검증은 T8). projectIdOrKey 해석. 실패.
**GREEN**: 컨트롤러(custom-field 동형 매핑) + DTO(CreateIssueTemplateRequest: issueTypeId Long, name @NotBlank≤100, content @NotBlank; UpdateIssueTemplateRequest: name?/content? 부분) + ErrorCodes + ExceptionHandler(404/409/403 매핑, 동명 예외 교차패키지 상태코드 주의).
**REFACTOR**: 응답 매핑 추출, KDoc.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests IssueTemplateControllerIntegrationTest`

### Task 7. issue-tracking — createIssue 안전망 + CreateIssueRequest description

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTemplateApplyTest.kt`]
- depends-on: [4]

**RED**: `IssueApplicationServiceTemplateApplyTest`(mockk, 새 파일) — 스펙 S4 5케이스(blank+템플릿→주입 / non-blank→요청우선 / 템플릿없음→null / ""+템플릿→주입 / cloneIssue 무영향). 실패.
**GREEN**: REST/application `CreateIssueRequest` 에 `description: String?` 추가 + IssueController 매핑 전달. `IssueApplicationService` 에 `issueTemplateRepository: IssueTemplateRepository? = null` **nullable-default 주입**(customFieldDefinitionRepository 동형 — 기존 테스트 호환). createIssue 에서 `val resolvedDescription = request.description?.takeIf { it.isNotBlank() } ?: issueTemplateRepository?.findActiveContentByProjectAndType(projectId, resolvedTypeId.value)` → `Issue.create(..., description = resolvedDescription)`. cloneIssue 미변경.
**REFACTOR**: 헬퍼 추출, KDoc(옵션C/blank 시맨틱).
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests IssueApplicationServiceTemplateApplyTest` + 기존 `IssueApplicationServiceTest`/`IssueControllerIntegrationTest` 회귀 0.

### Task 8. identity-access — prod resolver + 권한시드 + 카운트테스트 + MyProjectPermission 노출

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessTemplatePermissionResolver.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/DevAllowTemplatePermissionResolver.kt`, `backend/modules/identity-access/src/main/resources/db/migration/V025__manage_templates_permission.sql`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/MyProjectPermissionController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/PermissionSchemaMigrationTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessTemplatePermissionResolverTest.kt`]
- depends-on: [1]

**RED**: `IdentityAccessTemplatePermissionResolverTest`(Testcontainers) — PROJECT_ADMIN→MANAGE_TEMPLATES true, MEMBER→false. `PermissionSchemaMigrationTest` 카운트 +1 기대로 수정 → 시드 전엔 실패.
**GREEN**: `IdentityAccessTemplatePermissionResolver`(@Profile("prod"), role_permissions 조회) + `DevAllowTemplatePermissionResolver`(@Profile("!prod"), 단 identity는 dev-allow 패턴 — custom-field 동형 확인 후 미러) + `V025__manage_templates_permission.sql`(PROJECT_ADMIN, MANAGE_TEMPLATES INSERT, init_codegen 미러 불요) + `MyProjectPermissionController` 요약에 `manageTemplates` 플래그 추가.
**REFACTOR**: KDoc, 권한코드 상수 참조.
**검증**: `./gradlew :backend:modules:identity-access:test --tests "IdentityAccessTemplatePermissionResolverTest" --tests "PermissionSchemaMigrationTest"`. **V번호 머지 직전 재확인(FR-MF-04)**. 권한코드 카운트 가드 전 모듈 grep.

### Task 9. 정본 동기화 (docs)

**메타**.
- agent: `backend-engineer`
- files: [`docs/sdd/05-data-model.md`, `docs/plan/product/issue-tracking.md`]
- depends-on: []

**작업(비-TDD docs)**: SDD §5.7 IssueTemplate — 실제 구현 타입 주석(project_id UUID, id UUID) + `UNIQUE(project_id, issue_type_id)` 명시. product §5.2.1 D3 — `body`→`content` 필드명 정정 + "(프로젝트,타입)당 1개" 명시 + ADR 링크. **D1~D5 체크박스 마킹 + dashboard regen 은 bts-merge 에서**(PR #125 주석). fr-index 카운트 변화 없음(FR-TM-01 기존 등재).
**검증**: `bash scripts/verify-master-plan.sh` 통과(FR ID 정합 + 카운트 drift 0).

## Plan 메타

- task 수: 9 (T3·T9 비-TDD, 나머지 TDD)
- 모듈: shared-kernel(T1) · issue-tracking(T2~T7) · identity-access(T8) · docs(T9)
- 예상 wave: 4 — W1[T1,T2,T3,T9] / W2[T4(←2,3), T8(←1)] / W3[T5(←1,2,4), T7(←4)] / W4[T6(←5)]
- 예상 시간: 약 16분(4 wave 병렬)
- TDD 강제: yes (T3·T9 제외)
- cross-BC: identity-access 권한 판정(포트 경유, 직접 import 0 — ArchUnit 강제)
- 주의: init_codegen 미러(T3) · V번호 충돌 재확인(T3·T8) · nullable-default 주입(T7) · 카운트 가드(T8) · lint-staged 자기파일만 stage(병렬 race)

## 리뷰 결과 (← /bts-review-plan 채움)
