# FR-IS-10 — 커스텀 필드 인프라

> slug: fr-is-10-custom-fields
> type: backend (feature — UI 폼 렌더 포함)
> agent: backend-engineer (+ frontend-engineer, qa-engineer)
> primary_bc: issue-tracking
> 생성: 2026-06-08

## Brief

신규 FR. 회사가 이슈에 커스텀 필드(예. "급여 영향도")를 직접 정의·관리하는 인프라.
필드 정의 CRUD + 값 저장·검증 + 이슈 폼 동적 렌더. SDD §05 데이터모델에 `issues.custom_fields JSONB` 컬럼 한 줄만 존재(미설계 영역).

**선후행**. FR-IS-10(이 작업, 커스텀 필드) → FR-PM-07(필드 수준 권한, 코어+커스텀 필드 대상). 필드 권한이 커스텀 필드를 대상으로 포함하므로 FR-IS-10이 선행.

**범위 결정 (Maxi 2026-06-08)**. 커스텀 필드를 신규 FR-IS-10으로 신설(FR-PM은 identity-access 전용 시리즈라 부적합). 필드 권한(FR-PM-07)은 별도 PR로 후행.

classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**: issue-tracking (단일, cross-BC 없음)
- **새 엔티티**:
  - `CustomFieldDefinition` — 필드 정의. 프로젝트별(`project_id`). key/name/description/type/required/display_order/is_active. Resolution 마스터 테이블 패턴 차용(소프트 삭제, display_order).
  - `CustomFieldOption` — 선택형(SINGLE_SELECT/MULTI_SELECT/RADIO) 타입의 선택지. 정의에 종속.
- **값 저장**: `issues.custom_fields` JSONB (`{field_key: value}`). SDD §05 데이터모델 설계와 일치. 타입/참조무결성은 ApplicationService 책임(JSONB는 스키마리스). GIN 인덱스(SDD §05 line 266 명시).
- **필드 타입**(확장 가능 `FieldType` enum, 1차 10종): SHORT_TEXT · LONG_TEXT · NUMBER · DATE · DATETIME · SINGLE_SELECT · MULTI_SELECT · CHECKBOX · RADIO · URL. cross-BC 참조형(USER/GROUP/VERSION/COMPONENT picker)은 후속 — 타입만 추가.
- **적용 범위**: 프로젝트별 (이슈타입 무관). component/version 선례. Jira식 풀 컨텍스트(프로젝트×이슈타입)는 채택 안 함.
- **새 용어**: "커스텀 필드(Custom Field)", "필드 정의(Field Definition)", "필드 타입(Field Type)" — glossary 추가 대기(Maxi 승인 후).
- **관리 권한**(spec에서 확정): 프로젝트별 스코프 → PROJECT_ADMIN 후보. 신규 권한 코드(MANAGE_CUSTOM_FIELDS) 필요 시 role_permissions 시드 추가 → PermissionSchemaMigrationTest 카운트 영향(learnings: fr-pm-permission-seed-migration-test-coupling). identity-access BC 결합이라 spec에서 분리 검토.
- **기존 결정 충돌**: 없음 (신규 영역). SDD §05 custom_fields 컬럼(미설계) 결선.
- **관련 ADR**: docs/decisions/2026-06-08-custom-fields-model.md (생성)
- **신규 FR 등록 필요**(전수 동기화): fr-index · SDD(02 + 신규 §) · product/issue-tracking · README · CLAUDE 카운트.

## 스펙

전체 스펙. [docs/specs/2026-06-08-fr-is-10-custom-fields.md](../specs/2026-06-08-fr-is-10-custom-fields.md)

핵심 요약.
- 1차 PR = **백엔드만** (정의 CRUD API + 이슈 값 검증 + 테스트). 프론트는 후속 PR.
- 정의 CRUD = ComponentController 패턴 차용(`/api/v1/projects/{key}/custom-fields`). 관리 권한 = PROJECT_ADMIN 직접 확인(신규 권한코드 없음).
- 값 = `issues.custom_fields` JSONB. 검증(미정의키/required/타입/선택지 → 422)은 ApplicationService.
- PATCH = 필드 단위 병합(키 단위 갱신, null=제거). FieldType/key 생성 후 불변.
- 데이터: V015 — custom_field_definitions + custom_field_options + issues.custom_fields JSONB + GIN(init_codegen 미러).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 4건 보강 — PATCH 병합 정책(Maxi 결정), 클론 미복사, 목록 노출, 검색/bulk/pdf 범위밖 명시.

