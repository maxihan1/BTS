// FR-WF-01 E2E — 표준 4 워크플로우 happy path
import type { Page } from '@playwright/test'
import { test, expect } from '@playwright/test'
import { navigateAndWaitForDiagram, getStateNodeCount } from './fixtures/workflow-helpers'

/**
 * 마이그레이션 V207 ⑨ 백필이 심는 INITIAL 전환의 이름 — MSW 픽스처와 같은 문자열.
 * 다이어그램에서 시작 화살표 `[*] --> to` 의 엣지 라벨로 나타난다.
 */
const INITIAL_TRANSITION_NAME = '이슈 생성'

/**
 * 출발 상태가 없는 전환(GLOBAL·INITIAL)이 문자열 'null' 노드로 새지 않았는지 확인한다.
 *
 * `fromStateKey` 가 null 인 전환을 `${from} --> ${to}` 로 보간하면 mermaid 가 `null` 이라는
 * 이름의 상태 노드를 만들어 낸다 — 노드 수 단언은 1 늘고 화면에는 정체불명의 노드가 남는다.
 * 노드 수 단언만으로는 「무엇이 늘었는지」를 못 잡으므로 이름으로 직접 0건을 확인한다.
 *
 * @param page Playwright Page 객체
 */
async function expectNoNullStateNode(page: Page): Promise<void> {
  const nullNodes = page.locator('[aria-label*="다이어그램"] svg .statediagram-state', {
    hasText: /^null$/,
  })
  await expect(nullNodes).toHaveCount(0)
}

// 각 워크플로우의 fixture 기대값 (workflow-fixtures.ts 와 일치)
const WORKFLOWS = [
  {
    key: 'software-default',
    name: '소프트웨어 개발 기본 워크플로우',
    stateCount: 5,
  },
  {
    key: 'bug-tracking',
    name: '버그 추적 워크플로우',
    stateCount: 5,
  },
  {
    key: 'simple',
    name: '단순 워크플로우 (TODO/DOING/DONE)',
    stateCount: 3,
  },
  {
    key: 'kanban-basic',
    name: '칸반 기본 워크플로우',
    stateCount: 4,
  },
] as const

// T6-1: software-default happy path
test('T6-1 software-default — 페이지 진입 + 다이어그램 렌더 + 5 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[0]

  await navigateAndWaitForDiagram(page, wf.key, wf.name)

  // 상태 노드 수 검증 — .statediagram-state (시작/종료 [*] 제외)
  const nodeCount = await getStateNodeCount(page)
  expect(nodeCount).toBe(wf.stateCount)

  // NFR-3 접근성: aria-label 을 통해 스크린 리더가 다이어그램을 인식할 수 있어야 한다
  await expect(page.locator(`[aria-label*="${wf.name}"]`)).toBeVisible()

  // 출발 상태가 없는 전환이 'null' 노드로 새지 않는다 + INITIAL 은 시작 화살표 라벨로 그려진다
  await expectNoNullStateNode(page)
  await expect(
    page.locator('[aria-label*="다이어그램"] svg').getByText(INITIAL_TRANSITION_NAME),
  ).toBeVisible()
})

// T6-2: bug-tracking happy path
test('T6-2 bug-tracking — 페이지 진입 + 다이어그램 렌더 + 5 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[1]

  await navigateAndWaitForDiagram(page, wf.key, wf.name)

  const nodeCount = await getStateNodeCount(page)
  expect(nodeCount).toBe(wf.stateCount)

  await expect(page.locator(`[aria-label*="${wf.name}"]`)).toBeVisible()

  // 출발 상태가 없는 전환이 'null' 노드로 새지 않는다 + INITIAL 은 시작 화살표 라벨로 그려진다
  await expectNoNullStateNode(page)
  await expect(
    page.locator('[aria-label*="다이어그램"] svg').getByText(INITIAL_TRANSITION_NAME),
  ).toBeVisible()
})

// T6-3: simple happy path
test('T6-3 simple — 페이지 진입 + 다이어그램 렌더 + 3 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[2]

  await navigateAndWaitForDiagram(page, wf.key, wf.name)

  const nodeCount = await getStateNodeCount(page)
  expect(nodeCount).toBe(wf.stateCount)

  await expect(page.locator(`[aria-label*="${wf.name}"]`)).toBeVisible()

  // 출발 상태가 없는 전환이 'null' 노드로 새지 않는다 + INITIAL 은 시작 화살표 라벨로 그려진다
  await expectNoNullStateNode(page)
  await expect(
    page.locator('[aria-label*="다이어그램"] svg').getByText(INITIAL_TRANSITION_NAME),
  ).toBeVisible()
})

// T6-4: kanban-basic happy path
test('T6-4 kanban-basic — 페이지 진입 + 다이어그램 렌더 + 4 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[3]

  await navigateAndWaitForDiagram(page, wf.key, wf.name)

  const nodeCount = await getStateNodeCount(page)
  expect(nodeCount).toBe(wf.stateCount)

  await expect(page.locator(`[aria-label*="${wf.name}"]`)).toBeVisible()

  // 출발 상태가 없는 전환이 'null' 노드로 새지 않는다 + INITIAL 은 시작 화살표 라벨로 그려진다
  await expectNoNullStateNode(page)
  await expect(
    page.locator('[aria-label*="다이어그램"] svg').getByText(INITIAL_TRANSITION_NAME),
  ).toBeVisible()
})

// T6-5: unknown-key 404 fallback
// Given: 존재하지 않는 워크플로우 키로 진입
// When: /workflows/non-existent 페이지 로드
// Then: role=alert 에 "워크플로우를 찾을 수 없습니다" 표시 + 다이어그램 노드 0건
test('T6-5 unknown-key — 404 응답 → fallback UI ("워크플로우를 찾을 수 없습니다") 표시', async ({ page }) => {
  // PR #16 D5 옵션 C 위임 충족.
  // workflows.$key.test.tsx T5-2 (jsdom mermaid getBBox 미구현 timeout 으로 it.skip) 의 E2E 위임 시나리오.
  // 본 시나리오는 unknown key 진입 시 workflows.$key.tsx 의 error fallback UI 검증.
  await page.goto('/workflows/non-existent')

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toHaveText('워크플로우를 찾을 수 없습니다')

  // mermaid 다이어그램 렌더 0 검증 — 일반 상태 노드 0건
  const stateNodes = page.locator('.statediagram-state')
  await expect(stateNodes).toHaveCount(0)
})
