// FR-WF-01 E2E — 표준 4 워크플로우 happy path
import { test, expect } from '@playwright/test'
import { navigateAndWaitForDiagram, getStateNodeCount } from './fixtures/workflow-helpers'

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
})

// T6-2: bug-tracking happy path
test('T6-2 bug-tracking — 페이지 진입 + 다이어그램 렌더 + 5 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[1]

  await navigateAndWaitForDiagram(page, wf.key, wf.name)

  const nodeCount = await getStateNodeCount(page)
  expect(nodeCount).toBe(wf.stateCount)

  await expect(page.locator(`[aria-label*="${wf.name}"]`)).toBeVisible()
})

// T6-3: simple happy path
test('T6-3 simple — 페이지 진입 + 다이어그램 렌더 + 3 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[2]

  await navigateAndWaitForDiagram(page, wf.key, wf.name)

  const nodeCount = await getStateNodeCount(page)
  expect(nodeCount).toBe(wf.stateCount)

  await expect(page.locator(`[aria-label*="${wf.name}"]`)).toBeVisible()
})

// T6-4: kanban-basic happy path
test('T6-4 kanban-basic — 페이지 진입 + 다이어그램 렌더 + 4 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[3]

  await navigateAndWaitForDiagram(page, wf.key, wf.name)

  const nodeCount = await getStateNodeCount(page)
  expect(nodeCount).toBe(wf.stateCount)

  await expect(page.locator(`[aria-label*="${wf.name}"]`)).toBeVisible()
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
