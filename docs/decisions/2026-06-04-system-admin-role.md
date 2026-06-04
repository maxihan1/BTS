# ADR — 전역 시스템 관리자 역할 + 전역 권한 인프라

> 날짜: 2026-06-04
> 상태: 채택 (PR #75, FR-PM-04 선행)
> BC: identity-access
> 관련 SDD: [12. 권한 모델](../sdd/12-permissions.md) (12.3 시스템 권한 · 12.6 OrgAdmin), [19. 인증](../sdd/19-authentication.md)
> 선행 ADR: [issue-permission-scheme-model](2026-06-02-issue-permission-scheme-model.md) · [project-membership-model](2026-06-01-project-membership-model.md) · [version-component-permission-prod-resolver](2026-06-03-version-component-permission-prod-resolver.md)
> 선행 해소 대상: FR-PM-04(전역 워크플로우 스킴 관리) · FR-AU-05(회원가입)

## 맥락

BTS의 모든 권한은 현재 **프로젝트 단위**다. `ProjectRole`(PROJECT_ADMIN/MEMBER 2종) + `project_memberships` + `permission_schemes`/`role_permissions` 매트릭스로, "특정 프로젝트 안에서 무엇을 할 수 있나"만 판정한다.

**전역(시스템) 역할은 데이터·JWT·판정 어디에도 없다.**

- SDD 12.6의 역할 8종(OrgAdmin 포함)은 FR-PM-01 ADR이 stale 폐기 선언했다. OrgAdmin/`ADMIN_SYSTEM`(SDD 12.3)은 문서에만 존재한다.
- `IssueScope.Global` 권한은 prod resolver(`IdentityAccessIssuePermissionResolver`)가 `resolveProjectId`에서 `null`을 반환해 **무조건 거부**한다(메모리 `issue-scope-global-prod-hard-deny`).
- JWT(`JwtIssuer`)는 BTS 자체 발급(Nimbus-JOSE-JWT, RS256)이고 claim에 역할 정보가 없다 — 권한은 매 요청 DB 조회.

이 공백이 두 후속 작업을 막고 있다.
- **FR-PM-04**: 워크플로우 스킴은 전역 자원(Jira Cloud 동일) → 전역 `MANAGE_WORKFLOW` 판정 주체가 필요.
- **FR-AU-05 회원가입**: 관리자 계정 생성/승인 흐름에 시스템 관리자 개념이 필요.

본 PR은 **인프라만**(Maxi 2026-06-04) 도입한다. 실제 관리 엔드포인트·UI는 FR-PM-04/FR-AU-05 소관.

## 결정

### D1 — 전역 역할 저장 = 별도 테이블 `system_role_assignments`

`project_memberships`(FR-PM-01)와 동형의 별도 테이블에 "어떤 사용자가 어떤 전역 역할을 갖는가"를 행으로 저장한다.

```sql
CREATE TABLE system_role_assignments (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       VARCHAR(32) NOT NULL CHECK (role IN ('SYSTEM_ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, role)
);
```

- **기각: `users.system_role` 컬럼.** 사용자당 단일 역할만 가능하고 NULL의 의미가 모호(일반 사용자? 미설정?). 미래에 전역 역할이 여러 종(감사자 등)으로 늘면 컬럼 추가가 누적된다.
- 별도 테이블은 `project_memberships`와 같은 패턴이라 일관적이고, 역할 종류가 늘어도 `CHECK` 제약에 값만 추가하면 된다(스키마 구조 불변).
- `project_id`가 없다는 점이 프로젝트 멤버십과의 핵심 차이 — 전역 역할은 프로젝트와 무관한 별도 축이다.

### D2 — 역할명 = `SYSTEM_ADMIN` 단일 역할로 도입

SDD 12.6의 `OrgAdmin`을 `SYSTEM_ADMIN`으로 명명해 도입한다(Jira "System Admin" 정신, "시스템 전체 관리"가 더 명확). 이번엔 단일 역할만. 미래 전역 역할은 `CHECK` 제약에 행 추가로 확장.

- `ProjectRole` enum에 섞지 **않는다**. 프로젝트 역할(프로젝트 안에서의 권한)과 전역 역할(시스템 전체)은 서로 다른 축이다. 별도 `SystemRole` enum으로 분리한다.

### D3 — 전역 판정 = `SYSTEM_ADMIN` 보유 여부 (단일 역할 직접 판정)

프로젝트 권한처럼 `role_permissions` 매트릭스로 가지 **않는다**. 단일 역할이므로 "SYSTEM_ADMIN 역할을 가졌는가"로 전역 권한을 직접 판정한다.

- 전역 판정기 포트는 shared-kernel(`com.bts.shared.permission`)에 둔다 — 여러 BC(FR-PM-04 등)가 의존할 공용 기반(`IssuePermissionResolver` 선례, "권한 = 공용 기반" 정신).
- 미래에 전역 권한이 세분화되면(예: 감사자는 `VIEW_AUDIT_LOG`만) 그때 전역 매트릭스를 도입한다. 단일 역할 단계에서 매트릭스는 과설계.

### D4 — JWT 전역 역할 클레임 추가

`JwtIssuer`가 로그인 시 `system_role_assignments`를 조회해 access token에 전역 역할 클레임(`system_roles` 또는 `roles`)을 담는다. JWT converter(`SidRevokeJwtConverter`의 delegate)가 이를 `ROLE_SYSTEM_ADMIN` authority로 변환.

- **트레이드오프 (stale)**: access token TTL이 15분이라, 역할을 박탈해도 최대 15분간 토큰에 잔존한다. 시스템 관리자는 소수이고 역할 변경이 드물어 수용 가능. 즉시 무효화가 필요한 경우는 후속(권한 변경 시 세션 revoke)에서 다룬다.
- **(2026-06-04 plan 단계 정정)** 전역 판정기(`SystemPermissionResolver`, `actorId` 입력)는 **DB 조회**로 판정한다 — `IssuePermissionResolver`/`ComponentPermissionResolver` 선례와 일관(항상 정확, 임의 사용자 질의 가능). JWT의 `roles` 클레임 → `ROLE_SYSTEM_ADMIN` authority는 **선언적 보안(@PreAuthorize) 경로로 병행**한다. 둘은 독립 경로다. (당초 "판정기는 claim 기반" 구상은 `actorId` 포트 시그니처와 맞지 않아 정정.)
- 판정기는 단순 DB 조회라 `@Profile` 분리(prod/non-prod stub)가 **불필요** — 모든 프로파일에서 실제 판정한다. `AlwaysAllow*` 같은 stub 없음 → 메모리 `profile-scoped-bean-boot-failure` 함정 원천 회피.

### D5 — 최초 관리자 부트스트랩 = 설정값 기반 멱등 승격

앱 기동 시 `bts.bootstrap.admin-username` 설정값을 읽어 해당 사용자에게 `SYSTEM_ADMIN`을 부여한다.

- **멱등**: 이미 `SYSTEM_ADMIN`이 1명이라도 존재하면 아무것도 하지 않는다(skip). 설정값이 비었거나 해당 username이 없으면 경고 로그만.
- 마이그레이션 고정 INSERT를 **기각**: 운영에서 어느 실제 계정을 박을지 미정이고, dev의 alice를 prod에 하드코딩할 수 없다. 설정값은 환경별(dev/prod)로 다르게 줄 수 있다.
- 구현: `ApplicationRunner`(시각 의존 없음). 닭-달걀 문제(관리자를 임명할 관리자가 없음)를 운영자가 설정으로 해소.

### D6 — 결선 범위 = 순수 토대 + 통합테스트 검증

본 PR은 **역할 저장 + JWT 클레임 + 전역 판정기 + 부트스트랩**까지. 실제 관리 엔드포인트/UI는 FR-PM-04/FR-AU-05.

- **dead code 회피**(CLAUDE.md §2): 판정기가 "진짜 막고 통과시키는가"를 통합테스트(@ActiveProfiles prod)로 검증한다 — SYSTEM_ADMIN 사용자 → 전역 판정 true, 일반 사용자 → false, 부트스트랩 멱등성.
- `IssueScope.Global` 실결선(현재 하드거부 → SYSTEM_ADMIN 통과)은 본 PR에서 **하지 않는다**. 이는 issue-tracking BC 결선이라 한 PR = 한 BC 원칙에 닿고, 실제 소비자(FR-PM-04 전역 워크플로우)가 없으면 또 다른 형태의 미검증 코드다. FR-PM-04가 전역 판정기를 사용할 때 결선.

## 결과 / 트레이드오프

- **산출물**: `system_role_assignments` 마이그레이션(V012) + `SystemRole` enum + Repository + 전역 판정기 포트(shared-kernel) + identity-access 판정기 구현 + `JwtIssuer` 클레임 확장 + 부트스트랩 `ApplicationRunner` + 통합테스트.
- **장점**: FR-PM-04·FR-AU-05의 공통 선행 해소. 프로젝트 권한과 독립된 깨끗한 전역 축. 미래 역할/권한 확장 여지(행 추가).
- **비용**: JWT 클레임 stale(15분). 전역 판정기가 본 PR에서 실 엔드포인트 소비자 없이 통합테스트로만 검증됨(FR-PM-04에서 실사용).
- **무효화**: FR-PM-01 ADR이 stale 폐기한 SDD 12.6 8종 역할 중 OrgAdmin을 `SYSTEM_ADMIN` 단일 역할로 부분 복원(8종 전체 복원 아님).
