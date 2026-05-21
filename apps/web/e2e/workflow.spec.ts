// FR-WF-01 E2E — 표준 4 워크플로우 happy path
import { test, expect } from '@playwright/test'

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

  await page.goto(`/workflows/${wf.key}`)

  // h1 헤더 확인
  await expect(page.getByRole('heading', { level: 1 })).toContainText(wf.name)

  // 다이어그램 컨테이너 visible 확인 (aria-label)
  const diagramContainer = page.locator(`[aria-label*="다이어그램"]`)
  await expect(diagramContainer).toBeVisible()

  // mermaid가 SVG를 주입할 때까지 대기
  await page.waitForSelector('[aria-label*="다이어그램"] svg', { state: 'visible', timeout: 10_000 })

  // 상태 노드 수 검증 — mermaid stateDiagram-v2 의 .node 클래스
  const nodeCount = await page.locator('[aria-label*="다이어그램"] svg .node').count()
  expect(nodeCount).toBe(wf.stateCount)
})

// T6-2: bug-tracking happy path
test('T6-2 bug-tracking — 페이지 진입 + 다이어그램 렌더 + 5 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[1]

  await page.goto(`/workflows/${wf.key}`)

  await expect(page.getByRole('heading', { level: 1 })).toContainText(wf.name)

  const diagramContainer = page.locator(`[aria-label*="다이어그램"]`)
  await expect(diagramContainer).toBeVisible()

  await page.waitForSelector('[aria-label*="다이어그램"] svg', { state: 'visible', timeout: 10_000 })

  const nodeCount = await page.locator('[aria-label*="다이어그램"] svg .node').count()
  expect(nodeCount).toBe(wf.stateCount)
})

// T6-3: simple happy path
test('T6-3 simple — 페이지 진입 + 다이어그램 렌더 + 3 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[2]

  await page.goto(`/workflows/${wf.key}`)

  await expect(page.getByRole('heading', { level: 1 })).toContainText(wf.name)

  const diagramContainer = page.locator(`[aria-label*="다이어그램"]`)
  await expect(diagramContainer).toBeVisible()

  await page.waitForSelector('[aria-label*="다이어그램"] svg', { state: 'visible', timeout: 10_000 })

  const nodeCount = await page.locator('[aria-label*="다이어그램"] svg .node').count()
  expect(nodeCount).toBe(wf.stateCount)
})

// T6-4: kanban-basic happy path
test('T6-4 kanban-basic — 페이지 진입 + 다이어그램 렌더 + 4 노드 + 헤더', async ({ page }) => {
  const wf = WORKFLOWS[3]

  await page.goto(`/workflows/${wf.key}`)

  await expect(page.getByRole('heading', { level: 1 })).toContainText(wf.name)

  const diagramContainer = page.locator(`[aria-label*="다이어그램"]`)
  await expect(diagramContainer).toBeVisible()

  await page.waitForSelector('[aria-label*="다이어그램"] svg', { state: 'visible', timeout: 10_000 })

  const nodeCount = await page.locator('[aria-label*="다이어그램"] svg .node').count()
  expect(nodeCount).toBe(wf.stateCount)
})
