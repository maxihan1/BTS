# FR-PJ PR-5 — 프로젝트 생성·목록·설정·아카이브 UI

> slug: fr-pj-pr-5-project-crud-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-20

## Brief

**FR-PJ PR-5** — project-management-crud 마스터 스펙(`docs/specs/2026-07-17-project-management-crud.md`) §9.2의 6분할 중 5번째(**D6 프론트 UI**).

프로젝트 **생성·목록·설정·아카이브** 4개 UI 화면을 FR-UX-06 개편 산출물인 새 **ADS v2 디자인 시스템**(`components/layout/PageLayout·PageHeader·Breadcrumb` + `components/ui/` 프리미티브) 위에 구축한다. 순수 프론트(apps/web)·마이그레이션 0.

**백엔드 완비 (PR-1~4)**:
- `POST /api/v1/projects` — 생성 · `CREATE_PROJECT`
- `GET /api/v1/projects` — 목록 · 멤버십 필터 · 존재누설0 · `?archived`
- `GET /{idOrKey}` — 조회 · BROWSE
- `PATCH /{idOrKey}` — 설정 · PROJECT_ADMIN · name만 · **204 No Content(재조회 필요)**
- `POST /{idOrKey}/archive`·`/unarchive` — PROJECT_ADMIN · 멱등200 · 아카이브 설정변경 409

**포함**: FR-PJ-01~04 완료마킹(전수 동기화 8종 · verify-master-plan).
**착수 전 필독**: `[[frontend-nav-aria-label-e2e-contract]]` · `[[playwright-getbyrole-exact-strict-mode]]`. 사이드바 ProjectTree(#298) 연동 확인.

**분류 정정**: classifier가 design/designer 오판 → ui/frontend-engineer 실측 정정(`.bts-cache/classify.json` note 참조).

## 도메인 정리

- **소비 BC**: issue-tracking(projects CRUD·archive) + project-workflow. **apps/web 단일 SPA** (한 PR=한 BC 규칙은 백엔드 대상, 프론트는 cross-BC 소비 정상).
- **영향 엔티티(읽기/소비만)**: Project(key=영문대문자+숫자·name·archivedAt), ProjectMembership(생성 시 생성자 자동 admin), GlobalPermissionGrant(CREATE_PROJECT).
- **새 용어**: 없음. 프로젝트(Project)·프로젝트 행정 권한(Project Admin)·아카이브(archivedAt) 모두 glossary 기존 확립. "프로젝트 아카이브"는 glossary 명시 항목 부재 → 후보로 기록하되 Maxi 승인 영역(수동).
- **기존 결정 충돌**: 없음. 소비 ADR 4종 모두 백엔드 결정, 프론트는 계약 준수:
  - `2026-07-17-global-permission-grants.md` — FR-PM-10 · CREATE_PROJECT 판정(`hasGlobalPermission`, SYSTEM_ADMIN 포함)
  - `2026-07-18-auto-assign-system-actor-permission-bypass.md` — PR-2 생성 hot-fix
  - `2026-06-01-project-membership-model.md` · `2026-06-01-project-member-projectidorkey.md`
- **관련 ADR**: 없음 (순수 프론트 소비 — 신규 ADR 불요). 도메인 모델링은 마스터 스펙 `docs/specs/2026-07-17-project-management-crud.md` §2 완료.
- **domain 단계 right-size**: 완전히 스펙된 도메인 소비 PR이라 대화형 grill-with-docs 생략(신규 용어 0·ADR 0). FR-UX-06 프론트 PR 선례 동형.

## 스펙

전체 스펙: [docs/specs/2026-07-20-fr-pj-pr-5-project-crud-ui.md](../specs/2026-07-20-fr-pj-pr-5-project-crud-ui.md)

**Maxi 결정 4건**: ①디자인=기존 ADS v2 적용(shotgun 없음) ②생성=전용 라우트 `/projects/new` ③설정 danger zone(name+archive)·목록 보기전용 ④백엔드 노출 2건(`ProjectResponse.archived` + `whoami.canCreateProject`).

**산출물**: 백엔드 2(BE-1 archived/issue-tracking·BE-2 canCreateProject/identity-access) + 프론트 3화면(목록·생성·설정) + 사이드바 연동 + FR-PJ-01~04 완료마킹. **한 PR=한 BC deviation**(2 백엔드 BC+apps/web) — 게이트1 근거 명시.

## Brainstorming Check

✅ 통과 (자체 적대검토 1회). 갭 3건 해소 — G1(router.ts 수동등록·_shell 자식·구현디테일)·**G2(‌/projects 진입점=사이드바 "모든 프로젝트" 링크·게이트1 확인)**·**G3(아카이브 행→settings/details 네비·게이트1 확인)**. 상세는 스펙 §Brainstorming Check.

## Plan

**설계 원칙**: ①BE 먼저(T1·T2)→프론트 Zod 강화(DTO invent 금지) ②RouteAdapter/Page 분리 — 화면 태스크는 props 기반 Page+단위테스트만(라우터 비의존), router.ts 등록은 T7로 몰아 shared-file 충돌 회피 ③MSW 핸들러는 T3(API)서 일괄 등록 → 화면 태스크 핸들러 파일 미충돌.

**의존 그래프**: (T1∥T2) → T3 → (T4∥T5∥T6) → T7 → (T8∥T9). 임계경로 T1/T2→T3→T4~6→T7→T8.

### Task 1. BE-1 — `ProjectResponse.archived` 노출

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/web/dto/ProjectResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/web/dto/ProjectResponseTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/project/web/ProjectQueryControllerTest.kt`]
- depends-on: []

**RED**: `ProjectResponseTest` — `from(project(archivedAt=now))` → `archived == true`, `from(project(archivedAt=null))` → `archived == false`. 컨트롤러 슬라이스에서 목록/단건 JSON에 `archived` 키 존재.
**GREEN**: `ProjectResponse` 에 `val archived: Boolean` 추가. `from()` = `archived = project.archivedAt != null`. (Project 도메인에 archivedAt 존재 — PR-4.)
**REFACTOR**: KDoc `@property archived` 추가.
**검증**: `./gradlew :modules:issue-tracking:test --tests '*ProjectResponse*' --tests '*ProjectQueryController*'` + build/test-results XML 실측.

### Task 2. BE-2 — `whoami.canCreateProject` 노출

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/WhoamiResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/WhoamiController.kt`(또는 조립 서비스), `backend/modules/identity-access/src/test/kotlin/.../WhoamiControllerTest.kt`]
- depends-on: []

