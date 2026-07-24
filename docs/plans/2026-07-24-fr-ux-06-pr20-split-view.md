# FR-UX-06 Phase 5 PR20 — split view (이슈 목록+상세 2분할)

> slug: fr-ux-06-pr20-split-view
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking
> 생성: 2026-07-24

## Brief

**사용자 원문**: FR-UX-06 Phase 5 PR20 — split view (이슈 목록+상세 2분할 화면). 새 라우트/레이아웃, 목록↔상세 URL 동기화. PR19에서 의도적으로 이연한 후속 화면.

**classify 결과**: type=ui · agent=frontend-engineer · primary_bc=issue-tracking · slug=fr-ux-06-pr20-split-view

**맥락**: FR-UX-06(BTS UI/UX를 Jira Cloud 방식으로 전면 개편) Phase 5(화면) 네 번째 PR. 선행 PR17(공유 FilterBar)·PR18(이슈 목록 카드→ui/table·서버정렬)·PR19(이슈 상세 탭화)에서 split view를 명시적으로 이연. 이번 PR에서 이슈 목록+상세를 좌우 2분할로 보는 화면을 신설.

## 도메인 정리

- **BC**: issue-tracking (물리 `apps/web`) / 논리 소속 personalization (FR-UX-06 ADR §D5 "논리 ≠ 물리")
- **영향 엔티티**: Issue, IssueKey (전부 기존 — 신설 0). 이 PR은 순수 view/routing 재구성, 도메인 모델 변경 없음
- **새 용어**: 없음 (glossary "split/2분할" grep 0건 → 신설 불요). "split view(2분할 화면)"은 UI 레이아웃 표현일 뿐 유비쿼터스 언어 대상 아님
- **기존 결정 충돌**: 없음. 이 PR은 FR-UX-06 ADR([2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md))의 Phase 5 실행 PR. 신규 ADR 불요
- **관련 ADR**: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (기존)

### ADR이 이 화면에 강제하는 제약 (spec으로 인계)

1. **★D4 규칙**: "라우트가 바뀌면 nav+Link, 같은 라우트에서 패널만 바뀌면 Radix Tabs." split view에서 목록 행을 골라 상세를 여는 것은 **URL이 바뀌는 라우트 이동**(체크포인트 "목록↔상세 URL 동기화")이다 → **URL 기반**으로 상세를 결정하고 목록 행은 `<Link>`. Radix Tabs 아님.
2. **★h1 단독 계약 (34 e2e)**: 문서당 `<h1>` 1개(WCAG 1.3.1). 현재 `issues.index`(목록·PageHeader h1)와 `issues.$key`(상세·이슈키 h1)는 **각각 별도 문서**라 h1 1개씩. split view는 둘을 **한 화면에 합성** → h1 충돌 위험. spec에서 상세 페인 h1 강등(→h2/section) 또는 목록/상세 h1 소유 규칙을 반드시 확정.
3. **role="navigation" aria-label 단일성 (18 e2e·4 문자열)**: 새 nav 랜드마크 추가 시 기존 라벨(`메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환`·workflow sidebar)과 충돌 금지.
4. **role="dialog" 147 e2e·검색 aria-label 단일**: 이 PR 무접촉이어야 안전.

### 기존 라우트 지형 (실측)

