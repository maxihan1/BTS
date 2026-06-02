# FR-CM-01 컴포넌트 관리 프론트엔드 UI + E2E (D6/D7)

> slug: fr-cm-01-component-frontend
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7)
> primary_bc: issue-tracking
> 생성: 2026-06-03

## Brief

FR-CM-01(프로젝트별 컴포넌트 CRUD + 컴포넌트 리드)의 백엔드 D1~D5는 PR #59에서 머지 완료. 이번 작업은 남은 **D6(프론트 UI — 컴포넌트 관리 페이지)** + **D7(E2E)**.

- plan 정본 항목: `docs/plan/product/issue-tracking.md` §3.1.1 FR-CM-01 D6/D7 (미체크)
- 백엔드 산출물(참조): PR #59 — `backend/modules/issue-tracking/.../component/*`, ADR `docs/adr/2026-06-02-component-model-and-permission-deferral.md`
- 백엔드 CRUD API(검증 대상): GET/POST `/api/projects/{idOrKey}/components`, PATCH `/api/.../components/{id}` (name/description), PATCH `.../components/{id}/lead`, DELETE `.../components/{id}` — 정확한 경로/계약은 spec 단계에서 백엔드 코드 grep로 확정

### 핵심 선행 learnings (이번 작업 적용)

- frontend-zod-backend-dto-contract-gap — Zod 스키마는 백엔드 DTO를 grep해 정합. invent 금지.
- e2e-msw-serviceworker-block — 새 API는 MSW 핸들러 추가가 정석, 분기순서 백엔드 일치.
- ui-pr-defer-e2e-regression-latent — UI PR(D6)은 기존 E2E 함께 실행. 텍스트 중복 버튼은 컨테이너/testid 한정.
- parallel-fr-overlapping-frontend-infra-collision — 컴포넌트 리드 선택은 기존 fetchUsers/useUsers 정본 재사용(중복 생성 금지).
- zod-v4-uuid-fixture-strictness — fixture UUID는 v4 형식.

## 도메인 정리

- **BC**: issue-tracking (프론트가 이 BC의 컴포넌트 API를 소비)
- **엔티티(프론트 타입 신규)**: `Component { id, projectId, name, description: string|null, leadUserId: string|null }`
- **새 용어**: 없음. "컴포넌트(Component)"는 glossary 기등재("프로젝트 내 하위 영역 분류"). "컴포넌트 리드"는 PR #59 도메인 ADR에 확립.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: `docs/adr/2026-06-02-component-model-and-permission-deferral.md` (PR #59 — 권한 prod 실판정은 FR-PM-03 이연, non-prod는 AlwaysAllow).

### 백엔드 API 계약 (PR #59 코드 grep 확정 — invent 금지)

Base path: `/api/v1/projects/{projectIdOrKey}/components` (projectIdOrKey = UUID 또는 projectKey)

| 메서드 | 경로 | 응답 | 비고 |
|---|---|---|---|
| POST | `` | 201 `{data: ComponentResponse}` | body: `{name(필수,≤255), description?(≤1000), leadUserId?}` |
| GET | `` | 200 `{data: ComponentResponse[]}` | 활성만(deleted_at null), name 오름차순 |
| GET | `/{id}` | 200 `{data: ComponentResponse}` | |
| PATCH | `/{id}` | 200 `{data: ComponentResponse}` | body: `{name?(≤255), description?(≤1000)}` — **null/생략 = 무변경 sentinel** (리드 변경 불가) |
| PATCH | `/{id}/lead` | 200 `{data: ComponentResponse}` | body: `{leadUserId: UUID|null}` — UUID=지정, null=해제 (2-state, assignee 동형) |
| DELETE | `/{id}` | 204 (no body) | 소프트 삭제 |