**RED**: whoami 응답에 `canCreateProject` 키. grant 보유 비-admin → true · SYSTEM_ADMIN → true · 무권한 → false. `SystemPermissionResolver.hasGlobalPermission(actor, CREATE_PROJECT)` 위임 검증(hasGrant 직접호출 금지 — [[fr-pm-10-global-permission-grants-done]] ADR D-2).
**GREEN**: `WhoamiResponse` 에 `val canCreateProject: Boolean` 추가. whoami 조립 시 `systemPermissionResolver.hasGlobalPermission(actorId, GlobalPermission.CREATE_PROJECT)` 호출해 채운다.
**REFACTOR**: KDoc(FR-PJ-01/FR-PM-10 근거) + 판정식이 `grant OR isSystemAdmin` 임을 주석.
**보안 주의**: whoami는 인증된 self만 조회 — 타인 권한 노출 아님. 매 whoami마다 resolver 1회(세션복원 빈도, 허용). fail-closed(resolver 예외 시 false).
**검증**: `./gradlew :modules:identity-access:test --tests '*Whoami*'` + XML 실측.

### Task 3. API 클라이언트 확장 + MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/projects.ts`, `apps/web/src/api/projects.test.ts`, `apps/web/src/api/schemas.ts`, `apps/web/src/api/schemas.test.ts`, `apps/web/src/hooks/use-project.ts`, `apps/web/src/hooks/use-project-mutations.ts`, `apps/web/src/hooks/__tests__/use-project.test.tsx`, `apps/web/src/mocks/project-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1, 2]

