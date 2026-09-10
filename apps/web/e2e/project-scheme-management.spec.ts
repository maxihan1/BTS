// FR-WF-08 PR ⑤ E2E — 프로젝트 설정의 스킴 관리 구역 진입·목록·전역 읽기 전용
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { workflowSchemeLabels } from '../src/i18n/workflow-scheme-labels'

const labels = workflowSchemeLabels.projectManagement

// ─────────────────────────────────────────────────────────────────────────────
// 왜 E2E 인가
//
// 유닛은 라우터를 목으로 갈아 끼우므로 「사이드바가 실제로 그 주소에 붙어 있는가」와
// 「배정 구역과 관리 구역이 한 화면에서 부딪히지 않는가」를 한 번도 재지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

test('프로젝트 스킴 설정에 관리 구역이 함께 뜬다', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/settings/workflow-scheme')

  // 배정 구역 — 기존 화면이 그대로 살아 있어야 한다.
  await expect(page.getByText(workflowSchemeLabels.assignment.currentSchemeTitle)).toBeVisible()
  // 관리 구역 — 사이드바가 붙었다.
  await expect(
    page.getByRole('navigation', { name: workflowSchemeLabels.sidebar.nav }),
  ).toBeVisible()
})

test('아무 스킴도 안 고르면 고르라고 안내한다', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/settings/workflow-scheme')

  await expect(page.getByText(labels.pickPrompt)).toBeVisible()
})

test('전역 템플릿을 고르면 읽기 전용 안내와 매핑을 보여준다', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/settings/workflow-scheme')

  const sidebar = page.getByRole('navigation', { name: workflowSchemeLabels.sidebar.nav })
  await sidebar.getByRole('button').first().click()

  // 목 픽스처는 전부 전역이다 — 편집 컨트롤 없이 안내와 내용만 나와야 한다.
  const notice = page.getByText(labels.globalReadOnlyNotice)
  await expect(notice).toBeVisible()

  // ★판정을 pane 안으로 좁힌다. 「페이지 어디에도 없다」가 아니라 「이 구역에 편집 컨트롤이
  //  없다」가 진짜 계약이다. 게다가 `추가`·`삭제` 같은 짧은 라벨은 `getByRole` 의 기본 부분
  //  일치에 다른 버튼이 함께 걸린다(메모리 playwright-getbyrole-exact-strict-mode).
  const pane = page.locator('section', { has: notice })
  await expect(
    pane.getByRole('button', { name: workflowSchemeLabels.mapping.addMappingButton, exact: true }),
  ).toHaveCount(0)
})

test('새 스킴 버튼이 생성 폼을 연다', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/settings/workflow-scheme')

  await page.getByRole('button', { name: workflowSchemeLabels.sidebar.addSchemeAriaLabel }).click()

  await expect(page.getByRole('heading', { name: labels.createHeading })).toBeVisible()
  await expect(page.getByLabel(labels.createKeyLabel)).toBeVisible()
})
