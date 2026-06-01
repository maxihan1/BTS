# FR-PM-01 프로젝트 행정 (관리자/멤버 관리) — 백엔드 D1~D5 스펙

> slug: project-admin-backend / BC: identity-access / type: auth
> 관련 ADR: [2026-06-01-project-membership-model](../decisions/2026-06-01-project-membership-model.md)
> 범위: 이미 존재하는 프로젝트의 멤버십 CRUD + 가드. 프론트(D6)·E2E(D7)·프로젝트 생성은 범위 밖.

## 사용자 시나리오 (Given-When-Then)

### S1. 첫 멤버 자동 관리자 (부트스트랩)
- **Given** 멤버가 0명인 프로젝트 P, JWT로 인증된 사용자 U
- **When** U가 P에 **자기 자신**(userId == actorId)을 멤버로 추가
- **Then** 요청 role과 무관하게 U는 `PROJECT_ADMIN`으로 저장된다 (201) + audit emit
- **제약** 부트스트랩은 JWT 전용(PAT 불가, B1), 타인 추가 불가(userId ≠ actorId면 부트스트랩 아님 → 멤버 0명이라 가드할 ADMIN 없음 → 404 처리, B2)

### S2. 관리자가 멤버 초대
- **Given** P에 PROJECT_ADMIN A가 존재, 사용자 B
- **When** A가 B를 role=MEMBER로 추가
- **Then** B가 MEMBER로 저장된다 (201)

### S3. 비관리자의 초대 거부
- **Given** P에 ADMIN A, MEMBER M
- **When** M이 새 사용자 C를 추가 시도
- **Then** 403 `not_project_admin`, 멤버 변화 없음

### S4. 역할 변경
- **Given** P에 ADMIN A, MEMBER B
- **When** A가 B의 role을 PROJECT_ADMIN으로 변경
- **Then** B가 PROJECT_ADMIN으로 갱신된다 (200)

### S5. 멤버 제거
- **Given** P에 ADMIN A, MEMBER B
- **When** A가 B를 제거
- **Then** B의 멤버십 행이 삭제된다 (204)

### S6. 마지막 관리자 보호
- **Given** P에 PROJECT_ADMIN A가 유일한 관리자
- **When** A를 제거하거나 MEMBER로 강등 시도 (A 자신 또는 다른 ADMIN이 없음)
- **Then** 409 `last_admin_protected`, 변화 없음

### S7. 멤버 목록 조회
- **Given** P의 멤버 A, B / 비멤버 X
- **When** A가 P의 멤버 목록 조회
- **Then** 200 + 멤버 배열. **X(비멤버)가 조회 시 404 `project_not_found`** (존재 숨김, B3 — 세션 IDOR 404 선례 일치)

### S8. 자기 강등 (다른 ADMIN 존재)
- **Given** P에 ADMIN A, ADMIN B
- **When** A가 자신을 MEMBER로 PATCH
- **Then** 200 (마지막 ADMIN 아니므로 허용). A가 유일 ADMIN이면 409 `last_admin_protected` (S6)

## 기능 요구사항 (FR)

- **FR-1** 멤버 추가 — `POST /api/v1/projects/{projectId}/members`. body `{ userId, role }`.
- **FR-2** 부트스트랩 — 멤버 0명 프로젝트의 첫 추가는 **JWT 인증 + 자기 자신(userId == actorId)**만 가능 + role을 PROJECT_ADMIN으로 강제 + audit emit (B1/B2).
- **FR-3** 가드 — 멤버 1명 이상이면 모든 변경(추가/역할변경/제거)은 해당 프로젝트 PROJECT_ADMIN만. **비멤버는 존재 숨김(404)**, 멤버지만 비ADMIN은 403 (B3).
- **FR-4** 역할 변경 — `PATCH /api/v1/projects/{projectId}/members/{userId}`. body `{ role }`. updateRole 시 `updated_at` 갱신(C4).
- **FR-5** 멤버 제거 — `DELETE /api/v1/projects/{projectId}/members/{userId}`. hard delete (행 삭제).
- **FR-6** 멤버 목록 — `GET /api/v1/projects/{projectId}/members`. 해당 프로젝트 멤버만 조회. 비멤버는 404(존재 숨김).
- **FR-11** Audit — 멤버 추가(부트스트랩 ADMIN 획득 포함)·역할 변경·멤버 제거는 `AuthAuditLogService`로 감사 이벤트 emit (C5).
- **FR-7** 마지막 관리자 보호 — 프로젝트의 PROJECT_ADMIN이 1명일 때 그를 제거/강등 불가.
- **FR-8** 프로젝트 존재 검증 — `ProjectDirectory` 포트(identity-access 내부 인터페이스)로 검증. 구현은 같은 DB의 `projects`를 read-only 조회(`WHERE id=:id AND deleted_at IS NULL`). 코드 import 없이 DB 레벨 read만 → BC 격리 유지. 미존재 시 404 `project_not_found`.
- **FR-9** 사용자 존재 검증 — 존재하지 않는 userId 추가 시 404/422.
- **FR-10** 중복 방지 — 이미 멤버인 userId 재추가 시 409 `membership_already_exists`.

