// SlackResultBanner 컴포넌트 테스트 — installed/error props → 배너 메시지 매핑 검증 (FR-SL-01 D6/D7 Task 6/R8)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SlackResultBanner } from './SlackResultBanner'

describe('SlackResultBanner — 성공(installed)', () => {
  it('installed가 있으면 role=alert로 팀 이름 포함 연결 완료 메시지를 렌더한다', () => {
    render(<SlackResultBanner installed="Acme Corp" />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent('Acme Corp 워크스페이스에 연결되었습니다')
  })
})

describe('SlackResultBanner — 실패(error) 코드별 메시지', () => {
  const cases: Array<[string, string]> = [
    ['access_denied', 'Slack 연결이 취소되었습니다.'],
    ['invalid_state', '연결 요청이 만료되었거나 유효하지 않습니다. 다시 시도해 주세요.'],
    ['missing_params', '연결 정보가 누락되었습니다. 다시 시도해 주세요.'],
    ['exchange_failed', 'Slack과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.'],
    ['unsupported_install_type', '워크스페이스 단위 설치만 지원합니다(조직 전체 설치 불가).'],
    ['missing_access_token', 'Slack이 유효한 봇 토큰을 반환하지 않았습니다. 다시 시도해 주세요.'],
  ]

  it.each(cases)('error=%s → "%s"', (code, message) => {
    render(<SlackResultBanner error={code} />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent(message)
  })

  it('invalid_code는 전용 메시지("Slack이 인증 코드를 거부했습니다")로 분리 표시된다', () => {
    render(<SlackResultBanner error="invalid_code" />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent('Slack이 인증 코드를 거부했습니다. 다시 시도해 주세요.')
  })

  it('미지원 코드(oauth_failed)는 일반 실패 메시지로 폴백한다', () => {
    render(<SlackResultBanner error="oauth_failed" />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent('Slack 연결에 실패했습니다. 다시 시도해 주세요.')
  })

  it('미지원 코드(install_failed)는 일반 실패 메시지로 폴백한다', () => {
    render(<SlackResultBanner error="install_failed" />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent('Slack 연결에 실패했습니다. 다시 시도해 주세요.')
  })

  it('완전히 알 수 없는 코드도 일반 실패 메시지로 폴백하고, 코드 원문은 화면에 노출하지 않는다', () => {
    render(<SlackResultBanner error="weird_xyz" />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent('Slack 연결에 실패했습니다. 다시 시도해 주세요.')
    expect(banner).not.toHaveTextContent('weird_xyz')
  })
})

describe('SlackResultBanner — 우선순위/빈 상태', () => {
  it('installed와 error가 함께 오면 error가 우선한다(취소 메시지)', () => {
    render(<SlackResultBanner installed="X" error="access_denied" />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent('Slack 연결이 취소되었습니다.')
    expect(banner).not.toHaveTextContent('X 워크스페이스에 연결되었습니다')
  })

  it('installed와 error가 모두 없으면 아무것도 렌더하지 않는다', () => {
    const { container } = render(<SlackResultBanner />)

    expect(screen.queryByRole('alert')).toBeNull()
    expect(container.firstChild).toBeNull()
  })
})

describe('SlackResultBanner — 다시 시도(onRetry)', () => {
  it('오류 배너에 onRetry를 주면 "다시 시도" 버튼이 렌더되고 클릭 시 onRetry가 호출된다', async () => {
    const onRetry = vi.fn()
    const user = userEvent.setup()
    render(<SlackResultBanner error="exchange_failed" onRetry={onRetry} />)

    const retryButton = screen.getByRole('button', { name: '다시 시도' })
    await user.click(retryButton)

    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('성공 배너에는 onRetry를 줘도 "다시 시도" 버튼이 렌더되지 않는다', () => {
    const onRetry = vi.fn()
    render(<SlackResultBanner installed="Acme" onRetry={onRetry} />)

    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('onRetry가 없으면 오류 배너에도 "다시 시도" 버튼이 렌더되지 않는다', () => {
    render(<SlackResultBanner error="exchange_failed" />)

    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })
})

describe('SlackResultBanner — 닫기(onDismiss)', () => {
  it('onDismiss를 주면 "닫기" 버튼이 렌더되고 클릭 시 onDismiss가 호출된다(오류 배너)', async () => {
    const onDismiss = vi.fn()
    const user = userEvent.setup()
    render(<SlackResultBanner error="access_denied" onDismiss={onDismiss} />)

    const dismissButton = screen.getByRole('button', { name: '닫기' })
    await user.click(dismissButton)

    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('onDismiss를 주면 성공 배너에도 "닫기" 버튼이 렌더된다', async () => {
    const onDismiss = vi.fn()
    const user = userEvent.setup()
    render(<SlackResultBanner installed="Acme" onDismiss={onDismiss} />)

    const dismissButton = screen.getByRole('button', { name: '닫기' })
    await user.click(dismissButton)

    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('onDismiss가 없으면 "닫기" 버튼이 렌더되지 않는다', () => {
    render(<SlackResultBanner installed="Acme" />)

    expect(screen.queryByRole('button', { name: '닫기' })).not.toBeInTheDocument()
  })
})

describe('SlackResultBanner — 색상/아이콘 유지', () => {
  it('성공 배너는 primary 색상 클래스와 체크 아이콘을 유지한다', () => {
    render(<SlackResultBanner installed="Acme" />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveClass('text-primary')
    expect(banner.querySelector('svg.lucide-circle-check')).not.toBeNull()
  })

  it('오류 배너는 destructive 색상 클래스와 경고 아이콘을 유지한다', () => {
    render(<SlackResultBanner error="access_denied" />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveClass('text-destructive')
    expect(banner.querySelector('svg.lucide-triangle-alert')).not.toBeNull()
  })
})
