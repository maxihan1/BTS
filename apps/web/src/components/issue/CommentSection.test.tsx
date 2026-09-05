// CommentSection 컴포넌트 단위 테스트 — FR-CO-01 T6 목록·작성 / FR-CO-02 T7 수정·삭제·bodyHtml
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactNode, RefObject } from 'react'
import { createRef } from 'react'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { aliceUser } from '@/mocks/auth-fixtures'
import { commentStrings } from '@/i18n/ko'
import { CommentSection } from './CommentSection'

// sonner toast mock — 실제 DOM 없이 호출 여부만 검증 (WorklogSection.test 선례)
vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))
import { toast } from 'sonner'

// useUsersByIds mock — displayName 해석을 제어한다
vi.mock('@/hooks/use-users', () => ({
  useUsersByIds: vi.fn(),
}))
import { useUsersByIds } from '@/hooks/use-users'

// RichTextEditor 대역 — jsdom 에서 ProseMirror 타이핑이 재현되지 않아 textarea 로 갈음한다.
// 대역이 prop 을 삼키지 않는 이유와 목록은 `@/test/rich-text-editor-mock` KDoc 참조.
vi.mock('@/components/editor/RichTextEditor', async () => ({
  RichTextEditor: (await import('@/test/rich-text-editor-mock')).RichTextEditorMock,
}))
import type { CommentResponse } from '@/api/comments'
import { COMMENT_BODY_MAX_LENGTH } from '@/lib/issue-text-constraints'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — RFC4122 v4 형식 UUID (Zod v4 검증 통과)
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_UUID = 'a0000000-0000-4000-a000-000000000001'
const BOB_UUID = 'b0000000-0000-4000-a000-000000000002'
const GHOST_UUID = 'd0000000-0000-4000-a000-000000000009'
const CM_ID_1 = 'c0000000-0000-4000-a000-000000000001'
const CM_ID_2 = 'c0000000-0000-4000-a000-000000000002'
const CM_ID_3 = 'c0000000-0000-4000-a000-000000000003'

const ISSUE_KEY = 'ATLAS-1'
const LIST_URL = `*/api/v1/issues/${ISSUE_KEY}/comments`

const earlierComment: CommentResponse = {
  id: CM_ID_1,
  authorId: ALICE_UUID,
  body: '먼저 쓴 댓글',
  bodyHtml: '<p>먼저 쓴 댓글</p>\n',
  createdAt: '2026-07-27T09:00:00Z',
  updatedAt: '2026-07-27T09:00:00Z',
}

const laterComment: CommentResponse = {
  id: CM_ID_2,
  authorId: BOB_UUID,
  body: '나중 쓴 댓글',
  bodyHtml: '<p>나중 쓴 댓글</p>\n',
  createdAt: '2026-07-27T10:00:00Z',
  updatedAt: '2026-07-27T10:00:00Z',
}

/** 작성자를 해석할 수 없는 댓글 (탈퇴·삭제된 사용자) */
const ghostComment: CommentResponse = {
  id: CM_ID_3,
  authorId: GHOST_UUID,
  body: '탈퇴한 사용자의 댓글',
  bodyHtml: '<p>탈퇴한 사용자의 댓글</p>\n',
  createdAt: '2026-07-27T11:00:00Z',
  updatedAt: '2026-07-27T11:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 하네스
// ─────────────────────────────────────────────────────────────────────────────

function renderSection(canUpdate = true, focusCommentId?: string): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  render(
    <CommentSection issueKey={ISSUE_KEY} canUpdate={canUpdate} focusCommentId={focusCommentId} />,
    { wrapper },
  )
}

beforeEach(() => {
  useAuthStore.setState({ user: aliceUser, accessToken: 'test-token' })
  vi.mocked(useUsersByIds).mockReturnValue({
    data: [
      { id: ALICE_UUID, displayName: 'Alice', username: 'alice', email: 'alice@bts.test' },
      { id: BOB_UUID, displayName: 'Bob', username: 'bob', email: 'bob@bts.test' },
    ],
  } as ReturnType<typeof useUsersByIds>)
  server.use(http.get(LIST_URL, () => HttpResponse.json({ data: [earlierComment, laterComment] })))
})

