// GitWebhookUrlModal 단위 테스트 — URL 1회 노출·복사·닫기 3경로 2단계 확인·미렌더 (FR-AT-07 PR-D Task 4)
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GitWebhookUrlModal } from '@/components/automation/GitWebhookUrlModal'

const WEBHOOK_PATH = '/api/v1/webhooks/git/tok'
const FULL_URL = `${window.location.origin}${WEBHOOK_PATH}`
const COPY_FAILED_TEXT = '복사에 실패했습니다. 직접 선택해 복사해 주세요.'
const CLOSE_CONFIRM_TEXT = 'URL은 다시 볼 수 없습니다. 닫을까요?'

// ─────────────────────────────────────────────────────────────────────────────
// clipboard mock — vi.stubGlobal 패턴 (WebhookTokenModal.test.tsx 동일).
// userEvent.setup()이 navigator를 재구성하므로 스텁은 setup() 이후에 적용한다.
// ─────────────────────────────────────────────────────────────────────────────

const clipboardWriteText = vi.fn()

function stubClipboard(): void {
  clipboardWriteText.mockResolvedValue(undefined)
  vi.stubGlobal('navigator', {
    ...navigator,
    clipboard: { writeText: clipboardWriteText },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('GitWebhookUrlModal — webhookUrl null', () => {
  it('webhookUrl 이 null 이면 렌더하지 않는다', () => {
    const { container } = render(<GitWebhookUrlModal webhookUrl={null} onClose={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('GitWebhookUrlModal — webhookUrl 있음', () => {
  it('표시 문자열이 window.location.origin 을 포함한 완전 URL 이다', () => {
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={vi.fn()} />)
    expect(screen.getByText(FULL_URL)).toBeInTheDocument()
  })

  it('복사 버튼이 완전 URL 을 클립보드에 쓴다', async () => {
    const user = userEvent.setup()
    stubClipboard()
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={vi.fn()} />)

    await user.click(screen.getByTestId('git-webhook-url-copy-button'))

    await waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith(FULL_URL)
    })
  })

  it('clipboard reject 시 실패 문구를 role="alert" 로 낸다', async () => {
    const user = userEvent.setup()
    clipboardWriteText.mockRejectedValueOnce(new Error('clipboard denied'))
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: clipboardWriteText } })
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={vi.fn()} />)

    await user.click(screen.getByTestId('git-webhook-url-copy-button'))

    const failureAlert = await screen.findByText(COPY_FAILED_TEXT)
    expect(failureAlert).toHaveAttribute('role', 'alert')
  })

  it('URL <code> 에 select-all 클래스가 있다', () => {
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={vi.fn()} />)
    const codeEl = screen.getByText(FULL_URL)
    expect(codeEl.tagName).toBe('CODE')
    expect(codeEl).toHaveClass('select-all')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR8 · EC6 — 닫기 경로별 독립 it. 경로 전수 실측 = 3개(X 버튼 / ESC / 오버레이 pointer-down).
// 각 it 는 ① 즉시 안 닫힘(onClose 미호출) ② 확인 프롬프트 등장 둘 다 단언한다. 번들 금지 —
// 번들하면 첫 경로 expect가 터질 때 나머지 경로는 실행조차 안 되고 무가드를 못 잡는다.
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookUrlModal — 닫기 3경로 2단계 확인 (FR8/EC6)', () => {
  it('ESC 는 즉시 닫지 않고 2단계 확인을 띄운다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={onClose} />)

    await user.keyboard('{Escape}')

    expect(onClose).not.toHaveBeenCalled()
    expect(await screen.findByText(CLOSE_CONFIRM_TEXT)).toBeInTheDocument()
  })

  it('오버레이 클릭은 즉시 닫지 않고 2단계 확인을 띄운다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={onClose} />)

    await user.click(screen.getByTestId('git-webhook-url-overlay'))

    expect(onClose).not.toHaveBeenCalled()
    expect(await screen.findByText(CLOSE_CONFIRM_TEXT)).toBeInTheDocument()
  })

  it('닫기 버튼(X)은 즉시 닫지 않고 2단계 확인을 띄운다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={onClose} />)

    await user.click(screen.getByTestId('git-webhook-url-close-button'))

    expect(onClose).not.toHaveBeenCalled()
    expect(await screen.findByText(CLOSE_CONFIRM_TEXT)).toBeInTheDocument()
  })

  it('2단계 확인에서 확인을 누르면 onClose 가 호출된다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={onClose} />)

    await user.click(screen.getByTestId('git-webhook-url-close-button'))
    await user.click(await screen.findByTestId('git-webhook-url-close-confirm'))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('2단계 확인에서 취소를 누르면 onClose 가 호출되지 않고 URL 이 계속 보인다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(<GitWebhookUrlModal webhookUrl={WEBHOOK_PATH} onClose={onClose} />)

    await user.click(screen.getByTestId('git-webhook-url-close-button'))
    await user.click(await screen.findByTestId('git-webhook-url-close-cancel'))

    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByText(FULL_URL)).toBeInTheDocument()
  })
})
