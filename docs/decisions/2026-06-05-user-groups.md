# ADR: 사용자 그룹 — 전역 그룹 인프라 + SYSTEM_ADMIN 관리

> 날짜: 2026-06-05
> 상태: 채택 (FR-PM-09)
> 관련 FR: FR-PM-09 (사용자 그룹)
> 관련 BC: identity-access (소유)
> 관련 SDD: [12. 권한 모델](../sdd/12-permissions.md) §12.6.1 사용자 그룹
> 선행: FR-PM-08 [system-admin-role](2026-06-04-system-admin-role.md)
> 후행 해소: FR-PM-06 (이슈 보안 수준 — 그룹 기반 멤버)

## 맥락

FR-PM-06(이슈 보안 수준)의 도메인 grill에서, 보안 수준의 "허용 명단"을 **그룹(Group) 기반**(Jira Cloud 방식)으로 구성하기로 결정. 그러나 BTS ground-truth:

- 사용자 그룹이라는 일급 개념이 전무. `user_external_accounts.groups`(LDAP 동기화 그룹 **이름 문자열** JSON 목록)만 외부계정 행에 비정규로 존재하고, 조회 가능한 그룹 멤버십 모델이 아니다.
- 로컬 가입 사용자(FR-AU-05)는 외부계정이 없어 그룹이 아예 0.
- SDD는 그룹을 개념적으로만 명시(04장 "Identity & Access가 사용자/조직/그룹 담당", §12.2 "권한 스킴 = 권한 → 사용자/역할/그룹 매핑", §19.8 "LDAP 그룹 멤버십 변경 → 역할 자동 조정")하나 구현·테이블·CRUD 전무.

→ 그룹 인프라를 별도 FR(FR-PM-09)로 선행 등재·구현하고, FR-PM-06은 그 뒤로 보류(PR #86). 본 ADR은 FR-PM-09의 도메인 결정을 고정한다.

## 결정

### D1. 전역(시스템 단위) 그룹

`UserGroup`은 프로젝트와 무관한 전역 엔티티. 한 번 만든 그룹("임원")을 모든 프로젝트·보안수준·권한스킴이 재사용한다. SDD 04장("Identity & Access가 그룹 담당") + Jira Cloud(사이트 단위 그룹)와 정합.

```kotlin
data class UserGroup(
    val id: UUID,
    val name: String,        // 전역 유니크
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

- `name` 전역 UNIQUE(중복 그룹명 거부, 409).
- **대안(기각)**: 프로젝트별 그룹 — 같은 조직 그룹을 프로젝트마다 중복 생성해야 해 운영 부담 + SDD 비전(전역)과 어긋남.

### D2. 멤버십 = N:M 조인테이블, 멱등

`group_memberships(group_id, user_id)` — 사용자 ↔ 그룹 다대다. 복합 PK `(group_id, user_id)`.

- 두 FK 모두 `ON DELETE CASCADE`(`users(id)`, `user_groups(id)`) — 그룹/사용자 삭제 시 멤버십 자동 정리. 순수 관계테이블이라 CASCADE 표준(FR-CM-02 `join-table-fk-cascade` 교훈: 공유 Testcontainers cleanup 연쇄 방지).
- 멤버 추가 멱등 — `ON CONFLICT (group_id, user_id) DO NOTHING`(SystemRoleAssignment 선례). 중복 추가는 예외 아님.
- 멤버 제거는 멱등 — 없는 멤버 제거는 no-op(404 아님) 또는 멱등 성공(spec에서 확정).

### D3. 관리 주체 = SYSTEM_ADMIN, 기존 포트 재사용 (신규 포트 0)

그룹 CRUD + 멤버 추가/제거는 시스템 관리자 전용. **기존 `SystemPermissionResolver.isSystemAdmin(actorId)`(shared-kernel, FR-PM-08) 재사용** — 신규 권한 코드/포트 없음.

- 컨트롤러가 인증 주체(JWT subject → UUID)를 추출(ProjectMemberController `resolveActor` 패턴) → `isSystemAdmin(actorId)` false면 403.
- `IdentityAccessSystemPermissionResolver`는 **@Profile 없는 단일 빈**(전 프로파일 DB 실판정) → non-prod AlwaysAllow 마스킹 없음. 테스트는 SYSTEM_ADMIN을 실제 시드해야 통과(`profile-scoped-bean-boot-failure`/`issue-scope-global-prod-hard-deny` 함정 원천 회피 — Global 스코프나 prod-only 포트 신규 도입 없음).

### D4. 영속 = raw SQL (NamedParameterJdbcTemplate), jOOQ/init_codegen 불요

identity-access는 jOOQ codegen 미적용(init_codegen.sql 없음). 그룹 Repository도 모듈 표준대로 `NamedParameterJdbcTemplate` + SQL 문자열 상수 + `RETURNING`. `jooq-init-codegen-mirror` 함정은 issue-tracking 한정이라 본 FR 비해당.

### D5. 마이그레이션 V015

`V015__user_groups.sql`(user_groups + group_memberships). identity-access 최신은 V014. **머지 직전 V번호 재확인 필수**(`migration-vnumber-concurrent-branch-collision` — 동시 브랜치가 V015 선점 시 git mv). 현재 FR-PM-06 worktree는 마이그레이션 미커밋(보류)이라 충돌원 아님.

### D6. 범위 = 순수 인프라 (소비처 결선은 각 소비 FR)

FR-PM-09는 그룹 엔티티 + 멤버십 + CRUD API만. 보안수준 멤버(FR-PM-06)·권한스킴 grants(§12.2)·그룹 멘션(§9) 결선은 각 소비 FR 소관(FR-PM-08 "인프라만" 선례 동형). 관리 UI/E2E(D6/D7)도 후속.

### D7. LDAP 그룹 동기화 후속

네이티브(BTS 자체) 그룹 우선. `user_external_accounts.groups` 문자열 ↔ 정규 `user_groups` 매핑·주기 동기화(SDD 19.8.1)는 별도 후속 FR. 본 FR은 수동 그룹/멤버 관리만.

## 결과

- 신규 테이블 2개(user_groups, group_memberships), 도메인 data class 2개, Repository(raw SQL), 관리 컨트롤러(SYSTEM_ADMIN 가드), 통합테스트(@ActiveProfiles prod + Testcontainers).
- 신규 권한 코드/포트 0(기존 SystemPermissionResolver 재사용). `role_permissions` 시드 무변경 → `PermissionSchemaMigrationTest` 카운트 영향 없음.
- IssueScope/IssuePermission enum 무변경 → cross-module 카운트 가드(`IssueScopeTest`) 영향 없음.
- FR-PM-06 재개 시 보안수준 멤버 타입에 GROUP 추가 가능(다형 멤버 설계 전제).
