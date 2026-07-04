// Import 매핑 마법사 — 값(상태/유형/우선순위) 매핑 단계 컴포넌트 단위 테스트 (FR-IM-02 D6 Task-5)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ValueCollectionResponse } from '@/api/import-mappings'
import { ValueMappingStep } from './ValueMappingStep'
import type { ValueMappingStepProps } from './ValueMappingStep'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const FIELDS_FIXTURE: ValueCollectionResponse['fields'] = [
  {
    targetField: 'STATUS',
    values: [
      { sourceValue: 'Open', suggestedTargetValue: 'OPEN' },
      { sourceValue: 'Closed' },
    ],
  },
  {
    targetField: 'TYPE',
    values: [{ sourceValue: 'Bug', suggestedTargetValue: 'BUG' }],
  },
  {
    targetField: 'PRIORITY',
    values: [{ sourceValue: 'High', suggestedTargetValue: 'HIGH' }],
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderStep(overrides: Partial<ValueMappingStepProps> = {}) {
  const onChange = vi.fn()
  const onNext = vi.fn()
  const onBack = vi.fn()
  const utils = render(
    <ValueMappingStep
      fields={FIELDS_FIXTURE}
      value={{}}
      onChange={onChange}
      onNext={onNext}
      onBack={onBack}
      {...overrides}
    />,
  )
  return { ...utils, onChange, onNext, onBack }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ValueMappingStep', () => {
  it('대상 필드별 한글 그룹과 소스 값 행을 렌더한다', () => {
    renderStep()

    expect(screen.getByText('상태')).toBeInTheDocument()
    expect(screen.getByText('유형')).toBeInTheDocument()
    expect(screen.getByText('우선순위')).toBeInTheDocument()
    expect(screen.getByText('Open')).toBeInTheDocument()
    expect(screen.getByText('Closed')).toBeInTheDocument()
    expect(screen.getByText('Bug')).toBeInTheDocument()
    expect(screen.getByText('High')).toBeInTheDocument()
  })

  it('추천값을 대상 값 입력에 프리필한다', () => {
    renderStep()

    const openInput = screen.getByLabelText('상태 Open 대상 값') as HTMLInputElement
    expect(openInput.value).toBe('OPEN')

    const closedInput = screen.getByLabelText('상태 Closed 대상 값') as HTMLInputElement
    expect(closedInput.value).toBe('')
  })

  it('대상 값 입력 변경 시 targetField::sourceValue 키로 onChange를 호출한다', () => {
    const { onChange } = renderStep()

    const bugInput = screen.getByLabelText('유형 Bug 대상 값')
    fireEvent.change(bugInput, { target: { value: 'DEFECT' } })

    expect(onChange).toHaveBeenCalledWith({ 'TYPE::Bug': 'DEFECT' })
  })

  it('기존 value 오버라이드를 보존하며 병합한다', () => {
    const onChange = vi.fn()
    render(
      <ValueMappingStep
        fields={FIELDS_FIXTURE}
        value={{ 'STATUS::Open': 'OPEN_CUSTOM' }}
        onChange={onChange}
        onNext={vi.fn()}
      />,
    )

    const bugInput = screen.getByLabelText('유형 Bug 대상 값')
    fireEvent.change(bugInput, { target: { value: 'DEFECT' } })

    expect(onChange).toHaveBeenCalledWith({
      'STATUS::Open': 'OPEN_CUSTOM',
      'TYPE::Bug': 'DEFECT',
    })
  })

  it('values 오버라이드가 있으면 프리필 대신 오버라이드 값을 표시한다', () => {
    renderStep({ value: { 'STATUS::Open': 'OPEN_CUSTOM' } })

    const openInput = screen.getByLabelText('상태 Open 대상 값') as HTMLInputElement
    expect(openInput.value).toBe('OPEN_CUSTOM')
  })

  it('STATUS는 자유 입력 안내를, TYPE/PRIORITY는 canonical 힌트를 표시한다', () => {
    renderStep()

    expect(screen.getByText(/자유롭게 입력/)).toBeInTheDocument()
    expect(screen.getAllByText(/BTS.*값과.*일치/)).toHaveLength(2)
  })

  it('fields가 빈 배열이면 안내 메시지를 표시한다', () => {
    renderStep({ fields: [] })

    expect(screen.getByText('매핑할 값이 없습니다.')).toBeInTheDocument()
  })

  it('[다음] 클릭 시 onNext가 호출된다', async () => {
    const user = userEvent.setup()
    const { onNext } = renderStep()

    await user.click(screen.getByRole('button', { name: '다음' }))
    expect(onNext).toHaveBeenCalledTimes(1)
  })

  it('onBack이 전달되면 [이전] 버튼이 렌더되고 클릭 시 호출된다', async () => {
    const user = userEvent.setup()
    const { onBack } = renderStep()

    await user.click(screen.getByRole('button', { name: '이전' }))
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('onBack이 없으면 [이전] 버튼을 렌더하지 않는다', () => {
    renderStep({ onBack: undefined })

    expect(screen.queryByRole('button', { name: '이전' })).not.toBeInTheDocument()
  })
})
