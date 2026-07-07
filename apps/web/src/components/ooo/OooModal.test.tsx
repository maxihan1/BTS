// OooModal 단위 테스트 — 렌더/기간입력/대리자검색선택/메시지/저장(replace)/해제/검증에러/재열림초기화 (FR-PR-03 Task 7)
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OooModal } from '@/components/ooo/OooModal'
import { oooLabels } from '@/i18n/ooo-labels'
import { toInstant, toLocalInputValue } from '@/lib/ooo-datetime'
import type { OooResponse } from '@/api/schemas'
import type { OooPatchBody } from '@/api/ooo'
import type { UserSummary } from '@/api/users'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const bobUser: UserSummary = {
  id: '3f3e5f2a-3b1b-4f9e-9d1a-1e2f3a4b5c6d',
  username: 'bob',
  displayName: '밥',
  email: 'bob@bts.local',
}
const carolUser: UserSummary = {
  id: '5a6b7c8d-9e0f-4123-8456-789abcdef012',
  username: 'carol',
  displayName: '캐롤',
  email: 'carol@bts.local',
}
const DELEGATE_DIRECTORY: readonly UserSummary[] = [bobUser, carolUser]

const emptyOoo: OooResponse = {
  startsAt: null,
  endsAt: null,
  delegateUserId: null,
  delegateName: null,
  message: null,
  active: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// mutation/query/whoami-refresh 스파이 — new Date() 의존 없이 호출 인자를 검증한다
// (StatusModal.test.tsx 선례와 동일한 패턴).
// ─────────────────────────────────────────────────────────────────────────────

const { updateMutate, clearMutate, refreshWhoamiMock, mutationState, oooQueryState } = vi.hoisted(() => ({
  updateMutate: vi.fn(
    (_body: unknown, opts?: { onSuccess?: () => void; onError?: () => void }) => {
      opts?.onSuccess?.()
    },
  ),
  clearMutate: vi.fn((_arg: unknown, opts?: { onSuccess?: () => void }) => {
    opts?.onSuccess?.()
  }),
  refreshWhoamiMock: vi.fn(() => Promise.resolve()),
  mutationState: { updatePending: false, clearPending: false },
  oooQueryState: { data: undefined as OooResponse | undefined },
}))

vi.mock('@/hooks/use-ooo', () => ({
  useOooQuery: () => ({ data: oooQueryState.data }),
  useUpdateOooMutation: () => ({ mutate: updateMutate, isPending: mutationState.updatePending }),
  useClearOooMutation: () => ({ mutate: clearMutate, isPending: mutationState.clearPending }),
}))

vi.mock('@/api/useProfile', () => ({ refreshWhoami: refreshWhoamiMock }))

// 대리자 검색 — query 길이 매칭 로직은 useUserSearch 자체(호출측 enabled 가드)의 책임이므로 여기선
// 고정 결과만 반환한다. OooModal의 2자 가드는 컴포넌트 자체 렌더 조건(DelegateResultList)이 담당.
vi.mock('@/hooks/use-user-directory', () => ({
  useUserSearch: (query: string) => ({
    data: query.length >= 2 ? DELEGATE_DIRECTORY : undefined,
    isFetching: false,
  }),
}))

vi.mock('@/hooks/use-users', () => ({
  useUsersByIds: (ids: string[]) => ({
    data: DELEGATE_DIRECTORY.filter((u) => ids.includes(u.id)),
    isLoading: false,
  }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** now 기준 상대 오프셋(ms)의 datetime-local input value(로컬 기준, 분 단위) */
function localValueAt(offsetMs: number): string {
  return toLocalInputValue(new Date(Date.now() + offsetMs).toISOString())
}

describe('OooModal', () => {
  beforeEach(() => {
    updateMutate.mockClear()
    clearMutate.mockClear()
    refreshWhoamiMock.mockClear()
    mutationState.updatePending = false
    mutationState.clearPending = false
    oooQueryState.data = emptyOoo
    vi.spyOn(window, 'alert').mockImplementation(() => {})
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('open=false면 렌더하지 않는다', () => {
    render(<OooModal open={false} onOpenChange={vi.fn()} />)
    expect(screen.queryByText(oooLabels.title)).toBeNull()
  })

  it('open=true면 기간/대리자검색/메시지/저장/해제 버튼을 렌더한다', () => {
    render(<OooModal open onOpenChange={vi.fn()} />)

    expect(screen.getByText(oooLabels.title)).toBeInTheDocument()
    expect(screen.getByLabelText(oooLabels.startsAtLabel)).toBeInTheDocument()
    expect(screen.getByLabelText(oooLabels.endsAtLabel)).toBeInTheDocument()
    expect(screen.getByPlaceholderText(oooLabels.delegateSearchPlaceholder)).toBeInTheDocument()
    expect(screen.getByLabelText(oooLabels.messageLabel)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: oooLabels.saveButton })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: oooLabels.clearButton })).toBeInTheDocument()
  })

  it('메시지 textarea에 maxLength=500이 걸려있다', () => {
    render(<OooModal open onOpenChange={vi.fn()} />)

    expect(screen.getByLabelText(oooLabels.messageLabel)).toHaveAttribute('maxlength', '500')
  })

  it('대리자 검색어가 2자 미만이면 검색 결과를 표시하지 않는다', () => {
    render(<OooModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByPlaceholderText(oooLabels.delegateSearchPlaceholder), {
      target: { value: '밥' },
    })

    expect(screen.queryByText('밥')).toBeNull()
  })

  it('대리자 검색어가 2자 이상이면 결과 목록이 표시되고 클릭으로 선택할 수 있다', () => {
    render(<OooModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByPlaceholderText(oooLabels.delegateSearchPlaceholder), {
      target: { value: '밥밥' },
    })
    fireEvent.click(screen.getByText('밥'))

    expect(screen.getByText(bobUser.displayName as string)).toBeInTheDocument()
    // 선택 후 검색어가 초기화되어 목록은 사라진다(선택됨 표시만 남음)
    expect(screen.queryByPlaceholderText(oooLabels.delegateSearchPlaceholder)).toHaveValue('')
  })

  it('대리자 선택 해제 버튼으로 선택을 취소할 수 있다', () => {
    render(<OooModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByPlaceholderText(oooLabels.delegateSearchPlaceholder), {
      target: { value: '밥밥' },
    })
    fireEvent.click(screen.getByText('밥'))
    expect(screen.getByText(bobUser.displayName as string)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '대리자 선택 해제' }))
    expect(screen.queryByText(bobUser.displayName as string)).toBeNull()
  })

  it('저장 시 기간을 ISO Instant로 변환하고 선택한 대리자·메시지를 담아 updateOoo mutate를 호출한다', () => {
    const onOpenChange = vi.fn()
    render(<OooModal open onOpenChange={onOpenChange} />)

    const startValue = localValueAt(60 * 60 * 1000)
    const endValue = localValueAt(2 * 60 * 60 * 1000)

    fireEvent.change(screen.getByLabelText(oooLabels.startsAtLabel), { target: { value: startValue } })
    fireEvent.change(screen.getByLabelText(oooLabels.endsAtLabel), { target: { value: endValue } })
    fireEvent.change(screen.getByPlaceholderText(oooLabels.delegateSearchPlaceholder), {
      target: { value: '밥밥' },
    })
    fireEvent.click(screen.getByText('밥'))
    fireEvent.change(screen.getByLabelText(oooLabels.messageLabel), {
      target: { value: '휴가 중입니다. 급한 건 bob에게.' },
    })
    fireEvent.click(screen.getByRole('button', { name: oooLabels.saveButton }))

    expect(updateMutate).toHaveBeenCalledTimes(1)
    const body = updateMutate.mock.calls[0]?.[0] as OooPatchBody
    expect(body.startsAt).toBe(toInstant(startValue))
    expect(body.endsAt).toBe(toInstant(endValue))
    expect(body.delegateUserId).toBe(bobUser.id)
    expect(body.message).toBe('휴가 중입니다. 급한 건 bob에게.')

    // 성공 시 whoami 재조회 + 모달 닫기
    expect(refreshWhoamiMock).toHaveBeenCalledTimes(1)
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('공백만 있는 메시지는 null로 정규화되어 전달된다', () => {
    render(<OooModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(oooLabels.startsAtLabel), {
      target: { value: localValueAt(60 * 60 * 1000) },
    })
    fireEvent.change(screen.getByLabelText(oooLabels.endsAtLabel), {
      target: { value: localValueAt(2 * 60 * 60 * 1000) },
    })
    fireEvent.change(screen.getByLabelText(oooLabels.messageLabel), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: oooLabels.saveButton }))

    const body = updateMutate.mock.calls[0]?.[0] as OooPatchBody
    expect(body.message).toBeNull()
  })

  it('종료 시각이 시작 시각보다 이전(또는 같음)이면 alert를 표시하고 mutate를 호출하지 않는다', () => {
    render(<OooModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(oooLabels.startsAtLabel), {
      target: { value: localValueAt(2 * 60 * 60 * 1000) },
    })
    fireEvent.change(screen.getByLabelText(oooLabels.endsAtLabel), {
      target: { value: localValueAt(60 * 60 * 1000) },
    })
    fireEvent.click(screen.getByRole('button', { name: oooLabels.saveButton }))

    expect(window.alert).toHaveBeenCalledWith(oooLabels.errorMessage)
    expect(updateMutate).not.toHaveBeenCalled()
  })

  it('종료 시각이 현재보다 과거(또는 같음)이면 alert를 표시하고 mutate를 호출하지 않는다', () => {
    render(<OooModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(oooLabels.startsAtLabel), {
      target: { value: localValueAt(-2 * 60 * 60 * 1000) },
    })
    fireEvent.change(screen.getByLabelText(oooLabels.endsAtLabel), {
      target: { value: localValueAt(-60 * 60 * 1000) },
    })
    fireEvent.click(screen.getByRole('button', { name: oooLabels.saveButton }))

    expect(window.alert).toHaveBeenCalledWith(oooLabels.errorMessage)
    expect(updateMutate).not.toHaveBeenCalled()
  })

  it('백엔드가 400을 반환(mutation onError)하면 alert를 표시한다', () => {
    updateMutate.mockImplementationOnce(
      (_body: unknown, opts?: { onSuccess?: () => void; onError?: () => void }) => {
        opts?.onError?.()
      },
    )
    render(<OooModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(oooLabels.startsAtLabel), {
      target: { value: localValueAt(60 * 60 * 1000) },
    })
    fireEvent.change(screen.getByLabelText(oooLabels.endsAtLabel), {
      target: { value: localValueAt(2 * 60 * 60 * 1000) },
    })
    fireEvent.click(screen.getByRole('button', { name: oooLabels.saveButton }))

    expect(window.alert).toHaveBeenCalledWith(oooLabels.errorMessage)
  })

  it('"부재중 해제" 클릭 시 clearOoo mutate를 호출하고 성공 후 whoami 재조회 + 닫기', () => {
    const onOpenChange = vi.fn()
    render(<OooModal open onOpenChange={onOpenChange} />)

    fireEvent.click(screen.getByRole('button', { name: oooLabels.clearButton }))

    expect(clearMutate).toHaveBeenCalledTimes(1)
    expect(refreshWhoamiMock).toHaveBeenCalledTimes(1)
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('닫았다 다시 열면 폼이 현재 설정으로 초기화된다(stale 입력 방지)', () => {
    const { rerender } = render(<OooModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(oooLabels.messageLabel), { target: { value: '남는 메모' } })

    rerender(<OooModal open={false} onOpenChange={vi.fn()} />)
    rerender(<OooModal open onOpenChange={vi.fn()} />)

    expect(screen.getByLabelText(oooLabels.messageLabel)).toHaveValue('')
  })

  it('기존 설정이 있으면 재열림 시 대리자·메시지·기간이 그대로 채워진다', () => {
    oooQueryState.data = {
      startsAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
      endsAt: new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString(),
      delegateUserId: bobUser.id,
      delegateName: bobUser.displayName,
      message: '기존 메시지',
      active: false,
    }

    render(<OooModal open onOpenChange={vi.fn()} />)

    expect(screen.getByLabelText(oooLabels.messageLabel)).toHaveValue('기존 메시지')
    expect(screen.getByText(bobUser.displayName as string)).toBeInTheDocument()
  })
})
