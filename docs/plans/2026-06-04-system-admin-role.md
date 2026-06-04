# 전역 시스템 관리자 역할 + 전역 권한 인프라

> slug: system-admin-role
> type: auth
> agent: security-engineer
> 생성: 2026-06-04

## Brief

SDD 12.6 OrgAdmin/시스템 권한(12.3 ADMIN_SYSTEM 등)의 실제 구현. 현재 BTS는 프로젝트 단위 권한
(PROJECT_ADMIN/MEMBER)만 있고 전역(시스템/조직) 관리자 역할이 데이터·JWT·판정 어디에도 부재.

**범위 (Maxi 2026-06-04 — 인프라만)**:
1. 사용자 전역 역할 저장 (users 전역역할 컬럼 or 시스템역할 테이블)
2. JWT 토큰에 전역 역할/권한 클레임 추가
3. 시스템 권한코드(ADMIN_SYSTEM 등) 전역 판정 인프라
4. 최초 시스템 관리자 부트스트랩

**범위 제외**: 회원가입(FR-AU-05 소관, 후속) · 전역 워크플로우 스킴 관리(FR-PM-04, 후속).
이 인프라가 두 작업의 공통 선행을 해소한다.

**선행 해소 대상**: FR-PM-04(전역 MANAGE_WORKFLOW 판정) · FR-AU-05 회원가입(관리자 계정 생성).
**Jira Cloud 모델**: 워크플로우 스킴 등 전역 자원은 사이트/전역 관리자가 관리.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: identity-access (전역 역할/권한은 인증 BC 소유, 프로젝트 권한과 동일)
- **새 엔티티**: `SystemRoleAssignment` (사용자↔전역 역할, project_id 없음 — 프로젝트 멤버십과의 핵심 차이)
- **새 enum**: `SystemRole`(현재 `SYSTEM_ADMIN` 1종). `ProjectRole`과 **분리** — 프로젝트 역할과 전역 역할은 다른 축.
- **새 포트**: 전역 권한 판정기 (shared-kernel `com.bts.shared.permission`). 여러 BC가 의존할 공용 기반.
- **영향 기존 코드**: `JwtIssuer`(전역 역할 클레임 추가) · JWT converter(authority 변환) · `users` 테이블(V001, FK 대상).
- **새 용어** (glossary 추가 대기, Maxi 승인 필요):
  - **전역 역할 / 시스템 역할** (System Role) — 프로젝트와 무관하게 시스템 전체에 적용되는 역할. 현재 `SYSTEM_ADMIN` 1종.
  - **시스템 관리자** (System Admin) — `SYSTEM_ADMIN` 전역 역할 보유자. SDD 12.6 OrgAdmin의 단일 역할 구현.
- **기존 결정 관계**:
  - FR-PM-01 ADR이 stale 폐기한 SDD 12.6 8종 역할 중 OrgAdmin을 `SYSTEM_ADMIN`으로 부분 복원.
  - 메모리 `issue-scope-global-prod-hard-deny`(IssueScope.Global prod 무조건 거부)의 정공 해소 토대 — 단 실결선은 FR-PM-04.
- **관련 ADR**: [docs/decisions/2026-06-04-system-admin-role.md](../decisions/2026-06-04-system-admin-role.md) (생성됨, D1~D6)
- **Maxi 결정 (2026-06-04, AskUserQuestion)**:
  - D1 저장 = 별도 테이블 `system_role_assignments` (users 컬럼 기각)
  - D5 부트스트랩 = 설정값(`bts.bootstrap.admin-username`) 기반 멱등 승격 (마이그레이션 고정 INSERT 기각)
  - D6 범위 = 순수 토대 + 통합테스트 검증 (IssueScope.Global 실결선은 FR-PM-04)

## 스펙

전체 스펙. [docs/specs/2026-06-04-system-admin-role.md](../specs/2026-06-04-system-admin-role.md)

핵심 시나리오 4줄 요약.
- 앱 기동 시 `bts.bootstrap.admin-username` 설정값의 사용자를 SYSTEM_ADMIN으로 멱등 승격 (이미 있으면 skip)
- SYSTEM_ADMIN 보유자 로그인 시 JWT에 `roles=["SYSTEM_ADMIN"]` 클레임 + `ROLE_SYSTEM_ADMIN` authority
- 전역 판정기(shared-kernel 포트)가 시스템 관리자 여부 판정 — FR-PM-04 등 후행이 소비
- 신규 REST 엔드포인트 없음(토대만), 실 동작은 FR-PM-04·FR-AU-05

산출물. V010 마이그레이션 · `SystemRole`/`SystemRoleAssignment` · Repository · `SystemPermissionResolver`(포트+구현) · `JwtIssuer` 클레임 확장 · 부트스트랩 `ApplicationRunner` · 통합테스트.

## Brainstorming Check

