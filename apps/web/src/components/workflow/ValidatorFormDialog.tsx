// 전환 규칙(validator) 추가·수정 다이얼로그 — type 별 config 폼 + baseline 병합 저장 (FR-WF-06 D6)
import type { JSX } from 'react'
import { useId, useState } from 'react'
import type { z } from 'zod'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { VALIDATOR_CONFIG_FORM_SCHEMAS, validatorConfigFormSchema } from '@/api/validators'
import type { ValidatorConfigFormSchema } from '@/api/validators'
import { validatorLabels } from '@/i18n/validator-labels'
import { cn } from '@/lib/utils'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 다이얼로그가 상위로 올려 보내는 값 — `displayOrder` 는 여기 없다(제약 C7). */
export interface ValidatorFormValues {
  /** validator type 식별자. */
  type: string
  /** baseline 을 병합한 **전체** config (제약 C6). */
  config: Record<string, unknown>
}

/** ValidatorFormDialog 컴포넌트 props */
export interface ValidatorFormDialogProps {
  /** 다이얼로그 열림 여부 */
  open: boolean
  /** create: 추가 / edit: 수정 */
  mode: 'create' | 'edit'
  /** edit 모드의 대상 행 — `type` 과 **baseline config** */
  initialValue?: ValidatorFormValues
  /** mutation 진행 중 여부 */
  submitting?: boolean
  /** 서버가 거절한 이유 — 다이얼로그를 닫지 않고 안에 띄운다(FR-7) */
  serverError?: string
  /** 검증을 통과했을 때만 호출된다 */
  onSubmit: (values: ValidatorFormValues) => void
  /** 취소·닫기 */
  onCancel: () => void
}

/** config 폼이 그릴 입력 한 칸 */
interface ConfigField {
  /** backend config 키. 라벨도 이 문자열 그대로다 — 사본을 만들지 않는다. */
  key: string
  /** 스키마상 선택 입력인가 */
  optional: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 스키마에서 폼을 도출하는 유틸리티
//
// ★ 폼이 자기 키 목록을 따로 들지 않는다. 키의 정본은 `VALIDATOR_CONFIG_FORM_SCHEMAS` 이고
//   그 선언을 `scripts/workflow/validator-type-catalog.test.ts` 가 SDD §7.3 표와 대조한다.
//   여기서 키를 한 번 더 적으면 그것이 대조되지 않는 세 번째 목록이 된다.
// ─────────────────────────────────────────────────────────────────────────────

/** 폼 스키마가 있는 type 전수. `CustomExpression` 은 스키마가 없으므로 자동으로 빠진다. */
const EDITABLE_TYPES: readonly string[] = Object.keys(VALIDATOR_CONFIG_FORM_SCHEMAS)

/**
 * 폼 스키마에서 입력 칸 목록을 도출한다.
 *
 * @param schema type 에 대응하는 config 폼 스키마.
 * @return 선언 순서를 유지한 입력 칸 목록.
 */
function configFieldsOf(schema: ValidatorConfigFormSchema): ConfigField[] {
  const shape: Record<string, z.ZodType> = schema.shape
  return Object.entries(shape).map(([key, field]) => ({ key, optional: field.isOptional() }))
}

/**
 * baseline config 에서 폼 입력의 초기 문자열을 뽑는다.
 *
 * 폼이 모르는 키까지 함께 담기지만 저장은 {@link mergeConfig} 가 현재 스키마의 키만 덮어쓰므로
 * 화면에 안 보이는 값이 실수로 바뀌지 않는다.
 *
 * @param config 로드한 config.
 * @return 키 → 표시 문자열.
 */
function stringValuesOf(config: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [key, value] of Object.entries(config)) {
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      out[key] = String(value)
    }
  }
  return out
}

/**
 * baseline 위에 폼이 아는 키만 덮어써서 **전체** config 를 만든다 (제약 C6).
 *
 * PUT 은 표현 전체 교체라 폼이 아는 키만 담아 보내면 손으로 넣은 값이나 나중 판올림에서 더한 키가
 * 편집 한 번에 조용히 사라진다. 그래서 baseline 을 버리지 않고 그 위에 덮어쓴다.
 * 선택 입력을 비운 것은 「지우겠다」는 뜻이므로 그 키만 뺀다.
 *
 * @param baseline 로드한 config. create 모드면 빈 객체.
 * @param fields 현재 type 의 입력 칸 목록.
 * @param values 폼 입력 문자열.
 * @return 요청 본문에 실을 전체 config.
 */
function mergeConfig(
  baseline: Record<string, unknown>,
  fields: readonly ConfigField[],
  values: Readonly<Record<string, string>>,
): Record<string, unknown> {
  const merged: Record<string, unknown> = { ...baseline }
  for (const { key, optional } of fields) {
    const typed = (values[key] ?? '').trim()
    if (typed === '' && optional) {
      delete merged[key]
      continue
    }
    merged[key] = typed
  }
  return merged
}

