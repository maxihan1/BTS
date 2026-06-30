// 가젯 설정 폼 — configFields 기반 타입별 입력 + 클라측 config 검증 (FR-DB-02 Task 6)
import type { JSX, ChangeEvent, Dispatch, SetStateAction } from 'react'
import { useState, useId } from 'react'
import { cn } from '@/lib/utils'
import { validateGadgetConfig, type GadgetCatalogEntry, type ConfigField } from '@/api/gadget-catalog'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MVP에서 숨길 필드 키 목록.
 * - aql: filter_result/issue_count는 filterId 경로만 MVP에서 지원한다.
 */
const MVP_HIDDEN_KEYS = new Set<string>(['aql'])

/** 공용 입력 위젯 CSS 클래스 — 모든 필드 타입이 공유한다 */
const INPUT_CLASS = cn(
  'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
  'placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
)

/** 필드 레이블 CSS 클래스 */
const LABEL_CLASS = 'mb-1 block text-sm font-medium text-foreground'

// ─────────────────────────────────────────────────────────────────────────────
// 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

/** GadgetConfigForm 컴포넌트 props */
export interface GadgetConfigFormProps {
  /** 선택한 카탈로그 엔트리 — configFields 기반으로 폼을 자동 생성한다 */
  entry: GadgetCatalogEntry
  /** 추가 콜백 — 검증 통과 시 호출 */
  onAdd: (config: Record<string, unknown>) => void
  /** 뒤로 가기 콜백 — 카탈로그 선택 단계로 복귀 */
  onBack: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * camelCase/snake_case 필드 키를 사람이 읽을 수 있는 레이블로 변환한다.
 *
 * @param key 필드 키 (예: "filterId", "project_key")
 * @returns 레이블 문자열 (예: "Filter Id", "Project Key")
 */
function toLabel(key: string): string {
  return key
    .replace(/_/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

// ─────────────────────────────────────────────────────────────────────────────
// FieldRow — 필드 타입별 입력 렌더 (내부 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

interface FieldRowProps {
  field: ConfigField
  /** label htmlFor 연결용 ID */
  fieldId: string
  strVals: Record<string, string>
  arrVals: Record<string, Array<Record<string, string>>>
  setStrVals: Dispatch<SetStateAction<Record<string, string>>>
  setArrVals: Dispatch<SetStateAction<Record<string, Array<Record<string, string>>>>>
}

/** 단일 config 필드를 렌더한다 — 필드 타입에 따라 입력 위젯을 선택한다 */
function FieldRow({
  field,
  fieldId,
  strVals,
  arrVals,
  setStrVals,
  setArrVals,
}: FieldRowProps): JSX.Element {
  const label = toLabel(field.key)

  // ARRAY 필드 (link_list links 등)
  if (field.type === 'ARRAY') {
    const rows = arrVals[field.key] ?? []
    const itemFields = field.itemSchema ?? []

    function addRow(): void {
      const emptyRow: Record<string, string> = Object.fromEntries(
        itemFields.map((sf) => [sf.key, '']),
      )
      setArrVals((prev) => ({ ...prev, [field.key]: [...(prev[field.key] ?? []), emptyRow] }))
    }

    function removeRow(idx: number): void {
      setArrVals((prev) => {
        const arr = [...(prev[field.key] ?? [])]
        arr.splice(idx, 1)
        return { ...prev, [field.key]: arr }
      })
    }

    function updateRow(idx: number, itemKey: string, val: string): void {
      setArrVals((prev) => {
        const arr = [...(prev[field.key] ?? [])]
        arr[idx] = { ...(arr[idx] ?? {}), [itemKey]: val }
        return { ...prev, [field.key]: arr }
      })
    }

    return (
      <div>
        <span className={LABEL_CLASS}>{label}</span>
        {rows.map((row, idx) => (
          <div key={idx} className="mt-2 flex items-center gap-2">
            {itemFields.map((sf) => (
              <input
                key={sf.key}
                type={sf.type === 'URL' ? 'url' : 'text'}
                value={row[sf.key] ?? ''}
                onChange={(e) => updateRow(idx, sf.key, e.target.value)}
                placeholder={sf.key}
                className={cn(INPUT_CLASS, 'flex-1')}
              />
            ))}
            {rows.length > 1 && (
              <button
                type="button"
                onClick={() => removeRow(idx)}
                className="shrink-0 rounded px-2 py-1 text-xs text-destructive hover:bg-destructive/10"
              >
                삭제
              </button>
            )}
          </div>
        ))}
        <button
          type="button"
          onClick={addRow}
          className="mt-2 text-sm text-primary hover:underline"
        >
          항목 추가
        </button>
      </div>
    )
  }

  // 공통 onChange (비ARRAY)
  const strValue = strVals[field.key] ?? ''
  function onChange(
    e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ): void {
    setStrVals((prev) => ({ ...prev, [field.key]: e.target.value }))
  }

  // STRING(markdown) → textarea
  if (field.type === 'STRING' && field.key === 'markdown') {
    return (
      <div>
        <label htmlFor={fieldId} className={LABEL_CLASS}>
          {label}
        </label>
        <textarea
          id={fieldId}
          value={strValue}
          onChange={onChange}
          rows={5}
          placeholder="마크다운 텍스트를 입력하세요..."
          className={cn(INPUT_CLASS, 'resize-y')}
        />
      </div>
    )
  }

  // ENUM → select
  if (field.type === 'ENUM') {
    return (
      <div>
        <label htmlFor={fieldId} className={LABEL_CLASS}>
          {label}
        </label>
        <select id={fieldId} value={strValue} onChange={onChange} className={INPUT_CLASS}>
          <option value="">-- 선택 --</option>
          {(field.enumValues ?? []).map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </select>
      </div>
    )
  }

  // STRING / INT / UUID / URL → input
  const inputType = field.type === 'INT' ? 'number' : field.type === 'URL' ? 'url' : 'text'
  return (
    <div>
      <label htmlFor={fieldId} className={LABEL_CLASS}>
        {label}
      </label>
      <input
        id={fieldId}
        type={inputType}
        value={strValue}
        onChange={onChange}
        placeholder={field.key}
        className={INPUT_CLASS}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// GadgetConfigForm — 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 설정 폼 컴포넌트.
 *
 * - entry.configFields 기반으로 폼을 자동 생성한다.
 * - MVP 숨김 필드(aql)는 렌더하지 않는다.
 * - 추가 시 validateGadgetConfig(entry, config)를 호출해 위반 메시지를 인라인 표시한다.
 * - 부모(GadgetCatalogModal)가 key={entry.type}을 부여해 가젯 종류 변경 시 자동 재마운트된다.
 */
export function GadgetConfigForm({ entry, onAdd, onBack }: GadgetConfigFormProps): JSX.Element {
  const baseId = useId()

  // 비ARRAY 필드 값 (초기: 빈 문자열)
  const [strVals, setStrVals] = useState<Record<string, string>>(() =>
    Object.fromEntries(entry.configFields.map((f) => [f.key, ''])),
  )

  // ARRAY 필드 값 (초기: 1행)
  const [arrVals, setArrVals] = useState<Record<string, Array<Record<string, string>>>>(() =>
    Object.fromEntries(
      entry.configFields
        .filter((f) => f.type === 'ARRAY')
        .map((f) => {
          const emptyRow: Record<string, string> = Object.fromEntries(
            (f.itemSchema ?? []).map((sf) => [sf.key, '']),
          )
          return [f.key, [emptyRow]]
        }),
    ),
  )

  const [errors, setErrors] = useState<string[]>([])

  /** 제출 시 config 객체를 조립한다 — 빈 문자열은 제외 (requireAtLeastOne·EC5 정합) */
  function buildConfig(): Record<string, unknown> {
    const config: Record<string, unknown> = {}
    for (const field of entry.configFields) {
      if (field.type === 'ARRAY') {
        // ARRAY는 항상 포함 (빈 배열 포함 → minItems/required 검증 필요)
        config[field.key] = arrVals[field.key] ?? []
      } else if (field.type === 'INT') {
        const v = strVals[field.key] ?? ''
        if (v !== '') {
          const n = parseInt(v, 10)
          if (!isNaN(n)) config[field.key] = n
        }
      } else {
        const v = strVals[field.key] ?? ''
        if (v !== '') config[field.key] = v
      }
    }
    return config
  }

  function handleSubmit(): void {
    const config = buildConfig()
    const errs = validateGadgetConfig(entry, config)
    setErrors(errs)
    if (errs.length === 0) {
      onAdd(config)
    }
  }

  const visibleFields = entry.configFields.filter((f) => !MVP_HIDDEN_KEYS.has(f.key))

  return (
    <div className="mt-4">
      {/* 검증 에러 알림 */}
      {errors.length > 0 && (
        <div
          role="alert"
          className="mb-3 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {errors.map((e, i) => (
            <p key={i}>{e}</p>
          ))}
        </div>
      )}

      {/* 필드 목록 */}
      <div className="max-h-[50vh] space-y-4 overflow-y-auto">
        {visibleFields.map((field) => (
          <FieldRow
            key={field.key}
            field={field}
            fieldId={`${baseId}-${field.key}`}
            strVals={strVals}
            arrVals={arrVals}
            setStrVals={setStrVals}
            setArrVals={setArrVals}
          />
        ))}
      </div>

      {/* 액션 버튼 */}
      <div className="mt-6 flex justify-between">
        <button
          type="button"
          onClick={onBack}
          className="rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground hover:bg-muted transition-colors"
        >
          뒤로
        </button>
        <button
          type="button"
          onClick={handleSubmit}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          추가
        </button>
      </div>
    </div>
  )
}
