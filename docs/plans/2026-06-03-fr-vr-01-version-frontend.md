# FR-VR-01 D6/D7 — 버전 관리 프론트엔드 UI + E2E

> slug: fr-vr-01-version-frontend
> type: ui (classify qa 오판 정정 — D6 프론트 주 + D7 E2E. FR-CM-01 D6/D7도 ui)
> agent: frontend-engineer (+ qa-engineer: D7 E2E)
> primary_bc: issue-tracking
> 생성: 2026-06-03

## Brief

FR-VR-01 D6/D7 — 버전(Version) 관리 프론트엔드 UI + E2E. 백엔드 PR #67 머지됨(버전 CRUD API
`/api/v1/projects/{projectKey}/versions` + `/dates` 서브리소스).

- 범위: D6 프론트 UI + D7 E2E. 백엔드 D1~D5는 PR #67 완료.
- 선례 동형: 컴포넌트 프론트(FR-CM-01 D6/D7, PR #64) — `/projects/$projectKey/settings/components` 패턴.
  - api/versions.ts(+types) ← components.ts 동형
  - components/version/{VersionFormDialog, VersionList, VersionRow} ← component/* 동형
  - hooks/use-versions.ts ← use-components 동형
  - mocks/version-handlers.ts ← component-handlers 동형
  - i18n/version-labels.ts ← component-labels 동형
  - routes/projects.$projectKey.settings.versions.tsx ← settings.components 동형
- 핵심 차이: ComponentLeadSelect(리드 셀렉터) 자리에 **날짜 입력 2개**(startDate/releaseDate, 순서 미강제).
  날짜 수정은 백엔드 `/dates` 전용 서브리소스. 사용자 참조 없음(useUsers 등 불필요).
- 메모리 선반영 후보: frontend-api-convention-per-bc(issues.ts 관례: DataResponse+공유ApiError+body.errorCode 헬퍼, X-XSRF-TOKEN 수동), msw-mutation-stateful-refetch, e2e-msw-serviceworker-block, playwright-getbyrole-exact-strict-mode, ui-pr-defer-e2e-regression-latent(D6+기존E2E 함께), ci-typecheck-tsconfig-app-vs-local.

## 도메인 정리

- BC: issue-tracking (프론트 view layer)
- 새 용어: 없음 (Version 도메인은 백엔드 PR #67에서 확립, glossary "버전 Version" 기등록). grill-with-docs 스킵 — 백엔드에서 도메인 grill 완료, 프론트는 API 소비 UI.
- 소비 대상 API (백엔드 PR #67):
  - `GET /api/v1/projects/{projectKey}/versions` (목록, 활성만 name 정렬)
  - `GET /api/v1/projects/{projectKey}/versions/{id}` (단건)
  - `POST .../versions` (생성: name 필수, description?, startDate?, releaseDate?)
  - `PATCH .../versions/{id}` (name·description, 문자열 sentinel)
  - `PATCH .../versions/{id}/dates` (startDate·releaseDate 전용, 두 키 항상 명시 — null=해제)
  - `DELETE .../versions/{id}` (204 소프트 삭제)
  - 에러: 401 / 404 PROJECT_NOT_FOUND·VERSION_NOT_FOUND / 409 VERSION_NAME_DUPLICATE / 400 Validation
- 핵심 차이(vs 컴포넌트 프론트 FR-CM-01): ComponentLeadSelect(리드 사용자 셀렉터) 자리에 **날짜 입력 2개**(startDate/releaseDate). 사용자 참조 없음 → useUsers/useUsersByIds 불필요. 날짜 수정은 `/dates` 전용 서브리소스. 날짜 순서 미강제(프론트도 강제 안 함, 경고 정도는 spec에서 결정).
- 기존 결정 충돌: 없음
- 관련 ADR: [docs/adr/2026-06-03-version-model-and-permission-deferral.md](../adr/2026-06-03-version-model-and-permission-deferral.md) (백엔드, 권한 FR-PM-03 이연 — 프론트는 액션 버튼 노출이 권한 판정 전이라 현재 인증 사용자면 노출, 백엔드 AlwaysAllow와 정합)

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-vr-01-version-frontend.md](../specs/2026-06-03-fr-vr-01-version-frontend.md)

핵심 시나리오 요약.
- `/projects/$projectKey/settings/versions` 버전 관리 페이지 — 목록(4분기, name 정렬, 날짜 2열) + 생성/수정/삭제 Dialog.
- 생성: name 필수 + description/startDate/releaseDate 선택. 수정: 한 Dialog에서 name/desc + 날짜, **변경 그룹별 분리 호출**(name/desc→`PATCH /{id}`, 날짜→`PATCH /{id}/dates`).
- 날짜는 yyyy-MM-dd 문자열(z.string nullable), 순서 미강제(역순 허용), 비우면 해제.
- 컴포넌트 프론트(PR #64) 동형, 차이: 리드/useUsers 전면 제거, ComponentLeadSelect 자리에 날짜 입력 2개.
- 에러: 401/404 PROJECT·VERSION_NOT_FOUND/409 VERSION_NAME_DUPLICATE. 권한 UI 최소화(FR-PM-03 이연).

## Brainstorming Check

✅ 통과 (직접 적대적 sanity check, office-hours 스킵 — 정의된 UI FR + 컴포넌트 프론트 1:1 선례).
주요: 날짜 수정 UI(Dialog 통합 + 변경 그룹별 분리 호출, 게이트1 검토), 사용자 참조 전면 제거, 날짜 yyyy-MM-dd 직렬화, 순서 미강제. 미해소 결정 없음(날짜 UI 방식만 Maxi 확정 대기).

## Plan

> 검증: `pnpm --filter @bts/web test`(vitest) / `typecheck`(tsc -p tsconfig.app.json, 메모리 ci-typecheck-tsconfig-app-vs-local) / `lint` / `test:e2e`.
> dispatch: **직렬**(같은 worktree 동시 커밋 시 lint-staged race — 메모리 parallel-dispatch-precommit-hook-race, 컴포넌트 PR #64 race 경험). 의존 그래프는 아래.
> TDD red→green→refactor 강제. 각 agent 자기 파일만 stage(-A 금지).
> 모든 신규 파일 = 컴포넌트 프론트(PR #64) 동형. 차이: 리드(ChangeLead/ComponentLeadSelect/useUsers) 제거, 날짜 2필드(startDate/releaseDate) 추가.

### Task 1. api/versions.ts + versions.types.ts

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/versions.types.ts`, `apps/web/src/api/versions.ts`, `apps/web/src/api/versions.test.ts`]
- depends-on: []

**RED**: versions.test.ts — fetchVersions/fetchVersion/createVersion/updateVersion/**changeVersionDates**/deleteVersion + extractVersionErrorCode + Zod 파싱(날짜 string nullable). 실패: 모듈 없음.
**GREEN**: components.ts/components.types.ts 동형. `versionResponseSchema` = **정확히 6필드**(id, projectId, name, description nullable, **startDate/releaseDate: z.string().nullable()**) — **createdAt/updatedAt 추가 금지**(B1: ground-truth VersionResponse.kt 6필드, invent 시 실서버 z.parse 실패 frontend-zod-backend-dto-contract-gap). CreateVersionInput(name, description?, startDate?, releaseDate?), UpdateVersionInput(name?, description?), ChangeDatesInput(startDate, releaseDate). `changeVersionDates(projectKey, id, {startDate, releaseDate})` → `PATCH /{id}/dates`(컴포넌트 changeComponentLead는 단일인자라 객체인자로 변경). X-XSRF-TOKEN. extractVersionErrorCode. **리드 관련 전부 제거**.
**REFACTOR**: KDoc·basePath 헬퍼.
**검증**: `pnpm --filter @bts/web test -- versions` + typecheck.

### Task 2. i18n/version-labels.ts

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/version-labels.ts`, `apps/web/src/i18n/version-labels.test.ts`]
- depends-on: []

**RED**: version-labels.test.ts — versionLabels(page heading/description, 필드명, 액션) + versionErrorMessage(code→메시지: VERSION_NAME_DUPLICATE/PROJECT_NOT_FOUND/VERSION_NOT_FOUND/VALIDATION_FAILED/null). 실패: 모듈 없음.
**GREEN**: component-labels.ts 동형. 날짜 라벨(시작일/릴리즈 예정일) 추가, 리드 라벨 제거. LEAD_NOT_FOUND 없음.
**REFACTOR**: 상수 구조 정리.
**검증**: `pnpm --filter @bts/web test -- version-labels`.

### Task 3. mocks/version-handlers.ts

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/version-handlers.ts`, `apps/web/src/mocks/version-handlers.test.ts`]
- depends-on: [1]

