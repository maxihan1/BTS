// Comparison 조건 1행 편집 컴포넌트 테스트 — 필드/연산자 select·arity/field별 값 위젯 분기 (FR-AT-03 D6 Task 2)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { JSX, ReactNode } from 'react'
import { server } from '@/test/server'
import { projectMemberHandlers } from '@/mocks/project-member-handlers'
import { FIELD_WHITELIST, COMPARISON_OPERATOR_META } from '@/api/automation-rules.types'
import type { ConditionComparison } from '@/api/automation-rules.types'
import { ConditionComparisonRow } from './ConditionComparisonRow'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — ActionConfigEditor.test.tsx / ProjectMemberSelect.test.tsx 선례 동형
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const ALICE_ID = '00000000-0000-4000-8000-000000000001'

function renderWithClient(ui: JSX.Element) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(ui, {
    wrapper: ({ children }: { readonly children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  })
}

beforeEach(() => {
  server.use(...projectMemberHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 필드/연산자 select 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionComparisonRow — 필드/연산자 select', () => {
  it('필드 select에 FIELD_WHITELIST 9종을 렌더한다', () => {
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: '' }
    render(<ConditionComparisonRow value={value} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    const fieldSelect = screen.getByTestId('condition-field-select') as HTMLSelectElement
    const optionValues = Array.from(fieldSelect.options).map((option) => option.value)
    expect(optionValues).toEqual(FIELD_WHITELIST)
  })

  it('연산자 select에 COMPARISON_OPERATOR_META 9종을 렌더한다', () => {
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: '' }
    render(<ConditionComparisonRow value={value} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    const operatorSelect = screen.getByTestId('condition-operator-select') as HTMLSelectElement
    const optionValues = Array.from(operatorSelect.options).map((option) => option.value)
    expect(optionValues).toEqual(Object.keys(COMPARISON_OPERATOR_META))
    Object.values(COMPARISON_OPERATOR_META).forEach((meta) => {
      expect(within(operatorSelect).getByRole('option', { name: meta.label })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// arity별 값 위젯 표시/은닉
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionComparisonRow — arity별 값 위젯 분기', () => {
  it('단항 연산자(EMPTY)는 값 위젯을 렌더하지 않는다', () => {
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EMPTY' }
    render(<ConditionComparisonRow value={value} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    expect(screen.queryByTestId('condition-value-input')).not.toBeInTheDocument()
  })

  it('단항 연산자(EXISTS)는 값 위젯을 렌더하지 않는다', () => {
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.assignee', operator: 'EXISTS' }
    render(<ConditionComparisonRow value={value} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    expect(screen.queryByTestId('condition-value-input')).not.toBeInTheDocument()
  })

  it('이항 연산자(EQUALS)는 값 위젯을 렌더한다', () => {
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: '' }
    render(<ConditionComparisonRow value={value} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    expect(screen.getByTestId('condition-value-input')).toBeInTheDocument()
  })

  it('IN 연산자는 값 위젯을 렌더한다', () => {
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.status', operator: 'IN', value: '' }
    render(<ConditionComparisonRow value={value} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    expect(screen.getByTestId('condition-value-input')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// [D2] 필드별 값 위젯 분기 — priority=숫자 select / assignee·reporter=ProjectMemberSelect / 나머지=텍스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionComparisonRow — [D2] 필드별 값 위젯 분기', () => {
  it('field=issue.priority는 1~5 숫자 select를 렌더하고 선택 시 숫자 값을 방출한다', async () => {
    const onChange = vi.fn()
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.priority', operator: 'EQUALS', value: 3 }
    render(<ConditionComparisonRow value={value} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    const widget = screen.getByTestId('condition-value-input') as HTMLSelectElement
    expect(widget.tagName).toBe('SELECT')
    expect(widget.value).toBe('3')
    ;[1, 2, 3, 4, 5].forEach((n) => {
      expect(within(widget).getByRole('option', { name: String(n) })).toBeInTheDocument()
    })

    await user.selectOptions(widget, '5')

    expect(onChange).toHaveBeenCalledWith({ kind: 'comparison', field: 'issue.priority', operator: 'EQUALS', value: 5 })
  })

  it('field=issue.assignee는 ProjectMemberSelect를 렌더하고 멤버 선택 시 uuid 값을 방출한다', async () => {
    const onChange = vi.fn()
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.assignee', operator: 'EQUALS', value: null }
    renderWithClient(<ConditionComparisonRow value={value} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    const aliceOption = await screen.findByRole('option', { name: '앨리스' })
    const widgetContainer = screen.getByTestId('condition-value-input')
    const memberSelect = within(widgetContainer).getByLabelText('값')
    await user.selectOptions(memberSelect, aliceOption)

    expect(onChange).toHaveBeenCalledWith({
      kind: 'comparison',
      field: 'issue.assignee',
      operator: 'EQUALS',
      value: ALICE_ID,
    })
  })

  it('field=issue.reporter는 ProjectMemberSelect를 렌더한다', async () => {
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.reporter', operator: 'EQUALS', value: null }
    renderWithClient(<ConditionComparisonRow value={value} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    await screen.findByRole('option', { name: '앨리스' })
    const widgetContainer = screen.getByTestId('condition-value-input')
    expect(within(widgetContainer).getByLabelText('값')).toBeInTheDocument()
  })

  it('field=issue.summary(나머지)는 텍스트 input을 렌더하고 입력 시 문자열 값을 방출한다', async () => {
    const onChange = vi.fn()
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: '' }
    render(<ConditionComparisonRow value={value} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    const widget = screen.getByTestId('condition-value-input') as HTMLInputElement
    expect(widget.tagName).toBe('INPUT')

    await user.type(widget, 'x')

    expect(onChange).toHaveBeenCalledWith({ kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: 'x' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 필드 변경 시 값 위젯 전환
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionComparisonRow — 필드 변경 시 값 위젯 전환', () => {
  it('필드를 issue.summary → issue.priority로 바꾸면 onChange가 숫자 기본값(1)과 함께 호출된다', async () => {
    const onChange = vi.fn()
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: 'x' }
    render(<ConditionComparisonRow value={value} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByTestId('condition-field-select'), 'issue.priority')

    expect(onChange).toHaveBeenCalledWith({ kind: 'comparison', field: 'issue.priority', operator: 'EQUALS', value: 1 })
  })

  it('필드를 issue.summary → issue.assignee로 바꾸면 onChange value가 null(멤버 미선택)로 초기화된다', async () => {
    const onChange = vi.fn()
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: 'x' }
    render(<ConditionComparisonRow value={value} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByTestId('condition-field-select'), 'issue.assignee')

    expect(onChange).toHaveBeenCalledWith({ kind: 'comparison', field: 'issue.assignee', operator: 'EQUALS', value: null })
  })

  it('필드를 issue.priority → issue.summary로 바꾸면 onChange value가 텍스트 기본값("")으로 초기화된다', async () => {
    const onChange = vi.fn()
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.priority', operator: 'EQUALS', value: 3 }
    render(<ConditionComparisonRow value={value} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByTestId('condition-field-select'), 'issue.summary')

    expect(onChange).toHaveBeenCalledWith({ kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: '' })
  })

  it('재렌더 시 필드가 issue.summary(텍스트)에서 issue.priority(숫자 select)로 위젯이 전환된다', () => {
    const textValue: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: '' }
    const { rerender } = render(<ConditionComparisonRow value={textValue} onChange={vi.fn()} projectKey={PROJECT_KEY} />)
    expect((screen.getByTestId('condition-value-input') as HTMLInputElement).tagName).toBe('INPUT')

    const priorityValue: ConditionComparison = { kind: 'comparison', field: 'issue.priority', operator: 'EQUALS', value: 1 }
    rerender(<ConditionComparisonRow value={priorityValue} onChange={vi.fn()} projectKey={PROJECT_KEY} />)
    expect((screen.getByTestId('condition-value-input') as HTMLSelectElement).tagName).toBe('SELECT')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 연산자를 단항으로 전환 — 값 소거 + 위젯 소거
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionComparisonRow — 연산자를 단항으로 전환', () => {
  it('연산자를 EQUALS → 비어있음(EMPTY)로 바꾸면 onChange에서 value 키가 제거된다', async () => {
    const onChange = vi.fn()
    const value: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: 'x' }
    render(<ConditionComparisonRow value={value} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByTestId('condition-operator-select'), COMPARISON_OPERATOR_META.EMPTY.label)

    const lastCall = onChange.mock.calls.at(-1)?.[0] as ConditionComparison
    expect(lastCall).toEqual({ kind: 'comparison', field: 'issue.summary', operator: 'EMPTY' })
    expect(Object.hasOwn(lastCall, 'value')).toBe(false)
  })

  it('연산자가 단항(EMPTY)으로 재렌더되면 값 위젯이 사라지고, 다시 이항으로 재렌더되면 되돌아온다', () => {
    const unaryValue: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EMPTY' }
    const { rerender } = render(<ConditionComparisonRow value={unaryValue} onChange={vi.fn()} projectKey={PROJECT_KEY} />)
    expect(screen.queryByTestId('condition-value-input')).not.toBeInTheDocument()

    const binaryValue: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: '' }
    rerender(<ConditionComparisonRow value={binaryValue} onChange={vi.fn()} projectKey={PROJECT_KEY} />)
    expect(screen.getByTestId('condition-value-input')).toBeInTheDocument()
  })
})
