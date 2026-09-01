// FR-WF-07 D7 E2E — 발행 · 기본값 복원 · 이관 필요 안내
//
// D7 의 정본 문장은 「상태를 빼고 발행하면 마법사가 뜨고 이관 후 발행된다」이고, 그 뒷절반
// (이관 실행·진행률)은 로드맵 PR 10b 몫이다. 여기서는 **앞절반과 그 분기들**을 잰다 —
// 이관이 필요 없는 발행 · 이관이 필요하면 발행이 막히고 무엇이 막는지 보이는 것 · 복원 ·
// 「발행 전에는 안 바뀐다」.
//
// 함정 메모리.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - `page.goto()` 재진입 금지 — MSW 모듈 재평가로 목 저장소가 리셋된다
import { test, expect } from '@playwright/test'
import { loginAsSystemAdmin } from './fixtures/workflow-scheme-fixtures'

const TARGET_NAME = '소프트웨어 개발 기본 워크플로우'

/** 목록을 거쳐 편집기로 들어간다. */
async function openEditor(page: import('@playwright/test').Page): Promise<void> {
  const adminNav = page.getByRole('navigation', { name: '관리 메뉴' })
  await adminNav.getByRole('link', { name: '워크플로우 관리', exact: true }).click()
  await page
    .getByRole('table', { name: '워크플로우 목록' })
    .getByRole('row')
    .filter({ hasText: TARGET_NAME })
    .getByRole('button', { name: `편집 ${TARGET_NAME}` })
    .click()
  await expect(page.getByRole('list', { name: '편성된 상태 목록' })).toBeVisible()
}

/** 상태 하나를 초안에서 뺀다. */
async function removeStatus(page: import('@playwright/test').Page, name: string): Promise<void> {
  await page.getByRole('button', { name: `상태 제거 ${name}` }).click()
  // 확인 다이얼로그의 확정 버튼 — 여는 버튼과 접두를 공유하므로 다이얼로그 안에서 집는다.
  await page.getByRole('dialog').getByRole('button', { name: '상태 제거' }).click()
}

test('P2 사라지는 상태가 없으면 마법사 없이 발행된다 (D7)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await openEditor(page)

  await page.getByRole('textbox', { name: '워크플로우 이름' }).fill('발행된 이름')
  await expect(page.getByText('저장됨')).toBeVisible()

  await page.getByRole('button', { name: '발행' }).click()
  const dialog = page.getByRole('dialog', { name: '워크플로우 발행' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('사라지는 상태가 없습니다')

  await dialog.getByRole('button', { name: '발행하기' }).click()

  // 발행해야 목록이 바뀐다.
  await page.getByRole('button', { name: '목록으로' }).click()
  await expect(page.getByRole('table', { name: '워크플로우 목록' })).toContainText('발행된 이름')
})

test('P3 초안을 고쳐도 발행 전에는 목록이 옛 정의를 보여준다 (D7 · FR-WF-07 의 존재 이유)', async ({
  page,
}) => {
  await loginAsSystemAdmin(page)
  await openEditor(page)

  const statusList = page.getByRole('list', { name: '편성된 상태 목록' })
  const before = await statusList.getByRole('listitem').count()

  await removeStatus(page, 'Closed')
  await expect(statusList.getByRole('listitem')).toHaveCount(before - 1)
  await expect(page.getByText('저장됨')).toBeVisible()

  // 목록의 상태 수는 그대로다 — 초안은 운영에 안 샌다.
  await page.getByRole('button', { name: '목록으로' }).click()
  const row = page
    .getByRole('table', { name: '워크플로우 목록' })
    .getByRole('row')
    .filter({ hasText: TARGET_NAME })
  await expect(row).toContainText(String(before))
})

test('P4 기본값 복원은 초안까지만 간다 (D7)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await openEditor(page)

  await page.getByRole('button', { name: '기본값으로 되돌리기' }).click()
  const dialog = page.getByRole('dialog', { name: '기본값으로 되돌리기' })
  await expect(dialog).toContainText('발행해야 운영에 반영됩니다')
  await dialog.getByRole('button', { name: '초안으로 불러오기' }).click()

  // 초안 이름이 기본값으로 바뀌고
  await expect(page.getByRole('textbox', { name: '워크플로우 이름' })).toHaveValue(/기본값/)
  // 목록은 그대로다
  await page.getByRole('button', { name: '목록으로' }).click()
  await expect(page.getByRole('table', { name: '워크플로우 목록' })).toContainText(TARGET_NAME)
})

test('P4b 이슈가 남은 상태를 빼면 발행 대신 무엇이 막는지 보여준다 (D7)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await openEditor(page)

  // 목 픽스처가 Done 상태에 이슈 3건을 들고 있다.
  await removeStatus(page, 'Done')
  await expect(page.getByText('저장됨')).toBeVisible()

  await page.getByRole('button', { name: '발행' }).click()
  const dialog = page.getByRole('dialog', { name: '워크플로우 발행' })
  await expect(dialog).toBeVisible()

  // 눌러 보고 409 를 받게 두지 않는다 — 발행 버튼 자체가 없다.
  await expect(dialog.getByRole('button', { name: '발행하기' })).toHaveCount(0)
  await expect(dialog).toContainText('3')
  // 보드 컬럼 고지는 사라지는 상태가 있으면 무조건 뜬다.
  await expect(dialog).toContainText('보드 컬럼')
})
