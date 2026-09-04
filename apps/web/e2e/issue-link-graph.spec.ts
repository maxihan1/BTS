// FR-LK-02 D7 E2E — 이슈 링크 그래프 패널 시나리오 (LinkGraph + MSW stateless handlers)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: 토글 버튼 exact:true로 strict mode 회피
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 재사용 (issue-fixtures.ts 정본)
//   - ui-pr-defer-e2e-regression-latent: 새 섹션이 기존 E2E 셀렉터를 깨지 않는지 확인
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동으로 ServiceWorker 유지
//
// MSW 핵심 사항.
//   - issue-graph-handlers.ts: stateless — 이슈 키별 고정 응답 (localStorage 플래그 불필요)
//   - ATLAS-1(GRAPH_NORMAL_KEY): 일반 그래프 — depth=1: 2노드, depth≥2: 3노드
//   - ATLAS-4(GRAPH_EMPTY_KEY): 빈 그래프 — 엣지 0개 → emptyState 렌더
//   - ATLAS-5(GRAPH_TRUNCATED_KEY): truncated=true — truncatedNotice + 그래프 렌더 동시
//   - ATLAS-4, ATLAS-5는 issue-handlers.ts에 fixture가 있어 이슈 상세 정상 렌더됨
//
// mermaid flowchart 렌더 실검증 현황.
//   - E2E-G5(C1)/E2E-G6(C2) 노드 클릭/키보드 — 활성화 (6개 전부 실행).
//   - 커밋 2fae8a07: CENTER_CLASS_DEF hex 절대값으로 교체 (CSS 변수 파싱 오류 해소).
//   - 커밋 887911dc: resolveSanitizedId 정규식 ^ 앵커 제거($ suffix 매칭으로 prefix 대응).
//   - 위 두 fix 이후 mermaid SVG 렌더 + role=link 바인딩이 정상 동작해 G5/G6 활성화.
//   - G1~G4는 mermaid 렌더 여부와 무관한 상위 레벨 UI 상태를 검증한다.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { linkGraphStrings } from '../src/i18n/ko'
import { GRAPH_EMPTY_KEY, GRAPH_TRUNCATED_KEY } from '../src/mocks/issue-graph-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 일반 그래프 이슈 — ATLAS-1 (issue-handlers.ts 정적 fixture) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** links-section data-testid — 이슈 상세 진입 완료 대기에 사용 */
const LINKS_SECTION_TESTID = 'links-section'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상세 페이지로 SPA 내부 내비게이션한다.
 * loginAsAlice 완료 후 ServiceWorker가 활성인 상태에서 호출.
 * links-section이 렌더될 때까지 대기 (이슈 상세 마운트 완료 기준).
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
  issueUrl = ISSUE_URL,
): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, issueUrl)
  // FR-UX-06 PR19 — LinkGraph(+IssueLinksPanel)은 "연결" 탭 안에 lazy 마운트됨(기본 활성 탭은 "이력").
  // 링크 그래프 접근 전 탭 전환 필요.
  await page.getByRole('tab', { name: '연결', exact: true }).click()
  await expect(page.getByTestId(LINKS_SECTION_TESTID)).toBeVisible()
}

/**
 * "링크 그래프" 섹션의 펼치기 버튼을 클릭하고 depth 컨트롤이 표시될 때까지 대기한다.
 */