- **ComponentResponse**: `{ id: UUID, projectId: UUID, name: string, description: string|null, leadUserId: UUID|null }`
- **응답 래퍼**: `DataResponse = { data: ... }` (issues.ts와 동일, 프론트는 `wrapped.data` 추출)
- **에러 바디**: RFC 7807 ProblemDetail + 커스텀 `errorCode`(대문자 스네이크) + `timestamp`.
  - `VALIDATION_FAILED`(400), `PROJECT_NOT_FOUND`(404), `COMPONENT_NOT_FOUND`(404), `COMPONENT_NAME_DUPLICATE`(409), `COMPONENT_ACCESS_DENIED`(403), `COMPONENT_LEAD_NOT_FOUND`(422), `INTERNAL_ERROR`(500)
  - non-prod AlwaysAllow라 403은 실발생 안 함(미래 대비 스키마엔 포함). 인증 401은 client가 공통 처리.

### 프론트 관례 채택 (선례 grep 확정)

- **issue-tracking BC 관례 채택** = `apps/web/src/api/issues.ts` 패턴: 공유 `ApiError`(from `./client`) + `DataResponse` `wrapped.data` 언래핑 + `errorCode` 대문자. **project-members.ts 패턴(커스텀 ApiError + `{error: 소문자}`) 아님** — 계약갭 방지.
- **페이지 위치**: `/projects/$projectKey/settings/components` (선례 `projects.$projectKey.settings.members.tsx` / `.workflow-scheme.tsx` 동형). RouteAdapter + props 기반 Page 패턴(라우터 비의존 단위 테스트).
- **user 인프라 재사용**: `fetchUsers/useUsers`(검색) + `fetchUsersByIds/useUsersByIds`(현재 리드 이름 표시) 정본 재사용. **중복 생성 금지**(parallel-fr-overlapping-frontend-infra-collision).
- CSRF: mutation은 `readXsrfToken()` → `X-XSRF-TOKEN` 헤더(members/issues 선례).

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-cm-01-component-frontend.md](../specs/2026-06-03-fr-cm-01-component-frontend.md)

핵심 시나리오 요약.
- `/projects/$projectKey/settings/components`에서 활성 컴포넌트 목록(4분기, name 오름차순) — 멤버 설정 페이지 패턴 재사용.
- 추가 Dialog(이름 필수·설명·리드), 행별 수정(name/description, null=무변경)·리드 지정/해제(전용 /lead)·소프트 삭제.
- 리드는 기존 useUsers/useUsersByIds 재사용, mutation은 invalidate-only, errorCode(409 중복/422 리드미존재/404) i18n 매핑.

디자인 접근: 멤버 설정 패턴 재사용 (Maxi 결정 2026-06-03, design-shotgun 스킵).

## Brainstorming Check

✅ 통과 (1회 iteration). 네비 진입점 불필요(직접 URL 관례), description 비우기 sentinel은 plan 검증 이관, 리드 셀렉터 인라인 신규 작성.

## Plan

> agent 기본값: `frontend-engineer`. T10만 `qa-engineer`. 모두 TDD red→green→refactor.
> 공유 편집 파일: `handlers.ts`(T3 전용), `router.ts`(T9 전용) — wave 직렬화 격리.

### Task 1. 컴포넌트 API 클라이언트 + Zod 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/components.types.ts`, `apps/web/src/api/components.ts`, `apps/web/src/api/components.test.ts`]
- depends-on: []

**RED**: `components.test.ts` — fetchComponents가 `{data:[...]}`를 언래핑해 `Component[]` 반환, createComponent 201 파싱, errorCode 추출(409 COMPONENT_NAME_DUPLICATE → ApiError.status/errorCode), DELETE 204 무바디. (MSW server.use 인라인 또는 fetch mock)
**GREEN**: `components.types.ts`(componentResponseSchema = `z.object({id:uuid, projectId:uuid, name:min(1), description:nullable, leadUserId:uuid.nullable()})` + dataResponse 래퍼 + Create/Update/Lead 입력 타입). `components.ts`(issues.ts 관례 — apiGet/apiPost/apiFetch + 공유 ApiError, wrapped.data 추출, X-XSRF-TOKEN). 함수: fetchComponents/fetchComponent/createComponent/updateComponent/changeComponentLead/deleteComponent.
**REFACTOR**: 에러 파싱 헬퍼 + KDoc + null=무변경 sentinel 주석.
**검증**: `pnpm --filter @bts/web test components.test`

