// ValidatorFormDialog 단위 테스트 — 고유 다이얼로그 이름/type 선택지/모르는 type degrade/baseline 병합/서버 에러
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { VALIDATOR_TYPES } from '@/mocks/validator-handlers'
import { validatorLabels } from '@/i18n/validator-labels'
import { ValidatorFormDialog } from '@/components/workflow/ValidatorFormDialog'
import type { ValidatorFormValues } from '@/components/workflow/ValidatorFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface RenderOptions {
  mode?: 'create' | 'edit'
  initialValue?: ValidatorFormValues
  submitting?: boolean
  serverError?: string
}

function renderDialog(options: RenderOptions = {}) {
  const onSubmit = vi.fn()
  const onCancel = vi.fn()
  render(
    <ValidatorFormDialog
      open
      mode={options.mode ?? 'create'}
      initialValue={options.initialValue}
      submitting={options.submitting ?? false}
      serverError={options.serverError}
      onSubmit={onSubmit}
      onCancel={onCancel}
    />,
  )
  return { onSubmit, onCancel }
}

/** 열려 있는 다이얼로그를 제목(= 접근 가능한 이름)으로 집는다. */
function dialogOf(name: string): HTMLElement {
  return screen.getByRole('dialog', { name })
}

// ─────────────────────────────────────────────────────────────────────────────
// 즉사 계약 §2 — 고유 이름 · h1 금지
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorFormDialog — 접근성 계약', () => {
  it('T6D-1: create/edit 이 서로 다른 고유한 다이얼로그 이름을 갖는다', () => {
    const { unmount } = render(
      <ValidatorFormDialog open mode="create" onSubmit={vi.fn()} onCancel={vi.fn()} />,
    )
    expect(dialogOf(validatorLabels.dialog.createTitle)).toBeInTheDocument()
    unmount()

    render(
      <ValidatorFormDialog
        open
        mode="edit"
        initialValue={{ type: VALIDATOR_TYPES.requiredField, config: { field: 'resolution' } }}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    expect(dialogOf(validatorLabels.dialog.editTitle)).toBeInTheDocument()
    expect(validatorLabels.dialog.createTitle).not.toBe(validatorLabels.dialog.editTitle)
  })

  it('T6D-2: 다이얼로그는 h1 을 만들지 않는다 (제약 C2)', () => {
    renderDialog()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// type 선택 — 편집 가능 3종만
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorFormDialog — 규칙 종류', () => {
  it('T6D-3: create 의 type 선택지에 CustomExpression 이 없다', () => {
    renderDialog()
    const select = screen.getByLabelText(validatorLabels.form.typeLabel)
    const values = within(select).getAllByRole('option').map((o) => (o as HTMLOptionElement).value)

    expect(values).not.toContain(VALIDATOR_TYPES.customExpression)
    expect(values).toEqual(
      expect.arrayContaining([
        VALIDATOR_TYPES.requiredField,
        VALIDATOR_TYPES.permissionCheck,
        VALIDATOR_TYPES.notStatusCategory,
      ]),
    )
  })

  it('T6D-4: type 을 고르면 그 type 의 config 입력 칸만 그린다', () => {
    renderDialog()
    fireEvent.change(screen.getByLabelText(validatorLabels.form.typeLabel), {
      target: { value: VALIDATOR_TYPES.permissionCheck },
    })

    expect(screen.getByLabelText(/permission/)).toBeInTheDocument()
    expect(screen.getByLabelText(/scope/)).toBeInTheDocument()
    // 다른 type 의 키는 그리지 않는다.
    expect(screen.queryByLabelText(/category/)).not.toBeInTheDocument()
    // 선택 입력에는 보조 표기가 붙는다.
    expect(screen.getByText(validatorLabels.form.optionalSuffix)).toBeInTheDocument()
  })

  it('T6D-5: edit 은 규칙 종류를 고정 표시한다 (선택으로 바꾸지 않는다)', () => {
    renderDialog({
      mode: 'edit',
      initialValue: { type: VALIDATOR_TYPES.requiredField, config: { field: 'resolution' } },
    })

    expect(screen.queryByLabelText(validatorLabels.form.typeLabel)).not.toBeInTheDocument()
    expect(screen.getByText(validatorLabels.form.typeFixedLabel)).toBeInTheDocument()
    expect(screen.getByText(VALIDATOR_TYPES.requiredField)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 엣지 E3 — 모르는 type 은 읽기 전용 degrade
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorFormDialog — 모르는 type (E3)', () => {
  it('T6D-6: 폼을 그리지 않고 저장된 값을 읽기 전용으로 보여준다', () => {
    const { onSubmit } = renderDialog({
      mode: 'edit',
      initialValue: { type: 'MysteryValidator', config: { mysteryKey: 'mystery-value' } },
    })

    expect(screen.getByText(validatorLabels.form.unknownTypeTitle)).toBeInTheDocument()
    expect(screen.getByText(validatorLabels.form.rawConfigLegend)).toBeInTheDocument()
    // 값을 추측해 입력 칸을 그리지 않는다.
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    // 원본 값은 그대로 보인다 — 낡아도 거짓말하지 않는다.
    expect(screen.getByText(/mystery-value/)).toBeInTheDocument()

    const save = screen.getByRole('button', { name: validatorLabels.dialog.saveButton })
    expect(save).toBeDisabled()
    fireEvent.click(save)
    expect(onSubmit).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 제약 C6 — baseline 병합
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorFormDialog — baseline 병합 (제약 C6)', () => {
  /**
   * ★ 폼이 모르는 키를 **버리지 않고 실어 올린다**. 형제 `PostActionConfigSection` 처럼
   * `config: { 아는 키만 }` 으로 전체 교체하면 여기서 red 가 난다.
   */
  it('T6D-7: 아는 키는 덮어쓰고 모르는 키는 보존해 전체 config 를 올린다', () => {
    const { onSubmit } = renderDialog({
      mode: 'edit',
      initialValue: {
        type: VALIDATOR_TYPES.requiredField,
        config: { field: 'resolution', legacyNote: 'keep-me', retries: 3 },
      },
    })

    fireEvent.change(screen.getByLabelText(/field/), { target: { value: 'assignee' } })
    fireEvent.click(screen.getByRole('button', { name: validatorLabels.dialog.saveButton }))

    expect(onSubmit).toHaveBeenCalledWith({
      type: VALIDATOR_TYPES.requiredField,
      config: { field: 'assignee', legacyNote: 'keep-me', retries: 3 },
    })
  })

  it('T6D-8: 선택 입력을 비우면 그 키만 빠지고 모르는 키는 남는다', () => {
    const { onSubmit } = renderDialog({
      mode: 'edit',
      initialValue: {
        type: VALIDATOR_TYPES.permissionCheck,
        config: { permission: 'TRANSITION_ISSUE', scope: 'ISSUE', legacyNote: 'keep-me' },
      },
    })

    fireEvent.change(screen.getByLabelText(/scope/), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: validatorLabels.dialog.saveButton }))

    expect(onSubmit).toHaveBeenCalledWith({
      type: VALIDATOR_TYPES.permissionCheck,
      config: { permission: 'TRANSITION_ISSUE', legacyNote: 'keep-me' },
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 검증·에러·진행 중
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorFormDialog — 검증과 에러', () => {
  it('T6D-9: 필수 값이 비면 onSubmit 을 부르지 않고 인라인 에러를 띄운다', () => {
    const { onSubmit } = renderDialog()
    fireEvent.change(screen.getByLabelText(validatorLabels.form.typeLabel), {
      target: { value: VALIDATOR_TYPES.requiredField },
    })
    fireEvent.click(screen.getByRole('button', { name: validatorLabels.dialog.createButton }))

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent(validatorLabels.form.errorConfigRequired)
    expect(screen.getByLabelText(/field/)).toHaveAttribute('aria-invalid', 'true')
  })

  it('T6D-10: 규칙 종류를 고르지 않으면 저장 버튼이 잠긴다', () => {
    const { onSubmit } = renderDialog()
    const submit = screen.getByRole('button', { name: validatorLabels.dialog.createButton })

    expect(submit).toBeDisabled()
    fireEvent.click(submit)
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('T6D-11: 서버 에러가 다이얼로그 안에 뜨고 다이얼로그는 그대로 열려 있다', () => {
    renderDialog({ serverError: '규칙 값이 이 규칙 종류의 요구와 맞지 않습니다.' })

    const dialog = dialogOf(validatorLabels.dialog.createTitle)
    expect(within(dialog).getByRole('alert')).toHaveTextContent(
      '규칙 값이 이 규칙 종류의 요구와 맞지 않습니다.',
    )
    expect(dialog).toBeInTheDocument()
  })

  it('T6D-12: 진행 중이면 저장 버튼이 잠기고 문구가 바뀐다', () => {
    renderDialog({
      mode: 'edit',
      submitting: true,
      initialValue: { type: VALIDATOR_TYPES.requiredField, config: { field: 'resolution' } },
    })

    const submit = screen.getByRole('button', { name: validatorLabels.dialog.submittingButton })
    expect(submit).toBeDisabled()
    expect(screen.getByLabelText(/field/)).toBeDisabled()
  })

  it('T6D-13: 취소하면 onCancel 을 부른다', () => {
    const { onCancel } = renderDialog()
    fireEvent.click(screen.getByRole('button', { name: validatorLabels.dialog.cancelButton }))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })
})