**RED**: version-handlers.test.ts — stateful CRUD + /dates + 분기(409 중복/404/400). 실패: 핸들러 없음.
**GREEN**: component-handlers.ts 동형. GET목록/단건, POST(201, 409 중복), PATCH(name/desc), **PATCH /dates(날짜 치환)**, DELETE(204). **mutation 결과 stateful 영속**(메모리 msw-mutation-stateful-refetch — invalidate refetch 후 롤백 방지). 분기순서 백엔드 일치. errorCode 토글. 핸들러를 컴포넌트와 동일 위치(mocks/handlers.ts)에 등록. **C2: version-handlers 전역 GET목록은 항상 200**(PROJECT_NOT_FOUND 자체발행 안 함 — 컴포넌트 동형. 404는 Task 8 route 테스트가 server.use per-test 오버라이드로 주입). VERSION_NAME_DUPLICATE/VERSION_NOT_FOUND/VALIDATION_FAILED만 발행.
**REFACTOR**: fixture는 component-handlers의 generateUuidV4 헬퍼 재사용(v4, 메모리 zod-v4-uuid-fixture-strictness 또는 crypto.randomUUID 폴백 동형), 헬퍼 정리.
**검증**: `pnpm --filter @bts/web test -- version-handlers`.

### Task 4. hooks/use-versions.ts

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-versions.ts`, `apps/web/src/hooks/__tests__/use-versions.test.tsx`]
- depends-on: [1, 2]

**RED**: use-versions.test.tsx — useVersions(목록), useCreateVersion, useUpdateVersion, **useChangeVersionDates**, useDeleteVersion. invalidate-only + onError 토스트(단일 발사). 실패: 훅 없음.
**GREEN**: use-components.ts 동형. VERSION_KEYS.list(projectKey)=['versions', projectKey]. useChangeComponentLead 자리에 useChangeVersionDates. 리드 훅 제거.
**REFACTOR**: queryKey 팩토리·notifyVersionError.
**검증**: `pnpm --filter @bts/web test -- use-versions`.

### Task 5. components/version/VersionFormDialog

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/version/VersionFormDialog.tsx`, `apps/web/src/components/version/VersionFormDialog.test.tsx`]
- depends-on: [1, 2, 4]

