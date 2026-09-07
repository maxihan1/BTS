// 가젯 설정 폼 — configFields 기반 타입별 입력 + 클라측 config 검증 (FR-DB-02 Task 6)
import type { JSX, ChangeEvent, Dispatch, SetStateAction } from 'react'
import { useState, useId } from 'react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { validateGadgetConfig, type GadgetCatalogEntry, type ConfigField } from '@/api/gadget-catalog'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import { ProjectPicker, BoardPicker, FilterPicker } from './GadgetScopePicker'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MVP에서 숨길 필드 키 목록.
 * - aql: filter_result/issue_count는 filterId 경로만 MVP에서 지원한다.
 */
const MVP_HIDDEN_KEYS = new Set<string>(['aql'])

/**
 * 스코프성 필드 → 선택기 매핑.
 *
 * ★이 목록은 백엔드 `GadgetType.kt` 의 필드 목록과 **서로를 검사하지 않는다.** 누가 거기에
 * 스코프성 필드를 추가해도 폼은 조용히 텍스트 입력으로 떨어지고, 그 상태는 「설정이 어렵다」로만
 * 드러난다 — 이 매핑이 고치려는 바로 그 결함의 재발이다.
 * 그래서 `scripts/workflow/gadget-config-picker-coverage.test.ts` 가 차집합으로 강제한다.
 * 필드를 늘릴 때 여기도 늘리지 않으면 그 판별식이 red 를 낸다.
 *
 * 값은 선택기 종류다 — 실제 컴포넌트 매핑은 아래 `renderPicker` 가 한다. 문자열로 두는 이유는
 * 판별식이 소스를 정규식으로 읽기 때문이다(컴포넌트 참조는 파싱이 취약해진다).
 */
const PICKER_BY_KEY = {
  projectKey: 'project',
  boardId: 'board',
  filterId: 'filter',
} as const

/** `PICKER_BY_KEY` 의 값 유니온 */
type PickerKind = (typeof PICKER_BY_KEY)[keyof typeof PICKER_BY_KEY]

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
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() => removeRow(idx)}
                className="shrink-0 rounded text-destructive hover:bg-destructive/10"
              >
                삭제
              </Button>
            )}
          </div>
        ))}
        <Button
          type="button"
          variant="link"
          size="default"
          onClick={addRow}
          className="mt-2 self-start px-0"
        >
          항목 추가
        </Button>
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
          placeholder={dashboardLabels.markdownGadgetPlaceholder}
          className={cn(INPUT_CLASS, 'resize-y')}
        />
      </div>
    )
  }

  // ENUM → select
  // ★스코프성 필드는 자유 입력이 아니라 선택기다. 타입 분기(STRING/UUID)보다 **먼저** 본다 —
  //   projectKey 는 STRING 이고 boardId 는 UUID 라, 타입으로 갈라 놓으면 두 자리에 같은 분기를 써야 한다.
  const pickerKind: PickerKind | undefined = PICKER_BY_KEY[field.key as keyof typeof PICKER_BY_KEY]
  if (pickerKind !== undefined) {
    const setValue = (next: string): void => {
      setStrVals((prev) => ({ ...prev, [field.key]: next }))
    }
    return (
      <div>
        <label htmlFor={fieldId} className={LABEL_CLASS}>
          {label}
        </label>
        {pickerKind === 'project' && (
          <ProjectPicker id={fieldId} value={strVals[field.key] ?? ''} onChange={setValue} />
        )}
        {pickerKind === 'board' && (
          <BoardPicker id={fieldId} value={strVals[field.key] ?? ''} onChange={setValue} />
        )}
        {pickerKind === 'filter' && (
          <FilterPicker id={fieldId} value={strVals[field.key] ?? ''} onChange={setValue} />
        )}
      </div>
    )
  }

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
        <Button
          type="button"
          variant="outline"
          size="lg"
          onClick={onBack}
          className="rounded-md px-4"
        >
          뒤로
        </Button>
        <Button
          type="button"
          variant="default"
          size="lg"
          onClick={handleSubmit}
          className="rounded-md px-4 hover:bg-primary/90"
        >
          추가
        </Button>
      </div>
    </div>
  )
}
