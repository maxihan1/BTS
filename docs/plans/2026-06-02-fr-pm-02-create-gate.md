# FR-PM-02 CREATE 게이트 — 목록 새 이슈 버튼 권한 비활성화

> slug: fr-pm-02-create-gate
> type: api (vertical slice: backend + frontend + e2e)
> agent: backend-engineer (+ frontend-engineer, security-engineer 검토)
> primary_bc: identity-access
> 생성: 2026-06-02

## Brief

이슈 목록의 "새 이슈" 버튼을 프로젝트 스코프 CREATE 권한이 없는 사용자에게 비활성화한다.
FR-PM-02 D6/D7(PR #55)은 이슈 *상세* 화면의 수정/삭제 게이트만 다뤘고, 상세 도달자는
이미 멤버라 CREATE 게이트가 no-op이었다. 목록의 "새 이슈"는 프로젝트 스코프 권한(특정
프로젝트에서 이슈를 만들 수 있는가)이라 이슈 키가 아닌 프로젝트 키 기준 조회가 필요하다.

기존 권한 조회 API `GET /api/v1/users/me/issue-permissions?issueKey=`를 프로젝트 스코프
(projectKey)까지 확장한다. fail-closed(권한 미확정 시 비활성), 서버 single source of truth
(Jira mypermissions 방식) 원칙은 D6/D7과 동일하게 유지한다.

## 도메인 정리

- **BC**: identity-access (PR #55와 동일). 인증 사용자 추출 + 권한 평가 응집.
- **새 도메인 모델: 없음.** 핵심 발견 — 백엔드 권한 판정기는 이미 프로젝트 스코프 + CREATE를 완전 지원한다.
  - `IssueScope`(sealed) = `Global` / `Project(key)` / `Issue(key)`. `Project` 변형 이미 존재.
  - `IdentityAccessIssuePermissionResolver.resolveProjectId`가 `IssueScope.Project -> projectDirectory.resolveKeyToId(scope.key)`를 이미 처리(라인 75-80).
  - `IssuePermission.CREATE -> "CREATE_ISSUE"` 매핑 이미 구현("이번 범위 내", 라인 99). 멤버 게이트 + `role_permissions` 매트릭스 판정까지 동작.
- **실제 갭 = 컨트롤러 진입점뿐.** `MyIssuePermissionController`가
  1. `issueKey` 파라미터만 받고 `IssueScope.Issue(issueKey)`로 하드코딩(라인 64,67),
  2. `UI_ISSUE_PERMISSIONS`에 CREATE가 빠져 있음(UPDATE/SOFT_DELETE/TRANSITION만, 라인 48-49).
  → 프로젝트 스코프 CREATE 권한을 조회할 경로가 없다.
- **새 용어: 없음.** glossary에 권한 코드(`CREATE_ISSUE`)·권한 스킴·역할-권한 매트릭스 이미 등재(라인 47-49).
- **기존 결정 충돌: 없음.** ADR `2026-06-02-issue-permission-query-api.md`(Jira mypermissions 방식, 서버 single source of truth)를 자연스럽게 확장. fail-closed 원칙 유지.
- **관련 ADR**: [issue-permission-query-api](../decisions/2026-06-02-issue-permission-query-api.md)(확장 대상), [issue-permission-scheme-model](../decisions/2026-06-02-issue-permission-scheme-model.md).
- **열린 설계 결정(→ /bts-spec)**: 프로젝트 스코프 조회의 엔드포인트 형태 — (A) 기존 `issue-permissions`에 `projectKey` 파라미터 추가(issueKey와 배타) vs (B) 신규 `GET /api/v1/users/me/project-permissions?projectKey=` 분리. 응답 DTO `IssuePermissionsResponse(issueKey, permissions)`도 projectKey 케이스 표현 방식 결정 필요.

## 스펙

전체 스펙. [docs/specs/2026-06-02-fr-pm-02-create-gate.md](../specs/2026-06-02-fr-pm-02-create-gate.md)

핵심 결정 + 시나리오 요약.
- **엔드포인트 = 신규 분리**(Maxi 결정). `GET /api/v1/users/me/project-permissions?projectKey=` → `{projectKey, permissions:{CREATE}}`. 기존 issue-permissions 계약 무변경(회귀 0).
- **백엔드 도메인 무변경**. resolver가 이미 `IssueScope.Project`+`CREATE→CREATE_ISSUE` 판정. 신규 컨트롤러가 호출만.
- **현 매트릭스 실효 = 비멤버/fail-closed**. PROJECT_ADMIN·MEMBER 둘 다 CREATE_ISSUE 보유(V008) → 멤버는 항상 활성. 실질 비활성은 비멤버 + 권한 미확정. D6/D7과 동일 구조, 미래 스킴 확장 대비.
- S1 멤버→버튼 활성, S2 권한없음→비활성, S3 로딩/에러→fail-closed 비활성.

## Brainstorming Check

✅ 통과 (1회, inline sanity check — office-hours 부적합: 정의된 FR + 도메인 검토 완료, 메모 `bts-spec-office-hours-mismatch`).

발견 1건 (범위 경계, 비차단 — 게이트1 Maxi 확인):
- "새 이슈" 버튼만 비활성화하면 `/issues/new` 직접 URL 접근은 여전히 가능. 백엔드 `POST /issues` 403이 실제 차단(fail-closed). FR 명시 범위는 "목록 버튼 비활성화" → `/issues/new` 라우트 자체 게이트는 별도 일관성 개선으로 분리 권장(이번 PR 집중 유지).

## Plan

### Task 1. 백엔드 — project-permissions 엔드포인트 + 응답 DTO

**메타**.
- agent: `security-engineer` (권한 조회 엔드포인트, identity-access)
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/MyProjectPermissionController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/dto/ProjectPermissionsResponse.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/MyProjectPermissionIntegrationTest.kt`]
- depends-on: []

**RED**: 통합테스트(Testcontainers) — `MyProjectPermissionIntegrationTest`.
- 멤버(seed) JWT로 `GET /api/v1/users/me/project-permissions?projectKey=ATLAS` → 200 + `{projectKey:"ATLAS", permissions:{CREATE:true}}`.
- 비멤버 → 200 + `{CREATE:false}`. 미인증 → 401. projectKey 누락/공백 → 400.
- **(C1) 존재하지 않는 projectKey(예: `ZZZZ`) → 200 + `{CREATE:false}`** (404 아님, resolver `resolveProjectId` null → false 보장, 라인 58/78). devex 일관성 분기 고정.
- 실패 메시지(예상): `MyProjectPermissionController` 빈 없음 → 404/빈 컨텍스트.

**GREEN**:
- `ProjectPermissionsResponse(projectKey: String, permissions: Map<String, Boolean>)`.
- `MyProjectPermissionController` — `MyIssuePermissionController`의 `resolveActorId`/`extractBearerToken` 패턴 재사용, `IssueScope.Project(projectKey)` + `IssuePermission.CREATE`로 resolver 호출. `UI_PROJECT_PERMISSIONS = listOf(CREATE)`.

**REFACTOR**: KDoc(엔드포인트 책임/인증 분기/@see ADR), `@Suppress` 사유는 기존 컨트롤러와 동일 톤. actor 추출 공통화는 과설계 — 10줄 미러 유지(기존 WhoamiController 패턴 일관).

**검증**: `./gradlew :backend:identity-access:test --tests '*MyProjectPermissionIntegrationTest'`

### Task 2. 프론트 — project-permissions API client + Zod 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/project-permissions.ts`, `apps/web/src/api/project-permissions.test.ts`]
- depends-on: []

**RED**: `fetchProjectPermissions('ATLAS')`가 `{projectKey, permissions:{CREATE}}`를 파싱·반환하는 테스트. 스키마 불일치 시 ZodError.

**GREEN**: `projectPermissionsSchema = z.object({ projectKey: z.string(), permissions: z.object({ CREATE: z.boolean() }) })` + `fetchProjectPermissions(projectKey)` (apiGet). `issue-permissions.ts` 미러.
- **(C4) Zod 키 정합 확인** — 백엔드 응답의 `permissions` 키는 `IssuePermission.CREATE.name`(="CREATE")이다. Task 1 산출 후 grep으로 Zod `CREATE` 키와 백엔드 직렬화 키 일치 확인(메모 `frontend-zod-backend-dto-contract-gap`).

**REFACTOR**: 타입 export(`ProjectPermissions`), KDoc.

**검증**: `pnpm --filter @bts/web test project-permissions`

### Task 3. 프론트 — MSW project-permissions 핸들러 + 등록

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/project-permission-handlers.ts`, `apps/web/src/mocks/__tests__/project-permission-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: []

**RED**: 핸들러 테스트 — alice 토큰 → 200 CREATE:true, 미인증 → 401, projectKey 누락 → 400.

**GREEN**: `issue-permission-handlers.ts` 패턴 미러. 토큰(username) 기반 — alice/bob → CREATE:true(둘 다 멤버). `handlers.ts`에 `...projectPermissionHandlers` 등록. 백엔드와 동일한 에러 분기 순서(401 → 400 → 200).
- **(C2) 비멤버 = 200 + CREATE:false (401 아님)** — 백엔드는 인증된 비멤버에게 200+false를 준다. 미러 시 "알려지지 않은 username → 401" 분기와 "인식된 username + 비멤버 → 200+false"를 구분할 것. 비활성 검증용 `CREATE:false` fixture를 별도 제공(S2 E2E 오버라이드와 정합).

**REFACTOR**: fixture 상수(`adminProjectPermissions`) 추출(옵션 B 스타일 — drift 차단).

**검증**: `pnpm --filter @bts/web test project-permission-handlers`

### Task 4. 프론트 — useProjectPermissions 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-project-permissions.ts`, `apps/web/src/hooks/use-project-permissions.test.ts`]
- depends-on: [2]

**RED**: 훅이 `fetchProjectPermissions`를 호출하고 data/isLoading/isError를 노출하는 테스트. projectKey 빈 문자열이면 비활성(enabled:false).

**GREEN**: `useProjectPermissions(projectKey)` — `useIssuePermissions` 미러. queryKey `['project-permissions', projectKey]`, staleTime 30초, enabled `!!projectKey`. `PROJECT_PERMISSION_KEYS` 상수.

**REFACTOR**: KDoc.

**검증**: `pnpm --filter @bts/web test use-project-permissions`

### Task 5. 프론트 — "새 이슈" 버튼 CREATE 게이트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.index.tsx`, `apps/web/src/routes/issues.index.test.tsx`]
- depends-on: [3, 4]   # (B2) T4 훅 + T3 MSW 핸들러 둘 다 필요

**RED**: 단위테스트 — `canCreate=true`(CREATE true) → 버튼 활성·클릭 이동, CREATE false/로딩/에러 → 비활성(클릭 무시). `data-testid="new-issue-button"`.

**GREEN**: `IssueListPage`에서 `useProjectPermissions(projectKey)` 호출. `canCreate = data?.permissions.CREATE === true`(fail-closed: 로딩/에러/undefined → false).
- **(C3) `<a>`→버튼 전환 시 기존 T5 호환 유지** — 활성(canCreate)일 때는 기존 `<a href="/issues/new">`(role=link, href) 그대로 렌더, 비활성일 때만 동일 스타일 `<button disabled>`로 렌더. 이렇게 하면 기존 `issues.index.test.tsx` T5(role=link + href 단언, 라인 103-110)가 활성 케이스에서 안 깨진다. 양쪽 모두 `data-testid="new-issue-button"` 부여.
- **(B1) 기존 테스트 MSW 등록** — `useProjectPermissions`가 렌더 시 `GET /project-permissions`를 발사하므로, 기존 `issues.index.test.tsx`의 모든 `server.use()`(T5 링크/빈상태/T6 클릭 등)에 `...projectPermissionHandlers` 추가(또는 `beforeEach` 공통 등록). 누락 시 `onUnhandledRequest:'error'`(setup.ts:8)로 기존 테스트가 깨진다.

**REFACTOR**: 게이트 로직 가독성 정리, 기존 헤더 스타일 클래스 보존(활성 `<a>`/비활성 `<button>` 동일 시각).

**검증**: `pnpm --filter @bts/web test issues.index` + `pnpm --filter @bts/web typecheck`

### Task 6. E2E — CREATE 게이트 시나리오 S1/S2

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-create-gate.spec.ts`]
- depends-on: [3, 5]

**RED→GREEN(E2E)**:
- S1: alice 로그인 → 이슈 목록 → `new-issue-button` 활성(enabled), 클릭 시 `/issues/new` 이동.
- S2: CREATE 없음 케이스 → MSW project-permissions 핸들러를 CREATE:false로 오버라이드(비멤버/회수 역할 시뮬레이션) → 버튼 비활성(disabled).
- 셀렉터는 `data-testid` 한정(텍스트 중복 회피, 메모 `playwright-getbyrole-exact-strict-mode`). 기존 E2E 함께 실행해 회귀 확인(메모 `ui-pr-defer-e2e-regression-latent`).

**검증**: `pnpm --filter @bts/web test:e2e issue-create-gate`

## Plan 메타

- task 수: 6
- 예상 wave: 4 — Wave1[T1·T2·T3 병렬, 의존0·파일 무겹침] → Wave2[T4(dep 2)] → Wave3[T5(dep 3,4)] → Wave4[T6(dep 3,5)]
- 예상 시간: 직렬 약 18분, wave 병렬 적용 약 10분
- TDD 강제: yes (`test:` 커밋이 `feat:` 앞)
- BC: identity-access 단일(+ same-BC 프론트 view layer)
- 추가 검증: ktlint/detekt(backend), typecheck(`pnpm --filter @bts/web typecheck`, 메모 ci-typecheck-tsconfig-app-vs-local), vitest, playwright
- 회귀 격리: 기존 issue-permissions 파일 무변경, 신규 파일 + handlers.ts 등록 1줄만

## 리뷰 결과

### code-reviewer 독립 plan 리뷰 (2026-06-02)

엔지니어링 집중 리뷰(autoplan 과함, 메모 `bts-review-plan-autoplan-overkill`). 도메인 무변경 근거·신규 분리 엔드포인트·보안 원칙 모두 코드와 정합 확인. BLOCKER 2 + CONCERN 4 발견 → **전부 plan 반영 완료**.

- **B1 (반영)** 기존 `issues.index.test.tsx`가 새 훅의 미핸들 `GET /project-permissions`로 깨짐(`setup.ts:8` `onUnhandledRequest:'error'`). → Task 5 GREEN에 기존 테스트 `server.use()` 핸들러 등록 명시.
- **B2 (반영)** Task 5 depends-on이 [4]뿐, T3(MSW) 의존 누락. → `[3, 4]`로 정정.
- **C1 (반영)** Task 1 RED에 "존재하지 않는 projectKey → 200+CREATE:false" 케이스 추가(devex 일관성).
- **C2 (반영)** Task 3 비멤버 = 200+false(401 아님). 미러 시 username 미인식(401)과 인식된 비멤버(200+false) 구분 명시.
- **C3 (반영)** Task 5 `<a>`→버튼 전환 시 활성=`<a href>`(role=link) 유지·비활성만 `<button disabled>` → 기존 T5 호환.
- **C4 (반영)** Task 2에 Zod `CREATE` 키 == 백엔드 `IssuePermission.CREATE.name` grep 확인 명시.
- N1/N2/N3: 비차단(wave 정정·범위밖 처리·PAT 감사로그 후속).

- **BLOCKER 잔여: 없음** (게이트1 진입 가능).
