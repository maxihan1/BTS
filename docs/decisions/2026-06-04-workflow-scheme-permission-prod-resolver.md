# ADR — 워크플로우 스킴 권한 포트 shared-kernel 이동 + prod 리졸버 (FR-PM-04)

> 날짜: 2026-06-04
> 상태: 채택
> BC: identity-access (prod 판정) + shared-kernel (포트 이동) + project-workflow (참조 갱신)
> 관련 FR: FR-PM-04 (워크플로우/자동화 관리 권한)
> 선행 FR: FR-PM-02(권한 스킴/매트릭스, PR #53) · FR-WF-02(프로젝트별 워크플로우 스킴 CRUD + 권한 포트 분리, PR #35)
> 선례 ADR: 2026-06-03-version-component-permission-prod-resolver (FR-PM-03 동형) · 2026-05-22-issue-permission-resolver-port

## 맥락

FR-WF-02가 워크플로우 스킴 CRUD를 구현하면서 권한 판정을 `WorkflowSchemePermissionResolver`
포트(project-workflow BC)로 추상화하고, prod 실판정을 FR-PM-04로 이연했다.
현재 non-prod는 `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`)가 통과시키고,
prod 프로파일에는 구현이 없어 운영 부팅 시 `BeanCreationException`으로 차단된다.
포트 KDoc이 "FR-PM-04 시 identity-access가 `IdentityAccessWorkflowSchemePermissionResolver`를
구현"한다고 명시적으로 예약했다.

그런데 한 가지 구조적 제약이 드러났다. `WorkflowSchemePermissionResolver` 포트는 `project-workflow`
BC 안(`com.bts.workflow.scheme.port.outbound`)에 있고, identity-access는 project-workflow를
의존하지 않으며 BC 격리 ArchUnit 룰상 의존할 수 없다. FR-PM-03의 컴포넌트/버전 포트가
shared-kernel에 있어 identity-access가 곧장 구현할 수 있었던 것과 다르다.

추가로 워크플로우/자동화 권한 두 축 중 자동화(`MANAGE_AUTOMATION`)는 automation BC(FR-AT-01~07)
자체가 미구현이라 가드를 붙일 엔드포인트가 없다.

## 결정

### D1 — 범위: 워크플로우 관리 권한(MANAGE_WORKFLOW)만 완결, 자동화는 연기

FR-PM-04의 두 권한 중 `MANAGE_WORKFLOW`만 prod 결선한다. `MANAGE_AUTOMATION`은
automation BC(FR-AT) 착수 시점에 동반 구현한다.

- **근거**: automation BC가 없어 `MANAGE_AUTOMATION` 시드는 소비처 0인 dead 시드가 된다
  (메모리 `no-cross-bc-deployment-assembly`). FR-PM-03이 "기능(CRUD) → 권한" 순서로 간 선례와
  일관 — 자동화는 아직 기능이 없으므로 권한만 먼저 두면 빈 껍데기다.
- **문서 정합**: `docs/plan/product/automation.md §0` 진입조건 "FR-PM-04 자동화 관리 권한 완료"를
  "FR-PM-04 워크플로우 부분 완료 + 자동화 권한은 본 BC 착수 시 동반"으로 조정한다.
- 권한 코드 `MANAGE_WORKFLOW`는 SDD 12.3(12-permissions.md:40) 정본 — 신규 생성이 아니다.

### D2 — `WorkflowSchemePermissionResolver` 포트를 shared-kernel로 이동

포트 interface + `WorkflowSchemePermission` enum + `WorkflowSchemeScope` sealed 계층을
`project-workflow` → `shared-kernel`(`com.bts.shared.permission`)로 이동한다.
FR-PM-03의 `ComponentPermissionResolver`/`VersionPermissionResolver` 배치와 동형.

- **actorId 타입**: 포트 시그니처를 `java.util.UUID`로 단순화한다(BC 공통 분모, shared-kernel 포트 관례).
  project-workflow의 `ActorId`/identity-access의 `UserId` 결합을 제거한다. 호출자가 UUID를 추출해 전달.
- **소비자 무변경 원칙 유지**: `WorkflowSchemeController`/service는 interface만 의존하므로
  import 경로(package)만 갱신된다. Guard 패턴(`requirePermission` 예외) 그대로.
- **주의**: 이동 시 참조처(project-workflow service/adapter/test의 `@Bean` 포함)를 전수 grep으로
  갱신해야 빌드 RED를 피한다(메모리 `archunit-shared-class-move-repository-package`).