async function expandLinkGraph(page: import('@playwright/test').Page): Promise<void> {
  const expandBtn = page.getByRole('button', {
    name: linkGraphStrings.expandLabel,
    exact: true,
  })
  await expect(expandBtn).toBeVisible()
  await expandBtn.click()
  // depth 컨트롤 표시 = 패널 펼쳐짐 확인
  await expect(page.getByRole('combobox', { name: linkGraphStrings.depthLabel })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-LK-02 이슈 링크 그래프 패널 (LinkGraph)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN)로 로그인 → /dashboard 진입 → ServiceWorker 기동 완료
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-G1 그래프 펼치기 → depth 컨트롤 표시 + "그래프 접기" 버튼 전환
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         "링크 그래프" 섹션이 접힌 상태 (기본값)
  // When    "그래프 펼치기" 버튼 클릭
  //         → GET /api/v1/issues/ATLAS-1/graph?depth=2 (MSW: 일반 그래프 응답)
  // Then    depth 컨트롤(select) 표시 — 기본 "2단계" 선택
  //         "그래프 펼치기" 버튼 → "그래프 접기" 버튼으로 전환
  //         renderError 메시지("그래프를 렌더링하지 못했습니다.") 미표시
  //         emptyState("연결된 이슈가 없습니다.") 미표시 (엣지 있음)
  //
  // 주: mermaid SVG g.node 실검증은 구현 코드 CSS 변수 파싱 오류로 SKIPPED
  //     (link-graph-mermaid.ts CENTER_CLASS_DEF 수정 후 활성화)
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-G1 그래프 펼치기 — depth 컨트롤 표시 + 접기 버튼 전환', async ({ page }) => {
    // Given.
    await navigateToIssueDetail(page)

    // Given. 접힌 상태 — "그래프 펼치기" 버튼 표시
    await expect(
      page.getByRole('button', { name: linkGraphStrings.expandLabel, exact: true }),
    ).toBeVisible()

    // When. 펼치기 클릭
    await expandLinkGraph(page)

    // Then. depth 컨트롤 표시 — 기본 "2" (2단계 selected)
    const depthSelect = page.getByRole('combobox', { name: linkGraphStrings.depthLabel })
    await expect(depthSelect).toBeVisible()
    await expect(depthSelect).toHaveValue('2')

    // Then. "그래프 접기" 버튼으로 전환
    await expect(
      page.getByRole('button', { name: linkGraphStrings.collapseLabel, exact: true }),
    ).toBeVisible()

    // Then. emptyState 미표시 (ATLAS-1은 엣지 있음)
    await expect(
      page.getByText(linkGraphStrings.emptyState, { exact: true }),
    ).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-G2 depth 컨트롤 변경 → 셀렉터 값 반영
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 → 그래프 펼침 (depth=2 기본값)
  // When    depth 셀렉터 → "1단계" (value=1) 선택
  //         → GET /api/v1/issues/ATLAS-1/graph?depth=1 (MSW: 2노드 응답)
  // Then    depth 셀렉터 값 "1" 유지 (React 상태 반영 확인)
  //         "그래프 접기" 버튼 여전히 표시 (패널 유지)
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-G2 depth 컨트롤 변경 — 1단계 선택 후 셀렉터 값 반영', async ({ page }) => {
    // Given.
    await navigateToIssueDetail(page)
    await expandLinkGraph(page)

    const depthSelect = page.getByRole('combobox', { name: linkGraphStrings.depthLabel })

    // Given. 기본 depth=2 확인
    await expect(depthSelect).toHaveValue('2')

    // When. depth 셀렉터 → "1단계" (value=1)
    await depthSelect.selectOption('1')

    // Then. 셀렉터 값 "1" 반영
    await expect(depthSelect).toHaveValue('1')

    // Then. 패널 유지 — "그래프 접기" 버튼 여전히 표시
    await expect(
      page.getByRole('button', { name: linkGraphStrings.collapseLabel, exact: true }),
    ).toBeVisible()

    // Then. emptyState 미표시 (depth=1이어도 ATLAS-1은 엣지 있음)
    await expect(
      page.getByText(linkGraphStrings.emptyState, { exact: true }),
    ).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-G3 빈 그래프 시나리오 — emptyState 메시지 표시
  //
  // Given   alice로 로그인, ATLAS-4 이슈 상세 진입
  //         (issue-handlers.ts fixture 존재 → 이슈 상세 정상 렌더)
  //         MSW: GET /graph → 엣지 0개 → generateGraphMermaidCode null → EmptyState
  // When    "그래프 펼치기" 버튼 클릭
  //         → GET /api/v1/issues/ATLAS-4/graph?depth=2 (MSW: 빈 그래프)
  // Then    emptyState 메시지("연결된 이슈가 없습니다.") 표시
  //         depth 컨트롤 표시 (패널 펼쳐짐 확인)
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-G3 빈 그래프 (ATLAS-4) — emptyState 메시지 표시', async ({ page }) => {
    // Given. ATLAS-4 이슈 상세 진입 (issue-handlers.ts fixture 있음)
    await navigateToIssueDetail(page, `/issues/${GRAPH_EMPTY_KEY}`)

    // When. 펼치기 클릭
    await expandLinkGraph(page)

    // Then. emptyState 메시지 표시
    await expect(
      page.getByText(linkGraphStrings.emptyState, { exact: true }),
    ).toBeVisible()

    // Then. depth 컨트롤도 표시 (패널 열림 상태)
    await expect(
      page.getByRole('combobox', { name: linkGraphStrings.depthLabel }),
    ).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-G4 truncated 시나리오 — truncatedNotice 표시
  //
  // Given   alice로 로그인, ATLAS-5 이슈 상세 진입
  //         (issue-handlers.ts fixture 존재 → 이슈 상세 정상 렌더)
  //         MSW: GET /graph → truncated=true + 엣지 있음
  // When    "그래프 펼치기" 버튼 클릭
  //         → GET /api/v1/issues/ATLAS-5/graph?depth=2 (MSW: truncated 그래프)
  // Then    truncatedNotice("노드가 너무 많아 일부만 표시됩니다.") 표시
  //         emptyState 미표시 (엣지 있으므로 그래프 렌더 시도)
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-G4 truncated 그래프 (ATLAS-5) — truncatedNotice 표시', async ({ page }) => {
    // Given. ATLAS-5 이슈 상세 진입 (issue-handlers.ts fixture 있음)
    await navigateToIssueDetail(page, `/issues/${GRAPH_TRUNCATED_KEY}`)

    // When. 펼치기 클릭
    await expandLinkGraph(page)

    // Then. truncatedNotice 표시
    await expect(
      page.getByText(linkGraphStrings.truncatedNotice, { exact: true }),
    ).toBeVisible()

    // Then. emptyState 미표시 (엣지 있음)
    await expect(
      page.getByText(linkGraphStrings.emptyState, { exact: true }),
    ).toHaveCount(0)

    // Then. depth 컨트롤 표시 (패널 열림 상태)
    await expect(
      page.getByRole('combobox', { name: linkGraphStrings.depthLabel }),
    ).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-G5 (C1) 노드 클릭 → 이슈 이동
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 → 그래프 펼침 (depth=2 기본값)
  //         mermaid SVG가 렌더되고 비-center 노드(ATLAS-2)에
  //         role=link, tabindex=0, aria-label이 부여된 상태
  // When    ATLAS-2 노드 클릭
  // Then    URL이 /issues/ATLAS-2 로 이동
  //         center 노드(ATLAS-1)는 role=link 없음 (no-op)
  //
  // 전제: 커밋 887911dc(resolveSanitizedId $-앵커) +
  //       커밋 2fae8a07(CENTER_CLASS_DEF hex 절대값)으로 mermaid 렌더 정상화됨.
  // ─────────────────────────────────────────────────────────────────────────
  // ★2026-09-04 계약 번복(Jira 패리티 J1). 링크 그래프 노드는 전체 페이지 이동이었다.
  // `LinkGraph` 가 `useOpenIssueDetail` 로 바뀌어 URL 이 움직이지 않는다 — 대상 이슈의
  // 상세 **모달**이 배경(현재 이슈 상세) 위에 열린다. 옛 `waitForURL` 은 8초를 기다렸다 죽었다.
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-G5 (C1) 비-center 노드 클릭 → 이슈 상세 모달', async ({ page }) => {
    // Given.
    await navigateToIssueDetail(page)
    await expandLinkGraph(page)

    // Given. mermaid SVG가 렌더되고 비-center 노드에 role=link 부여될 때까지 대기
    // (mermaid 비동기 렌더 특성상 polling 방식 사용 — sleep 대신 toPass)
    await expect(async () => {
      const hasLinkNode = await page.evaluate(() => {
        const container = document.querySelector('[aria-label="링크 그래프"]')
        if (!container) return false
        return container.querySelectorAll('g.node[role="link"]').length > 0
      })
      expect(hasLinkNode).toBe(true)
    }).toPass({ timeout: 12_000 })

    // Given. ATLAS-2 노드에 aria-label 부여됨 확인
    const atlas2Node = page.locator(`[aria-label="${linkGraphStrings.nodeAriaLabel('ATLAS-2')}"]`)
    await expect(atlas2Node).toBeVisible()

    // Then. center(ATLAS-1) 노드는 role=link 없음 (no-op 확인)
    const centerLinkRoles = await page.evaluate(() => {
      const container = document.querySelector('[aria-label="링크 그래프"]')
      if (!container) return 0
      // data-id="node_0"인 노드(center=ATLAS-1, 첫 번째 노드)의 role 확인
      const centerNode = container.querySelector('g.node[data-id="node_0"]')
      if (!centerNode) return -1 // data-id 없으면 -1 (id fallback 경로)
      return centerNode.getAttribute('role') === 'link' ? 1 : 0
    })
    // center 노드에 role=link가 없어야 함 (0 또는 -1: -1은 fallback 경로라 data-id 부재)
    expect(centerLinkRoles).not.toBe(1)

    // When. ATLAS-2 노드 클릭
    await atlas2Node.click()

    // Then. ATLAS-2 상세 모달이 열린다 (URL 은 ATLAS-1 상세 그대로)
    await expect(page.getByRole('dialog', { name: '이슈 상세 ATLAS-2' })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-G6 (C2) 키보드 Enter로 노드 이슈 상세 모달
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 → 그래프 펼침 (depth=2 기본값)
  //         ATLAS-2 노드에 tabindex=0, role=link, aria-label 부여됨
  // When    ATLAS-2 노드 포커스 → Enter 키 입력
  // Then    ATLAS-2 상세 모달이 열린다
  //         aria-label 값이 nodeAriaLabel('ATLAS-2') 와 일치
  //
  // ★2026-09-04 계약 번복(Jira 패리티 J1). 링크 그래프 노드는 전체 페이지 이동이었다.
  // `LinkGraph` 가 `useOpenIssueDetail` 로 바뀌어 URL 이 움직이지 않는다 — 대상 이슈의
  // 상세 **모달**이 배경(현재 이슈 상세) 위에 열린다. 옛 `waitForURL` 은 8초를 기다렸다 죽었다.
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-G6 (C2) 키보드 Enter로 비-center 노드 이슈 상세 모달', async ({ page }) => {
    // Given.
    await navigateToIssueDetail(page)
    await expandLinkGraph(page)

    // Given. mermaid 렌더 + role=link 바인딩 대기
    await expect(async () => {
      const hasLinkNode = await page.evaluate(() => {
        const container = document.querySelector('[aria-label="링크 그래프"]')
        if (!container) return false
        return container.querySelectorAll('g.node[role="link"]').length > 0
      })
      expect(hasLinkNode).toBe(true)
    }).toPass({ timeout: 12_000 })

    // Given. ATLAS-2 노드 — aria-label 확인 (C2 검증)
    const atlas2Node = page.locator(`[aria-label="${linkGraphStrings.nodeAriaLabel('ATLAS-2')}"]`)
    await expect(atlas2Node).toBeVisible()

    // Given. tabindex=0 확인 (포커스 가능)
    await expect(atlas2Node).toHaveAttribute('tabindex', '0')

    // When. 포커스 + Enter
    await atlas2Node.focus()
    await page.keyboard.press('Enter')

    // Then. ATLAS-2 상세 모달이 열린다
    await expect(page.getByRole('dialog', { name: '이슈 상세 ATLAS-2' })).toBeVisible()
  })
})
