// FR-TL-03 D7 E2E — 타임라인 줌 레벨 컨트롤 (세그먼트·확대축소·단축키·localStorage 영속)
//
// 시나리오 개요.
//   S-ZOOM-1. 세그먼트 버튼     — '분기' 클릭 → aria-pressed + 분기 축 Q 텍스트 등장 / '주' 클릭 → 확대
//   S-ZOOM-2. +/− 버튼          — 끝단 disabled 검증 (week=+disabled, quarter=−disabled)
//   S-ZOOM-3. 키보드 단축키     — '1'=주/'2'=월/'3'=분기 aria-pressed 전환
//   S-ZOOM-4. localStorage 영속 — '주' 선택 후 page.reload() → 새로고침 뒤 '주' 복원
//   S-ZOOM-5. deps 라인 정합    — 줌 변경 후 의존 라인 DOM 유지
//
// 설계 결정.
//   - SPA 내부 이동: /projects/BTS/backlog → nav "타임라인" 클릭 (직접 goto 금지).
//     MSW ServiceWorker 영속 보장 (memory: worktree-stale-base-rebase-and-e2e-msw-traps).
//   - localStorage 초기화: loginAsAlice → page.evaluate 로 removeItem (각 테스트 격리).
//     addInitScript 미사용 — reload 시에도 같이 실행되어 S-ZOOM-4 영속 검증을 깨기 때문.
//   - S-ZOOM-4 reload 정당성: timeline-zoom 은 localStorage 영속 (MSW mutation state 아님).
//     reload 후에도 localStorage는 유지되므로 reload=가짜그린 함정과 무관
//     (memory: msw-mutation-stateful-refetch 는 mutation 영속 문제, 여기선 해당 없음).
//   - 분기 축 'Q' 텍스트: BTS 픽스처 날짜(2026-07~10)가 Q3/Q4 범위 → "2026 Q3" 등장.
//     TimelineAxis.tsx computeQuarterTicks: label=`${year} Q${quarter}`.
//   - 셀렉터 안정성: zoom control은 role="group" + aria-label="타임라인 줌 레벨" 컨테이너
//     내에서 한정 → 페이지 내 다른 '주'/'월' 텍스트와 strict mode 충돌 방지
//     (memory: playwright-getbyrole-exact-strict-mode).
//   - 키보드 단축키: page.keyboard.press('1') — window keydown 리스너가 수신
//     (use-timeline-zoom.ts 단축키 맵: 1=week / 2=month / 3=quarter).
//   - deps 라인: bounding box 폭이 0일 수 있어 toBeAttached() 로 DOM 존재 확인
//     (FR-TL-02 S-DEPS-1 선례).

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — timeline-labels.ts / use-timeline-zoom.ts / timeline-fixtures.ts 동기화
// ─────────────────────────────────────────────────────────────────────────────

const BTS_PROJECT_KEY = 'BTS'

/** 백로그 URL — SPA 내부 이동 전 진입점 */
const BACKLOG_URL = `/projects/${BTS_PROJECT_KEY}/backlog`

/** 타임라인 URL 패턴 — SPA 이동 후 waitForURL 기준 */
const TIMELINE_URL_PATTERN = `**/projects/${BTS_PROJECT_KEY}/timeline`

/**
 * 줌 컨트롤 그룹 aria-label.
 * TimelineZoomControl.tsx: role="group" aria-label={timelineLabels.zoom.groupAriaLabel}
 * timeline-labels.ts: groupAriaLabel = '타임라인 줌 레벨'
 */
const ZOOM_GROUP_ARIA_LABEL = '타임라인 줌 레벨'

/** 세그먼트 버튼 텍스트 — timeline-labels.ts 동기화 */
const ZOOM_LABEL_WEEK = '주'
const ZOOM_LABEL_MONTH = '월'
const ZOOM_LABEL_QUARTER = '분기'

/**
 * + 버튼 aria-label — timeline-labels.ts: zoomInAriaLabel = '확대'.
 * 확대 끝단(week)에서 disabled.
 */
const ZOOM_IN_ARIA_LABEL = '확대'

/**
 * − 버튼 aria-label — timeline-labels.ts: zoomOutAriaLabel = '축소'.
 * 축소 끝단(quarter)에서 disabled.
 */