## Plan

> 권한코드 방식(Component 동형) 확정 → **cross-BC PR** (권한코드 시드 + resolver = identity-access, 나머지 = issue-tracking). FR-CM-01 선례.
> 패키지: `com.bts.issue.customfield.{domain,application,repository,web,adapter}` (component 동형).
> 권한 포트: `com.bts.shared.permission.CustomFieldPermission(Resolver)`. prod: identity `...permission.IdentityAccessCustomFieldPermissionResolver`.
> V번호: issue-tracking=V015, identity-access=V017.

### Task 1. 권한 포트 — CustomFieldPermission enum + CustomFieldPermissionResolver

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/CustomFieldPermission.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/CustomFieldPermissionResolver.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/permission/CustomFieldPermissionTest.kt`]
- depends-on: []

**RED**: CustomFieldPermissionTest — enum CREATE/UPDATE/DELETE 존재 + toPermissionCode()="MANAGE_CUSTOM_FIELDS" 매핑. 클래스 없음으로 실패.
**GREEN**: ComponentPermission/ComponentPermissionResolver 동형 작성. enum 3종 + `toPermissionCode()` 전부 MANAGE_CUSTOM_FIELDS. Resolver fun interface `hasPermission(actorId, permission, projectId): Boolean`.
**REFACTOR**: KDoc + ComponentPermission 참조 주석.
**검증**: `./gradlew :backend:shared-kernel:test --tests CustomFieldPermissionTest`

### Task 2. V015 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V015__custom_fields.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/customfield/CustomFieldsMigrationTest.kt`]
- depends-on: []

**RED**: MigrationTest — Testcontainers Flyway 적용 후 `custom_field_definitions`/`custom_field_options` 테이블 + `issues.custom_fields` 컬럼 + GIN 인덱스 존재 검증. 미적용 실패.
**GREEN**: 스펙 §데이터 모델 DDL 그대로. 부분 유니크 `UNIQUE(project_id,key) WHERE deleted_at IS NULL`, options FK ON DELETE CASCADE, `ALTER TABLE issues ADD custom_fields JSONB NOT NULL DEFAULT '{}'`, GIN. init_codegen.sql에 동일 컬럼 미러(메모리 jooq-init-codegen-mirror).
**REFACTOR**: COMMENT ON 컬럼 (Resolution 스타일).
**검증**: `./gradlew :backend:issue-tracking:test --tests CustomFieldsMigrationTest`

### Task 3. 도메인 — FieldType enum + CustomFieldDefinition + CustomFieldOption + 예외

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/customfield/domain/FieldType.kt`, `.../customfield/domain/CustomFieldDefinition.kt`, `.../customfield/domain/CustomFieldOption.kt`, `.../customfield/domain/CustomFieldExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/customfield/domain/CustomFieldDefinitionTest.kt`]
- depends-on: []

**RED**: 도메인 테스트 — FieldType 10종, `CustomFieldDefinition.create()` 불변식(key URL-safe·name 비어있지 않음·선택형은 옵션 필수). 클래스 없음 실패.
**GREEN**: FieldType enum(SHORT_TEXT/LONG_TEXT/NUMBER/DATE/DATETIME/SINGLE_SELECT/MULTI_SELECT/CHECKBOX/RADIO/URL) + `isSelectType` 헬퍼. CustomFieldDefinition 불변 데이터클래스 + create 팩토리(require). CustomFieldOption. 예외(DuplicateCustomFieldKey/CustomFieldNotFound/InvalidFieldDefinition/ImmutableFieldTypeChange).
**REFACTOR**: require 메시지 상수화.
**검증**: `./gradlew :backend:issue-tracking:test --tests CustomFieldDefinitionTest`

### Task 4. 값 검증기 — CustomFieldValueValidator (타입별)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/customfield/domain/CustomFieldValueValidator.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/customfield/domain/CustomFieldValueValidatorTest.kt`]
- depends-on: [3]

