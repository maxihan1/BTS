// 검색 가능 단일 선택 combobox 테스트 — command+popover 합성
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Combobox } from '../combobox'

// jsdom 은 scrollIntoView 를 구현하지 않는다 — cmdk CommandItem 이 활성 항목이 바뀔 때마다
// 부른다. `ui/command.test.tsx:16` 이 쓰는 것과 같은 처방이다.
if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
  window.HTMLElement.prototype.scrollIntoView = () => {}
}

const OPTIONS = [
  { value: 'todo', label: '할 일' },
  { value: 'in-progress', label: '진행 중' },
  { value: 'done', label: '완료' },
]

function setup(overrides: Partial<React.ComponentProps<typeof Combobox>> = {}) {
  const onChange = vi.fn()
  render(
    <Combobox
      options={OPTIONS}
      value={null}
      onChange={onChange}
      ariaLabel="상태 선택"
      placeholder="상태 이름으로 검색..."
      emptyText="일치하는 상태가 없습니다"
      triggerPlaceholder="상태를 고르세요"
      {...overrides}
    />,
  )
  return { onChange }
}

describe('Combobox', () => {
  it('트리거에 고유 aria-label 이 붙는다', () => {
    setup()
    expect(screen.getByRole('combobox', { name: '상태 선택' })).toBeInTheDocument()
  })

  it('값이 없으면 트리거에 placeholder 를 보여준다', () => {
    setup()
    expect(screen.getByRole('combobox', { name: '상태 선택' })).toHaveTextContent('상태를 고르세요')
  })

  it('값이 있으면 그 라벨을 보여준다 — value 가 아니라 label 이다', () => {
    setup({ value: 'in-progress' })
    expect(screen.getByRole('combobox', { name: '상태 선택' })).toHaveTextContent('진행 중')
  })

  it('열면 옵션 전부가 보인다', async () => {
    setup()
    await userEvent.click(screen.getByRole('combobox', { name: '상태 선택' }))
    expect(screen.getAllByRole('option')).toHaveLength(OPTIONS.length)
  })

  it('고르면 value 로 onChange 가 불린다', async () => {
    const { onChange } = setup()
    await userEvent.click(screen.getByRole('combobox', { name: '상태 선택' }))
    await userEvent.click(screen.getByRole('option', { name: '완료' }))
    expect(onChange).toHaveBeenCalledWith('done')
  })

  it('검색하면 일치하는 것만 남는다', async () => {
    setup()
    await userEvent.click(screen.getByRole('combobox', { name: '상태 선택' }))
    await userEvent.type(screen.getByPlaceholderText('상태 이름으로 검색...'), '진행')
    expect(screen.getAllByRole('option')).toHaveLength(1)
  })

  it('일치가 없으면 emptyText 를 보여준다', async () => {
    setup()
    await userEvent.click(screen.getByRole('combobox', { name: '상태 선택' }))
    await userEvent.type(screen.getByPlaceholderText('상태 이름으로 검색...'), 'zzzz')
    expect(screen.getByText('일치하는 상태가 없습니다')).toBeInTheDocument()
    expect(screen.queryAllByRole('option')).toHaveLength(0)
  })

  it('disabled 면 열리지 않는다', async () => {
    setup({ disabled: true })
    await userEvent.click(screen.getByRole('combobox', { name: '상태 선택' }))
    expect(screen.queryAllByRole('option')).toHaveLength(0)
  })
})
