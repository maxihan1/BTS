// Import 매핑 마법사 — 값(상태/유형/우선순위) 매핑 단계 컴포넌트 (FR-IM-02 D6 Task-5)
import type { JSX } from 'react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import type { ValueCollectionResponse, ValueTargetField } from '@/api/import-mappings'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 대상 필드 한국어 라벨 */
const FIELD_LABELS: Record<ValueTargetField, string> = {
  STATUS: '상태',
  TYPE: '유형',
  PRIORITY: '우선순위',
}

/**
 * 대상 필드별 엄격도 힌트.
 * STATUS는 자유 입력(불일치해도 새 값으로 등록)이고, TYPE/PRIORITY는 BTS canonical
 * 값과 정확히 일치해야 확정 단계를 통과한다 — FIELD_LABELS와 동형으로 필드별 매핑.
 */
const FIELD_HINTS: Record<ValueTargetField, string> = {
  STATUS: '자유롭게 입력할 수 있습니다. BTS에 없는 값이면 새 값으로 등록됩니다.',
  TYPE: 'BTS에 등록된 값과 정확히 일치해야 합니다. 일치하지 않으면 확정 단계에서 거부됩니다.',
  PRIORITY: 'BTS에 등록된 값과 정확히 일치해야 합니다. 일치하지 않으면 확정 단계에서 거부됩니다.',
}

/** targetField/sourceValue 쌍을 override map 키로 합성한다 */
function toOverrideKey(targetField: ValueTargetField, sourceValue: string): string {
  return `${targetField}::${sourceValue}`
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ValueMappingStep 컴포넌트 props */
export interface ValueMappingStepProps {
  /** collectValues 응답의 대상 필드별 값 목록 */
  readonly fields: ValueCollectionResponse['fields']
  /** `targetField::sourceValue` → 대상 값 override map */
  readonly value: Record<string, string>
  /** override map 변경 시 호출 — 전체 갱신된 map을 전달한다 */
  readonly onChange: (next: Record<string, string>) => void
  /** [다음] 클릭 콜백 */
  readonly onNext: () => void
  /** [이전] 클릭 콜백. 생략 시 [이전] 버튼을 렌더하지 않는다 */
  readonly onBack?: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// ValueMappingStep (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Import 매핑 마법사의 값(상태/유형/우선순위) 매핑 단계.
 *
 * `fields`(collectValues 응답)를 대상 필드별로 그룹 렌더한다. 각 소스 값 행은
 * 대상 값 `<Input>`을 제공하며, `suggestedTargetValue`가 있으면 프리필하고
 * `value` override map에 해당 키가 있으면 override 값을 우선한다.
 *
 * STATUS는 자유 입력(불일치해도 새 값으로 등록)이고, TYPE/PRIORITY는 BTS canonical
 * 값과 일치해야 확정 단계에서 통과한다 — 필드별로 다른 힌트를 노출한다.
 *
 * `fields`가 빈 배열이면 값 매핑 대상이 없다는 안내만 렌더한다. 컨테이너(마법사)가
 * 이 단계 자체를 자동 스킵할 수 있어 이 안내는 사용자가 뒤로 이동한 경우에만 보인다.
 */
export const ValueMappingStep = ({
  fields,
  value,
  onChange,
  onNext,
  onBack,
}: ValueMappingStepProps): JSX.Element => {
  const handleValueChange = (targetField: ValueTargetField, sourceValue: string, nextValue: string): void => {
    onChange({ ...value, [toOverrideKey(targetField, sourceValue)]: nextValue })
  }

  return (
    <div>
      {fields.length === 0 ? (
        <p className="mb-4 text-sm text-muted-foreground">매핑할 값이 없습니다.</p>
      ) : (
        fields.map((field) => (
          <ValueMappingGroup
            key={field.targetField}
            field={field}
            value={value}
            onValueChange={handleValueChange}
          />
        ))
      )}

      <div className="mt-2 flex justify-end gap-2">
        {onBack !== undefined && (
          <Button type="button" variant="outline" size="sm" onClick={onBack}>
            이전
          </Button>
        )}
        <Button type="button" size="sm" onClick={onNext}>
          다음
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ValueMappingGroup — 대상 필드 하나(상태/유형/우선순위)의 소스 값 목록
// ─────────────────────────────────────────────────────────────────────────────

interface ValueMappingGroupProps {
  readonly field: ValueCollectionResponse['fields'][number]
  readonly value: Record<string, string>
  readonly onValueChange: (targetField: ValueTargetField, sourceValue: string, nextValue: string) => void
}

/** 대상 필드 하나(상태/유형/우선순위) 그룹 — 라벨 + 엄격도 힌트 + 소스 값 행 목록 */
function ValueMappingGroup({ field, value, onValueChange }: ValueMappingGroupProps): JSX.Element {
  const label = FIELD_LABELS[field.targetField]
  const hint = FIELD_HINTS[field.targetField]

  return (
    <fieldset className="mb-4">
      <legend className="text-sm font-medium">{label}</legend>
      <p className="mb-2 text-xs text-muted-foreground">{hint}</p>
      {field.values.map((entry) => (
        <ValueMappingRow
          key={entry.sourceValue}
          label={label}
          targetField={field.targetField}
          sourceValue={entry.sourceValue}
          suggestedTargetValue={entry.suggestedTargetValue ?? null}
          overrideValue={value[toOverrideKey(field.targetField, entry.sourceValue)]}
          onValueChange={onValueChange}
        />
      ))}
    </fieldset>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ValueMappingRow — 소스 값 하나 + 대상 값 입력
// ─────────────────────────────────────────────────────────────────────────────

interface ValueMappingRowProps {
  readonly label: string
  readonly targetField: ValueTargetField
  readonly sourceValue: string
  readonly suggestedTargetValue: string | null
  readonly overrideValue: string | undefined
  readonly onValueChange: (targetField: ValueTargetField, sourceValue: string, nextValue: string) => void
}

/** 소스 값 하나의 행 — 좌측 소스 값 표시, 우측 대상 값 입력(override 우선, 없으면 추천값 프리필) */
function ValueMappingRow({
  label,
  targetField,
  sourceValue,
  suggestedTargetValue,
  overrideValue,
  onValueChange,
}: ValueMappingRowProps): JSX.Element {
  const inputValue = overrideValue ?? suggestedTargetValue ?? ''

  return (
    <div className="mb-2 flex items-center gap-2">
      <span className="w-1/2 truncate text-sm text-foreground">{sourceValue}</span>
      <Input
        aria-label={`${label} ${sourceValue} 대상 값`}
        value={inputValue}
        onChange={(e) => {
          onValueChange(targetField, sourceValue, e.target.value)
        }}
      />
    </div>
  )
}
