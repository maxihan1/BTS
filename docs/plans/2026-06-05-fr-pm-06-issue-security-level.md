# FR-PM-06 — 이슈 보안 수준 (Issue Security Level)

> slug: fr-pm-06-issue-security-level
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-05

## Brief

FR-PM-06 이슈 보안 수준. 이슈마다 보안 등급(IssueSecurityLevel: 이름·설명·허용 역할 목록)을
지정하고, VIEW_ISSUE 권한을 통과해도 그 이슈의 보안 수준을 통과하지 못하면 못 보게 하는
추가 차단 계층. FR-PM-05(Browse/View 분리)의 직속 후속.

- SDD 정본: docs/sdd/12-permissions.md §12.4
- plan 추적: docs/plan/product/identity-access.md §4.6
- 단계: D1 도메인 / D2 명세 / D3 데이터모델(security_levels, issues.security_level_id) /
  D4 백엔드 가드 / D5 백엔드 테스트 / D6 프론트 UI / D7 E2E

## 도메인 정리 (← /bts-domain 채움)

### ⏸️ 보류 (2026-06-05) — 그룹 인프라 선행 필요

도메인 grill 중 Maxi 결정으로 **FR-PM-06 보류**. 이유와 결정 내역을 기록(재개 시 컨텍스트 보존).

**Maxi 결정 (2026-06-05 도메인 grill)**
- 허용 명단 모델 + Reporter/Assignee 예외 = **Jira Cloud 방식 그대로**.
- 작업 범위 = 백엔드 먼저 D1~D5 (UI/E2E는 후속).
- 멤버 타입 = **그룹(Group) 기반**을 원함 → 그러나 BTS에 그룹 인프라 전무.
- 최종 결정 = **사용자 그룹 FR을 먼저 등재·구현하고, FR-PM-06은 그 뒤로 보류**.
  PR #86 + worktree는 **보류 상태로 유지**(폐기 안 함).

**Ground-truth (재개 시 출발점)**
- `IssuePermission`(shared-kernel, 7종: BROWSE/VIEW/CREATE/UPDATE/TRANSITION/SOFT_DELETE/HARD_DELETE)
  + `IssueScope`(Global/Project/Issue). 카운트 가드: shared-kernel `IssueScopeTest`(hasSize(7)).
- prod 판정기 `IdentityAccessIssuePermissionResolver`(@Profile prod): 이슈 단건 스코프에서
  **키 접두사로 projectId만 해석**, 이슈 데이터(보안수준 값)는 안 읽음 → per-issue 판정 시
  이슈의 security_level_id를 cross-BC로 읽는 경로 신설 필요.
- non-prod `AlwaysAllowIssuePermissionResolver`(@Profile !prod, 항상 true).
- 권한 검사: `IssueApplicationService.assertPermission` / `assertViewIssueOrNotFound`(404 존재숨김).
  listIssues=BROWSE/Project, findByKey=VIEW/Issue.
- listIssues jOOQ 필터: `IssueRepository.listWithType` (activeInProject 조건) — per-issue 술어 추가 지점.
- 역할 모델: `ProjectRole`(PROJECT_ADMIN/MEMBER 2종)뿐. SDD §12.4 예시("임원만") 표현 불가.
- 권한 시드: V008/V009/V013/V014, `PermissionSchemaMigrationTest` count=12. 다음 V번호=V015(주의: 동시 브랜치).
- issues 테이블: security_level 비슷한 컬럼 없음(신설 필요). init_codegen.sql 미러 필수.
- 그룹: `user_external_accounts.groups`(LDAP 동기화 JSON 문자열 목록)만 존재. 진짜 그룹 엔티티/
  멤버십/CRUD 없음. 로컬 가입 사용자는 그룹 0.

**재개 설계 방향 (그룹 FR 완료 후)**
- 멤버 타입을 `member_type` + `member_value` **다형**으로 설계 → 그룹은 GROUP 타입 한 줄 추가로 수용.
  이번 가능 타입: 특정 사용자 / 프로젝트 역할 / 보고자 / 담당자.
