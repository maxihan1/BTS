# FR-IS-01 상태전이 PR 2/2 — 전이 UI(프론트) + Playwright E2E

> slug: fr-is-01-transition-ui
> type: ui
> agent: frontend-engineer (E2E task는 qa-engineer)
> 생성: 2026-05-30

## Brief

PR #38(백엔드 가용전이, 머지됨)의 프론트 짝. 이슈 상세 화면이 현재 상태를 읽기전용 배지
(`IssueMetaPanel.tsx`, `data-testid="issue-state-badge"`)로만 보여주는데, 여기에 **상태 변경 컨트롤**
(shadcn select/dropdown)을 추가한다.

- `GET /api/v1/issues/{key}/transitions` 로 가용전이(서버 권위, validator/조건/권한 평가됨)를 받아 노출.
- `POST /api/v1/issues/{key}/transition` (body `{toStatusKey, expectedVersion}`) 로 전이 실행.
- 백엔드 계약: `GET .../transitions` → `{data:{transitions:[{fromStateKey,toStateKey,name,key}]}}`,
  key=`${fromStateKey}__${toStateKey}`. 에러 404(전이 없음)/422(워크플로우 미설정).
  전이 실행 에러: 409(expectedVersion 불일치 = 낙관적 잠금 충돌).

산출물: api 클라이언트 transition 함수(`src/api/issues.ts` 신규 — 현재 transition 함수 없음) +
Zod 스키마(backend DTO와 grep 정합) + MSW stateful 핸들러(`src/mocks/issue-handlers.ts`) +
i18n(409/422 에러 메시지) + Playwright E2E(happy 전이 / 가용전이 필터 / 에러).