- `routes/issues.index.tsx` — 목록(IssueListPage + IssueListRouteAdapter, PR18에서 ui/table화). `useSearch`로 필터/정렬/컬럼 URL 상태 관리
- `routes/issues.$key.tsx` — 상세(좌 본문 / 우 메타패널, PR19에서 활동 3탭화·IssueMetaPanel 분해)
- 둘 다 code-based 라우팅(router.ts에 adapter 등록, PR#11 컨벤션). `_shell` pathless layout 하위(PR10 재부모화)

### 미해결 설계 질문 (→ spec Phase A에서 결정)

- **라우트 구조**: (a) `issues.index`에 `?selected=KEY` 검색 파라미터로 우측 상세 페인 인라인 로드 vs (b) 별도 split 라우트 신설 vs (c) `issues.$key`를 목록 페인과 나란히. → D4상 "URL이 상세를 결정"이 핵심 계약. spec에서 구체 결정.
- **기존 전체화면 상세(`issues.$key`) 유지 여부** — split view가 기본이 되면 전체화면 상세 라우트를 남길지/리다이렉트할지.
- **반응형** — 좁은 폭(<900px)에서 2분할 붕괴 처리(PR11 사이드바 반응형 관례 참조).

## 스펙

전체 스펙. [docs/specs/2026-07-24-fr-ux-06-pr20-split-view.md](../specs/2026-07-24-fr-ux-06-pr20-split-view.md)

**Maxi 확정 결정 3 (미해결 질문 봉인)**.
- D1 = `/issues?selected=KEY` 검색 파라미터 (목록 라우트 유지·우측에 기존 IssueDetailPage 인라인)
- D2 = 상세 페인은 **이슈 선택 시에만** 등장 (미선택 목록 전체폭 → 선택 시 축소·페인 등장 → 해제 복귀)
- D3 = 좁은 화면(<lg) = 목록만 + 행 클릭 시 기존 `/issues/$key` 전체화면 이동

핵심 시나리오 3줄 요약.
- 와이드에서 이슈 행 클릭 → `?selected=KEY`로 URL 동기화·우측 상세 페인 등장·선택 행 강조 (닫기 → 전체폭 복귀)
- 필터/정렬/page 변경 시 `?selected` 보존, 상세 페인은 selected 키 기준 독립 fetch
- `<lg` 좁은폭은 목록만·행 클릭 시 전체화면 이동 / `IssueDetailPage variant='page'` 미지정 렌더는 기존 무변경(무회귀 증거)

**하드 제약**. `/issues/$key` 전체화면 유지(딥링크)·상세 페인 h1→h2(문서 h1 단일)·기존 IssueDetailPage 재사용·nav/dialog e2e 무접촉. FR 129 불변.

## Brainstorming Check

✅ 통과 (1회 sanity check). gap 4건 보강 — G1 a11y(포커스/Escape/aria-current)·G2 클릭목적지 useMediaQuery 판정·G3 네비 onNavigate 콜백 단일경로 실측·G4 페인 독립 스크롤. 핵심 갈림길은 Maxi D1~D3로 선봉인.

## Plan

> 전부 `apps/web` 프론트. 테스트 = vitest(유닛) + Playwright(e2e). RED = 실패 유닛/e2e 테스트 먼저.
> 무회귀 원칙: `IssueDetailPage` `variant` 기본값 `'page'`로 기존 렌더 바이트 동일 → 기존 3 테스트파일 무수정 green.

### Task 1. IssueDetailPage에 `variant='page'|'pane'` 도입

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`]
- depends-on: []

**RED**: `issues.$key.test.tsx`에 추가.
- `variant` 미지정(기본 `'page'`) → 제목이 `<h1>`, 닫기 버튼 없음 (기존 동작).
- `variant='pane'` → 제목이 `<h2>`(문서 h1 단일 계약), `onClose` prop 호출하는 닫기 버튼 존재, `Escape` 키 → `onClose` 호출, 등장 시 포커스가 상세 영역(제목/닫기)으로 이동.
- `variant='pane'` + `IssueRedirectError`(옛키→새키) → `onIssueRedirect(newKey)` 호출(기본 fullscreen navigate 대신), 삭제 → `onIssueClosed()` 호출.
- 실패(예상): `variant`/`onClose`/`onIssueRedirect`/`onIssueClosed` prop 미존재.

**GREEN**: `issues.$key.tsx`.
- `IssueDetailPageProps`에 `variant?: 'page' | 'pane'`(기본 `'page'`), `onClose?`, `onIssueRedirect?(newKey)`, `onIssueClosed?` 추가.
- 제목 태그를 `variant==='pane' ? 'h2' : 'h1'`로 분기(현재 613줄 `<h1>`).
- pane일 때 헤더에 닫기 버튼 렌더 → `onClose`. `useEffect`로 mount 시 포커스 이동 + `Escape` keydown 리스너 → `onClose`.
- redirect(120줄)·delete(234줄) navigate를 `variant==='pane'`이면 콜백 위임, 아니면 현행 navigate 유지.

**REFACTOR**: pane 헤더/포커스 로직을 파일 내 헬퍼로 정리. KDoc에 variant 계약 명시.

**검증**: `pnpm exec vitest run src/routes/issues.$key.test.tsx`

### Task 2. `useMediaQuery` 훅 신설 (matchMedia 기반)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-media-query.ts`, `apps/web/src/hooks/use-media-query.test.ts`]
- depends-on: []

