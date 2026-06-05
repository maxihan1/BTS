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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
