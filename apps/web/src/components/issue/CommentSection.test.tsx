// CommentSection 컴포넌트 단위 테스트 — FR-CO-01 T6 TDD RED
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
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
import type { CommentResponse } from '@/api/comments'

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

function renderSection(canUpdate = true): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  render(<CommentSection issueKey={ISSUE_KEY} canUpdate={canUpdate} />, { wrapper })
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
