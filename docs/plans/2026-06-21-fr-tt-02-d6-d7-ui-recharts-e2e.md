# FR-TT-02 D6/D7 — 워크로그 집계 프론트 UI + E2E

> slug: fr-tt-02-d6-d7-ui-recharts-e2e
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-21

## Brief

사용자 원문: "fr-tt-02 d6, d7 진행하자" + "다른 섹션 병행 작업 중이니 워크트리는 새로 만들어서 진행"

FR-TT-02 — 이슈/사용자/기간별 시간 집계 (agile-planning §5.2, BC=issue-tracking).
백엔드 D1~D5 완료(#167): `GET /api/v1/worklogs/aggregate?project=&by=&granularity=&from=&to=`.
이번 작업 = D6(프론트 UI 표 + recharts 차트) + D7(E2E).

classify: 원판정 type=qa(E2E 키워드 오판) → type=ui / agent=frontend-engineer 교정.

### Maxi 확정 결정 (2종, spec 선행)
1. **차트 = recharts 설치** (product 기획대로). 절대규칙 #17 — 버전 고정 필수.
2. **배치 = 프로젝트별 보고 페이지** `/projects/$projectKey/reports/worklog`.

### 백엔드 응답 계약 (실측, Zod 1:1 미러 대상)
`{ data: { by, granularity?, from?, to?, buckets:[{key,label,timeSpentSeconds,worklogCount}], totalTimeSpentSeconds } }`
- `@JsonInclude(NON_NULL)`: granularity(by≠period 시 키 제거), from/to(미전달 시 키 제거)
- 주의: spec 예시의 `project` 필드는 실제 DTO에 없음 (drift — Zod는 실 DTO 기준)

## 도메인 정리

- **BC**: issue-tracking (백엔드 ADR `2026-06-20-worklog-aggregate-model.md` D1 계승)
- **신규 도메인 용어**: 없음 — 프론트는 백엔드 집계 계약(`/api/v1/worklogs/aggregate`)을 **소비만** 한다. 새 도메인 개념 0.
- **신규 ADR**: 없음 — 백엔드 ADR가 모델/권한(BROWSE+Project scope)/차원(issue·user·period)/granularity/period UTC 버킷을 모두 확정. ADR §미해결 5항목도 spec(`2026-06-20-fr-tt-02-worklog-aggregate.md`)에서 전부 채워짐.
- **기존 결정 충돌**: 없음 (백엔드 ADR/spec 계승).
- **프론트 고유 결정** (UI 스펙 — spec 단계에서 확정):
  - period sparse→dense 채움 = **프론트 D6 책임** (spec E1 명시). 백엔드는 데이터 있는 버킷만 반환.
  - recharts 차트 종류 (by=issue/user 막대, by=period 시계열).
  - 시간 포맷 (초 → "2h 30m" 등) — FR-TT-01 WorklogSection 선례 재사용 후보.
  - 403 권한 게이팅 UI, worklog 0건 빈 상태.
- **glossary 메모**: glossary.md에 worklog/집계 용어 미등록(백엔드 FR-TT-01/02 시 누락). 수동 영역이라 본 PR 범위 밖 — Maxi 영역으로 남김.

## 스펙

전체 스펙. [docs/specs/2026-06-21-fr-tt-02-d6-d7-worklog-aggregate-ui.md](../specs/2026-06-21-fr-tt-02-d6-d7-worklog-aggregate-ui.md)

핵심 요약.
- 라우트 `/projects/$projectKey/reports/worklog` 신설(RouteAdapter + props Page). 차원 셀렉터(issue/user/period) + period 시 granularity + from/to 네이티브 date.
- API 클라이언트 `worklog-aggregate.ts` 신규(이슈 단위 `worklogs.ts`와 분리). Zod는 실 DTO 1:1 — granularity/from/to **optional**(@JsonInclude NON_NULL), `project` 필드 미포함(drift 차단).
- recharts `BarChart` + 표(formatSeconds 재사용). 빈 상태/403 권한 안내/로딩 처리.
- period dense 채움은 범위 외(sparse 시간순 표시 + 명시). 차트 막대 과다 시 상위 N + 표 전체.

## Brainstorming Check

✅ self-review 1-pass 통과 (정의된 FR + Maxi 결정 2종으로 핵심 갈림길 사전 확정).
보강 항목. Zod optional/nullable 구분 · project drift 차단 · displayName 빈 문자열 placeholder · 차원전환 필터 보존 · router.ts 병행 worktree 충돌 명시 · recharts 버전 고정 · 403 probe 방지.

## Plan

레이어별 분해. API → 훅/i18n → 차트/표 → 리포트 조립 → 라우트/MSW → E2E.
파일 경로는 repo 루트 기준. 모든 신규 파일 L1 한국어 헤더 주석 필수(글로벌 CLAUDE.md §6).

### Task 0 (controller 선행 — wave dispatch 전 단독 실행)

**recharts 설치 + lockfile 커밋.** TDD 사이클 없음(의존성 추가).
- `cd apps/web && pnpm add recharts@3.8.1` (정확 버전, 절대규칙 #17 — `^`/`~` 금지. package.json에 `"recharts": "3.8.1"` 확인).
- **node_modules race 회피**: wave 병렬 dispatch가 시작되기 전에 controller가 단독 실행 + 커밋. 이후 모든 task가 설치된 recharts 사용(메모리 worktree-node-modules-partial-install).
- 검증: `pnpm --filter ... typecheck` 통과 + `grep '"recharts": "3.8.1"' apps/web/package.json`.

### Task 1. API 클라이언트 + Zod 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/worklog-aggregate.ts`, `apps/web/src/api/worklog-aggregate.test.ts`]
- depends-on: []

**RED** (`worklog-aggregate.test.ts`): MSW/모킹 없이 fetch mock 또는 기존 apiFetch mock 패턴(worklogs.test.ts 참고).
- `fetchWorklogAggregate('BTS', { by:'issue' })` → 200 응답 파싱: buckets/totalTimeSpentSeconds/by
- granularity/from/to **부재** 응답도 파싱 성공(@JsonInclude NON_NULL → Zod `.optional()`, not `.nullable()`)
- `project` 필드는 스키마에 **없음**(실 DTO 기준, invent 금지) — 응답에 와도 무시(passthrough 아님, strip)
- by=period + granularity='month' 응답(granularity 존재) 파싱
- 403/400 → `ApiError(status)` throw
- 실패 메시지(예상): `worklog-aggregate` 모듈/함수 없음

**GREEN** (`worklog-aggregate.ts`):
- Zod: `worklogAggregateBucketSchema { key, label, timeSpentSeconds:number, worklogCount:number }`, `worklogAggregateResponseSchema { by, granularity:optional, from:optional, to:optional, buckets:array, totalTimeSpentSeconds:number }`
- 타입 export(`WorklogAggregateResponse`, `WorklogAggregateBucket`, `AggregateDimension='issue'|'user'|'period'`, `AggregateGranularity='day'|'week'|'month'`)
- `fetchWorklogAggregate(projectKey, params)`: querystring 조립(undefined 파라미터 제외), `apiFetch` GET, `dataResponseSchema(...).parse`. worklogs.ts의 dataResponseSchema 헬퍼 패턴 재사용(로컬 정의).

**REFACTOR**: querystring 빌더 헬퍼 + KDoc.

**검증**: `cd apps/web && pnpm test worklog-aggregate` + `pnpm typecheck`

### Task 2. TanStack Query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-worklog-aggregate.ts`, `apps/web/src/hooks/use-worklog-aggregate.test.tsx`]
- depends-on: [1]

**RED**: queryKey가 모든 파라미터(projectKey, by, granularity, from, to) 포함 → 파라미터 변경 시 재조회. enabled(projectKey 존재 시). 기존 use-versions/use-components 훅 테스트 패턴 참고.

**GREEN**: `useWorklogAggregate(projectKey, params)` = `useQuery({ queryKey:['worklog-aggregate', projectKey, params], queryFn: () => fetchWorklogAggregate(...) })`.

**REFACTOR**: queryKey 헬퍼 + KDoc.

**검증**: `pnpm test use-worklog-aggregate`

### Task 3. i18n 라벨

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/worklog-aggregate-labels.ts`, `apps/web/src/i18n/worklog-aggregate-labels.test.ts`]
- depends-on: []

**RED** (`*-labels.test.ts`): 라벨 객체 키 존재 + **콜론(`:`) 종결 0**(version-labels.test.ts 패턴 복사). 페이지 제목/설명, 차원 옵션명(이슈별/사용자별/기간별), granularity 옵션(일/주/월), from/to/적용, 빈 상태, 권한 안내, 차트/표 헤더, "(알 수 없음)" placeholder, "상위 N개 표시" 안내.

**GREEN**: `worklogAggregateLabels` 객체. ko.test.ts 전역 콜론 검증도 통과.

**REFACTOR**: 그룹화(page/filter/chart/table/empty/error) + KDoc.

**검증**: `pnpm test worklog-aggregate-labels && pnpm test i18n/ko`

### Task 4. 차트 컴포넌트 (recharts BarChart)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/worklog/WorklogAggregateChart.tsx`, `apps/web/src/components/worklog/WorklogAggregateChart.test.tsx`]
- depends-on: [1]

**RED**: **★recharts jsdom 한계** — `ResponsiveContainer`는 jsdom에서 width/height 0이라 차트 미렌더(메모리 mermaid getBBox 선례). 단위 테스트는 (a) **데이터→차트 데이터 변환 로직**(상위 N 절단·label/value 매핑·빈 배열 처리)을 검증, (b) recharts 자체 렌더는 mock(`vi.mock('recharts', ...)` 또는 ResponsiveContainer를 고정 크기 div로 대체) 또는 컴포넌트가 빈 배열·정상 배열에서 throw 없이 마운트되는지만. 실 시각 렌더는 D7 E2E 위임(KDoc 명시 — silent skip 아님).
- 상위 N(기본 20) 초과 시 차트는 N개만, label에 "상위 N개" 안내 노출 props/콜백.
- 빈 buckets → 차트 영역에 빈 상태(또는 null 반환, 부모가 빈상태 처리).

**GREEN**: `WorklogAggregateChart({ buckets, dimension })`. recharts `<ResponsiveContainer><BarChart data={chartData}>`. **★C1 차트 방향**: by=issue/user는 **가로 막대**(`layout="vertical"`, YAxis type=category=label, XAxis=시간) — 긴 issueKey/displayName 가독성. by=period는 **세로 막대**(XAxis=시간축 label, YAxis=시간). 시간 축 tick formatter=초→시간(h), Tooltip=formatSeconds. chartData = buckets 상위 N개 매핑. by=period면 시간순 유지(백엔드 ASC), 그 외 백엔드 DESC 유지. aria-label 부여.

**REFACTOR**: chartData 변환 순수 함수 분리(테스트 용이) + 상위 N 상수.

**검증**: `pnpm test WorklogAggregateChart && pnpm typecheck`

### Task 5. 표 컴포넌트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/worklog/WorklogAggregateTable.tsx`, `apps/web/src/components/worklog/WorklogAggregateTable.test.tsx`]
- depends-on: [1]

**RED**: buckets를 행으로(label / 소요시간=`formatSeconds` / worklogCount). label 빈 문자열(by=user displayName 누락)→"(알 수 없음)" placeholder(key=UUID 비노출, spec E3). 합계 행(또는 footer)에 totalTimeSpentSeconds=formatSeconds. 빈 배열→빈 상태 행.

**GREEN**: `WorklogAggregateTable({ buckets, total, dimension })`. `@/lib/duration` formatSeconds 재사용(신규 포맷 금지). 시맨틱 `<table>` + i18n 헤더.

**REFACTOR**: 행 컴포넌트 분리 + KDoc.

**검증**: `pnpm test WorklogAggregateTable`

### Task 6. 리포트 메인 컴포넌트 (필터 + 조립 + 상태)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/worklog/WorklogAggregateReport.tsx`, `apps/web/src/components/worklog/WorklogAggregateReport.test.tsx`]
- depends-on: [2, 3, 4, 5]

**RED** (MSW로 useWorklogAggregate 응답 모킹 — 컴포넌트 통합):
- 차원 셀렉터(issue/user/period) 전환 → 재조회(by 변경). 기본 by=issue.
- by=period 선택 시 granularity 셀렉터 노출, 그 외 숨김. **차원 전환해도 granularity/from/to 상태 보존**(spec E5).
- from/to 네이티브 `input[type=date]`(입력 변경 시 즉시 반영, 별도 적용 버튼 없음 — N1). **from>to면 적용 차단 + 안내**(클라이언트 방어, spec S6).
- 정상 → 요약(total) + 차트 + 표 렌더. buckets=[] → 빈 상태(spec S4). 로딩 → 로딩 표시.
- **403 → 권한 안내**(spec S5) — ApiError(403) 분기 → **기존 `ProjectNotFoundScreen` 재사용**(members 라우트 named export, probe 방지 문구 적합 — C2). 신규 안내 컴포넌트 만들지 않음.

**GREEN**: 필터 state(useState: by, granularity, from, to) → `useWorklogAggregate(projectKey, params)`. isPending/isError(403 분기)/빈배열/정상 4-상태. 차트+표 조립. label은 select 외부 설명(기존 패턴). i18n 라벨 사용.

**REFACTOR**: 필터 바 하위 컴포넌트 분리 + KDoc.

**검증**: `pnpm test WorklogAggregateReport`

### Task 7. 라우트 페이지 + router 등록 + MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.reports.worklog.tsx`, `apps/web/src/routes/projects.$projectKey.reports.worklog.test.tsx`, `apps/web/src/router.ts`, `apps/web/src/mocks/worklog-aggregate-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [6]

**RED** (`*.reports.worklog.test.tsx`): RouteAdapter가 useParams로 projectKey 추출 → Page에 전달(라우터 비의존 단위 — 기존 versions 페이지 패턴). Page가 WorklogAggregateReport 렌더. MSW 핸들러가 `GET /api/v1/worklogs/aggregate` 응답(by별 fixture).

**GREEN**:
- 페이지: `ProjectWorklogReportRouteAdapter`(useParams) + `ProjectWorklogReportPage({projectKey})`(props). 헤더(p-8 space-y-6) + WorklogAggregateReport.
- `router.ts`: `projects/$projectKey/reports/worklog` 라우트 등록. **★병행 worktree(fr-bd-01) 충돌 주의** — router.ts 동시 수정. 자기 라우트 블록만 추가, 머지 시점 rebase로 양쪽 보존(메모리 parallel-fr-overlapping-frontend-infra-collision). adapter import만 추가.
- MSW: `worklog-aggregate-handlers.ts`(by=issue/user/period + 403 시나리오, 영속 store 패턴). `handlers.ts`에 import + spread 한 줄.

**REFACTOR**: 핸들러 fixture 분리 + KDoc.

**검증**: `pnpm test reports.worklog && pnpm typecheck && pnpm build`

### Task 8. E2E (D7)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/worklog-aggregate.spec.ts`]
- depends-on: [7]

**RED→GREEN** (Playwright happy path — 구현 코드 수정 금지, E2E만):
- 로그인 fixture → `/projects/<key>/reports/worklog` 진입 → 표에 버킷 데이터 표시(셀렉터 within 한정, strict-mode).
- 차원 전환(이슈별→사용자별→기간별) → 표/차트 갱신. **MSW 시나리오는 SPA 내부 이동·영속 store**(reload 금지=store 리셋 가짜그린, 메모리 e2e-msw-scenario-toggle). recharts SVG는 `.recharts-*` 클래스 존재만 확인(실 구조 page.evaluate로 사전 확인, 메모리 mermaid 셀렉터 선례) — 정확 막대 수 단언은 표로.
- 빈 상태 시나리오 → 빈 상태 메시지. 403 시나리오 → 권한 안내.
- 기존 E2E 회귀 동반 실행(메모리 ui-pr-defer-e2e-regression-latent). orphan vite 포트 정리(메모리 e2e-orphan-vite).

**검증**: `pnpm test:e2e worklog-aggregate` + 인접 회귀 spec

## Plan 메타

- task 수: 8 (+ Task 0 controller 선행 recharts 설치)
- 예상 wave: 5 — W1[T1,T3] · W2[T2,T4,T5] · W3[T6] · W4[T7] · W5[T8]. 프론트 레이어 의존(api→훅/차트/표→조립→라우트→E2E)으로 직렬성 존재.
- 예상 시간: 약 20~30분(레이어 직렬 + E2E 통합)
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 범위: D6(프론트 UI) + D7(E2E)만. 백엔드 D1~D5 완료(#167). period dense 채움·URL 쿼리 동기화는 범위 외(spec E1/E7).
- 추가 검증: pnpm verify(lint+typecheck+test+build) + playwright. **recharts 신규 의존성** — 절대규칙 #17 버전 고정 + Maxi 승인 완료.
- 핵심 함정(리뷰 반영): (1) recharts jsdom 렌더 한계→데이터변환 로직 단위+E2E 위임. (2) Zod optional≠nullable(NON_NULL). (3) project 필드 invent 금지(실 DTO). (4) router.ts 병행 worktree 충돌. (5) i18n 콜론 종결 0. (6) formatSeconds 재사용. (7) MSW 영속 store(E2E 가짜그린 회피). (8) node_modules race→Task 0 선행 설치.

## 리뷰 결과

### 적대적 독립 plan 리뷰 (design + eng-frontend 통합, 2026-06-21)

gstack plan-design-review 대화형 대신 집중 독립 리뷰(메모리 bts-review-plan-autoplan-overkill 정신). 종합 판정 **통과(BLOCKER 0)**, CONCERN 3건 반영.

**BLOCKER: 없음.**

**CONCERN 3건 (반영)**.
- **C1 (design) — 차트 방향**: by=issue/user는 label(issueKey/displayName)이 길어 세로 막대 X축 가독성 저하. → Task 4 GREEN에 "by=issue/user는 **가로 막대**(`layout='vertical'`, YAxis=label), by=period는 세로 막대(시간축)" 보강. (잔여 taste — 게이트1 확인)
- **C2 (eng) — 403 안내 화면**: 기존 `ProjectNotFoundScreen`(named export, members 라우트) 문구가 "존재하지 않거나 접근 권한이 없습니다"로 **probe 방지형**이라 403에도 적합 → **재사용 확정**(신규 컴포넌트 불요). 단 우리는 HTTP 403 status 분기(versions는 errorCode 404). → Task 6에 "403 → ProjectNotFoundScreen 재사용" 명시.
- **C3 (design/UX) — 진입점 부재**: 프로젝트 컨텍스트 네비게이션 컴포넌트가 코드베이스에 **없음**(settings 페이지도 모으는 사이드바 없이 직접 경로 진입). 보고 페이지 진입점(FR10)은 기존 패턴 부재. → **게이트1 Maxi 확인 taste decision**. MVP 기본안 = 직접 URL 진입(+E2E 직접 진입), 네비 링크는 마땅한 호스트 컴포넌트 생기면 후속. Task 7은 라우트 등록까지만, 진입점 링크는 Maxi 결정에 따라.

**NIT (반영)**.
- N1 — from/to 적용 트리거: 차원/granularity 셀렉터는 즉시 반영, from/to는 입력 변경 시 반영(별도 적용 버튼 없음). Task 6에 명시.
- N2 — 차트 접근성: aria-label + 표가 1차 데이터 출처(스크린리더). 이미 spec NFR 반영. OK.

**확인된 강점**. Zod optional≠nullable(NON_NULL)·project drift 차단·recharts jsdom 한계 명시·router.ts 병행 충돌·MSW 영속 store·node_modules race 선행설치·formatSeconds 재사용·i18n 콜론 검증 — 모두 plan에 선반영됨.
