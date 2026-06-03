# ADR — 버전/컴포넌트 권한 prod 리졸버 + MANAGE_* 매트릭스 시드 (FR-PM-03)

> 날짜: 2026-06-03
> 상태: 채택
> BC: identity-access
> 관련 FR: FR-PM-03 (버전/컴포넌트 등록 권한)
> 선행 FR: FR-PM-02(권한 스킴/매트릭스, PR #53) · FR-CM-01(컴포넌트 CRUD, PR #59) · FR-VR-01(버전 CRUD, PR #67)
> 선례 ADR: 2026-06-02-issue-permission-scheme-model · 2026-05-22-issue-permission-resolver-port
>          · 2026-06-02-component-model-and-permission-deferral · 2026-06-03-version-model-and-permission-deferral (이연 결정)

## 맥락

FR-CM-01/FR-VR-01이 컴포넌트·버전 CRUD를 구현하면서 권한 판정을 두 포트
(`ComponentPermissionResolver`, `VersionPermissionResolver`, shared-kernel)로 추상화하고,
prod 실판정을 FR-PM-03으로 이연했다. 현재 prod 구현이 없어 운영 부팅 시 `BeanCreationException`으로
차단되고, 개발/테스트는 `AlwaysAllow*PermissionResolver`(`@Profile("!prod")`)가 통과시킨다.

FR-PM-03은 두 포트의 prod 구현 + `permission_schemes`/`role_permissions` 매트릭스를 채워
"누가 버전/컴포넌트를 관리하나"를 실제로 판정한다. FR-PM-02의
`IdentityAccessIssuePermissionResolver`가 거의 1:1 선례다.

## 결정

### D1 — 권한 코드: SDD 12.3의 단일 MANAGE_* 코드 사용
SDD 12.3 정본을 따른다. 이슈(FR-PM-02)가 operation별 코드(CREATE_ISSUE/EDIT_ISSUE/DELETE_ISSUE)로
나뉜 것과 달리, 컴포넌트·버전은 **도메인당 단일 관리 권한**으로 묶는다(Jira 동일).

| 포트 권한 | permission_code |
|---|---|
| `ComponentPermission.{CREATE, UPDATE, DELETE}` | `MANAGE_COMPONENTS` |
| `VersionPermission.{CREATE, UPDATE, DELETE}` | `MANAGE_VERSIONS` |

매핑 함수는 `when` else 없이 3종 전부 명시해 enum drift를 컴파일 에러로 차단한다
(`IssuePermission.toCodeOrNull` 선례). 단, 세 값이 같은 코드로 매핑되므로 nullable이 아닌
non-null 반환(범위 밖 개념 없음).

### D2 — prod 리졸버 2개를 identity-access BC에 추가, @Profile("prod")
`IdentityAccessComponentPermissionResolver` + `IdentityAccessVersionPermissionResolver`를
`com.atlas.bts.identity.permission` 패키지에 추가한다(`IdentityAccessIssuePermissionResolver` 동형).

판정 알고리즘(IssuePermissionResolver 동형, 단 scope 해석 단계 제거 — 포트가 `projectId`를 직접 받음):
1. 멤버 게이트 — `ProjectMembershipRepository.findByProjectAndUser(projectId, actorId)`가 null(비멤버)이면 `false`.
2. 매트릭스 판정 — `PermissionSchemeRepository.roleHasPermission(projectId, membership.role.name, code)`.

비멤버 완전 차단(deny-by-default 하한)은 FR-PM-02와 동일한 안전 포스처다.

### D3 — `@Profile("prod")` 배타 + non-prod 부팅 가드 유지
- prod impl: `@Profile("prod")`.
- non-prod stub: 기존 `AlwaysAllow*PermissionResolver`(`@Profile("!prod")`) 유지.
- 두 Bean이 동시에 활성화되지 않는다(`@Profile` 배타성). 메모리 `profile-scoped-bean-boot-failure`
  함정(@Profile prod 단독 주입 → 비prod 컨텍스트 부팅 실패) 회피 — non-prod에는 항상 stub이 존재.

### D4 — 새 마이그레이션으로 MANAGE_* 매트릭스 시드 (PROJECT_ADMIN 전용)
"FR-PM-02 활용"은 `permission_schemes`/`role_permissions` **테이블 재사용**을 의미하나,
두 코드 시드 행은 부재하므로 **새 Flyway 마이그레이션이 필요**하다. 기본 스킴
(`00000000-0000-0000-0000-000000000001`)에 다음 2행을 추가한다(Maxi 결정 2026-06-03, Jira 기본).

| scheme | role | permission_code |
|---|---|---|
| Default | PROJECT_ADMIN | MANAGE_COMPONENTS |
| Default | PROJECT_ADMIN | MANAGE_VERSIONS |

**MEMBER에는 부여하지 않는다** — 버전/컴포넌트 관리는 행정 성격(Jira 'Administer Projects' 계열).
MEMBER가 CRUD 호출 시 매트릭스 미보유로 `false` → 403.

> DATA.md 마이그레이션 규칙. jOOQ 상수 생성을 위해 init_codegen.sql 미러가 필요한지는
> 본 변경이 **컬럼 추가가 아닌 데이터 시드(INSERT)**라 해당 없음(메모리 `jooq-init-codegen-mirror`는
> 컬럼/스키마 변경 한정). 단 D3/spec 단계에서 V번호 충돌(FR-IS-07 등 동시 진행 브랜치) 재확인.

## 결과

- 산출물: prod 리졸버 2개 + 권한 코드 매핑 함수 2개 + 시드 마이그레이션 1개 + 권한 매트릭스 테스트
  (멤버/비멤버 × 역할 × 권한). 프론트(D6)/E2E(D7)는 plan에 따라 진행.
- 보안 포스처 전환: FR-PM-03 머지 후 prod에서 버전/컴포넌트 관리가 PROJECT_ADMIN 멤버로 한정된다
  (이전 임시 "인증되면 통과" 종료).
- 미해소: MANAGE_* 코드를 비기본 스킴/스킴 편집 UI로 부여하는 흐름은 별도 FR(스킴 관리) 소관.