afterEach(() => {
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) 목록 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('CommentSection — (a) 목록 렌더', () => {
  it('댓글을 작성 시각 오름차순으로 렌더한다', async () => {
    renderSection()

    await waitFor(() => {
      expect(screen.getByText('먼저 쓴 댓글')).toBeInTheDocument()
    })
    const items = screen.getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('먼저 쓴 댓글')
    expect(items[1]).toHaveTextContent('나중 쓴 댓글')
  })

  it('작성자 displayName 을 표시한다', async () => {
    renderSection()

    await waitFor(() => {
      expect(screen.getByText('Alice')).toBeInTheDocument()
    })
    expect(screen.getByText('Bob')).toBeInTheDocument()
  })

  it('작성자를 해석할 수 없으면 authorId UUID 를 그대로 표시한다', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json({ data: [ghostComment] })))
    renderSection()

    await waitFor(() => {
      expect(screen.getByText(GHOST_UUID)).toBeInTheDocument()
    })
  })

  it('댓글이 0건이면 빈 상태 문구를 보여준다', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json({ data: [] })))
    renderSection()

    await waitFor(() => {
      expect(screen.getByText(commentStrings.commentEmptyState)).toBeInTheDocument()
    })
  })

  it('목록 403 이면 권한 안내를 보여주고 빈 상태와 구분한다', async () => {
    server.use(
      http.get(LIST_URL, () =>
        HttpResponse.json({ errorCode: 'ISSUE_ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    renderSection()

    await waitFor(() => {
      expect(screen.getByText(commentStrings.commentLoadError)).toBeInTheDocument()
    })
    // ★ 같은 증상(댓글 0개)에 원인이 둘이므로 반드시 구분돼야 한다
    expect(screen.queryByText(commentStrings.commentEmptyState)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) 작성 폼
// ─────────────────────────────────────────────────────────────────────────────

describe('CommentSection — (b) 작성 폼', () => {
  it('본문을 입력해 제출하면 성공 토스트가 뜨고 입력이 비워진다', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(
      http.post(LIST_URL, () => HttpResponse.json({ data: earlierComment }, { status: 201 })),
    )
    renderSection()

    const textarea = await screen.findByLabelText(commentStrings.commentBodyLabel)
    await user.type(textarea, '새 댓글')
    await user.click(screen.getByRole('button', { name: commentStrings.commentAddButton }))

    await waitFor(() => {
      expect(toast.success).toHaveBeenCalledWith(commentStrings.commentAddSuccess)
    })
    expect(textarea).toHaveValue('')
  })

  it('본문이 비어 있으면 제출 버튼이 비활성이다', async () => {
    renderSection()

    const button = await screen.findByRole('button', { name: commentStrings.commentAddButton })
    expect(button).toBeDisabled()
  })

  it('작성 실패 시 에러 토스트가 뜨고 입력 본문이 보존된다', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(
      http.post(LIST_URL, () =>
        HttpResponse.json({ errorCode: 'COMMENT_BODY_TOO_LONG' }, { status: 400 }),
      ),
    )
    renderSection()

    const textarea = await screen.findByLabelText(commentStrings.commentBodyLabel)
    await user.type(textarea, '실패할 댓글')
    await user.click(screen.getByRole('button', { name: commentStrings.commentAddButton }))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith(commentStrings.commentAddError)
    })
    // ★ 사용자가 쓴 글을 잃지 않는다 — 실패 시 입력은 남아야 한다
    expect(textarea).toHaveValue('실패할 댓글')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) 권한 게이팅
// ─────────────────────────────────────────────────────────────────────────────

describe('CommentSection — (c) 권한 게이팅', () => {
  it('canUpdate=false 이면 작성 폼 대신 권한 안내를 보여준다', async () => {
    renderSection(false)

    await waitFor(() => {
      expect(screen.getByText(commentStrings.commentNoPermission)).toBeInTheDocument()
    })
    // 폼이 아예 없어야 한다 — 눌러보고 403 으로 알게 되는 방식 금지
    expect(screen.queryByLabelText(commentStrings.commentBodyLabel)).not.toBeInTheDocument()
  })

  it('canUpdate=true 이면 폼이 있고 권한 안내는 없다', async () => {
    renderSection(true)

    expect(await screen.findByLabelText(commentStrings.commentBodyLabel)).toBeInTheDocument()
    expect(screen.queryByText(commentStrings.commentNoPermission)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-CO-02 T7 — 수정·삭제 어포던스 / 인라인 편집 / bodyHtml 렌더
//
// 저작자 판정은 `authStore` 의 userId 정본을 쓴다. 위 (a)~(c) 픽스처의 ALICE_UUID 는
// 표시 이름 해석용 값이라 로그인 사용자 id 와 다르다 — 여기서는 정본을 직접 참조한다
// (메모리 e2e-fixture-whoami-userid-alignment).
// ─────────────────────────────────────────────────────────────────────────────

/** 현재 로그인 사용자(alice)가 쓴 댓글 */
const myComment: CommentResponse = {
  id: CM_ID_1,
  authorId: aliceUser.userId,
  body: '내가 쓴 댓글',
  bodyHtml: '<p>내가 쓴 댓글</p>\n',
  createdAt: '2026-07-27T09:00:00Z',
  updatedAt: '2026-07-27T09:00:00Z',
}

/** 남이 쓴 댓글 (bob) */
const othersComment: CommentResponse = {
  id: CM_ID_2,
  authorId: BOB_UUID,
  body: '남이 쓴 댓글',
  bodyHtml: '<p>남이 쓴 댓글</p>\n',
  createdAt: '2026-07-27T10:00:00Z',
  updatedAt: '2026-07-27T10:00:00Z',
}

/** 댓글 단건 엔드포인트 (수정·삭제) */
const ITEM_URL = `${LIST_URL}/:commentId`

/**
 * 이슈 권한 응답을 고정한다.
 *
 * 훅을 `vi.mock` 하지 않고 실제 `useIssuePermissions` 를 태운 뒤 응답만 바꾼다 —
 * 훅을 통째로 대체하면 "권한 응답 → 버튼 노출" 배선 자체가 검증 대상에서 빠진다.
 *
 * @param softDelete SOFT_DELETE(모더레이터) 보유 여부
 * @returns 권한 요청이 실제로 처리된 횟수를 읽는 함수 (응답 도착 대기용)
 */
function stubIssuePermissions(softDelete: boolean): { servedCount: () => number } {
  let count = 0
  server.use(
    http.get('*/api/v1/users/me/issue-permissions', ({ request }) => {
      count += 1
      const issueKey = new URL(request.url).searchParams.get('issueKey') ?? ISSUE_KEY
      return HttpResponse.json({
        issueKey,
        permissions: { UPDATE: true, SOFT_DELETE: softDelete, TRANSITION: true },
      })
    }),
  )
  return { servedCount: () => count }
}

/** 댓글 목록 응답을 고정한다 */
function stubCommentList(comments: CommentResponse[]): void {
  server.use(http.get(LIST_URL, () => HttpResponse.json({ data: comments })))
}

describe('CommentSection — (d) 수정·삭제 버튼 노출 조건', () => {
  it('자기 댓글에 수정·삭제 버튼이 보인다', async () => {
    stubIssuePermissions(false)
    stubCommentList([myComment])
    renderSection()

    const item = await screen.findByRole('listitem')
    expect(
      within(item).getByRole('button', { name: commentStrings.commentEditButton }),
    ).toBeInTheDocument()
    expect(
      within(item).getByRole('button', { name: commentStrings.commentDeleteButton }),
    ).toBeInTheDocument()
  })

  it('남의 댓글에는 수정·삭제 버튼이 모두 없다 (SOFT_DELETE 미보유)', async () => {
    const permissions = stubIssuePermissions(false)
    stubCommentList([othersComment])
    renderSection()

    const item = await screen.findByRole('listitem')
    // 권한 응답 도착 전(로딩 중)에도 버튼이 없어야 한다 — fail-open 이면 여기서 걸린다
    expect(
      within(item).queryByRole('button', { name: commentStrings.commentEditButton }),
    ).not.toBeInTheDocument()

    // 권한 응답이 도착해 반영된 뒤에도 여전히 없어야 한다
    await waitFor(() => {
      expect(permissions.servedCount()).toBeGreaterThan(0)
    })
    expect(
      within(item).queryByRole('button', { name: commentStrings.commentEditButton }),
    ).not.toBeInTheDocument()
    expect(
      within(item).queryByRole('button', { name: commentStrings.commentDeleteButton }),
    ).not.toBeInTheDocument()
  })

  it('SOFT_DELETE 보유자에게는 남의 댓글에 삭제 버튼만 보인다 (수정 버튼 없음)', async () => {
    stubIssuePermissions(true)
    stubCommentList([othersComment])
    renderSection()

    const item = await screen.findByRole('listitem')
    // ★이 화면의 핵심 판별자 — 삭제 권한이 수정 어포던스까지 열면 백엔드는 403 을 주는데
    //   UI 는 버튼을 보여주는 거짓 어포던스가 된다. 사용자가 누르고 거부당한다.
    //   백엔드 술어가 다르다. 수정 = 작성자 뿐 / 삭제 = 작성자 OR SOFT_DELETE 보유자.
    expect(
      await within(item).findByRole('button', { name: commentStrings.commentDeleteButton }),
    ).toBeInTheDocument()
    expect(
      within(item).queryByRole('button', { name: commentStrings.commentEditButton }),
    ).not.toBeInTheDocument()
  })
})

describe('CommentSection — (e) 인라인 편집', () => {
  it('인라인 편집 — 취소하면 원문이 복원된다', async () => {
    const user = userEvent.setup({ delay: null })
    const patchSpy = vi.fn()
    stubIssuePermissions(false)
    stubCommentList([myComment])
    server.use(
      http.patch(ITEM_URL, () => {
        patchSpy()
        return HttpResponse.json({ data: myComment })
      }),
    )
    renderSection()

    const item = await screen.findByRole('listitem')
    await user.click(within(item).getByRole('button', { name: commentStrings.commentEditButton }))

    const textarea = screen.getByLabelText(commentStrings.commentEditBodyLabel)
    // ★편집기에는 **HTML** 이 들어간다(V039). 서버가 준 bodyHtml 을 그대로 seed 하므로
    //   평문이 아니라 마크업이 보이는 것이 정상이다.
    expect(textarea).toHaveValue(myComment.bodyHtml)
    await user.clear(textarea)
    await user.type(textarea, '버릴 초안')
    await user.click(screen.getByRole('button', { name: commentStrings.commentEditCancelButton }))

    // 편집 폼이 닫히고 원문이 그대로 보인다
    await waitFor(() => {
      expect(
        screen.queryByLabelText(commentStrings.commentEditBodyLabel),
      ).not.toBeInTheDocument()
    })
    expect(screen.getByText(myComment.body)).toBeInTheDocument()
    expect(screen.queryByText('버릴 초안')).not.toBeInTheDocument()
    // 취소는 서버를 부르지 않는다
    expect(patchSpy).not.toHaveBeenCalled()

    // 다시 열면 버린 초안이 아니라 원문이 들어 있다
    await user.click(within(item).getByRole('button', { name: commentStrings.commentEditButton }))
    expect(screen.getByLabelText(commentStrings.commentEditBodyLabel)).toHaveValue(
      myComment.bodyHtml,
    )
  })

  it('인라인 편집 — 저장하면 목록에 반영된다', async () => {
    const user = userEvent.setup({ delay: null })
    stubIssuePermissions(false)
    let stored: CommentResponse[] = [myComment]
    server.use(
      http.get(LIST_URL, () => HttpResponse.json({ data: stored })),
      http.patch(ITEM_URL, async ({ request }) => {
        const payload = (await request.json()) as { body?: unknown }
        const body = typeof payload.body === 'string' ? payload.body : ''
        const updated: CommentResponse = {
          ...myComment,
          body,
          bodyHtml: `<p>${body}</p>\n`,
          updatedAt: '2026-07-27T12:00:00Z',
        }
        stored = [updated]
        return HttpResponse.json({ data: updated })
      }),
    )
    renderSection()

    const item = await screen.findByRole('listitem')
    await user.click(within(item).getByRole('button', { name: commentStrings.commentEditButton }))

    const textarea = screen.getByLabelText(commentStrings.commentEditBodyLabel)
    await user.clear(textarea)
    await user.type(textarea, '고쳐 쓴 댓글')
    await user.click(screen.getByRole('button', { name: commentStrings.commentEditSaveButton }))

    // 캐시 무효화 → 재조회 결과가 화면에 반영된다
    await waitFor(() => {
      expect(screen.getByText('고쳐 쓴 댓글')).toBeInTheDocument()
    })
    expect(screen.queryByLabelText(commentStrings.commentEditBodyLabel)).not.toBeInTheDocument()
    expect(screen.queryByText(myComment.body)).not.toBeInTheDocument()
  })
})

describe('CommentSection — (f) 삭제 확인', () => {
  it('삭제는 확인 다이얼로그를 거친다 (취소하면 삭제되지 않는다)', async () => {
    const deleteSpy = vi.fn()
    stubIssuePermissions(false)
    stubCommentList([myComment])
    server.use(
      http.delete(ITEM_URL, () => {
        deleteSpy()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderSection()

    const item = await screen.findByRole('listitem')
    // Radix AlertDialog 는 fireEvent 로 조작한다 (AccountLinkList.test 선례 —
    // 다이얼로그가 열리면 body 의 pointer-events 가 꺼져 userEvent 검사에 걸린다)
    fireEvent.click(within(item).getByRole('button', { name: commentStrings.commentDeleteButton }))

    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText(commentStrings.commentDeleteDialogTitle)).toBeInTheDocument()

    // 취소 — 확인을 거치지 않은 삭제는 절대 일어나지 않는다
    fireEvent.click(
      within(dialog).getByRole('button', { name: commentStrings.commentDeleteDialogCancel }),
    )
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    })
    expect(deleteSpy).not.toHaveBeenCalled()
    expect(screen.getByText(myComment.body)).toBeInTheDocument()

    // 확인하면 그때 삭제된다 — 게이트가 "영원히 안 지운다" 가 아님을 함께 고정한다
    fireEvent.click(within(item).getByRole('button', { name: commentStrings.commentDeleteButton }))
    const reopened = await screen.findByRole('alertdialog')
    fireEvent.click(
      within(reopened).getByRole('button', { name: commentStrings.commentDeleteDialogConfirm }),
    )
    await waitFor(() => {
      expect(deleteSpy).toHaveBeenCalledTimes(1)
    })
  })
})

describe('CommentSection — (g) 수정됨 표시 / bodyHtml 렌더', () => {
  it('updatedAt !== createdAt 이면 "(수정됨)" 이 보인다', async () => {
    stubIssuePermissions(false)
    stubCommentList([{ ...myComment, updatedAt: '2026-07-27T12:00:00Z' }])
    renderSection()

    expect(await screen.findByText(commentStrings.commentEditedBadge)).toBeInTheDocument()
  })

  it('updatedAt === createdAt 이면 "(수정됨)" 이 없다', async () => {
    stubIssuePermissions(false)
    stubCommentList([myComment])
    renderSection()

    await screen.findByRole('listitem')
    expect(screen.queryByText(commentStrings.commentEditedBadge)).not.toBeInTheDocument()
  })

  it('서버가 준 bodyHtml 이 텍스트가 아니라 HTML 로 렌더된다', async () => {
    stubIssuePermissions(false)
    stubCommentList([
      { ...myComment, body: '본문 **강조**', bodyHtml: '<p>본문 <strong>강조</strong></p>\n' },
    ])
    renderSection()

    // ★단언 범위 — "받은 bodyHtml 을 이스케이프하지 않고 DOM 에 넣었는가"(배선)만 본다.
    //   MSW 모크는 마크다운 변환도 정화도 하지 않으므로 "강조가 <strong> 으로 바뀐다" 나
    //   "스크립트가 차단된다" 를 여기서 단언하면 모크를 검증하는 거짓 초록이 된다.
    //   그 두 주장은 백엔드 CommentControllerIntegrationTest 가 증명한다.
    const emphasized = await screen.findByText('강조')
    expect(emphasized.tagName).toBe('STRONG')
    // 원문 body 를 텍스트로 뿌리던 이전 동작이 남아 있으면 여기서 걸린다
    expect(screen.queryByText('본문 **강조**')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) FR-UX-10 F11 — 단축키 `m` 손잡이 (focusRef + aria-keyshortcuts + 포커스 표시)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `focusRef` 를 연결한 채로 섹션을 렌더한다.
 *
 * @param focusRef 작성 폼 textarea 를 받을 ref
 * @param canUpdate 쓰기 권한 여부
 */
function renderSectionWithFocusRef(
  focusRef: RefObject<HTMLDivElement | null>,
  canUpdate = true,
): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  render(
    <CommentSection issueKey={ISSUE_KEY} canUpdate={canUpdate} focusRef={focusRef} />,
    { wrapper },
  )
}

describe('CommentSection — (e) FR-UX-10 F11 단축키 `m` 손잡이', () => {
  it('focusRef 가 댓글 편집기 DOM 노드를 잡는다', async () => {
    // ★WYSIWYG 전환으로 포커스 대상이 textarea 에서 contenteditable 컨테이너가 됐다.
    //   `m` 단축키는 이 ref 로 `.focus()` 를 부른다 — contenteditable 도 포커스를 받는다.
    const focusRef = createRef<HTMLDivElement>()
    renderSectionWithFocusRef(focusRef)

    await screen.findByLabelText(commentStrings.commentBodyLabel)
    // `?.` 가 null 을 삼켜 공허 통과하는 것을 막는다 — 배선이 끊기면 여기서 걸린다.
    expect(focusRef.current).not.toBeNull()
  })

  it('focusRef 가 연결되면 aria-keyshortcuts="m" 을 알린다', async () => {
    const focusRef = createRef<HTMLDivElement>()
    renderSectionWithFocusRef(focusRef)

    // 속성은 편집기를 감싼 래퍼가 갖는다 — contenteditable 자체는 TipTap 이 소유해
    // 임의 속성을 붙이면 에디터 재생성 때 사라진다.
    const editor = await screen.findByLabelText(commentStrings.commentBodyLabel)
    expect(editor.closest('[aria-keyshortcuts]')).toHaveAttribute('aria-keyshortcuts', 'm')
  })

  it('focusRef 가 없으면 aria-keyshortcuts 를 붙이지 않는다', async () => {
    renderSection()

    const editor = await screen.findByLabelText(commentStrings.commentBodyLabel)
    expect(editor.closest('[aria-keyshortcuts]')).toBeNull()
  })

  // ★「작성 입력에 포커스 표시가 있다」(F-1) 단언은 여기서 지웠다.
  //   포커스 링은 이제 `RichTextEditor` 의 컨테이너가 `focus-within:ring-2` 로 소유한다 —
  //   이 파일은 그 컴포넌트를 mock 으로 대체하므로 클래스를 볼 수 없고, 봐도 mock 의 것이라
  //   가짜 그린이 된다. 표시 자체는 브라우저 눈확인과 e2e 의 몫이다.
})

// ─────────────────────────────────────────────────────────────────────────────
// (f) 길이 카운터 · 상한 잠금
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 댓글 상한 배선 판별식.
 *
 * ## ★본문과 재는 문자열이 다르다
 *
 * 이슈 본문은 서버가 **HTML** 을 잰다(`@Size` on `descriptionHtml`). 댓글은 **평문**이다 —
 * `CommentApplicationService.validateBody(body)` 가 raw 텍스트를 받는다. 둘을 섞으면
 * 카운터가 거짓말을 한다. 그래서 이 판별식은 평문 길이로 판정하는지를 잰다.
 *
 * 입력은 `RichTextEditorMock`(textarea 대역)을 통과하므로 `fireEvent.change` 로 긴 값을
 * 한 번에 넣는다 — `user.type` 으로 32,768자를 치면 테스트가 끝나지 않는다.
 */
describe('CommentSection — (f) 길이 카운터·상한 잠금', () => {
  /** 평문 기준 길이를 갖는 HTML 을 만든다. `<p></p>` 를 벗기면 정확히 `length` 자다. */
  function commentHtmlOfPlainLength(length: number): string {
    return `<p>${'가'.repeat(length)}</p>`
  }

  it('평범한 길이에서는 카운터를 띄우지 않는다', async () => {
    stubIssuePermissions(false)
    stubCommentList([])
    renderSection()

    const box = await screen.findByLabelText(commentStrings.commentBodyLabel)
    fireEvent.change(box, { target: { value: '<p>짧은 댓글</p>' } })

    expect(screen.queryByTestId('comment-add-length-counter')).not.toBeInTheDocument()
  })

  it('상한을 넘으면 카운터가 나타나고 등록 버튼이 잠긴다', async () => {
    stubIssuePermissions(false)
    stubCommentList([])
    renderSection()

    const box = await screen.findByLabelText(commentStrings.commentBodyLabel)
    fireEvent.change(box, {
      target: { value: commentHtmlOfPlainLength(COMMENT_BODY_MAX_LENGTH + 1) },
    })

    expect(screen.getByTestId('comment-add-length-counter')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: commentStrings.commentAddButton })).toBeDisabled()
    })
  })

  it('상한 이하이면 등록 버튼이 열려 있다 — 위 단언의 비-공허 짝', async () => {
    stubIssuePermissions(false)
    stubCommentList([])
    renderSection()

    const box = await screen.findByLabelText(commentStrings.commentBodyLabel)
    fireEvent.change(box, {
      target: { value: commentHtmlOfPlainLength(COMMENT_BODY_MAX_LENGTH) },
    })

    // 임계는 넘어 카운터가 보이고, 상한은 안 넘어 등록은 열려 있다.
    expect(screen.getByTestId('comment-add-length-counter')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: commentStrings.commentAddButton })).toBeEnabled()
    })
  })

  it('평문이 아니라 HTML 길이로 재면 안 된다 — 태그만으로 상한을 넘기지 못한다', async () => {
    stubIssuePermissions(false)
    stubCommentList([])
    renderSection()

    // 평문은 10자인데 마크업이 잔뜩 붙어 HTML 은 상한을 넘는 입력.
    const noisyHtml = `<p>${'<strong>가</strong>'.repeat(10)}</p>${'<!-- x -->'.repeat(4000)}`
    expect(noisyHtml.length).toBeGreaterThan(COMMENT_BODY_MAX_LENGTH)

    const box = await screen.findByLabelText(commentStrings.commentBodyLabel)
    fireEvent.change(box, { target: { value: noisyHtml } })

    // 서버가 재는 것은 평문이므로 잠기면 안 된다.
    expect(screen.queryByTestId('comment-add-length-counter')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: commentStrings.commentAddButton })).toBeEnabled()
    })
  })

  it('수정 폼에서도 상한을 넘으면 저장이 잠긴다', async () => {
    const user = userEvent.setup({ delay: null })
    stubIssuePermissions(false)
    stubCommentList([myComment])
    renderSection()

    const item = await screen.findByRole('listitem')
    await user.click(within(item).getByRole('button', { name: commentStrings.commentEditButton }))

    const box = screen.getByLabelText(commentStrings.commentEditBodyLabel)
    fireEvent.change(box, {
      target: { value: commentHtmlOfPlainLength(COMMENT_BODY_MAX_LENGTH + 1) },
    })

    expect(screen.getByTestId('comment-edit-length-counter')).toBeInTheDocument()
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: commentStrings.commentEditSaveButton }),
      ).toBeDisabled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (g) 댓글 딥링크 — 인박스 알림이 지목한 댓글
// ─────────────────────────────────────────────────────────────────────────────

describe('CommentSection — (g) 댓글 딥링크', () => {
  it('focusCommentId 가 지목한 댓글만 강조 대상이 된다', async () => {
    renderSection(true, CM_ID_2)

    await waitFor(() => {
      expect(screen.getByTestId(`comment-row-${CM_ID_2}`)).toHaveAttribute(
        'data-focus-target',
        'true',
      )
    })
    // 나머지 행까지 강조되면 「어느 댓글 때문에 왔는지」가 다시 사라진다.
    expect(screen.getByTestId(`comment-row-${CM_ID_1}`)).not.toHaveAttribute('data-focus-target')
  })

  it('focusCommentId 가 없으면 어떤 행도 강조하지 않는다', async () => {
    renderSection()

    await waitFor(() => {
      expect(screen.getByTestId(`comment-row-${CM_ID_1}`)).toBeInTheDocument()
    })
    expect(screen.getByTestId(`comment-row-${CM_ID_1}`)).not.toHaveAttribute('data-focus-target')
    expect(screen.getByTestId(`comment-row-${CM_ID_2}`)).not.toHaveAttribute('data-focus-target')
  })

  it('이미 삭제된 댓글을 가리켜도 목록은 평소대로 렌더된다', async () => {
    // 알림은 남았는데 댓글이 지워진 경우 — 딥링크가 빗나갈 뿐 화면이 깨져선 안 된다.
    renderSection(true, GHOST_UUID)

    await waitFor(() => {
      expect(screen.getByText('먼저 쓴 댓글')).toBeInTheDocument()
    })
    expect(screen.getByTestId(`comment-row-${CM_ID_1}`)).not.toHaveAttribute('data-focus-target')
    expect(screen.getByTestId(`comment-row-${CM_ID_2}`)).not.toHaveAttribute('data-focus-target')
  })

  it('대상 댓글이 목록에 들어오면 화면 가운데로 스크롤한다', async () => {
    // jsdom 에는 scrollIntoView 가 없다 — 없는 채로 두면 옵셔널 호출이 조용히 통과해
    // 「스크롤한다」를 아무도 검증하지 않는다. 심어 두고 호출을 실제로 본다.
    const scrollIntoView = vi.fn()
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      value: scrollIntoView,
      configurable: true,
      writable: true,
    })

    renderSection(true, CM_ID_2)

    await waitFor(() => {
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'center' })
    })
  })
})