**RED**: VersionFormDialog.test.tsx — 생성 모드(name 필수 검증, desc/startDate/releaseDate 입력), 수정 모드(기존값 prefill, **변경 그룹별 분리 호출** 강제 케이스: 둘 다 미변경→0 호출 / 날짜만 변경→useChangeVersionDates 1회만 / name·desc만→useUpdateVersion 1회만 / 둘 다→2회), 날짜 비우기=해제, 역순 허용, 409 폼 에러. 실패: 컴포넌트 없음.
**GREEN**(C1 — 선례 동형 아닌 **신규 설계** 명시): ComponentFormDialog.tsx의 레이아웃/key패턴은 참고하되, 컴포넌트는 리드를 Row 인라인에서 처리하고 Dialog는 단일 mutation이므로 **Dialog의 2-mutation 분기는 신규**. ComponentLeadSelect 자리에 date input 2개(ui/input type=date). onSubmit 변경 감지: **date input 빈 문자열('')은 null로 정규화** 후 initial(string|null) 대비 비교. name/desc 그룹과 날짜 그룹 독립 판정 → 변경된 그룹의 mutation만 호출. props 식별값으로 useState 초기화 시 key prop 재마운트(메모리 react-usestate-stale-key-prop, ComponentFormDialog key={formKey} 패턴).
**REFACTOR**: 변경 감지 헬퍼(''→null 정규화 포함) 추출·KDoc.
**검증**: `pnpm --filter @bts/web test -- VersionFormDialog` + typecheck.

