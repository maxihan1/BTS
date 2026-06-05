# FR-PM-09 사용자 그룹 (전역 그룹 인프라) — 스펙

> BC: identity-access | agent: security-engineer | 범위: 백엔드 인프라만(D1~D5)
> 도메인 ADR: [docs/decisions/2026-06-05-user-groups.md](../decisions/2026-06-05-user-groups.md)
> SDD §12.6.1 | plan §4.9

## 개요

전역(시스템 단위) 사용자 그룹 + 멤버십 인프라. 시스템 관리자(SYSTEM_ADMIN)가 그룹을 만들고
사용자를 그룹에 넣고 빼는 관리 API. 그룹은 향후 보안 수준(FR-PM-06)·권한 스킴·멘션이 소비할
재사용 단위. 본 FR은 그룹 자체의 CRUD + 멤버십 관리만(소비처 결선·UI는 후속).

## 사용자 시나리오 (Given-When-Then)

- **S1 그룹 생성**. Given SYSTEM_ADMIN로 인증된 사용자, When `POST /api/v1/groups {name:"임원", description:"임원진"}`,
  Then 201 + 생성된 그룹(id/name/description) 반환. name이 이미 존재하면 409.
- **S2 그룹 목록**. Given SYSTEM_ADMIN, When `GET /api/v1/groups`, Then 200 + 전체 그룹 목록(멤버 수 포함).
- **S3 그룹 수정**. Given SYSTEM_ADMIN, When `PATCH /api/v1/groups/{id} {name,description}`, Then 200 + 갱신본.
  없는 그룹이면 404, 다른 그룹과 name 중복이면 409.
- **S4 그룹 삭제**. Given SYSTEM_ADMIN, When `DELETE /api/v1/groups/{id}`, Then 204. 멤버십은 CASCADE 자동 삭제.
  없는 그룹이면 404.
- **S5 멤버 추가**. Given SYSTEM_ADMIN, When `PUT /api/v1/groups/{id}/members/{userId}`, Then 204.
  이미 멤버여도 204(멱등). 없는 그룹/사용자면 404.
- **S6 멤버 제거**. Given SYSTEM_ADMIN, When `DELETE /api/v1/groups/{id}/members/{userId}`, Then 204.
  멤버가 아니어도 204(멱등). 없는 그룹이면 404.
- **S7 멤버 목록**. Given SYSTEM_ADMIN, When `GET /api/v1/groups/{id}/members`, Then 200 + 멤버 사용자 요약(id/username/displayName) 목록.
- **S8 권한 거부**. Given 비-SYSTEM_ADMIN 인증 사용자, When 위 임의 엔드포인트 호출, Then 403. 미인증이면 401.

## 기능 요구사항 (FR)

- **FR1**. 전역 `UserGroup`(id, name, description?, createdAt, updatedAt). name 전역 UNIQUE.
- **FR2**. `GroupMembership`(groupId, userId) N:M. 복합 PK. 멤버 추가 멱등(ON CONFLICT DO NOTHING).
- **FR3**. 그룹 CRUD API + 멤버 추가/제거/목록 API. 모두 SYSTEM_ADMIN 전용.
- **FR4**. 그룹 삭제 시 멤버십 ON DELETE CASCADE. 사용자 삭제(외부) 시 그 멤버십 CASCADE.
- **FR5**. 권한 판정은 기존 `SystemPermissionResolver.isSystemAdmin(actorId)` 재사용(신규 포트 0).

## 비기능 요구사항 (NFR)

- **NFR1 권한**. 모든 엔드포인트 SYSTEM_ADMIN. 컨트롤러가 actor(JWT subject→UUID, `resolveActor` 패턴) 추출 →
  `isSystemAdmin` false면 403, 미인증이면 401. (read 엔드포인트도 SYSTEM_ADMIN — 최소권한; 비관리자 read 확대는 소비 FR 위임.)
  **가드 메커니즘 = DB 기반 `SystemPermissionResolver.isSystemAdmin(actorId)` 수동 호출**(FR-PM-04 동형). `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`(JWT roles claim 기반 authority) 사용 안 함 — (1) claim이 stale일 수 있고 DB가 ground-truth, (2) PAT는 roles claim이 없어 hasRole로는 항상 거부되나 DB 판정은 PAT sysadmin도 일관 허용(EC8). 컨트롤러는 `@PreAuthorize("isAuthenticated()")`(UsersController 패턴)로 1차 인증만 강제하고, SYSTEM_ADMIN 판정은 핸들러 내 isSystemAdmin 가드.
- **NFR2 멱등**. 멤버 추가/제거는 멱등(중복 추가·없는 멤버 제거 모두 성공 204).
- **NFR3 동시성**. name 유니크 race는 DB UNIQUE 제약이 최종 방어 → 409로 매핑(advisory lock 불요).
- **NFR4 영속**. raw SQL(NamedParameterJdbcTemplate), RETURNING, ON CONFLICT. identity-access 모듈 표준.
- **NFR5 검증 ground-truth**. prod 프로파일 Testcontainers 통합테스트(SYSTEM_ADMIN 허용·비관리자 거부·멱등·CASCADE).

## API 인터페이스 (REST)

