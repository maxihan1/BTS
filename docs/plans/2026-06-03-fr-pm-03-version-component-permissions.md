# FR-PM-03 — 버전/컴포넌트 등록 권한

> slug: fr-pm-03-version-component-permissions
> type: auth
> agent: security-engineer (주축) + db-engineer(D3) + frontend-engineer(D6) + qa-engineer(D7)
> primary_bc: identity-access
> 생성: 2026-06-03

## Brief

FR-PM-03 — 버전/컴포넌트 등록 권한. 두 권한 리졸버 포트(`ComponentPermissionResolver`,
`VersionPermissionResolver`)의 prod 구현 + `permission_schemes` 매트릭스를 채운다.

- 선행 충족. FR-CM-01(컴포넌트 CRUD, PR #59) + FR-VR-01(버전 CRUD, PR #67) 모두 완료.
  두 리졸버는 현재 포트로 추상화되어 prod 실판정이 FR-PM-03으로 이연된 상태.
- 관련 ADR. docs/adr/2026-06-02-component-model-and-permission-deferral.md,
  docs/adr/2026-06-03-version-model-and-permission-deferral.md
- plan 문서. docs/plan/product/identity-access.md §4.3
- classify 정정. classify-task.ts가 '버전/컴포넌트' 단어로 ui/frontend 오분류 →
  plan §4.3 근거로 auth/security-engineer 정정.

### 산출물(D1~D7, plan §4.3)
- D1. 도메인 (security-engineer)
- D2. 명세 (security-engineer)
- D3. 데이터 모델 — FR-PM-02 활용(신규 마이그레이션 여부 spec에서 확정) (db-engineer)
- D4. 백엔드 — `@PreAuthorize` 추가 / 두 리졸버 prod 구현 (security-engineer)
- D5. 백엔드 테스트 (security-engineer)
- D6. 프론트 UI (frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리

- BC: identity-access (prod 리졸버 구현 위치). 포트는 shared-kernel, AlwaysAllow stub은 issue-tracking.
- 영향 컴포넌트:
  - 신규. `IdentityAccessComponentPermissionResolver`, `IdentityAccessVersionPermissionResolver`
    (`com.atlas.bts.identity.permission`, `@Profile("prod")`)
  - 신규. 권한 코드 매핑 함수 2개 (ComponentPermission/VersionPermission → MANAGE_*)
  - 신규. Flyway 마이그레이션 (기본 스킴에 MANAGE_COMPONENTS/MANAGE_VERSIONS 시드 2행)
  - 재사용. `ProjectMembershipRepository`, `PermissionSchemeRepository`(roleHasPermission), `permission_schemes`/`role_permissions`
- 새 용어: 없음 (권한 스킴/권한 코드/역할-권한 매트릭스 모두 glossary 기존). 새 권한 코드
  `MANAGE_COMPONENTS`/`MANAGE_VERSIONS`는 SDD 12.3 정본의 인스턴스 — 신규 유비쿼터스 언어 아님.
- 권한 코드 매핑 (SDD 12.3, Jira식 단일 관리 권한):
  - ComponentPermission.{CREATE,UPDATE,DELETE} → `MANAGE_COMPONENTS`
  - VersionPermission.{CREATE,UPDATE,DELETE} → `MANAGE_VERSIONS`
- 역할 시드 (Maxi 결정 2026-06-03): **PROJECT_ADMIN 전용** (MEMBER 미부여, 403). Jira 'Administer Projects' 계열.
- 판정 알고리즘: 멤버 게이트(비멤버 false) → 매트릭스(roleHasPermission). IssuePermissionResolver 동형,
  단 포트가 projectId 직접 수령이라 scope 해석 단계 없음.
- 기존 결정 충돌: 없음. 두 이연 ADR(component/version deferral)이 FR-PM-03이 채우도록 설계함.
- 관련 ADR:
  - [docs/decisions/2026-06-03-version-component-permission-prod-resolver.md](../decisions/2026-06-03-version-component-permission-prod-resolver.md) (생성됨)
  - 선례. docs/decisions/2026-06-02-issue-permission-scheme-model.md, docs/adr/2026-05-22-issue-permission-resolver-port.md
- 회귀 가드 메모: `profile-scoped-bean-boot-failure`(@Profile prod 단독 → 비prod 부팅 실패),
  `best-effort-loop-permission-exception-nonprod-mask`(non-prod AlwaysAllow가 prod 누출 가림),
  `archunit-vacuous-rule-silent-pass`(권한 매트릭스 테스트는 비멤버/미부여 역할로 false 경로 반드시 검증).

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-pm-03-version-component-permissions.md](../specs/2026-06-03-fr-pm-03-version-component-permissions.md)

핵심 요약.
- prod 리졸버 2개(identity-access, @Profile prod): 멤버 게이트(비멤버 false) → roleHasPermission 매트릭스.
- 권한 코드: 컴포넌트/버전 CRUD 3종 각각 단일 MANAGE_COMPONENTS / MANAGE_VERSIONS (Jira식).
- V009 시드: 기본 스킴에 PROJECT_ADMIN × MANAGE_* 2행(MEMBER 미부여 → 403).
- 호출부 무변경(이미 포트 호출), 403/404 매핑 기존 처리.
- 이번 범위 backend D1~D5 중심. D6/D7 프론트는 게이트에서 분할 결정.

## Brainstorming Check

✅ 통과 (적대적 1-pass 자체 검토). 거부 경로·BC 조립 부재·V번호·enum drift 보강 반영.
미결정 1건 — PR 분할(backend 먼저 vs 프론트 포함) → 게이트 1에서 Maxi 확인.

## Plan

> 모든 task는 identity-access 단일 모듈. 경로 prefix:
> `IA = backend/modules/identity-access/src`. 검증 명령은 repo 루트에서 `./gradlew`.

### Task 1. V009 마이그레이션 — 기본 스킴에 MANAGE_* 시드 (PROJECT_ADMIN 전용)

**메타**.
- agent: `db-engineer`
- files: [`IA/main/resources/db/migration/V009__manage_components_versions_permissions.sql`, `IA/test/kotlin/com/atlas/bts/identity/permission/ManageComponentVersionMigrationTest.kt`]
- depends-on: []

**RED**.
- 파일: `ManageComponentVersionMigrationTest.kt` (Testcontainers, JdbcPermissionSchemeRepository 주입 또는 직접 쿼리)
- 테스트:
  - `roleHasPermission(randomProjectId, "PROJECT_ADMIN", "MANAGE_COMPONENTS") == true` (기본 스킴 fallback)
  - `... "MANAGE_VERSIONS" == true`
  - `roleHasPermission(randomProjectId, "MEMBER", "MANAGE_COMPONENTS") == false`
  - `... "MEMBER", "MANAGE_VERSIONS" == false`
- 실패 (예상): V009 부재 → 4건 모두 false (PROJECT_ADMIN 케이스 실패).

**GREEN**.
- 파일: `V009__manage_components_versions_permissions.sql`
  ```sql
  -- 기본 스킴에 버전/컴포넌트 관리 권한 추가 (FR-PM-03, PROJECT_ADMIN 전용)
  INSERT INTO role_permissions (scheme_id, role, permission_code)
  VALUES
      ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_COMPONENTS'),
      ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_VERSIONS');
  ```

**REFACTOR**. SQL 주석 정리(SDD 12.3 / ADR 링크). init_codegen 미러 없음(identity-access JdbcTemplate).

**검증**: `./gradlew :modules:identity-access:test --tests '*ManageComponentVersionMigrationTest'`

---

### Task 2. 컴포넌트 prod 리졸버 + 권한코드 매핑 + 단위 테스트(mockk)

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessComponentPermissionResolver.kt`, `IA/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessComponentPermissionResolverTest.kt`]
- depends-on: []

**RED**.
- 파일: `IdentityAccessComponentPermissionResolverTest.kt` (MockK — membershipRepo, schemeRepo 격리)
- 테스트:
  - (a) 비멤버(findByProjectAndUser null) → 모든 permission false (멤버 게이트)
  - (b) PROJECT_ADMIN 멤버 + schemeRepo true → true; CREATE/UPDATE/DELETE 각각 `MANAGE_COMPONENTS` 코드로 호출됨(`verify`)
  - (c) MEMBER 멤버 + schemeRepo false → false (매트릭스 거부)
- 실패 (예상): `IdentityAccessComponentPermissionResolver` 클래스 없음.

**GREEN**.
- 파일: `IdentityAccessComponentPermissionResolver.kt`
  ```kotlin
  @Component
  @Profile("prod")
  class IdentityAccessComponentPermissionResolver(
      private val membershipRepo: ProjectMembershipRepository,
      private val schemeRepo: PermissionSchemeRepository,
  ) : ComponentPermissionResolver {
      override fun hasPermission(actorId: UUID, permission: ComponentPermission, projectId: UUID): Boolean {
          val membership = membershipRepo.findByProjectAndUser(projectId, actorId) ?: return false
          return schemeRepo.roleHasPermission(projectId, membership.role.name, permission.toPermissionCode())
      }
  }
  // when else 없이 3종 전부 명시 — enum drift 컴파일 차단
  private fun ComponentPermission.toPermissionCode(): String =
      when (this) {
          ComponentPermission.CREATE, ComponentPermission.UPDATE, ComponentPermission.DELETE -> "MANAGE_COMPONENTS"
      }
  ```

**REFACTOR**. KDoc(판정 알고리즘 2단계 + @Profile 배타 + IssueResolver 동형, scope 해석 없음 차이). `@Suppress("ReturnCount")` 불요(early return 1개).

**검증**: `./gradlew :modules:identity-access:test --tests '*IdentityAccessComponentPermissionResolverTest'`

---

### Task 3. 버전 prod 리졸버 + 권한코드 매핑 + 단위 테스트(mockk)

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessVersionPermissionResolver.kt`, `IA/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessVersionPermissionResolverTest.kt`]
- depends-on: []