const ZOOM_OUT_ARIA_LABEL = '축소'

/**
 * GanttChart 루트 컨테이너 testid.
 * GanttChart.tsx: data-testid="gantt-chart"
 */
const GANTT_TESTID = 'gantt-chart'

/**
 * localStorage 줌 영속 키.
 * use-timeline-zoom.ts: STORAGE_KEY = 'timeline-zoom'
 */
const ZOOM_STORAGE_KEY = 'timeline-zoom'

/**
 * 분기 축 눈금 텍스트 패턴.
 * TimelineAxis.tsx computeQuarterTicks: label=`${year} Q${quarter}`
 * BTS 픽스처 날짜(2026-07-01 ~ 2026-10-31) → Q3(Jul-Sep) / Q4(Oct) 범위 포함.
 */
const QUARTER_AXIS_PATTERN = /2026 Q\d/

/**
 * BTS-2→BTS-3 의존 라인 aria-label — S-ZOOM-5 deps 정합 검증.
 * timeline-labels.ts: lineAriaLabel('BTS-2', 'BTS-3') = 'BTS-2가 BTS-3을 차단'
 */
const DEPS_EDGE_ARIA = 'BTS-2가 BTS-3을 차단'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — alice 로그인 후 localStorage 초기화 → 백로그 진입 → 타임라인 SPA 이동
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로그인 → localStorage timeline-zoom 초기화 → /projects/BTS/backlog 진입
 * → nav "타임라인" 클릭(SPA 이동).
 *
 * localStorage 초기화는 page.evaluate 로 removeItem — addInitScript 미사용.
 * addInitScript 는 reload 시에도 재실행되므로 S-ZOOM-4 영속 검증을 깨뜨린다.
 *
 * @param page Playwright Page 객체
 */
