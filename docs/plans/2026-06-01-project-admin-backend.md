# FR-PM-01 프로젝트 행정 (관리자/멤버 관리) — 백엔드 D1~D5

> slug: project-admin-backend
> plan slug: identity/project-admin
> type: auth
> agent: security-engineer
> primary BC: identity-access
> 생성: 2026-06-01

## Brief

FR-PM-01 — 프로젝트 행정 (관리자/멤버 관리). 우선순위 필수, 선행 §2.1(로그인, 완료).
이번 PR 범위는 **백엔드 D1~D5**. D6(프론트 UI)·D7(E2E)은 후속 PR.

- D1. 도메인 — ProjectRole (책임. security-engineer)
- D2. 명세 — 관리자 멤버 초대/제거 (책임. security-engineer)
- D3. 데이터 모델 — `project_memberships(project_id, user_id, role)` (책임. db-engineer)
- D4. 백엔드 — CRUD API + 가드 (책임. security-engineer)
- D5. 백엔드 테스트 (책임. security-engineer)

참고. 이 FR은 미뤄둔 회원가입(전역 admin 권한)의 선행 작업. FR-AU-05 노트에서 "FR-PM-01 선행 필요" 명시.

분류 교정. classifier가 "E2E" 키워드로 qa 오분류 → auth/security-engineer로 교정 (docs/plan/product/identity-access.md §4.1 책임 표기 근거).

## 도메인 정리

- **BC**: identity-access (멤버십 = 권한 1차 소스)
- **영향 엔티티**:
  - `ProjectMembership` (신규) — `project_memberships(id, project_id, user_id, role, ...)`
  - `ProjectRole` (신규 enum) — `PROJECT_ADMIN`, `MEMBER` 2종
  - `User` (기존, UUID id) — user_id FK 대상
  - `projects` (issue-tracking, UUID id) — project_id 참조 대상 (FK 없음, cross-BC)
- **새 용어 (glossary 추가 후보, Maxi 승인 대기)**:
  - 프로젝트 멤버십 / ProjectMembership — 한 사용자가 한 프로젝트에 갖는 역할 보유 관계
  - 프로젝트 역할 / ProjectRole — PROJECT_ADMIN / MEMBER
  - (기존 "프로젝트 행정 권한"은 glossary §권한에 이미 존재)
- **실재 검증 (phantom 방지, learnings 2026-05-20)**:
  - `projects` 테이블 실재 ✅ (issue-tracking V001, UUID) / `Project` 도메인 엔티티·`lead_id` 미존재 ❌
  - `ProjectRole`·권한 가드·`project_memberships` 미존재 ❌ (전부 신규)
  - 프로젝트 생성 API 미존재 ❌ → 부트스트랩 규칙으로 우회 (ADR D4)
- **핵심 도메인 결정 (Maxi 승인)**:
  - 범위: 이미 존재하는 프로젝트의 멤버 CRUD + 가드만. 프로젝트 생성은 별도 FR
  - 첫 관리자 시동: 멤버 0명 프로젝트의 첫 멤버 = 자동 PROJECT_ADMIN
  - 역할: 최소 2종 (PROJECT_ADMIN / MEMBER)
- **기존 결정 충돌**: SDD 05.3(BIGINT)·12.6(역할 8종)은 stale → 실제 UUID + 2종 채택 (ADR이 현재 정본)
- **관련 ADR**: [docs/decisions/2026-06-01-project-membership-model.md](../decisions/2026-06-01-project-membership-model.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-01-project-admin-backend.md](../specs/2026-06-01-project-admin-backend.md)

