// CommentSection 통합 테스트 — useUsersByIds 를 mock 하지 않고 MSW 실경로로 작성자 이름 해석을 봉인
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { aliceUser, bobUser, carolUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { commentHandlers, resetCommentStore, seedComments } from '@/mocks/comment-handlers'
import { issuePermissionHandlers } from '@/mocks/issue-permission-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { userListFixture } from '@/mocks/user-fixtures'
import type { CommentResponse } from '@/api/comments'
import { CommentSection } from './CommentSection'

// ─────────────────────────────────────────────────────────────────────────────
// ★ 이 파일이 존재하는 이유 — 형제 `CommentSection.test.tsx` 는 `@/hooks/use-users` 를 통째로
// `vi.mock` 한다. 그래서 「authorId → displayName」 해석이 **테스트가 직접 심은 배열**로 대체되고,
// 실제 `GET /api/v1/users?ids=` 경로는 한 번도 밟히지 않는다.
//
// 그 결과 `auth-fixtures`(whoami·토큰이 쓰는 사용자 id)와 `user-fixtures`(사용자 디렉터리가
// 돌려주는 id)의 **교집합이 공집합**이 된 채로도 전 테스트가 초록이었다. 화면에는 이름 대신
// 원시 UUID 가 나오는데, 그 사실은 브라우저를 눈으로 봐야만 드러났다.
//
// 판별식 — **"토큰이 만든 authorId 를 사용자 디렉터리가 되찾아 이름으로 바꿔줄 수 있는가."**
// mock 을 끼우는 순간 이 질문이 사라지므로, 여기서는 어떤 use-users mock 도 쓰지 않는다.
//
// `src/test/server.ts` 의 기본 핸들러는 refresh 1건뿐이므로, 애플리케이션이 실제로 쓰는
// 프로덕션 mock 핸들러(`userHandlers`·`commentHandlers`·`issuePermissionHandlers`)를 그대로
// 등록한다 — 인라인 가짜 응답을 쓰면 픽스처 정합을 검증한다는 이 파일의 목적이 사라진다.
// ─────────────────────────────────────────────────────────────────────────────

const ISSUE_KEY = 'ATLAS-1'

/** alice 가 쓴 댓글 — authorId 는 auth 정본 id (토큰이 만들어내는 값과 같다) */
const aliceComment: CommentResponse = {
  id: 'c0000000-0000-4000-a000-00000000000a',
  authorId: aliceUser.userId,
  body: '앨리스 댓글',
  bodyHtml: '<p>앨리스 댓글</p>\n',
  createdAt: '2026-07-27T09:00:00Z',
  updatedAt: '2026-07-27T09:00:00Z',
}

/** carol 이 쓴 댓글 — 2026-07-27 에 신설된 세 번째 사용자도 같은 정렬을 지키는지 본다 */
const carolComment: CommentResponse = {
  id: 'c0000000-0000-4000-a000-00000000000c',
  authorId: carolUser.userId,
  body: '캐럴 댓글',
  bodyHtml: '<p>캐럴 댓글</p>\n',
  createdAt: '2026-07-27T10:00:00Z',
  updatedAt: '2026-07-27T10:00:00Z',
}

function renderSection(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  render(<CommentSection issueKey={ISSUE_KEY} canUpdate />, { wrapper })
}

beforeEach(() => {
  resetCommentStore()
  server.use(...userHandlers, ...commentHandlers, ...issuePermissionHandlers)
  useAuthStore.setState({ user: aliceUser, accessToken: mockAccessToken('alice') })
})

afterEach(() => {
  resetCommentStore()
})

describe('CommentSection 통합 — MSW 실경로 작성자 이름 해석', () => {
  /**
   * 비-공허 가드. 아래 렌더 단정들은 "auth 사용자 id 가 사용자 디렉터리에도 있다" 는 전제 위에 선다.
   * 전제가 깨지면(교집합이 다시 비면) 이 테스트가 **먼저** 실패해 원인을 정확히 가리킨다.
   */
  it('auth 픽스처 사용자 id 는 전부 사용자 디렉터리에도 존재한다 (교집합 비지 않음)', () => {
    const directoryIds = new Set(userListFixture.map((u) => u.id))

    expect(directoryIds.has(aliceUser.userId)).toBe(true)
    expect(directoryIds.has(bobUser.userId)).toBe(true)
    expect(directoryIds.has(carolUser.userId)).toBe(true)
  })

  it('작성자 displayName 을 표시한다 — UUID 폴백이 아니다', async () => {
    seedComments(ISSUE_KEY, [aliceComment])
    renderSection()

    await waitFor(() => {
      expect(screen.getByText('김앨리스')).toBeInTheDocument()
    })
    expect(screen.queryByText(aliceUser.userId)).not.toBeInTheDocument()
  })

  it('세 번째 사용자(carol)도 이름으로 표시된다', async () => {
    seedComments(ISSUE_KEY, [carolComment])
    renderSection()

    await waitFor(() => {
      expect(screen.getByText('캐럴')).toBeInTheDocument()
    })
    expect(screen.queryByText(carolUser.userId)).not.toBeInTheDocument()
  })
})
