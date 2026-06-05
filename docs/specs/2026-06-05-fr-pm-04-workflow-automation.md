# FR-PM-04 — 워크플로우 스킴 관리 권한 (prod resolver) — 스펙

> slug: fr-pm-04-workflow-automation · type: auth · agent: security-engineer · 작성: 2026-06-05
> 선행 해소: FR-PM-08(PR #75) 전역 시스템 관리자 인프라 완료 → 보류 해제.
> 도메인/ADR: `docs/plans/2026-06-03-fr-pm-04-workflow-automation.md ## 도메인 정리` + `docs/decisions/2026-06-04-workflow-scheme-permission-prod-resolver.md`

## 배경 — 채우는 공백

워크플로우 스킴(여러 이슈 타입에 워크플로우를 묶는 설정 묶음)의 생성·수정·삭제·매핑·배정 동작에는
이미 `WorkflowSchemePermissionResolver.requirePermission(...)` Guard가 서비스/컨트롤러에 결선돼 있다.
그러나 **prod 구현체가 없다**. 현재는 `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`)
stub이 모든 요청을 통과시키고, prod 프로파일에서는 Bean 미해소로 부팅이 차단된다.

FR-PM-04는 그 prod 구현체를 제공해 **운영 환경에서 실제 권한 판정이 작동**하게 하고 부팅을 가능하게 한다.

## 결정 요약 (Maxi 확정)

| 동작 | 권한 enum / scope | prod 판정 | 근거 |
|---|---|---|---|
| 스킴 생성·수정·삭제·매핑 추가/삭제 | `MANAGE_SCHEME` / `Global` | `SystemPermissionResolver.isSystemAdmin(actor)` (FR-PM-08) | Jira Cloud 모델 — 스킴은 전역 자원, 시스템 관리자 소관 (Maxi 2026-06-04) |
| 프로젝트에 스킴 배정 / 조회 | `ASSIGN_SCHEME` / `Project(key)` | 멤버십 게이트 + `role_permissions` 매트릭스에서 PROJECT_ADMIN의 `MANAGE_WORKFLOW` 보유 판정 | 프로젝트 관리자 모델, SDD 12.3 + 최소권한 (Maxi 2026-06-05) |

## 사용자 시나리오 (Given-When-Then)

### S1 — 시스템 관리자가 워크플로우 스킴 생성 (Global, 허용)
- **Given** 사용자 A가 `system_role_assignments`에 `SYSTEM_ADMIN`을 보유 (prod 프로파일)
- **When** `POST /api/v1/workflow-schemes` 로 새 스킴 생성 요청
- **Then** `isSystemAdmin(A)==true` → 통과, 스킴 생성됨 (201)

### S2 — 일반 사용자가 워크플로우 스킴 생성 (Global, 거부)
- **Given** 사용자 B가 전역 역할 없음 (어떤 프로젝트의 PROJECT_ADMIN이더라도)
- **When** `POST /api/v1/workflow-schemes` 요청
- **Then** `isSystemAdmin(B)==false` → `WORKFLOW_SCHEME_ACCESS_DENIED` 예외 → 403

### S3 — 프로젝트 관리자가 자기 프로젝트에 스킴 배정 (Project, 허용)
- **Given** 사용자 C가 프로젝트 `ATLAS`의 멤버이며 역할이 `PROJECT_ADMIN`, 해당 스킴(스킴이 묶인 권한 스킴)에 `MANAGE_WORKFLOW` 부여됨
- **When** `PUT /api/v1/projects/ATLAS/workflow-scheme` 로 스킴 배정
- **Then** 멤버십 확인 + 매트릭스 `roleHasPermission(projectId, "PROJECT_ADMIN", "MANAGE_WORKFLOW")==true` → 통과 (200)

### S4 — 일반 멤버가 스킴 배정 (Project, 거부)
- **Given** 사용자 D가 프로젝트 `ATLAS`의 `MEMBER` (PROJECT_ADMIN 아님)
- **When** 스킴 배정 요청
- **Then** 매트릭스에 MEMBER의 `MANAGE_WORKFLOW` 없음 → 거부 → 403

### S5 — 비멤버가 스킴 배정 (Project, 거부 — deny-by-default 하한)
- **Given** 사용자 E가 프로젝트 `ATLAS`의 멤버가 아님
- **When** 스킴 배정 요청
- **Then** 멤버십 게이트에서 즉시 거부 (매트릭스 조회 전) → 403

