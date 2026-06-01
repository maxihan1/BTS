# FR-IS-03 D6/D7 프론트 — 담당자 셀렉터(GET /api/v1/users) + PATCH /assignee + Playwright E2E

> slug: fr-is-03-d6-d7-assignee-ui
> type: feature (ui + e2e)
> agent: frontend-engineer (D6), qa-engineer (D7)
> 생성: 2026-06-01
> PR 범위: D6 + D7 한 PR (Maxi 확정 — UI PR이 E2E를 미루면 회귀 잠복, ui-pr-defer-e2e-regression-latent 교훈)

## Brief

FR-IS-03(이슈 담당자 — Reporter 1 / Assignee 1 / Watchers N)의 프론트엔드 짝(D6) + E2E(D7).
백엔드 D1~D5는 **PR #49(머지 완료)** 로 끝남. 이번 PR은 그 백엔드 계약을 프론트에 노출한다.

- 담당자 셀렉터: `GET /api/v1/users?query=` 로 사용자 검색 → 선택/해제 → `PATCH /api/v1/issues/{key}/assignee`
- `IssueMetaPanel`에 담당자 섹션 추가 (기존 셀렉터 패턴 재사용 — Maxi 확정)
- E2E: 담당자 할당 / 검색 / 해제 시나리오

**선례 패턴**: PR #46 D6(우선순위/영향도/환경/라벨)·PR #41(전이 UI)이 같은 화면(IssueMetaPanel / issues.$key) 작업.

## 도메인 정리

- **BC**: issue-tracking + identity-access(읽기 노출). 프론트는 두 API 소비만.
- **새 용어**: 없음. Assignee(담당자)/Reporter(보고자)는 SDD 정본 + 백엔드 구현 완료.
- **관련 ADR**: `docs/adr/2026-06-01-issue-assignee-user-lookup-port.md` — issue-tracking은 `assigneeId`(UUID)만 노출, **프론트가 사용자 목록으로 id→이름 매핑**. issue-tracking은 사용자 이름을 모름(BC 격리).
- **게이트1 승인 필요**: 없음 (외부 의존성 신규 도입 없음. cmdk는 FR-IS-09 예정 — 이번엔 native 요소만 사용, §1.17 의존성 추가 금지 준수).

### 백엔드 계약 (grep 검증 완료 — frontend-zod-backend-dto-contract-gap 회피)

**`GET /api/v1/users?query=<prefix>` (identity-access UsersController.kt):**
- 인증 필수(PII). 응답: `List<UserSummaryResponse>` (배열 직접, `{data:}` 래퍼 **없음**)
- 항목 필드: `{ id: UUID, username: String, displayName: String?, email: String? }`
- `query` 미전달 시 전체(최대 MAX_RESULTS건). username/displayName 부분일치.

**`PATCH /api/v1/issues/{key}/assignee` (IssueController.kt + ChangeAssigneeRequest.kt):**
- 요청 body: `{ assigneeId: UUID|null, expectedVersion: Long }`
- `assigneeId=null` → 담당자 해제 (3-state null 시맨틱, 전용 서브리소스)
- `expectedVersion` 필수 (낙관적 잠금). DB 버전과 다르면 409.
- 응답: `{ data: IssueResponse }` (200). **422 ASSIGNEE_NOT_FOUND** — assigneeId가 실재하지 않는 사용자일 때.

**`IssueResponse` (issue-tracking IssueResponse.kt):**
- 기존 `reporterId: UUID` 노출. **신규 `assigneeId: UUID? = null`** (null=미할당) 노출 — Zod 스키마에 추가 대상.

### 프론트 현 상태

- `apps/web/src/api/issues.ts` — `issueResponseSchema`에 `assigneeId` 없음(추가 대상). `changeAssignee` 함수 없음.
- 사용자 목록 API 클라이언트 **없음** (신규 `apps/web/src/api/users.ts`).
- mutation 훅 원형: `useUpdateIssueSummary.ts` (onMutate 낙관적 / onError 롤백+409 toast / onSettled invalidate).
- 화면: `IssueMetaPanel.tsx`(현재 보고자 UUID raw 표시, 담당자 섹션 없음) / `routes/issues.$key.tsx`(핸들러 배선).
- i18n: `apps/web/src/i18n/ko.ts` `issueDetailStrings`.
- MSW: `apps/web/src/mocks/issue-handlers.ts` + `issue-fixtures.ts`.

## ⚠️ 회귀 방지 — 메모리 교훈 (반드시 준수)