- security_levels(프로젝트별 등급) + issues.security_level_id + 다형 멤버 테이블.
- VIEW_ISSUE 매트릭스 통과 **AND** 보안수준 멤버십 통과 → 미통과 시 404(FR-PM-05 D2 일관).

### 관련

- 선행 ADR: docs/decisions/2026-06-05-issue-browse-view-permission.md (FR-PM-05 §D4가 본 FR로 위임)
- 후속 의존: 신규 "사용자 그룹" FR (먼저 등재·구현)

## 스펙 (← /bts-spec Phase A 채움)

### ▶️ 재개 (2026-06-06) — 그룹 인프라(FR-PM-09 PR #88) 흡수 후 spec 작성

전체 스펙. [docs/specs/2026-06-06-fr-pm-06-issue-security-level.md](../specs/2026-06-06-fr-pm-06-issue-security-level.md)

**Maxi 결정(2026-06-06 도메인 grill)**: **Jira Cloud Issue Security와 동일**.
- 구조 = 이슈 보안 스킴 → 보안 등급 → 등급 멤버 (스킴 계층 포함).
- 멤버 타입 5종 = REPORTER / ASSIGNEE / USER / PROJECT_ROLE / **GROUP**(FR-PM-09 소비).
- 스킴·등급·멤버 관리 = SYSTEM_ADMIN(isSystemAdmin 재사용). 프로젝트 스킴 적용 = PROJECT_ADMIN.
- 이슈 등급 지정 = **SET_ISSUE_SECURITY 전용 권한 신설**(role_permissions 시드 12→13).
- 판정 = identity-access resolver 확장. 등급 멤버만 VIEW, 미통과 404. **관리자 우회 없음**(Jira 동일).
- 등급 없는 이슈 = 기존 VIEW 매트릭스만(Browse/View 통과자 모두).

