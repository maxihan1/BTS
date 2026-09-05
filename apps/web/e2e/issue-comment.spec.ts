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
//   - 댓글 입력은 TipTap contenteditable 이다(J8). value 가 없으므로 비움 검증은 toHaveText('')
//   - ★그리고 **작성 중인 초안이 DOM 텍스트로 존재한다**. textarea 시절 초안은 value 라
//     `section.getByText(...)` 에 안 걸렸지만 contenteditable 은 걸린다 — 목록 반영을
//     섹션 전체에서 세면 **에디터 자신을 세고 초록**이 되는 가짜 그린이다.
//     그래서 목록 검증은 `postedBodies`(data-testid=comment-body-*)로만 한다.
//
// MSW 핵심.
//   - commentStore 는 모듈 스코프 stateful. 각 test 는 새 context(새 ServiceWorker)로
//     시작하므로 초기 진입 시 댓글 0건 = 빈 상태가 보인다.
//   - POST 는 요청 본문이 아니라 Bearer 토큰에서 저작자를 도출한다(백엔드 계약 재현).
//
import { test, expect } from '@playwright/test'
import type { Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { commentStrings, issueDetailStrings } from '../src/i18n/ko'

/** 테스트 대상 이슈 — ATLAS-1 (issue-fixtures.ts 정적 fixture) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/**
 * 목록에 실제로 반영된 댓글 본문들.
 *
 * `CommentRow` 가 `data-testid="comment-body-<id>"` 로 렌더하므로 **저장된 댓글만** 잡힌다.
 * 작성 중인 TipTap 초안은 여기에 포함되지 않는다.
 */
function postedBodies(section: Locator): Locator {
  return section.locator('[data-testid^="comment-body-"]')
}

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
    const editor = section.getByLabel(commentStrings.commentBodyLabel)
    await expect(editor).toBeVisible()

    // 빈 본문에서는 제출 버튼이 비활성
    const submit = section.getByRole('button', { name: commentStrings.commentAddButton })
    await expect(submit).toBeDisabled()

    await editor.fill('E2E 에서 작성한 댓글')
    await expect(submit).toBeEnabled()
    await submit.click()

    // 목록에 반영 (stateful MSW store → refetch 가 방금 만든 댓글을 돌려준다).
    // ★섹션 전체가 아니라 **목록 본문**에서 찾는다 — 에디터 초안이 같은 글자를 들고 있다.
    await expect(postedBodies(section).filter({ hasText: 'E2E 에서 작성한 댓글' })).toHaveCount(1)
    // 빈 상태 문구는 사라져야 한다
    await expect(section.getByText(commentStrings.commentEmptyState)).toHaveCount(0)
    // 성공 시에만 입력을 비운다.
    // ★`toHaveValue` 를 쓰지 않는다 — 댓글 입력은 J8 에서 TipTap contenteditable 이 되었고
    //   `toHaveValue` 는 input/textarea/select 전용이라 「Not an input element」로 죽는다.
    //   비어 있는 TipTap 문서는 `<p><br></p>` 라 textContent 가 '' 다.
    await expect(editor).toHaveText('')
  })

  test('연속 작성 시 두 댓글이 모두 남는다 — 멱등 처리하지 않는다', async ({ page }) => {
    await page.getByRole('tab', { name: issueDetailStrings.activityCommentTabLabel }).click()

    const section = page.getByRole('region', { name: commentStrings.commentSectionTitle })
    const editor = section.getByLabel(commentStrings.commentBodyLabel)
    const submit = section.getByRole('button', { name: commentStrings.commentAddButton })

    const sameWords = () => postedBodies(section).filter({ hasText: '같은 말' })

    await editor.fill('같은 말')
    await submit.click()
    // 목록에 1건이 실릴 때까지 기다린다. 이 대기가 곧 「저장이 끝났다」의 신호이며,
    // 저장 중에는 에디터가 `contenteditable=false` 라 다음 fill 이 즉시 죽는다.
    await expect(sameWords()).toHaveCount(1)
    await expect(editor).toHaveText('')

    await editor.fill('같은 말')
    await submit.click()

    // ★EC-7 — 같은 말을 두 번 하는 것은 정상 행위다. 멱등 처리하지 않는다.
    await expect(sameWords()).toHaveCount(2)
  })
})
