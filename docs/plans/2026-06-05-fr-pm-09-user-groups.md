# FR-PM-09 — 사용자 그룹 (전역 그룹 인프라)

> slug: fr-pm-09-user-groups
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-05

## Brief

FR-PM-09 사용자 그룹 백엔드 인프라. 전역(시스템 단위) `UserGroup` + `group_memberships`(사용자↔그룹 N:M),
SYSTEM_ADMIN(FR-PM-08)만 관리하는 그룹 CRUD + 멤버 추가/제거 API. 백엔드 인프라만(D1~D5).

BTS에 그룹 개념 전무(`user_external_accounts.groups` LDAP 문자열 목록만 존재, 로컬 가입자는 그룹 0)해
FR-PM-06(이슈 보안 수준, 그룹 기반 멤버)이 막혀 있음 → 이 인프라가 선행 해소.

- SDD 정본: docs/sdd/12-permissions.md §12.6.1
- plan 추적: docs/plan/product/identity-access.md §4.9
- 범위: 전역 그룹 / 네이티브 먼저(LDAP 동기화 후속) / 백엔드 인프라만(관리 UI/E2E 후속)
- 관리 주체: SYSTEM_ADMIN (FR-PM-08 SystemPermissionResolver 재사용)
- 단계: D1 도메인 / D2 명세 / D3 데이터모델(user_groups, group_memberships, V015) /
  D4 백엔드(Repository + 관리 API + SYSTEM_ADMIN 가드) / D5 백엔드 테스트

## 도메인 정리

- **BC**: identity-access (소유). security-engineer.
- **영향 엔티티(신규)**: `UserGroup`(전역, name 유니크), `GroupMembership`(group×user N:M).
- **새 용어**: "사용자 그룹 / User Group" — 여러 사용자를 묶은 전역 재사용 단위. glossary 추가 대상(Maxi 승인 대기).
- **기존 결정 충돌**: 없음. 기존 enum/포트/시드 무변경.

### 도메인 결정 (ADR 고정)

- D1. 전역 그룹(프로젝트 무관), name 전역 UNIQUE.
- D2. `group_memberships(group_id, user_id)` N:M, 복합 PK, 두 FK ON DELETE CASCADE, 멤버추가 ON CONFLICT DO NOTHING(멱등).
- D3. 관리=SYSTEM_ADMIN. **기존 `SystemPermissionResolver.isSystemAdmin`(shared-kernel, FR-PM-08) 재사용 — 신규 포트/권한코드 0**. 컨트롤러가 actor 추출 후 가드.
- D4. 영속=raw SQL(NamedParameterJdbcTemplate). identity-access는 jOOQ 미사용 → init_codegen 미러 불요.
- D5. 마이그레이션 V015(머지 직전 V번호 재확인).
- D6. 범위=순수 인프라(엔티티+멤버십+CRUD API). 소비처(보안수준/권한스킴/멘션) 결선·관리 UI/E2E는 후속.
- D7. LDAP 그룹 동기화 후속 FR.

### Ground-truth 앵커 (구현 참조)

- 포트: `shared-kernel/.../SystemPermissionResolver.kt` (`isSystemAdmin(UUID): Boolean`)
- 구현: `identity-access/.../IdentityAccessSystemPermissionResolver.kt` (@Profile 없음, 전 프로파일 실판정)
- 컨트롤러 패턴: `identity-access/.../web/ProjectMemberController.kt` (`@RequestMapping("/api/v1/...")`, `resolveActor(jwt)` JWT subject+PAT, 인라인 `mapServiceException`)
- Repository 패턴: `identity-access/.../systemrole/JdbcSystemRoleAssignmentRepository.kt` (NamedParameterJdbcTemplate, ON CONFLICT DO NOTHING, RETURNING)
- 도메인 패턴: `identity-access/.../project/ProjectMembership.kt` (불변 data class)
- users PK: `V001__users.sql` (id UUID gen_random_uuid())
- 최신 마이그레이션: V014. 다음=V015.
- 통합테스트 패턴: `identity-access/.../systemrole/SystemAdminInfraIntegrationTest.kt` (@ActiveProfiles prod + Testcontainers, users 시드 후 role assign)

### 함정 회피 메모 (메모리 교훈)

