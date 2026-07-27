// FR-CO-02 E2E — 댓글 수정·삭제 (인라인 편집 · 확인 다이얼로그 · 어포던스 게이팅 · HTML 배선)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 최초 goto 후 SPA 내부 동작으로만 검증
//     (reload/goto 재진입 금지 — service worker store 가 새 모듈로 재시작된다)
//   - msw-mutation-stateful-refetch: comment-handlers 의 commentStore 가 영속이라
//     PATCH/DELETE 후 invalidateQueries refetch 가 바뀐 결과를 실제로 돌려준다
//   - e2e-fixture-whoami-userid-alignment: alice userId = 00000000-...-001 (auth-fixtures 정본)
//     이 값이 어긋나면 "본인 댓글" 판정이 깨져 수정 버튼이 통째로 사라진다
//   - playwright-getbyrole-exact-strict-mode: 같은 이름의 버튼이 행마다·다이얼로그에도 있으므로
//     모든 버튼 조회를 **행 또는 다이얼로그 컨테이너로 한정**하고 exact:true 를 붙인다
//     (특히 "삭제" 는 행 트리거와 다이얼로그 확인 버튼이 동명이다)
//   - ui-pr-defer-e2e-regression-latent: 같은 컴포넌트를 바꾼 PR 이므로 기존
//     issue-comment.spec.ts 4시나리오를 항상 함께 실행한다
//
// MSW 핵심.
//   - 쓰기 3종 모두 저작자를 요청 본문이 아니라 **Bearer 토큰**에서 도출한다(백엔드 계약 재현).
//     S4 의 "남의 댓글" 은 이 성질을 이용해 bob 토큰으로 직접 POST 해서 만든다.
//   - alice = SOFT_DELETE 보유(모더레이터), bob = 미보유.
//   - commentStore 는 모듈 스코프 stateful. 각 test 는 새 context(새 ServiceWorker)로 시작하므로
//     초기 진입 시 댓글 0건이다.
//
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { commentStrings, issueDetailStrings } from '../src/i18n/ko'

/** 테스트 대상 이슈 — ATLAS-1 (issue-fixtures.ts 정적 fixture) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** bob 의 mock access token — comment-handlers 의 `MOCK_TOKEN_PREFIX + username` 관례 */
const BOB_BEARER = 'Bearer mock-access-token-bob'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 댓글 탭으로 전환하고 댓글 섹션 locator 를 돌려준다.
 *
 * 기본 활성 탭은 "이력" 이라 댓글 섹션은 탭 전환 전에는 마운트조차 되지 않는다
 * (issue-comment.spec.ts 의 D8 회귀 고정 시나리오가 그 사실을 붙들고 있다).
 *
 * @param page Playwright Page
 * @returns 댓글 섹션 locator (이후 모든 조회의 컨테이너)
 */
async function openCommentTab(page: Page): Promise<Locator> {
  await page.getByRole('tab', { name: issueDetailStrings.activityCommentTabLabel }).click()
  const section = page.getByRole('region', { name: commentStrings.commentSectionTitle })
  await expect(section).toBeVisible()
  return section
}

/**
 * 작성 폼으로 댓글 1건을 작성하고 목록에 반영될 때까지 기다린다.
 *
 * UI 를 통과시키는 이유 — 저작자를 alice(현재 로그인 사용자)로 만들기 위해서다.
 * MSW 가 토큰에서 저작자를 도출하므로 앱의 apiFetch 경로를 타야 alice 댓글이 된다.
 *
 * @param section 댓글 섹션 locator
 * @param body 작성할 본문
 */
async function addCommentViaUi(section: Locator, body: string): Promise<void> {
  await section.getByLabel(commentStrings.commentBodyLabel).fill(body)
  await section.getByRole('button', { name: commentStrings.commentAddButton, exact: true }).click()
  await expect(commentRow(section, body)).toBeVisible()
}

/**
 * 본문 텍스트로 댓글 행(li)을 찾는다.
 *
 * 페이지 전역 `getByRole` 대신 **행 단위 컨테이너**를 만들어 두는 것이 이 spec 의 핵심 관례다.
 * 수정/삭제 버튼은 행마다 같은 접근성 이름을 갖고, "삭제" 는 확인 다이얼로그에도 또 있다.
 *
 * ★편집 **도중**에는 쓰지 말 것 — [commentRowAt] 을 쓴다. 이유는 그쪽 주석에 있다.
 *
 * @param section 댓글 섹션 locator
 * @param body 찾을 본문 텍스트
 * @returns 해당 댓글의 li locator
 */