**RED**: `use-media-query.test.ts`.
- `useMediaQuery('(min-width: 1024px)')`가 초기 `matchMedia().matches` 반환.
- `change` 이벤트 발생 시 값 갱신. 언마운트 시 리스너 해제.
- jsdom `matchMedia` mock으로 검증.
- 실패(예상): 훅 파일 없음.

**GREEN**: `use-media-query.ts` — `window.matchMedia(query)` 구독 훅. SSR/`matchMedia` 부재 안전(초기 false 폴백).

**REFACTOR**: `addEventListener('change')` 표준 API 사용(구형 `addListener` 폴백 불요 — 타깃 브라우저 확인). L1 주석.

**검증**: `pnpm exec vitest run src/hooks/use-media-query.test.ts`

### Task 3. IssueTable 선택 행 시각 강조 (`selectedKey`)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/IssueTable.tsx`, `apps/web/src/components/issues/IssueTable.test.tsx`]
- depends-on: []

**RED**: `IssueTable.test.tsx`.
- `selectedKey='ATLAS-1'` → 해당 행에 `aria-current` + 선택 배경 클래스(`--bg-selected` 계열), 다른 행엔 없음.
- `selectedKey` 미지정/`null` → 어떤 행도 강조 없음(기존 렌더 무변경, bulk 체크박스 `select-{key}`·요약 `issue-summary-{key}`·행 `aria-label` 계약 verbatim).
- 실패(예상): `selectedKey` prop 미존재.

**GREEN**: `IssueTableProps`에 `selectedKey?: string | null` 추가. `IssueTableDataRow`가 `issue.key === selectedKey`면 `aria-current="true"` + 배경 클래스. bulk `selection`과 독립.

**REFACTOR**: 강조 클래스 상수화. KDoc에 "split 선택(`selectedKey`) ≠ bulk 선택(`selection`)" 명시.

**검증**: `pnpm exec vitest run src/components/issues/IssueTable.test.tsx`

### Task 4. router `issuesIndexRoute.validateSearch`에 `selected` 추가

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/router.ts`, `apps/web/src/router.split-search.test.tsx`]
- depends-on: []

**RED**: `router.split-search.test.tsx`.
- `createMemoryHistory`로 `/issues?selected=ATLAS-9&status=open&sort=priority,asc` 진입 → `issuesIndexRoute` 파싱 결과 `selected==='ATLAS-9'` **그리고 기존 status/sort 보존**.
- `?selected` 없으면 `selected===undefined`.
- **★load-bearing 근거**: validateSearch 화이트리스트에 없는 키는 TanStack이 **제거**한다 → 이 테스트가 없으면 `?selected`가 어댑터에 영영 안 도달. mutation 판별(현 whitelist에 selected 추가 전에는 RED여야 함).
- 실패(예상): `selected` 미파싱(undefined).

**GREEN**: `issuesIndexRoute.validateSearch` 반환 타입·객체에 `selected?: string`(`typeof search['selected']==='string' ? … : undefined`) 추가.

**REFACTOR**: 없음(1필드 추가). 반환 타입 주석.

**검증**: `pnpm exec vitest run src/router.split-search.test.tsx`

### Task 5. IssueListRouteAdapter split 레이아웃 결선 (통합)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.index.tsx`, `apps/web/src/routes/issues.index.test.tsx`]
- depends-on: [1, 2, 3, 4]

