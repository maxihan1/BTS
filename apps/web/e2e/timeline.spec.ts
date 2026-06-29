// FR-TL-01 D7 E2E — 타임라인 Gantt 뷰 happy path (실렌더 검증)
//
// 시나리오 개요.
//   S1. 초기 렌더  — 백로그 nav "타임라인" 클릭 → SPA 내부 이동 → Gantt 차트 표시 확인
//   S2. Epic 그룹  — BTS-1 Epic 막대 + BTS-2 자식 행/막대 실렌더 확인
//   S3. 그룹 토글  — 접기 클릭 → 자식 행 DOM 제거 → 펼치기 클릭 → 자식 복귀
//   S4. 막대 클릭  — BTS-2 막대 클릭 → 이슈 상세 /issues/BTS-2 이동
//
// 설계 결정.
//   - SPA 내부 이동: /projects/BTS/backlog → nav "타임라인" 클릭 (타임라인으로 직접 goto 금지).
//     MSW 영속 보장 (memory: fr-nt-03 / worktree-stale-base-rebase-and-e2e-msw-traps).
//     백로그 페이지 nav는 데이터 없이도 항상 렌더 (BacklogPage.tsx:59-74).
//   - MSW 시드: timeline-handlers.ts — project=BTS → BTS_TIMELINE_ITEMS 정적 반환.
//     localStorage 플래그 불필요(stateless 핸들러). auto-seed는 MODE!=='test' 게이팅.
//   - 실렌더 검증: jsdom은 막대 픽셀 좌표 미지원 → E2E 실브라우저에서만 Gantt 막대 검증
//     (memory: fr-lk-02 mermaid / fr-tt-02 recharts 실렌더 선례).
//   - 셀렉터: data-testid="gantt-chart"(GanttChart.tsx:214) + aria-label 기반.
//     strict mode 방지 — gantt container 한정 (memory: playwright-getbyrole-exact-strict-mode).
//   - 드래그 없음 (Task 8 명세).
//   - timeline-fixtures.ts / timeline-labels.ts 직접 import 금지
//     (import.meta.env.MODE 가 없어도 Node.js 런타임에서 타입 export는 안전하지만,
//      board/backlog 선례와 일관되게 상수를 인라인 동기화한다).

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — timeline-fixtures.ts / timeline-labels.ts / GanttChart.tsx와 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** BTS 프로젝트 키 — timeline-handlers.ts에서 BTS_TIMELINE_ITEMS를 반환하는 기준 */
const BTS_PROJECT_KEY = 'BTS'

/** 백로그 URL — SPA 내부 이동 전 진입점 (nav "타임라인" 링크가 항상 표시됨) */
const BACKLOG_URL = `/projects/${BTS_PROJECT_KEY}/backlog`

/** 타임라인 URL 패턴 — SPA 이동 후 waitForURL 기준 */
const TIMELINE_URL_PATTERN = `**/projects/${BTS_PROJECT_KEY}/timeline`

/**
 * BTS-1 Epic 이슈 키 — BTS_TIMELINE_ITEMS[1].key (issueType='epic').
 * Epic 그룹 헤더 레이블 행 + 막대 접근성 라벨에 포함된다.
 */
const EPIC_KEY = 'BTS-1'

/**
 * BTS-2 자식 이슈 키 — BTS_TIMELINE_ITEMS[0].key (epicKey='BTS-1').
 * 그룹 접기/펼치기 검증 대상 + 막대 클릭 이동 검증 대상.
 */
const CHILD_KEY = 'BTS-2'

/**
 * timeline-labels.ts 동기화.
 * collapseAriaLabel: '그룹 접기' (펼쳐진 상태 토글 버튼 라벨)
 * expandAriaLabel:   '그룹 펼치기' (접힌 상태 토글 버튼 라벨)
 */
const COLLAPSE_ARIA_LABEL = '그룹 접기'
const EXPAND_ARIA_LABEL = '그룹 펼치기'

/**
 * GanttChart.tsx:214 — `data-testid="gantt-chart"`.
 * 타임라인 정상 렌더 시 표시되는 루트 컨테이너.
 */