async function loginAndNavigateToTimeline(
  page: import('@playwright/test').Page,
): Promise<void> {
  // alice 로그인 (ServiceWorker 활성화 포함)
  await loginAsAlice(page)

  // localStorage 초기화 — 이전 테스트의 timeline-zoom 누수 방지
  // page.evaluate 는 1회성 실행이므로 reload 시 재실행되지 않는다
  await page.evaluate((key: string) => localStorage.removeItem(key), ZOOM_STORAGE_KEY)

  // 백로그 진입 — nav는 항상 렌더됨
  await page.goto(BACKLOG_URL)
  const nav = page.getByRole('navigation', { name: '프로젝트 뷰 전환' })
  await expect(nav).toBeVisible()

  // "타임라인" 링크 클릭 — SPA 내부 이동 (reload 금지)
  await nav.getByRole('link', { name: '타임라인', exact: true }).click()

  // 타임라인 URL 진입 완료 대기
  await page.waitForURL(TIMELINE_URL_PATTERN)
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite — FR-TL-03 줌 레벨 컨트롤
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-TL-03 타임라인 줌 레벨 컨트롤 (세그먼트·확대축소·단축키·localStorage 영속)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S-ZOOM-1. 세그먼트 버튼 — 분기 클릭 → aria-pressed + Q 텍스트 등장 / 주 클릭 → 확대
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동 완료 (localStorage 초기화)
  //        기본 줌 = '월' (parseZoomLevel null → DEFAULT_ZOOM='month')
  //        줌 컨트롤 그룹(role="group", aria-label="타임라인 줌 레벨") 표시
  // When   '분기' 세그먼트 버튼 클릭
  // Then   '분기' 버튼 aria-pressed="true"
  //        '월' 버튼 aria-pressed="false"
  //        Gantt 차트 영역에 분기 축 눈금 텍스트("2026 Q3" 등) 등장
  // When   '주' 세그먼트 버튼 클릭
  // Then   '주' 버튼 aria-pressed="true"
  //        분기 축 텍스트 사라짐 (week zoom → 월/일 축, Q 레이블 없음)
  // ─────────────────────────────────────────────────────────────────────────
  test('S-ZOOM-1 세그먼트 버튼 — 분기 클릭 → aria-pressed + Q 축 텍스트 등장 / 주 클릭 → 확대', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    // Given. 줌 컨트롤 그룹 확인 — 기본 '월' 활성
    const zoomControl = page.getByRole('group', { name: ZOOM_GROUP_ARIA_LABEL })
    await expect(zoomControl).toBeVisible()

    const monthBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_MONTH, exact: true })
    const quarterBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_QUARTER, exact: true })
    const weekBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_WEEK, exact: true })

    // Given. 기본 '월' aria-pressed="true" 확인
    await expect(monthBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(quarterBtn).toHaveAttribute('aria-pressed', 'false')

    // When. '분기' 버튼 클릭
    await quarterBtn.click()

    // Then. '분기' 활성, '월' 비활성
    await expect(quarterBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(monthBtn).toHaveAttribute('aria-pressed', 'false')
    await expect(weekBtn).toHaveAttribute('aria-pressed', 'false')

    // Then. 분기 축 눈금 텍스트 등장 — TimelineAxis computeQuarterTicks label=`${year} Q${quarter}`
    // BTS 픽스처: BTS-1(2026-07-01 ~ 2026-09-30) → 범위에 Q3(7월 1일) 포함
    // getByText 는 다중 일치 허용, .first() 로 strict mode 우회
    await expect(gantt.getByText(QUARTER_AXIS_PATTERN).first()).toBeVisible()

    // When. '주' 버튼 클릭 → 확대
    await weekBtn.click()

    // Then. '주' 활성
    await expect(weekBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(quarterBtn).toHaveAttribute('aria-pressed', 'false')

    // Then. 분기 축 텍스트 사라짐 (week zoom: top=month YYYY.MM, bottom=day DD — Q 없음)
    await expect(gantt.getByText(QUARTER_AXIS_PATTERN).first()).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-ZOOM-2. +/− 버튼 — 끝단 disabled 검증
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동 완료 (기본 '월')
  // When   '−'(축소) 버튼 클릭
  // Then   '분기' 활성, '−' 버튼 disabled (최대 축소 끝단 = quarter)
  //        '+' 버튼 NOT disabled
  // When   '+'(확대) 버튼 클릭
  // Then   '월' 활성 (중간 레벨 = 끝단 아님)
  //        '−'/'+' 모두 NOT disabled
  // When   '주' 세그먼트 버튼 클릭
  // Then   '주' 활성, '+' 버튼 disabled (최대 확대 끝단 = week)
  //        '−' 버튼 NOT disabled
  // ─────────────────────────────────────────────────────────────────────────
  test('S-ZOOM-2 +/− 버튼 — 끝단 disabled 검증 (quarter=−disabled, week=+disabled)', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    const zoomControl = page.getByRole('group', { name: ZOOM_GROUP_ARIA_LABEL })
    await expect(zoomControl).toBeVisible()

    const weekBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_WEEK, exact: true })
    const monthBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_MONTH, exact: true })
    const quarterBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_QUARTER, exact: true })
    const zoomInBtn = zoomControl.getByRole('button', { name: ZOOM_IN_ARIA_LABEL, exact: true })
    const zoomOutBtn = zoomControl.getByRole('button', { name: ZOOM_OUT_ARIA_LABEL, exact: true })

    // Given. 기본 '월' 확인, 양쪽 버튼 활성
    await expect(monthBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(zoomOutBtn).not.toBeDisabled()
    await expect(zoomInBtn).not.toBeDisabled()

    // When. '−'(축소) 버튼 클릭 → '분기'
    await zoomOutBtn.click()

    // Then. '분기' 활성, '−' disabled (끝단 = quarter)
    await expect(quarterBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(zoomOutBtn).toBeDisabled()
    await expect(zoomInBtn).not.toBeDisabled()

    // When. '+'(확대) 버튼 클릭 → '월'
    await zoomInBtn.click()

    // Then. '월' 활성, 양쪽 버튼 활성 (중간 레벨)
    await expect(monthBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(zoomOutBtn).not.toBeDisabled()
    await expect(zoomInBtn).not.toBeDisabled()

    // When. '주' 세그먼트 버튼 직접 클릭 → 최대 확대
    await weekBtn.click()

    // Then. '주' 활성, '+' disabled (끝단 = week)
    await expect(weekBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(zoomInBtn).toBeDisabled()
    await expect(zoomOutBtn).not.toBeDisabled()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-ZOOM-3. 키보드 단축키 — '1'=주/'2'=월/'3'=분기 aria-pressed 전환
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동 완료 (기본 '월')
  // When   키보드 '1' 입력 (window keydown → use-timeline-zoom KEY_ZOOM_MAP)
  // Then   '주' 버튼 aria-pressed="true"
  // When   키보드 '3' 입력
  // Then   '분기' 버튼 aria-pressed="true"
  // When   키보드 '2' 입력
  // Then   '월' 버튼 aria-pressed="true"
  //
  // 키 핸들러: use-timeline-zoom.ts window.addEventListener('keydown', ...)
  // isEditableTarget 체크: body가 포커스인 경우 무시하지 않음(편집 불가 요소)
  // ─────────────────────────────────────────────────────────────────────────
  test('S-ZOOM-3 키보드 단축키 — 1=주/3=분기/2=월 aria-pressed 전환', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    const zoomControl = page.getByRole('group', { name: ZOOM_GROUP_ARIA_LABEL })
    await expect(zoomControl).toBeVisible()

    const weekBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_WEEK, exact: true })
    const monthBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_MONTH, exact: true })
    const quarterBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_QUARTER, exact: true })

    // Given. 기본 '월' 확인
    await expect(monthBtn).toHaveAttribute('aria-pressed', 'true')

    // 포커스를 편집 불가 요소로 확보 — 줌 컨트롤 그룹 클릭
    // use-timeline-zoom.ts isEditableTarget: input/textarea/contenteditable 제외
    // → 일반 div 클릭 후 body 포커스 상태에서 window keydown 리스너 수신
    await gantt.click({ position: { x: 10, y: 10 }, force: true })

    // When. '1' 키 입력 → week
    await page.keyboard.press('1')

    // Then. '주' 활성
    await expect(weekBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(monthBtn).toHaveAttribute('aria-pressed', 'false')

    // When. '3' 키 입력 → quarter
    await page.keyboard.press('3')

    // Then. '분기' 활성
    await expect(quarterBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(weekBtn).toHaveAttribute('aria-pressed', 'false')

    // When. '2' 키 입력 → month
    await page.keyboard.press('2')

    // Then. '월' 활성
    await expect(monthBtn).toHaveAttribute('aria-pressed', 'true')
    await expect(quarterBtn).toHaveAttribute('aria-pressed', 'false')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-ZOOM-4. localStorage 영속 — '주' 선택 후 page.reload() → '주' 복원
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동 완료 (localStorage 초기화 → 기본 '월')
  // When   '주' 세그먼트 버튼 클릭 (localStorage 'timeline-zoom'='week' 저장)
  // When   page.reload() — localStorage 유지, MSW ServiceWorker 지속
  // Then   Gantt 차트 재렌더 완료 (MSW 핸들러 BTS_TIMELINE_ITEMS 정적 반환)
  //        '주' 버튼 aria-pressed="true" (useTimelineZoom useState 초기값 localStorage 읽기)
  //
  // 이 시나리오에서 page.reload()는 정당하다.
  // timeline-zoom은 localStorage 영속 — MSW mutation state 아님.
  // reload 후에도 localStorage 값은 유지되고, useState 초기화 함수가 재실행된다.
  // MSW ServiceWorker는 이미 등록된 상태이므로 reload 후 데이터 요청을 처리한다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S-ZOOM-4 localStorage 영속 — 주 선택 후 reload → 주 복원', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동 (localStorage 초기화 포함)
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    const zoomControl = page.getByRole('group', { name: ZOOM_GROUP_ARIA_LABEL })
    await expect(zoomControl).toBeVisible()

    const weekBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_WEEK, exact: true })

    // Given. 기본 '월' 확인
    const monthBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_MONTH, exact: true })
    await expect(monthBtn).toHaveAttribute('aria-pressed', 'true')

    // When. '주' 버튼 클릭 → localStorage 'timeline-zoom'='week' 저장
    await weekBtn.click()
    await expect(weekBtn).toHaveAttribute('aria-pressed', 'true')

    // localStorage 값 저장 확인 — 영속 검증의 전제 조건
    const storedValue = await page.evaluate((key: string) => localStorage.getItem(key), ZOOM_STORAGE_KEY)
    expect(storedValue).toBe('week')

    // When. 페이지 리로드 — localStorage 유지, MSW ServiceWorker 지속
    await page.reload()

    // Then. Gantt 차트 재렌더 완료 대기 (MSW BTS_TIMELINE_ITEMS 정적 응답)
    // reload 후 로딩 스켈레톤이 표시되다가 데이터 로드 완료 후 gantt-chart 등장
    const ganttAfterReload = page.getByTestId(GANTT_TESTID)
    await expect(ganttAfterReload).toBeVisible()

    // Then. 줌 컨트롤 '주' 복원 — useTimelineZoom useState 초기값이 localStorage를 읽음
    // parseZoomLevel('week') = 'week' → '주' 버튼 aria-pressed="true"
    const zoomControlAfterReload = page.getByRole('group', { name: ZOOM_GROUP_ARIA_LABEL })
    await expect(zoomControlAfterReload).toBeVisible()

    const weekBtnAfterReload = zoomControlAfterReload.getByRole('button', {
      name: ZOOM_LABEL_WEEK,
      exact: true,
    })
    await expect(weekBtnAfterReload).toHaveAttribute('aria-pressed', 'true')

    const monthBtnAfterReload = zoomControlAfterReload.getByRole('button', {
      name: ZOOM_LABEL_MONTH,
      exact: true,
    })
    await expect(monthBtnAfterReload).toHaveAttribute('aria-pressed', 'false')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S-ZOOM-5. deps 라인 정합 — 줌 변경 후 의존 라인 DOM 유지
  //
  // Given  alice 로그인, BTS 타임라인 SPA 이동 완료 (기본 '월')
  //        BTS MSW deps 픽스처 자동 시드 (BTS-2→BTS-3, BTS-1→BTS-4)
  //        BTS-2→BTS-3 의존 라인 DOM 존재 확인
  // When   '분기' 세그먼트 버튼 클릭 (줌 변경)
  // Then   BTS-2→BTS-3 의존 라인 DOM 여전히 존재 (줌 변경으로 사라지지 않음)
  //        (svg path는 bounding box 폭이 0일 수 있어 toBeAttached()로 DOM 존재 확인)
  // When   '주' 세그먼트 버튼 클릭
  // Then   의존 라인 DOM 여전히 존재
  //
  // FR-TL-02 기존 E2E(S-DEPS-1) 선례: SVG path는 toBeAttached()로 확인.
  // ─────────────────────────────────────────────────────────────────────────
  test('S-ZOOM-5 deps 라인 정합 — 줌 변경 후 의존 라인 DOM 유지', async ({ page }) => {
    // Given. alice 로그인 + BTS 타임라인 SPA 이동
    await loginAndNavigateToTimeline(page)

    const gantt = page.getByTestId(GANTT_TESTID)
    await expect(gantt).toBeVisible()

    const zoomControl = page.getByRole('group', { name: ZOOM_GROUP_ARIA_LABEL })
    await expect(zoomControl).toBeVisible()

    const quarterBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_QUARTER, exact: true })
    const weekBtn = zoomControl.getByRole('button', { name: ZOOM_LABEL_WEEK, exact: true })

    // Given. BTS-2→BTS-3 의존 라인 DOM 존재 확인 (초기 '월' 줌)
    // visible path는 SVG path — bounding box 폭이 0일 수 있어 toBeAttached() 사용 (S-DEPS-1 선례)
    const depsEdge = gantt.locator(`[aria-label="${DEPS_EDGE_ARIA}"]`)
    await expect(depsEdge).toBeAttached()

    // When. '분기' 버튼 클릭 → 줌 변경
    await quarterBtn.click()
    await expect(quarterBtn).toHaveAttribute('aria-pressed', 'true')

    // Then. 의존 라인 DOM 여전히 존재 (줌 변경이 deps DOM을 제거하지 않음)
    await expect(depsEdge).toBeAttached()

    // When. '주' 버튼 클릭 → 확대
    await weekBtn.click()
    await expect(weekBtn).toHaveAttribute('aria-pressed', 'true')

    // Then. 의존 라인 DOM 여전히 존재
    await expect(depsEdge).toBeAttached()
  })
})
