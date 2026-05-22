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
