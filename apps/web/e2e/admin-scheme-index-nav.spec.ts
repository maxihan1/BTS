// 스킴 관리 인덱스 라우팅 회귀 E2E — 목록에서 상세·생성 화면으로 실제로 넘어가는지 URL 로 단정한다
import { test, expect } from '@playwright/test'
import {
  loginAsSystemAdmin,
  navigateToSchemeList,
  i18nLabels,
} from './fixtures/workflow-scheme-fixtures'

/**
 * 이 스펙이 잡는 회귀.
 *
 * `/admin/workflow-schemes` 인덱스가 스킴 클릭·「+ 새 스킴」을 **이동이 아니라 로컬 state 갱신**으로
 * 처리하던 시절이 있었다. 상세·생성 라우트는 router.ts 에 이미 등록돼 있었는데도 관리 허브로
 * 들어온 사용자는 스킴을 열 수도, 새로 만들 수도 없었다. 그 결과 커스텀 스킴 생성 경로가 UI 에서
 * 사라져 DB 에는 마이그레이션 시드(표준 4건)만 남았고, 프로젝트 설정의 스킴 드롭다운이
 * 「템플릿만 고를 수 있는」 화면이 됐다.
 *
 * ⚠️ 단위 테스트만으로는 이 회귀를 못 막는다 — 거기서는 `useNavigate` 가 목이라 `to` 문자열이
 * 틀려도 통과한다. 여기서는 **실제 URL 과 착지한 화면**을 본다.
 */

const labels = i18nLabels.workflowScheme

test('목록에서 스킴을 클릭하면 그 스킴의 상세 URL 로 이동한다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToSchemeList(page)

  await page.getByRole('button', { name: /소프트웨어 개발 기본 스킴/ }).click()

  await expect(page).toHaveURL(/\/admin\/workflow-schemes\/software-default-scheme$/)
  // 착지 확인 — 상세에만 있는 메타 패널이 보여야 한다(URL 만 바뀌고 화면이 안 바뀌는 경우 차단).
  await expect(page.getByText(labels.metaPanel.schemeKeyLabel)).toBeVisible()
})

test('커스텀 스킴도 같은 경로로 상세에 진입한다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToSchemeList(page)

  await page.getByRole('button', { name: /사내 개발팀 커스텀 스킴/ }).click()

  await expect(page).toHaveURL(/\/admin\/workflow-schemes\/custom-scheme-alpha$/)
  await expect(page.getByText('custom-scheme-alpha', { exact: true })).toBeVisible()
})

test('빈 상태 「+ 새 스킴」 버튼이 생성 화면으로 이동한다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToSchemeList(page)

  await page.getByRole('button', { name: labels.emptyState.addSchemeButton, exact: true }).click()

  await expect(page).toHaveURL(/\/admin\/workflow-schemes\/new$/)
  await expect(page.getByLabel(labels.create.keyLabel)).toBeVisible()
})

test('사이드바 「＋ 새 스킴 생성」 버튼도 생성 화면으로 이동한다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToSchemeList(page)

  await page.getByRole('button', { name: labels.sidebar.addSchemeAriaLabel }).click()

  await expect(page).toHaveURL(/\/admin\/workflow-schemes\/new$/)
  await expect(page.getByLabel(labels.create.keyLabel)).toBeVisible()
})
