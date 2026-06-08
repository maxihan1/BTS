# FR-PM-07 PR-B — 필드 수준 권한 프론트 UI + E2E

> slug: fr-pm-07-frontend
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-08

## Brief

FR-PM-07 PR-B — 필드 수준 권한 프론트(D6) + E2E(D7). 백엔드 PR-A(#97) 머지 완료.
범위: 이슈 화면 restrictedFields 기반 숨김 필드 렌더 차단 + 편집 불가 컨트롤 비활성, 규칙 관리 화면(field-permissions CRUD), E2E 시나리오.
도메인/스펙 재사용: docs/specs/2026-06-08-fr-pm-07-field-permissions.md (§S8, §9), ADR docs/decisions/2026-06-08-field-level-permissions.md.

## 도메인 정리

PR-A 재사용. ADR [2026-06-08-field-level-permissions](../decisions/2026-06-08-field-level-permissions.md). 신규 도메인 없음(프론트는 백엔드 계약 소비).

## 스펙

PR-A 스펙 §S8(프론트 렌더 차단)·§4(API)·§9(완료기준 D6/D7) 재사용. [docs/specs/2026-06-08-fr-pm-07-field-permissions.md](../specs/2026-06-08-fr-pm-07-field-permissions.md).

## Brainstorming Check

PR-A에서 완료(스펙 §Brainstorming Check). 프론트는 백엔드 계약 소비라 신규 gap 없음.

## Plan

> 선례 복제 = FR-IS-10 커스텀 필드 관리 UI(#98). field-permissions는 그 List/Dialog/Row/훅/MSW/E2E 패턴을 그대로 복제하되 규칙 필드(`fieldKind/fieldKey/groupId/accessLevel`)로 특화.
> 백엔드 계약(PR-A): `restrictedFields: List<String>`, `FieldPermissionResponse{id,fieldKind,fieldKey,groupId,groupName,accessLevel}`, `CreateFieldPermissionRequest{fieldKind,fieldKey,groupId,accessLevel}`. T1이 `noneditableFields` 추가.

### Task 1. 백엔드 이슈 응답 `noneditableFields` 추가 (편집 미리 비활성 지원)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueFieldVisibilityTest.kt`]
- depends-on: []

**RED**: `IssueFieldVisibilityTest`에 케이스 추가 — visible이지만 editable 아닌 필드가 `noneditableFields`에 담기고, restrictedFields(숨김)와 비중복, editable 필드는 미포함. → `test: fr-pm-07-fe task-1 red`

**GREEN**: `IssueResponse.noneditableFields: List<String> = emptyList()` 추가. ApplicationService 마스킹 후처리(maskFieldsForSingle/Page)에서 `editableFields(actor, projectId, visibleCandidates)` 호출 → visible−editable = noneditable 계산(restrictedFields 제외분만). 단건·목록. → `feat: ... green`

**REFACTOR**: 헬퍼 분리 + KDoc. → `refactor: ...`

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*IssueFieldVisibilityTest" --rerun-tasks`

---

### Task 2. 프론트 field-permissions API client + types + 그룹 조회 client

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/field-permissions.ts`, `apps/web/src/api/field-permissions.types.ts`, `apps/web/src/api/groups.ts`, `apps/web/src/api/__tests__/field-permissions.test.ts`]
- depends-on: []

**RED**: vitest — `fetchFieldPermissions`/`createFieldPermission`/`deleteFieldPermission`/`fetchGroups` 호출 시 올바른 경로·메서드·CSRF 헤더(X-XSRF-TOKEN). Zod 파싱(백엔드 계약). → `test: ... red`

**GREEN**: `custom-fields.ts` 패턴 복제 — `fetchFieldPermissions(projectKey)`(GET), `createFieldPermission(projectKey, input)`(POST+CSRF), `deleteFieldPermission(projectKey, id)`(DELETE+CSRF). `fetchGroups()`(GET /api/v1/groups). Zod: `FieldPermissionRule{id,fieldKind('CORE'|'CUSTOM'),fieldKey,groupId,groupName,accessLevel('VIEW'|'EDIT')}`, `CreateFieldPermissionInput`. **백엔드 DTO와 정합**(learnings: frontend-zod-backend-dto-contract-gap — invent 금지, spec서 grep). → `feat: ... green`

**REFACTOR**: errorCode 추출 헬퍼 + JSDoc. → `refactor: ...`

**검증**: `cd apps/web && pnpm test field-permissions`

---

### Task 3. 프론트 IssueResponse Zod에 restrictedFields + noneditableFields 추가

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/__tests__/issues.test.ts`]
- depends-on: [1]

**RED**: issueResponseSchema 파싱 시 `restrictedFields: string[]`·`noneditableFields: string[]` 포함(기본 빈배열). 백엔드 계약 이름 정합. → `test: ... red`

**GREEN**: `restrictedFields: z.array(z.string()).default([])`, `noneditableFields: z.array(z.string()).default([])` 추가. (Zod 강화 시 인라인 mock 파급 grep — learnings: zod-schema-strengthen-inline-mock-fanout) → `feat: ... green`

**REFACTOR**: 주석. → `refactor: ...`

**검증**: `cd apps/web && pnpm test issues`

---

### Task 4. useFieldPermissions CRUD 훅 + useGroups 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-field-permissions.ts`, `apps/web/src/hooks/use-groups.ts`, `apps/web/src/hooks/__tests__/use-field-permissions.test.tsx`]
- depends-on: [2]

**RED**: TanStack Query 훅 — `useFieldPermissions(projectKey)`(목록), `useCreateFieldPermission`/`useDeleteFieldPermission`(invalidate-only, silent), `useGroups()`. `use-custom-fields.ts` 패턴. → `test: ... red`

**GREEN**: queryKey `['field-permissions', projectKey]`·`['groups']`. mutation invalidate-only(learnings: mutation-setquerydata-partial-response-flicker). → `feat: ... green`

**REFACTOR**: JSDoc. → `refactor: ...`

**검증**: `cd apps/web && pnpm test use-field-permissions`

---

### Task 5. 규칙 관리 화면 (List/Dialog/Row) + 라우트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/field-permissions/FieldPermissionList.tsx`, `apps/web/src/components/field-permissions/FieldPermissionFormDialog.tsx`, `apps/web/src/components/field-permissions/FieldPermissionRow.tsx`, `apps/web/src/routes/projects.$projectKey.settings.field-permissions.tsx`, `apps/web/src/router.ts`, `apps/web/src/api/project-permissions.ts`, `apps/web/src/components/field-permissions/__tests__/FieldPermissionList.test.tsx`]
- depends-on: [4]

**RED**: vitest — 목록 4분기(로딩/에러/빈/목록), MANAGE_FIELD_PERMISSIONS 게이팅(버튼 disabled), Dialog 생성(필드+그룹+VIEW/EDIT 선택), Row 삭제. data-testid 부여(텍스트 중복 회피, learnings: playwright-getbyrole-exact-strict-mode). → `test: ... red`

**GREEN**: CustomFieldList/FormDialog/Row + RouteAdapter 패턴 복제. Dialog 입력 = fieldKind(CORE/CUSTOM)·fieldKey(CORE는 화이트리스트 select·CUSTOM은 커스텀 필드 select)·groupId(useGroups 드롭다운)·accessLevel(VIEW/EDIT). router.ts에 `projectFieldPermissionsSettingsRoute`(`/projects/$projectKey/settings/field-permissions`, requireAuth). project-permissions Zod에 MANAGE_FIELD_PERMISSIONS 추가. → `feat: ... green`

**REFACTOR**: submitError 소유구조 확인(learnings: dialog-submiterror-ownership-dead-path) + JSDoc + 한국어 헤더. → `refactor: ...`

**검증**: `cd apps/web && pnpm test FieldPermissionList`

---

### Task 6. 이슈 화면 제한 필드 숨김 + 편집 비활성

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/issue/IssueDescription.tsx`, `apps/web/src/components/issue/__tests__/IssueMetaPanel.fieldperm.test.tsx`]
- depends-on: [3]

**RED**: vitest — restrictedFields에 든 코어/커스텀 필드는 렌더 안 됨(숨김), noneditableFields에 든 필드는 입력 컨트롤 `disabled`. 기존 `canEdit`(useIssuePermissions UPDATE)와 AND. summary/priority(non-null)는 숨김 대상 아님. → `test: ... red`

**GREEN**: `issue.restrictedFields.includes(key)`면 렌더 차단, `issue.noneditableFields.includes(key)`면 `disabled`. 커스텀 필드(CustomFieldInput)·코어 필드 각 렌더부에 적용. → `feat: ... green`

**REFACTOR**: 헬퍼(isFieldHidden/isFieldDisabled) 분리 + JSDoc. → `refactor: ...`

**검증**: `cd apps/web && pnpm test IssueMetaPanel`

---

### Task 7. MSW field-permission-handlers + 이슈 핸들러 보강

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/field-permission-handlers.ts`, `apps/web/src/mocks/handlers.ts`, `apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/group-handlers.ts`]
- depends-on: [2, 3]

