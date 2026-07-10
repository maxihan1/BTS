// 자동화 룰 생성/수정 Dialog — 트리거 5종 선택 + 트리거별 조건부 필드(cron/fields) 직렬화 (FR-AT-01 D6 Task 6)
import type { JSX, KeyboardEvent } from 'react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { UseFormRegister } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { extractAutomationRuleErrorCode } from '@/api/automation-rules'
import { triggerTypeSchema, serializeTriggerConfig } from '@/api/automation-rules.types'
import type { AutomationRule, TriggerType } from '@/api/automation-rules.types'
import {
  useCreateAutomationRule,
  useUpdateAutomationRule,
  AUTOMATION_RULES_QUERY_KEY,
} from '@/api/useAutomationRules'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (WebhookTokenModal.tsx 선례, i18n 미도입 BC 관례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  createTitle: '자동화 룰 추가',
  editTitle: '자동화 룰 수정',
  nameLabel: '이름',
  namePlaceholder: '룰 이름을 입력하세요',
  triggerLabel: '트리거',
  cronLabel: 'cron 표현식',
  cronHint: '6필드 cron, 예: `0 0 9 * * *` = 매일 09:00',
  fieldsLabel: '특정 필드 (선택)',
  fieldsDescription: '특정 필드 변경 시만 발화합니다. 비워두면 전체 필드 변경에 반응합니다.',
  fieldsPlaceholder: '필드 키 입력 후 Enter',
  saveButton: '저장',
  cancelButton: '취소',
} as const

/** 트리거 타입 5종 한국어 라벨 — backend TriggerType enum 1:1 대응 */
const TRIGGER_LABELS: Record<TriggerType, string> = {
  ISSUE_CREATED: '이슈 생성',
  ISSUE_UPDATED: '이슈 수정',
  ISSUE_COMMENTED: '이슈 댓글 작성',
  SCHEDULED: '예약 실행 (cron)',
  WEBHOOK: '웹훅 호출',
}

/** automation BC errorCode → 한국어 메시지 매핑 (스펙 §4 FR-7) */
const AUTOMATION_ERROR_MESSAGES: Record<string, string> = {
  AUTOMATION_RULE_INVALID: '입력값을 확인해주세요.',
  AUTOMATION_MALFORMED_REQUEST: '요청 형식이 올바르지 않습니다.',
  AUTOMATION_RULE_VERSION_CONFLICT: '다른 곳에서 먼저 변경되었습니다. 최신 정보를 다시 불러온 뒤 시도해주세요.',
  AUTOMATION_ACCESS_DENIED: '권한이 없습니다.',
  AUTOMATION_RULE_NOT_FOUND: '자동화 룰을 찾을 수 없습니다.',
}

const DEFAULT_ERROR_MESSAGE = '저장에 실패했습니다. 다시 시도해주세요.'

/** 저장 실패 에러를 사용자 노출 메시지로 변환한다 (공유 errorCode 추출 헬퍼 경유). */
function resolveErrorMessage(error: unknown): string {
  const code = extractAutomationRuleErrorCode(error)
  if (code === null) return DEFAULT_ERROR_MESSAGE
  return AUTOMATION_ERROR_MESSAGES[code] ?? DEFAULT_ERROR_MESSAGE
}

/**
 * editingRule prop이 실제 값을 가지는지 판정하는 타입 가드.
 * `editingRule !== undefined && editingRule !== null` 반복 대신 이 함수를 조건식에 직접 호출하면
 * TypeScript가 호출 지점에서 editingRule을 `AutomationRule`로 narrowing한다.
 */
function hasEditingRule(rule: AutomationRule | null | undefined): rule is AutomationRule {
  return rule !== undefined && rule !== null
}

// ─────────────────────────────────────────────────────────────────────────────
// triggerConfig 파싱 헬퍼 — 수정 모드 초기값 로드용 (serializeTriggerConfig의 역방향)
// ─────────────────────────────────────────────────────────────────────────────

interface ParsedTriggerConfig {
  cron: string
  fields: string[]
}

