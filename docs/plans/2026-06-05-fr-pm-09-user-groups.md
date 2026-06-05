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
**GREEN**: `UserGroup`(id?/name/description/createdAt/updatedAt 불변 data class) + companion `create`/정규화(`require` 불변식). `ProjectMembership` data class 패턴 따름.
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
**GREEN**: 포트 인터페이스(create/findById/findAll/update/delete/addMember/removeMember/listMemberIds/existsById) + `@Repository` Jdbc 구현(NamedParameterJdbcTemplate, RETURNING, ON CONFLICT, memberCount 스칼라 서브쿼리). `JdbcSystemRoleAssignmentRepository` 패턴.
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
**GREEN**: `@RestController` UserGroupController(`/api/v1/groups`, `@PreAuthorize("isAuthenticated()")`) — `resolveActor(jwt)`(ProjectMemberController 패턴) → `systemPermissionResolver.isSystemAdmin(actorId)` false면 403 → service 위임. 요청/응답 DTO(GroupResponse/CreateGroupRequest/UpdateGroupRequest, UserSummaryResponse 재사용) + 인라인 `mapServiceException`.
**REFACTOR**: DTO 매핑 헬퍼 + KDoc.
**검증**: `./gradlew :modules:identity-access:test --tests '*UserGroupControllerTest'`

### Task 6. prod 통합테스트 — end-to-end ground-truth (S1~S8, 멱등, CASCADE)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/UserGroupIntegrationTest.kt`]
- depends-on: [5]

**RED**: `UserGroupIntegrationTest`(@ActiveProfiles prod + Testcontainers + RANDOM_PORT, TestRestTemplate) — SYSTEM_ADMIN 시드 후 그룹 생성/조회/수정/삭제 + 멤버 추가(멱등)/제거(멱등)/목록, 비관리자 403(ground-truth, non-prod 마스킹 없음 확인), 미인증 401, name 중복 409, 그룹 삭제 시 멤버십 CASCADE. (실패: 시나리오 미구현분 있으면)
**GREEN**: 통합테스트 작성(SystemAdminInfraIntegrationTest 시드 패턴 — users INSERT 후 SystemRoleAssignmentRepository.assign).
**REFACTOR**: 시나리오 헬퍼 정리.
**검증**: `./gradlew :modules:identity-access:test --tests '*UserGroupIntegrationTest'`

## Plan 메타

- task 수: 6
- depends-on 그래프: T1[], T2[] → T3[1,2] → T4[3] → T5[4] → T6[5]
- wave: W1(T1,T2 병렬) → W2(T3) → W3(T4) → W4(T5) → W5(T6). 단일 모듈이라 test 컴파일 공유 → bts-impl이 격리 gradle home로 경합 회피(`bts-plan-wave-gradle-module-compile`/FR-PM-05 N1)
- TDD 강제: yes (test 커밋 먼저)
- 추가 검증: 모듈 전체 `:modules:identity-access:test` + ktlintMain/TestSourceSetCheck + detekt(--rerun-tasks). 신규 enum/시드 0 → 카운트 가드 비영향 재확인.
- agent: T2=db-engineer(마이그레이션), 그 외 security-engineer

## 리뷰 결과 (← /bts-review-plan 채움)
