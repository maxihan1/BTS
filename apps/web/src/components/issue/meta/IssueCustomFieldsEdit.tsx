// 이슈 커스텀 필드 일괄 편집 컴포넌트 (IssueMetaPanel 분해 A, FR-IS-10)
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { CustomFieldInput } from '@/components/custom-fields/CustomFieldInput'
import type { CustomField } from '@/api/custom-fields.types'
import { isRequiredFieldEmpty } from '@/components/custom-fields/required-empty'
import type { CustomFieldValues } from '@/api/issues'
import { issueDetailStrings } from '@/i18n/ko'
// FR-PM-07 필드 권한 헬퍼 — IssueMetaPanel.tsx에 정본으로 남아있는 순수 함수를 재사용한다.
// IssueMetaPanel.tsx도 이 파일의 IssueCustomFieldsEdit를 import하지만, 두 모듈 모두
// 이 값들을 렌더 시점(함수 호출) 에만 참조하므로 모듈 평가 시점 순환 문제는 없다.
import { isFieldHidden, isFieldDisabled } from '@/components/issue/IssueMetaPanel'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueCustomFieldsEdit props */
export interface IssueCustomFieldsEditProps {
  /** 활성 커스텀 필드 정의 목록 */
  fieldDefs: CustomField[]
  /** 현재 이슈의 커스텀 필드 값 맵 */
  values: CustomFieldValues
  /** 저장 콜백 — 편집된 키-값 맵 전달 */
  onSave: (customFields: CustomFieldValues) => void
  /** 수정 권한 — false이면 저장 버튼 disabled */
  canEdit: boolean
  /**
   * FR-PM-07 — 열람 불가 필드 키 목록. 해당 커스텀 필드는 렌더 자체를 건너뜀.
   * 미전달 시 빈 배열로 처리 (기존 호출부 하위호환).
   */
  restrictedFields?: string[]
  /**
   * FR-PM-07 — 편집 불가 필드 키 목록. 해당 커스텀 필드의 입력 컨트롤은 disabled.
   * canEdit과 OR 비활성 — 둘 중 하나라도 막으면 disabled.
   * 미전달 시 빈 배열로 처리 (기존 호출부 하위호환).
   */
  noneditableFields?: string[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 커스텀 필드 일괄 편집 컴포넌트 (FR-IS-10).
 *
 * - 활성 정의(fieldDefs) 기준으로 CustomFieldInput을 렌더한다.
 * - issue.customFields 값이 바뀌면(refetch) 로컬 상태도 동기화 (stale 방지).
 * - 저장 버튼 클릭 시 required 빈값 검사 → 빈 필드 존재 시 경고 표시 + 저장 차단 (스펙 E-3).
 * - 미정의 잔존 키는 표시 생략 (스펙 E-2).
 * - WCAG AA: data-testid="custom-fields-section", data-testid="custom-fields-save"
 */
export function IssueCustomFieldsEdit({
  fieldDefs,
  values,
  onSave,
  canEdit,
  restrictedFields = [],
  noneditableFields = [],
}: IssueCustomFieldsEditProps): JSX.Element {
  // FR-PM-07: restrictedFields에 포함된 커스텀 필드는 렌더에서 제외 (isFieldHidden 헬퍼)
  const visibleFieldDefs = fieldDefs.filter((f) => !isFieldHidden(f.key, restrictedFields))
  const [draft, setDraft] = useState<CustomFieldValues>({ ...values })
  const [hasRequiredError, setHasRequiredError] = useState(false)

  // values props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
  const prevValuesRef = useRef(values)
  useEffect(() => {
    if (prevValuesRef.current !== values) {
      prevValuesRef.current = values
      setDraft({ ...values })
      setHasRequiredError(false)
    }
  }, [values])

  function handleFieldChange(key: string, value: unknown): void {
    setDraft((prev) => ({ ...prev, [key]: value }))
    // 값이 변경되면 기존 에러 초기화
    if (hasRequiredError) setHasRequiredError(false)
  }

  /**
   * 저장 직전 draft를 정규화한다 (스펙 E-6).
   * - draft에 실제로 존재하는 키만 처리 (미정의 잔존 키 포함 금지 — E-2 정책)
   * - 타입별 빈값 판정:
   *   SHORT_TEXT/LONG_TEXT/URL/DATE/DATETIME/SINGLE_SELECT/RADIO: '' | undefined → null
   *   NUMBER: undefined 또는 NaN → null. 단 0은 유효값으로 유지.
   *   MULTI_SELECT: [] → null
   *   CHECKBOX: boolean false 포함 유효값, null 변환 안 함.
   */
  function buildNormalizedPatch(): CustomFieldValues {
    const patch: CustomFieldValues = {}
    // visibleFieldDefs 기준 — restrictedFields에 포함된 키는 패치에서도 제외 (FR-PM-07)
    for (const field of visibleFieldDefs) {
      const key = field.key
      if (!(key in draft)) continue
      const raw = draft[key]
      switch (field.fieldType) {
        case 'SHORT_TEXT':
        case 'LONG_TEXT':
        case 'URL':
        case 'DATE':
        case 'DATETIME':
        case 'SINGLE_SELECT':
        case 'RADIO':
          patch[key] = raw === '' || raw === undefined ? null : raw
          break
        case 'NUMBER': {
          const isBlank = raw === undefined || raw === null || (typeof raw === 'number' && isNaN(raw))
          patch[key] = isBlank ? null : raw
          break
        }
        case 'MULTI_SELECT':
          patch[key] = Array.isArray(raw) && raw.length === 0 ? null : raw
          break
        case 'CHECKBOX':
          // boolean false 포함 유효값 — 그대로 유지
          patch[key] = raw
          break
      }
    }
    return patch
  }

  function handleSave(): void {
    // 스펙 E-3: required 필드 빈값 1차 검사 — visibleFieldDefs 기준 (restrictedFields 제외)
    const hasEmpty = visibleFieldDefs.some((field) => {
      if (!field.required) return false
      const raw = draft[field.key]
      return isRequiredFieldEmpty(field.fieldType, raw)
    })
    if (hasEmpty) {
      setHasRequiredError(true)
      return
    }
    setHasRequiredError(false)
    onSave(buildNormalizedPatch())
  }

  return (
    <div
      className="px-3.5 py-3 border-b border-border"
      data-testid="custom-fields-section"
    >
      <p className="text-xs text-muted-foreground mb-2">{issueDetailStrings.customFieldsSectionLabel}</p>
      <div className="flex flex-col gap-3">
        {visibleFieldDefs.map((field) => (
          <div key={field.id} className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">
              {field.name}
              {field.required && <span className="text-destructive ml-0.5">*</span>}
            </label>
            <CustomFieldInput
              field={field}
              value={draft[field.key]}
              onChange={(v) => handleFieldChange(field.key, v)}
              disabled={isFieldDisabled(field.key, canEdit, noneditableFields)}
            />
          </div>
        ))}
      </div>
      {/* required 빈값 경고 — 스펙 E-3 1차 클라 경고 */}
      {hasRequiredError && (
        <p
          className="text-xs text-destructive mt-1"
          role="alert"
          data-testid="custom-fields-required-error"
        >
          {issueDetailStrings.customFieldRequiredEmpty}
        </p>
      )}
      <Button
        variant="outline"
        size="sm"
        className="self-end mt-2 min-h-[44px]"
        onClick={handleSave}
        disabled={!canEdit}
        aria-label={issueDetailStrings.customFieldsSaveAriaLabel}
        data-testid="custom-fields-save"
      >
        {issueDetailStrings.customFieldsSaveButton}
      </Button>
    </div>
  )
}
