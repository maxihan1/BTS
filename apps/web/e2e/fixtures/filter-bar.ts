// 필터 바 드롭다운을 여는 e2e 공용 헬퍼 — 지라 기본 검색식 가로 필터 바 (Jira 패리티)
import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'

/**
 * 필터 드롭다운을 연다 — **닫혀 있을 때만**.
 *
 * 필터 바가 가로 드롭다운으로 바뀌면서 상태·담당자·라벨·컴포넌트 컨트롤이 각자 팝오버 안으로
 * 들어갔다. 트리거는 토글이라 이미 열려 있는데 또 누르면 도로 닫히므로, Radix 가 트리거에
 * 실어 주는 `data-state` 로 먼저 판정한다.
 *
 * @param page Playwright Page
 * @param label 필터 이름 — 「상태」 「담당자」 「라벨」 「컴포넌트」
 */
export async function openFilterDropdown(page: Page, label: string): Promise<void> {
  const trigger = page.getByRole('button', { name: `${label} 필터`, exact: true })
  await expect(trigger).toBeVisible()
  if ((await trigger.getAttribute('data-state')) === 'open') return
  await trigger.click()
  await expect(trigger).toHaveAttribute('data-state', 'open')
}
