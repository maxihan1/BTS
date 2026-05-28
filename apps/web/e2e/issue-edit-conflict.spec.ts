// FR-IS-01 D7 E2E-5 동시 편집 409 회귀 가드 — PR #26 EC-1 (낙관적 업데이트 + 409 롤백 + sonner toast) 가드
import { test, expect } from '@playwright/test'
import { loginAsAlice, createIssueViaUI, i18nLabels } from './fixtures/issue-fixtures'
import { MOCK_CONFLICT_TRIGGER } from '../src/mocks/issue-handlers'

test.describe('E2E-5 동시 편집 409 회귀 가드', () => {
  test('summary 인라인 수정이 409 응답을 받으면 toast + 편집모드 유지 + 취소로 원본 복원', async ({ page }) => {
    // ── Given. alice 로그인 + 새 이슈 생성 (원본 summary 보존 검증용) ─────────────
    await loginAsAlice(page)
    const originalSummary = 'E2E-5 원본 제목 (롤백 대상)'
    await createIssueViaUI(page, originalSummary)

    // ── When. 편집 모드 진입 → MOCK_CONFLICT_TRIGGER 입력 → 저장 (409 응답 유도) ──
    await page.getByRole('button', { name: i18nLabels.issueDetail.editTitleButton }).click()
    const titleInput = page.getByLabel(i18nLabels.issueDetail.titleEditLabel)
    await expect(titleInput).toBeVisible()
    await titleInput.fill(MOCK_CONFLICT_TRIGGER)
    await page.getByRole('button', { name: i18nLabels.issueDetail.saveButton }).click()

    // ── Then 1. sonner toast 노출 (useUpdateIssueSummary onError 409 분기) ─────
    // useUpdateIssueSummary.ts:73 의 toast.error 메시지 정합 검증
    await expect(
      page.getByText('다른 사용자가 이미 이 이슈를 수정했습니다. 새로고침 후 다시 시도해 주세요.'),
    ).toBeVisible()

    // ── Then 2. 현재 UX = 409 시 편집 모드 유지 (issues.$key.tsx:98 의 onSuccess
    //   에만 setIsEditingTitle(false), onError 미처리) — 사용자가 값 재입력/취소
    //   결정 가능하도록 input 보존. input 가 계속 노출 + local state 유지 검증.
    await expect(titleInput).toBeVisible()
    await expect(titleInput).toHaveValue(MOCK_CONFLICT_TRIGGER)

    // ── When 2. 취소 버튼 클릭 → 편집 모드 종료 ────────────────────────────────
    await page.getByRole('button', { name: i18nLabels.issueDetail.cancelButton }).click()

    // ── Then 3. 편집 모드 종료 + h1 헤딩에 원본 summary 노출 (cache rollback
    //   완료 검증 — onError snapshot 롤백 + onSettled invalidateQueries 재조회).
    await expect(titleInput).not.toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: originalSummary })).toBeVisible()
  })
})