**2 PR 분할 확정(2026-06-06 Maxi)**.
- **PR-A(이번 PR #86) = identity-access 관리 인프라** — V016(schemes/levels/members/project-scheme)+SET_ISSUE_SECURITY 시드 + 도메인/Repository/Service + 스킴·등급·멤버 CRUD API + 프로젝트 스킴 적용 API. issues 컬럼·판정 결선 제외.
- **PR-B(후속) = issue-tracking 결선** — issues.security_level_id+init_codegen + 이슈 지정 API + IssueSecurityLookup 포트 + resolver 판정 확장 + 목록 필터.

## Brainstorming Check (← /bts-spec Phase B 채움)

✅ 통과 (적대적 sanity check, EC 12건). Maxi 결정 gap 2건 해소(관리자 우회 없음 / 2 PR 분할). 핵심 위험 인계 — cross-BC 순서 의존(판정 PR-B로 분리), 권한 시드 카운트 가드(12→13), non-prod 마스킹(판정은 PR-B prod 통합), 명세 변경 전수 동기화(SDD §12.4).

## Plan (← /bts-plan 채움) — PR-A (identity-access 관리 인프라)

> 패키지 베이스: `com.atlas.bts.identity.issuesecurity`(도메인/리포/서비스), `com.atlas.bts.identity.web`(컨트롤러).
> 전 task identity-access 단일 모듈 → 같은 test 컴파일 단위 공유(wave 직렬화 요인, 격리 gradle home로 경합 회피). 검증 경로 `:modules:identity-access`.
> **이번 PR 범위 = 관리 인프라만. issues.security_level_id·이슈지정·판정 결선은 PR-B 후속(범위 밖).**

### Ground-truth 앵커 (구현 참조 — FR-PM-09/PR #88 동형)

- 도메인 팩토리 패턴: `identity/group/UserGroup.kt`(create + require 불변식, id?/타임스탬프 nullable).
- Repository 패턴: `identity/group/JdbcUserGroupRepository.kt`(NamedParameterJdbcTemplate, RETURNING은 단건 INSERT/UPDATE만, 멱등 멤버는 `jdbc.update`+ON CONFLICT DO NOTHING).
- 컨트롤러/actor: `identity/web/UserGroupController.kt`·`ProjectMemberController.kt`(`@PreAuthorize("isAuthenticated()")`, `resolveActor(jwt)`→userId, 에러 envelope `mapOf("error" to "<snake_case>")` 소문자 key).
- SYSTEM_ADMIN 가드: `shared-kernel/.../SystemPermissionResolver.kt`(`isSystemAdmin(UUID)`), 구현 `IdentityAccessSystemPermissionResolver`(@Profile 없음, 전 프로파일 실판정).
- PROJECT_ADMIN 판정: `identity/project/ProjectMembershipRepository.kt`(`findByProjectAndUser(projectId,userId)→ProjectMembership.role`), `identity/project/ProjectRole.kt`(PROJECT_ADMIN/MEMBER). 프로젝트 키→id: `identity/project/ProjectDirectory.kt`(`resolveKeyToId(key)→UUID?`).
- 권한 시드: `V008~V014`, `PermissionSchemaMigrationTest`(현재 count=12). 다음 마이그레이션=**V016**(머지 직전 재확인).
- 통합테스트 부팅 레시피: `identity/web/UserGroupIntegrationTest.kt`(@ActiveProfiles prod + RANDOM_PORT + PEM 키 + OAuth2 exclude + LDAP @MockBean 5종 + loginJwt 실로그인). raw SQL만(jOOQ/init_codegen 불요).

### 함정 회피 메모 (메모리 교훈)

- **권한 시드 카운트 가드**(`fr-pm-permission-seed-migration-test-coupling`): SET_ISSUE_SECURITY 시드 시 `PermissionSchemaMigrationTest` count 12→13 동반 갱신. 모듈 전체 test로만 표면화.
- **조인테이블 FK CASCADE**(`join-table-fk-cascade`): levels/members FK에 ON DELETE CASCADE 명시(스킴 삭제→등급→멤버 연쇄). project_issue_security_schemes는 scheme FK ON DELETE RESTRICT(적용 중 스킴 삭제 차단).
- **V번호 동시 브랜치 충돌**(`migration-vnumber-concurrent-branch-collision`): 머지 직전 V016 재확인.
- **prod+RANDOM_PORT 부팅**(`identity-access-prod-randomport-boot-recipe`): UserGroupIntegrationTest 레시피 복제.
- **에러 envelope BC별 상이**(`frontend-api-convention-per-bc`): identity-access는 `{error:소문자코드}`.
- **도메인 우회 금지**(`patch-merge-domain-bypass`): 서비스가 도메인 create/검증 팩토리 경유, DTO 검증은 1차 방어뿐.
- **enum 추가 cross-module 카운트 가드**(`enum-add-breaks-crossmodule-count-guard`): MemberType은 identity-access 신규 enum(타 모듈 카운트 가드 무관) — 단 신규 enum 추가 시 자체 카운트 테스트만.

- **관련 ADR**: `docs/decisions/2026-06-06-issue-security-level-scheme-model.md`(생성 대상 — SDD §12.4 단순 allowedRoles 모델 → Jira식 스킴 구조 결정, T2 또는 docs 동기화에서).

### Task 1. 도메인 — IssueSecurityScheme/IssueSecurityLevel/SecurityLevelMember + MemberType + 검증

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecurityScheme.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecurityLevel.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/SecurityLevelMember.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecurityDomainTest.kt`]
- depends-on: []

**RED**: `IssueSecurityDomainTest` — (a) `IssueSecurityScheme.create(name,description)` trim/blank→IAE/255자 초과→예외. (b) `IssueSecurityLevel.create(schemeId,name,description,isDefault)` 검증. (c) `SecurityLevelMember.create(levelId,memberType,memberValue)` 다형 검증 — USER/GROUP은 memberValue가 UUID 형식, PROJECT_ROLE은 `PROJECT_ADMIN`|`MEMBER`, REPORTER/ASSIGNEE는 memberValue=null이어야 함(동반 시 IAE). `MemberType` enum 5종. (실패: 클래스 없음)
**GREEN**: 3 data class(id?/타임스탬프 nullable 불변) + `MemberType`(REPORTER/ASSIGNEE/USER/PROJECT_ROLE/GROUP) + companion `create`(require 불변식, UserGroup.create 패턴). 멤버 다형 검증은 memberType별 memberValue 규칙을 도메인에서 강제(우회 금지).
**REFACTOR**: 길이 상수 + KDoc(한 줄 역할).
**검증**: `./gradlew :modules:identity-access:test --tests '*IssueSecurityDomainTest'`

### Task 2. 마이그레이션 V016 — 4 테이블 + SET_ISSUE_SECURITY 시드 + 스키마/카운트 검증

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V016__issue_security_levels.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecuritySchemaMigrationTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/PermissionSchemaMigrationTest.kt`]
- depends-on: []