**RED**: `issues.index.test.tsx`.
- (와이드, `useMediaQuery` mock true) `?selected=ATLAS-3` → 우측에 `IssueDetailPage variant='pane'` 렌더, `IssueTable selectedKey='ATLAS-3'`, 문서 `<h1>` 정확히 1개.
- (와이드) 행 클릭(`onNavigate('ATLAS-3')`) → `navigate`가 `?selected=ATLAS-3`로 이동(기존 filter/sort/page **보존**), 같은 키 재클릭 → `selected` 제거(toggle off).
- (와이드) 페인 `onClose`/`onIssueClosed` → `selected` 제거. `onIssueRedirect('NEW-1')` → `?selected=NEW-1`.
- (좁은폭, mock false) 행 클릭 → `navigate({ to: '/issues/$key', params: { key } })` 전체화면(선택 파라미터 미사용), `?selected` 있어도 페인 미표시(목록만).
- `selected` 미지정 → 페인 없음·목록 전체폭(기존 렌더 무변경).
- 실패(예상): split 분기·selected 보존 로직 미존재.

**GREEN**: `issues.index.tsx` `IssueListRouteAdapter`.
- `useSearch`에서 `selected` 추출. `const isWide = useMediaQuery('(min-width: 1024px)')`.
- `onNavigate` = 와이드면 `navigate({ search: (prev)=>({ ...prev, selected: key===prev.selected ? undefined : key }) })`(**기존 search 스프레드로 filter/sort/page 보존**), 좁으면 `navigate({ to:'/issues/$key', params:{key} })`.
- 와이드 && `selected` → 2컬럼 레이아웃(목록 축소 + `<IssueDetailPage issueKey={selected} variant='pane' onClose/onIssueClosed=selected 제거, onIssueRedirect=selected 교체 />`). 각 컬럼 독립 스크롤(`overflow-y-auto`). 아니면 단일 컬럼 목록.
- `IssueTable`에 `selectedKey={isWide ? selected : null}` 전달.

**REFACTOR**: split 레이아웃을 파일 내 `IssueListSplitView` 서브컴포넌트(또는 `components/issues/`)로 추출 — DOM/testid verbatim. i18n 문자열 정리.

**검증**: `pnpm exec vitest run src/routes/issues.index.test.tsx`