핵심 요약.
- REST `/api/v1/projects/{projectId}/members` — POST(추가)/GET(목록)/PATCH(역할)/DELETE(제거)
- 부트스트랩: 멤버 0명 프로젝트의 첫 추가 = 인증된 누구나 + role을 PROJECT_ADMIN 강제
- 가드: 멤버 1+ 상태에선 PROJECT_ADMIN만 변경. 멤버십 자기참조 평가(JWT/PAT 동일)
- 마지막 ADMIN 제거/강등 금지(`last_admin_protected`)
- 프로젝트 존재 검증: ProjectDirectory read-only 포트(projects DB 조회, FK 없음, BC 격리)
- 데이터: V007 project_memberships (UUID, user_id FK / project_id FK 없음, UNIQUE(project_id,user_id))
- 기술: NamedParameterJdbcTemplate(jOOQ 미사용 → init_codegen 미러 불필요), snake_case 에러코드

기술 패턴 확정(기존 코드 조사).
- Repository: `@Repository @Transactional(READ_COMMITTED)` + NamedParameterJdbcTemplate + RowMapper (JdbcUserRepository 선례)
- actor 추출: `@AuthenticationPrincipal Jwt` subject UUID(JWT) / PAT는 SecurityContext principal userId
- 테스트: `@SpringBootTest(RANDOM_PORT)` + Testcontainers postgres:16 + Flyway 자동 마이그레이션

## Brainstorming Check

✅ 통과 (1회, adversarial self-review). gap 2건(project 존재검증 방식, PAT 허용) Maxi 결정으로 해소. race/마지막관리자/GET권한은 초안 반영.

## Plan

패키지. `com.atlas.bts.identity.project` (기존 user/·session/·pat/ 서브패키지 패턴 따름).
공통 검증. 각 task 끝 `./gradlew :modules:identity-access:test --tests <…>` + 최종 ktlint/detekt/ArchUnit.

### Task 1. ProjectRole enum + ProjectMembership 도메인

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectRole.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembership.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/ProjectMembershipTest.kt`]
- depends-on: []

**RED**. `ProjectMembershipTest` — `ProjectRole.from("PROJECT_ADMIN")`/`from("MEMBER")` 정상, `from("owner")`/소문자/빈값 → `IllegalArgumentException`(가드가 `invalid_role` 422로 매핑). `ProjectMembership(projectId, userId, role, createdAt, updatedAt)` 생성 + role 보유.
**GREEN**. enum 2값 + `from(raw)` 정규화 함수, data class.
**REFACTOR**. KDoc(역할 의미 + ADR 링크), `from`의 허용값 상수화.
**검증**. `--tests ProjectMembershipTest`

### Task 2. V007 마이그레이션 — project_memberships

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V007__project_memberships.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/V007MigrationTest.kt`]
- depends-on: []

**RED**. `V007MigrationTest`(Testcontainers) — 마이그레이션 후 `project_memberships` 존재, `UNIQUE(project_id,user_id)`·`role CHECK`·`user_id FK ON DELETE CASCADE`·**세 인덱스**(project / user / admin 부분 인덱스 `WHERE role='PROJECT_ADMIN'`) 존재. (spec §데이터 모델 DDL 그대로, C4).
**GREEN**. V007 DDL 작성 (V004 sessions 스타일 — COMMENT 포함, pgcrypto는 V001에서 이미 활성). admin count 조회용 부분 인덱스 포함.
**REFACTOR**. 컬럼 COMMENT(project_id는 "cross-BC, FK 없음" 명시).
**검증**. `--tests V007MigrationTest`

### Task 3. JdbcProjectMembershipRepository

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembershipRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/ProjectMembershipRepositoryIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**. 통합테스트 — `save`, `findByProjectAndUser`, `listByProject`, `countByProject`, `countAdminsByProject`, `updateRole`, `deleteByProjectAndUser`. UNIQUE 위반 시 예외(중복 멤버 → 상위에서 409 매핑). 인터페이스 + Jdbc 구현 분리(JdbcUserRepository 패턴).
**GREEN**. `@Repository @Transactional(READ_COMMITTED)` + NamedParameterJdbcTemplate + RowMapper. SQL 상수 companion.
**REFACTOR**. RowMapper object 분리, SQL 상수 정리.
**검증**. `--tests ProjectMembershipRepositoryIntegrationTest`