/**
 * 스키마를 어긴 config 키를 뽑는다.
 *
 * @param schema 현재 type 의 폼 스키마.
 * @param config 병합을 마친 config.
 * @return 어긴 키 목록. 전부 통과하면 빈 배열.
 */
function invalidKeysOf(
  schema: ValidatorConfigFormSchema,
  config: Record<string, unknown>,
): string[] {
  const result = schema.safeParse(config)
  if (result.success) {
    return []
  }
  return result.error.issues.map((issue) => String(issue.path[0] ?? '')).filter((key) => key !== '')
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface ConfigFieldInputsProps {
  fields: readonly ConfigField[]
  values: Readonly<Record<string, string>>
  invalidKeys: readonly string[]
  disabled: boolean
  onChange: (key: string, value: string) => void
}

/**
 * type 별 config 입력 칸을 그린다.
 *
 * 라벨은 backend config 키 문자열 그대로다 — 한국어 사본을 두면 키가 바뀌는 날 화면만 조용히
 * 낡는다(제약 C1 과 같은 이유).
 */
function ConfigFieldInputs({
  fields,
  values,
  invalidKeys,
  disabled,
  onChange,
}: ConfigFieldInputsProps): JSX.Element {
  return (
    <div className="space-y-4">
      {fields.map((field) => {
        const invalid = invalidKeys.includes(field.key)
        return (
          <div key={field.key} className="space-y-1.5">
            <label htmlFor={`validator-config-${field.key}`} className="text-sm font-medium text-foreground">
              <span className="font-mono">{field.key}</span>
              {field.optional ? (
                <span className="ml-1 text-muted-foreground">{validatorLabels.form.optionalSuffix}</span>
              ) : null}
            </label>
            <input
              id={`validator-config-${field.key}`}
              type="text"
              value={values[field.key] ?? ''}
              onChange={(e) => onChange(field.key, e.target.value)}
              aria-invalid={invalid}
              disabled={disabled}
              className={cn(
                'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
                'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
                'disabled:cursor-not-allowed disabled:opacity-50',
                invalid && 'border-destructive focus-visible:border-destructive',
              )}
            />
          </div>
        )
      })}
    </div>
  )
}

interface UnknownTypeNoticeProps {
  config: Record<string, unknown>
}

/**
 * 프론트가 폼을 모르는 type 을 **읽기 전용으로 degrade** 한다 (엣지 E3 · 제약 C1).
 *
 * 폼을 추측해 그리면 낡은 화면이 거짓말을 한다 — 모르면 모른다고 말하고 저장된 값을 그대로 보인다.
 */
function UnknownTypeNotice({ config }: UnknownTypeNoticeProps): JSX.Element {
  return (
    <div className="space-y-3">
      <div className="rounded-md bg-warning/10 px-3 py-2 text-sm text-warning-text ring-1 ring-foreground/10">
        <p className="font-medium">{validatorLabels.form.unknownTypeTitle}</p>
        <p className="mt-1">{validatorLabels.form.unknownTypeDescription}</p>
      </div>
      <div className="space-y-1.5">
        <p className="text-sm font-medium text-foreground">{validatorLabels.form.rawConfigLegend}</p>
        <pre className="overflow-x-auto rounded-lg bg-muted px-3 py-2 font-mono text-xs text-muted-foreground">
          {JSON.stringify(config, null, 2)}
        </pre>
      </div>
    </div>
  )
}

interface TypeControlProps {
  mode: 'create' | 'edit'
  controlId: string
  type: string
  disabled: boolean
  onChange: (next: string) => void
}

/**
 * 규칙 종류 컨트롤.
 *
 * create 는 선택지를 주고 edit 는 고정 표시한다 — 수정 중에 종류를 바꾸면 baseline 이 다른
 * 종류의 키를 품은 채 넘어가 「무엇을 보존하는 병합인지」가 흐려진다(제약 C6).
 */
function TypeControl({ mode, controlId, type, disabled, onChange }: TypeControlProps): JSX.Element {
  if (mode === 'edit') {
    return (
      <div className="space-y-1.5">
        <p className="text-sm font-medium text-foreground" id={`${controlId}-label`}>
          {validatorLabels.form.typeFixedLabel}
        </p>
        <p className="font-mono text-sm text-muted-foreground" aria-labelledby={`${controlId}-label`}>
          {type}
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-1.5">
      <label htmlFor={controlId} className="text-sm font-medium text-foreground">
        {validatorLabels.form.typeLabel}
      </label>
      <select
        id={controlId}
        value={type}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className={cn(
          'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
          'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
          'disabled:cursor-not-allowed disabled:opacity-50',
        )}
      >
        <option value="">{validatorLabels.form.typePlaceholder}</option>
        {EDITABLE_TYPES.map((candidate) => (
          <option key={candidate} value={candidate}>
            {candidate}
          </option>
        ))}
      </select>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전환 규칙 추가·수정 다이얼로그.
 *
 * ### 접근성 이름 (즉사 계약 §2)
 * `role="dialog"` 의 접근 가능한 이름은 [DialogTitle] 이 `aria-labelledby` 로 만든다. 별도
 * `aria-label` 을 **더하지 않는다** — Radix 는 `aria-labelledby` 를 우선하므로 두 경로를 두면
 * 「지정한 이름과 실제 이름이 다른」 자리가 생긴다(`components/ui/confirm-dialog.tsx` KDoc 이
 * 실측으로 못박은 관례이고 `CreateIssueDialog` 가 같은 선례다). 제목 2종은 이 라우트의 다른
 * 다이얼로그와 겹치지 않는 고유 문자열이다.
 *
 * ### `h1` 을 만들지 않는다 (제약 C2)
 * 이 페이지의 `h1` 은 워크플로우 이름 하나여야 한다. [DialogTitle] 은 `h2` 다.
 *
 * ### 모르는 type (엣지 E3)
 * `validatorConfigFormSchema(type)` 가 `undefined` 면 폼을 추측해 그리지 않고 읽기 전용으로
 * degrade 하며 저장 버튼을 잠근다.
 */
export function ValidatorFormDialog({
  open,
  mode,
  initialValue,
  submitting = false,
  serverError,
  onSubmit,
  onCancel,
}: ValidatorFormDialogProps): JSX.Element {
  const typeControlId = useId()
  const baseline: Record<string, unknown> = initialValue?.config ?? {}

  const [type, setType] = useState<string>(initialValue?.type ?? '')
  const [values, setValues] = useState<Record<string, string>>(() => stringValuesOf(baseline))
  const [invalidKeys, setInvalidKeys] = useState<readonly string[]>([])
  const [formError, setFormError] = useState<string | undefined>(undefined)

  const schema = type === '' ? undefined : validatorConfigFormSchema(type)
  const fields = schema === undefined ? [] : configFieldsOf(schema)
  const canSubmit = schema !== undefined && !submitting

  /**
   * 저장 — 병합 → 검증 → 통과 시에만 상위로 올린다.
   *
   * `schema === undefined` 갈래는 타입 좁히기 용이다. 종류 미선택·모르는 종류·진행 중은
   * 셋 다 `canSubmit` 이 false 라 버튼이 이미 잠겨 있고, 여기서 그 상태를 또 판정하면
   * **도달할 수 없는 분기를 지키는 테스트**가 따라붙는다.
   */
  function handleSubmit(): void {
    if (schema === undefined) return
    const config = mergeConfig(baseline, fields, values)
    const broken = invalidKeysOf(schema, config)
    setInvalidKeys(broken)
    setFormError(broken.length > 0 ? validatorLabels.form.errorConfigRequired : undefined)
    if (broken.length > 0) return
    onSubmit({ type, config })
  }

  const alertMessage = serverError ?? formError
  const submitLabel = submitting
    ? validatorLabels.dialog.submittingButton
    : mode === 'create'
      ? validatorLabels.dialog.createButton
      : validatorLabels.dialog.saveButton

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) onCancel() }}>
      {/* aria-describedby={undefined} — Radix Description 불필요 경고 억제(형제 다이얼로그와 같은 이유) */}
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>
            {mode === 'create' ? validatorLabels.dialog.createTitle : validatorLabels.dialog.editTitle}
          </DialogTitle>
        </DialogHeader>

        {alertMessage !== undefined && (
          <div
            role="alert"
            className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive ring-1 ring-foreground/10"
          >
            {alertMessage}
          </div>
        )}

        <div className="space-y-4">
          <TypeControl
            mode={mode}
            controlId={typeControlId}
            type={type}
            disabled={submitting}
            onChange={(next) => { setType(next); setInvalidKeys([]); setFormError(undefined) }}
          />

          {schema !== undefined ? (
            <ConfigFieldInputs
              fields={fields}
              values={values}
              invalidKeys={invalidKeys}
              disabled={submitting}
              onChange={(key, value) => setValues((prev) => ({ ...prev, [key]: value }))}
            />
          ) : type !== '' ? (
            <UnknownTypeNotice config={baseline} />
          ) : null}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" size="lg" onClick={onCancel} disabled={submitting}>
            {validatorLabels.dialog.cancelButton}
          </Button>
          <Button type="button" variant="default" size="lg" onClick={handleSubmit} disabled={!canSubmit}>
            {submitLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
