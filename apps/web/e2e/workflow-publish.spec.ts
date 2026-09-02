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
import { workflowPublishLabels as labels } from '../src/i18n/workflow-publish-labels'

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

  // ★ D6b — 발행하기 버튼이 없던 그 자리에 마법사(이관 시작)가 들어온다. 별도 다이얼로그가
  // 아니라 같은 `워크플로우 발행` 다이얼로그의 단계라 위 세 단언이 그대로 살아 있다.
  await expect(dialog.getByRole('button', { name: labels.migration.start })).toBeVisible()
})

test('D7 정본 — 상태를 빼고 발행하면 마법사가 뜨고 이관 후 발행된다 (FR-WF-07 D6b)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await openEditor(page)

  // 발행이 실제로 일어났는지는 마지막에 **목록의 상태 수**로 잰다(P3 와 같은 축) — 그 기준선.
  const statusList = page.getByRole('list', { name: '편성된 상태 목록' })
  const beforeCount = await statusList.getByRole('listitem').count()

  // 1. 이슈가 남은 상태를 뺀다 → 발행 → 마법사가 뜬다
  await removeStatus(page, 'Done')
  await expect(statusList.getByRole('listitem')).toHaveCount(beforeCount - 1)
  await expect(page.getByText('저장됨')).toBeVisible()

  await page.getByRole('button', { name: '발행' }).click()
  const dialog = page.getByRole('dialog', { name: labels.publish.dialogTitle })
  await expect(dialog).toBeVisible()
  // 발행 버튼은 아직 없다 — 이관을 먼저 끝내야 한다.
  await expect(dialog.getByRole('button', { name: labels.publish.confirm })).toHaveCount(0)

  const startButton = dialog.getByRole('button', { name: labels.migration.start })
  await expect(startButton).toBeVisible()
  await expect(startButton).toBeDisabled()

  // 2. 도착지를 고른다 → 이관 시작 → 진행률이 보인다
  await dialog.getByRole('combobox', { name: 'Done' }).click()
  await page.getByRole('option', { name: 'Closed', exact: true }).click()
  await expect(startButton).toBeEnabled()
  await startButton.click()

  await expect(dialog.getByRole('progressbar', { name: labels.migration.progressLabel })).toBeVisible()
  await expect(dialog.getByText(labels.migration.completed)).toBeVisible()

  // ★ G-1 — 이관 완료가 발행을 자동으로 부르지 않는다. 완료 직후에도 여전히 없다가,
  // 사용자가 다시 눌러야 나타난다.
  const publishButton = dialog.getByRole('button', { name: labels.publish.confirm })
  await expect(publishButton).toBeVisible()

  // 3. 완료되면 사용자가 다시 발행을 누른다 → 발행 성공(G-1)
  await publishButton.click()

  // ★ 「이름이 목록에 보인다」로는 아무것도 못 잰다 — 이 시나리오는 이름을 바꾸지 않으므로
  //   그 단언은 발행 전에도, 발행이 409 로 죽어도 참이다. 발행이 정규 정의를 교체했다는 증거는
  //   **뺀 상태 하나가 목록의 상태 수에서 사라진 것**이다(P3 가 「발행 전에는 안 준다」를 재는
  //   바로 그 값).
  await page.getByRole('button', { name: '목록으로' }).click()
  const row = page
    .getByRole('table', { name: '워크플로우 목록' })
    .getByRole('row')
    .filter({ hasText: TARGET_NAME })
  // 열 순서는 이름 · 키 · 상태 수 · 전환 수 · 조작(`routes/admin.workflows.tsx`).
  await expect(row.getByRole('cell').nth(2)).toHaveText(String(beforeCount - 1))
})