**RED**: (a) `IssueSecuritySchemaMigrationTest`(@ActiveProfiles prod + Testcontainers) — 4 테이블 존재, scheme.name UNIQUE, level (scheme_id,name) UNIQUE, 스킴당 is_default 최대 1(부분 유니크 인덱스), 스킴 삭제→levels→members CASCADE, member_type CHECK 제약, project_issue_security_schemes scheme FK RESTRICT. (b) `PermissionSchemaMigrationTest` count **12→13** 갱신 + SET_ISSUE_SECURITY 행(PROJECT_ADMIN) 존재 단언. (실패: 테이블/시드 없음)
**GREEN**: V016 SQL(스펙 §데이터 모델 identity-access 블록 — schemes/levels/level_members/project_issue_security_schemes + uq_security_level_one_default 부분 유니크 + SET_ISSUE_SECURITY role_permissions 시드 PROJECT_ADMIN). raw SQL만(init_codegen 불요).
**REFACTOR**: SQL 주석(한 줄 역할).
**검증**: `./gradlew :modules:identity-access:test --tests '*IssueSecuritySchemaMigrationTest' --tests '*PermissionSchemaMigrationTest'`

### Task 3. Repository — IssueSecuritySchemeRepository (스킴/등급/멤버 raw SQL)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecuritySchemeRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/JdbcIssueSecuritySchemeRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/issuesecurity/JdbcIssueSecuritySchemeRepositoryIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**: `Jdbc...IntegrationTest`(Testcontainers) — 스킴 create/findById/findAll(+levels)/update/delete(CASCADE), 등급 addLevel/listLevels/updateLevel/deleteLevel, 멤버 addMember(다형, 중복 멱등 ON CONFLICT)/listMembers/removeMemberById, name 중복→DuplicateKeyException 전파, isDefault 둘째 등급 추가 시 부분유니크 위반. (실패: 클래스 없음)
**GREEN**: 포트 인터페이스 + `@Repository` Jdbc 구현(NamedParameterJdbcTemplate). 스킴 aggregate(스킴+등급+멤버) 한 Repository. RETURNING은 단건 create/update만, 멤버 멱등 추가는 `jdbc.update`(ON CONFLICT DO NOTHING). findAll levels는 스칼라 서브쿼리/배치(cartesian 회피 `cartesian-product-jooq-leftjoin-count`).
**REFACTOR**: SQL 상수 + rowMapper 분리.
**검증**: `./gradlew :modules:identity-access:test --tests '*JdbcIssueSecuritySchemeRepositoryIntegrationTest'`

### Task 4. Repository — ProjectSecuritySchemeRepository (프로젝트 스킴 적용)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/ProjectSecuritySchemeRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/JdbcProjectSecuritySchemeRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/issuesecurity/JdbcProjectSecuritySchemeRepositoryIntegrationTest.kt`]
- depends-on: [2]

**RED**: `Jdbc...IntegrationTest`(Testcontainers) — assign(projectId,schemeId) upsert(프로젝트당 0~1, 교체=덮어쓰기), findByProject, unassign(멱등), scheme FK RESTRICT(적용 중 스킴 삭제 차단 확인은 T3 영역과 교차→여기선 assign/find/unassign만). (실패: 클래스 없음)
**GREEN**: 포트 + Jdbc 구현. assign은 `INSERT ... ON CONFLICT (project_id) DO UPDATE`(프로젝트당 단일). project_id는 cross-BC 참조(FK 없음).
**REFACTOR**: SQL 상수.
**검증**: `./gradlew :modules:identity-access:test --tests '*JdbcProjectSecuritySchemeRepositoryIntegrationTest'`

### Task 5. Service — IssueSecuritySchemeService (스킴/등급/멤버 CRUD + 예외)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecuritySchemeService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecurityExceptions.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/issuesecurity/IssueSecuritySchemeServiceTest.kt`]
- depends-on: [3]