**RED/GREEN/REFACTOR**. Task 2와 동형. 차이 — 포트 `VersionPermissionResolver`, enum `VersionPermission`, 코드 `MANAGE_VERSIONS`.

**검증**: `./gradlew :modules:identity-access:test --tests '*IdentityAccessVersionPermissionResolverTest'`

---

### Task 4. prod-프로파일 통합 테스트 — 매트릭스 전수(컴포넌트+버전) + 빈 주입

**메타**.
- agent: `security-engineer`
- files: [`IA/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessComponentPermissionResolverIntegrationTest.kt`, `IA/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessVersionPermissionResolverIntegrationTest.kt`]
- depends-on: [1, 2, 3]

**RED**.
- 하니스: `@SpringBootTest @ActiveProfiles("prod")` + 외부 provider `@MockBean`(AutoProvisionService/ExternalAccountRepository/LdapProvider/LdapProviderConfigService) + OAuth2 autoconfig 제외 + Testcontainers PG. (FR-PM-02 IdentityAccessIssuePermissionResolverIntegrationTest 하니스 복제)
- 시드: project_memberships에 PROJECT_ADMIN actor 1 + MEMBER actor 1, 비멤버 actor 1. 스킴 미매핑(기본 스킴 fallback, V009 적용).
- **선례 dead-code 복제 금지(code-reviewer 지적)**: 포트가 projectId를 직접 받으므로 `projects` 테이블/
  `ProjectDirectory`/`ensureProjectsTableExists`/projectKey 시드 **불요**. FR-PM-02 선례는 IssueScope
  키 해석 때문에 projects를 시드했으나 컴포넌트/버전은 멤버십+스킴만 시드하면 된다.