const GANTT_TESTID = 'gantt-chart'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — FR-TL-02 deps 오버레이 (DependencyOverlay.tsx / timeline-fixtures.ts / timeline-labels.ts 동기화)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * deps E2E 시나리오 토글 localStorage 키.
 * timeline-handlers.ts `E2E_TIMELINE_DEPS_SCENARIO_KEY`와 동기화.
 * 값 'truncated' → deps 핸들러가 truncated:true 반환 (S-DEPS-6 검증용).
 */
const DEPS_SCENARIO_KEY = '__bts_e2e_timeline_deps_scenario'

/**
 * BTS_TIMELINE_DEPS 엣지 1 visible path aria-label — BTS-2 blocks BTS-3.
 * `timelineLabels.deps.lineAriaLabel('BTS-2', 'BTS-3')` 반환값과 동기화.
 */
const DEPS_EDGE_1_ARIA = 'BTS-2가 BTS-3을 차단'

/**
 * BTS_TIMELINE_DEPS 엣지 2 visible path aria-label — BTS-1 blocks BTS-4.
 * `timelineLabels.deps.lineAriaLabel('BTS-1', 'BTS-4')` 반환값과 동기화.
 */
const DEPS_EDGE_2_ARIA = 'BTS-1가 BTS-4을 차단'

/**
 * deps truncated 경고 메시지 — `timelineLabels.deps.truncatedMessage`.
 * 기존 이슈 누락 배너(`timelineLabels.truncated.message`)와 문구로 구분.
 */
const DEPS_TRUNCATED_MSG = '일부 의존 라인이 생략되었습니다'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — alice 로그인 후 백로그 진입 → 타임라인 SPA 이동
//
// 모든 시나리오가 공통으로 사용하는 선행 흐름.
// 백로그 nav는 데이터 없이도 항상 렌더되므로 BTS 프로젝트 데이터 존재 여부와 무관하다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로그인 → /projects/BTS/backlog 진입 → nav "타임라인" 클릭(SPA 이동).
 * page.goto('/projects/BTS/timeline') 금지 — MSW ServiceWorker 영속 보장.
 *
 * @param page Playwright Page 객체
 */