분류: classify가 qa로 판정했으나(제목 "E2E" 키워드) 본질은 전이 UI 신규 구현 → Maxi 결정으로 ui 재분류.
D6(PR #39, 이슈 유형 셀렉터)는 별개 기능이며 이미 머지됨 — 같은 화면 파일을 건드리던 충돌은 D6 선머지로 해소.

## 도메인 정리

BC = **issue-tracking**. project-workflow는 `WorkflowKeyResolver` SPI로만 소비(직접 import 금지) — cross-BC wiring은 PR #27/#28/#38에서 완료·확정. 이번 PR은 **프론트만** 건드리므로 BC 격리 위반 없음.

유비쿼터스 언어 — 전부 기존 용어, 신규 0건.
- **가용전이(available transition)** — 현재 상태에서 실행 가능한(서버가 validator/조건/권한 평가) 전이.
- **상태 키(state key)** — 소문자(`open`/`in_progress`/`closed`). 백엔드 resolved 소문자 그대로 사용(대문자 잔재 없음, PR #28 정렬).
- **전이 동일성 = (fromStateKey, toStateKey)** — ADR 2026-05-28-workflow-transition-identity-policy. `name`은 표시용, 식별 사용 금지.

신규 ADR 없음. 도메인 결정은 PR #38 spec에서 종결(옵션 A 풀버전 = 서버 권위 가용전이).

## 스펙 (기존 docs/specs/2026-05-29-fr-is-01-transition.md 활용)

전체 FR-IS-01 스펙은 기존 문서에 충실히 존재. **이번 PR 2/2 범위 = 프론트 + 프론트 E2E**.
- 포함: **FR-T-F1~F5**(전이 컨트롤 UI / 전이 실행+캐시무효화 / api+Zod / MSW stateful / 에러 i18n) + **FR-T-Q1**(Playwright E2E happy·필터·에러).
- 제외(이미 PR #38 머지): FR-T-S1/P1(SPI), FR-T-B1/B2(엔드포인트), FR-T-Q2/Q3(백엔드 통합테스트).

**확정 백엔드 계약 (코드 grep 검증 완료).**
```
GET  /api/v1/issues/{key}/transitions
  200 { data: { transitions: [{ fromStateKey, toStateKey, name, key }] } }   // key=`${from}__${to}`
  404 이슈 없음/삭제   422 워크플로우 미설정
POST /api/v1/issues/{key}/transition
  body { toStatusKey: string, expectedVersion: number }
  200 { data: IssueResponse(... currentStateKey, version ...) }
  404 / 409(전이거부 transition_not_allowed · 낙관락 버전충돌) / 422
```
**재사용 자원.**
- 프론트 `workflowTransitionViewSchema`(`apps/web/src/api/workflows.ts:24`)가 `{fromStateKey,toStateKey,name,key}` 동일 형태 — 전이 Zod 스키마 정합 기준(메모 `frontend-zod-backend-dto-contract-gap` 충족).
- 이슈 상세는 D6 타입변경 mutation에서 이미 `expectedVersion=issue.version` + `ApiError status===409` + TanStack Query 캐시무효화 패턴 보유(`issues.$key.tsx`) — 전이도 동일 패턴 차용.
- `IssueResponse.currentStateKey`/`version` 존재 → 별도 상태 조회 불필요.

UX 시나리오는 기존 spec S1~S6 그대로(happy / 가용전이만 노출 / 409 거부 / 409 버전충돌 / 422 미설정 / 종료상태 전이0건). NFR: WCAG AA(44px·키보드), 전이 중 disabled(중복클릭 방지), 폴링 금지.

## Plan

> TDD red→green→refactor 강제. 각 task RED(실패 테스트 먼저 커밋)→GREEN→REFACTOR.
> 검증은 wave 종료마다 controller가 `pnpm verify`(lint+typecheck+test+build) 전체 직접 실행(scoped typecheck 맹점 회피, 메모 lint-staged race 학습). E2E는 마지막.

### Task 1. api 클라이언트 + Zod 스키마 (전이 조회/실행)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`]
- depends-on: []

**RED** (`issues.test.ts`).
- `fetchIssueTransitions(key)` — `GET /api/v1/issues/{key}/transitions` 응답 `{data:{transitions:[...]}}`을 Zod로 파싱해 `{fromStateKey,toStateKey,name,key}[]` 반환. 잘못된 형태면 Zod throw.
- `transitionIssue(key, {toStatusKey, expectedVersion})` — `POST /api/v1/issues/{key}/transition` 호출, 200 시 `IssueResponse` 파싱 반환. 409/422 시 `ApiError`(status 보존) throw.
- 실패 메시지(예상): `fetchIssueTransitions`/`transitionIssue` 미존재.

**GREEN** (`issues.ts`).
- `issueTransitionSchema` 신규 — `workflowTransitionViewSchema`(`api/workflows.ts:24`)와 동일 형태 `{fromStateKey,toStateKey,name,key}`. **backend `TransitionItem` DTO와 grep 정합 유지**(메모 `frontend-zod-backend-dto-contract-gap`).
- `transitionsResponseSchema` = `z.object({ data: z.object({ transitions: z.array(issueTransitionSchema) }) })`.
- `fetchIssueTransitions`, `transitionIssue` (기존 `api/client.ts` + `ApiError` 패턴 차용).

**REFACTOR**. 스키마/엔드포인트 상수 정리 + KDoc(한국어 헤더).

**검증**. `pnpm test -- src/api/issues.test.ts` + `pnpm typecheck`.

### Task 2. MSW stateful 핸들러 (전이 조회 + 전이 실행)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/issue-fixtures.ts`, `apps/web/src/mocks/__tests__/issue-handlers.test.ts`]
- depends-on: []   # 다른 파일군. 고정 백엔드 계약 기준이라 T1과 병렬 가능

**RED** (`issue-handlers.test.ts`).
- `GET /api/v1/issues/:key/transitions` — 현재 상태(`createdIssueFixture.currentStateKey`) 기준 가용전이 반환. `open` → `[{open→in_progress "시작"},{open→closed "취소"}]`, `in_progress` → `[{in_progress→in_review},{in_progress→closed}]`, `closed` → `[]`(종료, S6).
- `POST /api/v1/issues/:key/transition` — `{toStatusKey, expectedVersion}` 수신. **분기순서 백엔드 일치(메모 `e2e-msw-serviceworker-block`)**: 404(미존재 key) → 422(`MOCK_NO_WORKFLOW_TRIGGER` 등 미설정) → 409(`expectedVersion`≠현재 version = 버전충돌 / `MOCK_CONFLICT_TRIGGER` = 전이거부) → 200(state 갱신 + version+1). 전이 후 후속 `GET /:key` 및 `/transitions`가 새 상태/새 가용전이 반영(stateful).

**GREEN** (`issue-handlers.ts` + `issue-fixtures.ts`).
- `issue-fixtures.ts`에 상태별 가용전이 맵(`software-default` 기준) 추가.
- `getTransitionsHandler`, `transitionHandler` 추가 + `issueHandlers` 배열 등록. `resetIssueState()`가 전이 상태도 초기화.

**REFACTOR**. 가용전이 맵을 fixture helper로(상태→전이 배열), `updateIssueHandler` 409 패턴과 분기 일관.

**검증**. `pnpm test -- src/mocks/__tests__/issue-handlers.test.ts`.

### Task 3. use-issue-transitions 훅 (가용전이 useQuery + 전이 useMutation)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-issue-transitions.ts`, `apps/web/src/hooks/__tests__/use-issue-transitions.test.tsx`]
- depends-on: [1]   # T1 api 함수 호출

**RED** (`use-issue-transitions.test.tsx`, MSW 위).
- `useIssueTransitions(key)` — `fetchIssueTransitions`로 가용전이 useQuery(`['issue-transitions', key]`).
- `useTransitionIssue(key)` — `transitionIssue` useMutation. onSuccess 시 `['issue', key]` + `['issue-transitions', key]` 캐시 무효화(상태 배지·가용전이 갱신).

**GREEN** (`use-issue-transitions.ts`). `use-issue-types.ts`(D6 선례) 패턴 차용. `useQueryClient().invalidateQueries`.

**REFACTOR**. queryKey 상수화 + KDoc.

**검증**. `pnpm test -- src/hooks/__tests__/use-issue-transitions.test.tsx`.

### Task 4. 전이 컨트롤 UI (IssueMetaPanel) + i18n 에러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/issue/IssueMetaPanel.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [3]   # T3 훅 사용

**RED** (`IssueMetaPanel.test.tsx`).
- 상태 배지(`issue-state-badge`) 영역에 전이 컨트롤(`IssueStateTransition` 서브컴포넌트, `IssueTypeSelect` 패턴) 렌더.
- 가용전이를 훅에서 받아 노출(드롭다운/버튼). 선택 시 `useTransitionIssue.mutate({toStatusKey: 선택 toStateKey, expectedVersion: issue.version})`.
- 전이 중 컨트롤 `disabled`(중복클릭 방지, NFR3). 가용전이 0건(종료상태 S6) → 컨트롤 비노출/비활성.
- 409(전이거부/버전충돌)·422(미설정) → i18n 에러 메시지 표시, 상태 변경 전 유지(S3/S4/S5).
- WCAG AA: `min-h-[44px]`, `aria-label`, 키보드 접근(NFR1). props 식별값으로 로컬 state 초기화 시 `key` prop 재마운트(메모 `react-usestate-stale-key-prop`).

**GREEN**. `IssueStateTransition` 서브컴포넌트 + `ko.ts`에 전이 라벨/409/422 문자열 추가.

**REFACTOR**. 컨트롤 접근성/스타일 DESIGN.md 컨벤션 정렬.

**검증**. `pnpm test -- src/components/issue/IssueMetaPanel.test.tsx` + `pnpm typecheck`(교차파일).

### Task 5. Playwright E2E (전이 시나리오)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-transition.spec.ts`]
- depends-on: [2, 4]   # MSW 핸들러(T2) + UI(T4) 필요

**RED→GREEN** (E2E는 spec 작성 = RED, UI/MSW 존재로 PASS = GREEN).
- **S1 happy**: 이슈 상세 진입(`open`) → 전이 컨트롤에서 "시작(→in_progress)" 선택 → 상태 배지 `in_progress` 갱신 확인.
- **S2 가용전이 필터**: `open`에서 컨트롤 열면 open 출발 전이만(시작/취소), `in_review` 직행 선택지 없음 확인.
- **에러**: S5(422 미설정) 또는 S3/S4(409) 중 MSW mock 가능 범위 1건 → 에러 메시지 + 상태 미변경 확인.
- **주의**: `serviceWorkers:'block'` 금지(MSW 부팅 깨짐, 메모 `e2e-msw-serviceworker-block`). 같은 텍스트 버튼 충돌 시 `exact:true`/좁은 컨테이너(메모 `playwright-getbyrole-exact-strict-mode`).

**구현 코드 수정 금지**(qa-engineer는 E2E·테스트만).

**검증**. `pnpm test:e2e -- e2e/issue-transition.spec.ts`.

## Plan 메타

- task 수: 5 (frontend-engineer 4 + qa-engineer 1)
- wave 예상: wave1=[T1,T2] → wave2=[T3] → wave3=[T4] → wave4=[T5]. 약 4 wave(T3→T4 체인 + E2E 막판).
- 예상 시간: 약 15~20분(병렬 wave 기준).
- TDD 강제: yes (RED→GREEN→REFACTOR, E2E는 spec=RED/PASS=GREEN).
- 추가 검증: wave 종료마다 controller `pnpm verify`(lint+typecheck+test+build) 직접 실행 + 최종 E2E.
- BC 격리: issue-tracking 프론트만. 백엔드 무변경.

## 리뷰 결과 (← /bts-review-plan 채움)
