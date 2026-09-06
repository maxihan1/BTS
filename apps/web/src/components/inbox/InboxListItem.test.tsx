// InboxListItem 컴포넌트 단위 테스트 — 제목/발신자/생성시각/읽음강조/버튼/링크 (FR-UX-03 Task-6)
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { inboxLabels } from '@/i18n/inbox-labels'
import type { InboxItem } from '@/api/inbox'
import { InboxListItem } from './InboxListItem'
import { useIssueDetailModalStore } from '@/components/issue/issueDetailModalStore'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router Link 모킹 — 라우터 컨텍스트 없이 단위 테스트 가능
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    search,
    children,
    className,
    onClick,
  }: {
    to: string
    params?: Record<string, string>
    search?: Record<string, string>
    children: React.ReactNode
    className?: string
    onClick?: (event: React.MouseEvent) => void
  }) => {
    // TanStack Router 동작 모사 — to의 `$key` 토큰을 params 값으로 치환해 실제 href 생성
    let href = to
    if (params !== undefined) {
      for (const [token, value] of Object.entries(params)) {
        href = href.replace(`$${token}`, value)
      }
    }
    // ★search 도 href 에 반영한다. 반영하지 않으면 「새 탭으로도 딥링크가 간다」는 단언이
    //   모크의 침묵 때문에 영영 통과한다 (메모리 `mock-swallowed-prop-is-invisible-to-unit-tests`).
    const query = new URLSearchParams(search ?? {}).toString()
    return (
      <a href={query === '' ? href : `${href}?${query}`} className={className} onClick={onClick}>
        {children}
      </a>
    )
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 기본 InboxItem (안읽음 + issueKey 있음)
// ─────────────────────────────────────────────────────────────────────────────

/** 유효한 RFC 4122 v4 UUID */
const ITEM_ID = '11111111-1111-4111-8111-111111111111'
const ACTOR_ID = '22222222-2222-4222-8222-222222222222'

/** 기본 픽스처 — 안읽음, 미보관, issueKey 있음 */
const baseItem: InboxItem = {
  id: ITEM_ID,
  eventType: 'ISSUE_MENTIONED',
  issueKey: 'ATLAS-42',
  title: 'ATLAS-42에서 멘션되었습니다',
  body: '이슈 본문 일부입니다.',
  actorUserId: ACTOR_ID,
  commentId: null,
  readAt: null,
  archivedAt: null,
  createdAt: '2026-06-25T10:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface RenderOptions {
  item?: InboxItem
  actorName?: string | null
  onToggleRead?: (id: string, read: boolean) => void
  onToggleArchive?: (id: string, archived: boolean) => void
}

function renderItem({
  item = baseItem,
  actorName = '홍길동',
  onToggleRead = vi.fn(),
  onToggleArchive = vi.fn(),
}: RenderOptions = {}) {
  return render(
    <InboxListItem
      item={item}
      actorName={actorName}
      onToggleRead={onToggleRead}
      onToggleArchive={onToggleArchive}
    />,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 기본 렌더 — 제목, 본문, 발신자, 생성 시각
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxListItem — S1 기본 렌더', () => {
  it('S1a: 항목 제목이 표시된다', () => {
    renderItem()
    expect(screen.getByText('ATLAS-42에서 멘션되었습니다')).toBeInTheDocument()
  })

  it('S1b: 항목 본문이 표시된다', () => {
    renderItem()
    expect(screen.getByText('이슈 본문 일부입니다.')).toBeInTheDocument()
  })

  it('S1c: actorName이 주어지면 해당 이름이 표시된다', () => {
    renderItem({ actorName: '홍길동' })
    expect(screen.getByText('홍길동')).toBeInTheDocument()
  })

  it('S1d: actorName이 null이면 시스템 발신 라벨이 표시된다', () => {
    renderItem({ actorName: null })
    expect(screen.getByText(inboxLabels.sender.system)).toBeInTheDocument()
  })

  it('S1e: 생성 시각이 표시된다', () => {
    renderItem()
    // 생성 시각 텍스트가 DOM에 존재해야 함 (포맷은 formatDate에 위임)
    expect(screen.getByTestId('inbox-item-created-at')).toBeInTheDocument()
  })

  it('S1f: body가 null이면 본문 영역이 렌더되지 않는다', () => {
    renderItem({ item: { ...baseItem, body: null } })
    expect(screen.queryByTestId('inbox-item-body')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 읽음/안읽음 시각 구분
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxListItem — S2 읽음/안읽음 시각 구분', () => {
  it('S2a: readAt이 null이면(안읽음) 강조 마커가 표시된다', () => {
    renderItem({ item: { ...baseItem, readAt: null } })
    expect(screen.getByTestId('inbox-item-unread-marker')).toBeInTheDocument()
  })

  it('S2b: readAt이 있으면(읽음) 강조 마커가 없다', () => {
    renderItem({
      item: { ...baseItem, readAt: '2026-06-25T11:00:00Z' },
    })
    expect(screen.queryByTestId('inbox-item-unread-marker')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 읽음 토글 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxListItem — S3 읽음 토글', () => {
  it('S3a: 안읽음 상태에서 읽음 표시 버튼이 렌더된다', () => {
    renderItem({ item: { ...baseItem, readAt: null } })
    // aria-label로 컨테이너를 한정해 중복 버튼 구분 (E2E strict mode 대비)
    const article = screen.getByRole('article')
    expect(
      within(article).getByRole('button', { name: inboxLabels.item.markRead }),
    ).toBeInTheDocument()
  })

  it('S3b: 읽음 상태에서 안읽음 표시 버튼이 렌더된다', () => {
    renderItem({ item: { ...baseItem, readAt: '2026-06-25T11:00:00Z' } })
    const article = screen.getByRole('article')
    expect(
      within(article).getByRole('button', { name: inboxLabels.item.markUnread }),
    ).toBeInTheDocument()
  })

  it('S3c: 읽음 표시 버튼 클릭 시 onToggleRead(id, true)가 호출된다', async () => {
    const onToggleRead = vi.fn()
    renderItem({ item: { ...baseItem, readAt: null }, onToggleRead })

    const article = screen.getByRole('article')
    await userEvent.click(
      within(article).getByRole('button', { name: inboxLabels.item.markRead }),
    )

    expect(onToggleRead).toHaveBeenCalledOnce()
    expect(onToggleRead).toHaveBeenCalledWith(ITEM_ID, true)
  })

  it('S3d: 안읽음 표시 버튼 클릭 시 onToggleRead(id, false)가 호출된다', async () => {
    const onToggleRead = vi.fn()
    renderItem({
      item: { ...baseItem, readAt: '2026-06-25T11:00:00Z' },
      onToggleRead,
    })

    const article = screen.getByRole('article')
    await userEvent.click(
      within(article).getByRole('button', { name: inboxLabels.item.markUnread }),
    )

    expect(onToggleRead).toHaveBeenCalledOnce()
    expect(onToggleRead).toHaveBeenCalledWith(ITEM_ID, false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 보관 토글 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxListItem — S4 보관 토글', () => {
  it('S4a: 미보관 상태에서 보관 버튼이 렌더된다', () => {
    renderItem({ item: { ...baseItem, archivedAt: null } })
    const article = screen.getByRole('article')
    expect(
      within(article).getByRole('button', { name: inboxLabels.item.archive }),
    ).toBeInTheDocument()
  })

  it('S4b: 보관 상태에서 보관 해제 버튼이 렌더된다', () => {
    renderItem({ item: { ...baseItem, archivedAt: '2026-06-25T12:00:00Z' } })
    const article = screen.getByRole('article')
    expect(
      within(article).getByRole('button', { name: inboxLabels.item.unarchive }),
    ).toBeInTheDocument()
  })

  it('S4c: 보관 버튼 클릭 시 onToggleArchive(id, true)가 호출된다', async () => {
    const onToggleArchive = vi.fn()
    renderItem({ item: { ...baseItem, archivedAt: null }, onToggleArchive })

    const article = screen.getByRole('article')
    await userEvent.click(
      within(article).getByRole('button', { name: inboxLabels.item.archive }),
    )

    expect(onToggleArchive).toHaveBeenCalledOnce()
    expect(onToggleArchive).toHaveBeenCalledWith(ITEM_ID, true)
  })

  it('S4d: 보관 해제 버튼 클릭 시 onToggleArchive(id, false)가 호출된다', async () => {
    const onToggleArchive = vi.fn()
    renderItem({
      item: { ...baseItem, archivedAt: '2026-06-25T12:00:00Z' },
      onToggleArchive,
    })

    const article = screen.getByRole('article')
    await userEvent.click(
      within(article).getByRole('button', { name: inboxLabels.item.unarchive }),
    )

    expect(onToggleArchive).toHaveBeenCalledOnce()
    expect(onToggleArchive).toHaveBeenCalledWith(ITEM_ID, false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. issueKey SPA Link
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxListItem — S5 issueKey 링크', () => {
  it('S5a: issueKey가 있으면 /issues/{key} 링크가 렌더된다', () => {
    renderItem({ item: { ...baseItem, issueKey: 'ATLAS-42' } })
    const link = screen.getByRole('link', { name: 'ATLAS-42' })
    expect(link).toHaveAttribute('href', '/issues/ATLAS-42')
  })

  it('S5b: issueKey가 null이면 이슈 링크가 없다', () => {
    renderItem({ item: { ...baseItem, issueKey: null } })
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 댓글 딥링크 — 알림을 누르면 그 댓글까지 간다
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxListItem — S6 댓글 딥링크', () => {
  const COMMENT_ID = '33333333-3333-4333-8333-333333333333'

  beforeEach(() => {
    useIssueDetailModalStore.setState({ openKey: null, openCommentId: null })
  })

  it('S6a: commentId 가 있으면 href 에 ?comment= 가 실린다 (새 탭·링크 복사 경로)', () => {
    renderItem({ item: { ...baseItem, commentId: COMMENT_ID } })

    expect(screen.getByRole('link', { name: 'ATLAS-42' })).toHaveAttribute(
      'href',
      `/issues/ATLAS-42?comment=${COMMENT_ID}`,
    )
  })

  it('S6b: commentId 가 null 이면 쿼리를 붙이지 않는다', () => {
    renderItem({ item: { ...baseItem, commentId: null } })

    expect(screen.getByRole('link', { name: 'ATLAS-42' })).toHaveAttribute(
      'href',
      '/issues/ATLAS-42',
    )
  })

  it('S6c: 좌클릭은 모달로 열되 댓글까지 함께 넘긴다', async () => {
    renderItem({ item: { ...baseItem, commentId: COMMENT_ID } })

    await userEvent.click(screen.getByRole('link', { name: 'ATLAS-42' }))

    expect(useIssueDetailModalStore.getState().openKey).toBe('ATLAS-42')
    expect(useIssueDetailModalStore.getState().openCommentId).toBe(COMMENT_ID)
  })

  it('S6d: 댓글 없는 알림을 뒤이어 열면 앞선 딥링크가 따라오지 않는다', async () => {
    const { unmount } = renderItem({ item: { ...baseItem, commentId: COMMENT_ID } })
    await userEvent.click(screen.getByRole('link', { name: 'ATLAS-42' }))
    unmount()

    renderItem({ item: { ...baseItem, issueKey: 'ATLAS-99', commentId: null } })
    await userEvent.click(screen.getByRole('link', { name: 'ATLAS-99' }))

    // 이전 값이 남으면 엉뚱한 이슈의 댓글로 스크롤하려 든다.
    expect(useIssueDetailModalStore.getState().openKey).toBe('ATLAS-99')
    expect(useIssueDetailModalStore.getState().openCommentId).toBeNull()
  })
})