1. **setQueryData 부분응답 플리커** (mutation-setquerydata-partial-response-flicker): assignee PATCH 응답(IssueResponse)은 단건 GET만 채우는 `descriptionHtml`이 null일 수 있다. mutation 훅은 **invalidate-only**로 동기화(onSettled invalidateQueries). onSuccess에서 `setQueryData(전체 응답)`로 덮지 말 것 — descriptionHtml/impactName 등이 null로 덮여 화면 깜빡임.
2. **MSW stateful 영속** (msw-mutation-stateful-refetch): PATCH /assignee 핸들러는 결과를 `issueOverrides`(기존 stateful 맵)에 영속해야 invalidate refetch 후 화면 롤백 안 됨. setQueryData 없이 invalidate만 쓰므로 더더욱 stateful 필수.
3. **Zod 스키마 강화 mock 파급** (zod-schema-strengthen-inline-mock-fanout): `issueResponseSchema`에 `assigneeId` 추가 시 산재한 인라인 IssueResponse mock/fixture가 깨질 수 있다. `assigneeId`를 **nullable**(`z.string().uuid().nullable()`)로 추가하면 기존 fixture(필드 없음→undefined)가 깨지지 않는지 확인. grep 전수검색 + `pnpm typecheck` 동반(vitest는 타입 미검증).
4. **텍스트 중복 strict mode** (playwright-getbyrole-exact-strict-mode / ui-pr-defer-e2e-regression-latent): 새 담당자 UI 요소가 기존 E2E 전역 셀렉터를 strict mode로 깨는지 D7에서 **기존 E2E 전체 함께 실행**. 같은 텍스트 버튼은 `data-testid`/컨테이너 한정.
5. **stale key prop** (react-usestate-stale-key-prop): 담당자 현재값은 `issue.assigneeId` props 파생. useState 초기화 금지(IssuePrioritySelect 패턴).

## Task 분해

### Task 1. API 계약 + Zod 스키마 (issues.ts + users.ts)

**메타.**
- agent: frontend-engineer
- files: apps/web/src/api/issues.ts, apps/web/src/api/issues.test.ts, apps/web/src/api/users.ts, apps/web/src/api/users.test.ts
- depends-on: []

**RED.** users.test.ts — `fetchUsers()`가 `GET /api/v1/users` 배열 응답을 `userSummarySchema[]`로 파싱(displayName/email nullable). `fetchUsers('alice')`가 query 쿼리스트링 전달. issues.test.ts — `issueResponseSchema`가 `assigneeId`(uuid nullable) 파싱, `changeAssignee(key, {assigneeId, expectedVersion})`가 PATCH /assignee 호출 + 응답 언래핑, 422/409 ApiError throw.

**GREEN.**
- `apps/web/src/api/users.ts` 신규 — `userSummarySchema = z.object({ id: uuid, username: min1, displayName: nullable, email: nullable })`, `export type UserSummary = z.infer<...>`, `fetchUsers(query?: string): Promise<UserSummary[]>` (배열 직접 파싱, `{data:}` 래퍼 없음).
- `issues.ts` — `issueResponseSchema`에 `assigneeId: z.string().uuid().nullable()` 추가. `changeAssignee(key, { assigneeId: string|null, expectedVersion: number })` 추가 (PATCH, `dataResponseSchema(issueResponseSchema)` 언래핑, 비-2xx → ApiError).

**REFACTOR.** JSDoc + 파일 헤더 한국어 주석. 매직 경로 상수화 불요(기존 패턴 인라인).

### Task 2. mutation 훅 + 사용자 조회 훅 (useChangeAssignee.ts + use-users.ts)

**메타.**
- agent: frontend-engineer
- files: apps/web/src/api/useChangeAssignee.ts, apps/web/src/api/useChangeAssignee.test.ts, apps/web/src/hooks/use-users.ts, apps/web/src/hooks/__tests__/use-users.test.tsx
- depends-on: [1]

**RED.** useChangeAssignee.test.ts — 성공 시 `invalidateQueries(['issue', key])` 호출(setQueryData로 전체 덮지 **않음**), 409 시 toast.error. use-users.test.tsx — `useUsers(query)`가 fetchUsers 호출 + 결과 반환, query 변경 시 재조회.

**GREEN.**
- `useChangeAssignee.ts` — useUpdateIssueSummary 패턴. 단 **onSuccess에서 setQueryData 금지, onSettled에서 invalidateQueries만**(교훈 1). mutationFn=changeAssignee. 409 → toast.error(한글 안내). 422 → toast.error("선택한 사용자를 찾을 수 없습니다" 류, ko.ts).
- `use-users.ts` — `useUsers(query: string)` useQuery, queryKey `['users', query]`, queryFn `fetchUsers(query)`. (debounce는 셀렉터 컴포넌트에서 처리 또는 enabled 조건.)

**REFACTOR.** issueQueryKey 재사용(useUpdateIssueSummary에서 export됨).

### Task 3. 담당자 셀렉터 UI + IssueMetaPanel 섹션 + i18n (IssueMetaPanel.tsx + ko.ts)

**메타.**
- agent: frontend-engineer
- files: apps/web/src/components/issue/IssueMetaPanel.tsx, apps/web/src/components/issue/IssueMetaPanel.test.tsx, apps/web/src/i18n/ko.ts
- depends-on: [1, 2]

**RED.** IssueMetaPanel.test.tsx — (a) 담당자 미할당 시 "미지정" 표시, (b) 검색 input에 입력 시 onAssigneeSearch 콜백/사용자 목록 노출, (c) 사용자 선택 시 onAssigneeChange(userId) 호출, (d) 해제 버튼 클릭 시 onAssigneeChange(null) 호출, (e) 현재 담당자 displayName 표시.

