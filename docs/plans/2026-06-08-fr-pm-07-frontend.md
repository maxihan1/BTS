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

### Task 9. identity-access 권한 노출 + 그룹 읽기 API (게이팅·그룹선택 결선)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/MyProjectPermissionController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/UserGroupController.kt`, `backend/modules/identity-access/src/test/kotlin/.../MyProjectPermissionControllerTest.kt`, `backend/modules/identity-access/src/test/kotlin/.../UserGroupControllerTest.kt`]
- depends-on: []

**RED→GREEN**:
- `MyProjectPermissionController` 응답 맵에 `MANAGE_FIELD_PERMISSIONS` 추가(MANAGE_CUSTOM_FIELDS 동형, PROJECT_ADMIN 전용). 게이팅 노출(learnings: ui-permission-gating-needs-summary-api-exposure). prod 통합테스트.
- 그룹 목록 읽기 — **Maxi 확정(옵션 A)**: `GET /api/v1/groups`를 **인증 사용자 읽기 허용**(이름+id+멤버수만 반환). 생성/수정/삭제/멤버 관리(POST/PATCH/DELETE)는 SYSTEM_ADMIN 유지. UserGroupController의 listGroups 가드만 isAuthenticated로 완화, 나머지 핸들러 가드 불변. prod 통합테스트로 "일반 사용자 GET 200·write 403" 실증.

**검증**: `cd backend && ./gradlew :modules:identity-access:test --tests "*MyProjectPermissionControllerTest" --tests "*UserGroupControllerTest" --rerun-tasks`

> T2(그룹조회 client)·T5(게이팅 소비)는 T9 의존. wave1에 T9 포함.

## 리뷰 결과

### plan-eng-review (2026-06-08, 독립 코드검증)

프론트 plan 가정을 코드 grep으로 검증. 백엔드 계약·패턴 실재 확인 + **2 갭 발견(반영 완료)**.

- ✅ **계약 정합**: `restrictedFields: List<String>`·`FieldPermissionResponse{id,fieldKind,fieldKey,groupId,groupName,accessLevel}`·`CreateFieldPermissionRequest` 실재(IssueResponse.kt, FieldPermissionDtos.kt). 프론트 Zod가 이 이름 그대로 따름.
- ✅ **패턴 재사용**: 커스텀 필드 관리 UI(#98) List/Dialog/Row/훅/MSW/E2E + RouteAdapter + useProjectPermissions 게이팅 + CSRF client 패턴 실재 — field-permissions 복제 대상 명확.
- ✅ **이슈 화면**: IssueMetaPanel(customFields+코어 렌더, useIssuePermissions canEdit), IssueDescription 실재 — restrictedFields 숨김·noneditableFields 비활성 적용 지점 명확.
- ⚠️ **갭1(반영)**: `MyProjectPermissionController`가 `MANAGE_FIELD_PERMISSIONS` 미노출 → Task 9에서 추가(MANAGE_CUSTOM_FIELDS 동형). 미반영 시 규칙 관리 버튼 게이팅 불가.
- ⚠️ **갭2(반영, Maxi 결정 필요)**: `GET /api/v1/groups`가 **SYSTEM_ADMIN 전용**인데 규칙 관리자는 PROJECT_ADMIN(MANAGE_FIELD_PERMISSIONS) → 그룹 드롭다운 못 채움. Task 9에서 그룹 목록 읽기 권한 완화. **방식은 게이트1 Maxi 결정**:
  - (A) `GET /api/v1/groups`를 **인증 사용자 읽기 허용**(이름+id+멤버수만, 관리는 SYSTEM_ADMIN 유지) — 단순, 그룹 이름은 비밀이 아님.
  - (B) `MANAGE_FIELD_PERMISSIONS` **보유자 한정** 읽기 — 더 좁은 노출, 프로젝트 권한 컨텍스트 필요(현 그룹 API는 전역이라 projectKey 인자 추가).
- **BLOCKER: 없음**(갭 2건 Task 9로 흡수). PR-B = 프론트(T2~T8) + issue-tracking view layer(T1) + identity-access 권한 노출(T9) cross-BC. learnings 옵션 C(프론트 PR의 same-pattern 백엔드 view/권한 결선) 적용.

### plan-design-review

스킵. 규칙 관리 화면은 커스텀 필드 관리(#98) 디자인 복제라 신규 비주얼 결정 없음(learnings: bts-review-plan-autoplan-overkill 정신). 기존 설정 탭 레이아웃 재사용.