## 비기능 요구사항 (NFR)

- 권한 가드 오버헤드 p95 < 10ms (identity-access §NFR 측정표 항목, 멤버십 1행 조회).
- 평문 비밀번호·토큰 미로깅 (해당 없음 — 멤버십엔 민감정보 없음).
- 모든 엔드포인트 인증 필수 (Spring Security 필터 우회 금지, identity-access 절대 규칙).
- 동시성 — 부트스트랩 race 방지 (§엣지 EC-1).

## API 인터페이스 (REST)

경로 prefix `/api/v1/projects/{projectId}/members`. projectId = UUID (프로젝트 key가 아닌 내부 id. key→id 변환은 cross-BC 조회라 범위 밖).

| 메서드 | 경로 | 요청 | 성공 | 권한 |
|---|---|---|---|---|
| POST | `/` | `{ userId: UUID, role: ProjectRole }` | 201 `ProjectMemberResponse` | 부트스트랩(JWT+자기자신) 또는 ADMIN(JWT/PAT) |
| GET | `/` | — | 200 `{ members: ProjectMemberResponse[] }` | 멤버(JWT/PAT) |
| PATCH | `/{userId}` | `{ role: ProjectRole }` | 200 `ProjectMemberResponse` | ADMIN(JWT/PAT) |
| DELETE | `/{userId}` | — | 204 | ADMIN(JWT/PAT) |

`ProjectRole = "PROJECT_ADMIN" | "MEMBER"`.

### Actor 추출 (B1 — JWT/PAT 두 경로)
`@AuthenticationPrincipal Jwt`는 PAT 요청에서 null이다(PAT principal은 `UsernamePasswordAuthenticationToken`의 String userId, `PatAuthenticationFilter` 선례). 따라서 헬퍼로 통일.
```kotlin
// JWT → jwt.subject(UUID), PAT → SecurityContext authentication.principal(String) → UUID
fun resolveActor(jwt: Jwt?): Actor   // { userId: UUID, isPat: Boolean }
```
부트스트랩(POST, 멤버 0명)은 `isPat == true`면 거부 → 401/403. 그 외 CRUD는 JWT/PAT 동일 평가.

### 에러 평가 순서 (C6 — 보안상 고정)
`인증(401) → actor 멤버십 조회(비멤버 404, 존재 숨김) → 인가(멤버지만 비ADMIN 403) → 대상 존재(user/member 404) → 입력 검증(invalid_role 422) → 비즈니스(중복 409, 마지막 admin 409)`.
role enum 검증은 컨트롤러 `@Valid`가 아니라 **인가 통과 후 서비스에서 `ProjectRole.from`**으로 수행(인가 우선, 권한 없는 자에게 검증 정보 노출 최소화).

```kotlin
data class ProjectMemberResponse(
    val projectId: UUID,
    val userId: UUID,
    val role: String,        // "PROJECT_ADMIN" | "MEMBER"
    val createdAt: Instant,
    val updatedAt: Instant,
)
data class AddMemberRequest(val userId: UUID, val role: String)   // role: 부트스트랩 시 무시
data class ChangeRoleRequest(val role: String)
```

### 에러 코드 (snake_case 문자열, 기존 AuthController 컨벤션)
| 코드 | HTTP | 상황 |
|---|---|---|
| `project_not_found` | 404 | projectId 미존재 **OR actor가 비멤버**(존재 숨김, B3) |
| `user_not_found` | 404 | 추가 대상 userId 미존재 |
| `member_not_found` | 404 | PATCH/DELETE 대상이 멤버 아님 |
| `membership_already_exists` | 409 | 이미 멤버 |
| `not_project_admin` | 403 | actor가 **멤버지만** ADMIN 아님 |
| `last_admin_protected` | 409 | 마지막 ADMIN 제거/강등 |
| `invalid_role` | 422 | role 값이 enum 밖 (인가 통과 후 검증) |

(`not_project_member` 제거 — 비멤버는 존재 숨김 위해 404 `project_not_found`로 흡수, B3)

응답 형식 `{ "error": "<code>" }` (기존 `mapOf("error" to ...)` 선례).

## 데이터 모델 변경

신규 마이그레이션 **V007__project_memberships.sql** (identity-access, jOOQ 미사용 → init_codegen 미러 불필요).