| 메서드 | 경로 | 권한 | 성공 | 실패 |
|---|---|---|---|---|
| POST | `/api/v1/groups` | SYSTEM_ADMIN | 201 GroupResponse | 400(name blank), 409(name 중복), 401/403 |
| GET | `/api/v1/groups` | SYSTEM_ADMIN | 200 [GroupResponse(+memberCount)] | 401/403 |
| GET | `/api/v1/groups/{groupId}` | SYSTEM_ADMIN | 200 GroupResponse | 404, 401/403 |
| PATCH | `/api/v1/groups/{groupId}` | SYSTEM_ADMIN | 200 GroupResponse | 400, 404, 409, 401/403 |
| DELETE | `/api/v1/groups/{groupId}` | SYSTEM_ADMIN | 204 | 404, 401/403 |
| GET | `/api/v1/groups/{groupId}/members` | SYSTEM_ADMIN | 200 [UserSummaryResponse] | 404, 401/403 |
| PUT | `/api/v1/groups/{groupId}/members/{userId}` | SYSTEM_ADMIN | 204 (멱등) | 404(group/user), 401/403 |
| DELETE | `/api/v1/groups/{groupId}/members/{userId}` | SYSTEM_ADMIN | 204 (멱등) | 404(group), 401/403 |

- **GroupResponse**: `{ id, name, description, memberCount?, createdAt, updatedAt }` (memberCount는 목록/단건에 스칼라 서브쿼리로, N+1 회피).
- **요청 본문**: 생성/수정 `{ name: String(1..255, trim, non-blank), description: String?(0..500) }`.
- **에러 코드(snake_case, ProjectMemberController 패턴)**: `group_name_conflict`, `group_not_found`, `user_not_found`, `group_name_invalid`, `forbidden`, `unauthorized`.

## 데이터 모델 변경 (V015)

```sql
CREATE TABLE user_groups (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE group_memberships (
    group_id   UUID        NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id)       ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX ix_group_memberships_user ON group_memberships(user_id);  -- 사용자별 그룹 역조회(소비 FR 대비)
```

- identity-access는 jOOQ 미사용 → init_codegen 미러 불요.
- V번호는 머지 직전 재확인(동시 브랜치 충돌 방지).

## 엣지 케이스

- **EC1 name 중복 생성/수정** → 409 `group_name_conflict`. DB UNIQUE 위반(`DuplicateKeyException`)을 409로 매핑.
- **EC2 없는 그룹 수정/삭제/멤버추가/멤버목록** → 404 `group_not_found`.
- **EC3 없는 사용자 멤버 추가** → 404 `user_not_found`. FK 위반 전 사전조회로 깔끔한 404(원시 FK 위반 500 회피).
- **EC4 이미 멤버 추가** → 204 멱등(ON CONFLICT DO NOTHING). version/타임스탬프 변동 없음.
- **EC5 멤버 아닌 사용자 제거** → 204 멱등(no-op).
- **EC6 그룹 삭제 시 멤버십** → CASCADE 자동 삭제(별도 로직 불요). 공유 Testcontainers cleanup 연쇄 방지 효과도.
- **EC7 name 정규화** → 저장 전 trim. 빈 문자열/공백만 → 400 `group_name_invalid`. 유니크는 case-sensitive(대소문자 구분); case-insensitive 유니크는 후속 필요 시 lower() 인덱스로.
- **EC8 PAT로 관리 호출** → `isSystemAdmin`은 DB(system_role_assignments) 기반 판정이라 토큰 종류 무관하게 actor userId로 판정. PAT 사용자라도 그 userId가 SYSTEM_ADMIN이면 통과(FR-PM-08 resolver가 DB 기반·프로파일 무관인 점과 일관). 토큰 종류로 추가 차단 안 함.

- **EC9 멤버 목록 규모**. `GET /members`는 현재 전체 반환(1K 사용자 규모 허용). 대형 그룹 페이지네이션은 후속(소비 UI FR 시점에 필요 시 query/limit 추가, UsersController 선례).

## 범위 밖 (명시 분리)

- **감사 로그**. 그룹 생성/삭제/멤버 변경의 audit emit은 본 FR 범위 밖 → FR-AU-10(인증 감사 로그) 후속(FR-PM-08 audit emit 후속 처리와 동형). 본 FR은 관리 동작만.
- **소비처 결선**. 보안 수준 멤버(FR-PM-06)·권한 스킴 grants(§12.2)·그룹 멘션(§9)은 각 소비 FR.
- **관리 UI / E2E**(D6/D7), **LDAP 그룹 동기화**(SDD 19.8.1)는 후속 FR.

## 제약 조건

- BC 격리. identity-access 단일 BC. 다른 BC 직접 import 없음. users 테이블 FK는 같은 모듈 내.
- 신규 권한 코드/포트 0(기존 SystemPermissionResolver 재사용). role_permissions 시드 무변경 → PermissionSchemaMigrationTest 카운트 비영향.
- enum 무변경 → cross-module 카운트 가드 비영향.
- 완제품 기준(DEVELOPMENT.md §1): 입력 검증·권한·에러 처리·테스트 모두 충족.

## 측정 가능한 완료 기준

- [ ] V015 마이그레이션 적용 → user_groups/group_memberships 생성, FK CASCADE 동작.
- [ ] 그룹 CRUD + 멤버 추가/제거/목록 8개 엔드포인트 동작.
- [ ] prod 프로파일 Testcontainers 통합테스트: S1~S8 전 시나리오(특히 S8 거부 ground-truth, EC4/EC5 멱등, EC6 CASCADE).
- [ ] 백엔드 3모듈(shared-kernel·identity-access 영향 범위) test + ktlint(Main/Test) + detekt 그린, 회귀 0.
- [ ] 신규 권한 코드/enum/시드 0 → 기존 카운트 가드 테스트 무영향 확인.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 3건 보강 — (A) 감사 로그 FR-AU-10 후속 명시(범위 밖), (B) 권한 가드 = DB 기반 isSystemAdmin 수동 가드로 못 박음(claim/@PreAuthorize hasRole 비사용, PAT 일관 EC8), (C) 멤버 목록 페이지네이션 후속 플래그(EC9). Maxi 결정 필요 gap 없음.