- SystemPermissionResolver는 @Profile 없음 → 테스트에서 SYSTEM_ADMIN 실제 시드 필수(마스킹 없음). prod-only 포트 신규 도입 안 함 → `profile-scoped-bean-boot-failure` 비유발.
- 조인테이블 FK CASCADE 누락 시 공유 Testcontainers cleanup 연쇄(`join-table-fk-cascade`) → CASCADE 명시.
- V번호 동시 브랜치 충돌(`migration-vnumber-concurrent-branch-collision`) → 머지 직전 재확인.
- enum/시드 무변경 → cross-module 카운트 가드·PermissionSchemaMigrationTest 비영향.

- **관련 ADR**: [docs/decisions/2026-06-05-user-groups.md](../decisions/2026-06-05-user-groups.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-05-fr-pm-09-user-groups.md](../specs/2026-06-05-fr-pm-09-user-groups.md)

핵심 요약.
- 전역 `UserGroup`(name 유니크) + `group_memberships`(N:M, FK CASCADE, 멱등) — V015 raw SQL.
- 관리 API 8종(그룹 CRUD + 멤버 추가/제거/목록), 모두 SYSTEM_ADMIN. 가드=DB 기반 `isSystemAdmin` 수동 호출.
- 멱등(중복추가/없는멤버제거 204), 없는 그룹/사용자 404, name 중복 409, 미인증 401/비관리자 403.
- 범위 밖: 감사로그(FR-AU-10), 소비처 결선(FR-PM-06 등), 관리 UI/E2E, LDAP 동기화.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 3건 보강 — (A) 감사 로그 범위 밖 명시, (B) 권한 가드를 DB 기반 isSystemAdmin 수동 호출로 확정(@PreAuthorize hasRole 비사용, PAT 일관), (C) 멤버 목록 페이지네이션 후속 플래그. Maxi 결정 필요 gap 0.

## Plan

> 패키지 베이스: `com.atlas.bts.identity.group`(도메인/리포/서비스), `com.atlas.bts.identity.web`(컨트롤러).
> 전 task identity-access 단일 모듈 → 같은 test 컴파일 단위 공유(wave 직렬화 요인). 검증 경로 `:modules:identity-access`.

### Task 1. 도메인 — UserGroup data class + name 정규화/검증

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/group/UserGroup.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/group/UserGroupTest.kt`]
- depends-on: []

**RED**: `UserGroupTest` — `UserGroup.create(name, description)`가 name trim, blank→`IllegalArgumentException`, 255자 초과→예외, description 500자 초과→예외. (실패: 클래스 없음)
**GREEN**: `UserGroup`(id?/name/description/createdAt/updatedAt 불변 data class) + companion `create`/정규화(`require` 불변식). **필드 불변(val) 형태만 `ProjectMembership` 참조**. 단 `ProjectMembership`엔 create/require 팩토리가 없음(검증은 서비스 분산) → **trim/length require는 신규 도입(identity-access 첫 도메인 팩토리)**, `patch-merge-domain-bypass` 교훈대로 서비스가 이 팩토리 경유(우회 금지). (C1)
**REFACTOR**: 길이 상수(MAX_NAME=255, MAX_DESC=500) 추출 + KDoc(한 줄 역할 주석).
**검증**: `./gradlew :modules:identity-access:test --tests '*UserGroupTest'`

### Task 2. 마이그레이션 V015 — user_groups + group_memberships (+ CASCADE 검증)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V015__user_groups.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/group/UserGroupSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `UserGroupSchemaMigrationTest`(@ActiveProfiles prod + Testcontainers) — user_groups/group_memberships 존재, name UNIQUE 위반 시 예외, 그룹 삭제→멤버십 CASCADE 삭제, 사용자 삭제→멤버십 CASCADE 삭제. (실패: 테이블 없음)
**GREEN**: V015 SQL(스펙 §데이터 모델 — user_groups, group_memberships 복합PK, 두 FK ON DELETE CASCADE, ix_group_memberships_user).
**REFACTOR**: SQL 주석(한 줄 역할). identity-access는 jOOQ 미사용 → init_codegen 미러 없음(확인).
**검증**: `./gradlew :modules:identity-access:test --tests '*UserGroupSchemaMigrationTest'`

### Task 3. Repository — UserGroupRepository 포트 + Jdbc 구현 (raw SQL)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/group/UserGroupRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/group/JdbcUserGroupRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/group/JdbcUserGroupRepositoryIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**: `JdbcUserGroupRepositoryIntegrationTest`(Testcontainers) — insert/findById/findAll(memberCount 스칼라 서브쿼리)/update/delete, addMember 멱등(ON CONFLICT DO NOTHING), removeMember 멱등(없는 멤버 no-op), listMembers, name UNIQUE 위반 → `DuplicateKeyException` 전파. (실패: 클래스 없음)
**GREEN**: 포트 인터페이스(create/findById/findAll/update/delete/addMember/removeMember/listMemberIds/existsById) + `@Repository` Jdbc 구현(NamedParameterJdbcTemplate, memberCount 스칼라 서브쿼리). **RETURNING은 그룹 create/update(단건 신규/확정 INSERT·UPDATE)에만 사용. addMember 멱등은 `jdbc.update`(ON CONFLICT DO NOTHING, RETURNING 없이) — DO NOTHING 시 RETURNING은 0행이라 queryForObject가 EmptyResultDataAccessException으로 깨짐(C3). `JdbcSystemRoleAssignmentRepository.assign` 선례(update+ON CONFLICT) 앵커.** removeMember도 jdbc.update(영향행 무관 멱등).
**REFACTOR**: SQL 문자열 상수 추출 + rowMapper 분리.
**검증**: `./gradlew :modules:identity-access:test --tests '*JdbcUserGroupRepositoryIntegrationTest'`

### Task 4. Application Service — CRUD 오케스트레이션 + 예외 + 멱등 + 존재검증

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/group/UserGroupService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/group/UserGroupExceptions.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/group/UserGroupServiceTest.kt`]
- depends-on: [3]