async function loginAndNavigateToTimeline(
  page: import('@playwright/test').Page,
): Promise<void> {
  // alice 로그인 (ServiceWorker 활성화 포함)
  await loginAsAlice(page)

  // 백로그 진입 — nav는 항상 렌더됨 (BacklogPage.tsx 구조)
  await page.goto(BACKLOG_URL)

  // nav 렌더 대기 (프로젝트 뷰 전환 nav)
  const nav = page.getByRole('navigation', { name: '프로젝트 뷰 전환' })
  await expect(nav).toBeVisible()

  // "타임라인" 링크 클릭 — SPA 내부 이동 (reload 금지)
  await nav.getByRole('link', { name: '타임라인', exact: true }).click()

  // 타임라인 URL 진입 완료 대기
  await page.waitForURL(TIMELINE_URL_PATTERN)
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-TL-01 타임라인 Gantt 뷰 (happy path 실렌더)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S1. 초기 렌더 — 백로그 nav → 타임라인 SPA 이동 + Gantt 차트 표시
  //
  // Given  alice 로그인 (ServiceWorker 활성)
  //        /projects/BTS/backlog 진입 → nav 항상 렌더
  // When   nav "타임라인" 링크 클릭 (SPA 내부 이동 — reload 금지)
  // Then   URL이 /projects/BTS/timeline으로 변경됨
  //        data-testid="gantt-chart"가 화면에 표시됨
  //        BTS-1 Epic 레이블 텍스트가 차트 내에 표시됨
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 초기 렌더 — 백로그 nav "타임라인" 클릭 → Gantt 차트 + BTS-1 Epic 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 백로그 진입
    await page.goto(BACKLOG_URL)
    const nav = page.getByRole('navigation', { name: '프로젝트 뷰 전환' })
    await expect(nav).toBeVisible()

    // When. "타임라인" 링크 클릭 (SPA 내부 이동)
    await nav.getByRole('link', { name: '타임라인', exact: true }).click()

    // Then. URL 타임라인으로 변경됨
    await page.waitForURL(TIMELINE_URL_PATTERN)

    // Then. Gantt 차트 루트 컨테이너 표시 (data-testid="gantt-chart")
    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Then. BTS-1 Epic 레이블 행이 차트 내에 표시됨 (컨테이너 한정 — strict mode 방지)
    await expect(gantt.getByText(EPIC_KEY)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2. Epic 그룹 + 막대 실렌더 확인
  //
  // Given  alice 로그인, 타임라인 SPA 이동 완료
  // Then   BTS-1 Epic 레이블 행 표시
  //        BTS-2 자식 레이블 행 표시 (기본 펼쳐진 상태)
  //        BTS-1 Epic 막대(role="button", aria-label에 "BTS-1" 포함) 표시
  //        BTS-2 자식 막대 표시
  //
  // MSW 시드: BTS_TIMELINE_ITEMS — BTS-1(epic) + BTS-2(story, epicKey=BTS-1)
  // 막대 aria-label: timelineLabels.row.barAriaLabel(key, start, due) = "{key} {start} ~ {due}"
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 Epic 그룹 막대 실렌더 — BTS-1 Epic + BTS-2 자식 행/막대 표시', async ({ page }) => {
    // Given. alice 로그인 + 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Then. BTS-1 Epic 레이블 행 표시 (레이블 열 <span>{epicItem.key}</span>)
    await expect(gantt.getByText(EPIC_KEY)).toBeVisible()

    // Then. BTS-2 자식 레이블 행 표시 (기본 펼쳐진 상태 — collapsedGroups 초기값 = 빈 Set)
    await expect(gantt.getByText(CHILD_KEY)).toBeVisible()

    // Then. BTS-1 Epic 막대 표시 (role="button", aria-label에 "BTS-1" 포함)
    // TimelineRow.tsx:87 — barAriaLabel = "{key} {start} ~ {due}"
    // strict mode 방지: .first() (gantt 스코프 안에서 BTS-1 막대는 1개)
    await expect(
      gantt.getByRole('button', { name: new RegExp(EPIC_KEY) }).first(),
    ).toBeVisible()

    // Then. BTS-2 자식 막대 표시
    await expect(
      gantt.getByRole('button', { name: new RegExp(CHILD_KEY) }).first(),
    ).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3. Epic 그룹 접기/펼치기 토글
  //
  // Given  alice 로그인, 타임라인 SPA 이동
  //        BTS-1 그룹이 기본 펼쳐진 상태 → BTS-2 자식 행 표시
  // When   aria-label="그룹 접기" 버튼 클릭 (aria-expanded="true")
  // Then   BTS-2 자식 레이블 행이 DOM에서 제거됨
  //        토글 버튼 aria-label이 "그룹 펼치기"로 변경됨 (aria-expanded="false")
  // When   aria-label="그룹 펼치기" 버튼 클릭
  // Then   BTS-2 자식 레이블 행 복귀
  //
  // GanttChart.tsx:100 — {!isCollapsed && childItems.map(...)} 조건부 렌더로 DOM 제거.
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 그룹 접기/펼치기 토글 — BTS-1 접기 → BTS-2 제거 → 펼치기 → BTS-2 복귀', async ({ page }) => {
    // Given. alice 로그인 + 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Given. BTS-2 자식 행 기본 표시 확인
    await expect(gantt.getByText(CHILD_KEY)).toBeVisible()

    // Given. "그룹 접기" 버튼 확인 — 펼쳐진 상태: aria-expanded=true
    // GanttChart.tsx:84-88 — aria-expanded={!isCollapsed}, aria-label={collapseAriaLabel}
    const collapseButton = gantt.getByRole('button', {
      name: COLLAPSE_ARIA_LABEL,
      exact: true,
    })
    await expect(collapseButton).toBeVisible()
    await expect(collapseButton).toHaveAttribute('aria-expanded', 'true')

    // When. "그룹 접기" 클릭
    await collapseButton.click()

    // Then. BTS-2 자식 레이블 행 DOM에서 제거됨 (not.toBeVisible = 미존재 포함)
    await expect(gantt.getByText(CHILD_KEY)).not.toBeVisible()

    // Then. 토글 버튼이 "그룹 펼치기"로 변경됨 (aria-expanded=false)
    const expandButton = gantt.getByRole('button', {
      name: EXPAND_ARIA_LABEL,
      exact: true,
    })
    await expect(expandButton).toBeVisible()
    await expect(expandButton).toHaveAttribute('aria-expanded', 'false')

    // When. "그룹 펼치기" 클릭
    await expandButton.click()

    // Then. BTS-2 자식 레이블 행 복귀
    await expect(gantt.getByText(CHILD_KEY)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4. 막대 클릭 → 이슈 상세 이동
  //
  // Given  alice 로그인, 타임라인 SPA 이동
  //        BTS-2 자식 막대 표시
  // When   BTS-2 막대(role="button", aria-label에 "BTS-2" 포함) 클릭
  // Then   URL이 /issues/BTS-2로 변경됨
  //        (TimelinePage.handleSelectIssue → navigate({ to: '/issues/$key', params: { key } }))
  // ─────────────────────────────────────────────────────────────────────────
  test('S4 막대 클릭 → 이슈 상세 이동 — BTS-2 막대 클릭 → /issues/BTS-2', async ({ page }) => {
    // Given. alice 로그인 + 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Given. BTS-2 막대 표시 확인 (role="button", aria-label에 "BTS-2" 포함)
    const childBar = gantt
      .getByRole('button', { name: new RegExp(CHILD_KEY) })
      .first()
    await expect(childBar).toBeVisible()

    // When. BTS-2 막대 클릭
    await childBar.click()

    // Then. 이슈 상세 URL로 이동됨
    // TimelinePage.tsx:205-207 — handleSelectIssue → navigate({ to: '/issues/$key', params: { key } })
    await page.waitForURL(`**/issues/${CHILD_KEY}`)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-TL-02 의존 라인 오버레이 (D6/D7 실렌더 + 클릭 강조 + 무회귀)
//
// 시나리오 개요.
//   S-DEPS-1. 의존 라인 실렌더   — BTS 타임라인 진입 → elbow path ≥1개 DOM 존재
//                                   d 속성에 H/V 포함 (직각 경로 확인)
//   S-DEPS-2. 클릭 강조          — hit-path 클릭 → data-selected + 나머지 data-dimmed
//   S-DEPS-3. 강조 해제          — 선택 후 배경(dep-overlay-bg) 클릭 → 모두 기본 상태
//   S-DEPS-P1. 막대 클릭 통과   — deps 미선택 시 Gantt 막대 클릭 → 이슈 상세 이동
//                                   (P1 hot-fix: 오버레이 배경 rect가 막대 클릭을 차단하지 않음)
//   S-DEPS-6. deps truncated 경고 — localStorage 플래그 시드 → 누락 경고 배너 표시
//
// 설계 결정.
//   - 기존 S1~S4(FR-TL-01)는 BTS MSW 픽스처 기준 — deps가 추가 렌더돼도 무회귀.
//   - hit-path(stroke="transparent", pointer-events:all) 클릭 시 force:true 사용.
//     부모 SVG/g의 pointer-events:none이 Playwright actionability check를 막기 때문.
//     force:true는 해당 요소에 이벤트를 직접 dispatch → React onClick 정상 수신.
//   - 배경 rect(dep-overlay-bg) 역시 force:true — 부모 SVG pointer-events:none 우회.
//   - data-selected/data-dimmed는 visible path(aria-label 기반) 속성으로 검증.
//   - deps E2E 시나리오 토글: addInitScript → loginAsAlice(goto 포함) 전에 등록
//     (memory: e2e-msw-scenario-toggle-localstorage-flag).
//   - SPA 내부 이동 유지 (MSW ServiceWorker 영속) — reload 금지
//     (memory: worktree-stale-base-rebase-and-e2e-msw-traps).
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-TL-02 타임라인 의존 라인 오버레이 (D6/D7 실렌더 + 클릭 강조)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S-DEPS-1. 의존 라인 실렌더
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동
  //        BTS MSW deps 픽스처 자동 시드 (BTS-2→BTS-3, BTS-1→BTS-4)
  // When   Gantt 차트 렌더 완료
  // Then   BTS-2→BTS-3 의존 라인 visible path DOM 표시
  //        d 속성에 H/V 포함 — elbow 직각 경로 (사선 금지, plan ADR)
  // ─────────────────────────────────────────────────────────────────────────
  test('S-DEPS-1 의존 라인 실렌더 — elbow path 렌더 + d 속성 H/V 포함', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Then. BTS-2→BTS-3 의존 라인 visible path DOM 존재
    // DependencyOverlay.tsx: visible path에 aria-label={timelineLabels.deps.lineAriaLabel(...)}
    // 주의: SVG path는 두 막대가 동일 x에 위치하면 수평 폭=0(수직선)이 되어 Playwright가
    //        "hidden"으로 판정한다 (bounding box 폭 0). toBeAttached()로 DOM 존재 확인.
    const edge1 = gantt.locator(`[aria-label="${DEPS_EDGE_1_ARIA}"]`)
    await expect(edge1).toBeAttached()

    // Then. d 속성에 H/V 포함 — 직각 elbow 경로 단언
    // DependencyOverlay.tsx: `M startX,startY+offset H midX V endY+offset H endX`
    const d = await edge1.getAttribute('d')
    expect(d).toContain('H')
    expect(d).toContain('V')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-DEPS-2. 클릭 강조 토글
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동
  //        의존 라인 ≥1개 렌더됨, 선택 없는 초기 상태 (selectedKey=null)
  // When   첫 번째 엣지(BTS-2→BTS-3) hit-path 클릭
  // Then   해당 visible path에 data-selected="true"
  //        다른 엣지 visible path에 data-dimmed="true"
  // ─────────────────────────────────────────────────────────────────────────
  test('S-DEPS-2 클릭 강조 — 엣지 클릭 → data-selected + 나머지 data-dimmed', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    const edge1 = gantt.locator(`[aria-label="${DEPS_EDGE_1_ARIA}"]`)
    const edge2 = gantt.locator(`[aria-label="${DEPS_EDGE_2_ARIA}"]`)

    // Given. 초기 상태 확인 — data-selected/data-dimmed 없음
    // SVG path 폭이 0일 수 있어 toBeAttached()로 DOM 존재 확인 (toBeVisible() 대신)
    await expect(edge1).toBeAttached()
    await expect(edge1).not.toHaveAttribute('data-selected', 'true')
    await expect(edge2).not.toHaveAttribute('data-dimmed', 'true')

    // When. edge1(BTS-2→BTS-3) hit-path에 직접 click 이벤트 dispatch
    // 두 dep 라인이 같은 x 좌표(수직선)여서 Playwright force:true 좌표 클릭 시
    // DOM 상위(나중 렌더) hit-path가 좌표 우선권을 가져가는 문제 회피.
    // page.evaluate로 target element에 직접 dispatchEvent → React onClick 수신.
    await page.evaluate((ariaLabel: string) => {
      const visible = document.querySelector(`[aria-label="${ariaLabel}"]`)
      if (!visible?.parentElement) throw new Error(`dep group not found: ${ariaLabel}`)
      const hitPath = visible.parentElement.querySelector('path[stroke="transparent"]')
      if (!hitPath) throw new Error('hit path not found in group')
      hitPath.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    }, DEPS_EDGE_1_ARIA)

    // Then. 엣지 1 선택 강조, 엣지 2 흐림 처리
    await expect(edge1).toHaveAttribute('data-selected', 'true')
    await expect(edge2).toHaveAttribute('data-dimmed', 'true')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-DEPS-3. 강조 해제 — 배경 클릭 → 모두 기본 상태
  //
  // Given  S-DEPS-2와 동일하게 엣지 1 선택 상태
  //        dep-overlay-bg(배경 rect) pointer-events:all (selectedKey !== null 조건)
  // When   dep-overlay-bg 클릭
  // Then   data-selected/data-dimmed 모두 해제 (selectedKey=null)
  // ─────────────────────────────────────────────────────────────────────────
  test('S-DEPS-3 강조 해제 — 선택 후 배경 클릭 → 모두 기본 상태', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    const edge1 = gantt.locator(`[aria-label="${DEPS_EDGE_1_ARIA}"]`)
    const edge2 = gantt.locator(`[aria-label="${DEPS_EDGE_2_ARIA}"]`)

    // Given. 엣지 1 선택 상태로 전환 (S-DEPS-2와 동일 — page.evaluate 방식)
    // SVG path는 toBeAttached()로 DOM 존재 확인 (bounding box 폭 0 → toBeVisible() 오작동)
    await expect(edge1).toBeAttached()
    await page.evaluate((ariaLabel: string) => {
      const visible = document.querySelector(`[aria-label="${ariaLabel}"]`)
      if (!visible?.parentElement) throw new Error(`dep group not found: ${ariaLabel}`)
      const hitPath = visible.parentElement.querySelector('path[stroke="transparent"]')
      if (!hitPath) throw new Error('hit path not found in group')
      hitPath.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    }, DEPS_EDGE_1_ARIA)
    await expect(edge1).toHaveAttribute('data-selected', 'true')

    // When. 배경 클릭 → 선택 해제
    // dep-overlay-bg: selectedKey !== null 시 pointer-events:all (DependencyOverlay.tsx)
    // force:true — 부모 SVG pointer-events:none actionability check 우회
    await gantt.getByTestId('dep-overlay-bg').click({ force: true })

    // Then. data-selected/data-dimmed 모두 해제됨
    await expect(edge1).not.toHaveAttribute('data-selected', 'true')
    await expect(edge2).not.toHaveAttribute('data-dimmed', 'true')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-DEPS-P1. 막대 클릭 통과 (P1 hot-fix 회귀 가드)
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동
  //        의존 라인 렌더됨 (dep overlay SVG가 Gantt 위에 존재)
  //        deps 선택 없는 초기 상태 (selectedKey=null → 배경 rect pointer-events:none)
  // When   BTS-2 Gantt 막대 클릭 (dep overlay 통과)
  // Then   /issues/BTS-2 이동 성공
  //        SVG 오버레이 + 배경 rect가 막대 클릭을 차단하지 않음
  // ─────────────────────────────────────────────────────────────────────────
  test('S-DEPS-P1 막대 클릭 통과 — deps 미선택 시 Gantt 막대 클릭 → 이슈 상세 이동', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Given. 의존 라인 렌더 확인 — dep overlay가 실제로 존재하는 상태에서 테스트
    // SVG path는 toBeAttached()로 DOM 존재 확인 (bounding box 폭 0 → toBeVisible() 오작동)
    await expect(gantt.locator(`[aria-label="${DEPS_EDGE_1_ARIA}"]`)).toBeAttached()

    // Given. BTS-2 막대 표시 확인 (deps 미선택 초기 상태)
    const childBar = gantt.getByRole('button', { name: new RegExp(CHILD_KEY) }).first()
    await expect(childBar).toBeVisible()

    // When. BTS-2 막대 클릭 (dep overlay 통과 — P1 hot-fix 검증)
    // selectedKey=null → 배경 rect pointer-events:none → 막대 클릭 통과
    await childBar.click()

    // Then. 이슈 상세 URL 이동 성공 (오버레이가 막대 클릭을 차단하지 않음)
    await page.waitForURL(`**/issues/${CHILD_KEY}`)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-DEPS-6. deps truncated 경고 배너
  //
  // Given  alice 로그인 전 addInitScript로 __bts_e2e_timeline_deps_scenario='truncated' 시드
  //        MSW deps 핸들러 → deps 배열 + truncated:true 반환
  //        BTS 타임라인 SPA 이동 완료
  // Then   DepsTruncatedBanner 표시 ("일부 의존 라인이 생략되었습니다")
  //        기존 이슈 누락 배너(TruncatedBanner)와 문구로 구분
  // ─────────────────────────────────────────────────────────────────────────
  test('S-DEPS-6 deps truncated 경고 — 시나리오 플래그 시드 → 누락 경고 배너 표시', async ({ page }) => {
    // Given. deps truncated 시나리오 플래그 등록 (goto 전 — loginAsAlice 내 goto에도 적용)
    // addInitScript는 이후 모든 페이지 로드에서 실행 (memory: e2e-msw-scenario-toggle-localstorage-flag)
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'truncated')
    }, DEPS_SCENARIO_KEY)

    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Then. DepsTruncatedBanner 표시 (TimelinePage.tsx: depsData?.truncated === true)
    await expect(page.getByText(DEPS_TRUNCATED_MSG)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-DEPS-REALCLICK. 수평 엣지 hit-path 실클릭 강조 (C-4 concern-fix)
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동
  //        BTS-1 blocks BTS-4 엣지 — 수평 폭이 있는 케이스
  //        startX (BTS-1 dueDate=2026-09-30 우끝) ≠ endX (BTS-4 startDate=2026-07-15 좌끝)
  //        elbow 경로: M startX,BTS1_y H midX V BTS4_y H endX
  //        bounding box 중심 (midX, (BTS1_y+BTS4_y)/2) 이 수직 세그먼트 위에 위치
  //        S-DEPS-2/3 는 BTS-2→BTS-3(수직선, startX≈endX)을 dispatchEvent로 커버 —
  //        해당 케이스는 수직선으로 bounding box 폭이 0에 가까워 Playwright 실클릭 곤란.
  //        BTS-1→BTS-4 는 수평 폭이 크므로 Playwright 실클릭으로 hit-path 경로 검증 가능.
  // When   BTS-1→BTS-4 hit-path를 Playwright .click() 실행 (force 없음, dispatchEvent 없음)
  //        SVG child pointer-events:all 이 부모 SVG/g pointer-events:none 불구 hit-test 통과.
  //        midX = (startX+endX)/2 가 bounding box center_x — 수직 세그먼트 위라
  //        elementFromPoint(midX, center_y) 가 BTS-1→BTS-4 hit-path를 반환.
  //        BTS-2→BTS-3 midX ≈ 2026-08-01 위치 vs BTS-1→BTS-4 midX ≈ 2026-08-22 위치 — 겹침 없음.
  // Then   BTS-1→BTS-4 visible path에 data-selected="true"
  //        BTS-2→BTS-3 visible path에 data-dimmed="true"
  //        (라인 선택 hit-path 실클릭성 검증 — pointer-events 메커니즘 실 브라우저 경로 확인)
  // ─────────────────────────────────────────────────────────────────────────
  test('S-DEPS-REALCLICK 수평 엣지 hit-path 실클릭 — BTS-1→BTS-4 .click() → data-selected', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Given. 두 엣지 DOM 존재 확인 (초기 미선택 상태)
    // visible path 는 bounding box 가 0일 수 있어 toBeAttached() 로 DOM 확인 (S-DEPS-1 선례)
    const edge1 = gantt.locator(`[aria-label="${DEPS_EDGE_1_ARIA}"]`)
    const edge2 = gantt.locator(`[aria-label="${DEPS_EDGE_2_ARIA}"]`)
    await expect(edge1).toBeAttached()
    await expect(edge2).toBeAttached()
    await expect(edge2).not.toHaveAttribute('data-selected', 'true')

    // When. BTS-1→BTS-4 hit-path 실클릭 (force 없음)
    // DependencyOverlay.tsx: visible path 와 같은 <g> 안에 <path stroke="transparent" pointerEvents="all">
    // CSS :has() 셀렉터로 aria-label 기준 부모 <g> 를 특정 후 투명 hit-path 에 접근 (strict mode 안전)
    const edge2HitPath = gantt.locator(
      `g:has([aria-label="${DEPS_EDGE_2_ARIA}"]) path[stroke="transparent"]`,
    )
    await expect(edge2HitPath).toBeAttached()
    await edge2HitPath.click()

    // Then. BTS-1→BTS-4 엣지 선택 강조 (selectedKey = "BTS-1__BTS-4")
    await expect(edge2).toHaveAttribute('data-selected', 'true')
    // Then. BTS-2→BTS-3 엣지 흐림 처리 (selectedKey !== null && !isSelected)
    await expect(edge1).toHaveAttribute('data-dimmed', 'true')
  })
})