### Task 6. components/version/VersionRow

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/version/VersionRow.tsx`, `apps/web/src/components/version/VersionRow.test.tsx`]
- depends-on: [1, 2]

**RED**: VersionRow.test.tsx — 행 표시(이름·설명·시작일·릴리즈일, 미지정 "—"), 수정 버튼 + **인라인 삭제 확인**(N5: ComponentRow 동형, window.confirm 아님 — 삭제 클릭→인라인 DeleteConfirm 노출→확인). 행 컨테이너 한정 셀렉터 대비 aria-label/test-id. 실패: 컴포넌트 없음.
**GREEN**: ComponentRow.tsx 동형(L70-93 인라인 DeleteConfirm 패턴 포함). 리드 표시 자리에 날짜 2열(yyyy-MM-dd 또는 로케일 표시), null→"—". 액션 버튼.
**REFACTOR**: 날짜 포맷 헬퍼·KDoc.
**검증**: `pnpm --filter @bts/web test -- VersionRow`.

### Task 7. components/version/VersionList

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/version/VersionList.tsx`, `apps/web/src/components/version/VersionList.test.tsx`]
- depends-on: [4, 5, 6]

**RED**: VersionList.test.tsx — 4분기(로딩 스켈레톤/에러/빈/목록 name정렬), "버전 추가" 버튼→생성 Dialog, 행 수정→수정 Dialog, 삭제 확인. 실패: 컴포넌트 없음.
**GREEN**: ComponentList.tsx 동형. useVersions + VersionFormDialog + VersionRow 연결. 삭제 확인.
**REFACTOR**: 분기 추출·KDoc.
**검증**: `pnpm --filter @bts/web test -- VersionList`.

### Task 8. route projects.$projectKey.settings.versions.tsx + router 등록

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.versions.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.versions.test.tsx`, `apps/web/src/router.ts`]
- depends-on: [4, 7]

**RED**: route 테스트 — Page(props projectKey) 렌더, PROJECT_NOT_FOUND→ProjectNotFoundScreen(members/components 선례 재사용). **C2: 404는 `server.use(...)` per-test 오버라이드로 주입**(components.test.tsx 동형 — version-handlers 전역은 200 유지). 정상→헤더+VersionList. 실패: 라우트 없음.
**GREEN**: settings.components.tsx 동형. RouteAdapter(useParams) + Page(props). router.ts에 `/projects/$projectKey/settings/versions` 등록(requireAuth, code-based adapter 패턴 메모리 TanStack code-based).
**REFACTOR**: KDoc.
**검증**: `pnpm --filter @bts/web test -- settings.versions` + typecheck + lint.

### Task 9. E2E version-management.spec.ts

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/version-management.spec.ts`]
- depends-on: [8]