**RED**: `UserGroupServiceTest`(mockk repo + 기존 UserRepository) — 생성 시 name 중복(DuplicateKeyException)→`UserGroupNameConflictException`, 없는 그룹 수정/삭제/멤버추가→`UserGroupNotFoundException`, 없는 사용자 멤버추가→`UserNotFoundException`(사전조회), 멱등 멤버추가/제거. (실패: 클래스 없음)
**GREEN**: `@Service @Transactional` UserGroupService — repo 위임 + 도메인 `UserGroup.create` 경유(정규화 우회 방지, `patch-merge-domain-bypass` 교훈) + 그룹/사용자 존재 사전조회 + DuplicateKeyException→도메인 예외 변환. 예외 클래스(UserGroupNotFound/NameConflict/UserNotFound).
**REFACTOR**: 가독성 정리 + KDoc.
**검증**: `./gradlew :modules:identity-access:test --tests '*UserGroupServiceTest'`

### Task 5. Controller — 관리 API 8종 + SYSTEM_ADMIN 가드 + DTO

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/UserGroupController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/UserGroupControllerTest.kt`]
- depends-on: [4]

**RED**: `UserGroupControllerTest`(MockMvc, mock service + SystemPermissionResolver) — 8 엔드포인트 라우팅/상태코드, 미인증 401, isSystemAdmin=false 403, 도메인 예외→404/409/400 매핑(snake_case 에러코드). (실패: 클래스 없음)
**GREEN**: `@RestController` UserGroupController(`/api/v1/groups`, `@PreAuthorize("isAuthenticated()")`) — `resolveActor(jwt)`(ProjectMemberController 패턴, 반환 `ActorContext`에서 `.userId` 추출) → `systemPermissionResolver.isSystemAdmin(actorId)` false면 403 → service 위임. 요청/응답 DTO(GroupResponse/CreateGroupRequest/UpdateGroupRequest, UserSummaryResponse 재사용) + 인라인 `mapServiceException`. **에러 응답 body = `mapOf("error" to "<snake_case_code>")`(소문자 key `error` — ProjectMemberController/AuthController 동일, `errorCode` 아님)(C2).** **SecurityConfig 무변경 — `/api/**` 기존 `authenticated()` 규칙이 자동 커버(N2, 신규 라우트 등록 금지).**
**REFACTOR**: DTO 매핑 헬퍼 + KDoc.
**검증**: `./gradlew :modules:identity-access:test --tests '*UserGroupControllerTest'`

### Task 6. prod 통합테스트 — end-to-end ground-truth (S1~S8, 멱등, CASCADE)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/UserGroupIntegrationTest.kt`]
- depends-on: [5]

**RED**: `UserGroupIntegrationTest`(@ActiveProfiles prod + Testcontainers + RANDOM_PORT, TestRestTemplate) — SYSTEM_ADMIN 시드 후 그룹 생성/조회/수정/삭제 + 멤버 추가(멱등)/제거(멱등)/목록, 비관리자 403(ground-truth, non-prod 마스킹 없음 확인), 미인증 401, name 중복 409, 그룹 삭제 시 멤버십 CASCADE. (실패: 시나리오 미구현분 있으면)
**GREEN**: 통합테스트 작성. **부팅 설정 — prod+RANDOM_PORT 결합 선례가 없으므로 두 선례의 합집합 필수(B1)**:
- `@DynamicPropertySource`에 Testcontainers datasource + flyway enabled + **PEM 키 셋업**(`SystemAdminInfraIntegrationTest`의 `private-key-pem-path` 등록 — prod `PemFileKeyProvider`가 키 요구) 포함.
- `properties = ["spring.autoconfigure.exclude=...OAuth2ClientAutoConfiguration"]`(`ProjectMemberFlowIntegrationTest` 선례) + LDAP `@MockBean` 5종(ldapProvider/ldapProviderConfigService/externalAccountRepository/autoProvisionService/ldapTemplate).
- **관리자 토큰 경로 = `loginJwt` 실 로그인**(`ProjectMemberFlowIntegrationTest.kt:536,638` 선례 — RANDOM_PORT 필터 체인이 검증 가능한 토큰 보장): admin 사용자 users INSERT + `localCredentialService.store`로 자격 시드 → 로그인으로 JWT 획득 → `SystemRoleAssignmentRepository.assign(adminId, SYSTEM_ADMIN)`로 전역역할 부여. 비관리자용 둘째 사용자도 동일 로그인(역할 미부여)으로 403 ground-truth. TestRestTemplate `Bearer` 헤더.
**REFACTOR**: 시나리오 헬퍼 정리.
**검증**: `./gradlew :modules:identity-access:test --tests '*UserGroupIntegrationTest'`

## Plan 메타

- task 수: 6
- depends-on 그래프: T1[], T2[] → T3[1,2] → T4[3] → T5[4] → T6[5]
- wave: W1(T1,T2 병렬) → W2(T3) → W3(T4) → W4(T5) → W5(T6). 단일 모듈이라 test 컴파일 공유 → bts-impl이 격리 gradle home로 경합 회피(`bts-plan-wave-gradle-module-compile`/FR-PM-05 N1)
- TDD 강제: yes (test 커밋 먼저)
- 추가 검증: 모듈 전체 `:modules:identity-access:test` + ktlintMain/TestSourceSetCheck + detekt(--rerun-tasks). 신규 enum/시드 0 → 카운트 가드 비영향 재확인.
- agent: T2=db-engineer(마이그레이션), 그 외 security-engineer

## 리뷰 결과

### code-reviewer ground-truth 적대적 plan 리뷰 (2026-06-05)

실제 코드베이스 대조. 핵심 앵커(SystemPermissionResolver.isSystemAdmin·@Profile 없음, ProjectMemberController.resolveActor, UserRepository.findById, UserSummaryResponse, JdbcSystemRoleAssignmentRepository ON CONFLICT, SystemAdminInfraIntegrationTest 시드, V015, init_codegen 불요, /api/** authenticated, enum/시드 0) **전부 실재 확인 — 환각 없음**.

**BLOCKER 1 (구현 전 반영 완료)**
- B1. T6 `prod + RANDOM_PORT` 조합 선례 부재 → 부팅 시 prod PemFileKeyProvider 키 누락/필터체인 미충족 위험. **해소**: T6 GREEN에 PEM 키 셋업 + OAuth2ClientAutoConfiguration exclude + LDAP @MockBean 5종 + loginJwt 실로그인 관리자 토큰 경로 명시(두 선례 합집합).

**CONCERN 3 (반영 완료)**
- C1. T1 "ProjectMembership data class 패턴 따름" 오류(ProjectMembership엔 create/require 없음) → T1 GREEN 정정(필드 불변만 참조, require는 신규 도입=identity-access 첫 도메인 팩토리).
- C2. 에러 envelope key 미명시 → T5 GREEN에 `mapOf("error" to code)` 소문자 key 명시(BC별 envelope 상이 함정 `frontend-api-convention-per-bc`).
- C3. addMember 멱등을 RETURNING+queryForObject로 짜면 DO NOTHING 0행→EmptyResultDataAccessException → T3 GREEN에 멤버추가/제거는 jdbc.update(RETURNING 없이), RETURNING은 그룹 create/update만 명시.

**NIT 3**: N1 패키지 약식표기(permission 패키지, import만 정확히) / N2 SecurityConfig 무변경 명시 추가(T5) / N3 memberCount 스칼라 서브쿼리 교훈 정합(확인).

**추정**: T6 부팅 실패는 추론 — 최종 확인은 구현 후 `:modules:identity-access:test`. B1 반영으로 사전 차단.

→ BLOCKER 해소 완료. 게이트 1 진입 가능.
