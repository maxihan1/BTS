# FR-PM-02 이슈 등록/수정/삭제 권한 분리 — 스펙 (백엔드 D1~D5)

> slug: fr-pm-02-issue-permissions · type: auth · BC: identity-access(소유) + issue-tracking(배선)
> 도메인 결정: [plan ## 도메인 정리](../plans/2026-06-02-fr-pm-02-issue-permissions.md) · ADR [issue-permission-scheme-model](../decisions/2026-06-02-issue-permission-scheme-model.md)
> 작성: 2026-06-02 (office-hours 스킵 — FR 정의 + grill 완료, 직접 기술 스펙)

## 0. 범위 선언

**포함(D1~D5)**: 권한 스킴 데이터 모델(permission_schemes/role_permissions/project_permission_scheme) + 기본 스킴 시드 + `IdentityAccessIssuePermissionResolver`(@Profile prod) adapter + 권한 매트릭스 전수 테스트.

**포함(스킴 공유)**: 스킴↔프로젝트 연결표 — 여러 프로젝트가 한 스킴 공유 가능한 구조. 미지정 프로젝트는 기본 스킴 fallback.

**제외**: 스킴 생성/수정/할당 **API·UI**(`MANAGE_PERMISSIONS`, 후속 — 이번엔 시드/마이그레이션으로만 스킴 구성) · 프론트 UI(D6) · E2E(D7) · 동적 역할(Reporter/Assignee, "자기 이슈만") · VIEW/TRANSITION 매트릭스 판정(FR-PM-05/04).

## 1. 사용자 시나리오 (Given-When-Then)

- **S1 (관리자 삭제 허용)**. Given alice가 ATLAS의 PROJECT_ADMIN. When ATLAS-1 소프트 삭제. Then 허용.
- **S2 (멤버 삭제 거부)**. Given bob이 ATLAS의 MEMBER. When ATLAS-1 소프트 삭제. Then 거부(403 ACCESS_DENIED).
- **S3 (멤버 생성/수정 허용)**. Given bob MEMBER. When 이슈 생성/ATLAS-1 수정. Then 허용.
- **S4 (비멤버 전면 차단)**. Given carol 비멤버. When 생성/조회/수정/삭제 어느 것이든. Then 거부(403).
- **S5 (범위 밖 멤버 통과)**. Given bob MEMBER. When ATLAS-1 조회(VIEW)/전이(TRANSITION). Then 허용(범위 밖, 멤버 게이트만).
- **S6 (범위 밖 비멤버 차단)**. Given carol 비멤버. When 조회/전이. Then 거부.
- **S7 (prod adapter 작동)**. Given prod. When 권한 판정. Then IdentityAccessIssuePermissionResolver가 role_permissions 조회.
- **S8 (dev/staging stub)**. Given !prod. When 판정. Then AlwaysAllow true(기존 동작 보존).

## 2. 기능 요구사항 (FR)

- **FR-1**. permission_schemes + role_permissions 신설(identity-access, V008).
- **FR-2**. 기본 스킴 1개 시드. 미매핑 프로젝트는 기본 스킴 적용.
- **FR-3**. 권한 코드(이번 범위): CREATE_ISSUE/EDIT_ISSUE/DELETE_ISSUE (SDD 12.3 명명).
- **FR-4**. 기본 매트릭스: PROJECT_ADMIN={C,E,D}, MEMBER={C,E}(DELETE 제외).
- **FR-5**. IdentityAccessIssuePermissionResolver(@Profile prod). 알고리즘: ① scope→projectId 해석(실패 false) ② 멤버십 조회(비멤버 false) ③ 범위 내→유효 스킴 매트릭스 ④ 범위 밖→멤버 통과 true.
- **FR-6**. IssuePermission→permission_code `when` exhaustive(컴파일 가드).
- **FR-7**. AlwaysAllow stub @Profile("!prod") 유지, 호출자 무변경.
- **FR-8**. project_permission_scheme 연결표 — 여러 프로젝트가 같은 scheme_id 공유. 미매핑은 is_default fallback. project_id cross-BC FK 없음.

## 3. 비기능 요구사항 (NFR)

- **NFR-1 (BC 격리)**. 계약 타입 shared-kernel(배선 B). issue-tracking은 인터페이스만 의존. ArchUnit issue→identity 금지 유지.
- **NFR-2 (성능)**. 멤버십 1회 + 매트릭스 1회 조회. enum 통역 무시. Redis 캐시는 범위 외(후속).
- **NFR-3 (보안)**. deny-by-default 하한(비멤버 차단). prod stub 미작동. prefix 명명 권한 혼동 차단.
- **NFR-4 (회귀 0)**. 평가 로직: 기존 테스트는 test 프로파일(!prod)=AlwaysAllow 유지 → 자동 0. prod adapter는 @ActiveProfiles("prod")+Testcontainers 별도. 배선 B 타입 이동은 전 테스트 import 영향 → 컴파일+전체 그린 가드.

## 4. scope → projectId 해석

| IssueScope | 해석 |
|---|---|
| Project(key) | ProjectDirectory.resolveKeyToId(key) |
| Issue(key) | key prefix(`ATLAS-1`→`ATLAS`, substringBefore('-')) → resolveKeyToId. issueKey prefix == projectKey 불변식 |
| Global | 본 FR 미사용, 보수적 false |

issue-tracking 데이터 미접근 — ProjectDirectory(projects read-only)만 사용.

## 5. 데이터 모델 변경 (V008, identity-access)

```sql
CREATE TABLE permission_schemes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(128) NOT NULL, description TEXT,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_permission_schemes_default ON permission_schemes(is_default) WHERE is_default = TRUE;

CREATE TABLE role_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id UUID NOT NULL REFERENCES permission_schemes(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL CHECK (role IN ('PROJECT_ADMIN','MEMBER')),
    permission_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (scheme_id, role, permission_code)
);
CREATE INDEX idx_role_permissions_scheme_role ON role_permissions(scheme_id, role);

CREATE TABLE project_permission_scheme (
    project_id UUID PRIMARY KEY,                          -- cross-BC 참조, FK 없음
    scheme_id UUID NOT NULL REFERENCES permission_schemes(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pps_scheme ON project_permission_scheme(scheme_id);

-- 기본 스킴 시드 (고정 UUID 00000000-0000-0000-0000-000000000001)
INSERT INTO permission_schemes (id, name, description, is_default) VALUES ('...001','Default Permission Scheme','기본 이슈 권한 스킴 (FR-PM-02)', TRUE);
INSERT INTO role_permissions (scheme_id, role, permission_code) VALUES
  ('...001','PROJECT_ADMIN','CREATE_ISSUE'),('...001','PROJECT_ADMIN','EDIT_ISSUE'),('...001','PROJECT_ADMIN','DELETE_ISSUE'),
  ('...001','MEMBER','CREATE_ISSUE'),('...001','MEMBER','EDIT_ISSUE');
```

- repository = NamedParameterJdbcTemplate (jOOQ 미사용 → init_codegen 미러 불필요).
- 유효 스킴 해석(D2): project_permission_scheme 매핑 있으면 그 scheme_id, 없으면 is_default 스킴(EXISTS 스칼라 서브쿼리, cartesian 회피).

## 6. 엣지 케이스

- **EC-1 (비멤버)**: 멤버십 없음 → false → 403. (VIEW 존재숨김은 FR-PM-05.)
- **EC-2 (프로젝트 없음)**: resolveKeyToId null → false.
- **EC-3 (스킴 미시드)**: 매트릭스 0건 → 범위 내 false(안전측). 마이그레이션 시드가 가드.
- **EC-4 (역할 CHECK)**: PROJECT_ADMIN/MEMBER만.
- **EC-5 (Global scope)**: false.
- **EC-6 (advisory lock 불필요)**: 권한 읽기 + 정적 시드, read-then-write race 없음.
- **EC-7 (HARD_DELETE)**: 범위 밖(멤버 게이트만). 엔드포인트 현재 없음.

## 7. 배선 결정 — 후보 B 확정

계약 타입(IssuePermissionResolver/IssuePermission/IssueScope)을 shared-kernel `com.bts.shared.permission`로 추출, 양쪽이 그것만 의존. 인터페이스 `hasPermission(actorId: UUID, …)` — ActorId는 issue 도메인 VO 잔류, 호출부 `actor.value`. A(identity→issue 직접 의존)는 god-dependency 누적으로 기각. shared-kernel 이미 cross-BC 계약 모듈 + 양쪽 의존 → 추가 의존 0. 그 외 제약: @PreAuthorize 미도입(G4), 403 유지, advisory lock 불필요.

## 8. 측정 가능한 완료 기준

- 권한 매트릭스 전수 통합테스트 13케이스: {ADMIN,MEMBER,비멤버}×{CREATE,UPDATE,SOFT_DELETE}(9) + 범위밖{VIEW,TRANSITION}×{멤버,비멤버}(4).
- 스킴 공유: 두 프로젝트 같은 scheme_id 동일 판정 + 미매핑 기본 fallback.
- prod 프로파일 adapter Bean 활성 + AlwaysAllow 비활성.
- issue-tracking 기존 단위/통합 테스트 회귀 0.
- V008 적용 + 기본 스킴/매트릭스/연결표 시드.
- detekt/ktlint 그린 + ArchUnit(issue→identity 금지) 그린.

## Brainstorming Check

✅ 통과 (self sanity check, [[bts-spec-office-hours-mismatch]] 정합). gap-1(NFR-4 회귀 0 모호 → 평가 로직/컴파일 레벨 분리) 해소. 잔여 미결(ActorId 처리)은 plan에서 옵션 b(UUID 수용)로 확정.