**RED**: `projects.test.ts` — `projectSchema` 가 `archived` 파싱(BE-1 계약). `createProject`/`getProject`/`updateProjectName`/`archiveProject`/`unarchiveProject` 각 엔드포인트·메서드·응답 언래핑. `schemas.test.ts` — whoami `canCreateProject` optional 파싱(키 부재 허용, 하위호환).
**GREEN**:
- `api/projects.ts`: `projectSchema` 에 `archived: z.boolean()` 추가. 신규 함수 5종(POST/GET단건/PATCH204/archive/unarchive). PATCH 는 204라 응답 바디 없음(void 반환·호출부 invalidate).
- `api/schemas.ts`: `WhoamiResponseSchema` 에 `canCreateProject: z.boolean().optional()` (EC-6 mock fanout 방어 — 부재=false 취급). **whoami mock fanout**: required 강화 안 함([[zod-schema-strengthen-inline-mock-fanout]]).
- 훅: `use-project(idOrKey)` 단건 쿼리, `use-project-mutations`(create/updateName/archive/unarchive — 성공 시 `['projects']`·`['project',key]` invalidate, setQueryData 금지 [[mutation-setquerydata-partial-response-flicker]]).
- MSW: `mocks/project-handlers.ts` 에 6엔드포인트 핸들러(생성 201·목록 archived 필터·단건·PATCH 204·archive/unarchive 200 stateful) + `handlers.ts` 등록([[msw-global-handler-registration-gap]]).
**REFACTOR**: 쿼리키 상수화, 에러코드 타입.
**검증**: `pnpm --filter web test -- projects schemas use-project` + `pnpm --filter web typecheck`.

### Task 4. FE-1 — 프로젝트 목록 화면 (Page 컴포넌트)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.index.tsx`, `apps/web/src/routes/__tests__/projects.index.test.tsx`, `apps/web/src/components/project/ProjectListTable.tsx`(선택)]
- depends-on: [3]

**RED**: `ProjectListPage`(props 기반) — 활성 프로젝트 테이블 렌더(key·name)·name 정렬 신뢰·행 클릭 `/projects/{key}/board`·아카이브 토글 시 `?archived` 재조회·아카이브 행 클릭 → `/projects/{key}/settings/details`(G3)·빈 상태 empty-state·`canCreateProject` true일 때만 "새 프로젝트" 버튼(S4)·아카이브 배지(BE-1 archived).
**GREEN**: `ProjectListRouteAdapter`(라우터 훅) + `ProjectListPage`(props: `useProjects`/`useAuthUser` 소비). PageLayout+PageHeader(h1 "프로젝트")·ui/table·ui/switch(아카이브 토글)·ui/empty-state·ui/badge. 아카이브 행 링크 분기.
**REFACTOR**: 테이블 컬럼 상수, 라벨 i18n.
**검증**: `pnpm --filter web test -- projects.index`.

### Task 5. FE-2 — 프로젝트 생성 화면 (Page 컴포넌트)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.new.tsx`, `apps/web/src/routes/__tests__/projects.new.test.tsx`, `apps/web/src/components/project/ProjectCreateForm.tsx`(선택)]
- depends-on: [3]

**RED**: `ProjectCreatePage` — key(영문대문자+숫자 검증)·name(required) 폼·제출 성공 시 `/projects/{key}/board` 이동(S3)·409 중복key "이미 사용 중" 표면·400 검증·403 권한 안내·canCreateProject=false 직접진입 시 접근 안내(EC-5).
**GREEN**: `ProjectCreateRouteAdapter` + `ProjectCreatePage`. PageLayout+PageHeader(h1 "새 프로젝트"·Breadcrumb 프로젝트>새 프로젝트)·ui/ 폼 프리미티브·`useProjectMutations().create`·성공 `navigate`. 폼 검증(key 정규식).
**REFACTOR**: 검증 규칙 상수, 에러 메시지 i18n.
**검증**: `pnpm --filter web test -- projects.new`.

### Task 6. FE-3 — 프로젝트 일반 설정 화면 (Page 컴포넌트)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.details.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.details.test.tsx`, `apps/web/src/components/project/ProjectDangerZone.tsx`(선택)]
- depends-on: [3]

