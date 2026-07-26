// FR-CO-01 E2E — 댓글 탭 진입 · 작성 → 목록 반영 · 기본 탭 회귀 고정 (T7)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 최초 goto 후 SPA 내부 동작으로만 검증
//     (reload/goto 재진입 금지 — service worker store 가 새 모듈로 재시작된다)
//   - msw-mutation-stateful-refetch: comment-handlers 의 commentStore 가 영속이라
//     POST 후 invalidateQueries refetch 가 방금 만든 댓글을 실제로 돌려준다
//   - e2e-fixture-whoami-userid-alignment: alice userId = 00000000-...-001 (auth-fixtures 정본)
//   - playwright-getbyrole-exact-strict-mode: 탭/버튼은 접근성 이름으로 정확 조회
//   - frontend-nav-aria-label-e2e-contract: 탭 라벨은 E2E 계약 — i18n 정본을 import 한다
//
// MSW 핵심.
//   - commentStore 는 모듈 스코프 stateful. 각 test 는 새 context(새 ServiceWorker)로
//     시작하므로 초기 진입 시 댓글 0건 = 빈 상태가 보인다.
//   - POST 는 요청 본문이 아니라 Bearer 토큰에서 저작자를 도출한다(백엔드 계약 재현).
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { commentStrings, issueDetailStrings } from '../src/i18n/ko'

/** 테스트 대상 이슈 — ATLAS-1 (issue-fixtures.ts 정적 fixture) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

test.describe('FR-CO-01 이슈 댓글', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(ISSUE_URL)
  })

  test('기본 활성 탭은 이력이다 — 댓글 탭은 초기 미활성 (D8 회귀 고정)', async ({ page }) => {
    const historyTab = page.getByRole('tab', { name: issueDetailStrings.activityHistoryTabLabel })
    const commentTab = page.getByRole('tab', { name: issueDetailStrings.activityCommentTabLabel })

    await expect(historyTab).toBeVisible()
    await expect(commentTab).toBeVisible()

    // ★D8 — CO-01 은 쓰기 노출이 본질이므로 기존 기본 탭을 바꾸지 않는다.
    await expect(historyTab).toHaveAttribute('aria-selected', 'true')
    await expect(commentTab).toHaveAttribute('aria-selected', 'false')

    // 댓글 섹션은 아직 마운트되지 않았다
    await expect(page.getByRole('region', { name: commentStrings.commentSectionTitle })).toHaveCount(0)
  })

  test('댓글 탭에 진입하면 빈 상태가 보인다', async ({ page }) => {
    await page.getByRole('tab', { name: issueDetailStrings.activityCommentTabLabel }).click()

    const section = page.getByRole('region', { name: commentStrings.commentSectionTitle })
    await expect(section).toBeVisible()
    await expect(section.getByText(commentStrings.commentEmptyState)).toBeVisible()
  })

  test('댓글을 작성하면 목록에 반영되고 입력이 비워진다', async ({ page }) => {
    await page.getByRole('tab', { name: issueDetailStrings.activityCommentTabLabel }).click()

    const section = page.getByRole('region', { name: commentStrings.commentSectionTitle })
    const textarea = section.getByLabel(commentStrings.commentBodyLabel)
    await expect(textarea).toBeVisible()

    // 빈 본문에서는 제출 버튼이 비활성
    const submit = section.getByRole('button', { name: commentStrings.commentAddButton })
    await expect(submit).toBeDisabled()

    await textarea.fill('E2E 에서 작성한 댓글')
    await expect(submit).toBeEnabled()
    await submit.click()

    // 목록에 반영 (stateful MSW store → refetch 가 방금 만든 댓글을 돌려준다)
    await expect(section.getByText('E2E 에서 작성한 댓글')).toBeVisible()
    // 빈 상태 문구는 사라져야 한다
    await expect(section.getByText(commentStrings.commentEmptyState)).toHaveCount(0)
    // 성공 시에만 입력을 비운다
    await expect(textarea).toHaveValue('')
  })

  test('연속 작성 시 두 댓글이 모두 남는다 — 멱등 처리하지 않는다', async ({ page }) => {
    await page.getByRole('tab', { name: issueDetailStrings.activityCommentTabLabel }).click()

    const section = page.getByRole('region', { name: commentStrings.commentSectionTitle })
    const textarea = section.getByLabel(commentStrings.commentBodyLabel)
    const submit = section.getByRole('button', { name: commentStrings.commentAddButton })

    await textarea.fill('같은 말')
    await submit.click()
    await expect(section.getByText('같은 말')).toHaveCount(1)

    await textarea.fill('같은 말')
    await submit.click()

    // ★EC-7 — 같은 말을 두 번 하는 것은 정상 행위다. 멱등 처리하지 않는다.
    await expect(section.getByText('같은 말')).toHaveCount(2)
  })
})