**GREEN**(인프라 코드 — RED는 T8 E2E가 소비, 생략 사유 명시): custom-field-handlers 패턴 복제 — stateful Map + RFC7807 + CSRF + 403 토글(localStorage). group-handlers(GET /api/v1/groups). 이슈 핸들러에 restrictedFields/noneditableFields 시드(공유 store 파생, learnings: msw-derived-behavior-shared-store-e2e · e2e-msw-scenario-toggle-localstorage-flag). handlers.ts 등록. → `feat: fr-pm-07-fe task-7 — MSW 핸들러`

**REFACTOR**: 한국어 헤더 + 공유 store 정리. → `refactor: ...`

**검증**: `cd apps/web && pnpm test` (회귀 0) + `pnpm build`

---

### Task 8. E2E — 규칙 CRUD + 이슈 화면 제한 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/field-permissions.spec.ts`]
- depends-on: [5, 6, 7]

**RED→GREEN**: Playwright(custom-fields.spec 패턴) — S1 규칙 생성(필드+그룹+VIEW/EDIT)→목록, S2 규칙 삭제, S3 이슈 화면 제한 필드 숨김(restrictedFields)·편집 비활성(noneditableFields), S4 MANAGE_FIELD_PERMISSIONS 없음 403 토글. **구현 코드 수정 금지**(테스트만). MSW 시나리오 localStorage 토글 + addInitScript. 기존 이슈 E2E 함께 실행해 회귀 확인(learnings: ui-pr-defer-e2e-regression-latent). → `test: fr-pm-07-fe task-8 e2e`

**검증**: `cd apps/web && pnpm test:e2e field-permissions`

---

## Plan 메타

- task 수: 8 (백엔드 view layer 1 + 프론트 6 + E2E 1)
- 모듈: issue-tracking(T1) + apps/web(T2~T8)
- 예상 wave: wave1[T1,T2] → wave2[T3,T4] → wave3[T5,T6,T7] → wave4[T8]
- TDD 강제: yes (프론트 vitest red→green, E2E는 qa)
- 추가 검증: pnpm typecheck(tsconfig.app.json — learnings: ci-typecheck-tsconfig-app-vs-local) + lint + test + build + E2E
- 계약 정합: 프론트 Zod ↔ 백엔드 DTO grep 검증(learnings: frontend-zod-backend-dto-contract-gap)

## 리뷰 결과 (← /bts-review-plan 채움)