### Task 4. ProjectDirectory 포트 + read-only 구현

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectDirectory.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/JdbcProjectDirectoryIntegrationTest.kt`]
- depends-on: []

**RED**. `JdbcProjectDirectoryIntegrationTest` — `exists(projectId)`: projects 행 존재+`deleted_at IS NULL` → true, soft-deleted → false, 미존재 → false. **테스트가 projects 최소 테이블을 직접 생성**(setUp `@Sql` 또는 jdbc DDL: `id UUID PK, deleted_at TIMESTAMPTZ`). prod는 통합 단일 DB라 projects 실재(런타임 가정 명시).
**GREEN**. `ProjectDirectory` 인터페이스 + `JdbcProjectDirectory`(`@Repository`, read-only 쿼리 `SELECT 1 FROM projects WHERE id=:id AND deleted_at IS NULL`).
**REFACTOR**. KDoc — "cross-BC read-only, import 금지(ADR D2)", projects DDL drift 주의 코멘트.
**검증**. `--tests JdbcProjectDirectoryIntegrationTest`

### Task 5. ProjectMembershipService — 부트스트랩 + 가드 + 마지막 admin 보호 + 존재 검증 + audit

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembershipService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/ProjectMembershipServiceTest.kt`]
- depends-on: [1, 3, 4]
- 참고: `AuthAuditLogService`(기존 audit/ 패키지) 의존. 새 AuthEventType(PROJECT_MEMBER_ADDED/ROLE_CHANGED/REMOVED) 추가 필요 시 그 enum도 files에 포함.

**RED**. `ProjectMembershipServiceTest`(repository fake/mock + ProjectDirectory fake + audit fake) —
- S1 부트스트랩: 멤버 0명 + actor 자기자신 + JWT → ADMIN 강제(EC-4 role=MEMBER도 강제) + audit emit. **isPat=true면 거부**(B1). **userId≠actorId면 비멤버 취급 404**(B2/EC-7).
- S2 ADMIN 초대(JWT/PAT 동일), S3 비ADMIN→`not_project_admin`(멤버지만), S4 역할변경(updated_at 갱신), S5 제거, S6/S8 마지막 ADMIN 제거·강등 금지, **비멤버 GET/변경→`project_not_found` 404 존재숨김**(B3/EC-7).
- FR-8 project 미존재→404, FR-9 user 미존재→404, FR-10 중복→409.
- 예외는 서비스 전용 예외 타입(상위 controller가 에러코드 매핑). 에러 평가순서(인증→멤버십→인가→대상존재→검증→비즈니스, C6) 단위 검증.
- audit: 추가/역할변경/제거 시 emit 검증(FR-11).
**GREEN**. `@Service @Transactional(READ_COMMITTED)`. addMember(actor, projectId, req)/changeRole/removeMember/listMembers. **부트스트랩·제거·강등은 `pg_advisory_xact_lock(projectId)` 획득 후 count→write**(EC-1/EC-2b race 차단, C1/C2). role 검증은 인가 통과 후 `ProjectRole.from`. audit emit.
**REFACTOR**. 가드 헬퍼(`requireProjectAdmin`/`resolveMembershipOr404`) 추출, 마지막 admin 규칙 도메인 함수화(`patch-merge-domain-bypass` 교훈 — SQL로 새지 않게 서비스/도메인에 유지), advisory lock key 헬퍼(UUID→2×bigint).
**검증**. `--tests ProjectMembershipServiceTest`