function commentRow(section: Locator, body: string): Locator {
  return section.locator('li').filter({ hasText: body })
}

/**
 * 순서(작성 시각 오름차순)로 댓글 행(li)을 찾는다 — 편집 흐름 전용.
 *
 * 편집 중에는 본문 텍스트 필터가 **대상을 놓친다**. React 가 `<textarea value>` 를 갱신할 때
 * `defaultValue`(= textarea 의 자식 텍스트)까지 함께 동기화하므로, 초안을 한 글자 고치는 순간
 * 그 행의 `textContent` 가 초안 텍스트로 바뀐다. 그러면 `filter({ hasText: 원문 })` 이 0건이 되어
 * 저장·취소 버튼을 영영 못 찾는다(실측 — 타임아웃 실패 2건의 원인).
 * 순서는 편집으로 바뀌지 않으므로 인덱스가 이 흐름에서 유일하게 안정적인 좌표다.
 *
 * @param section 댓글 섹션 locator
 * @param index 0부터 시작하는 행 순번
 * @returns 해당 순번 댓글의 li locator
 */
function commentRowAt(section: Locator, index: number): Locator {
  return section.locator('li').nth(index)
}

/**
 * bob 이 쓴 댓글을 만든다 — 앱 세션(alice)은 건드리지 않는다.
 *
 * 페이지 컨텍스트에서 bob 토큰으로 직접 POST 한다. MSW 핸들러가 **요청 본문이 아니라 토큰**에서
 * 저작자를 도출하므로 이 한 번의 요청만 bob 이 되고, 화면의 로그인 사용자는 alice 그대로다.
 * 로그아웃→bob 로그인→다시 alice 경로는 전체 페이지 재적재를 부르고, 그 순간 모듈 스코프
 * commentStore 가 초기화돼 방금 심은 댓글이 사라진다.
 *
 * ★호출 전에 **앱이 MSW 응답을 한 번 받은 것을 확인**해야 한다. `page.goto` 는 load 이벤트에서
 * 풀리지만 `worker.start()` 는 그 뒤에 끝나므로, 그 사이의 raw fetch 는 가로채이지 않고 vite 의
 * `/api` → :8080 프록시로 흘러 **500** 이 된다(백엔드 미기동. 실측으로 확인한 실패 원인).
 *
 * 응답 코드를 단언해 "가로채이지 않아 조용히 실패" 를 차단한다 — MSW 가 인터셉트하지 못하면
 * 201 이 나올 수 없으므로 여기서 즉시 터진다(거짓 초록 방지).
 *
 * @param page Playwright Page
 * @param body bob 이 쓸 본문
 */