### Task 2. i18n 라벨 + 에러 메시지

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/component-labels.ts`, `apps/web/src/i18n/component-labels.test.ts`]
- depends-on: []

**RED**: 라벨 객체에 목록/추가/수정/삭제/리드/미지정/빈상태 + errorCode별 메시지(COMPONENT_NAME_DUPLICATE/COMPONENT_LEAD_NOT_FOUND/PROJECT_NOT_FOUND/VALIDATION_FAILED) 키 존재 검증.
**GREEN**: `component-labels.ts`(project-member-labels 선례 구조).
**REFACTOR**: errorCode→메시지 매핑 함수 추출.
**검증**: `pnpm --filter @bts/web test component-labels`

### Task 3. MSW 컴포넌트 핸들러 + 통합 인덱스 등록

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/component-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1]

**RED**: (핸들러는 테스트 인프라라 자체 테스트 없음 — 대신 handlers.ts에 componentHandlers spread 추가가 기존 handlers.test.ts 통과 유지하는지 + 후속 hook 테스트의 GREEN 근거) component-handlers의 stateful CRUD가 다음 task 테스트에서 RED→GREEN 작동.
**GREEN**: `component-handlers.ts` — stateful in-memory map. GET 목록(name 정렬)/POST(201, 이름중복 409 COMPONENT_NAME_DUPLICATE)/GET단건/PATCH(name·desc)/PATCH lead(422 토글 localStorage 플래그)/DELETE 204. errorCode는 ProblemDetail `{errorCode, ...}` 형태(백엔드 일치). 분기순서 백엔드 일치(메모리 e2e-msw-serviceworker-block). fixture UUID는 v4 형식(메모리 zod-v4-uuid-fixture-strictness). handlers.ts에 `...componentHandlers` 알파벳 위치 추가.
**REFACTOR**: 리셋 헬퍼 + 시드 fixture 분리.
**검증**: `pnpm --filter @bts/web test handlers.test`

### Task 4. TanStack Query 훅 (use-components)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-components.ts`, `apps/web/src/hooks/__tests__/use-components.test.tsx`]
- depends-on: [1, 3]

**RED**: useComponents(projectKey) 목록 캐싱, useCreate/useUpdate/useChangeLead/useDelete mutation이 성공 시 `['components', projectKey]` invalidate(invalidate-only, 메모리 mutation-setquerydata-partial-response-flicker), 409/422 에러 전파.
**GREEN**: `use-components.ts` — queryKey `['components', projectKey]`. mutation onSuccess invalidateQueries.
**REFACTOR**: queryKey 팩토리 + KDoc.
**검증**: `pnpm --filter @bts/web test use-components`

### Task 5. 리드 셀렉터 (ComponentLeadSelect)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/component/ComponentLeadSelect.tsx`, `apps/web/src/components/component/ComponentLeadSelect.test.tsx`]
- depends-on: [2]

**RED**: 검색 입력(debounce) → useUsers 결과 옵션 렌더 + "미지정" 옵션 + onChange(userId|null) 호출. 현재 리드 표시명은 useUsersByIds.
**GREEN**: `ComponentLeadSelect.tsx` — IssueMetaPanel assignee 인라인 패턴 차용(useUsers/useUsersByIds 재사용, 신규 user API 금지).
**REFACTOR**: debounce 상수 + aria-label.
**검증**: `pnpm --filter @bts/web test ComponentLeadSelect`

### Task 6. 생성/수정 Dialog (ComponentFormDialog)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/component/ComponentFormDialog.tsx`, `apps/web/src/components/component/ComponentFormDialog.test.tsx`]
- depends-on: [2, 5]

**RED**: 생성 모드(빈 폼)·수정 모드(기존값 prefill) 토글. 이름 필수 검증, 저장 시 onSubmit payload(수정은 변경 필드만 — null=무변경 sentinel 준수). 409 중복 시 폼 내 에러 표시, Dialog 유지. props 식별값으로 초기화 시 key prop 재마운트(메모리 react-usestate-stale-key-prop).
**GREEN**: `ComponentFormDialog.tsx`(shadcn Dialog + Input + ComponentLeadSelect, AddMemberDialog 선례).
**REFACTOR**: 폼 상태 훅 분리 + i18n.
**검증**: `pnpm --filter @bts/web test ComponentFormDialog`

