// 커스텀 필드 FieldType 10종 입력 위젯 — 순수 presentational 컴포넌트 (FR-IS-10 D6 Task 5)
import type { JSX, ChangeEvent } from 'react'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { CustomField, CustomFieldOption } from '@/api/custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** textarea 기본 행 수 */
const TEXTAREA_DEFAULT_ROWS = 4

// ─────────────────────────────────────────────────────────────────────────────
// 변환 유틸 (DATETIME offset 처리)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ISO8601 offset 문자열을 datetime-local input 값("YYYY-MM-DDTHH:mm")으로 변환한다.
 * 값이 없거나 파싱 불가능하면 빈 문자열을 반환한다.
 *
 * @param iso ISO8601 offset 문자열 (예: "2024-03-15T09:30:00+09:00")
 * @returns datetime-local 입력 형식 문자열 (예: "2024-03-15T09:30")
 */
function toLocalInput(iso: string): string {
  if (!iso) return ''
  try {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return ''
    // ISO 문자열에서 오프셋 부분을 제거하고 "YYYY-MM-DDTHH:mm"만 추출
    // new Date(iso).toISOString()은 항상 UTC로 변환되므로
    // 원본 오프셋 기준의 로컬 시각을 보존하려면 문자열 직접 파싱이 필요
    const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/.exec(iso)
    if (match !== null && match[1] !== undefined) {
      return match[1]
    }
    return ''
  } catch {
    return ''
  }
}

/**
 * datetime-local input 값("YYYY-MM-DDTHH:mm")을 ISO8601 offset 문자열로 변환한다.
 * 로컬 시스템 타임존 오프셋을 적용한다. 빈 값이면 빈 문자열을 반환한다.
 *
 * @param local datetime-local 입력값 (예: "2024-06-01T14:00")
 * @returns ISO8601 offset 문자열 (예: "2024-06-01T14:00:00+09:00")
 */
function toIsoOffset(local: string): string {
  if (!local) return ''
  try {
    const d = new Date(local)
    if (isNaN(d.getTime())) return ''
    // 시스템 타임존 오프셋(분 단위)
    const offsetMin = -d.getTimezoneOffset()
    const sign = offsetMin >= 0 ? '+' : '-'
    const absMin = Math.abs(offsetMin)
    const hh = String(Math.floor(absMin / 60)).padStart(2, '0')
    const mm = String(absMin % 60).padStart(2, '0')
    // "YYYY-MM-DDTHH:mm:ss+HH:MM" 형식
    const pad = (n: number): string => String(n).padStart(2, '0')
    return (
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
      `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` +
      `${sign}${hh}:${mm}`
    )
  } catch {
    return ''
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** CustomFieldInput 컴포넌트 props */
export interface CustomFieldInputProps {
  /** 커스텀 필드 메타 정보 */
  readonly field: CustomField
  /** 현재 값 — FieldType에 따라 타입이 달라진다 */
  readonly value: unknown
  /** 값 변경 콜백 — FieldType에 맞는 타입으로 호출된다 */
  readonly onChange: (value: unknown) => void
  /** 비활성화 여부 */
  readonly disabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입별 하위 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** SHORT_TEXT 위젯 */
function ShortTextWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const strValue = typeof value === 'string' ? value : ''
  return (
    <Input
      type="text"
      data-testid={`custom-field-${field.key}`}
      aria-label={field.name}
      value={strValue}
      required={field.required}
      disabled={disabled}
      onChange={(e: ChangeEvent<HTMLInputElement>) => onChange(e.target.value)}
    />
  )
}

/** LONG_TEXT 위젯 */
function LongTextWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const strValue = typeof value === 'string' ? value : ''
  return (
    <textarea
      data-testid={`custom-field-${field.key}`}
      aria-label={field.name}
      value={strValue}
      rows={TEXTAREA_DEFAULT_ROWS}
      required={field.required}
      disabled={disabled}
      className="h-auto w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
      onChange={(e: ChangeEvent<HTMLTextAreaElement>) => onChange(e.target.value)}
    />
  )
}

/** NUMBER 위젯 */
function NumberWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const numValue = typeof value === 'number' ? value : value === undefined || value === null ? '' : value
  return (
    <Input
      type="number"
      data-testid={`custom-field-${field.key}`}
      aria-label={field.name}
      value={numValue as string | number}
      required={field.required}
      disabled={disabled}
      onChange={(e: ChangeEvent<HTMLInputElement>) => {
        const raw = e.target.value
        onChange(raw === '' ? undefined : Number(raw))
      }}
    />
  )
}

/** DATE 위젯 */
function DateWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const strValue = typeof value === 'string' ? value : ''
  return (
    <Input
      type="date"
      data-testid={`custom-field-${field.key}`}
      aria-label={field.name}
      value={strValue}
      required={field.required}
      disabled={disabled}
      onChange={(e: ChangeEvent<HTMLInputElement>) => onChange(e.target.value)}
    />
  )
}

