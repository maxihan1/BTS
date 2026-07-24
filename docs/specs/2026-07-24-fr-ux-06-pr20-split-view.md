<!-- FR-UX-06 Phase 5 PR20 — 이슈 목록+상세 2분할(split view) 화면 스펙 -->

# FR-UX-06 Phase 5 PR20 — split view (이슈 목록+상세 2분할) — 스펙

> slug: fr-ux-06-pr20-split-view · type: ui · agent: frontend-engineer · BC: issue-tracking (논리 personalization)
> 관련 ADR: [2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (§D4 nav vs Tabs · h1 단독 계약)
> 상위 plan: [2026-07-24-fr-ux-06-pr20-split-view.md](../plans/2026-07-24-fr-ux-06-pr20-split-view.md)
> FR 총수: **129 불변** (순수 프론트 view/routing 재구성, FR 신설/변경 없음)

## 배경

이슈 목록(`/issues`, PR18에서 ui/table화)과 이슈 상세(`/issues/$key`, PR19에서 활동 3탭화)를 지금은 별도 전체화면으로 오가야 한다. Jira Cloud의 issue navigator처럼 **좌 목록 / 우 상세를 한 화면에서** 보게 하여, 목록을 훑으며 이슈를 빠르게 확인하도록 개선한다. PR19에서 의도적으로 이연했다.

## Maxi 확정 결정 (2026-07-24 게이트 전 3-way)

| # | 결정 | 선택 |
|---|---|---|
| D1 | 상세 표시·URL 동기화 방식 | **`/issues?selected=KEY` 검색 파라미터** — 목록 라우트 유지, 우측에 기존 `IssueDetailPage` 인라인 |
| D2 | 상세 페인 등장 시점 | **이슈 선택 시에만 등장** — 미선택 시 목록 전체폭, 선택 시 우측 페인 등장·목록 축소, 해제 시 복귀 |
| D3 | 좁은 화면(<lg) 처리 | **목록만 표시 + 행 클릭 시 기존 `/issues/$key` 전체화면 이동** |

### 하드 제약 (질문 아님 — 항상 준수)

- **HC-1**: `/issues/$key` 전체화면 라우트는 외부 딥링크(Slack·알림·이메일이 이슈 키 영구 인용, [[이슈 키 재사용 금지]])가 가리키므로 **그대로 유지·무변경**.
- **HC-2**: 상세 페인은 **기존 `IssueDetailPage` 재사용** (신규 상세 구현 금지).
- **HC-3**: 문서당 `<h1>` 1개 계약(WCAG 1.3.1·34 e2e). split 와이드 모드에서 목록의 `<h1>이슈 목록`이 문서 h1을 소유 → **상세 페인의 `<h1>`은 h2로 강등**.
- **HC-4**: `role="navigation"` aria-label 4종·`role="dialog"` 147 e2e·`검색` 라벨 단일 — 이 PR **무접촉**.

## 사용자 시나리오 (Given-When-Then)

### S1 — 와이드에서 이슈 선택 시 우측 상세 페인 등장
- **Given** 데스크탑(≥lg) 폭에서 `/issues` 목록을 본다 (선택 없음, 목록 전체폭)
- **When** 목록에서 이슈 `ATLAS-123` 행을 클릭한다
- **Then** URL이 `/issues?selected=ATLAS-123`(+기존 필터/정렬/page 쿼리 보존)로 바뀌고, 우측에 `ATLAS-123` 상세 페인이 등장하며 목록이 좌측으로 축소된다. 선택된 행이 시각적으로 강조된다.

### S2 — 선택 해제 시 전체폭 복귀
- **Given** `/issues?selected=ATLAS-123` split 상태
- **When** 상세 페인의 닫기 버튼을 누른다 (또는 강조된 행을 재클릭)
- **Then** `?selected`가 URL에서 제거되고, 상세 페인이 사라지며 목록이 전체폭으로 복귀한다.

### S3 — 딥링크로 split 진입
- **Given** 사용자가 `/issues?selected=ATLAS-123` URL을 직접 연다 (와이드)
- **When** 페이지가 로드된다
- **Then** 처음부터 좌 목록 / 우 `ATLAS-123` 상세로 렌더된다.

### S4 — 필터/정렬/페이지 변경 시 선택 보존
- **Given** `/issues?selected=ATLAS-123&status=open` split 상태
- **When** 정렬을 바꾸거나 다음 페이지로 이동한다
- **Then** `?selected=ATLAS-123`이 그대로 보존되고 상세 페인이 유지된다 (목록만 갱신).

### S5 — 좁은 화면(<lg)에서는 전체화면 이동
- **Given** 태블릿/작은 창(<lg) 폭에서 `/issues` 목록을 본다
- **When** 이슈 `ATLAS-123` 행을 클릭한다
- **Then** split을 띄우지 않고 기존 `/issues/$key` 전체화면(`/issues/ATLAS-123`)으로 이동한다.

### S6 — 페인 안에서 이동된 이슈(308 redirect)
- **Given** split 상태에서 옛 키 `OLD-1`을 선택했는데 이슈가 `NEW-1`으로 이동됨
- **When** 상세 페인이 로드되며 `IssueRedirectError`가 발생
- **Then** `?selected`가 `NEW-1`로 교체된다 (split 유지, 전체화면 이탈 안 함).

### S7 — 페인 안에서 이슈 삭제
- **Given** split 상태에서 `ATLAS-123` 상세 페인을 보는 중
- **When** 페인 안에서 이슈를 삭제한다
- **Then** `?selected`가 제거되어 페인이 닫히고 목록(전체폭)에 남는다 (목록은 삭제 반영 갱신).

### S8 — 존재하지 않는/권한 없는 selected 키
- **Given** `/issues?selected=NOPE-999`
- **When** 상세 페인이 404/403을 받음
- **Then** 상세 페인 영역에만 기존 `IssueDetailPage`의 에러 상태가 표시되고, 좌측 목록은 정상 동작 (페이지 전체가 깨지지 않음).

## 기능 요구사항 (FR — 본 PR 내부 태스크 단위, FR 대장 등록 아님)

- **PR20-F1**: `/issues` 라우트 `validateSearch`에 `selected?: string` 추가. 기존 필터/정렬/page 파라미터와 공존, 정규화가 `selected`를 삼키지 않음.
- **PR20-F2**: `IssueListRouteAdapter`가 `selected`를 읽어 (와이드) `<IssueDetailPage variant="pane" .../>`를 우측에 렌더. `selected` 없으면 목록 전체폭.
- **PR20-F3**: 반응형 레이아웃 — `≥lg` split 2컬럼(목록 축소 + 상세 페인), `<lg` 목록 단일 컬럼. **레이아웃 자체는 Tailwind 브레이크포인트(CSS)로 처리.** 목록 페인과 상세 페인은 **각자 독립 스크롤 컨테이너**(`overflow-y-auto`) — 긴 상세를 스크롤해도 목록 헤더/스크롤이 따로 논다.
- **PR20-F4**: 행 클릭 콜백(`onNavigate`)을 화면 폭 컨텍스트에 맞게 분기 — 와이드: `?selected=KEY` 설정(nav navigate, D4 준수), 좁은폭: `/issues/$key`로 이동. **레이아웃은 CSS지만 클릭 목적지 분기는 폭 값을 알아야 하므로 `matchMedia` 기반 `useMediaQuery` 훅으로 판정**(레이아웃 CSS·클릭 JS 이원 처리). **DOM/testid/aria-label 무변경**(콜백 목적지만 교체 — 네비는 IssueTable의 `onNavigate` 콜백 단일 경로, 별도 `<Link>` 앵커 없음을 실측 확인).
- **PR20-F5**: `IssueDetailPage`에 `variant?: 'page' | 'pane'` prop 추가 (기본 `'page'` = 현행 동작 무변경). `'pane'`일 때: ①제목 `<h1>`→`<h2>` 강등 ②닫기 버튼 노출(`?selected` 제거) ③리다이렉트/삭제 후 이동을 콜백(`onIssueRedirect(newKey)`·`onIssueClosed()`)으로 위임 — 기본값은 현행 fullscreen navigate ④**페인 등장 시 포커스를 페인 제목(또는 닫기 버튼)으로 이동 + `Escape` 키로 닫기**(a11y·SR 사용자에게 상세 등장 인지).
- **PR20-F6**: 선택된 행 시각 강조(ADS `--bg-selected`/`--text-selected` 토큰·`aria-current` 등 접근성 표식).

## 비기능 요구사항 (NFR)

- **NFR-1**: `variant` 미지정 시 `IssueDetailPage`·`issues.$key` 전체화면·`issues.index` 목록 단독 렌더가 **바이트 동일 수준으로 무변경** (기존 유닛/e2e 무수정 green이 무회귀 증거).
- **NFR-2**: split 전환·선택은 **브라우저 뒤로가기/앞으로가기로 되돌아감** (URL 상태라 History 자동 처리).
- **NFR-3**: 상세 페인 데이터는 `selected` 키 기준 독립 fetch — 목록 필터와 무관(필터에서 제외된 이슈도 선택 시 표시).
- **NFR-4**: 상세 페인 로딩 중 목록은 상호작용 가능(페인 로딩이 목록을 블로킹하지 않음).
- **NFR-5 (a11y)**: 페인 등장 시 포커스가 상세 영역으로 이동하고 `Escape`로 닫힌다. 선택 행은 `aria-current`로 표식. 문서 h1 단일(HC-3).

## 데이터 모델 변경

- **없음.** 백엔드 API·DTO·스키마 무변경. 순수 프론트 (기존 `fetchIssues`·`fetchIssue` 재사용).

## 엣지 케이스

- **EC-1**: `?selected` 값이 빈 문자열/공백 → 미선택으로 취급(페인 안 뜸).
- **EC-2**: 목록이 비어 있는데 `?selected=KEY` → 상세 페인만 정상 표시(목록은 빈 상태 UI).
- **EC-3**: 좁은폭→와이드 리사이즈 중 `?selected` 존재 → 와이드 되는 순간 페인 등장(CSS 반응형이면 자동). 반대 방향도 목록만 남고 URL의 `selected`는 보존(다시 넓히면 재등장) — 또는 `<lg`에서 selected 무시 표시. **결정: `<lg`에서는 `?selected`가 있어도 페인 미표시(목록만), URL은 보존**(리사이즈 왕복 시 상태 안정).
- **EC-4**: 상세 페인 안 상태전이/수정 → 목록의 해당 행도 최신값 반영(공유 react-query 캐시 `issueQueryKey` invalidate로 자동, PR19 캐시 관례).
- **EC-5**: bulk 선택 체크박스(`select-{key}`)와 split 선택(`?selected`)은 **독립** — 체크박스 클릭은 stopPropagation으로 행 네비 안 함(기존 IssueTable 계약 유지), 요약/행 클릭만 split 선택.
- **EC-6**: 같은 이슈를 재선택 → 토글로 페인 닫힘(S2) 또는 유지 — **결정: 재클릭은 닫힘(toggle off)**. 명확한 해제 경로 제공.

## 제약 조건

- **C-1 (ADR D4)**: split 선택은 라우트/URL 변경이므로 `<Link>`/navigate. Radix Tabs 사용 금지.
- **C-2 (ADR h1)**: 페인 상세 h2 강등 필수. split 와이드에서 문서 h1은 목록의 `이슈 목록` 단 하나.
- **C-3 (BC 격리)**: issue-tracking 프론트만. 백엔드/다른 BC 무접촉.
- **C-4 (e2e)**: 기존 issue 목록/상세 e2e(필터·crud·detail 탭 등) 무회귀. 신규 split e2e는 로컬 실행(CI에 e2e 잡 없음, [[frontend-ci-10min-timeout-nonrequired]]).
- **C-5 (완제품)**: PoC/임시 금지. 에러·로딩·접근성·반응형 모두 완비.

## 측정 가능한 완료 기준

1. `/issues?selected=KEY`(와이드) → 좌 목록 + 우 상세 페인, 선택 행 강조, URL 동기화. 닫기 → `?selected` 제거·전체폭 복귀. (신규 e2e)
2. `<lg`에서 행 클릭 → `/issues/$key` 전체화면 이동. (신규 e2e, 뷰포트 축소)
3. 필터/정렬/page 변경 시 `?selected` 보존. (신규 e2e 또는 유닛)
4. 문서 h1 정확히 1개(split 와이드) — `getByRole('heading', { level: 1 })` 단일. (e2e/유닛)
5. `variant` 미지정 렌더가 기존과 무변경 — `issues.$key`·`issues.index` 기존 e2e/유닛 **무수정 green**.
6. typecheck 0 · eslint 0(`eslint src`) · 관련 유닛 green · 프론트 build 0.
7. FR 총수 129 불변 (verify-master-plan 통과, D단계 마킹 변경 없어 dashboard regen 불요).

## Brainstorming Check

✅ 통과 (1회 sanity check). 발견·보강한 gap 4건.
- **G1 (a11y)**: 페인 등장 시 포커스 이동 + `Escape` 닫기 + `aria-current` 선택표식 → F5·NFR-5 추가.
- **G2 (폭 판정)**: 레이아웃은 CSS 반응형이나 "클릭 목적지(와이드 `?selected` vs 좁은폭 전체화면)" 분기는 폭 값을 알아야 함 → `useMediaQuery`(matchMedia) 훅 명시(F4).
- **G3 (네비 경로)**: 목록 행 네비가 별도 `<Link>` 앵커가 아니라 `onNavigate` 콜백 단일 경로임을 실측 확인 → 콜백 교체만으로 e2e 안전(F4).
- **G4 (스크롤)**: 목록·상세 페인 독립 스크롤 컨테이너 → F3 추가.
- Maxi 결정(D1~D3)으로 라우트 구조·페인 등장·반응형의 핵심 갈림길은 이미 봉인됨(재-loop 불요).
