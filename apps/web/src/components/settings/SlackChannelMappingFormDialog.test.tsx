// SlackChannelMappingFormDialog 컴포넌트 테스트 — 생성/수정·이벤트 0개 가드·409 두 종류·400·key 재마운트 검증 (FR-SL-06 D6 Task 4)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { JSX, ReactNode } from 'react'
import { createChannelMapping, updateChannelMapping } from '@/api/slack'
import type { ChannelMapping } from '@/api/slack'
import { ApiError } from '@/api/client'
import { SlackChannelMappingFormDialog } from './SlackChannelMappingFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 모듈 mock — MSW 비의존(W2 병렬 안전성, SlackUserConnectionCard.test.tsx 선례 동형)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/slack', () => ({
  createChannelMapping: vi.fn(),
  updateChannelMapping: vi.fn(),
}))

const mockCreate = vi.mocked(createChannelMapping)
const mockUpdate = vi.mocked(updateChannelMapping)

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 (SlackUserConnectionCard.test.tsx 선례) */
function createWrapper(): { Wrapper: (props: { readonly children: ReactNode }) => JSX.Element; queryClient: QueryClient } {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { readonly children: ReactNode }): JSX.Element {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
  return { Wrapper, queryClient }
}

const PROJECT_KEY = 'ATLAS'