**RED**: 검증기 테스트 — 스펙 §값별 JSONB 형식 + E1~E4/E6 검증. 미정의키 거부, required 누락 거부, 타입 불일치 거부, 선택지 위반 거부. 각 케이스 ValidationException 기대.
**GREEN**: `validate(definitions: List<CustomFieldDefinition>, values: Map<String, Any?>)`. 타입별 when 분기 검증(길이/형식/옵션). 미정의 키·required·타입·옵션 위반 → 도메인 예외.
**REFACTOR**: 타입별 검증을 FieldType when으로 응집. URL/ISO date 정규식 상수.
**검증**: `./gradlew :backend:issue-tracking:test --tests CustomFieldValueValidatorTest`

### Task 5. Repository — CustomFieldDefinitionRepository (정의+옵션)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/customfield/repository/CustomFieldDefinitionRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/customfield/repository/CustomFieldDefinitionRepositoryTest.kt`]
- depends-on: [2, 3]

**RED**: Repository 통합 테스트(Testcontainers) — save/findByProjectAndKey/findActiveByProject/softDelete + 옵션 동반 저장/조회. 부분 유니크 충돌(409 소스). 정의 없음 실패.
**GREEN**: ComponentRepository 동형(raw SQL NamedParameterJdbcTemplate). 정의+옵션 트랜잭션 저장, 활성 목록 display_order 정렬, 소프트 삭제, 옵션 join 조회.
**REFACTOR**: SQL 상수화 + rowMapper 분리.
**검증**: `./gradlew :backend:issue-tracking:test --tests CustomFieldDefinitionRepositoryTest`

### Task 6. 권한 결선 — identity prod resolver + V017 시드 + PermissionSchemaMigrationTest

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessCustomFieldPermissionResolver.kt`, `backend/modules/identity-access/src/main/resources/db/migration/V017__manage_custom_fields_permission.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/PermissionSchemaMigrationTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessCustomFieldPermissionResolverTest.kt`]
- depends-on: [1]

**RED**: PermissionSchemaMigrationTest 카운트 +1(role_permissions) 기대 → 현재 시드로 실패. resolver 통합 테스트 — PROJECT_ADMIN true / MEMBER false.
**GREEN**: V017 시드 `('...001','PROJECT_ADMIN','MANAGE_CUSTOM_FIELDS')`. IdentityAccessComponentPermissionResolver 동형 prod resolver(roleHasPermission). PermissionSchemaMigrationTest 카운트 갱신(메모리 fr-pm-permission-seed-migration-test-coupling).
**REFACTOR**: toPermissionCode 재사용.
**검증**: `./gradlew :backend:identity-access:test --tests PermissionSchemaMigrationTest --tests IdentityAccessCustomFieldPermissionResolverTest`

### Task 7. ApplicationService — 정의 CRUD + 권한 가드 + AlwaysAllow stub

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/customfield/application/CustomFieldApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/customfield/adapter/AlwaysAllowCustomFieldPermissionResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/customfield/application/CustomFieldApplicationServiceTest.kt`]
- depends-on: [1, 4, 5]

**RED**: Service 단위 테스트(mock resolver/repo) — create/update/delete 전 assertPermission 호출, 미인가 시 거부 예외. fieldType/key 불변(update 시 변경 거부). 프로젝트 미존재 404.
**GREEN**: ComponentApplicationService 동형. @Service @Transactional. CRUD + assertPermission(CustomFieldPermissionResolver). AlwaysAllow stub(non-prod, issue-tracking adapter — IssueScope 마스킹 패턴).
**REFACTOR**: assertPermission private 헬퍼.
**검증**: `./gradlew :backend:issue-tracking:test --tests CustomFieldApplicationServiceTest`

### Task 8. Controller + DTO + ErrorCodes + ExceptionHandler

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/customfield/web/CustomFieldController.kt`, `.../customfield/web/CustomFieldErrorCodes.kt`, `.../customfield/web/CustomFieldExceptionHandler.kt`, `.../customfield/web/dto/CreateCustomFieldRequest.kt`, `.../customfield/web/dto/UpdateCustomFieldRequest.kt`, `.../customfield/web/dto/CustomFieldResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/customfield/web/CustomFieldControllerTest.kt`]
- depends-on: [7]

**RED**: Controller 슬라이스 테스트(@WebMvcTest 또는 MockMvc) — 5 엔드포인트 상태코드(201/200/200/200/204) + 권한 403 + 미인증 401 + RFC7807 ProblemDetail(errorCode). 미구현 실패.
**GREEN**: ComponentController 동형. `/api/v1/projects/{projectIdOrKey}/custom-fields`. DataResponse 래핑. Jakarta Validation. ExceptionHandler 도메인 예외→상태(409/404/422). actorId SYSTEM_ACTOR_UUID placeholder.
**REFACTOR**: ErrorCodes 상수.
**검증**: `./gradlew :backend:issue-tracking:test --tests CustomFieldControllerTest`

### Task 9. 이슈 통합 — Create/Update/Response + custom_fields 검증/병합/저장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequest.kt`, `.../rest/UpdateIssueRequest.kt`, `.../rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueCustomFieldsTest.kt`]
- depends-on: [4, 5]

