// IssueCustomFieldsEdit 단위 테스트 — IssueMetaPanel 분해 A (FR-UX-06 PR19 Task 2)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IssueCustomFieldsEdit } from '@/components/issue/meta/IssueCustomFieldsEdit'
import { issueDetailStrings } from '@/i18n/ko'
import type { CustomField } from '@/api/custom-fields.types'

/** 텍스트형 커스텀 필드 정의 픽스처 */
const textFieldFixture: CustomField = {
  id: 'fd000001-0000-4000-8000-000000000001',
  projectId: 'pd000001-0000-4000-8000-000000000001',
  key: 'affected_version',
  name: '영향 버전',
  description: null,
  fieldType: 'SHORT_TEXT',
  required: false,
  displayOrder: 0,
  options: [],
}

/** required 텍스트형 커스텀 필드 정의 픽스처 */
const requiredFieldFixture: CustomField = {
  id: 'fd000001-0000-4000-8000-000000000010',
  projectId: 'pd000001-0000-4000-8000-000000000001',
  key: 'req_field',
  name: '필수 항목',
  description: null,
  fieldType: 'SHORT_TEXT',
  required: true,
  displayOrder: 0,
  options: [],
}

describe('IssueCustomFieldsEdit', () => {
  it('fieldDefs 기준으로 CustomFieldInput이 렌더된다', () => {
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[textFieldFixture]}
        values={{}}
        onSave={vi.fn()}
        canEdit={true}
      />,
    )
    expect(screen.getByTestId('custom-fields-section')).toBeInTheDocument()
    expect(screen.getByTestId('custom-field-affected_version')).toBeInTheDocument()
  })

  it('값 편집 후 저장 시 onCustomFieldsSave가 편집된 값 맵으로 호출된다', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[textFieldFixture]}
        values={{}}
        onSave={onSave}
        canEdit={true}
      />,
    )
    const input = screen.getByTestId('custom-field-affected_version')
    await user.type(input, 'v3.0.0')
    const saveBtn = screen.getByTestId('custom-fields-save')
    await user.click(saveBtn)
    expect(onSave).toHaveBeenCalledWith({ affected_version: 'v3.0.0' })
  })

  it('restrictedFields에 포함된 커스텀 필드는 렌더되지 않는다 (FR-PM-07)', () => {
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[textFieldFixture]}
        values={{}}
        onSave={vi.fn()}
        canEdit={true}
        restrictedFields={['affected_version']}
      />,
    )
    expect(screen.queryByTestId('custom-field-affected_version')).not.toBeInTheDocument()
  })

  it('noneditableFields에 포함된 커스텀 필드는 disabled이다 (FR-PM-07)', () => {
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[textFieldFixture]}
        values={{}}
        onSave={vi.fn()}
        canEdit={true}
        noneditableFields={['affected_version']}
      />,
    )
    expect(screen.getByTestId('custom-field-affected_version')).toBeDisabled()
  })

  it('required 필드를 비운 채 저장 시도 시 경고가 표시되고 onCustomFieldsSave가 호출되지 않는다 (스펙 E-3)', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[requiredFieldFixture]}
        values={{}}
        onSave={onSave}
        canEdit={true}
      />,
    )
    const saveBtn = screen.getByTestId('custom-fields-save')
    await user.click(saveBtn)
    expect(screen.getByTestId('custom-fields-required-error')).toBeInTheDocument()
    expect(onSave).not.toHaveBeenCalled()
  })

  it('canEdit=false이면 저장 버튼이 disabled이다', () => {
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[textFieldFixture]}
        values={{}}
        onSave={vi.fn()}
        canEdit={false}
      />,
    )
    expect(screen.getByTestId('custom-fields-save')).toBeDisabled()
  })

  it('저장 버튼에 aria-label이 있다 (WCAG AA)', () => {
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[textFieldFixture]}
        values={{}}
        onSave={vi.fn()}
        canEdit={true}
      />,
    )
    const saveBtn = screen.getByTestId('custom-fields-save')
    expect(saveBtn).toHaveAttribute('aria-label', issueDetailStrings.customFieldsSaveAriaLabel)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// required 판정이 생성 폼과 갈라져 있다
//
// `isRequiredFieldEmpty` 는 생성 폼(`IssueCreateForm`)과 이 화면에 **글자 단위로 같은 사본**
// 2개로 존재했다. 2026-08-09 에 생성 폼만 고치면서 두 화면의 계약이 갈라졌다.
//
// | 입력 | 생성 폼 | 편집 화면(사본) |
// |---|---|---|
// | required MULTI_SELECT 가 `undefined` | 차단 | **통과** → 백엔드 422 |
// | required CHECKBOX 미체크 | 차단 | **통과** |
//
// 같은 이슈의 같은 필드가 **만들 때와 고칠 때 규칙이 다르다.** 사용자는 이유를 알 수 없다.
// 게다가 편집 경로에는 `CUSTOM_FIELD_VALIDATION_FAILED` 매핑도 없어 일반 에러가 뜬다.
// ─────────────────────────────────────────────────────────────────────────────

/** required MULTI_SELECT 픽스처 — 값이 아예 없는 상태(`undefined`)를 만든다. */
const requiredMultiSelectFixture: CustomField = {
  id: 'fd000001-0000-4000-8000-000000000020',
  projectId: 'pd000001-0000-4000-8000-000000000001',
  key: 'req_multi',
  name: '필수 다중선택',
  description: null,
  fieldType: 'MULTI_SELECT',
  required: true,
  displayOrder: 0,
  options: [
    { value: 'a', label: 'A', displayOrder: 0 },
    { value: 'b', label: 'B', displayOrder: 1 },
  ],
}

/** required CHECKBOX 픽스처 — 약관 동의류. 체크해야 제출 가능해야 한다. */
const requiredCheckboxFixture: CustomField = {
  id: 'fd000001-0000-4000-8000-000000000021',
  projectId: 'pd000001-0000-4000-8000-000000000001',
  key: 'req_check',
  name: '필수 동의',
  description: null,
  fieldType: 'CHECKBOX',
  required: true,
  displayOrder: 0,
  options: [],
}

describe('IssueCustomFieldsEdit — required 판정이 생성 폼과 같아야 한다', () => {
  it('required MULTI_SELECT 가 값 없이(undefined) 저장되지 않는다', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[requiredMultiSelectFixture]}
        values={{}}
        onSave={onSave}
        canEdit={true}
      />,
    )
    await user.click(screen.getByTestId('custom-fields-save'))
    expect(screen.getByTestId('custom-fields-required-error')).toBeInTheDocument()
    expect(onSave).not.toHaveBeenCalled()
  })

  it('required CHECKBOX 를 체크하지 않으면 저장되지 않는다', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[requiredCheckboxFixture]}
        values={{}}
        onSave={onSave}
        canEdit={true}
      />,
    )
    await user.click(screen.getByTestId('custom-fields-save'))
    expect(screen.getByTestId('custom-fields-required-error')).toBeInTheDocument()
    expect(onSave).not.toHaveBeenCalled()
  })

  it('required CHECKBOX 를 체크하면 저장된다 (대조군 — 규칙이 한쪽으로 굳지 않았다)', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    render(
      <IssueCustomFieldsEdit
        fieldDefs={[requiredCheckboxFixture]}
        values={{ req_check: true }}
        onSave={onSave}
        canEdit={true}
      />,
    )
    await user.click(screen.getByTestId('custom-fields-save'))
    expect(screen.queryByTestId('custom-fields-required-error')).not.toBeInTheDocument()
    expect(onSave).toHaveBeenCalled()
  })
})