### S6 — 존재하지 않는 프로젝트 키로 배정
- **Given** projectKey가 어떤 프로젝트에도 매핑되지 않음
- **When** 배정 요청
- **Then** key→id 해석 실패 → 거부(403, 정보 노출 최소화 — 404 대신 권한 거부로 통일)

## 기능 요구사항 (FR)

- **FR-1** prod 프로파일에서 `WorkflowSchemePermissionResolver` Bean이 `IdentityAccessWorkflowSchemePermissionResolver`(`@Profile("prod")`)로 해소되어 부팅이 성공한다.
- **FR-2** `MANAGE_SCHEME`/`Global` 호출은 `SystemPermissionResolver.isSystemAdmin(actor.UUID)`가 `true`일 때만 통과한다.
- **FR-3** `ASSIGN_SCHEME`/`Project(key)` 호출은 (a) projectKey→projectId 해석, (b) 멤버십 존재, (c) 해당 역할의 `MANAGE_WORKFLOW` 매트릭스 보유 — 셋 모두 만족할 때만 통과한다.
- **FR-4** 권한 미보유 시 `requirePermission`은 Guard 패턴으로 예외를 던진다. **예외 타입은 shared-kernel에 정의**(`WorkflowSchemeAccessDeniedException` 등)하여 identity-access(throw 주체)와 project-workflow(매핑 주체)가 모두 참조 가능해야 한다. project-workflow `WorkflowSchemeExceptionHandler`에 핸들러 1건 추가 → 403 + `WORKFLOW_SCHEME_ACCESS_DENIED` errorCode. (FR-PM-03는 `Boolean hasPermission`이라 예외가 소비 BC 내부였으나, 본 포트는 Guard 패턴이라 예외가 BC를 가로지름 — 이 차이가 핵심.)
- **FR-5** `MANAGE_WORKFLOW` permission_code가 기본 권한 스킴의 `PROJECT_ADMIN` 역할에 시드된다(신규 마이그레이션 V013).
- **FR-6** non-prod(dev/test/staging)에서는 기존 `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile("!prod")`)가 그대로 통과시킨다(기존 동작 유지).
- **FR-7** 포트 계약(`WorkflowSchemePermissionResolver` + `WorkflowSchemePermission` + `WorkflowSchemeScope`)을 project-workflow → shared-kernel로 이동한다(FR-PM-03 선례). 시그니처의 `actor: ActorId`는 **`actorId: UUID`로 단순화**(BC 공통 분모, 도메인 D2). 따라서 호출부(8곳)는 `actor` → `UUID.fromString(actor.raw)` 추출만 변경되고, **`permission`/`scope` 인자와 Guard 호출 구조는 무변경**이다. `WorkflowSchemeScope.Project(key: String)`는 plain String이라 이동에 BC 의존 없음.

## 비기능 요구사항 (NFR)

- **NFR-1 (보안)** deny-by-default. 비멤버·미해석 키·역할 미보유는 모두 거부. AlwaysAllow는 prod에서 절대 활성화되지 않음(`@Profile` 배타성 + 부팅 가드).
- **NFR-2 (BC 격리)** identity-access는 project-workflow를 import하지 않는다. 계약은 shared-kernel 경유. ArchUnit 룰 통과.
- **NFR-3 (감사)** PII 비노출 — 로그에 actor raw UUID/permission enum/scope만(기존 stub 로그 포스처 유지).
- **NFR-4 (회귀 0)** 기존 project-workflow/identity-access 테스트 전부 그린. 포트 이동에 따른 참조처 전수 갱신.

## API 인터페이스 (REST)

**신규 엔드포인트 없음.** 기존 엔드포인트에 prod 권한 게이트만 활성화된다.

- `POST/PUT/DELETE /api/v1/workflow-schemes/**` (`WorkflowSchemeController`) — `MANAGE_SCHEME`/`Global`
- `PUT/GET /api/v1/projects/{key}/workflow-scheme` (`ProjectWorkflowSchemeController`) — `ASSIGN_SCHEME`/`Project(key)`

거부 응답 — 403, body `{ "errorCode": "WORKFLOW_SCHEME_ACCESS_DENIED", ... }` (project-workflow BC 에러 응답 관례).

## 데이터 모델 변경