### Task 6. split view E2E

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-split-view.spec.ts`]
- depends-on: [5]

**RED→GREEN**: Playwright(기존 issue 목록 MSW/시드 재사용, 신규 핸들러 최소화).
- S1: (와이드 뷰포트) `/issues`에서 이슈 행 클릭 → URL `?selected=KEY`, 우측 상세 페인 표시, 선택 행 강조.
- S2: 닫기 → `?selected` 제거, 전체폭 복귀.
- S3: `/issues?selected=KEY` 직접 진입 → split 렌더.
- S4: 정렬/페이지 변경 후 `?selected` 보존.
- S5: (좁은 뷰포트로 `setViewportSize`) 행 클릭 → `/issues/$key` 전체화면.
- h1 단독: split 와이드에서 `getByRole('heading', { level: 1 })` 정확히 1개.
- **바이너리 직접 실행·개수 판정**([[e2e-playwright-filter-arg-drop]]). CI e2e 잡 없음 → 로컬 필수.

**검증**: `pnpm exec playwright test issue-split-view` (바이너리 직접, 개수 대조)

## Plan 메타

- task 수: 6
- wave 계산(files 무겹침 + depends-on): **Wave1 = T1·T2·T3·T4**(전부 독립·파일 무겹침) → **Wave2 = T5**(1~4 의존) → **Wave3 = T6**(5 의존)
- ★**wave1 dispatch 주의**: PR19에서 wave1 병렬 lint-staged 공유 stash 레이스로 커밋 귀속 흡수 발생([[fr-ux-06-pr19-issue-detail-tabs-done]]·[[worktree-lint-staged-shared-git-stash-collision]]). **impl prompt에 "자기 files만 stage·git stash 금지" 명시, 재발 시 wave1 직렬 dispatch로 전환** 권장.
- 예상 시간: 6 task × 3분 ≈ 18분(직렬) / wave 병렬 시 약 9분
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 추가 검증: typecheck 0 · `eslint src` 0 · vitest 관련 green + 전수 무회귀 · playwright split spec green · build 0
- FR 129 불변 (D단계 마킹 변경 없음 → dashboard regen 불요, verify-master-plan 129/129)

## 리뷰 결과

### plan-design-review (2026-07-24, 디자인 관점 집중 검토)

**✅ 통과**.
- D4 규칙 준수(라우트 이동 = URL/Link, Radix Tabs 미사용) — split 선택이 정확히 nav 성격.
- h1 단독 계약(HC-3)을 T1 variant='pane' h2 강등으로 정면 처리 — 디자인·접근성 정합.
- 리사이즈 가능 divider **미도입**은 올바른 스코프 규율(Jira split도 고정폭). 애니메이션 미도입도 적절.
- 선택 행 강조를 ADS `--bg-selected`/`--text-selected` 토큰 재사용(하드코딩 색 금지) — 디자인 시스템 정합. bulk 체크박스와 시각 독립.
- 미선택 시 전체폭(D2)은 화면 낭비 최소화 — 좋은 기본값.

**⚠️ 주의(비차단)**.
- **분할 비율**. 상세 페인 등장 시 목록:상세 비율 미명시 → **권장 기본 = 목록 좌측 고정 최소폭(가독) + 상세 flex 확장**(Jira navigator 관례, 상세가 넓음). T5에서 확정. BLOCKER 아님.
- 페인 등장 포커스 이동이 스크롤 점프를 유발하지 않도록 `preventScroll` 고려(T1 REFACTOR).

**✅ taste decision — Maxi 확정 (게이트1)**.
- **좁아진 목록 페인의 컬럼 처리 = (A) 컬럼 유지 + 가로 스크롤.** 기존 다컬럼 표 그대로, split에서 목록이 좁아지면 목록 페인이 좌우 스크롤(`overflow-x-auto`). 반응형 컬럼 셋 미도입 → T5 구현 최소, 컬럼 선택 관례(PR18 `useColumnVisibility`) 그대로 유지. T5 GREEN에 반영: 목록 컬럼 로직 무변경, split 목록 컨테이너에 `overflow-x-auto` + F3의 `overflow-y-auto` 병기.

**BLOCKER: 없음.**

### 종합
- BLOCKER 0. 순수 프론트·FR 129 불변·무회귀 설계(variant 기본 'page' 무변경). taste 1건은 게이트1에서 Maxi 확정.

### bts-codereview (2026-07-24, PR #305, superpowers:code-reviewer)

**BLOCKER 0.** 절대 규칙 19개 위반 없음(any/`!`/빈catch/console.log/localStorage clean). TDD 커밋 순서 준수.

**확정 버그 4건 봉합 (T8 hot-fix, TDD)**.
- CONCERNS-1: `handleFilterChange`가 필터 변경 시 `selected` 유실(NFR-3 "페인은 필터와 무관" 위반) → `(prev)=>({...nextSearch, page:0, selected: prev.selected})`로 보존.
- CONCERNS-2: 페인 Escape 리스너가 Radix 다이얼로그/드롭다운 Esc와 이중 발화(다이얼로그+페인 동시 닫힘) → keydown 핸들러에 `if (e.defaultPrevented) return`.
- CONCERNS-3(★데이터 안전): 페인 `IssueDetailPage`에 `key` 부재 → 이슈 전환 시 삭제확인/편집 상태 이월로 **잘못된 이슈 삭제 위험** → `key={selected}` 부여(fresh 마운트). [[react-usestate-stale-key-prop]].
- CONCERNS-4: 빈/공백 `?selected=` → 페인이 빈 키로 404 렌더(EC-1 위반) → `normalizeSelectedKey` 정규화(blank→undefined).

**후속 이연 (경미)**.
- CONCERNS-5: 페인 오픈/클로즈 시 목록 return 분기 변경으로 목록이 재마운트 → bulk 체크박스 선택·스크롤 초기화. 항상-마운트 레이아웃 리팩터 필요(비-split 케이스 레이아웃 회귀 위험)라 별도 후속 티켓. 데이터 손실 아님(react-query 캐시 유지).

**검증 (T8 후)**. 유닛 5파일 143 green(+5)·typecheck 0·eslint 0 errors·e2e 11(split6+table5) green.
