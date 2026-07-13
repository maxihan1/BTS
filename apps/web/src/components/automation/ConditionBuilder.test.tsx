// 조건 트리(And/Or/Not 그룹 + Comparison leaf) 재귀 편집 컴포넌트 테스트 (FR-AT-03 D6 Task 3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createEmptyConditionTree } from '@/api/automation-rules.types'
import type { ConditionComparison, ConditionGroup } from '@/api/automation-rules.types'
import { ConditionBuilder } from './ConditionBuilder'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — ActionListEditor.test.tsx 선례 동형
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

const comparisonA: ConditionComparison = { kind: 'comparison', field: 'issue.summary', operator: 'EQUALS', value: '' }
const comparisonB: ConditionComparison = { kind: 'comparison', field: 'issue.status', operator: 'EQUALS', value: 'b' }

/** "조건 추가" 클릭 시 새로 추가되는 기본 Comparison — ConditionBuilder 내부 기본값과 동일 형태(선례: ActionListEditor DEFAULT_NEW_ACTION). */
const DEFAULT_COMPARISON: ConditionComparison = { kind: 'comparison', field: 'issue.key', operator: 'EQUALS', value: '' }

/** elements[index]를 noUncheckedIndexedAccess-safe하게 가져온다. 없으면 테스트를 즉시 실패시킨다. */
function requireElement(elements: readonly HTMLElement[], index: number): HTMLElement {
  const element = elements[index]
  if (element === undefined) {
    throw new Error(`element ${index} not found — got ${elements.length} elements`)
  }
  return element
}

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태 — 첫 Comparison/그룹 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — 빈 상태', () => {
  it('빈 그룹을 렌더하고 힌트와 조건추가/그룹추가 버튼을 보여주며 삭제 버튼은 없다(루트)', () => {
    render(<ConditionBuilder value={createEmptyConditionTree()} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    expect(screen.getByText(/조건을 추가하세요/)).toBeInTheDocument()
    expect(screen.getByTestId('condition-add-comparison')).toBeInTheDocument()
    expect(screen.getByTestId('condition-add-group')).toBeInTheDocument()
    expect(screen.queryByTestId('condition-remove-node')).not.toBeInTheDocument()
  })

  it('"조건 추가" 클릭 시 기본 Comparison 1건을 추가한 그룹으로 onChange를 호출한다', async () => {
    const onChange = vi.fn()
    render(<ConditionBuilder value={createEmptyConditionTree()} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.click(screen.getByTestId('condition-add-comparison'))

    expect(onChange).toHaveBeenCalledWith({ kind: 'group', op: 'and', negated: false, children: [DEFAULT_COMPARISON] })
  })

  it('"그룹 추가" 클릭 시 빈 중첩 그룹 1건을 추가한 그룹으로 onChange를 호출한다', async () => {
    const onChange = vi.fn()
    render(<ConditionBuilder value={createEmptyConditionTree()} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.click(screen.getByTestId('condition-add-group'))

    expect(onChange).toHaveBeenCalledWith({
      kind: 'group',
      op: 'and',
      negated: false,
      children: [{ kind: 'group', op: 'and', negated: false, children: [] }],
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// And 그룹에 Comparison 추가(기존 자식 有)
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — Comparison 추가(기존 자식 有)', () => {
  it('기존 Comparison이 있을 때 "조건 추가" 클릭 시 배열 끝에 기본 Comparison을 추가한다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [comparisonA] }
    render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.click(screen.getByTestId('condition-add-comparison'))

    expect(onChange).toHaveBeenCalledWith({ ...group, children: [comparisonA, DEFAULT_COMPARISON] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 그룹 op 토글(And↔Or)
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — 그룹 op 토글(And↔Or)', () => {
  it('op 토글 버튼 클릭 시 and→or로 전환된 그룹을 onChange로 방출한다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [comparisonA] }
    render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    expect(screen.getByTestId('condition-group-op-toggle')).toHaveTextContent('AND')
    await user.click(screen.getByTestId('condition-group-op-toggle'))

    expect(onChange).toHaveBeenCalledWith({ ...group, op: 'or' })
  })

  it('op=or 상태에서 토글 클릭 시 and로 되돌아간다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'or', negated: false, children: [comparisonA] }
    render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    expect(screen.getByTestId('condition-group-op-toggle')).toHaveTextContent('OR')
    await user.click(screen.getByTestId('condition-group-op-toggle'))

    expect(onChange).toHaveBeenCalledWith({ ...group, op: 'and' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Not(negated) 토글
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — Not(negated) 토글', () => {
  it('negate 체크박스가 그룹의 negated 상태를 반영한다', () => {
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: true, children: [comparisonA] }
    render(<ConditionBuilder value={group} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    expect(screen.getByTestId('condition-group-negate')).toBeChecked()
  })

  it('negate 체크박스 클릭 시 negated를 토글한 그룹을 onChange로 방출한다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [comparisonA] }
    render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.click(screen.getByTestId('condition-group-negate'))

    expect(onChange).toHaveBeenCalledWith({ ...group, negated: true })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 중첩 그룹 — 추가/삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — 중첩 그룹', () => {
  it('그룹 안에 중첩 그룹을 추가하면 자식 트리에 빈 그룹이 나타난다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [] }
    const { rerender } = render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.click(screen.getByTestId('condition-add-group'))
    const nextValue = onChange.mock.calls[0]?.[0] as ConditionGroup
    rerender(<ConditionBuilder value={nextValue} onChange={onChange} projectKey={PROJECT_KEY} />)

    expect(screen.getAllByTestId('condition-group')).toHaveLength(2)
  })

  it('중첩 그룹의 삭제 버튼 클릭 시 해당 중첩 그룹만 부모에서 제거된다', async () => {
    const onChange = vi.fn()
    const nested = createEmptyConditionTree()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [comparisonA, nested] }
    render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    const removeButtons = screen.getAllByTestId('condition-remove-node')
    expect(removeButtons).toHaveLength(2)
    await user.click(requireElement(removeButtons, 1))

    expect(onChange).toHaveBeenCalledWith({ ...group, children: [comparisonA] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Comparison 노드 삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — Comparison 삭제', () => {
  it('Comparison 삭제 버튼 클릭 시 해당 Comparison만 제거된 그룹을 onChange로 방출한다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [comparisonA, comparisonB] }
    render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    const removeButtons = screen.getAllByTestId('condition-remove-node')
    expect(removeButtons).toHaveLength(2)
    await user.click(requireElement(removeButtons, 0))

    expect(onChange).toHaveBeenCalledWith({ ...group, children: [comparisonB] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Comparison leaf 편집 위임 — 인덱스 기반 격리
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — Comparison leaf 편집 위임', () => {
  it('Comparison 값 입력 시 해당 인덱스만 갱신한 그룹으로 onChange를 호출하고 다른 자식에 영향을 주지 않는다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [comparisonA, comparisonB] }
    render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    const valueInputs = screen.getAllByTestId('condition-value-input')
    await user.type(requireElement(valueInputs, 0), 'x')

    expect(onChange).toHaveBeenLastCalledWith({
      ...group,
      children: [{ ...comparisonA, value: 'x' }, comparisonB],
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 재마운트 안전성(안정 key) — 자식 제거 후에도 남은 자식 상태 보존
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — 재마운트 안전성(안정 key)', () => {
  it('첫 Comparison 제거 후 재렌더링해도 남은 Comparison의 필드 값이 유지된다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [comparisonA, comparisonB] }
    const { rerender } = render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.click(requireElement(screen.getAllByTestId('condition-remove-node'), 0))
    const nextValue = onChange.mock.calls[0]?.[0] as ConditionGroup
    rerender(<ConditionBuilder value={nextValue} onChange={onChange} projectKey={PROJECT_KEY} />)

    const fieldSelect = screen.getByTestId('condition-field-select') as HTMLSelectElement
    expect(fieldSelect.value).toBe('issue.status')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Comparison 추가 시 객체 참조 격리(회귀) — qa E2E 발견 버그
//
// addComparisonChild가 모듈 전역 단일 상수(DEFAULT_COMPARISON)를 그대로 push하면, 같은 그룹에
// "조건 추가"를 두 번 눌러 생긴 두 Comparison 노드가 동일 객체 참조를 공유한다. ConditionBuilder의
// idFor(WeakMap 키가 노드 참조)가 이 경우 두 자식에게 같은 React key를 부여해 편집 시 유령 노드가
// 생기는 원인이 된다(재현: 2개 추가 후 1개 편집 시 노드 3개로 증가). createEmptyConditionTree가
// 호출마다 새 객체를 반환하는 선례와 대칭이어야 한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — Comparison 추가 시 객체 참조 격리(회귀)', () => {
  it('같은 그룹에 "조건 추가"를 연속 두 번 클릭하면 두 Comparison 노드가 서로 다른 객체 참조를 가진다', async () => {
    const onChange = vi.fn()
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [] }
    const { rerender } = render(<ConditionBuilder value={group} onChange={onChange} projectKey={PROJECT_KEY} />)
    const user = userEvent.setup()

    await user.click(screen.getByTestId('condition-add-comparison'))
    const afterFirst = onChange.mock.calls[0]?.[0] as ConditionGroup
    rerender(<ConditionBuilder value={afterFirst} onChange={onChange} projectKey={PROJECT_KEY} />)

    await user.click(screen.getByTestId('condition-add-comparison'))
    const afterSecond = onChange.mock.calls[1]?.[0] as ConditionGroup

    const [first, second] = afterSecond.children
    if (first === undefined || second === undefined) {
      throw new Error(`expected 2 children, got ${afterSecond.children.length}`)
    }
    expect(afterSecond.children).toHaveLength(2)
    expect(first).not.toBe(second)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 행별 고유 id(C1 코드리뷰 회귀) — 같은 그룹에 Comparison 2개 이상이면 DOM id가 충돌하면 안 된다
//
// 정적 id/htmlFor(condition-field 등)를 쓰면 한 그룹에 Comparison이 2개 이상일 때 DOM id가
// 중복되고, 라벨 클릭 시 문서상 첫 행으로 잘못 포커스된다. ActionConfigEditor의 idPrefix 관례를
// ConditionComparisonRow에도 적용해 행별로 고유한 id를 부여해야 한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — 행별 고유 id(C1 회귀)', () => {
  it('같은 그룹에 Comparison이 2개 이상이면 두 필드 select의 DOM id가 서로 다르다', () => {
    const group: ConditionGroup = { kind: 'group', op: 'and', negated: false, children: [comparisonA, comparisonB] }
    render(<ConditionBuilder value={group} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    const fieldSelects = screen.getAllByTestId('condition-field-select') as HTMLSelectElement[]
    expect(fieldSelects).toHaveLength(2)
    const [first, second] = fieldSelects
    if (first === undefined || second === undefined) {
      throw new Error(`expected 2 field selects, got ${fieldSelects.length}`)
    }
    expect(first.id).not.toBe('')
    expect(second.id).not.toBe('')
    expect(first.id).not.toBe(second.id)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 루트가 단일 Comparison인 경우(그룹 아닌 트리 루트)
// ─────────────────────────────────────────────────────────────────────────────

describe('ConditionBuilder — 루트가 단일 Comparison인 경우', () => {
  it('루트 노드가 Comparison이면 그룹 컨트롤/삭제 버튼 없이 ConditionComparisonRow만 렌더한다', () => {
    render(<ConditionBuilder value={comparisonA} onChange={vi.fn()} projectKey={PROJECT_KEY} />)

    expect(screen.getByTestId('condition-field-select')).toBeInTheDocument()
    expect(screen.queryByTestId('condition-group')).not.toBeInTheDocument()
    expect(screen.queryByTestId('condition-remove-node')).not.toBeInTheDocument()
  })
})