**RED**: `ProjectDetailsSettingsPage` — name 편집 폼(PATCH 204 후 invalidate·S5)·MANAGE_COMPONENTS(useProjectPermissions) false면 액션 숨김·404/비멤버 → `ProjectNotFoundScreen` 재사용(존재비노출)·danger zone: archived면 "해제"·활성이면 "아카이브"(BE-1 archived로 결정·S6)·archived 상태면 name 폼 비활성(EC-3, 409 방어).
**GREEN**: `ProjectDetailsSettingsRouteAdapter`(useParams) + `ProjectDetailsSettingsPage`(props: projectKey). `use-project`(단건, archived 포함)·`useProjectPermissions`·`useProjectMutations`(updateName/archive/unarchive). PageLayout+PageHeader.
**REFACTOR**: danger zone 컴포넌트 추출, 라벨 i18n.
**검증**: `pnpm --filter web test -- settings.details`.

### Task 7. FE-4 — 라우트 등록(router.ts) + 사이드바 연동

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/router.ts`, `apps/web/src/routes/__tests__/router.shell.test.tsx`, `apps/web/src/components/layout/ProjectTree.tsx`, `apps/web/src/components/layout/__tests__/ProjectTree.test.tsx`, `apps/web/src/components/layout/__tests__/navigation-contract.test.tsx`]
- depends-on: [4, 5, 6]

**RED**:
- router.shell.test — 신규 3라우트(`/projects`·`/projects/new`·`/projects/$projectKey/settings/details`)가 **`_shell` 자식**으로 등록·fullPath 정확·라우트 카운트 갱신([[tanstack-pathless-layout-router-test-blind]]).
- ProjectTree.test — "모든 프로젝트" 진입 링크(→`/projects`)·설정 그룹에 "일반" 링크(→settings/details, 11→12).
- navigation-contract.test — 신규 "프로젝트"/"모든 프로젝트" 라벨이 기존 aria-label 4종 계약 무위반(exact 매칭·[[frontend-nav-aria-label-e2e-contract]]).
**GREEN**: router.ts 에 3 `createRoute`(getParentRoute: shellRoute) + Adapter 연결. ProjectTree "모든 프로젝트" 링크(nav+Link, Tabs 금지) + 설정 서브링크 추가.
**REFACTOR**: 라우트 카운트 주석 정합(PR13 선례).
**검증**: `pnpm --filter web test -- router.shell ProjectTree navigation-contract` + `pnpm --filter web typecheck` + `pnpm --filter web build`.

### Task 8. D7 — E2E (happy-path)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/project-crud.spec.ts`, `apps/web/e2e/fixtures/*`(필요 시)]
- depends-on: [7]

**RED→GREEN**: Playwright 시나리오 — 목록 표시·생성 성공(→board)·아카이브 토글·설정 name 변경·아카이브/해제 왕복. `loginAsSystemAdmin` 헬퍼(admin 권한 필요). 바이너리 직접호출([[e2e-playwright-filter-arg-drop]])·전수 baseline 대조. **PR-5 포함 근거**: [[ui-pr-defer-e2e-regression-latent]]·CI e2e 잡 부재([[frontend-ci-10min-timeout-nonrequired]]).
**검증**: `node_modules/.bin/playwright test project-crud` + 개수 실측.

