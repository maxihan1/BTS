# FR-PM-03 D6/D7 — 버전/컴포넌트 권한 게이팅 UI + E2E

> slug: fr-pm-03-d6-d7-permission-gating-ui
> type: feature (혼합 — backend identity-access + frontend apps/web + qa)
> agent: frontend-engineer(주축) + security-engineer(권한질의 API) + qa-engineer(E2E)
> 생성: 2026-06-03

## Brief

FR-PM-03 backend(D1~D5, PR #70)가 prod 권한 리졸버 + MANAGE_COMPONENTS/MANAGE_VERSIONS
매트릭스(PROJECT_ADMIN 전용)를 채웠다. D6/D7은 그 권한을 프론트에서 게이팅한다.

- D6. 권한 없는 사용자에게 버전/컴포넌트 **관리 버튼(생성/수정/삭제) 비활성화**.
- D7. E2E — PROJECT_ADMIN 관리 가능 / 권한 없는 사용자 차단 시나리오.

**핵심(이전 세션 코드리뷰 발견)**. 기존 `MyProjectPermissionController`
(`GET /api/v1/users/me/project-permissions`)는 `IssuePermission.CREATE`만 반환 →
MANAGE_COMPONENTS/MANAGE_VERSIONS 미노출. **D6에 백엔드 권한 질의 API 확장 필요**.
그 컨트롤러가 Component/VersionPermissionResolver(@Profile prod)를 소비하게 되면
**identity-access용 non-prod fallback 빈 필수**(메모리 profile-scoped-bean-boot-failure,
PR #55의 66-test 연쇄 회피).

선례. FR-PM-02 D6/D7(PR #55) 이슈 생성 게이팅, FR-CM-01(PR #64)/FR-VR-01(PR #68) 프론트
관리 UI(`/projects/$projectKey/settings` 버전·컴포넌트). use-project-permissions 훅 +
project-permission-handlers MSW.

## 도메인 정리

- BC: identity-access(권한 질의 API 확장 + fallback) + apps/web(게이팅 UI). qa(E2E).
- 영향 컴포넌트:
  - backend(identity-access). `MyProjectPermissionController` 확장(+Component/VersionPermissionResolver 주입),
    `ProjectPermissionsResponse.permissions` 맵에 MANAGE_* 키 추가, `DevAllowComponentPermissionResolver`/
    `DevAllowVersionPermissionResolver` 신규(@Profile !prod).
  - frontend. `api/project-permissions`(Zod 스키마에 MANAGE_* 추가), ComponentList/ComponentRow/VersionList/VersionRow
    버튼 게이팅, project-permission MSW/fixtures 확장.
- 새 용어: 없음(MANAGE_COMPONENTS/MANAGE_VERSIONS는 SDD 12.3 기존, PR #70 도입).
- 핵심 결정(전부 선례 결정, ADR 참조):
  - D1 기존 엔드포인트 확장(제네릭 맵에 키 추가, additive). 새 엔드포인트 안 만듦.
  - D2 projectKey→projectId는 ProjectDirectory.resolveKeyToId, null→false(기존 미존재 정책 일관).
  - D3 non-prod fallback 빈 2개 필수(컨트롤러가 @Profile prod 포트 소비 → 부팅 가드, DevAllowIssue 1:1).
  - D4 프론트 fail-closed 게이팅(로딩/에러/false→disabled), 모든 mutation 버튼.
- 기존 결정 충돌: 없음. FR-PM-02 권한질의 API(ADR 2026-06-02-issue-permission-query-api) 확장.
- 관련 ADR: [docs/decisions/2026-06-03-version-component-permission-query-and-gating.md](../decisions/2026-06-03-version-component-permission-query-and-gating.md) (생성됨)
- 회귀 가드 메모: profile-scoped-bean-boot-failure(fallback 부재→부팅 깨짐, 모듈 전체 test로만 표면화),
  zod-schema-strengthen-inline-mock-fanout / frontend-zod-backend-dto-contract-gap(응답 스키마 키 추가→인라인 mock 깨짐),
  e2e-msw-scenario-toggle-localstorage-flag(같은 사용자 권한 토글), ui-pr-defer-e2e-regression-latent(UI PR은 기존 E2E 함께 실행),
  best-effort-loop-permission-exception-nonprod-mask(거부 경로 vacuous 주의).

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-pm-03-d6-d7-permission-gating-ui.md](../specs/2026-06-03-fr-pm-03-d6-d7-permission-gating-ui.md)

핵심 요약.
- backend: MyProjectPermissionController가 Component/VersionPermissionResolver 주입, 응답 맵에 MANAGE_* 키 추가
  (key→id ProjectDirectory 해석, null→false) + DevAllow fallback 2(@Profile !prod).
- frontend: projectPermissionsSchema에 MANAGE_* required 추가(+fixture/mock 전수 갱신) + List/Row fail-closed 게이팅.
- E2E: admin 활성/non-admin disabled(localStorage 토글) + 기존 version/component E2E 함께 그린.
- 함정: Zod strip, mock fan-out, non-prod 부팅(fallback), 기존 List/Row 테스트 회귀(admin mock 주입).

## Brainstorming Check

✅ 통과 (적대적 1-pass). EC7(기존 List/Row 테스트 회귀)·EC8(canManage prop 스레딩) 보강. 미결정 없음.

## Plan

> 경로 prefix: IA = backend/modules/identity-access/src · WEB = apps/web/src.
> 백엔드(identity-access)·프론트(apps/web)는 독립 모듈/파일 → 다른 스트림. 검증 명령은 worktree 루트.

### Task 1. (backend) DevAllow fallback 빈 2개 + 부팅 테스트

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/permission/DevAllowComponentPermissionResolver.kt`, `IA/main/kotlin/com/atlas/bts/identity/permission/DevAllowVersionPermissionResolver.kt`, `IA/test/kotlin/com/atlas/bts/identity/permission/ComponentVersionPermissionResolverFallbackBootTest.kt`]
- depends-on: []

**RED**. 부팅 테스트 — non-prod identity-access 컨텍스트에 `ComponentPermissionResolver`/`VersionPermissionResolver` 빈이 **각 정확히 1개 + DevAllow 인스턴스**인지 단언(`getBeansOfType(...).hasSize(1)` + `isInstanceOf(DevAllow...)`, C4 — 선례 IssuePermissionResolverBootTest.kt:84-89 형태. 단순 @Autowired는 빈 2개 중복도 통과해 가드 약함). DevAllow* 부재 시 UnsatisfiedDependency로 실패. 커밋 `test: fr-pm-03-d6d7 task-1 red`.
**GREEN**. `DevAllowComponentPermissionResolver`/`DevAllowVersionPermissionResolver`(`@Component @Profile("!prod")`, hasPermission 항상 true + WARN 로그). DevAllowIssuePermissionResolver 1:1. 커밋 `feat: ... task-1 green`.
**REFACTOR**. KDoc(BC 격리 존재 이유 — 컨트롤러 소비자 + non-prod fallback). GREEN 포함이면 생략.
**검증**: `./gradlew :modules:identity-access:test --tests '*ComponentVersionPermissionResolverFallbackBootTest'` (gradlew는 backend/).

### Task 2. (backend) MyProjectPermissionController 확장 — MANAGE_* 노출

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/web/MyProjectPermissionController.kt`, `IA/test/kotlin/com/atlas/bts/identity/integration/MyProjectPermissionIntegrationTest.kt`]
- depends-on: [1]

**RED**. MyProjectPermissionIntegrationTest 확장 — 응답 `permissions` 맵에 `MANAGE_COMPONENTS`/`MANAGE_VERSIONS` 키 존재 + (prod 프로파일) PROJECT_ADMIN→true / 비멤버→false / 미존재 projectKey→false.
- **B1(BLOCKER): admin 시드 추가 필수**. 현재 이 테스트는 member/nonmember만 시드(seedUsers:316-327, seedMemberships:336-351에 MEMBER 1행) → MANAGE_*가 항상 false라 "admin→true" 불가. **admin 사용자 + PROJECT_ADMIN 멤버십 INSERT 추가**(기본 스킴 fallback이라 스킴 매핑은 불요 — V009가 기본 스킴 PROJECT_ADMIN에 MANAGE_* 시드). 선례 IdentityAccessComponentPermissionResolverIntegrationTest.kt:212-236.
- 커밋 `test: ... task-2 red`.
**GREEN**. 컨트롤러가 `ComponentPermissionResolver`/`VersionPermissionResolver` 추가 주입. `projectId = projectDirectory.resolveKeyToId(projectKey)`; null이면 MANAGE_* false, 아니면 두 리졸버 호출(CREATE 권한은 MANAGE 대상 enum 대표값으로 hasPermission 1회). 응답 맵에 두 키 추가(기존 CREATE 유지, additive). DTO 변경 없음(Map<String,Boolean> 제네릭). 커밋 `feat: ... task-2 green`.
**REFACTOR**. UI_PROJECT_PERMISSIONS 주석/KDoc 갱신.
**검증**: `./gradlew :modules:identity-access:test --tests '*MyProjectPermissionIntegrationTest'` + **머지 전 :modules:identity-access 전체 test**(부팅 회귀).

### Task 3. (frontend) projectPermissionsSchema MANAGE_* + fixture/mock 전수 갱신

**메타**.
- agent: `frontend-engineer`
- files: [`WEB/api/project-permissions.ts`, `WEB/mocks/project-permission-fixtures.ts`, `WEB/mocks/project-permission-handlers.ts`, `WEB/mocks/__tests__/project-permission-handlers.test.ts`, `WEB/hooks/use-project-permissions.test.ts`, `WEB/routes/issues.index.test.tsx`]
- depends-on: []

**RED**. project-permissions 스키마 테스트 — 응답에 `MANAGE_COMPONENTS`/`MANAGE_VERSIONS` 없으면 parse 실패(required), 있으면 통과. 커밋 `test: ... task-3 red`.
**GREEN**. `projectPermissionsSchema.permissions`에 `MANAGE_COMPONENTS: z.boolean()`, `MANAGE_VERSIONS: z.boolean()` 추가. **project-permission 응답을 내는 인라인 mock 전수 갱신**.
- **B2(BLOCKER): fan-out 파일 누락 주의**. `grep -i project`로 못 잡는 곳 2개 — `hooks/use-project-permissions.test.ts:117-120`(`permissions:{CREATE:true}`만), `routes/issues.index.test.tsx:52`(기본 권한 핸들러). 둘 다 schema parse 경로(useProjectPermissions)를 타 런타임 z.parse 실패로 깨짐. **tsc는 못 잡음(런타임 parse)** → 검증은 typecheck가 아니라 **전체 vitest**.
- nonMember fixture(project-permission-fixtures.ts:64-66)에도 MANAGE_*:false 추가(C1 E2E 토글 재사용 기반).
- 커밋 `feat: ... task-3 green`.
**REFACTOR**. fixture admin/non-admin 헬퍼(true/false 세트).
**검증**: `pnpm --filter @bts/web test`(project-permission + hooks + routes/issues.index 포함 전체) + `pnpm --filter @bts/web typecheck`.

### Task 4. (frontend) 컴포넌트 게이팅 — ComponentList/Row + 기존 테스트 보강

**메타**.
- agent: `frontend-engineer`
- files: [`WEB/components/component/ComponentList.tsx`, `WEB/components/component/ComponentRow.tsx`, `WEB/components/component/__tests__/ComponentList.test.tsx`(또는 인접 test), `WEB/components/component/__tests__/ComponentRow.test.tsx`]
- depends-on: [3]

**RED**. 게이팅 테스트 — admin(MANAGE_COMPONENTS true) → 추가/편집/삭제/리드변경 활성; non-admin/로딩/에러 → disabled(fail-closed).
- **C2: List는 useProjectPermissions mock, Row는 canManage prop 직접 주입**(Row는 훅 호출 안 함 — 중복 호출 방지). 기존 ComponentList.test.tsx(:113,175,244 버튼 클릭)·ComponentRow.test.tsx 회귀 → List 테스트 admin mock 주입, Row 테스트 canManage=true prop 주입(EC7).
- 커밋 `test: ... task-4 red`.
**GREEN**. ComponentList가 `useProjectPermissions(projectKey)`로 `canManage = !isLoading && !isError && permissions?.MANAGE_COMPONENTS === true`. "추가" 버튼 `disabled={!canManage}`, ComponentRow에 `canManage` prop 전달(편집/삭제 `disabled={!canManage}`).
- **C3: ComponentRow 인라인 리드 변경(ComponentLeadSelect + useChangeComponentLead, ComponentRow.tsx:170-176)도 mutation → `disabled={!canManage}` 게이팅**(MANAGE_COMPONENTS 범위). 빠뜨리면 권한 없는 사용자가 리드 변경 가능(UI 구멍).
- FR-PM-02 IssueMetaPanel 동형. 커밋 `feat: ... task-4 green`.
**REFACTOR**. disabled 사유/aria.
**검증**: `pnpm --filter @bts/web test component` + typecheck.

### Task 5. (frontend) 버전 게이팅 — VersionList/Row + 기존 테스트 보강

**메타**.
- agent: `frontend-engineer`
- files: [`WEB/components/version/VersionList.tsx`, `WEB/components/version/VersionRow.tsx`, `WEB/components/version/__tests__/VersionList.test.tsx`, `WEB/components/version/__tests__/VersionRow.test.tsx`]
- depends-on: [3]

**RED/GREEN/REFACTOR**. Task 4와 동형. 차이 — version, `MANAGE_VERSIONS`. VersionRow엔 리드변경 없음(edit/delete만, C3 무관). List=mock / Row=canManage prop(C2). 기존 VersionList.test.tsx(:115,175,239)·VersionRow.test.tsx 회귀 보강.
**검증**: `pnpm --filter @bts/web test version` + typecheck.

### Task 6. (qa) E2E — 권한 게이팅 시나리오 + 기존 E2E 회귀 확인

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/version-component-permission-gating.spec.ts`]
- depends-on: [3, 4, 5]

**RED→GREEN**. PROJECT_ADMIN(MANAGE_* true) → 관리 버튼 활성; 권한 없는 사용자(false) → disabled.
- **C1: 기존 플래그 재사용**. 신규 플래그 만들지 말 것 — `E2E_FORCE_CREATE_FALSE_KEY`(project-permission-handlers.ts:24)가 이미 nonMember 응답으로 토글하고, T3에서 nonMember fixture에 MANAGE_*:false 추가하면 자동 적용. 선례 e2e/issue-create-gate.spec.ts:59-61의 addInitScript 정본.
- 행 한정 셀렉터(playwright-getbyrole-exact-strict-mode). **기존 component-management.spec.ts/version-management.spec.ts 함께 실행**해 disabled 속성 회귀 0 확인(ui-pr-defer-e2e-regression-latent).
**검증**: `pnpm --filter @bts/web test:e2e version-component-permission-gating` + 기존 version/component E2E.

## Plan 메타

- task 수: 6
- wave 예상: 3 (wave1 = T1 backend·T3 frontend 독립 / wave2 = T2[1]·T4[3]·T5[3] / wave3 = T6[3,4,5])
- 스트림 분리: backend(identity-access) ⟂ frontend(apps/web) 다른 모듈·파일. MSW로 프론트는 backend 무의존.
- 같은 worktree 병렬 커밋 race 주의(parallel-dispatch-precommit-hook-race): 각 agent 자기 files만 stage(-A 금지),
  controller가 git log로 task별 test→feat 순서 직접 검증. 동일 모듈(apps/web) T4/T5는 직렬 권장.
- TDD 강제: yes.
- 머지 전 검증(controller 직접): :modules:identity-access 전체 test+ktlint+detekt / apps/web vitest+typecheck(tsconfig.app)+해당 E2E.

## 리뷰 결과

### code-reviewer ground-truth 적대적 plan 리뷰 (2026-06-03)

main 트리 실제 코드 대조. 골격(엔드포인트 additive 확장 + fallback 2 + fail-closed 게이팅)은 코드와 정합.
BLOCKER 2 + CONCERN 4 발견 → **전부 plan 반영 완료**.

- 🛑 B1 — T2 MyProjectPermissionIntegrationTest는 member/nonmember만 시드 → "admin→true" 불가. PROJECT_ADMIN 시드 추가 명시(반영).
- 🛑 B2 — Zod fan-out에 use-project-permissions.test.ts·routes/issues.index.test.tsx 누락 + grep 못 잡음(런타임 parse, tsc 무력). files 추가 + 전체 vitest 검증(반영).
- ⚠️ C1 — E2E는 기존 E2E_FORCE_CREATE_FALSE_KEY 재사용(nonMember fixture에 MANAGE_*:false). 신규 플래그 금지(반영).
- ⚠️ C2 — List=훅 mock / Row=canManage prop 직접 주입(중복 호출 방지)(반영).
- ⚠️ C3 — ComponentRow 인라인 리드변경(useChangeComponentLead)도 게이팅(권한 구멍 방지)(반영).
- ⚠️ C4 — T1 부팅 단언 hasSize(1)+isInstanceOf 형태(빈 중복 가드)(반영).
- ✅ OK 8항목 — 엔드포인트/DTO 제네릭맵/resolveKeyToId/non-prod fallback 필요성/Zod strip/Row prop/E2E 경로/CREATE 키 유지 전부 코드 정합. issue-permission 계열 무관 확인.

종합. 4개 보강 반영 후 게이트 1 진행 권장. 신규 발명/존재하지 않는 메서드·훅 없음.