```sql
CREATE TABLE project_memberships (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID        NOT NULL,                          -- cross-BC, FK 없음 (ADR D2)
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        VARCHAR(32) NOT NULL CHECK (role IN ('PROJECT_ADMIN','MEMBER')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, user_id)
);
CREATE INDEX idx_project_memberships_project ON project_memberships(project_id);
CREATE INDEX idx_project_memberships_user    ON project_memberships(user_id);
-- 마지막 admin 보호(S6)·가드의 admin count 조회용 부분 인덱스 (C4)
CREATE INDEX idx_project_memberships_admins  ON project_memberships(project_id) WHERE role = 'PROJECT_ADMIN';
```

`updateRole`은 `UPDATE ... SET role=:role, updated_at=NOW() WHERE ...` (updated_at 명시 갱신, 트리거 없음 — sessions 선례).

## 엣지 케이스

- **EC-1 부트스트랩 race (C1 — 직렬화 방식 확정)** — 두 요청이 동시에 "멤버 0명"을 읽고 각자 삽입하면 둘 다 PROJECT_ADMIN이 될 수 있다(UNIQUE는 다른 user면 못 막음). → **프로젝트 단위 `pg_advisory_xact_lock(hi, lo)`**(projectId UUID의 상·하위 64bit를 2-int 변형 키로) 획득 후 count→insert를 같은 `@Transactional`(READ_COMMITTED 유지)에서 수행. 락 대기 방식이라 SERIALIZABLE 재시도(40001) 불필요. 자기자신 제한(B2)과 결합 시 같은 actor 동시 2요청은 UNIQUE로도 1건만 성공.
- **EC-2 자기 자신 제거 / 강등** — ADMIN이 자신을 제거/강등 가능. 단 마지막 ADMIN이면 `last_admin_protected` (S6/S8).
- **EC-2b 두 ADMIN 동시 상호 제거 (C2)** — ADMIN A·B가 동시에 서로 제거 시 각자 `countAdmins==2`를 읽고 통과 → admin 0명 데드락(복구 불가, 멤버 남아있어 부트스트랩도 안 됨). → 제거/강등도 EC-1과 **같은 프로젝트 advisory lock** 하에서 `countAdminsByProject`를 평가 → 정확히 1건만 성공, admin ≥ 1 유지.
- **EC-3 같은 role로 PATCH** — MEMBER→MEMBER 등 no-op. 200 + 멱등. updated_at은 갱신.
- **EC-4 부트스트랩 + 명시 role=MEMBER** — 멤버 0명 + 자기자신 + role=MEMBER 요청 → PROJECT_ADMIN으로 강제 저장(FR-2). 응답에 실제 저장된 role 반영.
- **EC-5 soft-deleted 프로젝트** — projects.deleted_at 설정 → `project_not_found`(ProjectDirectory가 `deleted_at IS NULL` 조건).
- **EC-6 PAT 인증 (B1 결정)** — CRUD(추가/역할변경/제거/목록)는 PAT도 멤버십 자기참조 가드로 JWT와 동일 평가. **부트스트랩(멤버 0명 첫 추가)만 JWT 전용**(PAT면 거부). actor 추출은 §API Actor 추출 참조.
- **EC-7 비멤버의 비부트스트랩 요청** — 멤버 0명 프로젝트에 타인 추가, 또는 비멤버의 GET/PATCH/DELETE → 404 `project_not_found`(존재 숨김, B3).

## 제약 조건

- BC 격리 — issue-tracking 코드 직접 import 금지 (DB read-only 조회는 포트 추상화로, D-A).
- @Transactional 메서드는 클래스 `@Service`/`@Repository` 필수 (TransactionalServiceArchTest).
- 멤버십 행은 hard delete (이슈키 같은 영구 보존 대상 아님).

## 측정 가능한 완료 기준

- [ ] V007 마이그레이션 적용(부분 인덱스 포함) + Testcontainers에서 검증
- [ ] 멤버 CRUD 4 엔드포인트 통합테스트 (S1~S8 전부)
- [ ] 가드/존재숨김 테스트 (S3 403, S6 409, S7 비멤버 404, EC-7)
- [ ] 부트스트랩 race(EC-1) + 두 ADMIN 동시제거(EC-2b) 동시성 테스트 (advisory lock)
- [ ] PAT/JWT 두 경로 actor 추출 테스트 + 부트스트랩 PAT 거부 (B1)
- [ ] 권한 변경 audit emit 검증 (FR-11)
- [ ] ktlint + detekt 그린, ArchUnit 통과
- [ ] `./gradlew :modules:identity-access:test` 그린

## Brainstorming Check

✅ 통과 (1회, adversarial self-review). 발견 gap 2건 모두 Maxi 결정으로 해소.
- D-A 프로젝트 존재 검증 → ProjectDirectory read-only 포트 조회 (BC 격리 + orphan 방지)
- D-B PAT 허용 → 멤버십 자기참조 가드로 JWT/PAT 동일 평가
- 부트스트랩 race(EC-1), 마지막 관리자 보호(S6), GET 비멤버 차단(FR-6)은 초안에 이미 반영됨.