- 매트릭스(각 리졸버 9케이스 = 3 actor × 3 permission):
  - PROJECT_ADMIN × {CREATE,UPDATE,DELETE} → true
  - MEMBER × {CREATE,UPDATE,DELETE} → false (MANAGE_* 미부여)
  - 비멤버 × {CREATE,UPDATE,DELETE} → false (멤버 게이트)
  - 빈 주입 단언: 주입된 `ComponentPermissionResolver`/`VersionPermissionResolver`가 IdentityAccess* 구현체.
- 실패 (예상): prod 리졸버 빈 미존재 → 컨텍스트 로드 실패 또는 단언 실패.

**GREEN**. Task 1~3 산출물로 통과(통합테스트 자체는 신규 구현 없음, 테스트만).

**REFACTOR**. DynamicTest 매트릭스로 케이스 압축(FR-PM-02 @TestFactory 선례). 두 통합테스트가 Testcontainers singleton base 공유(메모리 Testcontainers 라이프사이클 — @Container 대신 .apply{start()}).

**검증**: `./gradlew :modules:identity-access:test --tests '*IdentityAccessComponentPermissionResolverIntegrationTest' --tests '*IdentityAccessVersionPermissionResolverIntegrationTest'`

---

## Plan 메타

- task 수: 4
- wave 예상: 2 (wave1 = T1·T2·T3 코드 독립 / wave2 = T4 [1,2,3] 의존)
- **같은 모듈 직렬화 주의 (메모리 bts-plan-wave-gradle-module-compile)**: 4 task 모두 identity-access
  test 소스셋 공유 → 파일 비겹침이어도 test 컴파일 단위가 직렬화 요인. 병렬 dispatch 시 컴파일/커밋 race
  주의(메모리 parallel-dispatch-precommit-hook-race): 각 agent는 자기 files만 stage(-A 금지),
  controller가 git log로 task별 test→feat 순서 직접 검증.