**RED**: IssueCustomFieldsTest — 생성 시 customFields 검증+저장, 조회 응답 노출(단건+목록), PATCH 필드단위 병합(키 갱신/null 제거/부재 무변경, E11), required 최종상태 검증. 미구현 실패.
**GREEN**: Issue 도메인에 customFields(Map) 추가. CreateIssueRequest/UpdateIssueRequest(JsonNullable 병합)/IssueResponse 필드 추가. IssueApplicationService가 정의 로드(Repository) + Validator 호출 + JSONB 저장. 클론(FR-IS-06) 미복사 유지(E10).
**REFACTOR**: 병합 로직 헬퍼 분리.
**검증**: `./gradlew :backend:issue-tracking:test --tests IssueCustomFieldsTest`

### Task 10. prod Testcontainers 통합 — 권한 ground-truth + 값 왕복

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/customfield/CustomFieldIntegrationTest.kt`]
- depends-on: [6, 8, 9]

**RED**: @ActiveProfiles("prod") 통합 — 정의 CRUD 권한(PROJECT_ADMIN 201 / MEMBER 403 / 미인증 401), 이슈 값 왕복(E1~E11 검증 422), 소프트삭제 후 값 보존+조회 제외(E5). 미충족 실패.
**GREEN**: 기존 issue-tracking prod 통합 테스트 base 재사용. ground-truth 거부 확인(non-prod AlwaysAllow 마스킹).
**REFACTOR**: 시드 헬퍼 추출.
**검증**: `./gradlew :backend:issue-tracking:test --tests CustomFieldIntegrationTest`

### Task 11. 전수 동기화 — fr-index/SDD/product/README/CLAUDE/glossary/dashboard

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/fr-index.md`, `docs/sdd/02-requirements.md`, `docs/sdd/05-data-model.md`, `docs/sdd/12-permissions.md`, `docs/plan/product/issue-tracking.md`, `docs/plan/README.md`, `CLAUDE.md`, `docs/progress.html`]
- depends-on: []

**RED**: `bash scripts/verify-master-plan.sh` 실행 → FR-IS-10 미등록으로 카운트 drift 실패(exit 4) 확인.
**GREEN**: 신규 FR-IS-10 등록 — fr-index(행+§A.2 issue-tracking 카운트+합계+상단주석), SDD(02 FR표 + 05 custom_fields 결선 주석 + 12.3 MANAGE_CUSTOM_FIELDS 권한코드 + 신규 §), product/issue-tracking(§본문+D단계+헤더 카운트+소속FR+완료게이트), README(BC테이블+합계), CLAUDE(FR 총수 121→122). glossary "커스텀 필드/필드 정의/필드 타입" 추가. dashboard `node scripts/build-dashboard.mjs` 재생성(메모리 dashboard-regen-after-fr-marking).
**REFACTOR**: 없음.
**검증**: `bash scripts/verify-master-plan.sh` (exit 0)

## Plan 메타

- task 수: 11
- BC: issue-tracking(주) + identity-access(권한 결선 T1/T6 — cross-BC, FR-CM-01 선례)
- 예상 wave (depends-on + files 기준):
  - wave1: T1, T2, T3, T11 (독립)
  - wave2: T4[3], T5[2,3], T6[1]
  - wave3: T7[1,4,5], T9[4,5]
  - wave4: T8[7]
  - wave5: T10[6,8,9]
- TDD 강제: yes (test 커밋 선행)
- 추가 검증: ktlint, detekt(--rerun-tasks), verify-master-plan.sh
- 주의(메모리): jooq-init-codegen-mirror(T2), permission-seed-migration-test-coupling+enum-count-guard(T6), patch-merge-domain-bypass(T9), join-table-fk-cascade(T2 options), migration-vnumber-concurrent-branch-collision(T2 V015 / T6 V017 머지 직전 재확인)

## 리뷰 결과 (← /bts-review-plan 채움)
