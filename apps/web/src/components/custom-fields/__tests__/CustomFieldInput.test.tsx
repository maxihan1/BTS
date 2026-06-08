// CustomFieldInput — FieldType 10종 위젯 단위 테스트 (FR-IS-10 D6 Task 5)
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { CustomFieldInput } from '../CustomFieldInput'
import type { CustomField } from '@/api/custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트용 CustomField 팩토리
// ─────────────────────────────────────────────────────────────────────────────

function makeField(overrides: Partial<CustomField>): CustomField {
  return {
    id: '00000000-0000-4000-a000-000000000001',
    projectId: '00000000-0000-4000-a000-000000000002',
    key: 'test-field',
    name: '테스트 필드',
    description: null,
    fieldType: 'SHORT_TEXT',
    required: false,
    displayOrder: 0,
    options: [],
    ...overrides,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHORT_TEXT
// ─────────────────────────────────────────────────────────────────────────────

describe('SHORT_TEXT', () => {
  it('text input을 렌더한다', () => {
    const field = makeField({ fieldType: 'SHORT_TEXT', key: 'sf' })
    render(<CustomFieldInput field={field} value="hello" onChange={vi.fn()} />)
    const input = screen.getByTestId('custom-field-sf')
    expect(input.tagName).toBe('INPUT')
    expect((input as HTMLInputElement).type).toBe('text')
    expect((input as HTMLInputElement).value).toBe('hello')
  })

  it('onChange가 string 값으로 호출된다', async () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'SHORT_TEXT', key: 'sf' })
    render(<CustomFieldInput field={field} value="" onChange={onChange} />)
    const input = screen.getByTestId('custom-field-sf')
    await userEvent.type(input, 'A')
    expect(onChange).toHaveBeenCalledWith('A')
  })

  it('disabled 상태가 전파된다', () => {
    const field = makeField({ fieldType: 'SHORT_TEXT', key: 'sf' })
    render(<CustomFieldInput field={field} value="" onChange={vi.fn()} disabled />)
    expect(screen.getByTestId('custom-field-sf')).toBeDisabled()
  })

  it('required 상태가 전파된다', () => {
    const field = makeField({ fieldType: 'SHORT_TEXT', key: 'sf', required: true })
    render(<CustomFieldInput field={field} value="" onChange={vi.fn()} />)
    expect(screen.getByTestId('custom-field-sf')).toBeRequired()
  })

  it('aria-label에 field.name이 포함된다', () => {
    const field = makeField({ fieldType: 'SHORT_TEXT', key: 'sf', name: '제목' })
    render(<CustomFieldInput field={field} value="" onChange={vi.fn()} />)
    expect(screen.getByTestId('custom-field-sf')).toHaveAttribute('aria-label', '제목')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// LONG_TEXT
// ─────────────────────────────────────────────────────────────────────────────

describe('LONG_TEXT', () => {
  it('textarea를 렌더한다', () => {
    const field = makeField({ fieldType: 'LONG_TEXT', key: 'lt' })
    render(<CustomFieldInput field={field} value={'multi\nline'} onChange={vi.fn()} />)
    const el = screen.getByTestId('custom-field-lt')
    expect(el.tagName).toBe('TEXTAREA')
    expect((el as HTMLTextAreaElement).value).toBe('multi\nline')
  })

  it('onChange가 string 값으로 호출된다', async () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'LONG_TEXT', key: 'lt' })
    render(<CustomFieldInput field={field} value="" onChange={onChange} />)
    fireEvent.change(screen.getByTestId('custom-field-lt'), { target: { value: 'new text' } })
    expect(onChange).toHaveBeenCalledWith('new text')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// NUMBER
// ─────────────────────────────────────────────────────────────────────────────

describe('NUMBER', () => {
  it('type=number input을 렌더한다', () => {
    const field = makeField({ fieldType: 'NUMBER', key: 'num' })
    render(<CustomFieldInput field={field} value={42} onChange={vi.fn()} />)
    const input = screen.getByTestId('custom-field-num')
    expect((input as HTMLInputElement).type).toBe('number')
    expect((input as HTMLInputElement).value).toBe('42')
  })

  it('숫자 입력 시 onChange가 number로 호출된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'NUMBER', key: 'num' })
    render(<CustomFieldInput field={field} value={0} onChange={onChange} />)
    fireEvent.change(screen.getByTestId('custom-field-num'), { target: { value: '7' } })
    expect(onChange).toHaveBeenCalledWith(7)
  })

  it('빈 값 입력 시 onChange가 undefined로 호출된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'NUMBER', key: 'num' })
    render(<CustomFieldInput field={field} value={1} onChange={onChange} />)
    fireEvent.change(screen.getByTestId('custom-field-num'), { target: { value: '' } })
    expect(onChange).toHaveBeenCalledWith(undefined)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DATE
// ─────────────────────────────────────────────────────────────────────────────

describe('DATE', () => {
  it('type=date input을 렌더한다', () => {
    const field = makeField({ fieldType: 'DATE', key: 'dt' })
    render(<CustomFieldInput field={field} value="2024-03-15" onChange={vi.fn()} />)
    const input = screen.getByTestId('custom-field-dt')
    expect((input as HTMLInputElement).type).toBe('date')
    expect((input as HTMLInputElement).value).toBe('2024-03-15')
  })

  it('onChange가 YYYY-MM-DD 문자열로 호출된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'DATE', key: 'dt' })
    render(<CustomFieldInput field={field} value="" onChange={onChange} />)
    fireEvent.change(screen.getByTestId('custom-field-dt'), { target: { value: '2024-12-01' } })
    expect(onChange).toHaveBeenCalledWith('2024-12-01')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DATETIME
// ─────────────────────────────────────────────────────────────────────────────

describe('DATETIME', () => {
  it('type=datetime-local input을 렌더한다', () => {
    const field = makeField({ fieldType: 'DATETIME', key: 'dtt' })
    // ISO 오프셋 문자열을 전달하면 datetime-local 형식으로 변환되어야 함
    render(<CustomFieldInput field={field} value="2024-03-15T09:30:00+09:00" onChange={vi.fn()} />)
    const input = screen.getByTestId('custom-field-dtt') as HTMLInputElement
    expect(input.type).toBe('datetime-local')
    // 로컬 입력값 형식: "YYYY-MM-DDTHH:mm"
    expect(input.value).toBe('2024-03-15T09:30')
  })

  it('onChange는 ISO8601 offset 문자열로 변환된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'DATETIME', key: 'dtt' })
    render(<CustomFieldInput field={field} value="" onChange={onChange} />)
    fireEvent.change(screen.getByTestId('custom-field-dtt'), { target: { value: '2024-06-01T14:00' } })
    // ISO offset 형식으로 변환되어야 함
    const called = onChange.mock.calls[0]?.[0] as string
    expect(called).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?([+-]\d{2}:\d{2}|Z)$/)
  })

  it('빈 값 입력 시 onChange가 빈 문자열로 호출된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'DATETIME', key: 'dtt' })
    render(<CustomFieldInput field={field} value="2024-03-15T09:30:00+09:00" onChange={onChange} />)
    fireEvent.change(screen.getByTestId('custom-field-dtt'), { target: { value: '' } })
    expect(onChange).toHaveBeenCalledWith('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// SINGLE_SELECT
// ─────────────────────────────────────────────────────────────────────────────

describe('SINGLE_SELECT', () => {
  const options = [
    { value: 'opt1', label: '옵션 1', displayOrder: 0 },
    { value: 'opt2', label: '옵션 2', displayOrder: 1 },
  ]

  it('shadcn Select trigger를 렌더하고 data-testid가 붙는다', () => {
    const field = makeField({ fieldType: 'SINGLE_SELECT', key: 'ss', options })
    render(<CustomFieldInput field={field} value="opt1" onChange={vi.fn()} />)
    // Select trigger는 data-testid로 찾는다
    expect(screen.getByTestId('custom-field-ss')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// MULTI_SELECT
// ─────────────────────────────────────────────────────────────────────────────

describe('MULTI_SELECT', () => {
  const options = [
    { value: 'a', label: '항목 A', displayOrder: 0 },
    { value: 'b', label: '항목 B', displayOrder: 1 },
    { value: 'c', label: '항목 C', displayOrder: 2 },
  ]

  it('체크박스 목록을 렌더한다', () => {
    const field = makeField({ fieldType: 'MULTI_SELECT', key: 'ms', options })
    render(<CustomFieldInput field={field} value={['a']} onChange={vi.fn()} />)
    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(3)
    // 첫 번째 옵션이 체크되어 있어야 함
    expect(checkboxes[0]).toBeChecked()
    expect(checkboxes[1]).not.toBeChecked()
  })

  it('체크 시 value 배열에 추가된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'MULTI_SELECT', key: 'ms', options })
    render(<CustomFieldInput field={field} value={['a']} onChange={onChange} />)
    // '항목 B' 체크박스 클릭
    fireEvent.click(screen.getAllByRole('checkbox')[1]!)
    expect(onChange).toHaveBeenCalledWith(['a', 'b'])
  })

  it('체크 해제 시 value 배열에서 제거된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'MULTI_SELECT', key: 'ms', options })
    render(<CustomFieldInput field={field} value={['a', 'b']} onChange={onChange} />)
    // '항목 A' 체크박스 클릭(해제)
    fireEvent.click(screen.getAllByRole('checkbox')[0]!)
    expect(onChange).toHaveBeenCalledWith(['b'])
  })

  it('data-testid가 컨테이너에 붙는다', () => {
    const field = makeField({ fieldType: 'MULTI_SELECT', key: 'ms', options })
    render(<CustomFieldInput field={field} value={[]} onChange={vi.fn()} />)
    expect(screen.getByTestId('custom-field-ms')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// CHECKBOX
// ─────────────────────────────────────────────────────────────────────────────

describe('CHECKBOX', () => {
  it('단일 checkbox를 렌더한다', () => {
    const field = makeField({ fieldType: 'CHECKBOX', key: 'cb' })
    render(<CustomFieldInput field={field} value={true} onChange={vi.fn()} />)
    const checkbox = screen.getByTestId('custom-field-cb')
    expect(checkbox.tagName).toBe('INPUT')
    expect((checkbox as HTMLInputElement).type).toBe('checkbox')
    expect(checkbox).toBeChecked()
  })

  it('클릭 시 onChange가 boolean으로 호출된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'CHECKBOX', key: 'cb' })
    render(<CustomFieldInput field={field} value={false} onChange={onChange} />)
    fireEvent.click(screen.getByTestId('custom-field-cb'))
    expect(onChange).toHaveBeenCalledWith(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// RADIO
// ─────────────────────────────────────────────────────────────────────────────

describe('RADIO', () => {
  const options = [
    { value: 'r1', label: '라디오 1', displayOrder: 0 },
    { value: 'r2', label: '라디오 2', displayOrder: 1 },
  ]

  it('radio 그룹을 렌더한다', () => {
    const field = makeField({ fieldType: 'RADIO', key: 'radio-key', options })
    render(<CustomFieldInput field={field} value="r1" onChange={vi.fn()} />)
    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(2)
    expect(radios[0]).toBeChecked()
    expect(radios[1]).not.toBeChecked()
  })

  it('모든 radio의 name이 field.key와 같다', () => {
    const field = makeField({ fieldType: 'RADIO', key: 'radio-key', options })
    render(<CustomFieldInput field={field} value="" onChange={vi.fn()} />)
    screen.getAllByRole('radio').forEach((radio) => {
      expect((radio as HTMLInputElement).name).toBe('radio-key')
    })
  })

  it('선택 시 onChange가 string으로 호출된다', () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'RADIO', key: 'radio-key', options })
    render(<CustomFieldInput field={field} value="" onChange={onChange} />)
    fireEvent.click(screen.getAllByRole('radio')[1]!)
    expect(onChange).toHaveBeenCalledWith('r2')
  })

  it('data-testid가 컨테이너에 붙는다', () => {
    const field = makeField({ fieldType: 'RADIO', key: 'radio-key', options })
    render(<CustomFieldInput field={field} value="" onChange={vi.fn()} />)
    expect(screen.getByTestId('custom-field-radio-key')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// URL
// ─────────────────────────────────────────────────────────────────────────────

describe('URL', () => {
  it('type=url input을 렌더한다', () => {
    const field = makeField({ fieldType: 'URL', key: 'url' })
    render(<CustomFieldInput field={field} value="https://example.com" onChange={vi.fn()} />)
    const input = screen.getByTestId('custom-field-url')
    expect((input as HTMLInputElement).type).toBe('url')
    expect((input as HTMLInputElement).value).toBe('https://example.com')
  })

  it('onChange가 string 값으로 호출된다', async () => {
    const onChange = vi.fn()
    const field = makeField({ fieldType: 'URL', key: 'url' })
    render(<CustomFieldInput field={field} value="" onChange={onChange} />)
    fireEvent.change(screen.getByTestId('custom-field-url'), { target: { value: 'https://test.io' } })
    expect(onChange).toHaveBeenCalledWith('https://test.io')
  })
})
