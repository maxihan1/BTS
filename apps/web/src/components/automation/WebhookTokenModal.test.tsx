// WebhookTokenModal 단위 테스트 — 1회 노출·복사·닫기·미렌더 (FR-AT-01 D6 Task 7)
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { WebhookTokenModal } from '@/components/automation/WebhookTokenModal'

const RAW_TOKEN = 'whk_1234567890abcdef1234567890abcdef'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('WebhookTokenModal — token null', () => {
  it('token이 null이면 아무것도 렌더하지 않는다', () => {
    const onClose = vi.fn()
    const { container } = render(<WebhookTokenModal token={null} onClose={onClose} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('WebhookTokenModal — token 있음', () => {
  it('토큰 문자열과 1회 경고 문구, 복사 버튼을 표시한다', () => {
    render(<WebhookTokenModal token={RAW_TOKEN} onClose={vi.fn()} />)

    expect(screen.getByText(RAW_TOKEN)).toBeInTheDocument()
    expect(
      screen.getByText('이 토큰은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없습니다.'),
    ).toBeInTheDocument()
    expect(screen.getByTestId('webhook-token-copy-button')).toBeInTheDocument()
  })

  it('복사 버튼을 클릭하면 navigator.clipboard.writeText가 호출되고 "복사됨" 라벨로 바뀐다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })

    const user = userEvent.setup()
    render(<WebhookTokenModal token={RAW_TOKEN} onClose={vi.fn()} />)

    await user.click(screen.getByTestId('webhook-token-copy-button'))

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(RAW_TOKEN)
    })
    expect(await screen.findByText('복사됨')).toBeInTheDocument()
  })

  it('닫기 버튼을 클릭하면 onClose가 호출된다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(<WebhookTokenModal token={RAW_TOKEN} onClose={onClose} />)

    await user.click(screen.getByTestId('webhook-token-close-button'))

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
