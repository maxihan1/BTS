# FR-IS-02 D7 — 이슈 타입 변경 Playwright E2E

> slug: fr-is-02-d7-type-change-e2e
> type: qa (fast-track — domain/spec/review-plan 스킵)
> agent: qa-engineer
> 생성: 2026-05-30

## Brief

FR-IS-02 D6(PR #39, 머지됨)에서 만든 "이슈 상세 화면 타입 표시 + 셀렉터로 변경" 기능에 대한 Playwright E2E. MSW mock 환경(`pnpm dev` + 기존 issue-handlers PATCH typeId + issue-type 카탈로그 핸들러). 신규 production/MSW 코드 없음 — E2E spec 파일만 추가.

## 기존 사실 확인 (worktree grep)

- 본보기 패턴 — `apps/web/e2e/issue-transition.spec.ts` (FR-IS-01 전환 E2E, PR #41). `loginAsAlice` → `page.goto('/issues/ATLAS-1')` → `getByRole('combobox', { name })` → `selectOption` → `getByTestId` 단언.
- 타입 셀렉터 — `IssueTypeSelect`(IssueMetaPanel 내부) = native `<select>`, aria-label = `issueDetailStrings.typeSelectLabel`("유형 선택").
- 타입명 표시 — `data-testid="issue-type-name"`.
- i18n — `apps/web/e2e/fixtures/issue-fixtures.ts` 의 `i18nLabels.issueDetail = issueDetailStrings` (typeSelectLabel/typeLabel/typeChangeConflictError 노출). E2E 는 hardcoded string 금지, 정본 키 참조.
- MSW — issue PATCH typeId 처리 + issue-type 카탈로그(GET) 핸들러 이미 존재(D6 머지분). 추가 핸들러 불필요.
- fixtures — ATLAS-1/ATLAS-2(story)/ATLAS-3(task) 정적 이슈 + 표준 5종 타입 카탈로그(epic/story/task/subtask/bug). ATLAS-1 현재 typeId 는 qa-engineer 가 issue-fixtures.ts 에서 확인 후 시나리오 작성.

## learnings 회귀 가드

- `e2e-msw-serviceworker-block` — E2E 는 MSW 의존, 새 핸들러 불필요(기존 재사용). 분기 순서 backend 일치 이미 검증됨.
- `playwright-getbyrole-exact-strict-mode` — 같은 텍스트 셀렉터 충돌 회피. `getByRole('combobox', { name })` 로 유형/전환 셀렉터 구분(전환 셀렉터도 같은 패널에 있으므로 name 으로 정확 구분 필수). 텍스트 단언은 exact:true.

## Plan (E2E 시나리오)

### Task 1. 이슈 타입 변경 E2E spec 작성

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-type-change.spec.ts`]
- depends-on: []

**시나리오** (issue-transition.spec.ts 패턴 미러, MSW mock 환경):
- **S1 happy** — ATLAS-1 상세 진입 → `issue-type-name` 현재 타입 확인 → 유형 셀렉터에서 다른 타입(예: Story) 선택 → `issue-type-name` 이 선택한 타입명으로 갱신(MSW stateful + 쿼리 무효화 재조회).
- **S2 셀렉터 옵션** — 유형 셀렉터에 활성 표준 5종(epic/story/task/subtask/bug) 옵션 노출. 전환 셀렉터와 혼동 없이 `name: typeSelectLabel` 로 정확 타깃.
- **S3 아이콘 표시** — 유형 행에 IssueTypeIcon(svg/aria-label=typeName) 노출 확인. 타입 변경 후 아이콘도 갱신(선택 — happy 에 통합 가능).

(409 충돌 경로는 E2E 에서 stale version 재현이 번거롭고, route 컴포넌트 테스트 T7-14 에서 이미 검증됨 → E2E 범위 제외.)

**검증**: `cd .worktrees/fr-is-02-d7-type-change-e2e && pnpm --filter web test:e2e -- issue-type-change.spec.ts` (또는 전체 E2E). 셀렉터는 i18nLabels 정본 참조, getByRole name 으로 전환/유형 셀렉터 구분.

## Plan 메타

- task 수: 1 (E2E spec 단일 파일)
- 구현 코드 수정 금지 (qa-engineer 책임 — 테스트만). production/MSW 변경 0 예상.
- 추가 검증: 전체 E2E green 회귀 없음 + typecheck/lint.

## 범위 제외

- 409 충돌 E2E (component test T7-14 커버).
- 커스텀 타입 변경(표준 5종 중심), 권한 모델(현재 AlwaysAllow).
