// FR-WF-01 E2E 워크플로우 페이지 공통 헬퍼 — 네비게이션 + 어설션 추출
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

/**
 * 워크플로우 상세 페이지로 이동하여 다이어그램 렌더를 기다린다.
 * - h1 헤더에 workflowName이 포함되어 있는지 확인
 * - [aria-label*="다이어그램"] 컨테이너가 visible인지 확인
 * - SVG가 주입될 때까지 최대 10초 대기
 *
 * @param page Playwright Page 객체
 * @param workflowKey URL에 사용될 워크플로우 키 (예: 'software-default')
 * @param workflowName 페이지 h1에 표시될 워크플로우 이름
 */
export async function navigateAndWaitForDiagram(
  page: Page,
  workflowKey: string,
  workflowName: string,
): Promise<void> {
  await page.goto(`/workflows/${workflowKey}`)

  // h1 헤더에 워크플로우 이름 포함 확인
  await expect(page.getByRole('heading', { level: 1 })).toContainText(workflowName)

  // 다이어그램 컨테이너 visible 확인 (aria-label — NFR-3 접근성)
  const diagramContainer = page.locator('[aria-label*="다이어그램"]')
  await expect(diagramContainer).toBeVisible()

  // mermaid SVG 주입 대기
  await page.waitForSelector('[aria-label*="다이어그램"] svg', { state: 'visible', timeout: 10_000 })
}

/**
 * SVG 안의 실제 상태 노드 수를 반환한다.
 * mermaid stateDiagram-v2 에서 .statediagram-state 는 일반 상태 노드만 포함한다.
 * 시작/종료 [*] 노드는 .node.start / .node.end 클래스로 분리되어 제외된다.
 *
 * @param page Playwright Page 객체
 * @returns 상태 노드 수
 */
export async function getStateNodeCount(page: Page): Promise<number> {
  return page.locator('[aria-label*="다이어그램"] svg .statediagram-state').count()
}
