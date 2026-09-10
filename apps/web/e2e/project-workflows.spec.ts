// FR-WF-08 E2E — 프로젝트 설정의 워크플로우 진입점 · 소유 갈림 · 편집기 재사용
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { workflowEditorLabels } from '../src/i18n/workflow-editor-labels'
import { E2E_PROJECT_WORKFLOWS_FORBIDDEN_KEY } from '../src/mocks/workflow-handlers'

const labels = workflowEditorLabels.projectSettings

// ─────────────────────────────────────────────────────────────────────────────
// 왜 E2E 인가
//
// 유닛은 `useNavigate` 와 권한 훅이 목이라, 라우트가 실제로 등록됐는지·가드를 통과하는지·
// 사이드바 링크가 그 주소로 가는지를 **한 번도 재지 않고** 통과한다. 실제 URL 을 밟는 것이
// 유일한 증거다 (계획 Task 4-5).
// ─────────────────────────────────────────────────────────────────────────────

test('프로젝트 설정 사이드바의 「워크플로우」가 목록으로 데려간다', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/settings/details')

  // 설정 그룹은 접혀 있다 — 먼저 펼쳐야 링크가 DOM 에 뜬다(project-tree.spec.ts 와 같은 절차).
  const nav = page.getByRole('navigation', { name: '프로젝트', exact: true })
  const settingsToggle = nav.getByRole('button', { name: '프로젝트 설정', exact: true })
  if ((await settingsToggle.getAttribute('aria-expanded')) === 'false') {
    await settingsToggle.click()
  }
  // `exact: true` 없이는 「워크플로우 스킴」까지 잡혀 strict mode 위반이다.
  await nav.getByRole('link', { name: '워크플로우', exact: true }).click()

  await expect(page).toHaveURL(/\/projects\/ATLAS\/settings\/workflows$/)
  await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()
})

test('목록이 전역 템플릿을 「전역」으로 표시하고 복제를 준다', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/settings/workflows')

  await expect(page.getByRole('table', { name: labels.table })).toBeVisible()
  // 목 워크플로우는 전부 전역 시드다 — 전역 배지가 보이고 편집이 아니라 복제가 있어야 한다.
  await expect(page.getByText(labels.ownerGlobal).first()).toBeVisible()
  await expect(
    page.getByRole('button', { name: new RegExp(`^${labels.copyToProject} `) }).first(),
  ).toBeVisible()
})

test('편집기 라우트가 관리 편집기를 그대로 띄운다', async ({ page }) => {
  await loginAsAlice(page)
  // 목록은 전역 행에 편집을 주지 않으므로 편집기 주소로 직접 들어간다 — 라우트 등록과
  // 편집기 재사용(Task 4-2)을 재는 것이 목적이다.
  await page.goto('/projects/ATLAS/settings/workflows/software-default')

  await expect(page.getByRole('tablist', { name: workflowEditorLabels.editor.tabs })).toBeVisible()
  await expect(page.getByRole('button', { name: labels.backToList })).toBeVisible()
})

test('편집기에서 목록으로 돌아간다', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/settings/workflows/software-default')

  await page.getByRole('button', { name: labels.backToList }).click()

  await expect(page).toHaveURL(/\/projects\/ATLAS\/settings\/workflows$/)
})

test('권한이 없으면 빈 표가 아니라 안내 카드가 뜬다', async ({ page }) => {
  await loginAsAlice(page)
  // ★비-어드민 멤버 시나리오. 프론트에 프로젝트 어드민 가드가 없으므로(GAP-1) 화면은 응답으로만
  //  이 상태를 안다. `page.route()` 는 MSW Service Worker 가 먼저 응답해 무효라 목 스위치를 쓴다.
  await page.addInitScript((key) => {
    window.localStorage.setItem(key, 'true')
  }, E2E_PROJECT_WORKFLOWS_FORBIDDEN_KEY)
  await page.goto('/projects/ATLAS/settings/workflows')

  await expect(page.getByText(labels.forbiddenTitle)).toBeVisible()
  await expect(page.getByRole('table', { name: labels.table })).toHaveCount(0)
})