✅ 통과 (적대적 self-review, office-hours 스킵 — 정의된 FR 작업이라 부적합, 메모리 `bts-spec-office-hours-mismatch`).
- 보강: 감사 로그(audit FR-AU-10 후속, 현재 로그만) · PAT 전역역할 제외(EC7) · 부여 경로 부트스트랩 한정.
- plan-review 위임 갈림길: 전역 판정기 시그니처(`isSystemAdmin` vs 권한코드 기반 `hasSystemPermission`) — FR-PM-04 사용성과 직결.

## Plan

> agent 기본값: `security-engineer` | BC: identity-access(+shared-kernel 포트 1파일) | 모듈 경로 prefix: `:modules:identity-access`, `:modules:shared-kernel`
> 전역 판정기 결정: `SystemPermissionResolver.isSystemAdmin(actorId: UUID): Boolean`, DB 조회, 프로파일 무관 단일 빈 (ADR D3/D4 정정).

### Task 1. system_role_assignments 마이그레이션 + 도메인 타입

**메타**.
- agent: `db-engineer` (마이그레이션) — 도메인 타입은 security-engineer 협업, 단일 task로 묶음
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V010__system_role_assignments.sql`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/systemrole/SystemRole.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/systemrole/SystemRoleAssignment.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/systemrole/SystemRoleTest.kt`]
- depends-on: []

**RED**:
- `SystemRoleTest` — `SystemRole.from("SYSTEM_ADMIN")` 정상 파싱 + 미지정 문자열 예외. `ProjectRole`과 별개 타입임을 컴파일로 보장(import 분리).
- 실패 메시지(예상): `SystemRole` 클래스 없음.

**GREEN**:
- `SystemRole` enum (`SYSTEM_ADMIN` 단일) + `from(raw)` (ProjectRole.from 선례).
- `SystemRoleAssignment`(userId, role, createdAt) 값 객체.
- `V010__system_role_assignments.sql` — 테이블(id/user_id FK CASCADE/role CHECK('SYSTEM_ADMIN')/created_at, UNIQUE(user_id, role)), user_id 인덱스.

**REFACTOR**: KDoc(한 줄 한국어 헤더), ProjectRole과의 축 차이 주석.

**검증**: `./gradlew :modules:identity-access:test --tests '*SystemRoleTest*'` + Flyway 마이그레이션이 Testcontainers 부팅에서 적용되는지(Task 2에서 표면화).

### Task 2. SystemRoleAssignmentRepository (Jdbc) — 멱등 부여 / 조회 / 존재여부

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/systemrole/SystemRoleAssignmentRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/systemrole/JdbcSystemRoleAssignmentRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/systemrole/JdbcSystemRoleAssignmentRepositoryTest.kt`]
- depends-on: [1]

**RED** (Testcontainers 통합):
- `assign` 후 `findRolesByUser`가 SYSTEM_ADMIN 포함.
- 같은 (user, role) 두 번 `assign` → 예외 없음, 행 1개(ON CONFLICT DO NOTHING 멱등) — EC4.
- `existsByRole(SYSTEM_ADMIN)` 비어있을 때 false, 부여 후 true (부트스트랩 멱등 판정용).
- 비존재 user 부여 → FK 위반(혹은 호출 전 검증 책임 — Task 5에서 user 조회 선행).
- 실패 메시지(예상): `SystemRoleAssignmentRepository` 없음.

**GREEN**: 인터페이스(`assign`/`findRolesByUser`/`existsByRole`) + `JdbcSystemRoleAssignmentRepository`(NamedParameterJdbcTemplate, `ON CONFLICT (user_id, role) DO NOTHING`). `JdbcProjectMembershipRepository` 선례 따름.

**REFACTOR**: SQL 상수 추출, rowMapper 분리.

**검증**: `./gradlew :modules:identity-access:test --tests '*JdbcSystemRoleAssignmentRepositoryTest*'`

### Task 3. SystemPermissionResolver 포트(shared-kernel) + identity 구현

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/SystemPermissionResolver.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessSystemPermissionResolver.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessSystemPermissionResolverTest.kt`]
- depends-on: [1, 2]

**RED**:
- `IdentityAccessSystemPermissionResolverTest`(mockk repository 단위 또는 Testcontainers) — SYSTEM_ADMIN 보유 user → `isSystemAdmin(id)=true`, 미보유 → false.
- 실패 메시지(예상): `SystemPermissionResolver` 인터페이스 없음.

**GREEN**:
- shared-kernel `interface SystemPermissionResolver { fun isSystemAdmin(actorId: UUID): Boolean }` (IssuePermissionResolver 선례 위치).
- identity `IdentityAccessSystemPermissionResolver`(repository.findRolesByUser에 SYSTEM_ADMIN 포함 여부 위임). `@Profile` 분리 **없음**(DB 조회라 모든 프로파일 동작) — ADR D4 정정.

**REFACTOR**: KDoc(FR-PM-04가 소비할 포트임 명시).

**검증**: `./gradlew :modules:shared-kernel:test :modules:identity-access:test --tests '*SystemPermissionResolver*'`