- TDD 강제: yes (각 task test 커밋 선행)
- 추가 검증: 머지 전 controller가 `:modules:identity-access` ktlintMain+TestSourceSetCheck + test + detekt
  직접 실행(메모리 subagent-ktlint-false-green).
- non-prod fallback 빈 불요: identity-access는 두 포트 비소비(컨슈머 없음). prod 와이어링 cross-BC는
  BC 조립 부재로 test-assembled까지만 검증(메모리 no-cross-bc-deployment-assembly, 스펙 EC3).
- 범위: backend D1~D5. D6/D7 프론트는 게이트 1에서 분할 결정(권장: 후속 PR).

## 구현 결과 (bts-impl, 2026-06-03)

- Task 1 (V009 마이그레이션) — ✅ test 5749ae0d → feat fd725678 → refactor b8fe49f5 (db-engineer)
- Task 2 (컴포넌트 prod 리졸버) — ✅ test ec21d8aa → feat 423b283b (security-engineer, KDoc GREEN 포함→refactor no-op 생략)
- Task 3 (버전 prod 리졸버) — ✅ test 680ad0df → feat 59f10bcf (security-engineer, 동형)
- Task 4 (prod 통합테스트) — ✅ test 6e5731cf (security-engineer, 매트릭스 각 10/10, MEMBER/비멤버 거부 ground-truth)
- hot-fix — 8564c37a: V009가 기본 스킴 카운트를 5→7로 바꿔 기존 PermissionSchemaMigrationTest 단언 갱신
  (개별 실행 미검출, 모듈 전체 test에서만 표면화 — 메모리 backend-detekt-lint-debt-unmasked 유형).
- 검증: `:modules:identity-access` test 662통과(0실패, 1 기존 skip) + ktlintMain/Test + detekt 전부 그린(--rerun-tasks).
- QA(E2E): D6 프론트 UI 부재로 N/A. backend 권한 검증은 prod-프로파일 통합테스트가 E2E 등가. D6/D7 후속 PR(게이트 결정).

## 리뷰 결과

### code-reviewer ground-truth 적대적 리뷰 (2026-06-03)

실제 코드 grep/read 대조(읽기 전용). **BLOCKER 0건.** 8개 핵심 검증 전부 코드와 일치.

- ✅ 포트 시그니처 — `hasPermission(actorId:UUID, permission, projectId:UUID)` 일치 (ComponentPermissionResolver.kt:46-51, VersionPermissionResolver.kt:47-52). enum 3종 매핑 정확.
- ✅ 리포 시그니처 — findByProjectAndUser(ProjectMembershipRepository.kt:40), roleHasPermission(PermissionSchemeRepository.kt:25-29) 실재. 생성자에서 projectDirectory 정확히 제외.
- ✅ V008 시드/UUID/V009 번호 — 기본 스킴 UUID·role_permissions 컬럼·CHECK·UNIQUE 모두 일치(V008:22-57). V009 충돌 없음.
- ✅ 권한 코드 정본 — SDD 12-permissions.md:42-43 MANAGE_COMPONENTS/MANAGE_VERSIONS 1:1.
- ✅ @Profile 배타 + non-prod 부팅 — 핵심 함정 회피 확인. 두 포트 소비자는 issue-tracking 2곳뿐(ComponentApplicationService.kt:46, VersionApplicationService.kt:45), identity-access 주입 0건 → non-prod fallback 빈 불요 주장 **참**. profile-scoped-bean-boot-failure 패턴 구조적 회피.
- ✅ cross-BC UUID 공유 — V007:5,14 주석으로 뒷받침(같은 UUID 공간, FK만 부재).
- ⚠️ CONCERN(경미) — Task 4 선례 dead-code 복제 방지(projects 시드 불요) → **plan 반영 완료**.
- ⚠️ CONCERN(경미) — Task 2 단위 MEMBER 거부는 mock false라 거부 ground-truth는 Task 4 통합(실 DB+V009). plan 이미 그렇게 설계.
- ⚠️ CONCERN(범위 밖) — D6는 단순 UI 아니라 **권한 질의 API(My*PermissionController) 신설 포함**. 게이트 1에서 Maxi 명시 필요. 이번 backend PR 영향 없음.

종합. 게이트 1 통과 권장. 머지 전 controller가 `:modules:identity-access` ktlintMain+TestSourceSetCheck+test+detekt 직접 실행, Task 4 통합 prod 프로파일 거부 경로 그린 확인이 거부 ground-truth.