**GREEN.**
- `ko.ts` — `issueDetailStrings`에 assignee 문자열 추가: assigneeLabel('담당자'), assigneeUnassigned('미지정'), assigneeSearchPlaceholder, assigneeUnassignButton, assigneeNotFoundError 등.
- `IssueMetaPanel.tsx` — 보고자 섹션 위(또는 적절 위치)에 담당자 섹션 추가. 신규 서브컴포넌트 `IssueAssigneeSelect`(같은 파일, 기존 IssuePrioritySelect/IssueLabelsEdit 패턴):
  - props: `value: string | null`(issue.assigneeId 파생, useState 초기화 금지 — 교훈 5), `users: UserSummary[]`, `onSearch: (q: string) => void`, `onAssigneeChange: (userId: string | null) => void`
  - 현재 담당자: users에서 id 매핑해 displayName(없으면 username) 표시, null이면 "미지정"
  - 검색 input(native) + 결과 목록(버튼 리스트) → 선택 시 onAssigneeChange(id)
  - "담당자 해제" 버튼 → onAssigneeChange(null). 미할당이면 비표시 또는 disabled
  - WCAG AA: min-h-[44px], aria-label. data-testid="assignee-section"
- `IssueMetaPanelProps`에 위 props 추가. **보고자(reporter) 섹션은 건드리지 않음**(surgical — 범위 밖).

**REFACTOR.** 컴포넌트 200줄/함수 30줄 제약(§2.2) 확인. 검색 결과 목록은 작은 서브컴포넌트로 분리 가능.

### Task 4. 라우트 배선 + MSW + fixtures (issues.$key.tsx + mocks)

**메타.**
- agent: frontend-engineer
- files: apps/web/src/routes/issues.$key.tsx, apps/web/src/routes/issues.$key.test.tsx, apps/web/src/mocks/issue-handlers.ts, apps/web/src/mocks/issue-fixtures.ts, apps/web/src/mocks/user-handlers.ts, apps/web/src/mocks/user-fixtures.ts
- depends-on: [3]

**RED.** issues.$key.test.tsx — 담당자 변경 시 PATCH /assignee 호출 후 화면 갱신(refetch), 해제 동작. (MSW stateful override 검증 — setQueryData 위 가짜그린 방지, 교훈 2.)

**GREEN.**
- `issues.$key.tsx` — useChangeAssignee + useUsers 배선. 검색어 로컬 state, onSearch → setState, IssueMetaPanel에 users/onSearch/onAssigneeChange props 전달. onAssigneeChange가 changeAssignee mutate({key, assigneeId, expectedVersion: issue.version}).
- `mocks/user-fixtures.ts` 신규 — UserSummary[] 목업(displayName 있는/없는 케이스 포함).
- `mocks/user-handlers.ts` 신규 — `GET /api/v1/users` query 필터링 핸들러(배열 직접 응답).
- `mocks/issue-handlers.ts` — `PATCH /api/v1/issues/:key/assignee` 핸들러: expectedVersion 불일치 409, 미존재 userId 422, 성공 시 **issueOverrides에 assigneeId+version+1 영속**(stateful, 교훈 2) 후 `{data: issue}` 반환.
- `mocks/issue-fixtures.ts` — fixtures에 `assigneeId: null`(또는 일부 채움) 추가. (Zod nullable이라 미추가여도 통과하나 명시 권장 — 교훈 3 grep 확인.)
- handlers.ts에 user-handlers 등록.

**REFACTOR.** 핸들러 분기 순서 백엔드와 일치(409→422→성공). e2e-msw-serviceworker 교훈: MSW 핸들러 추가가 정석.

### Task 5 (D7). Playwright E2E (qa-engineer)

**메타.**
- agent: qa-engineer
- files: apps/web/tests/e2e/issue-assignee.spec.ts (경로는 기존 E2E 위치 확인 후)
- depends-on: [4]

시나리오: (S1) 담당자 검색→선택→메타패널에 이름 표시, (S2) 담당자 해제→"미지정", (S3) [선택] 422/409 toast. **구현 코드 수정 금지**(테스트만). **기존 E2E 전체 함께 실행**(교훈 4 — strict mode 회귀 확인).

## Wave 계산

- wave 1 = [Task 1] (독립)
- wave 2 = [Task 2] (depends-on 1)
- wave 3 = [Task 3] (depends-on 1,2)
- wave 4 = [Task 4] (depends-on 3)
- wave 5 = [Task 5/D7] (depends-on 4, qa-engineer)

→ 파일 의존이 선형(같은 화면 파일 연쇄)이라 사실상 직렬. frontend-engineer 1명이 Task 1~4를 순차 TDD, qa-engineer가 Task 5. (병렬 이점 없음 — bts-plan-wave-gradle-module-compile 교훈: 같은 모듈/파일은 직렬화.)

## Context Notes (작업 중 결정 누적)

- 2026-06-01 시작. 백엔드 계약 grep 검증 완료(위 표). 담당자 셀렉터는 native 요소(cmdk 미도입, §1.17).
- mutation은 invalidate-only 확정(descriptionHtml 플리커 회피).
