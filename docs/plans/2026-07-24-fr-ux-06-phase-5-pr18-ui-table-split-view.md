# FR-UX-06 Phase 5 PR18 — 이슈 목록 카드 → ui/table 네비게이터 (정렬·컬럼 선택·split view)

> slug: fr-ux-06-phase-5-pr18-ui-table-split-view
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트 apps/web 단일)
> 생성: 2026-07-24

## Brief

**사용자 원문**. FR-UX-06 Phase 5 PR18 — 이슈 목록 카드를 `ui/table` 네비게이터로 전환 (정렬·컬럼 선택·split view). 허브 `fr-ux-06-jira-redesign-plan` 22 PR 체인 중 Phase 5 두 번째 화면 PR (PR17=공유 FilterBar 다음).

**classify 결과**. type=ui · agent=frontend-engineer · primary_bc=issue-tracking · slug=fr-ux-06-phase-5-pr18-ui-table-split-view.

**컨텍스트**. `ui/table` 프리미티브는 PR2(#286)에서 이미 도입됨. Phase 5 화면 개편의 일부로, 이슈 목록(issues.index 라우트)의 카드 레이아웃을 Jira Cloud 방식 테이블 네비게이터로 전환한다.

**착수 전 필독 (메모리)**.
- [[frontend-nav-aria-label-e2e-contract]] — h1 단일(e2e 34건)·role 셀렉터·nav 라벨 4종 계약
- [[playwright-getbyrole-exact-strict-mode]] — 이슈 행/셀 텍스트 셀렉터 strict 위반 주의
- [[e2e-playwright-filter-arg-drop]] — vitest·playwright 둘 다 `pnpm test -- <파일>` 인자 삼킴 → 바이너리 직접 호출
- [[frontend-ci-10min-timeout-nonrequired]] — CI에 e2e 잡 없음 → 로컬 e2e 필수

## 도메인 정리

- **BC**: issue-tracking (프론트 apps/web 뷰 계층 단일). 백엔드 도메인 모델 무변경 전제.
- **영향 엔티티**: 없음 (Issue 등 도메인 모델 변경 0). 이슈 목록 화면(`routes/issues.index.tsx`, 649줄)의 렌더 방식만 카드 → 테이블로 전환.
- **새 용어**: 없음. "정렬(sort)"·"컬럼(column)"·"split view"는 UI 프레젠테이션 어휘로, 도메인 유비쿼터스 언어 아님.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: 없음 (뷰 계층 변경 → 새 ADR 불필요). 참고 ADR — `2026-06-05-issue-browse-view-permission`(목록은 BROWSE 권한 게이팅 준수, 기존 API가 이미 처리)·`2026-06-30-fr-api-01-cursor-pagination-envelope`(페이지네이션 방식).

### 실측 (현행 구현)
- `routes/issues.index.tsx` = `IssueListPage` + `IssueCard`(`<div>` flex 행) + `IssueListRouteAdapter`. 현재 `<ul>/<li>` + IssueCard, `data.content.map`.
- 데이터: `useQuery(['issues', projectKey, page, normalizedFilter], () => fetchIssues({projectKey, page, size, filter}))`. **sort 파라미터 미전송.**
- 백엔드 `GET /api/v1/issues` = `@PageableDefault(size=20) pageable: Pageable` → Spring이 `?sort=필드,방향`을 **바인딩은 가능**(단, `service.listIssues`가 실제 정렬 적용하는지는 스펙/플랜에서 검증 필요).
- 컬럼 선택/뷰 preference **persist 흔적 0** (localStorage·백엔드 preference API 없음).
- `ui/table` 프리미티브(PR2 #286)는 이미 존재 → 첫 소비자 후보.

### ★스코프 확정 (Maxi, 2026-07-24)
1. **정렬 = 서버 전체 정렬**. 프론트 `fetchIssues`가 `?sort=필드,방향` 전송 + 백엔드 `IssueRepository.listWithType`가 `pageable.sort`를 **정렬 허용목록(whitelist)** 으로 적용 (현재 `ORDER BY created_at DESC` 고정 → 동적화). AQL `buildOrderBy(sort)`/`searchByAql`(IssueRepository:2500·2781) 패턴 재사용. **issue-tracking 같은 BC 뷰 계층 확장이라 한 PR 안 허용**(PR#13 옵션 C 선례).
2. **컬럼 선택 = localStorage 저장** (프론트만·기기별). 백엔드 preference API 미신설.
3. **split view = PR19로 미룸**. PR18 = 표 전환 + 서버 정렬 + 컬럼 선택까지. 좌우 분할·상세 재구성은 PR19(이슈 상세 탭화)와 함께.

### 확정 작업 경계
- **프론트(apps/web)**: `routes/issues.index.tsx` 카드 → `ui/table` 테이블·정렬 헤더(서버 sort 연동·queryKey에 sort 포함)·컬럼 선택 드롭다운(localStorage persist)·`fetchIssues` sort 파라미터 추가.
- **백엔드(issue-tracking, 같은 BC 뷰 계층)**: `listWithType`가 `pageable.sort` 허용목록 정렬 적용(무지정 시 기존 created_at desc 유지=무회귀). 허용 컬럼만 정렬 가능(임의 컬럼 거부).
- **불변**: 도메인 모델·FR 총수 129·기존 페이지네이션 계약·BROWSE 권한 게이팅.

## 스펙

전체 스펙. [docs/specs/2026-07-24-fr-ux-06-phase-5-pr18-ui-table-split-view.md](../specs/2026-07-24-fr-ux-06-phase-5-pr18-ui-table-split-view.md)

핵심 시나리오 요약.
- 이슈 목록 카드 → `ui/table` 테이블 전환(행 클릭=상세, 체크박스 전파 차단, overflow-x auto)
- 정렬 헤더 클릭 → 서버 `?sort=필드,방향` 전송(전체 정렬)·asc↔desc↔해제 3-state·URL 반영. 백엔드 `listWithType`가 허용목록 정렬 적용(무지정 시 created_at desc 무회귀)
- 컬럼 선택 드롭다운 → localStorage persist(필수 컬럼 체크박스·키·요약 숨김 불가)
- 기존 전량 보존(선택 누적·전체선택·페이지네이션·IssueFilterBar·담당자 해석)

정렬 허용목록(초안). `key · summary · priority · createdAt · updatedAt` (status는 G1로 초기 제외). impl에서 실제 ISSUES 컬럼 대조 확정.

## Brainstorming Check

✅ 통과 (1회, office-hours 스킵→자체 적대적 sanity check). gap 3건 반영: G1 상태정렬 의미(허용목록서 status 제외)·**G2 ★e2e 셀렉터 보존**(select-{key}·issue-summary-{key}·aria-label={key}·role=status verbatim 또는 의존테스트 동일PR 갱신)·G3 정렬 aria-sort+방향아이콘. Maxi 결정 필요 gap 0.

## Plan

### 정렬 필드 계약 (T1↔T2↔T4 공유 — 이 토큰을 세 태스크가 동일하게 사용)
`key · summary · priority · createdAt · updatedAt`. 프론트가 `?sort=<토큰>,<asc|desc>` 전송 → 백엔드 whitelist가 이 토큰을 jOOQ 컬럼(ISSUES.KEY·SUMMARY·PRIORITY·CREATED_AT·UPDATED_AT)에 매핑. status는 G1(워크플로우 순서)로 제외.

### Task 1. 백엔드 `listWithType`가 pageable.sort를 허용목록으로 적용

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryListSortTest.kt`]
- depends-on: []

**RED**: `IssueRepositoryListSortTest`(Testcontainers) —
- `priority,desc` 정렬 요청 시 결과가 우선순위 내림차순 (허용 필드 적용)
- 허용목록 외 필드(`assigneeId,asc`) 요청 → 예외 아닌 기본 정렬(created_at desc) fallback (EC1)
- **정렬 무지정(unsorted Pageable) → 기존 created_at desc 유지 (N2 무회귀)** ← load-bearing, mutation(현 고정 orderBy를 그대로 두면 sorted 케이스 red)
실패: 현재 `.orderBy(ISSUES.CREATED_AT.desc())` 고정이라 priority 정렬 테스트 실패.

**GREEN**: `listWithType`의 content 쿼리 `.orderBy(...)`를 `pageable.sort` → whitelist 매핑 함수(`buildListOrderBy(sort): List<SortField<*>>`, AQL `buildOrderBy` 패턴 참조)로 교체. sort 비었거나 허용필드 0 → `listOf(ISSUES.CREATED_AT.desc())`. count 쿼리는 정렬 무관(불변).

**REFACTOR**: whitelist를 `private val SORTABLE_COLUMNS: Map<String, Field<*>>` 상수 추출 + KDoc(허용 필드·fallback 계약).

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*IssueRepositoryListSortTest'` (dev postgres 5433 필요 — [[gradlew-at-backend-and-assembly-boot-needs-dev-postgres]])

### Task 2. 프론트 `fetchIssues`에 sort 파라미터 추가

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`]
- depends-on: []

**RED**: `issues.test.ts` — `fetchIssues({projectKey, page, size, sort:{field:'priority', dir:'desc'}})` 호출 시 요청 URL에 `sort=priority%2Cdesc` 포함. sort 미전달 시 기존 URL(sort 없음) 유지 = 하위호환(N2).
실패: `FetchIssuesParams`에 sort 없음.

**GREEN**: `FetchIssuesParams`에 `sort?: { field: SortField; dir: 'asc'|'desc' }` 추가. `SortField` 유니온 타입(정렬 필드 계약 5종). `buildIssueFilterQuery` 뒤에 `if (sort) query.append('sort', \`${sort.field},${sort.dir}\`)`.

**REFACTOR**: `SortField` 타입·유효 필드 배열 export(T4/T5 재사용). KDoc.

**검증**: `node_modules/.bin/vitest run src/api/issues.test.ts` (바이너리 직접 — [[e2e-playwright-filter-arg-drop]])

### Task 3. 컬럼 표시 상태 훅 (localStorage persist)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-column-visibility.ts`, `apps/web/src/hooks/use-column-visibility.test.ts`]
- depends-on: []

**RED**: `use-column-visibility.test.ts` —
- 초기값 = 전달된 기본 표시 컬럼
- 토글 시 해당 컬럼 on/off
- localStorage에 저장 + 재마운트 시 복원 (persist)
- localStorage 값 손상/미지의 컬럼 키 → 기본값 안전 복구 (EC2)
- 필수 컬럼(required)은 토글 무시(항상 표시)
실패: 훅 미존재.

**GREEN**: `useColumnVisibility(storageKey, allColumns, requiredKeys, defaultVisible)` — `useState` + `useEffect`로 localStorage sync. try/catch 파싱. 반환 `{ visible: Set<string>, toggle(key), isVisible(key) }`.

**REFACTOR**: 손상 복구·필수 컬럼 로직 헬퍼 분리 + KDoc.

**검증**: `node_modules/.bin/vitest run src/hooks/use-column-visibility.test.ts`

### Task 4. `IssueTable` + `ColumnSelector` 컴포넌트 (ui/table 기반·★e2e 셀렉터 보존)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/IssueTable.tsx`, `apps/web/src/components/issues/issue-columns.ts`, `apps/web/src/components/issues/ColumnSelector.tsx`, `apps/web/src/components/issues/IssueTable.test.tsx`, `apps/web/src/components/issues/ColumnSelector.test.tsx`]
- depends-on: []

**RED**: `IssueTable.test.tsx` — (columns/issues/selection/sort는 props)
- `ui/table` 렌더(`<table>` 시맨틱), 컨테이너 overflow-x auto
- **★e2e 셀렉터 verbatim 보존 (G2)**: 체크박스 `data-testid=select-{key}`·`aria-label="이슈 선택"`, 요약 `data-testid=issue-summary-{key}`, 행 링크 `aria-label={key}`, 상태 `role="status"`
- 행 클릭 → onNavigate(key), 체크박스 클릭 → 이벤트 전파 차단(행 이동 안 함)
- 정렬 헤더 클릭 → onSort(field) 호출·현재 정렬 컬럼에 `aria-sort={ascending|descending}` + 방향 아이콘, 비정렬 `aria-sort="none"` (G3)
- 표시 컬럼(visible) prop만 렌더
`ColumnSelector.test.tsx` — 드롭다운 컬럼 토글 콜백·필수 컬럼 비활성

**GREEN**: `issue-columns.ts`(컬럼 정의: key·header·sortable·required·render)·`IssueTable`(TableHeader/Body/Row/Head/Cell, sortable 헤더 버튼+aria-sort, 담당자는 useUsersByIds 해석 결과 prop)·`ColumnSelector`(ui/dropdown-menu 또는 popover+checkbox).

**REFACTOR**: 컬럼 render 함수 정리·KDoc(셀렉터 계약 보존 사유 명시).

**검증**: `node_modules/.bin/vitest run src/components/issues/IssueTable.test.tsx src/components/issues/ColumnSelector.test.tsx`

### Task 5. `issues.index` 라우트 결선 (테이블 전환·정렬 URL·컬럼 셀렉터)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.index.tsx`, `apps/web/src/routes/issues.index.test.tsx`]
- depends-on: [2, 3, 4]

**RED**: `issues.index.test.tsx` —
- `IssueListContent`가 `<ul>/IssueCard` 대신 `<IssueTable>` 렌더
- 정렬 헤더 클릭 → URL `?sort=` 반영 + `fetchIssues`에 sort 전달 + queryKey에 sort 포함
- 정렬 3-state 토글(asc→desc→해제) + 해제 시 sort 파라미터 제거
- 정렬 변경 시 page 0 리셋 (EC3)
- 컬럼 셀렉터 결선(useColumnVisibility)·표시 컬럼 IssueTable 전달
- 기존 보존: 필터 결선·clearAll·selection 누적·페이지네이션·FilteredEmptyState/IssueEmptyState/로딩/에러 (S6·EC4·EC7)
실패: IssueListContent가 아직 카드 렌더.

**GREEN**: `IssueListContent`를 IssueTable+ColumnSelector로 교체. useSearch로 sort 읽기·navigate로 sort 쓰기(3-state)·queryKey `['issues', projectKey, page, normalizedFilter, sort]`·정렬 변경 시 page 0. `IssueCard` 제거(orphan). router validateSearch에 sort 스키마 추가(선택).

**REFACTOR**: sort URL 직렬화 헬퍼·KDoc.

**검증**: `node_modules/.bin/vitest run src/routes/issues.index.test.tsx` + `node_modules/.bin/vitest run`(전수 회귀)

### Task 6. E2E — 테이블·정렬·컬럼 선택·필터+정렬

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-table.spec.ts`]
- depends-on: [5]

**RED→GREEN**: Playwright 시나리오 —
- S1 테이블 렌더(행·컬럼·행 클릭 상세 이동)
- S2 정렬 헤더 클릭 → `?sort=` URL 반영 + 순서 변경, 3-state 토글
- S3 정렬 유지 채 페이지 이동
- S4 컬럼 토글 → 표시 변경 + 새로고침 후 유지(localStorage)
- S5 필터+정렬 동시
- 기존 selection·pagination e2e 회귀 없음

**검증**: `apps/web/node_modules/.bin/playwright test e2e/issue-table.spec.ts`(바이너리 직접·개수 판정 [[e2e-playwright-filter-arg-drop]]) + 관련 기존 e2e(issue list·filter) 전수 대조

## Plan 메타

- task 수: 6 (백엔드 1 · 프론트 4 · qa 1)
- 예상 wave: 3 (wave1=T1·T2·T3·T4 4-병렬 / wave2=T5 / wave3=T6). 파일 겹침 0.
- 예상 시간: 직렬 ~18분 / 병렬 wave ~9분
- TDD 강제: yes (T6은 e2e라 RED→GREEN 변형)
- 추가 검증: typecheck·lint·vitest 전수·playwright·백엔드 :issue-tracking:test
- ★load-bearing 가드: T1 무지정 정렬 무회귀(mutation 필수)·T4 e2e 셀렉터 verbatim 보존
- BC 격리: 백엔드 변경은 issue-tracking 같은 BC 뷰 계층(sort)만·다른 BC 무접촉

## 디자인 결정 (plan-design-review, 2026-07-24)

design spec이 방향(색·hover·상태·overflow)을 잠갔으므로 목업 대신 텍스트 리뷰로 갭 5개 보강(Maxi 결정). impl(T4·T5)이 준수.

- **GAP-1 정렬 헤더 어포던스** (T4) — 정렬 가능 컬럼(`key·summary·priority·createdAt·updatedAt`)의 `<TableHead>`는 `<button>` 래핑·`cursor-pointer`·hover 시 `--bg-neutral-hover` 배경·`aria-sort`. 활성 정렬 컬럼은 방향 아이콘(▲asc/▼desc) 상시 표시, 비활성 정렬가능 컬럼은 hover 시 옅은 힌트 아이콘. 비정렬 컬럼(체크박스·담당자·라벨)은 일반 `<th>`.
- **GAP-2 컬럼 선택 배치** (T4) — 테이블 상단 툴바 우측에 트리거 = 아이콘(lucide `SlidersHorizontal`/`Columns3`) + "컬럼" 라벨, `ui/dropdown-menu`(또는 popover) + 체크박스 목록. 필수 컬럼은 disabled 체크박스(항상 표시).
- **GAP-3 상태 프리미티브 (결정)** — PR18은 **기존 인라인 상태 컴포넌트 보존**(FilteredEmptyState·IssueEmptyState·로딩·에러). `empty-state`/`skeleton` 프리미티브 통합은 **PR22 스코프(중복 제거)로 유지**. 사유 — PR18 스코프 최소화 + design spec도 프리미티브 통합을 PR22 debt로 명시(§250·256). 예외 = GAP-5 신규 로딩 피드백은 `skeleton` 프리미티브 사용(신규라 인라인 중복 미생성).
- **GAP-4 컬럼 폭·시각 위계** (T4) — 키(`font-mono`·좁은 고정폭·`--text-subtle`)·요약(flex-1·**시각 주역**·강조·truncate)·상태(고정·배지)·담당자(고정·아바타+이름)·우선순위(고정)·수정일(고정·`--text-subtle`). 요약이 주역, 나머지 보조.
- **GAP-5 정렬/페이지 전환 피드백** (T5) — 재조회(isFetching·데이터 존재) 동안 표 본문 `opacity-60 pointer-events-none`(레이아웃 시프트 방지)·첫 로딩(데이터 없음)은 `skeleton` 행. 정렬/페이지 클릭 후 즉시 피드백.

## 리뷰 결과

### 엔지니어링 self-review (2026-07-24, plan-design-review 축소 — 디자인은 design spec 잠금)

**축소 사유**. type=ui지만 이슈 테이블 레이아웃·인터랙션(행클릭=상세·체크박스 전파차단·overflow-x auto)이 `docs/design/fr-ux-06-jira-redesign.md`에 이미 확정. 시각 방향 잠금 상태라 plan-design-review 저효익. 실제 위험 = 백엔드 정렬 보안/무회귀 + e2e 셀렉터 보존 → 엔지니어링 관점 self-review. 디자인 QA는 구현 후 codereview/design-review 위임.

**PASS 항목**.
- ✅ 정렬 보안(N4) — whitelist = `Map<String, jOOQ Field>` 조회(원시 SQL 문자열 보간 없음). 미지 필드 → fallback. SQL injection·의도외 컬럼 노출 차단.
- ✅ 무회귀(N2) — T1 RED에 "무지정 정렬 → created_at desc 유지" mutation 가드 포함(load-bearing, [[verify-logic-vs-verify-guard]] 준수).
- ✅ e2e 셀렉터 보존(G2) — T4 RED가 select-{key}·issue-summary-{key}·aria-label={key}·role=status verbatim 어서션.
- ✅ 캐시 정합 — T5 queryKey에 sort 포함(정렬별 캐시 분기, stale 방지).
- ✅ BC 격리 — 백엔드 변경은 issue-tracking 같은 BC 뷰 계층(sort)만. 다른 BC 무접촉.
- ✅ wave — 파일 겹침 0, T5 depends [2,3,4]. 백엔드 T1 단일이라 Gradle 모듈 병렬 컴파일 충돌 없음([[bts-plan-wave-gradle-module-compile]] 미해당).

**⚠️ 주의 2건 (BLOCKER 아님, impl 반영 권장)**.
- W1 **테이블 접근성 이름** — `<table>`에 접근 가능한 이름(aria-label 또는 caption) 부여 권장. T4 GREEN에 반영.
- W2 **key 정렬 사전순 함정** — 이슈 키(`PROJ-1`·`PROJ-10`·`PROJ-2`) 문자열 정렬은 숫자순과 불일치. impl에서 (a) key를 정렬 허용목록에서 제외하거나 (b) 시퀀스 숫자 컬럼으로 정렬. 기본 정렬은 created_at이라 실사용 영향 작음. T1 impl 시 결정.

**BLOCKER: 없음.**

### plan-design-review (2026-07-24, 텍스트 리뷰·목업 스킵 — 디자인 방향 design spec 잠금)

- 완성도 초기 7/10 → 갭 5건 보강 후 목표 10/10.
- 갭 5건 모두 `## 디자인 결정` 섹션에 반영(GAP-1 정렬 어포던스·GAP-2 컬럼 셀렉터 배치·GAP-3 상태 프리미티브 결정·GAP-4 컬럼 위계·GAP-5 전환 피드백).
- 상태 커버리지(빈/로딩/에러)·a11y(aria-sort·h1 단일·셀렉터 보존)·반응형(overflow-x)·AI slop 위험 낮음(관례적 테이블) 확인.
- BLOCKER: 없음.

## GSTACK REVIEW REPORT

| Runs | Status | Findings |
|---|---|---|
| 엔지니어링 self-review | ✅ PASS | 주의 2 (테이블 a11y 이름·key 사전순 정렬), BLOCKER 0 |
| plan-design-review (텍스트) | ✅ PASS | 갭 5 보강 완료, BLOCKER 0 |

**VERDICT**: 계획 승인 가능. 디자인 갭 5건 계획 반영 완료, 엔지니어링 위험(정렬 보안·무회귀·e2e 셀렉터) 안전장치 명시. CODEX/CROSS-MODEL outside voices 미실행(Maxi 미요청·디자인 잠금).

NO UNRESOLVED DECISIONS
