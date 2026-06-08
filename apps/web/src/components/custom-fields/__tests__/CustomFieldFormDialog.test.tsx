// 커스텀 필드 생성/수정 Dialog 컴포넌트 테스트 — RHF + Zod + 옵션 편집기 검증
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { CreateCustomFieldInput, UpdateCustomFieldInput, CustomField } from '@/api/custom-fields.types'
import { CustomFieldFormDialog } from '../CustomFieldFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const FIXTURE_FIELD: CustomField = {
  id: '11111111-1111-4111-a111-111111111111',
  projectId: '22222222-2222-4222-a222-222222222222',
  key: 'priority',
  name: '우선순위',
  description: '업무 우선순위 필드',
  fieldType: 'SINGLE_SELECT',
  required: false,
  displayOrder: 1,
  options: [
    { value: 'low', label: '낮음', displayOrder: 0 },
    { value: 'high', label: '높음', displayOrder: 1 },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderCreateDialog(onSubmit = vi.fn(), submitError?: string | null) {
  const onOpenChange = vi.fn()
  render(
    <CustomFieldFormDialog
      open={true}
      mode="create"
      onSubmit={onSubmit}
      onOpenChange={onOpenChange}
      submitError={submitError}
    />,
  )
  return { onSubmit, onOpenChange }
}

function renderEditDialog(
  initial: CustomField = FIXTURE_FIELD,
  onSubmit = vi.fn(),
  submitError?: string | null,
) {
  const onOpenChange = vi.fn()
  render(
    <CustomFieldFormDialog
      open={true}
      mode="edit"
      initial={initial}
      onSubmit={onSubmit}
      onOpenChange={onOpenChange}
      submitError={submitError}
    />,
  )
  return { onSubmit, onOpenChange }
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — create 모드 기본 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S1 create 모드 렌더', () => {
  it('create 모드에서 "커스텀 필드 추가" 제목이 표시된다', () => {
    renderCreateDialog()
    expect(screen.getByText('커스텀 필드 추가')).toBeInTheDocument()
  })

  it('create 모드에서 키 입력 필드가 활성화 상태이다', () => {
    renderCreateDialog()
    const keyInput = screen.getByLabelText('키')
    expect(keyInput).not.toBeDisabled()
  })

  it('create 모드에서 필드 타입 select가 활성화 상태이다', () => {
    renderCreateDialog()
    // 필드 타입 콤보박스 활성화 여부 확인
    const select = screen.getByRole('combobox', { name: '필드 타입' })
    expect(select).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — edit 모드 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S2 edit 모드 렌더', () => {
  it('edit 모드에서 "커스텀 필드 수정" 제목이 표시된다', () => {
    renderEditDialog()
    expect(screen.getByText('커스텀 필드 수정')).toBeInTheDocument()
  })

  it('edit 모드에서 키 입력 필드가 initial 값으로 채워지고 비활성화된다', () => {
    renderEditDialog()
    const keyInput = screen.getByLabelText('키') as HTMLInputElement
    expect(keyInput.value).toBe('priority')
    expect(keyInput).toBeDisabled()
  })

  it('edit 모드에서 이름 필드가 initial 값으로 채워진다', () => {
    renderEditDialog()
    const nameInput = screen.getByLabelText('이름') as HTMLInputElement
    expect(nameInput.value).toBe('우선순위')
  })

  it('edit 모드에서 필드 타입이 비활성화 상태이다', () => {
    renderEditDialog()
    const select = screen.getByRole('combobox', { name: '필드 타입' })
    expect(select).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 선택형 옵션 편집기
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S3 선택형 옵션 편집기', () => {
  it('SINGLE_SELECT 필드 edit 시 옵션 편집기가 표시된다', () => {
    renderEditDialog()
    expect(screen.getByText('선택지')).toBeInTheDocument()
  })

  it('edit 모드에서 initial 옵션 2건이 렌더된다', () => {
    renderEditDialog()
    const valueInputs = screen.getAllByPlaceholderText('값')
    expect(valueInputs).toHaveLength(2)
  })

  it('"선택지 추가" 버튼 클릭 시 옵션 행이 추가된다', async () => {
    const user = userEvent.setup()
    renderEditDialog()

    const addBtn = screen.getByRole('button', { name: '선택지 추가' })
    await user.click(addBtn)

    const valueInputs = screen.getAllByPlaceholderText('값')
    expect(valueInputs).toHaveLength(3)
  })

  it('옵션 행 삭제 버튼 클릭 시 해당 행이 제거된다', async () => {
    const user = userEvent.setup()
    renderEditDialog()

    // 2건 중 첫 번째 삭제
    const deleteButtons = screen.getAllByRole('button', { name: '옵션 삭제' })
    await user.click(deleteButtons[0]!)

    const valueInputs = screen.getAllByPlaceholderText('값')
    expect(valueInputs).toHaveLength(1)
  })

  it('비선택형(SHORT_TEXT) 필드에서는 옵션 편집기가 표시되지 않는다', () => {
    const textField: CustomField = { ...FIXTURE_FIELD, fieldType: 'SHORT_TEXT', options: [] }
    renderEditDialog(textField)
    expect(screen.queryByText('선택지')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — create 모드에서 선택형 타입 선택 시 옵션 편집기 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S4 create 모드 선택형 타입 변경', () => {
  it('create 모드에서 SINGLE_SELECT 선택 시 옵션 편집기가 나타난다', async () => {
    const user = userEvent.setup()
    renderCreateDialog()

    // 초기 상태: 옵션 편집기 미표시
    expect(screen.queryByText('선택지')).not.toBeInTheDocument()

    // 필드 타입을 SINGLE_SELECT로 변경
    const select = screen.getByRole('combobox', { name: '필드 타입' })
    await user.selectOptions(select, 'SINGLE_SELECT')

    expect(screen.getByText('선택지')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 저장 disabled 조건 (선택형 + 옵션 0건)
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S5 선택형 옵션 0건 시 저장 disabled', () => {
  it('선택형인데 옵션이 0건이면 저장 버튼이 disabled이다', async () => {
    const user = userEvent.setup()
    renderEditDialog()

    // 옵션 2건 모두 삭제
    const deleteButtons = screen.getAllByRole('button', { name: '옵션 삭제' })
    await user.click(deleteButtons[0]!)
    // 삭제 후 1건 남은 상태에서 다시 삭제
    const remainingDelete = screen.getByRole('button', { name: '옵션 삭제' })
    await user.click(remainingDelete)

    const saveBtn = screen.getByRole('button', { name: '저장' })
    expect(saveBtn).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — create 모드 폼 제출
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S6 create 모드 제출', () => {
  it('유효한 값 입력 후 저장 시 onSubmit이 CreateCustomFieldInput 형태로 호출된다', { timeout: 10_000 }, async () => {
    const onSubmit = vi.fn()
    const user = userEvent.setup({ delay: null })
    renderCreateDialog(onSubmit)

    await user.type(screen.getByLabelText('키'), 'my_field')
    await user.type(screen.getByLabelText('이름'), '내 필드')

    const select = screen.getByRole('combobox', { name: '필드 타입' })
    await user.selectOptions(select, 'NUMBER')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledOnce()
    })

    const arg = onSubmit.mock.calls[0]?.[0] as CreateCustomFieldInput
    expect(arg.key).toBe('my_field')
    expect(arg.name).toBe('내 필드')
    expect(arg.fieldType).toBe('NUMBER')
  })

  it('create 모드 제출 시 options는 포함되지 않는다 (비선택형)', { timeout: 10_000 }, async () => {
    const onSubmit = vi.fn()
    const user = userEvent.setup({ delay: null })
    renderCreateDialog(onSubmit)

    await user.type(screen.getByLabelText('키'), 'txt_field')
    await user.type(screen.getByLabelText('이름'), '텍스트 필드')

    const select = screen.getByRole('combobox', { name: '필드 타입' })
    await user.selectOptions(select, 'SHORT_TEXT')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledOnce()
    })

    const arg = onSubmit.mock.calls[0]?.[0] as CreateCustomFieldInput
    expect(arg.options).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7 — edit 모드 폼 제출
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S7 edit 모드 제출', () => {
  it('edit 모드 제출 시 fieldType·key가 포함되지 않는다', { timeout: 10_000 }, async () => {
    const onSubmit = vi.fn()
    const user = userEvent.setup({ delay: null })
    renderEditDialog(FIXTURE_FIELD, onSubmit)

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledOnce()
    })

    const arg = onSubmit.mock.calls[0]?.[0] as UpdateCustomFieldInput
    expect((arg as Record<string, unknown>)['fieldType']).toBeUndefined()
    expect((arg as Record<string, unknown>)['key']).toBeUndefined()
  })

  it('edit 모드 제출 시 수정된 이름이 onSubmit에 전달된다', { timeout: 10_000 }, async () => {
    const onSubmit = vi.fn()
    const user = userEvent.setup({ delay: null })
    renderEditDialog(FIXTURE_FIELD, onSubmit)

    const nameInput = screen.getByLabelText('이름')
    await user.clear(nameInput)
    await user.type(nameInput, '변경된 이름')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledOnce()
    })

    const arg = onSubmit.mock.calls[0]?.[0] as UpdateCustomFieldInput
    expect(arg.name).toBe('변경된 이름')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8 — 유효성 검사 오류
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S8 유효성 검사', () => {
  it('키가 빈 값이면 저장 시 오류 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderCreateDialog()

    await user.type(screen.getByLabelText('이름'), '이름만 입력')
    const select = screen.getByRole('combobox', { name: '필드 타입' })
    await user.selectOptions(select, 'SHORT_TEXT')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  it('키가 URL-safe 소문자 정규식을 위반하면 오류 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderCreateDialog()

    await user.type(screen.getByLabelText('키'), 'UPPERCASE_KEY')
    await user.type(screen.getByLabelText('이름'), '이름')
    const select = screen.getByRole('combobox', { name: '필드 타입' })
    await user.selectOptions(select, 'SHORT_TEXT')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S9 — submitError 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S9 submitError 표시', () => {
  it('submitError가 있으면 다이얼로그 안에 오류 메시지가 표시된다', () => {
    renderCreateDialog(vi.fn(), '이미 같은 키의 필드가 있습니다.')
    expect(screen.getByText('이미 같은 키의 필드가 있습니다.')).toBeInTheDocument()
  })

  it('submitError가 null이면 오류 영역이 렌더되지 않는다', () => {
    renderCreateDialog(vi.fn(), null)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S10 — 취소 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S10 취소 버튼', () => {
  it('취소 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderCreateDialog()

    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S11 — key prop 재마운트 (edit initial 교체)
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S11 key prop 재마운트', () => {
  it('initial.id가 바뀌면 이름 필드가 새 initial 값으로 초기화된다', () => {
    const { rerender } = render(
      <CustomFieldFormDialog
        open={true}
        mode="edit"
        initial={FIXTURE_FIELD}
        onSubmit={vi.fn()}
        onOpenChange={vi.fn()}
      />,
    )

    const anotherField: CustomField = {
      ...FIXTURE_FIELD,
      id: '33333333-3333-4333-a333-333333333333',
      name: '다른 필드',
      key: 'other_key',
    }

    rerender(
      <CustomFieldFormDialog
        open={true}
        mode="edit"
        initial={anotherField}
        onSubmit={vi.fn()}
        onOpenChange={vi.fn()}
      />,
    )

    const nameInput = screen.getByLabelText('이름') as HTMLInputElement
    expect(nameInput.value).toBe('다른 필드')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S12 — MULTI_SELECT / RADIO도 옵션 편집기 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('CustomFieldFormDialog — S12 MULTI_SELECT / RADIO 옵션 편집기', () => {
  it('MULTI_SELECT 필드 edit 시 옵션 편집기가 표시된다', () => {
    const field: CustomField = { ...FIXTURE_FIELD, fieldType: 'MULTI_SELECT' }
    renderEditDialog(field)
    expect(screen.getByText('선택지')).toBeInTheDocument()
  })

  it('RADIO 필드 edit 시 옵션 편집기가 표시된다', () => {
    const field: CustomField = { ...FIXTURE_FIELD, fieldType: 'RADIO' }
    renderEditDialog(field)
    expect(screen.getByText('선택지')).toBeInTheDocument()
  })

  it('NUMBER 필드 edit 시 옵션 편집기가 표시되지 않는다', () => {
    const field: CustomField = { ...FIXTURE_FIELD, fieldType: 'NUMBER', options: [] }
    renderEditDialog(field)
    expect(screen.queryByText('선택지')).not.toBeInTheDocument()
  })
})