/** DATETIME 위젯 — ISO↔datetime-local 변환 포함 */
function DateTimeWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const strValue = typeof value === 'string' ? value : ''
  const localValue = toLocalInput(strValue)
  return (
    <Input
      type="datetime-local"
      data-testid={`custom-field-${field.key}`}
      aria-label={field.name}
      value={localValue}
      required={field.required}
      disabled={disabled}
      onChange={(e: ChangeEvent<HTMLInputElement>) => {
        const raw = e.target.value
        onChange(raw === '' ? '' : toIsoOffset(raw))
      }}
    />
  )
}

/** SINGLE_SELECT 위젯 */
function SingleSelectWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const strValue = typeof value === 'string' ? value : ''
  return (
    <Select
      value={strValue}
      disabled={disabled}
      required={field.required}
      onValueChange={(v: string) => onChange(v)}
    >
      <SelectTrigger
        data-testid={`custom-field-${field.key}`}
        aria-label={field.name}
        className="w-full"
      >
        <SelectValue placeholder="선택하세요" />
      </SelectTrigger>
      <SelectContent>
        {field.options.map((opt: CustomFieldOption) => (
          <SelectItem key={opt.value} value={opt.value}>
            {opt.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

/** MULTI_SELECT 위젯 — 네이티브 checkbox 목록 */
function MultiSelectWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const selected: string[] = Array.isArray(value)
    ? (value as unknown[]).filter((v): v is string => typeof v === 'string')
    : []

  function handleChange(optValue: string, checked: boolean): void {
    if (checked) {
      onChange([...selected, optValue])
    } else {
      onChange(selected.filter((v) => v !== optValue))
    }
  }

  return (
    <div
      data-testid={`custom-field-${field.key}`}
      role="group"
      aria-label={field.name}
      className="flex flex-col gap-1"
    >
      {field.options.map((opt: CustomFieldOption) => (
        <label key={opt.value} className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            value={opt.value}
            checked={selected.includes(opt.value)}
            disabled={disabled}
            aria-label={opt.label}
            onChange={(e: ChangeEvent<HTMLInputElement>) =>
              handleChange(opt.value, e.target.checked)
            }
          />
          {opt.label}
        </label>
      ))}
    </div>
  )
}

/** CHECKBOX 위젯 — 단일 boolean 체크박스 */
function CheckboxWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const checked = typeof value === 'boolean' ? value : false
  return (
    <label className="flex items-center gap-2 text-sm">
      <input
        type="checkbox"
        data-testid={`custom-field-${field.key}`}
        aria-label={field.name}
        checked={checked}
        required={field.required}
        disabled={disabled}
        onChange={(e: ChangeEvent<HTMLInputElement>) => onChange(e.target.checked)}
      />
      {field.name}
    </label>
  )
}

/** RADIO 위젯 — 네이티브 radio 그룹 */
function RadioWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const strValue = typeof value === 'string' ? value : ''
  return (
    <div
      data-testid={`custom-field-${field.key}`}
      role="group"
      aria-label={field.name}
      className="flex flex-col gap-1"
    >
      {field.options.map((opt: CustomFieldOption) => (
        <label key={opt.value} className="flex items-center gap-2 text-sm">
          <input
            type="radio"
            name={field.key}
            value={opt.value}
            checked={strValue === opt.value}
            disabled={disabled}
            aria-label={opt.label}
            required={field.required}
            onChange={(e: ChangeEvent<HTMLInputElement>) => {
              if (e.target.checked) onChange(opt.value)
            }}
          />
          {opt.label}
        </label>
      ))}
    </div>
  )
}

/** URL 위젯 */
function UrlWidget({
  field,
  value,
  onChange,
  disabled,
}: CustomFieldInputProps): JSX.Element {
  const strValue = typeof value === 'string' ? value : ''
  return (
    <Input
      type="url"
      data-testid={`custom-field-${field.key}`}
      aria-label={field.name}
      value={strValue}
      required={field.required}
      disabled={disabled}
      onChange={(e: ChangeEvent<HTMLInputElement>) => onChange(e.target.value)}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 FieldType 10종에 대응하는 입력 위젯.
 * 순수 presentational 컴포넌트 — 상태 없음, 부모가 value/onChange를 제어한다.
 *
 * data-testid="custom-field-{field.key}" 가 각 입력 요소(또는 컨테이너)에 부여된다.
 *
 * @param field 커스텀 필드 메타 정보
 * @param value 현재 값 — FieldType별 타입 참조
 * @param onChange 값 변경 콜백
 * @param disabled 비활성화 여부
 */
export function CustomFieldInput(props: CustomFieldInputProps): JSX.Element {
  const { field } = props
  switch (field.fieldType) {
    case 'SHORT_TEXT':
      return <ShortTextWidget {...props} />
    case 'LONG_TEXT':
      return <LongTextWidget {...props} />
    case 'NUMBER':
      return <NumberWidget {...props} />
    case 'DATE':
      return <DateWidget {...props} />
    case 'DATETIME':
      return <DateTimeWidget {...props} />
    case 'SINGLE_SELECT':
      return <SingleSelectWidget {...props} />
    case 'MULTI_SELECT':
      return <MultiSelectWidget {...props} />
    case 'CHECKBOX':
      return <CheckboxWidget {...props} />
    case 'RADIO':
      return <RadioWidget {...props} />
    case 'URL':
      return <UrlWidget {...props} />
  }
}
