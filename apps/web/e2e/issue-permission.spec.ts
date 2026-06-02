// FR-PM-02 이슈 권한 E2E — 역할별 버튼 활성/비활성 검증 (ADMIN vs MEMBER)
//
// MSW 권한 핸들러(issue-permission-handlers.ts)가 Authorization 토큰의 username을 기준으로
//   alice → adminPermissionsFixture  (UPDATE/SOFT_DELETE/TRANSITION 모두 true)
//   bob   → memberPermissionsFixture (UPDATE/TRANSITION true, SOFT_DELETE false)
// 로 응답한다. 로그인 사용자만 바꾸면 권한이 자동으로 달라진다.
import { test, expect } from '@playwright/test'
import { loginAsAlice, loginAsBob, createIssueViaUI } from './fixtures/issue-fixtures'

test.describe('이슈 권한 분리 (FR-PM-02)', () => {
  // ─────────────────────────────────────────────────────────────────────────────
  // S1 — ADMIN(alice): 삭제 버튼 + 제목 저장 버튼 모두 활성
  //
  // Given  alice(PROJECT_ADMIN)로 로그인
  // When   이슈 상세 화면 진입
  // Then   삭제 버튼 enabled, 제목 저장 버튼 enabled
  // ─────────────────────────────────────────────────────────────────────────────
  test('S1 ADMIN(alice) — 삭제 버튼·수정 버튼 모두 활성(enabled)', async ({ page }) => {
    // Given: alice로 로그인
    await loginAsAlice(page)

    // 이슈 생성 후 상세 페이지 진입
    await createIssueViaUI(page)

    // 제목 편집 모드 진입 (저장 버튼 노출)
    await page.getByRole('button', { name: '✎ 제목 수정' }).click()

    // When + Then: 제목 저장 버튼 enabled
    const titleSaveButton = page.getByTestId('issue-title-save')
    await expect(titleSaveButton).toBeVisible()
    await expect(titleSaveButton).toBeEnabled()

    // Then: 삭제 버튼 enabled (권한 로딩 완료 대기)
    const deleteButton = page.getByTestId('issue-delete')
    await expect(deleteButton).toBeVisible()
    await expect(deleteButton).toBeEnabled()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S2 — MEMBER(bob): 삭제 버튼 비활성 + 사유 title, 제목 저장 버튼 활성
  //
  // Given  bob(MEMBER)으로 로그인
  // When   이슈 상세 화면 진입
  // Then   삭제 버튼 disabled + title="삭제 권한이 없습니다"
  //        제목 저장 버튼 enabled (UPDATE 권한 있음)
  // ─────────────────────────────────────────────────────────────────────────────
  test('S2 MEMBER(bob) — 삭제 버튼 비활성(disabled) + 사유 표시, 수정 버튼은 활성', async ({ page }) => {
    // Given: bob으로 로그인
    await loginAsBob(page)

    // 이슈 생성 후 상세 페이지 진입
    await createIssueViaUI(page)

    // 제목 편집 모드 진입 (저장 버튼 노출)
    await page.getByRole('button', { name: '✎ 제목 수정' }).click()

    // Then: 제목 저장 버튼 enabled (bob은 UPDATE 권한 있음)
    const titleSaveButton = page.getByTestId('issue-title-save')
    await expect(titleSaveButton).toBeVisible()
    await expect(titleSaveButton).toBeEnabled()

    // Then: 삭제 버튼 disabled (SOFT_DELETE 권한 없음)
    const deleteButton = page.getByTestId('issue-delete')
    await expect(deleteButton).toBeVisible()
    await expect(deleteButton).toBeDisabled()

    // Then: 삭제 버튼 title 속성에 사유 표시
    await expect(deleteButton).toHaveAttribute('title', '삭제 권한이 없습니다')
  })
})
