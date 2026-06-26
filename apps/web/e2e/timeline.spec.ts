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