### Task 9. FR-PJ-01~04 완료마킹 (전수 동기화)

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/fr-index.md`, `docs/plan/product/project-workflow.md`(및 관련 BC product), `docs/plan/README.md`, `CLAUDE.md`, `docs/sdd/02-requirements.md`, `docs/progress.html`]
- depends-on: [7]

**작업**: FR-PJ-01~04 D6/D7 완료마킹(D단계 체크박스)·진척 반영. **FR 총수 129 불변**(카운트 추가 아님). `node scripts/build-dashboard.mjs` 재생성. **★controller(나)가 `bash scripts/verify-master-plan.sh` PASS 직접 확인**([[fr-scope-change-full-sync-rule]]·[[orchestrator-instruction-counts-are-blindfolds]] — 숫자 안 믿고 verify로 판정).
**검증**: `bash scripts/verify-master-plan.sh` (종료 0).

## Plan 메타

- task 수: 9
- 의존 그래프: (T1∥T2) → T3 → (T4∥T5∥T6) → T7 → (T8∥T9). 예상 wave 5~6.
- TDD 강제: yes (test 커밋 선행)
- agent 분포: backend 1(T1) · security 1(T2) · frontend 6(T3~T7,T9) · qa 1(T8)
- **한 PR=한 BC deviation**: issue-tracking(T1)+identity-access(T2)+apps/web. 게이트1 근거 명시. cross-BC import 0(각 모듈 내). :modules:app 조립 부팅 검증([[prod-assembly-boot-verification-required]]).
- 추가 검증: typecheck·lint·vitest 전수·playwright·:modules:app:test·verify-master-plan.

## 리뷰 결과

### 집중 리뷰 (eng+design+security 렌즈, 2026-07-20)

**right-size 근거**: type=ui라 라우팅은 plan-design-review이나, 디자인=기존 ADS v2 적용(Maxi D1 확정·신규 시각시스템 0)이라 design-review 가치 낮음. 진짜 리스크(아키텍처·보안·네비)에 집중 렌즈 적용([[bts-review-plan-autoplan-overkill]]). PR11~13(동일 ADS 적용) 선례 동형. **BLOCKER: 없음.**

**엔지니어링/아키텍처**
- ✅ DTO invent 방지 순서 정합 — T3(프론트 Zod) depends-on [T1,T2]. BE 계약 먼저, MSW mock이 실응답과 일치.
- ✅ RouteAdapter/Page 분리로 router.ts 단일소유(T7)·화면 T4~6 shared-file 미충돌([[parallel-fr-overlapping-frontend-infra-collision]] 방어). Page는 props 기반 단위테스트(members 선례).
- ✅ 의존 그래프 비순환. T7 depends-on [4,5,6](컴포넌트 실재 후 import), T8 depends-on [7](라우트 배선 후 E2E).
- ⚠️ **W1 (게이트1 확인)** 한 PR=한 BC deviation — issue-tracking(T1)+identity-access(T2)+apps/web. 정당(UI가 cross-BC 읽기노출 필요·PR-2 D10 선례) but 조립부팅 재검증 필수([[prod-assembly-boot-verification-required]], DoD-6). cross-BC import 0.
- ⚠️ **W2** i18n 라벨 shared-file — T4/5/6 병렬 시 공용 라벨 파일 동시편집 충돌 가능. **처방**: 각 화면 자체 라벨 상수 or T3/T7 중앙화. impl wave 배정 시 files 교집합 확인.

**보안 (BE-2 whoami.canCreateProject)**
- ✅ `hasGlobalPermission` 위임(hasGrant 직접호출 금지·ADR D-2)·self-only(whoami 인증자 본인)·fail-closed(예외→false). 타인 권한 노출 아님.
- ✅ 프론트 게이팅은 UX 편의뿐 — 백엔드 POST가 최종 방어(fail-closed). canCreateProject 조작/staleness가 우회로 안 됨.
- ⚠️ **W3** whoami mock fanout — 프론트 스키마 `canCreateProject` optional(부재=false 버튼숨김·안전). prod는 항상 전송이라 실사용 정상(비대칭 무해).

**디자인 (ADS v2 적용)**
- ✅ PageLayout/PageHeader/Breadcrumb·ui/ 프리미티브만. **Breadcrumb 첫 실소비처**(PR13 산출 활성화 — "소비처 부재" 해소).
- ⚠️ **W4** 아카이브 확인 UX — 파괴적이나 가역(unarchive 존재). AlertDialog 래퍼 미신설(별도 스코프)이라 확인이 필요하면 `ui/dialog`(기존) 사용 or 인라인 danger zone 버튼. impl서 결정.
- ✅ empty-state·badge·table·switch 전부 기존 프리미티브.

**네비 (스펙 §Brainstorming)**
- ⚠️ **G2/G3 게이트1 확인** — G2(/projects 진입점=사이드바 "모든 프로젝트")·G3(아카이브 행→settings/details). aria-label exact 매칭 계약 준수(T7 navigation-contract.test).

**요약**: BLOCKER 0. WARN 4(W1~4) + 게이트1 확인 3(W1·G2·G3). W2는 impl wave 배정 시, W3/W4는 impl 시 처리.