**RED**: `...ServiceTest`(mockk repo + UserGroupRepository + ProjectMembershipRepository) — name 중복→SchemeNameConflict, 없는 스킴/등급→NotFound, 멤버 추가 시 USER/GROUP memberValue 실재 사전조회(없으면 404 UserNotFound/GroupNotFound), PROJECT_ROLE/REPORTER/ASSIGNEE 검증, 멱등. (실패: 클래스 없음)
**GREEN**: `@Service @Transactional` — repo 위임 + 도메인 create 경유(우회 금지) + USER/GROUP 멤버 실재 사전조회 + DuplicateKeyException→도메인 예외. 예외 클래스(SchemeNotFound/SchemeNameConflict/LevelNotFound/LevelNameConflict/MemberTypeInvalid/MemberValueInvalid).
  - **실재 사전조회 정정(code-reviewer CONCERN)**: `UserRepository`엔 `existsById` **없음** → `userRepository.findById(value) != null` 사용(공유 `UserRepository` 인터페이스 미수정 — 새 메서드 추가 시 JDBC 구현+기존 mock 파급 `plan-files-constructor-injection-existing-tests`). GROUP은 `userGroupRepository.existsById(uuid)` 실재.
**REFACTOR**: 가독성 + KDoc.
**검증**: `./gradlew :modules:identity-access:test --tests '*IssueSecuritySchemeServiceTest'`

### Task 6. Service — ProjectSecuritySchemeService (프로젝트 적용 + PROJECT_ADMIN 판정)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/ProjectSecuritySchemeService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/issuesecurity/ProjectSecuritySchemeServiceTest.kt`]
- depends-on: [4, 5]

**RED**: `...ServiceTest`(mockk ProjectSecuritySchemeRepository + ProjectDirectory + ProjectMembershipRepository + SchemeRepository) — assign 시 (a) 프로젝트 키→id 해석(없으면 404) (b) actor가 그 프로젝트 PROJECT_ADMIN 아니면 거부(403 의미) (c) 없는 스킴→404 (d) unassign/find 동일 가드. (실패: 클래스 없음)
**GREEN**: `@Service @Transactional` — `ProjectDirectory.resolveKeyToId` + `ProjectMembershipRepository.findByProjectAndUser`로 role==PROJECT_ADMIN 판정(역할 직접 체크 — ADMIN_PROJECT 권한코드 미시드이므로 매트릭스 대신 역할, ground-truth 정합) + scheme 실재 확인. 거부는 도메인 예외(ProjectSchemeAccessDenied).
**REFACTOR**: 가드 헬퍼 + KDoc.
**검증**: `./gradlew :modules:identity-access:test --tests '*ProjectSecuritySchemeServiceTest'`

### Task 7. Controller — 스킴/등급/멤버 관리 API + SYSTEM_ADMIN 가드 + DTO

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/IssueSecuritySchemeController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/IssueSecuritySchemeControllerTest.kt`]
- depends-on: [5]

**RED**: `...ControllerTest`(MockMvc, mock service + SystemPermissionResolver) — 스킴/등급/멤버 관리 엔드포인트(스펙 §API 관리 블록) 라우팅/상태코드, 미인증 401, isSystemAdmin=false 403, 도메인 예외→404/409/400(snake_case `{error:...}`). (실패: 클래스 없음)
**GREEN**: `@RestController`(`/api/v1/issue-security-schemes`·`/api/v1/issue-security-levels`·`/api/v1/issue-security-level-members`, `@PreAuthorize("isAuthenticated()")`) — `resolveActor(jwt)`→`isSystemAdmin` false 403 → service. DTO + 인라인 `mapServiceException`(envelope `{error:코드}`). SecurityConfig 무변경(/api/** authenticated 자동 커버).
**REFACTOR**: DTO 매핑 헬퍼 + KDoc.
**검증**: `./gradlew :modules:identity-access:test --tests '*IssueSecuritySchemeControllerTest'`

### Task 8. Controller — 프로젝트 스킴 적용 API + PROJECT_ADMIN 가드

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/ProjectSecuritySchemeController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/ProjectSecuritySchemeControllerTest.kt`]
- depends-on: [6]