const EDIT_MAPPING: ChannelMapping = {
  id: 'a1000000-0000-4000-8000-000000000001',
  projectKey: PROJECT_KEY,
  channelId: 'C0123456789',
  channelName: '#general',
  eventTypes: ['issue.created', 'issue.assigned'],
  createdAt: '2026-07-10T00:00:00Z',
  updatedAt: '2026-07-10T00:00:00Z',
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 신규 생성 — 폼 필드·헬퍼 텍스트·이벤트 0개 가드
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingFormDialog — 신규 생성', () => {
  it('채널 ID 입력이 비어있고 C1 헬퍼 텍스트가 표시된다', () => {
    const { Wrapper } = createWrapper()
    render(
      <SlackChannelMappingFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} editingMapping={null} />,
      { wrapper: Wrapper },
    )

    expect(screen.getByLabelText('채널 ID')).toHaveValue('')
    expect(
      screen.getByText('Slack 채널 세부정보에서 채널 ID(C…)를 복사해 붙여넣으세요'),
    ).toBeInTheDocument()
  })

  it('이벤트를 하나도 선택하지 않으면 저장 버튼이 비활성화된다', () => {
    const { Wrapper } = createWrapper()
    render(
      <SlackChannelMappingFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} editingMapping={null} />,
      { wrapper: Wrapper },
    )

    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled()
  })

  it('이벤트를 1개 이상 선택하면 저장 버튼이 활성화된다', async () => {
    const user = userEvent.setup()
    const { Wrapper } = createWrapper()
    render(
      <SlackChannelMappingFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} editingMapping={null} />,
      { wrapper: Wrapper },
    )

    await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))

    expect(screen.getByRole('button', { name: '저장' })).toBeEnabled()
  })

  it('채널 ID + 이벤트 선택 후 제출하면 createChannelMapping 호출 + 성공 시 닫힘·목록 invalidate', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    const { Wrapper, queryClient } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    mockCreate.mockResolvedValue(EDIT_MAPPING)

    render(
      <SlackChannelMappingFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        editingMapping={null}
      />,
      { wrapper: Wrapper },
    )

    await user.type(screen.getByLabelText('채널 ID'), 'C0123456789')
    await user.type(screen.getByLabelText('채널 표시명 (선택)'), '#general')
    await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockCreate).toHaveBeenCalledWith({
        projectKey: PROJECT_KEY,
        channelId: 'C0123456789',
        channelName: '#general',
        eventTypes: ['issue.created'],
      })
    })
    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['slack-channel-mappings', PROJECT_KEY] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 수정 — 프리필·부분 필드 전송
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingFormDialog — 수정', () => {
  it('editingMapping 값으로 채널 ID·채널명·이벤트를 프리필한다', () => {
    const { Wrapper } = createWrapper()
    render(
      <SlackChannelMappingFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingMapping={EDIT_MAPPING}
      />,
      { wrapper: Wrapper },
    )

    expect(screen.getByLabelText('채널 ID')).toHaveValue('C0123456789')
    expect(screen.getByLabelText('채널 표시명 (선택)')).toHaveValue('#general')
    expect(screen.getByRole('checkbox', { name: '이슈 생성' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '담당자 지정' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '멘션' })).not.toBeChecked()
  })

  it('채널 ID를 수정 후 제출하면 updateChannelMapping(id, ...)이 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    const { Wrapper } = createWrapper()
    mockUpdate.mockResolvedValue({ ...EDIT_MAPPING, channelId: 'C9999999999' })

    render(
      <SlackChannelMappingFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        editingMapping={EDIT_MAPPING}
      />,
      { wrapper: Wrapper },
    )

    await user.clear(screen.getByLabelText('채널 ID'))
    await user.type(screen.getByLabelText('채널 ID'), 'C9999999999')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockUpdate).toHaveBeenCalledWith(EDIT_MAPPING.id, {
        channelId: 'C9999999999',
        channelName: '#general',
        eventTypes: ['issue.created', 'issue.assigned'],
      })
    })
    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  it('editingMapping이 다른 매핑으로 바뀌면 이전 입력이 잔존하지 않는다 (key 재마운트)', () => {
    const { Wrapper } = createWrapper()
    const OTHER_MAPPING: ChannelMapping = {
      ...EDIT_MAPPING,
      id: 'a1000000-0000-4000-8000-000000000002',
      channelId: 'C2222222222',
      channelName: '#random',
      eventTypes: ['sprint.started'],
    }

    const { rerender } = render(
      <SlackChannelMappingFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingMapping={EDIT_MAPPING}
      />,
      { wrapper: Wrapper },
    )
    expect(screen.getByLabelText('채널 ID')).toHaveValue('C0123456789')

    rerender(
      <SlackChannelMappingFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingMapping={OTHER_MAPPING}
      />,
    )

    expect(screen.getByLabelText('채널 ID')).toHaveValue('C2222222222')
    expect(screen.getByLabelText('채널 표시명 (선택)')).toHaveValue('#random')
    expect(screen.getByRole('checkbox', { name: '이슈 생성' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: '스프린트 시작' })).toBeChecked()
  })

  it('이벤트를 모두 해제하면 저장 버튼이 비활성화되고, 강제 제출해도 저장이 호출되지 않는다', async () => {
    const user = userEvent.setup()
    const { Wrapper } = createWrapper()
    render(
      <SlackChannelMappingFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingMapping={EDIT_MAPPING}
      />,
      { wrapper: Wrapper },
    )

    await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))
    await user.click(screen.getByRole('checkbox', { name: '담당자 지정' }))
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled()

    fireEvent.submit(screen.getByTestId('slack-channel-mapping-form'))

    await waitFor(() => {
      expect(screen.getByText('이벤트 유형을 1개 이상 선택해주세요.')).toBeInTheDocument()
    })
    expect(mockUpdate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 에러 처리 — 409 두 종류·400·그 외
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingFormDialog — 에러 처리', () => {
  async function submitCreate(onOpenChange: () => void, wrapper: (props: { readonly children: ReactNode }) => JSX.Element): Promise<void> {
    const user = userEvent.setup()
    render(
      <SlackChannelMappingFormDialog projectKey={PROJECT_KEY} open onOpenChange={onOpenChange} editingMapping={null} />,
      { wrapper },
    )
    await user.type(screen.getByLabelText('채널 ID'), 'C0123456789')
    await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))
    await user.click(screen.getByRole('button', { name: '저장' }))
  }

  it('409 SLACK_CHANNEL_MAPPING_CONFLICT → "이미 동일한 채널 매핑이 존재합니다." 폼 에러를 표시하고 닫히지 않는다', async () => {
    const onOpenChange = vi.fn()
    const { Wrapper } = createWrapper()
    mockCreate.mockRejectedValue(
      new ApiError(409, { code: 'SLACK_CHANNEL_MAPPING_CONFLICT', message: '이미 동일한 채널 매핑이 존재합니다.' }),
    )

    await submitCreate(onOpenChange, Wrapper)

    await waitFor(() => {
      expect(screen.getByText('이미 동일한 채널 매핑이 존재합니다.')).toBeInTheDocument()
    })
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('409 WORKSPACE_NOT_INSTALLED → "Slack 워크스페이스가 먼저 연결돼야 합니다." 안내를 표시한다', async () => {
    const onOpenChange = vi.fn()
    const { Wrapper } = createWrapper()
    mockCreate.mockRejectedValue(
      new ApiError(409, { code: 'WORKSPACE_NOT_INSTALLED', message: 'Slack 워크스페이스가 설치되어 있지 않습니다.' }),
    )

    await submitCreate(onOpenChange, Wrapper)

    await waitFor(() => {
      expect(screen.getByText('Slack 워크스페이스가 먼저 연결돼야 합니다.')).toBeInTheDocument()
    })
  })

  it('400 → 응답 message를 그대로 표시한다', async () => {
    const onOpenChange = vi.fn()
    const { Wrapper } = createWrapper()
    mockCreate.mockRejectedValue(
      new ApiError(400, { code: 'SLACK_CHANNEL_MAPPING_INVALID', message: '이벤트 유형 값이 올바르지 않습니다.' }),
    )

    await submitCreate(onOpenChange, Wrapper)

    await waitFor(() => {
      expect(screen.getByText('이벤트 유형 값이 올바르지 않습니다.')).toBeInTheDocument()
    })
  })

  it('400 응답에 message가 없으면 "입력을 확인해주세요." 폴백을 표시한다', async () => {
    const onOpenChange = vi.fn()
    const { Wrapper } = createWrapper()
    mockCreate.mockRejectedValue(new ApiError(400, {}))

    await submitCreate(onOpenChange, Wrapper)

    await waitFor(() => {
      expect(screen.getByText('입력을 확인해주세요.')).toBeInTheDocument()
    })
  })

  it('그 외 에러(500 등) → 일반 저장 실패 메시지를 표시한다', async () => {
    const onOpenChange = vi.fn()
    const { Wrapper } = createWrapper()
    mockCreate.mockRejectedValue(new ApiError(500, { code: 'SLACK_CHANNEL_MAPPING_INTERNAL_ERROR' }))

    await submitCreate(onOpenChange, Wrapper)

    await waitFor(() => {
      expect(screen.getByText('저장에 실패했습니다. 다시 시도해주세요.')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 취소
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackChannelMappingFormDialog — 취소', () => {
  it('취소 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    const { Wrapper } = createWrapper()

    render(
      <SlackChannelMappingFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        editingMapping={null}
      />,
      { wrapper: Wrapper },
    )

    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