/**
 * 저장된 triggerConfig JSON 문자열을 폼 초기값(cron/fields)으로 역직렬화한다.
 * 파싱 실패 시 콘솔에 에러를 남기고 빈 값으로 폴백한다 — 화면이 깨지지 않도록 한다.
 */
function parseTriggerConfig(triggerConfig: string): ParsedTriggerConfig {
  try {
    const parsed: unknown = JSON.parse(triggerConfig)
    if (typeof parsed !== 'object' || parsed === null) {
      return { cron: '', fields: [] }
    }
    const obj = parsed as Record<string, unknown>
    const cron = typeof obj['cron'] === 'string' ? obj['cron'] : ''
    const fields = Array.isArray(obj['fields'])
      ? obj['fields'].filter((field): field is string => typeof field === 'string')
      : []
    return { cron, fields }
  } catch (error) {
    console.error('automation triggerConfig 파싱 실패', error)
    return { cron: '', fields: [] }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 폼 스키마 — cron은 SCHEDULED 트리거일 때만 필수(프론트 사전검증)
// ─────────────────────────────────────────────────────────────────────────────

const formSchema = z
  .object({
    name: z.string().min(1, '이름을 입력해주세요.'),
    triggerType: triggerTypeSchema,
    cron: z.string(),
  })
  .refine((values) => values.triggerType !== 'SCHEDULED' || values.cron.trim().length > 0, {
    message: 'cron 표현식을 입력해주세요.',
    path: ['cron'],
  })

type FormValues = z.infer<typeof formSchema>

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** AutomationRuleFormDialog props */
export interface AutomationRuleFormDialogProps {
  /** 자동화 룰이 소속된 프로젝트 키 */
  readonly projectKey: string
  /** 다이얼로그 열림 여부 */
  readonly open: boolean
  /** 다이얼로그 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
  /** 값이 있으면 수정 모드로 동작한다 — 트리거 타입은 수정 불가(backend PATCH DTO에 없음) */
  readonly editingRule?: AutomationRule | null
  /** WEBHOOK 트리거 생성 성공 시 응답에 동봉된 원문 토큰을 1회 전달하는 콜백 */
  readonly onWebhookToken?: (token: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 트리거별 조건부 필드 서브컴포넌트 — SCHEDULED(cron) / ISSUE_UPDATED(fields) / 나머지(없음)
// ─────────────────────────────────────────────────────────────────────────────

interface TriggerConfigFieldsProps {
  readonly triggerType: TriggerType
  readonly register: UseFormRegister<FormValues>
  readonly cronError?: string
  readonly fields: string[]
  readonly fieldDraft: string
  readonly onFieldDraftChange: (value: string) => void
  readonly onFieldDraftKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void
  readonly onRemoveField: (field: string) => void
}

function TriggerConfigFields({
  triggerType,
  register,
  cronError,
  fields,
  fieldDraft,
  onFieldDraftChange,
  onFieldDraftKeyDown,
  onRemoveField,
}: TriggerConfigFieldsProps): JSX.Element | null {
  if (triggerType === 'SCHEDULED') {
    return (
      <div className="mb-4">
        <label htmlFor="automation-rule-cron" className="block text-sm font-medium mb-1">
          {labels.cronLabel}
        </label>
        <input
          id="automation-rule-cron"
          type="text"
          aria-label={labels.cronLabel}
          data-testid="automation-rule-cron-input"
          autoComplete="off"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm font-mono outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          {...register('cron')}
        />
        <p className="text-xs text-muted-foreground mt-1">{labels.cronHint}</p>
        {cronError !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {cronError}
          </p>
        )}
      </div>
    )
  }

  if (triggerType === 'ISSUE_UPDATED') {
    return (
      <div className="mb-4">
        <label htmlFor="automation-rule-fields" className="block text-sm font-medium mb-1">
          {labels.fieldsLabel}
        </label>
        <p className="text-xs text-muted-foreground mb-1">{labels.fieldsDescription}</p>
        <input
          id="automation-rule-fields"
          type="text"
          aria-label={labels.fieldsLabel}
          data-testid="automation-rule-fields-input"
          placeholder={labels.fieldsPlaceholder}
          autoComplete="off"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
          value={fieldDraft}
          onChange={(event) => { onFieldDraftChange(event.target.value) }}
          onKeyDown={onFieldDraftKeyDown}
        />
        {fields.length > 0 && (
          <ul className="mt-2 flex flex-wrap gap-1.5" aria-label="선택된 필드 목록">
            {fields.map((field) => (
              <li key={field}>
                <button
                  type="button"
                  data-testid={`automation-rule-field-chip-${field}`}
                  aria-label={`${field} 제거`}
                  onClick={() => { onRemoveField(field) }}
                  className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs"
                >
                  {field}
                  <span aria-hidden="true">×</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    )
  }

  return null
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 폼 컴포넌트 (key prop 재마운트를 위해 분리)
// ─────────────────────────────────────────────────────────────────────────────

interface FormBodyProps {
  readonly projectKey: string
  readonly editingRule?: AutomationRule | null
  readonly onOpenChange: (open: boolean) => void
  readonly onWebhookToken?: (token: string) => void
}

function FormBody({ projectKey, editingRule, onOpenChange, onWebhookToken }: FormBodyProps): JSX.Element {
  const initialConfig = hasEditingRule(editingRule)
    ? parseTriggerConfig(editingRule.triggerConfig)
    : { cron: '', fields: [] }

  const [fields, setFields] = useState<string[]>(initialConfig.fields)
  const [fieldDraft, setFieldDraft] = useState('')
  const [submitError, setSubmitError] = useState<string | null>(null)

  const queryClient = useQueryClient()
  const createRule = useCreateAutomationRule(projectKey)
  const updateRule = useUpdateAutomationRule(projectKey)

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: editingRule?.name ?? '',
      triggerType: editingRule?.triggerType ?? 'ISSUE_CREATED',
      cron: initialConfig.cron,
    },
  })

  const watchedTriggerType = watch('triggerType')
  const effectiveTriggerType = hasEditingRule(editingRule) ? editingRule.triggerType : watchedTriggerType
  const isEditMode = hasEditingRule(editingRule)

  function addField(): void {
    const trimmed = fieldDraft.trim()
    if (trimmed === '') return
    setFields((prev) => (prev.includes(trimmed) ? prev : [...prev, trimmed]))
    setFieldDraft('')
  }

  function handleFieldDraftKeyDown(event: KeyboardEvent<HTMLInputElement>): void {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      addField()
    }
  }

  function removeField(field: string): void {
    setFields((prev) => prev.filter((item) => item !== field))
  }

  async function onValid(values: FormValues): Promise<void> {
    setSubmitError(null)
    // 편집 모드는 editingRule.triggerConfig를 병합 시작점으로 넘겨 백엔드 미지 키를 보존한다
    // (코드리뷰 SUGGESTION 2 — 트리거 타입은 편집 모드에서 잠겨 있어 키 집합이 일관된다).
    const baseConfigJson = hasEditingRule(editingRule) ? editingRule.triggerConfig : undefined
    const triggerConfig = serializeTriggerConfig(effectiveTriggerType, { cron: values.cron, fields }, baseConfigJson)

    try {
      if (hasEditingRule(editingRule)) {
        await updateRule.mutateAsync({
          id: editingRule.id,
          body: { version: editingRule.version, name: values.name, triggerConfig },
        })
      } else {
        const response = await createRule.mutateAsync({
          name: values.name,
          triggerType: values.triggerType,
          triggerConfig,
        })
        if (response.webhookToken !== null) {
          onWebhookToken?.(response.webhookToken)
        }
      }
      onOpenChange(false)
    } catch (error) {
      // 409(OCC 버전 충돌)는 목록을 invalidate해 refetch를 유도한다 — 그렇지 않으면 stale
      // version으로 재시도가 반복되어 무한 409에 빠진다 (스펙 §4 FR-7 · §6 E5 · §2 S4).
      if (extractAutomationRuleErrorCode(error) === 'AUTOMATION_RULE_VERSION_CONFLICT') {
        void queryClient.invalidateQueries({ queryKey: AUTOMATION_RULES_QUERY_KEY(projectKey) })
      }
      setSubmitError(resolveErrorMessage(error))
    }
  }

  return (
    <form onSubmit={handleSubmit(onValid)} noValidate>
      {/* 이름 */}
      <div className="mb-4">
        <label htmlFor="automation-rule-name" className="block text-sm font-medium mb-1">
          {labels.nameLabel}
        </label>
        <input
          id="automation-rule-name"
          type="text"
          aria-label={labels.nameLabel}
          placeholder={labels.namePlaceholder}
          autoComplete="off"
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
          {...register('name')}
        />
        {errors.name !== undefined && (
          <p className="text-xs text-destructive mt-1" role="alert">
            {errors.name.message}
          </p>
        )}
      </div>

      {/* 트리거 — 수정 모드는 잠금(backend PatchAutomationRuleRequest에 triggerType 없음) */}
      <div className="mb-4">
        <label htmlFor="automation-rule-trigger" className="block text-sm font-medium mb-1">
          {labels.triggerLabel}
        </label>
        <select
          id="automation-rule-trigger"
          aria-label={labels.triggerLabel}
          data-testid="automation-rule-trigger-select"
          disabled={isEditMode}
          className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 disabled:opacity-50 disabled:cursor-not-allowed"
          {...register('triggerType')}
        >
          {triggerTypeSchema.options.map((type) => (
            <option key={type} value={type}>
              {TRIGGER_LABELS[type]}
            </option>
          ))}
        </select>
      </div>

      <TriggerConfigFields
        triggerType={effectiveTriggerType}
        register={register}
        cronError={errors.cron?.message}
        fields={fields}
        fieldDraft={fieldDraft}
        onFieldDraftChange={setFieldDraft}
        onFieldDraftKeyDown={handleFieldDraftKeyDown}
        onRemoveField={removeField}
      />

      {/* 서버 오류 */}
      {submitError !== null && (
        <p className="text-sm text-destructive mb-4" role="alert">
          {submitError}
        </p>
      )}

      {/* 액션 버튼 */}
      <div className="flex justify-end gap-2 mt-6">
        <Button
          type="button"
          variant="outline"
          size="sm"
          data-testid="automation-rule-cancel-button"
          onClick={() => { onOpenChange(false) }}
        >
          {labels.cancelButton}
        </Button>
        <Button type="submit" size="sm" data-testid="automation-rule-save-button">
          {labels.saveButton}
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AutomationRuleFormDialog (외부 공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 생성/수정 겸용 Dialog.
 *
 * - `editingRule`이 있으면 수정 모드(이름·트리거설정만 PATCH, 트리거 타입은 잠금).
 *   없으면 생성 모드(이름+트리거타입+트리거설정 POST).
 * - 트리거별 조건부 필드는 {@link TriggerConfigFields}로 분리 —
 *   SCHEDULED(cron 필수 사전검증)·ISSUE_UPDATED(fields 태그, 비면 전체 필드)·나머지(없음).
 * - `open`/`editingRule.id` 조합을 key로 사용해 {@link FormBody}를 재마운트한다 —
 *   Dialog가 열린 채로 편집 대상이 바뀌어도 이전 입력이 잔존하지 않는다
 *   (react-usestate-stale-key-prop 교훈).
 * - WEBHOOK 트리거 생성 성공 시 응답의 webhookToken 원문을 `onWebhookToken`으로 1회 전달한다.
 */
export const AutomationRuleFormDialog = ({
  projectKey,
  open,
  onOpenChange,
  editingRule,
  onWebhookToken,
}: AutomationRuleFormDialogProps): JSX.Element => {
  const formKey = `${open ? 'open' : 'closed'}:${editingRule?.id ?? 'new'}`
  const title = hasEditingRule(editingRule) ? labels.editTitle : labels.createTitle

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          role="dialog"
          aria-modal="true"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 overflow-y-auto max-h-[90vh]"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">{title}</DialogPrimitive.Title>

          <FormBody
            key={formKey}
            projectKey={projectKey}
            editingRule={editingRule}
            onOpenChange={onOpenChange}
            onWebhookToken={onWebhookToken}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