### Task 7. 컴포넌트 행 (ComponentRow)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/component/ComponentRow.tsx`, `apps/web/src/components/component/ComponentRow.test.tsx`]
- depends-on: [2, 4]

**RED**: 이름·설명·리드표시명(useUsersByIds) 렌더 + 수정/삭제 액션(행 컨테이너 한정 aria-label, 메모리 ui-pr-defer-e2e-regression-latent). 삭제 확인 → useDelete. 리드 변경 → useChangeLead.
**GREEN**: `ComponentRow.tsx`(MemberRow 선례).
**REFACTOR**: 액션 핸들러 분리.
**검증**: `pnpm --filter @bts/web test ComponentRow`

### Task 8. 목록 컴포넌트 (ComponentList — 4분기)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/component/ComponentList.tsx`, `apps/web/src/components/component/ComponentList.test.tsx`]
- depends-on: [4, 6, 7]

**RED**: 4분기(로딩 스켈레톤/에러/빈/목록) + "컴포넌트 추가" 버튼 → ComponentFormDialog + ComponentRow 목록(name 오름차순). PROJECT_NOT_FOUND는 상위 페이지 위임.
**GREEN**: `ComponentList.tsx`(MemberList 선례, Card 레이아웃).
**REFACTOR**: 분기 가독성 + i18n.
**검증**: `pnpm --filter @bts/web test ComponentList`

### Task 9. 라우트 페이지 + 등록 (projects.$projectKey.settings.components)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.components.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.components.test.tsx`, `apps/web/src/router.ts`]
- depends-on: [8]

**RED**: RouteAdapter(useParams $projectKey) + Page(props 기반). PROJECT_NOT_FOUND(404) → 접근 불가 안내(members ProjectNotFoundScreen 선례), 정상 → 헤더 + ComponentList. 라우터 비의존 단위 테스트.
**GREEN**: route 페이지 + `router.ts`에 `/projects/$projectKey/settings/components` createRoute 등록(requireAuth, adapter import).
**REFACTOR**: 헤더 i18n + KDoc.
**검증**: `pnpm --filter @bts/web test settings.components`

### Task 10. E2E (Playwright) — D7

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/component-management.spec.ts`]
- depends-on: [3, 9]

**RED→GREEN**: S1 목록 / S2 생성 / S4 수정 / S5 리드 지정·해제 / S6 삭제 happy path. MSW component-handlers stateful 의존. 행별 액션은 행 컨테이너 한정 셀렉터(strict mode 회피). **기존 E2E 함께 실행해 회귀 0 확인**(메모리 ui-pr-defer-e2e-regression-latent). serviceWorkers:'block' 금지(MSW 의존).
**검증**: `pnpm --filter @bts/web test:e2e component-management` + 전체 E2E 회귀 확인.

## Plan 메타

- task 수: 10 (각 TDD 사이클)
- 예상 wave: 약 7 (api→msw/labels→hooks/dialog→row→list→page→e2e 자연 직렬 레이어). 프론트라 Gradle 모듈 직렬화는 무관, 파일 겹침은 handlers.ts(T3)·router.ts(T9)만 격리.
- TDD 강제: yes (test commit이 feat commit보다 먼저)
- 추가 검증: `tsc -p tsconfig.app.json`(메모리 ci-typecheck-tsconfig-app-vs-local) + `pnpm --filter @bts/web lint` + vitest 전체 + playwright(T10) + 기존 E2E 회귀 0
- plan 검증 이관 항목: description 빈 문자열 PATCH 시 백엔드 도메인 update 동작(빈→유지 vs 빈 허용) — T1/T6 구현 전 백엔드 ComponentApplicationService.update grep 확인

## 리뷰 결과 (← /bts-review-plan 채움)