**RED**: `...ControllerTest`(MockMvc, mock ProjectSecuritySchemeService) — `PUT/DELETE/GET /api/v1/projects/{key}/issue-security-scheme` 라우팅, 미인증 401, 비-PROJECT_ADMIN 403, 없는 프로젝트/스킴 404. **actor 추출이 프로젝트 조회보다 먼저**(미인증자 404 probe 차단, `auth-extraction-before-resource-lookup` 교훈). (실패: 클래스 없음)
**GREEN**: `@RestController`(`@PreAuthorize("isAuthenticated()")`) — resolveActor 먼저 → service(내부 PROJECT_ADMIN 판정) 위임. DTO + envelope `{error:코드}`.
**REFACTOR**: KDoc.
**검증**: `./gradlew :modules:identity-access:test --tests '*ProjectSecuritySchemeControllerTest'`

### Task 9. prod 통합테스트 — 관리 end-to-end ground-truth (S1~S7, 권한 거부)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/IssueSecurityIntegrationTest.kt`]
- depends-on: [7, 8]

**RED**: `IssueSecurityIntegrationTest`(@ActiveProfiles prod + RANDOM_PORT + TestRestTemplate) — SYSTEM_ADMIN 시드 후 스킴/등급/멤버 CRUD(S1~S5), PROJECT_ADMIN 시드 후 프로젝트 스킴 적용/해제(S6~S7), 비-SYSTEM_ADMIN 관리 호출 403(ground-truth 마스킹 없음), 비-PROJECT_ADMIN 적용 403, 미인증 401, 스킴 CASCADE. (실패: 시나리오 미구현)
**GREEN**: `UserGroupIntegrationTest` 부팅 레시피 복제(PEM 키 + OAuth2 exclude + LDAP @MockBean 5종 + loginJwt 실로그인 admin). **+ S6/S7용 추가 시드(code-reviewer NIT)**: UserGroup 레시피는 SYSTEM_ADMIN(`roleAssignmentRepository.assign`)만 시드 → 프로젝트 스킴 적용 시나리오는 `projects` 행 INSERT + `project_memberships` PROJECT_ADMIN 행 시드 필요(`ProjectMemberFlowIntegrationTest` 선례 참조). 비-PROJECT_ADMIN 둘째 사용자로 403 ground-truth. TestRestTemplate Bearer.
**REFACTOR**: 시나리오 헬퍼.
**검증**: `./gradlew :modules:identity-access:test --tests '*IssueSecurityIntegrationTest'`

### Task 10. 명세 동기화 — ADR 생성 + SDD §12.4 재작성 (머지 전 필수, TDD 무관)

**메타**.
- agent: `security-engineer` (또는 controller 직접)
- files: [`docs/decisions/2026-06-06-issue-security-level-scheme-model.md`, `docs/sdd/12-permissions.md`, `docs/plan/product/identity-access.md`]
- depends-on: []  # 코드 무관, 머지 전 언제든. 단 게이트2 전 완료 필수.

**작업**(code-reviewer CONCERN — 명세 deviation은 이 PR의 real deliverable):
- **ADR 신규**: `docs/decisions/2026-06-06-issue-security-level-scheme-model.md` — SDD §12.4 단순 `allowedRoles: List<Long>` 모델 → Jira식 스킴 구조(스킴→등급→멤버 다형 5타입) 결정 + 관리자 우회 없음 + 2 PR 분할 근거. (현재 파일 부재 — 반드시 생성.)
- **SDD §12.4 재작성**: `IssueSecurityLevel(id, projectId, name, description, allowedRoles)` → 스킴 계층(IssueSecurityScheme/IssueSecurityLevel(schemeId)/SecurityLevelMember(memberType,memberValue)) + UUID 체계 + 프로젝트-스킴 적용. SET_ISSUE_SECURITY 권한코드 §12.3 추가.
- **product/identity-access §4.6**: D1~D5 체크박스 + PR-A 완료 마킹(머지 시).
- **검증**: `bash scripts/verify-master-plan.sh` 통과(FR 카운트 121 불변, FR-PM-06 기존 — 카운트 drift 없음).