**RED→GREEN**: Playwright happy path — S1 목록, S2 생성, S4 수정(name+날짜), S5 날짜 해제, S6 삭제. **B2: 파일 경로는 `apps/web/e2e/`**(playwright testDir, component-management.spec.ts 동일 위치 — tests/e2e/ 아님, 안 그러면 미실행 false-green). **행 컨테이너 한정 셀렉터**(메모리 playwright-getbyrole-exact-strict-mode, ui-pr-defer-e2e-regression-latent). **N4: 테스트마다 고유 버전명 접두사(S2-/S4- 등)로 stateful store 오염/중복409 회피**(component-management.spec.ts 동형, reset 헤더 없음). 삭제는 인라인 confirm 컨테이너 한정 클릭. MSW serviceWorker block 금지(메모리 e2e-msw-serviceworker-block). **기존 E2E 함께 실행해 회귀 0 확인**(UI PR은 기존 E2E 동반). 구현 코드 수정 금지.
**검증**: `pnpm --filter @bts/web test:e2e` (version-management + 기존 전체).

## Plan 메타

- task 수: 9
- dispatch: **직렬**(worktree 커밋 race 회피). 의존: T1·T2 독립 → T3[1]·T6[1,2] → T4[1,2] → T5[1,2,4] → T7[4,5,6] → T8[4,7] → T9[8].
- TDD 강제: yes. 각 agent 자기 파일만 stage.
- 핵심 차이(vs FR-CM-01 프론트): 리드/useUsers/ChangeLead/422 전면 제거, 날짜 2필드 + /dates 전용 + 수정 Dialog 변경 그룹 분리 호출.
- 추가 검증: 머지 전 controller가 typecheck(tsconfig.app)+lint+전체 vitest+E2E 직접 실행(메모리 ci-typecheck-tsconfig-app-vs-local, subagent false-green 방지).

## 리뷰 결과

### plan-review (2026-06-03, code-reviewer 독립 dispatch, ground-truth 대조)

type=ui지만 핵심 리스크가 Zod↔백엔드 DTO 계약(frontend-zod-backend-dto-contract-gap)이라 design-review 대신 code-reviewer를 백엔드 VersionResponse DTO/컴포넌트 프론트 실제 코드와 대조 dispatch. 위험이 전부 "동형 복붙" 가정이 실제 선례 구조와 어긋나는 지점에 집중.

- **BLOCKER 2건 → 해소(plan/spec 수정 완료)**:
  - **B1** — spec이 VersionResponse에 createdAt/updatedAt을 invent했으나 ground-truth는 6필드뿐(id/projectId/name/description/startDate/releaseDate). 실서버 z.parse 실패 잠복. **해소: spec 6필드 고정, Task 1 "createdAt/updatedAt 추가 금지" 명시.**
  - **B2** — Task 9 E2E 경로가 `apps/web/tests/e2e/`(미존재)라 testDir 밖→미실행 false-green. **해소: `apps/web/e2e/`로 정정.**
- **CONCERN 3건 → 반영**:
  - **C1** — "변경 그룹 분리 호출"이 선례 동형이 아닌 신규 설계(컴포넌트는 리드를 Row 인라인에서 처리, Dialog는 단일 mutation). **해소: Task 5에 신규 설계 명시 + date input ''→null 정규화 + 단위테스트 0/1/1/2 호출 케이스 강제.**
  - **C2** — PROJECT_NOT_FOUND를 version-handlers가 자체발행 안 함(컴포넌트 동형). **해소: Task 3 전역 200 유지 + Task 8 route 테스트 server.use per-test 오버라이드 명시.**
  - **C3** — 권한: 프론트 영향 없음(PASS, requireAuth+404로 충분).
- **NIT 반영**: N4 E2E 고유 이름 격리 + generateUuidV4 헬퍼 재사용(Task 3/9), N5 인라인 DeleteConfirm 동형(Task 6).
- **PASS (ground-truth 확인)**: /dates 계약({startDate,releaseDate} 두 키, PATCH /{id}/dates 경로), 날짜 z.string().nullable()(@JsonFormat yyyy-MM-dd), errorCode 5종 실제 값 일치, depends-on 그래프 순환·누락 0, 직렬 dispatch 타당.
