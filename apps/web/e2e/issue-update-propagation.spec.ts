// 이슈를 고치면 **새로고침 없이** 뒤에 있는 목록이 따라 바뀐다 (Maxi 보고 2026-09-07)
//
// 🛑 어느 단계에서도 `page.reload()` 를 쓰지 않는다. 새로고침을 넣는 순간 이 스펙은 고치기
//    전에도 초록이 되고, 재현하려던 증상을 그대로 놓친다.
//
// ## 왜 「모달 + 뒤의 목록」인가
// 사용자가 본 화면이 그것이다 — 목록에서 행을 눌러 상세를 열고, 고치고, 닫으면 목록은 옛 값
// 그대로였다. 목록은 모달 뒤에 **계속 마운트돼 있으므로** 캐시가 무효화되면 그 자리에서 다시
// 그려진다. 이동도 새로고침도 필요 없다는 것이 계약이다.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { issueDetailStrings } from '../src/i18n/ko'

const TARGET_KEY = 'ATLAS-1'
const ORIGINAL_SUMMARY = '첫 번째 이슈 — 로그인 페이지 구현'
const UPDATED_SUMMARY = '전파 확인용 새 제목'

test.describe('이슈 수정이 목록에 즉시 반영된다 (FR-IS-04)', () => {
  test('상세 모달에서 제목을 바꾸면 뒤의 목록 행이 새로고침 없이 따라간다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/projects/ATLAS/issues')

    const table = page.getByRole('table', { name: '이슈 목록' })
    const row = table.getByRole('row').filter({ hasText: TARGET_KEY })
    await expect(row).toBeVisible()

    // 앵커 — 바꾸기 전 제목을 실제로 읽는다. 안 읽으면 「안 바뀌었다」와 「원래 그랬다」를
    // 구분할 수 없다.
    await expect(row).toContainText(ORIGINAL_SUMMARY)
    await expect(row).not.toContainText(UPDATED_SUMMARY)

    // 행을 눌러 상세 모달을 연다 (표시 방식 기본이 모달 · J1)
    await row.getByRole('link', { name: TARGET_KEY }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // 제목을 고친다 — 제목 텍스트 자체가 편집 진입면이다(`inline-edit.spec.ts` 선례).
    // 🛑 `dialog.getByRole('heading').first()` 를 쓰지 마라 — 그것은 다이얼로그 제목
    //    (「이슈 상세 ATLAS-1」)이고 버튼이 없다.
    // ★2026-09-07 — 종전에는 `region "이슈 상세"` 안으로 좁혔는데 **그 전제가 깨졌다.**
    //   본문/메타 독립 스크롤(J25~J27)로 제목이 스크롤 영역 **밖** 고정 헤더로 옮겨졌고,
    //   그 region 은 이제 본문 스크롤 영역만 가리킨다. 다이얼로그 범위에서 이름으로 지목한다 —
    //   이 이름을 가진 버튼은 상세 전체에 하나뿐이다.
    await dialog.getByRole('button', { name: ORIGINAL_SUMMARY, exact: true }).click()
    const titleInput = page.getByLabel(issueDetailStrings.titleEditLabel)
    await expect(titleInput).toBeVisible()
    await titleInput.fill(UPDATED_SUMMARY)
    await titleInput.press('Enter')

    // 상세가 먼저 반영된다
    await expect(dialog).toContainText(UPDATED_SUMMARY)

    // 모달을 닫는다 — **새로고침이 아니다.** SPA 상태도 캐시도 그대로다.
    // 🛑 닫지 않으면 뒤의 목록을 조회할 수 없다. Radix Dialog 가 배경을 `aria-hidden` 으로
    //    덮어 role 조회가 통째로 실패한다(2026-09-07 실측 — 「element(s) not found」).
    await dialog.getByRole('button', { name: '닫기' }).click()
    await expect(dialog).toBeHidden()

    // ★목록 행이 **새로고침 없이** 따라간다 — 이것이 이 스펙의 본체다.
    //  고치기 전에는 `['issue', key]` 하나만 무효화해 목록 캐시가 옛 제목을 든 채 남았다.
    await expect(row).toContainText(UPDATED_SUMMARY, { timeout: 10_000 })
    await expect(row).not.toContainText(ORIGINAL_SUMMARY)
  })
})