- **non-prod stub**: `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`)는
  shared-kernel 포트를 구현하도록 import만 갱신해 유지한다.

### D3 — prod 리졸버를 identity-access BC에 추가, @Profile("prod")

`IdentityAccessWorkflowSchemePermissionResolver`(`@Component @Profile("prod")`)를
`com.atlas.bts.identity.permission` 패키지에 추가한다(`IdentityAccessComponentPermissionResolver` 동형).

판정 알고리즘의 핵심 — **Global scope 판정 모델은 spec 단계에서 정밀 설계한다(Maxi 결정)**.
워크플로우 스킴 생성/수정/삭제는 `WorkflowSchemeScope.Global`이나, 현재 `role_permissions` 매트릭스는
프로젝트 단위(스킴이 프로젝트에 묶임)다. 전역 자원의 "누가 관리하나"를 판정하는 모델이 미정의 상태다.
SDD 12.6 시스템 역할(OrgAdmin 등)과 기존 테이블을 근거로 spec에서 확정한 뒤 D3 알고리즘을 채운다.

- non-prod stub과 `@Profile` 배타성으로 두 Bean이 동시 활성화되지 않는다
  (메모리 `profile-scoped-bean-boot-failure` 회피 — non-prod에는 항상 stub 존재).

### D4 — 권한 매트릭스 시드 (MANAGE_WORKFLOW)

`MANAGE_WORKFLOW`를 `role_permissions`에 시드한다. 구체적인 scheme/role 배치는 D3의
Global scope 판정 모델 확정과 함께 spec 단계에서 결정한다(전역 자원이라 FR-PM-03의
"기본 스킴 + PROJECT_ADMIN" 단순 패턴이 그대로 맞지 않을 수 있음).

- identity-access는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님.
- 데이터 시드(INSERT)면 컬럼/스키마 변경이 아니므로 init_codegen.sql 미러 불요
  (FR-PM-03 V009 선례). 단 PermissionSchemaMigrationTest 정확 카운트 단언을 함께 갱신해야 한다
  (메모리 `fr-pm-permission-seed-migration-test-coupling`).

## 결과

- 워크플로우 스킴 CRUD가 prod에서 실제 권한 판정을 받는다(현재 stub 통과 → 실판정).
- 포트 이동으로 BC 격리를 지키며 identity-access가 prod 구현을 제공한다.
- 자동화 권한은 명시적으로 연기되어 dead 시드를 회피한다.
- Global scope 판정 모델은 spec에서 확정 — 본 ADR은 D3/D4를 spec 결정에 의존하는 미완 항목으로 표시한다.

## spec 단계 확정 (2026-06-05, 보류 해소 — FR-PM-08 인프라 완료 후)

D3/D4의 이연 항목을 spec(`docs/specs/2026-06-05-fr-pm-04-workflow-automation.md`)에서 다음과 같이 확정했다.

- **D3 (Global scope 판정)**: 워크플로우 스킴 CRUD(`MANAGE_SCHEME`/`Global`)는 **시스템 관리자 전용**.
  FR-PM-08이 제공한 `com.bts.shared.permission.SystemPermissionResolver.isSystemAdmin(UUID)`를 소비해 판정한다
  (Jira Cloud 모델 — 스킴은 전역 자원). prod 리졸버는 이 포트를 주입받아 호출(`SystemRoleAssignmentRepository` 직접 호출 아님).
- **D3 (Project scope 판정)**: 스킴 배정(`ASSIGN_SCHEME`/`Project(key)`)은 **프로젝트 관리자**.
  `ProjectDirectory.resolveKeyToId`(key→id, FR-PM-02 선례) → 멤버십 게이트 → `role_permissions` 매트릭스
  `roleHasPermission(projectId, role, "MANAGE_WORKFLOW")`로 판정(FR-PM-03 동형).
- **D4 (시드 배치)**: `MANAGE_WORKFLOW`를 기본 권한 스킴(`00000000-…-001`)의 `PROJECT_ADMIN` 역할에 1행 시드(V013).
  Global은 매트릭스를 거치지 않으므로 시드 불요 — 시스템 역할로 판정.
- **Guard 예외 BC 가로지름**: 포트가 Guard 패턴(throw)이라 prod 리졸버(identity-access)가 던지는
  `WorkflowSchemeAccessDeniedException`을 shared-kernel에 정의해 project-workflow 핸들러가 403으로 매핑한다
  (FR-PM-03 Boolean 방식과의 차이).