## Plan 메타

- task 수: 9 (코드 TDD) + T10(명세 동기화, TDD 무관, 머지 전)
- depends-on 그래프: T1[], T2[] → T3[1,2], T4[2] → T5[3], T6[4,5] → T7[5], T8[6] → T9[7,8]
- wave(예상): W1(T1,T2) → W2(T3,T4) → W3(T5) → W4(T6,T7) → W5(T8) → W6(T9). 단일 모듈 test 컴파일 공유 → 격리 gradle home로 경합 회피(`bts-plan-wave-gradle-module-compile`).
- TDD 강제: yes (test 커밋 먼저)
- 추가 검증: 모듈 전체 `:modules:identity-access:test` + ktlintMain/TestSourceSetCheck + detekt(--rerun-tasks). **PermissionSchemaMigrationTest 12→13 갱신 포함**.
- agent: T2=db-engineer(마이그레이션), 그 외 security-engineer
- 명세 변경 전수 동기화(머지 전): SDD §12.4 + product/identity-access §4.6 D단계 + ADR 생성. FR 카운트 불변(121).

## 리뷰 결과

### code-reviewer ground-truth 적대적 plan 리뷰 (2026-06-06)

실제 코드 대조. 핵심 앵커(SystemPermissionResolver.isSystemAdmin·@Profile 없음, IdentityAccessSystemPermissionResolver, ProjectMembershipRepository.findByProjectAndUser→role, ProjectRole PROJECT_ADMIN/MEMBER, ProjectDirectory.resolveKeyToId, UserGroup.create, JdbcUserGroupRepository ON CONFLICT 패턴, UserGroupController resolveActor·`{error:소문자}`, UserGroupIntegrationTest prod+RANDOM_PORT 레시피, V015→V016, init_codegen 불요, PermissionSchemaMigrationTest count=12) **전부 실재 확인 — 환각 0**.

**BLOCKER: 없음.** 게이트1 진입 가능(아래 3건 plan 반영 완료).

**CONCERN 3 (2건 plan 반영, 1건 Maxi 확인)**:
- C1. T5 `UserRepository.existsById` 앵커 오류 — 실제 미존재(findById/findByIds만). → **반영**: T5 GREEN을 `userRepository.findById(value)!=null`로 정정(공유 인터페이스 미수정, plan-files 파급 회피). GROUP은 existsById 실재.
- C2. T6 PROJECT_ADMIN 판정을 role 직접 체크(ProjectMembership.role==PROJECT_ADMIN)로 — FR-PM-04는 MANAGE_WORKFLOW 매트릭스 사용. ADMIN_PROJECT 권한코드가 role_permissions에 **미시드**(V008~V014 grep 확인)라 매트릭스 불가 → 역할 직접 체크 정당. 단 "PROJECT_ADMIN 게이트 메커니즘 2종 공존"은 **게이트1 Maxi 확인 항목**.
- C3. SDD §12.4 재작성 + ADR 생성이 이 PR의 real deliverable(ADR 파일 부재 확인). → **반영**: T10(명세 동기화) 명시 task 추가, verify-master-plan 통과 조건.

**NIT 2 (반영)**:
- N1. T9 prod 통합테스트에 projects + PROJECT_ADMIN 멤버십 시드 필요(S6/S7) → T9 GREEN 명시(ProjectMemberFlowIntegrationTest 참조).
- N2. W1(T1,T2)/W4(T6,T7) 같은 worktree 동시 커밋 → own-files-only staging(`parallel-dispatch-precommit-hook-race`). bts-impl controller가 git log로 task별 test→feat 순서 검증.

**확인된 정합**: V016 정확(feat/fr-cm-04가 identity 마이그레이션 미접촉), 카운트 12→13(SET_ISSUE_SECURITY PROJECT_ADMIN only +1), MemberType은 cross-module 카운트 가드 무관(IssueScopeTest는 IssuePermission 7종 전용), profile-scoped-bean-boot-failure 비유발(신규 빈 전부 무조건 등록, prod-scoped resolver는 PR-B), 의존 그래프 무순환, 다형 멤버 도메인 검증+DB CHECK 2중.