### Task 6. ProjectMemberController + DTO + 에러 매핑

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/ProjectMemberController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/dto/ProjectMemberResponse.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/ProjectMemberControllerTest.kt`]
- depends-on: [5]

**RED**. WebMvc 슬라이스 테스트 — 4 엔드포인트 매핑, **actor 추출 헬퍼**(JWT `jwt.subject` / PAT는 `@AuthenticationPrincipal Jwt`가 null → SecurityContext principal String→UUID, `resolveActor` 헬퍼, B1), 서비스 예외→snake_case 에러코드+HTTP 매핑(spec §에러표 7종, `not_project_member` 제거), **비멤버 404 존재숨김**(B3), **부트스트랩 PAT 거부**(B1), 성공 응답 DTO/상태(201/200/204). PAT 인증 경로 테스트 필수(EC-6).
**GREEN**. `@RestController @RequestMapping("/api/v1/projects/{projectId}/members")`. DTO(AddMemberRequest/ChangeRoleRequest/ProjectMemberResponse). `resolveActor(jwt)` 헬퍼. 예외→`mapOf("error" to code)` 매핑(인라인 when, RestControllerAdvice 없음 — 기존 선례). role `@Valid` 대신 서비스 검증(인가 우선, C6).
**REFACTOR**. 에러코드 매핑 함수 분리(`FailureReason.toErrorCode` 선례), actor 추출 헬퍼 정리.
**검증**. `--tests ProjectMemberControllerTest`

### Task 7. 통합테스트 — 전체 시나리오 + 부트스트랩 race

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/ProjectMemberFlowIntegrationTest.kt`]
- depends-on: [6]

**RED**(진짜 실패하는 새 통합 검증). `@SpringBootTest(RANDOM_PORT)` + Testcontainers — S1~S8 end-to-end(실 HTTP), **EC-1 부트스트랩 동시성**(두 스레드 동시 첫 추가 → advisory lock 직렬화 검증, `PatAndConcurrencyIntegrationTest` 패턴 참조), **EC-2b 두 ADMIN 동시 상호제거 → admin≥1 유지**, **부트스트랩 PAT 거부 + 비멤버 404**(B1/B3), JWT/PAT 두 인증 경로, **audit emit 검증**(FR-11). projects 테이블은 setUp에서 생성(Task 4와 동일) + 시드 행.
**GREEN**. (단위는 T1~6에서 test-first 완료. T7은 통합 wiring/설정/누락 보강. 동시성·존재숨김은 T7 RED가 처음 드러내는 새 검증)
**REFACTOR**. 시나리오 헬퍼 정리.
**검증**. `--tests ProjectMemberFlowIntegrationTest` + `./gradlew :modules:identity-access:test`(전체) + ktlintCheck + detekt

## Plan 메타

- task 수: 7
- 의존 그래프 / wave(이론):
  - wave 1. T1(도메인, security) ∥ T2(마이그레이션, db) ∥ T4(ProjectDirectory, security) — 파일 겹침 없음, depends-on 없음
  - wave 2. T3(repository) [1,2]
  - wave 3. T5(service) [1,3,4]
  - wave 4. T6(controller) [5]
  - wave 5. T7(통합) [6]
  - 단 identity-access 단일 모듈 → 같은 test 컴파일 단위 공유로 실제 병렬 이득 제한(`bts-plan-wave-gradle-module-compile` 교훈). wave는 논리 의존 표기.
- TDD 강제: yes (test-first, `test:` 커밋이 `feat:` 앞)
- 리스크:
  1. **projects 테이블 cross-BC** — identity-access 테스트 DB에 projects 없음 → T4/T7가 최소 테이블 생성. prod는 동일 DB·스키마라 실재(ADR deployment invariant, C3). DDL drift 주의(id/deleted_at만 사용).
  2. **부트스트랩 race(EC-1) + 두 admin 동시제거(EC-2b)** — `pg_advisory_xact_lock(projectId)` 하 count→write 직렬화. 동시성 통합테스트로 가드(C1/C2).
  3. **마지막 admin 보호** — 도메인 규칙으로 표현(SQL로 새지 않게, `patch-merge-domain-bypass` 교훈).
  4. **PAT actor 추출** — `@AuthenticationPrincipal Jwt`는 PAT에서 null → `resolveActor` 헬퍼로 JWT/PAT 통일. 부트스트랩만 PAT 거부(B1).
  5. **권한 상승 잔존(B2)** — 멤버 0명 프로젝트 노출 시 자기 ADMIN화 가능 → 자기자신+JWT+audit로 좁힘. 프로젝트 생성 FR 전까지 seed 격리 운영 가드(ADR).
  6. **정보노출(B3)** — 비멤버 404 통일. `not_project_member` 에러코드 제거.

