// CommandPalette 단위 테스트 — 빈 목록 렌더/명령 힌트 prefill/goto·search·issue 실행/E1~E3/IME 가드 (FR-UX-04 Task-2)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CommandPalette } from './CommandPalette'
import { QUICK_LINKS, COMMANDS } from './commands'

// jsdom은 scrollIntoView를 구현하지 않는다 — cmdk Command.Item이 활성 항목 변경 시
// 내부적으로 호출하므로(cmdk 소스 `ne()`), 이 파일 범위에서만 no-op stub을 등록한다.
if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
  window.HTMLElement.prototype.scrollIntoView = () => {}
}

// TanStack Router useNavigate 모킹 — 라우터 컨텍스트 없이 단위 테스트 (Header.test.tsx 패턴 미러)
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

/** CommandPalette를 열린 상태로 렌더하고 onOpenChange mock을 반환하는 헬퍼 */
function renderPalette() {
  const onOpenChange = vi.fn()
  render(<CommandPalette open onOpenChange={onOpenChange} />)
  return { onOpenChange }
}

beforeEach(() => {
  mockNavigate.mockReset()
})

describe('CommandPalette', () => {
  it('빈 입력 시 QUICK_LINKS 4개 + 명령 힌트 3개를 렌더한다 (S2)', () => {
    renderPalette()

    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(QUICK_LINKS.length + COMMANDS.length)

    for (const link of QUICK_LINKS) {
      expect(screen.getByRole('option', { name: link.label })).toBeInTheDocument()
    }
  })

  it('명령 힌트 /goto 선택 시 입력창에 /goto 를 prefill하고 라우팅하지 않는다 (S2 보강)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const gotoHint = screen.getByRole('option', { name: /goto/i })
    await user.click(gotoHint)

    const input = screen.getByRole('combobox') as HTMLInputElement
    expect(input.value).toBe('/goto ')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('/goto PROJ-12 입력 후 Enter → 이슈 상세로 navigate하고 팔레트를 닫는다 (S3)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto PROJ-12')
    await user.keyboard('{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/$key', params: { key: 'PROJ-12' } })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('/search 로그인 버그 입력 후 Enter → text ~ 로 감싼 AQL 로 navigate 한다 (S7 · FR9)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/search 로그인 버그')
    await user.keyboard('{Enter}')

    // 봉합 전에는 { q: '로그인 버그' } 였고 그것은 실서버에서 SEARCH_SYNTAX_ERROR 다
    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/search',
      search: { q: 'text ~ "로그인 버그"' },
    })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('/search 에 따옴표가 들어가도 유효한 AQL 이 된다 (E4)', async () => {
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '/search 로그인"버그')
    await user.keyboard('{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/search',
      search: { q: 'text ~ "로그인\\"버그"' },
    })
  })

  it('/issue 결제 실패 조사 입력 후 Enter → 새 이슈 폼으로 navigate한다 (S5)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/issue 결제 실패 조사')
    await user.keyboard('{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/new', search: { summary: '결제 실패 조사' } })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('알 수 없는 명령(/foo)은 안내 문구를 표시하고 navigate하지 않는다 (E1)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/foo x')

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('알 수 없는 명령입니다. /foo')
    // 문장 종결에 콜론을 쓰지 않는다 (§5 회귀 방지, codereview CONCERN-2)
    expect(alert.textContent).not.toContain(':')

    await user.keyboard('{Enter}')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('외부 open이 false로 바뀌었다가 다시 true가 되면 입력을 초기화한다 (Cmd+K 토글 재오픈, codereview CONCERN-1)', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    const { rerender } = render(<CommandPalette open onOpenChange={onOpenChange} />)

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto ')
    // 인자 없는 /goto → 안내 문구만 표시, 빠른이동 목록은 숨겨진다
    expect(screen.queryAllByRole('option')).toHaveLength(0)

    // Radix Command.Dialog는 controlled open이 외부에서 false로 바뀌어도
    // onOpenChange를 호출하지 않는다 — useCommandPalette의 토글(Cmd+K)을 흉내
    rerender(<CommandPalette open={false} onOpenChange={onOpenChange} />)
    rerender(<CommandPalette open onOpenChange={onOpenChange} />)

    const reopenedInput = screen.getByRole('combobox') as HTMLInputElement
    expect(reopenedInput.value).toBe('')
    expect(screen.getAllByRole('option')).toHaveLength(QUICK_LINKS.length + COMMANDS.length)
  })

  it('인자 없는 /goto는 사용법 안내를 표시하고 navigate하지 않는다 (E2)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto')

    expect(screen.getByRole('alert')).toHaveTextContent(/goto/i)

    await user.keyboard('{Enter}')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('이슈 키 형식이 아닌 /goto 안녕은 형식 안내를 표시하고 navigate하지 않는다 (E3)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto 안녕')

    expect(screen.getByRole('alert')).toHaveTextContent(/PROJ-12/)

    await user.keyboard('{Enter}')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('IME 조합 중 Enter는 명령을 실행하지 않는다 (NFR4)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto PROJ-12')
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })

    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })
})
