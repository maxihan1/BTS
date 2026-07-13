// SlackChannelMappingList 단위 테스트 — 로딩/에러(404)/빈/정상 렌더 + 삭제 확인→invalidate (FR-SL-06 D6 Task 3)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SlackChannelMappingList } from './SlackChannelMappingList'
import { listChannelMappings, deleteChannelMapping } from '@/api/slack'
import type { ChannelMapping } from '@/api/slack'
import { ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 모듈 mock — MSW 비의존(Task 6과 병렬 진행 중, W2 격리)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/slack', () => ({
  listChannelMappings: vi.fn(),
  deleteChannelMapping: vi.fn(),
}))

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

const MAPPING_WITH_NAME: ChannelMapping = {
  id: '11111111-1111-4111-8111-111111111111',
  projectKey: PROJECT_KEY,
  channelId: 'C0123456',
  channelName: 'general',
  eventTypes: ['issue.created', 'issue.commented'],
  createdAt: '2026-07-10T00:00:00Z',
  updatedAt: '2026-07-10T00:00:00Z',
}

const MAPPING_WITHOUT_NAME: ChannelMapping = {
  id: '22222222-2222-4222-8222-222222222222',
  projectKey: PROJECT_KEY,
  channelId: 'C0999999',
  channelName: null,
  eventTypes: ['sprint.started'],
  createdAt: '2026-07-11T00:00:00Z',
  updatedAt: '2026-07-11T00:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider로 감싸 렌더한다. invalidate 검증을 위해 queryClient도 반환. */
function renderList(onAdd = vi.fn(), onEdit = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <SlackChannelMappingList projectKey={PROJECT_KEY} onAdd={onAdd} onEdit={onEdit} />
    </QueryClientProvider>,
  )
  return { ...utils, queryClient }
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 로딩
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingList — 로딩', () => {
  it('로딩 중에는 상태 표시가 렌더된다', () => {
    vi.mocked(listChannelMappings).mockReturnValue(new Promise(() => {}))
    renderList()
    expect(screen.getByRole('status')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 에러 — 404는 권한없음/미존재 안내, 그 외는 일반 오류
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingList — 에러', () => {
  it('404 응답 시 "권한이 없거나 찾을 수 없습니다" 안내를 표시한다', async () => {
    vi.mocked(listChannelMappings).mockRejectedValue(new ApiError(404, { errorCode: 'SLACK_CHANNEL_MAPPING_NOT_FOUND' }))

    renderList()

    await waitFor(() => {
      expect(screen.getByText('권한이 없거나 찾을 수 없습니다')).toBeInTheDocument()
    })
  })

  it('404 이외 응답 시 일반 오류 메시지를 표시한다', async () => {
    vi.mocked(listChannelMappings).mockRejectedValue(new ApiError(500, { errorCode: 'UNKNOWN' }))

    renderList()

    await waitFor(() => {
      expect(screen.getByText('채널 매핑을 불러오지 못했습니다.')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingList — 빈 상태', () => {
  it('빈 목록이면 빈 상태 안내를 표시하고, "채널 추가" 클릭 시 onAdd가 호출된다', async () => {
    vi.mocked(listChannelMappings).mockResolvedValue([])
    const onAdd = vi.fn()
    const user = userEvent.setup()

    renderList(onAdd)

    await waitFor(() => {
      expect(screen.getByText('아직 등록된 채널 매핑이 없습니다.')).toBeInTheDocument()
    })

    await user.click(screen.getByTestId('slack-channel-mapping-add-button'))
    expect(onAdd).toHaveBeenCalledTimes(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 정상 렌더 — 채널명/ID + 이벤트 라벨
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingList — 정상 렌더', () => {
  it('채널명이 있으면 채널명 + 채널 ID를 함께 표시하고, 이벤트 라벨을 카탈로그로 변환해 표시한다', async () => {
    vi.mocked(listChannelMappings).mockResolvedValue([MAPPING_WITH_NAME])

    renderList()

    await waitFor(() => {
      expect(screen.getByText('general')).toBeInTheDocument()
    })

    const row = screen.getByText('general').closest('li')
    if (row === null) throw new Error('행 요소를 찾지 못함')

    expect(within(row).getByText(/C0123456/)).toBeInTheDocument()
    expect(within(row).getByText('이슈 생성')).toBeInTheDocument()
    expect(within(row).getByText('댓글 작성')).toBeInTheDocument()
  })

  it('채널명이 없으면 채널 ID를 대표 표시로 사용한다', async () => {
    vi.mocked(listChannelMappings).mockResolvedValue([MAPPING_WITHOUT_NAME])

    renderList()

    await waitFor(() => {
      expect(screen.getAllByText('C0999999').length).toBeGreaterThan(0)
    })
    expect(screen.getByText('스프린트 시작')).toBeInTheDocument()
  })

  it('카탈로그에 없는 미지 이벤트 값은 wireValue 그대로 표시한다', async () => {
    vi.mocked(listChannelMappings).mockResolvedValue([
      { ...MAPPING_WITH_NAME, eventTypes: ['unknown.future.event'] },
    ])

    renderList()

    await waitFor(() => {
      expect(screen.getByText('unknown.future.event')).toBeInTheDocument()
    })
  })

  it('행 "수정" 클릭 시 onEdit(mapping)이 호출된다', async () => {
    vi.mocked(listChannelMappings).mockResolvedValue([MAPPING_WITH_NAME])
    const onEdit = vi.fn()
    const user = userEvent.setup()

    renderList(vi.fn(), onEdit)

    await waitFor(() => screen.getByText('general'))
    await user.click(screen.getByTestId(`slack-channel-mapping-edit-${MAPPING_WITH_NAME.id}`))

    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onEdit).toHaveBeenCalledWith(expect.objectContaining({ id: MAPPING_WITH_NAME.id }))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 삭제 — 확인 모달 → 확인 시 mutation 호출 → invalidate(정확한 queryKey)
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingList — 삭제', () => {
  it('삭제 확인 흐름 — 삭제 → 확인 모달 → 확인 클릭 시 deleteChannelMapping 호출 + 목록 invalidate', async () => {
    vi.mocked(listChannelMappings).mockResolvedValue([MAPPING_WITH_NAME])
    vi.mocked(deleteChannelMapping).mockResolvedValue(undefined)
    const user = userEvent.setup()

    const { queryClient } = renderList()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await waitFor(() => screen.getByText('general'))
    await user.click(screen.getByTestId(`slack-channel-mapping-delete-${MAPPING_WITH_NAME.id}`))

    const confirmButton = await screen.findByTestId(`slack-channel-mapping-delete-confirm-${MAPPING_WITH_NAME.id}`)
    await user.click(confirmButton)

    await waitFor(() => {
      expect(deleteChannelMapping).toHaveBeenCalledWith(MAPPING_WITH_NAME.id)
    })
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['slack-channel-mappings', PROJECT_KEY] })
    })
  })

  it('삭제 취소 — 확인 모달에서 취소를 누르면 deleteChannelMapping이 호출되지 않는다', async () => {
    vi.mocked(listChannelMappings).mockResolvedValue([MAPPING_WITH_NAME])
    const user = userEvent.setup()

    renderList()

    await waitFor(() => screen.getByText('general'))
    await user.click(screen.getByTestId(`slack-channel-mapping-delete-${MAPPING_WITH_NAME.id}`))

    const cancelButton = await screen.findByTestId('slack-channel-mapping-delete-cancel')
    await user.click(cancelButton)

    await waitFor(() => {
      expect(screen.queryByTestId('slack-channel-mapping-delete-cancel')).not.toBeInTheDocument()
    })
    expect(deleteChannelMapping).not.toHaveBeenCalled()
    expect(screen.getByText('general')).toBeInTheDocument()
  })
})