## 리뷰 결과

### code-reviewer 독립 eng/보안 리뷰 (2026-06-01)

전체: CONDITIONAL — 설계 골격(BC 격리, 부트스트랩 모델, TDD 분해, 기존 패턴 정합)은 건전. BLOCKER 3건은 코드 문제가 아니라 spec/ADR 정책 공백 → Maxi 결정 3개로 닫힘.

**BLOCKER (auth, 무시 불가)**
- **B1 PAT 가드 충돌** — spec "PAT 허용"이 기존 세션관리 선례(PAT 403, session-management-pat-exclusion)와 정면충돌. 또 `@AuthenticationPrincipal Jwt`는 PAT 요청에서 null(PAT principal은 String userId) → actor 추출 경로 미확정.
- **B2 부트스트랩 권한 상승** — 임의 인증 사용자가 임의 projectId(멤버 0명)에 자신을 추가 → 자동 ADMIN. 타인 부트스트랩 허용 + audit 없음 → 권한 상승 추적 불가.
- **B3 정보노출** — 비멤버 GET 403이 "프로젝트 실재" oracle 제공. 기존 세션 IDOR 방어(404 단일응답, OWASP) 선례와 모순. B2와 결합 시 정찰도구화.

**CONCERN**
- C1 부트스트랩 race 직렬화 방식 "SERIALIZABLE 또는…" 미확정 → 한 방식 확정 필요(advisory lock 권장).
- C2 두 ADMIN 동시 상호제거 → admin 0명 데드락(복구 불가). spec에 없음.
- C3 ProjectDirectory 단일 DB 토폴로지 가정 load-bearing + 마이그레이션 순서 미정 → ADR deployment invariant로 명문화.
- C4 admin count 부분인덱스·`updateRole`의 updated_at 갱신·hard delete의 ADR 명문화 미흡.
- C5 권한변경 audit emit 누락(AuthAuditLogService 존재하나 미사용).
- C6 자기강등/타인부트스트랩/에러 평가순서(인증→존재→인가→검증→비즈니스) 엣지 누락.

**NIT**: 에러코드 매핑 양호, TDD 형식 양호(T7 통합 RED 강조 권장), 기존 패턴 정합(actor 추출만 B1).

→ BLOCKER 3건 Maxi 결정 후 spec/ADR 반영. CONCERN은 내가 spec 보강(C1/C2 advisory lock, C4 인덱스, C6 순서) + C5는 Maxi 결정.

**처리 완료 (2026-06-01, Maxi 결정 반영)**
- B1 → CRUD PAT 허용, 부트스트랩 JWT 전용. actor 추출 `resolveActor` 헬퍼(JWT/PAT). [spec §Actor 추출, FR-2, EC-6]
- B2 → 부트스트랩 자기자신(userId==actorId)만 + JWT + audit. 타인/PAT는 404. [spec S1/FR-2, ADR D4]
- B3 → 비멤버 404 존재숨김(`not_project_member` 제거). [spec 에러표, S7, EC-7]
- C1/C2 → `pg_advisory_xact_lock(projectId)` 하 count→write (부트스트랩 race + 두 admin 동시제거). [spec EC-1/EC-2b, plan T5]
- C3 → ADR deployment invariant 명문화(동일 DB·스키마, 분리 시 SPI 교체).
- C4 → admin 부분 인덱스 + updateRole updated_at + hard delete ADR D6. [spec DDL, plan T2]
- C5 → audit emit 이번 PR 포함(FR-11), AuthAuditLogService 재사용. [spec FR-11, plan T5]
- C6 → 에러 평가순서 고정 + role 검증 인가 후. 자기강등(S8)/타인부트스트랩(EC-7) 엣지 추가. [spec §에러 평가 순서]
- BLOCKER 0 잔존. 구현 착수 가능.
