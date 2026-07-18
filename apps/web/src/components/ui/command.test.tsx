// Command 프리미티브 단위 테스트 — cmdk 필터링/empty state 계약 (CommandDialog 제외, core-only)
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import {
  Command,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
} from './command'

// jsdom은 scrollIntoView를 구현하지 않는다 — cmdk Command.Item이 활성 항목 변경 시
// 내부적으로 호출하므로(CommandPalette.test.tsx 선례), 이 파일 범위에서만 no-op stub을 등록한다.
if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
  window.HTMLElement.prototype.scrollIntoView = () => {}
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 렌더 헬퍼 — 서로 겹치는 부분 문자열이 없는 3개 항목으로 필터 결과를 결정적으로 만든다
// ─────────────────────────────────────────────────────────────────────────────

function renderCommand() {
  return render(
    <Command label="테스트 커맨드">
      <CommandInput placeholder="검색" />
      <CommandList>
        <CommandEmpty>결과 없음</CommandEmpty>
        <CommandGroup heading="과일">
          <CommandItem value="apple">Apple</CommandItem>
          <CommandItem value="banana">Banana</CommandItem>
          <CommandItem value="cherry">Cherry</CommandItem>
        </CommandGroup>
      </CommandList>
    </Command>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: 기본 렌더 — 입력창(combobox) + 항목 3개(option) 모두 노출
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-1: 기본 렌더', () => {
  it('CommandInput은 role="combobox", CommandItem 3개는 role="option"으로 노출된다', () => {
    renderCommand()

    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(3)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-2: 매치되는 문자 입력 시 해당 CommandItem만 필터되어 남음
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-2: 검색어 필터링', () => {
  it('"cherry" 입력 시 Cherry 항목만 남는다', async () => {
    const user = userEvent.setup({ delay: null })
    renderCommand()

    const input = screen.getByRole('combobox')
    await user.type(input, 'cherry')

    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(1)
    expect(options[0]).toHaveTextContent('Cherry')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-3: 매치 0건 → CommandEmpty 텍스트 노출
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-3: 매치 0건', () => {
  it('어떤 항목과도 매치되지 않는 문자 입력 시 CommandEmpty 텍스트가 노출된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderCommand()

    const input = screen.getByRole('combobox')
    await user.type(input, 'xyz')

    expect(screen.queryAllByRole('option')).toHaveLength(0)
    expect(screen.getByText('결과 없음')).toBeInTheDocument()
  })
})