async function seedBobComment(page: Page, body: string): Promise<void> {
  const status = await page.evaluate(
    async ({ url, text, bearer }: { url: string; text: string; bearer: string }) => {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: bearer },
        body: JSON.stringify({ body: text }),
      })
      return res.status
    },
    { url: `/api/v1/issues/${ISSUE_KEY}/comments`, text: body, bearer: BOB_BEARER },
  )
  expect(status).toBe(201)
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-CO-02 이슈 댓글 수정·삭제', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(ISSUE_URL)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 수정 → 목록 반영 + "(수정됨)"
  //
  // Given  alice 가 쓴 댓글 1건이 목록에 있다
  // When   그 행의 수정 버튼 → 인라인 편집에서 본문을 고쳐 저장한다
  // Then   목록이 새 본문으로 바뀌고, updatedAt != createdAt 이므로 "(수정됨)" 이 붙는다
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 — 본인 댓글을 수정하면 목록에 반영되고 "(수정됨)" 이 붙는다', async ({ page }) => {
    const section = await openCommentTab(page)
    await addCommentViaUi(section, '고치기 전 본문')

    // 댓글이 1건뿐이므로 0번 행이 곧 그 댓글이다 (편집 중에도 안 흔들리는 좌표)
    await expect(section.locator('li')).toHaveCount(1)
    const row = commentRowAt(section, 0)
    // 수정 전에는 이력 표시가 없어야 한다 — 있으면 아래 Then 이 무의미해진다
    await expect(row.getByText(commentStrings.commentEditedBadge)).toHaveCount(0)

    await row.getByRole('button', { name: commentStrings.commentEditButton, exact: true }).click()

    const editBox = row.getByLabel(commentStrings.commentEditBodyLabel)
    await expect(editBox).toHaveValue('고치기 전 본문')
    await editBox.fill('고친 뒤 본문')
    await row
      .getByRole('button', { name: commentStrings.commentEditSaveButton, exact: true })
      .click()

    // Then. stateful store → invalidateQueries refetch 후에도 새 본문이 살아 있다
    const updatedRow = commentRow(section, '고친 뒤 본문')
    await expect(updatedRow).toBeVisible()
    await expect(section.getByText('고치기 전 본문')).toHaveCount(0)
    await expect(updatedRow.getByText(commentStrings.commentEditedBadge)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 삭제 → 확인 다이얼로그 → 목록에서 사라진다
  //
  // Given  댓글 2건이 있다 (지울 것 / 남을 것)
  // When   지울 행의 삭제 버튼 → 확인 다이얼로그에서 "삭제" 를 누른다
  // Then   그 행만 목록에서 사라지고 나머지 1건은 남는다
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 — 삭제는 확인 다이얼로그를 거쳐 그 댓글만 목록에서 사라진다', async ({ page }) => {
    const section = await openCommentTab(page)
    await addCommentViaUi(section, '지울 댓글')
    await addCommentViaUi(section, '남을 댓글')

    await commentRow(section, '지울 댓글')
      .getByRole('button', { name: commentStrings.commentDeleteButton, exact: true })
      .click()

    // 다이얼로그는 Portal 로 섹션 밖에 렌더된다 — 확인 버튼은 행 트리거와 동명("삭제")이므로
    // alertdialog 컨테이너로 한정해야 strict mode 위반을 피한다
    const dialog = page.getByRole('alertdialog')
    await expect(dialog.getByText(commentStrings.commentDeleteDialogTitle)).toBeVisible()
    await dialog
      .getByRole('button', { name: commentStrings.commentDeleteDialogConfirm, exact: true })
      .click()

    // Then. 지목한 행만 사라진다 (전량 삭제가 아니라는 대조까지 확인)
    await expect(commentRow(section, '지울 댓글')).toHaveCount(0)
    await expect(commentRow(section, '남을 댓글')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 — 편집 취소 → 원문 유지
  //
  // Given  alice 댓글 1건을 편집 모드로 열고 본문을 고쳐 둔 상태
  // When   저장하지 않고 취소를 누른다
  // Then   목록은 원문 그대로이고 편집 모드가 닫힌다 (고친 초안은 버려진다)
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 — 편집 도중 취소하면 원문이 유지된다', async ({ page }) => {
    const section = await openCommentTab(page)
    await addCommentViaUi(section, '원래 본문')

    await expect(section.locator('li')).toHaveCount(1)
    const row = commentRowAt(section, 0)
    await row.getByRole('button', { name: commentStrings.commentEditButton, exact: true }).click()
    await row.getByLabel(commentStrings.commentEditBodyLabel).fill('버려질 초안')

    await row
      .getByRole('button', { name: commentStrings.commentEditCancelButton, exact: true })
      .click()

    // Then. 원문이 그대로 남고, 저장이 일어나지 않았으므로 "(수정됨)" 도 붙지 않는다
    const keptRow = commentRow(section, '원래 본문')
    await expect(keptRow).toBeVisible()
    await expect(keptRow.getByLabel(commentStrings.commentEditBodyLabel)).toHaveCount(0)
    await expect(section.getByText('버려질 초안')).toHaveCount(0)
    await expect(keptRow.getByText(commentStrings.commentEditedBadge)).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 — 남의 댓글에는 수정 버튼이 없다
  //
  // Given  bob 이 쓴 댓글 1건 + alice 가 쓴 댓글 1건이 있고, alice(SOFT_DELETE 보유)로 보고 있다
  // When   두 행의 어포던스를 비교한다
  // Then   bob 행에는 수정 버튼이 없고 삭제 버튼만 있다. alice 행에는 수정 버튼이 있다.
  //
  // ★대조가 없으면 vacuous 하다 — 버튼이 통째로 안 그려져도 "수정 버튼 없음" 은 통과한다.
  //   그래서 (a) 같은 행의 삭제 버튼 존재와 (b) 본인 행의 수정 버튼 존재를 함께 단언한다.
  //   수정 = 작성자 뿐 / 삭제 = 작성자 OR 모더레이터 라는 **서로 다른 술어**를 이 대조가 고정한다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 — 남의 댓글엔 수정 버튼이 없고 삭제만 보인다 (모더레이터 alice)', async ({ page }) => {
    // Given. 빈 상태 문구가 뜰 때까지 기다린다 — 목록 GET 이 MSW 를 통해 실제로 성공했다는 뜻이고,
    // 이 지점 이후여야 아래 raw fetch 가 vite 프록시로 새지 않는다 (seedBobComment 주석 참조)
    const section = await openCommentTab(page)
    await expect(section.getByText(commentStrings.commentEmptyState)).toBeVisible()

    await seedBobComment(page, 'bob 이 쓴 댓글')

    // alice 가 자기 댓글을 쓰면 그 POST 의 invalidateQueries 가 목록을 다시 읽어,
    // 방금 심은 bob 댓글까지 함께 화면에 올라온다 (대조군 확보 + 새로고침 없이 반영)
    await addCommentViaUi(section, 'alice 가 쓴 댓글')

    const bobRow = commentRow(section, 'bob 이 쓴 댓글')
    const aliceRow = commentRow(section, 'alice 가 쓴 댓글')
    await expect(bobRow).toBeVisible()

    // Then. 남의 댓글 — 수정 불가, 삭제는 모더레이터 권한으로 가능
    await expect(
      bobRow.getByRole('button', { name: commentStrings.commentEditButton, exact: true }),
    ).toHaveCount(0)
    await expect(
      bobRow.getByRole('button', { name: commentStrings.commentDeleteButton, exact: true }),
    ).toBeVisible()

    // Then. 본인 댓글 — 수정 버튼이 실제로 그려진다 (위 단언이 vacuous 가 아님을 고정)
    await expect(
      aliceRow.getByRole('button', { name: commentStrings.commentEditButton, exact: true }),
    ).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 — 본문이 HTML 로 렌더된다 (배선만)
  //
  // Given  댓글 1건. MSW 모크는 본문을 `<p>...</p>` 로 **감싸기만** 한다(변환도 정화도 없다)
  // When   목록에서 그 행의 본문 영역을 본다
  // Then   `<p>` 가 **DOM 요소**로 들어가 있고, 화면 텍스트에 `<p>` 리터럴이 보이지 않는다
  //
  // ★단언 범위 — 여기서 증명되는 것은 "서버가 준 bodyHtml 이 텍스트가 아니라 DOM 으로 들어갔다"
  //   **배선뿐**이다. 마크다운 변환·XSS 정화는 이 모크가 하지 않으므로 프론트에서 단언하면
  //   모크를 검증하는 거짓 초록이 된다(comment-handlers.ts 상단 경고). 그 둘은 백엔드
  //   CommentControllerIntegrationTest 가 증명한다.
  //
  //   판별력. body 를 텍스트로 렌더 → <p> 요소 0개 / bodyHtml 을 이스케이프 렌더 → 화면에
  //   "<p>" 리터럴 노출 / bodyHtml 을 DOM 으로 렌더 → 아래 두 단언만 통과.
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 — 본문은 bodyHtml 이 DOM 으로 렌더된다 (텍스트가 아니다)', async ({ page }) => {
    const section = await openCommentTab(page)
    await addCommentViaUi(section, 'HTML 배선 확인용 본문')

    // data-testid 는 comment-body-{uuid} 라 접두사로만 잡는다 (id 를 미리 알 수 없다)
    const bodyEl = commentRow(section, 'HTML 배선 확인용 본문').locator(
      '[data-testid^="comment-body-"]',
    )

    await expect(bodyEl.locator('p')).toHaveCount(1)
    await expect(bodyEl.locator('p')).toHaveText('HTML 배선 확인용 본문')
    await expect(bodyEl).not.toContainText('<p>')
  })
})