### Task 4. JwtIssuer 전역 역할 클레임 + authority 변환

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/JwtIssuer.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/AuthController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/RefreshTokenService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/SidRevokeJwtConverter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/JwtIssuerTest.kt`]
- depends-on: [1, 2]

**RED**:
- `JwtIssuer` 발급 시 `roles` 인자가 `["SYSTEM_ADMIN"]`이면 claim에 포함, 빈 리스트면 미포함(또는 빈 배열) — S3.
- converter가 `roles` 클레임을 `ROLE_SYSTEM_ADMIN` authority로 변환(JwtGrantedAuthoritiesConverter 커스텀, scopes의 SCOPE_ 변환과 공존).
- PAT 경로는 roles 미전달(EC7) — `PersonalAccessTokenService`는 호출부 변경 없음 확인.
- 실패 메시지(예상): `issue()`에 roles 파라미터 없음.

**GREEN**:
- `JwtIssuer.issue(..., roles: List<String> = emptyList())` 추가, `roles` 비어있지 않으면 `.claim(CLAIM_ROLES, roles)`.
- `AuthController`/`RefreshTokenService` 발급부에서 `SystemRoleAssignmentRepository.findRolesByUser(userId)` 조회해 전달.
- converter에 `roles` → `ROLE_` 매핑 추가(기존 scopes SCOPE_ 유지).

**REFACTOR**: CLAIM_ROLES 상수, 역할 조회 헬퍼.

**검증**: `./gradlew :modules:identity-access:test --tests '*JwtIssuerTest*'` + 기존 AuthController/RefreshToken 테스트 회귀 0.

### Task 5. 부트스트랩 ApplicationRunner (설정값 기반 멱등 승격)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/systemrole/SystemAdminBootstrapRunner.kt`, `backend/modules/identity-access/src/main/resources/application.yml`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/systemrole/SystemAdminBootstrapRunnerTest.kt`]
- depends-on: [1, 2]

**RED**:
- S1: admin-username 설정 + 해당 user 존재 + 보유자 0 → 기동 후 SYSTEM_ADMIN 부여.
- S2: 보유자 이미 존재 → skip(부여 없음).
- EC1: 설정 비어있음 → 아무 동작 없음.
- EC2: 설정 username의 user 없음 → 경고 로그, 부팅 정상, 부여 없음.
- 실패 메시지(예상): `SystemAdminBootstrapRunner` 없음.

**GREEN**:
- `@Component class SystemAdminBootstrapRunner(...) : ApplicationRunner`. `@Value("\${bts.bootstrap.admin-username:}")`.
- 로직: 설정 blank → return. `existsByRole(SYSTEM_ADMIN)` true → return(멱등). UserRepository로 username 조회, 없으면 warn 로그 후 return, 있으면 `assign`.
- `application.yml`에 `bts.bootstrap.admin-username:` 키(기본 빈값).
- 시각 의존 없음(Clock 불요).

**REFACTOR**: 로그 메시지 정리, KDoc.

**검증**: `./gradlew :modules:identity-access:test --tests '*SystemAdminBootstrapRunnerTest*'`

### Task 6. prod 프로파일 end-to-end 통합테스트 + 프로파일 부팅 안전

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/systemrole/SystemAdminInfraIntegrationTest.kt`]
- depends-on: [3, 4, 5]

**RED→GREEN** (이미 GREEN인 코드의 통합 검증, @ActiveProfiles "prod" Testcontainers):
- SYSTEM_ADMIN 사용자 로그인 → JWT roles 클레임 + authority + `SystemPermissionResolver.isSystemAdmin=true`, 일반 사용자 전부 false (S3+S4 end-to-end).
- 부트스트랩이 prod 컨텍스트 기동 시 동작(S1).
- non-prod 컨텍스트 부팅 정상(프로파일 한정 빈 부재 확인, NFR5).

**검증**: `./gradlew :modules:identity-access:test --tests '*SystemAdminInfraIntegrationTest*'` + 모듈 전체 `:modules:identity-access:test`.

## Plan 메타

- task 수: 6
- 예상 시간: 직렬 약 25~30분, wave 적용 시 약 18분 (예상 wave: T1 → T2 → {T3·T4·T5} → T6, 단 {T3·T4·T5}는 같은 identity-access 모듈 test 컴파일 단위 공유로 bts-impl이 직렬화할 수 있음 — 메모리 `bts-plan-wave-gradle-module-compile`)
- TDD 강제: yes (test 커밋이 feat보다 먼저, controller가 git log 검증)
- 추가 검증: ktlintMain+TestSourceSetCheck + detekt (sub-agent "통과" 보고 불신, controller 직접 실행 — 메모리 `subagent-ktlint-false-green`)
- 프론트/E2E: 없음 (인프라만, D6/D7 부재)
- 머지 전: detekt baseline 결선 확인, V번호 충돌 재확인(현재 최신 V009 → V010)

## 리뷰 결과 (← /bts-review-plan 채움)
