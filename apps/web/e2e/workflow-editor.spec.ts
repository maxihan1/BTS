// FR-WF-04 D7 E2E — /admin/workflows 목록 + 목록 모드 편집기
// (이름 수정 · 상태 추가 · 재기동 생존 · 전환 이름)
//
// 관련 함정 메모리.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — MSW 브라우저 워커 사용
//   - msw-mutation-stateful-refetch: workflow-admin-handlers 는 인메모리 stateful —
//     mutation 후 refetch 로 화면이 실제로 갱신되는지까지 본다
//   - ★ 라벨 substring: '워크플로우' 는 '워크플로우 스킴' 의 substring 이라 부분일치가
//     둘을 함께 잡는다. 사이드바 링크는 반드시 '워크플로우 관리' 로 집는다.
import { test, expect } from '@playwright/test'
import { loginAsSystemAdmin } from './fixtures/workflow-scheme-fixtures'

/** 목 픽스처 기준 — software-default 는 5 상태 + 7 전환(INITIAL 1 + NORMAL 6) */
const TARGET_KEY = 'software-default'
const TARGET_NAME = '소프트웨어 개발 기본 워크플로우'

/** 사이드바를 통해 목록으로 간다 — 진입점이 실제로 걸려 있는지까지 함께 본다 */
async function navigateToWorkflowList(page: import('@playwright/test').Page): Promise<void> {
  const adminNav = page.getByRole('navigation', { name: '관리 메뉴' })
  await expect(adminNav).toBeVisible()
  // exact 로 집는다 — '워크플로우 관리' 는 '워크플로우 스킴' 과 접두를 공유한다
  await adminNav.getByRole('link', { name: '워크플로우 관리', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: '워크플로우 관리' })).toBeVisible()
}

test('E2E-1 목록 → 편집기 진입 → 이름 수정이 저장된다 (D7)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToWorkflowList(page)

  // ── 목록. 표에 워크플로우가 보이고 상태·전환 개수를 함께 준다 ──
  const table = page.getByRole('table', { name: '워크플로우 목록' })
  await expect(table).toBeVisible()
  const row = table.getByRole('row').filter({ hasText: TARGET_NAME })
  await expect(row).toHaveCount(1)
  await expect(row).toContainText(TARGET_KEY)

  // ── 편집기 진입 ──
  await row.getByRole('button', { name: `편집 ${TARGET_NAME}` }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/workflows/${TARGET_KEY}$`))
  await expect(page.getByRole('heading', { level: 1, name: TARGET_NAME })).toBeVisible()

  // ── 이름 수정 → 저장 → h1 이 새 이름을 반영한다 (refetch 까지 확인) ──
  const nameInput = page.getByRole('textbox', { name: '워크플로우 이름' })
  await nameInput.fill('이름을 고친 워크플로우')
  await page.getByRole('button', { name: '변경 사항 저장' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '이름을 고친 워크플로우' })).toBeVisible()
})

test('E2E-2 상태를 추가하면 목록에 남고 뒤로 갔다 와도 살아 있다 (D7 재기동 생존)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: TARGET_NAME })).toBeVisible()

  const statusList = page.getByRole('list', { name: '편성된 상태 목록' })
  const before = await statusList.getByRole('listitem').count()

  // ── 상태 추가 ──
  await page.getByRole('button', { name: '상태 추가' }).click()
  const picker = page.getByRole('dialog', { name: '워크플로우에 추가할 상태 선택' })
  await expect(picker).toBeVisible()
  await picker.getByRole('combobox', { name: '워크플로우에 추가할 상태 선택' }).click()
  await page.getByRole('option', { name: 'Blocked', exact: true }).click()
  await picker.getByRole('button', { name: '상태 추가' }).click()

  await expect(statusList.getByRole('listitem')).toHaveCount(before + 1)
  await expect(statusList).toContainText('Blocked')

  // ── 목록으로 나갔다 다시 들어와도 살아 있다 (stateful 목이 refetch 를 견딘다) ──
  await page.getByRole('button', { name: '목록으로' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '워크플로우 관리' })).toBeVisible()
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await expect(page.getByRole('list', { name: '편성된 상태 목록' })).toContainText('Blocked')
})

test('E2E-3 전환 이름을 고치면 목록이 새 이름을 보여준다 (D7)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await page.getByRole('tab', { name: '전환' }).click()

  const list = page.getByRole('list', { name: '전환 목록' })
  await expect(list).toBeVisible()

  // 픽스처의 INITIAL 전환에는 종류 배지가 붙는다 — 출발 상태가 없다는 것을 사용자가 안다
  await expect(list).toContainText('이슈 생성 시')

  await page.getByRole('button', { name: '전환 편집 Start Work' }).click()
  const dialog = page.getByRole('dialog', { name: '전환 수정' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('textbox', { name: '전환 이름' }).fill('작업 시작하기')
  await dialog.getByRole('button', { name: '저장' }).click()

  await expect(list).toContainText('작업 시작하기')
  await expect(list).not.toContainText('Start Work')
})

test('E2E-4 사이드바 링크 이름이 스킴 링크와 겹치지 않는다 (§2 즉사 계약)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  const adminNav = page.getByRole('navigation', { name: '관리 메뉴' })

  // 부분일치(기본)로 '워크플로우' 를 집으면 둘이 잡힌다 — 그 사실 자체를 못박는다.
  // 이 단언이 1 로 바뀌면 누군가 라벨을 줄인 것이고, 그때 exact 없는 기존 셀렉터가 죽는다.
  await expect(adminNav.getByRole('link', { name: '워크플로우' })).toHaveCount(2)
  await expect(adminNav.getByRole('link', { name: '워크플로우 관리', exact: true })).toHaveCount(1)
  await expect(adminNav.getByRole('link', { name: '워크플로우 스킴', exact: true })).toHaveCount(1)
})