- **V013 마이그레이션 (identity-access)** — 기본 권한 스킴(`00000000-...-001`)의 `PROJECT_ADMIN` 역할에 `MANAGE_WORKFLOW` permission_code 1행 INSERT. (V009 `MANAGE_COMPONENTS`/`MANAGE_VERSIONS` 시드 미러)
- 스키마 DDL 변경 없음 → jOOQ 코드젠/`init_codegen.sql` 미러 불요(데이터 시드만).
- **주의** `PermissionSchemaMigrationTest`의 role_permissions 정확 카운트 단언을 +1 갱신 (메모리 `fr-pm-permission-seed-migration-test-coupling`).

## 엣지 케이스

- **EC1** prod 부팅 시 `WorkflowSchemePermissionResolver` Bean 충돌/미해소 — `@Profile("prod")` 단일 빈만 활성, stub은 `!prod`. 부팅 통합테스트(prod 프로파일)로 검증.
- **EC2** `ActorId.raw`(UUID String) → `java.util.UUID` 변환 — 포트 시그니처가 `ActorId`이므로 prod 구현 내부에서 `UUID.fromString(actor.raw)` 변환. (ActorId가 이미 UUID 형식 강제하므로 안전)
- **EC3** PAT(개인 액세스 토큰) actor — PAT는 전역 역할 제외(FR-PM-08 EC7). PAT로 스킴 CRUD 시도 시 `isSystemAdmin==false`로 거부됨(의도된 동작).
- **EC4** 표준 스킴(is_default) 핵심 필드 변경도 `MANAGE_SCHEME` 요구 — 기존 서비스 로직 유지, 권한 게이트는 동일.
- **EC5** key→id 해석은 **identity-access의 `ProjectDirectory.resolveKeyToId(key): UUID?`를 사용**한다(FR-PM-02 `IdentityAccessIssuePermissionResolver`가 `IssueScope.Project`에서 동일 사용). project-workflow의 `JdbcProjectLookupAdapter`는 그 BC 전용이라 identity-access가 import 불가(BC 격리) — 사용 금지. 해석 실패(null)는 거부(403).
- **EC6** non-prod 통합테스트가 AlwaysAllow로 거부 경로를 가린다 — 거부(403) 검증은 **prod 프로파일 통합테스트**가 ground-truth (메모리 `issue-scope-global-prod-hard-deny` · `fr-pm-permission-seed-migration-test-coupling`).
- **EC7** Guard 예외가 BC를 가로지름 — identity-access prod resolver가 throw, project-workflow handler가 catch. 예외 타입이 shared-kernel에 없으면 컴파일 불가/매핑 누락(403이 아닌 500). 포트 이동 PR에서 예외 타입 + 핸들러를 함께 결선해야 함(부분 구현 시 dead path).

## Brainstorming Check

✅ 통과 (1회 iteration). 적대적 점검에서 2건 갭 발견 후 보강.
- (갭1) 포트 이동 시 `actor: ActorId → UUID` 시그니처 변경 → 호출부 8곳 최소 변경 명시 (FR-7 정정).
- (갭2) Guard 패턴 예외가 BC를 가로지름(FR-PM-03 Boolean과 다름) → 예외 타입 shared-kernel 배치 + project-workflow 핸들러 결선 명시 (FR-4 정정 · EC7 추가).

## 제약 조건

- 한 PR = 한 BC 원칙이나, 본 FR은 (a) shared-kernel 포트 이동, (b) project-workflow 참조 갱신, (c) identity-access prod 구현 — 3모듈 접촉. FR-PM-03 선례와 동일 구조이므로 단일 PR 유지(포트 이동은 본질적으로 다모듈).
- 자동화 관리 권한(`MANAGE_AUTOMATION`)은 automation BC 부재로 **범위 제외**(도메인 D1). 소비처 0 dead 시드 금지.

## 측정 가능한 완료 기준

1. prod 프로파일 통합테스트에서 S1(허용)·S2(거부)·S3(허용)·S4·S5(거부) 전부 기대 상태코드.
2. `PermissionSchemaMigrationTest` 갱신 후 그린(MANAGE_WORKFLOW 시드 카운트 반영).
3. project-workflow + identity-access + shared-kernel 모듈 전체 `test` 그린, 회귀 0.
4. ArchUnit BC 격리 룰 통과(identity-access ↛ project-workflow).
5. ktlint + detekt 4모듈 그린.
6. prod 부팅(test-assembled boot) 성공 — `WorkflowSchemePermissionResolver` 해소 확인.
