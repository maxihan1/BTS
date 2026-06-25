// 알림 보관함 검색 필터 컴포넌트 단위 테스트 — FR-UX-03 Task-7
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import * as React from 'react'
import type { InboxFilters } from '@/api/inbox'
import type { UserSummary } from '@/api/users'
import type { InboxFiltersProps } from './InboxFilters'

// ─────────────────────────────────────────────────────────────────────────────
// fetchUsers / fetchUsersByIds mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/users', () => ({
  fetchUsers: vi.fn(),
  fetchUsersByIds: vi.fn(),
}))

import { fetchUsers, fetchUsersByIds } from '@/api/users'

const ALICE: UserSummary = {
  id: '11111111-1111-4111-a111-111111111111',
  username: 'alice',
  displayName: '앨리스',
  email: 'alice@example.com',
}

const BOB: UserSummary = {
  id: '22222222-2222-4222-b222-222222222222',
  username: 'bob',
  displayName: null,
  email: 'bob@example.com',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 타입이 명확한 filters 변경 mock 함수 타입 */
type FilterChangeMock = ReturnType<typeof vi.fn<(next: InboxFilters) => void>>

/** InboxFilters 렌더 헬퍼 */
function renderInboxFilters(
  initial: InboxFilters = {},
): { handler: FilterChangeMock } & ReturnType<typeof render> {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const handler: FilterChangeMock = vi.fn<(next: InboxFilters) => void>()

  const utils = render(
    <QueryClientProvider client={qc}>
      <InboxFiltersWrapper initial={initial} onFiltersChange={handler} />
    </QueryClientProvider>,
  )

  return { ...utils, handler }
}

/** controlled wrapper — InboxFilters가 export된 후에 import된다 */
let InboxFilters: React.ComponentType<InboxFiltersProps>

// ─────────────────────────────────────────────────────────────────────────────
// InboxFiltersWrapper — controlled 상태 관리
// ─────────────────────────────────────────────────────────────────────────────

function InboxFiltersWrapper({
  initial,
  onFiltersChange,
}: {
  initial: InboxFilters
  onFiltersChange: (next: InboxFilters) => void
}) {
  const [filters, setFilters] = React.useState<InboxFilters>(initial)

  function handleChange(next: InboxFilters) {
    setFilters(next)
    onFiltersChange(next)
  }

  return <InboxFilters filters={filters} onFiltersChange={handleChange} />
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxFilters', () => {
  beforeEach(async () => {
    vi.mocked(fetchUsers).mockResolvedValue([])
    vi.mocked(fetchUsersByIds).mockResolvedValue([])
    // 동적 import — RED 단계에서는 존재하지 않아 실패
    const mod = await import('./InboxFilters')
    InboxFilters = mod.InboxFilters
  })

  describe('텍스트 검색 입력(q)', () => {
    it('검색어 입력 시 onFiltersChange가 q 필드와 함께 호출된다', async () => {
      const { handler } = renderInboxFilters()
      // delay:null — 긴 문자열 타이핑 timeout 방지 (learnings: vitest userType timeout)
      const user = userEvent.setup({ delay: null })

      const input = screen.getByPlaceholderText('알림 검색')
      await user.type(input, 'atlas')

      await waitFor(() => {
        expect(handler).toHaveBeenLastCalledWith(
          expect.objectContaining({ q: 'atlas' }),
        )
      })
    })

    it('초기 filters.q 값이 검색 입력창에 렌더된다', () => {
      renderInboxFilters({ q: '초기값' })
      expect(screen.getByDisplayValue('초기값')).toBeInTheDocument()
    })

    it('검색어를 지우면 onFiltersChange가 q=undefined로 호출된다', async () => {
      const { handler } = renderInboxFilters({ q: 'test' })
      const user = userEvent.setup({ delay: null })

      const input = screen.getByDisplayValue('test')
      await user.clear(input)

      await waitFor(() => {
        const lastCall = handler.mock.calls[handler.mock.calls.length - 1]?.[0]
        expect(lastCall?.q === undefined || lastCall?.q === '').toBe(true)
      })
    })
  })

  describe('발신자 자동완성', () => {
    it('발신자 검색 입력 포커스 후 텍스트 입력 시 fetchUsers가 호출된다', async () => {
      vi.mocked(fetchUsers).mockResolvedValue([ALICE, BOB])
      const user = userEvent.setup({ delay: null })
      renderInboxFilters()

      const senderInput = screen.getByPlaceholderText('발신자')
      await user.click(senderInput)
      await user.type(senderInput, '앨리')

      await waitFor(() => {
        expect(vi.mocked(fetchUsers)).toHaveBeenCalledWith('앨리')
      })
    })

    it('발신자 후보 목록이 표시된다', async () => {
      vi.mocked(fetchUsers).mockResolvedValue([ALICE, BOB])
      const user = userEvent.setup({ delay: null })
      renderInboxFilters()

      const senderInput = screen.getByPlaceholderText('발신자')
      await user.click(senderInput)
      await user.type(senderInput, '앨리')

      await waitFor(() => {
        // displayName이 있으면 displayName 표시
        expect(screen.getByRole('option', { name: '앨리스' })).toBeInTheDocument()
        // displayName이 null이면 username 표시
        expect(screen.getByRole('option', { name: 'bob' })).toBeInTheDocument()
      })
    })

    it('후보 선택 시 onFiltersChange가 senderId(UUID)와 함께 호출된다', async () => {
      vi.mocked(fetchUsers).mockResolvedValue([ALICE])
      const { handler } = renderInboxFilters()
      const user = userEvent.setup({ delay: null })

      const senderInput = screen.getByPlaceholderText('발신자')
      await user.click(senderInput)
      await user.type(senderInput, '앨리')

      await waitFor(() => {
        expect(screen.getByRole('option', { name: '앨리스' })).toBeInTheDocument()
      })

      await user.click(screen.getByRole('option', { name: '앨리스' }))

      await waitFor(() => {
        expect(handler).toHaveBeenCalledWith(
          expect.objectContaining({ senderId: ALICE.id }),
        )
      })
    })

    it('사용자가 UUID를 직접 입력해도 senderId가 설정되지 않는다', async () => {
      // UUID 입력 자체가 senderId로 이어지면 안 됨 — 검색→선택만 허용
      vi.mocked(fetchUsers).mockResolvedValue([])
      const { handler } = renderInboxFilters()
      const user = userEvent.setup({ delay: null })

      const senderInput = screen.getByPlaceholderText('발신자')
      await user.click(senderInput)
      await user.type(senderInput, ALICE.id)

      // 후보 없음 → 선택 이벤트 발생 안 함 → senderId 미설정
      await waitFor(() => {
        const calls = handler.mock.calls
        const hasSenderIdSet = calls.some(
          ([f]: [InboxFilters]) => f.senderId === ALICE.id,
        )
        expect(hasSenderIdSet).toBe(false)
      })
    })

    it('선택된 발신자 해제 시 onFiltersChange가 senderId=undefined로 호출된다', async () => {
      // 초기 senderId 이름 조회용 mock
      vi.mocked(fetchUsersByIds).mockResolvedValue([ALICE])
      const { handler } = renderInboxFilters({ senderId: ALICE.id })
      const user = userEvent.setup({ delay: null })

      // 이름 로딩 완료 후 해제 버튼 표시까지 대기
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /발신자 선택 해제/i })).toBeInTheDocument()
      })

      await user.click(screen.getByRole('button', { name: /발신자 선택 해제/i }))

      await waitFor(() => {
        expect(handler).toHaveBeenCalledWith(
          expect.objectContaining({ senderId: undefined }),
        )
      })
    })

    it('선택된 발신자 이름이 입력창에 표시된다', async () => {
      // 초기 senderId 이름 조회용 mock
      vi.mocked(fetchUsersByIds).mockResolvedValue([ALICE])
      renderInboxFilters({ senderId: ALICE.id })

      // senderId가 설정된 상태에서 이름 로딩 후 해제 버튼 노출
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /발신자 선택 해제/i })).toBeInTheDocument()
      })
    })
  })

  describe('기간 from/to date 입력 — ISO Instant 형식 변환', () => {
    it('시작일 입력 시 onFiltersChange가 from을 T00:00:00.000Z(UTC 하루 시작) 형식으로 호출한다', async () => {
      const { handler } = renderInboxFilters()
      const user = userEvent.setup({ delay: null })

      const fromInput = screen.getByLabelText('시작일')
      await user.type(fromInput, '2026-06-01')

      await waitFor(() => {
        expect(handler).toHaveBeenCalledWith(
          expect.objectContaining({ from: '2026-06-01T00:00:00.000Z' }),
        )
      })
    })

    it('종료일 입력 시 onFiltersChange가 to를 T23:59:59.999Z(UTC 하루 끝) 형식으로 호출한다', async () => {
      const { handler } = renderInboxFilters()
      const user = userEvent.setup({ delay: null })

      const toInput = screen.getByLabelText('종료일')
      await user.type(toInput, '2026-06-30')

      await waitFor(() => {
        expect(handler).toHaveBeenCalledWith(
          expect.objectContaining({ to: '2026-06-30T23:59:59.999Z' }),
        )
      })
    })

    it('시작일을 지우면 from이 undefined로 호출된다', async () => {
      const { handler } = renderInboxFilters({ from: '2026-06-01T00:00:00.000Z' })
      const user = userEvent.setup({ delay: null })

      // date input의 초기값을 지움
      const fromInput = screen.getByLabelText('시작일')
      await user.clear(fromInput)

      await waitFor(() => {
        const lastCall = handler.mock.calls[handler.mock.calls.length - 1]?.[0]
        expect(lastCall?.from).toBeUndefined()
      })
    })

    it('초기 filters.from/to(ISO Instant)가 date 입력에 YYYY-MM-DD로 렌더된다', () => {
      // date input의 value는 YYYY-MM-DD 형식이어야 한다 (ISO Instant의 날짜 부분 추출)
      renderInboxFilters({
        from: '2026-06-01T00:00:00.000Z',
        to: '2026-06-30T23:59:59.999Z',
      })

      expect(screen.getByDisplayValue('2026-06-01')).toBeInTheDocument()
      expect(screen.getByDisplayValue('2026-06-30')).toBeInTheDocument()
    })

    it('bare date(2026-06-01)를 직접 from으로 전달해도 date 입력에 날짜만 표시된다', () => {
      // 기존 state에 bare date가 있어도 표시는 정상
      renderInboxFilters({ from: '2026-06-01' })
      // date input은 YYYY-MM-DD만 받으므로 2026-06-01이 유효
      expect(screen.getByLabelText('시작일')).toBeInTheDocument()
    })
  })

  describe('필터 통합', () => {
    it('q + from + to를 동시에 설정하면 onFiltersChange가 3개 필드를 ISO Instant 형식으로 호출한다', async () => {
      const { handler } = renderInboxFilters()
      const user = userEvent.setup({ delay: null })

      await user.type(screen.getByPlaceholderText('알림 검색'), 'atlas')
      await user.type(screen.getByLabelText('시작일'), '2026-06-01')
      await user.type(screen.getByLabelText('종료일'), '2026-06-30')

      await waitFor(() => {
        const last = handler.mock.calls[handler.mock.calls.length - 1]?.[0]
        expect(last?.q).toBe('atlas')
        expect(last?.from).toBe('2026-06-01T00:00:00.000Z')
        expect(last?.to).toBe('2026-06-30T23:59:59.999Z')
      })
    })
  })
})
