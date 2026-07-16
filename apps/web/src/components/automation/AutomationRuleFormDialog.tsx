// 자동화 룰 생성/수정 Dialog — 트리거 5종 선택 + 트리거별 조건부 필드(cron/fields) 직렬화 (FR-AT-01 D6 Task 6)
// + 액션 리스트(5종, SET_FIX_VERSIONS는 FR-AT-07 PR-B)·실행 주체(actor) 편집 배선, config 비대칭(EC1) 직렬화/역직렬화 (FR-AT-02 D6 Task 6)
import type { JSX, KeyboardEvent } from 'react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { UseFormRegister } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { extractAutomationRuleErrorCode } from '@/api/automation-rules'
import {
  triggerTypeSchema,
  serializeTriggerConfig,
  serializeActionConfig,
  parseActionConfig,
  parseConditionExpression,
  serializeConditionExpression,
} from '@/api/automation-rules.types'
import type {
  AutomationRule,
  TriggerType,
  ActionRequestInput,
  ConditionNode,
  RuleConflict,
} from '@/api/automation-rules.types'
import {
  useCreateAutomationRule,
  useUpdateAutomationRule,
  AUTOMATION_RULES_QUERY_KEY,
} from '@/api/useAutomationRules'
import { ActionListEditor } from './ActionListEditor'
import type { ActionFormState } from './ActionConfigEditor'
import { ProjectMemberSelect } from './ProjectMemberSelect'
import { ConditionBuilder } from './ConditionBuilder'

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
  basicSectionLabel: '기본',
  triggerSectionLabel: '트리거',
  actionsSectionLabel: '액션',
  conditionSectionLabel: '조건',
  actorSectionLabel: '실행 주체',
  actorHint: '선택하지 않으면 룰을 만든 사용자로 자동 지정됩니다.',
  saveButton: '저장',
  cancelButton: '취소',
} as const

/** 폼 섹션 헤더 공통 클래스 — GadgetCatalogModal.tsx 섹션 라벨 선례 동형(design-review#1) */
const SECTION_HEADING_CLASS = 'mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground'

/** 트리거 타입 5종 한국어 라벨 — backend TriggerType enum 1:1 대응 */
const TRIGGER_LABELS: Record<TriggerType, string> = {
  ISSUE_CREATED: '이슈 생성',
  ISSUE_UPDATED: '이슈 수정',
  ISSUE_COMMENTED: '이슈 댓글 작성',
  SCHEDULED: '예약 실행 (cron)',
  WEBHOOK: '웹훅 호출',
}

/** automation BC errorCode → 한국어 메시지 매핑 (스펙 §4 FR-7, 401/500은 /review F2 추가) */
const AUTOMATION_ERROR_MESSAGES: Record<string, string> = {
  AUTOMATION_RULE_INVALID: '입력값을 확인해주세요.',
  AUTOMATION_MALFORMED_REQUEST: '요청 형식이 올바르지 않습니다.',
  AUTOMATION_RULE_VERSION_CONFLICT: '다른 곳에서 먼저 변경되었습니다. 최신 정보로 다시 열어 시도해주세요.',
  AUTOMATION_ACCESS_DENIED: '권한이 없습니다.',
  AUTOMATION_RULE_NOT_FOUND: '자동화 룰을 찾을 수 없습니다.',
  AUTOMATION_UNAUTHENTICATED: '세션이 만료되었습니다. 다시 로그인해주세요.',
  AUTOMATION_INTERNAL_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.',
  INVALID_CONDITION_EXPRESSION: '조건 표현식이 올바르지 않습니다. 필드/연산자/값을 확인해주세요.',
}

const DEFAULT_ERROR_MESSAGE = '저장에 실패했습니다. 다시 시도해주세요.'

/** 저장 실패 에러를 사용자 노출 메시지로 변환한다 (공유 errorCode 추출 헬퍼 경유). */
function resolveErrorMessage(error: unknown): string {
  const code = extractAutomationRuleErrorCode(error)
  if (code === null) return DEFAULT_ERROR_MESSAGE
  return AUTOMATION_ERROR_MESSAGES[code] ?? DEFAULT_ERROR_MESSAGE
}

/**
 * 저장 mutation 실패를 처리한다 (/review F1).
 *
 * 409(AUTOMATION_RULE_VERSION_CONFLICT)는 `editingRule.version`(부모 useState 스냅샷)이
 * stale해 폼을 열어둔 채 재시도해도 다시 409가 반복된다. 그래서 목록 쿼리를 invalidate해
 * refetch를 유도하고, 폼을 닫아(onOpenChange(false)) 사용자가 최신 version으로 재오픈하게
 * 한다 — 폼이 닫히므로 인라인 submitError 대신 토스트로 안내한다.
 * 그 외 에러(500/네트워크/Zod 등)는 재시도 가능하므로 폼을 유지한 채 submitError로 보여준다.
 */
function handleSubmitFailure(
  error: unknown,
  projectKey: string,
  queryClient: QueryClient,
  onOpenChange: (open: boolean) => void,
  setSubmitError: (message: string | null) => void,
): void {
  const message = resolveErrorMessage(error)
  if (extractAutomationRuleErrorCode(error) === 'AUTOMATION_RULE_VERSION_CONFLICT') {
    void queryClient.invalidateQueries({ queryKey: AUTOMATION_RULES_QUERY_KEY(projectKey) })
    toast.error(message)
    onOpenChange(false)
    return
  }
  setSubmitError(message)
}

/**
 * 저장 성공 응답의 conflicts를 `onConflicts` 콜백으로 1회 전달한다 — onWebhookToken 처리
 * (`response.webhookToken !== null` 가드)와 대칭 패턴이다(FR-AT-04 D6/D7 Task 3).
 *
 * create 응답은 `response.rule.conflicts`(중첩), update 응답은 `updated.conflicts`(최상위)로
 * 위치가 다르지만 이 헬퍼는 이미 꺼내진 배열만 받아 "비어있지 않을 때만 전달" 판정에 집중한다.
 * GET(목록/단건)은 backend가 `@JsonInclude(NON_NULL)`로 키 자체를 생략하므로 `undefined`도
 * "충돌 없음"으로 취급한다.
 */
function emitConflicts(conflicts: RuleConflict[] | undefined, onConflicts?: (c: RuleConflict[]) => void): void {
  if (conflicts !== undefined && conflicts.length > 0) onConflicts?.(conflicts)
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
// 액션 리스트 파싱/직렬화 헬퍼 — 수정 모드 초기값 로드(S7)/제출 직렬화(FR9) 전용
//
// ⚠️ config 비대칭(EC1) — editingRule.actions[].config는 응답 객체이므로 읽기는
// parseActionConfig, 제출은 폼 상태 → serializeActionConfig(JSON 문자열)로 명확히 분리한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * editingRule.actions(응답 config=객체)를 액션 리스트 폼 상태로 역직렬화한다(S7).
 * 생성 모드(editingRule 없음)는 빈 배열에서 시작한다(EC5 — 액션 없는 룰도 유효).
 */
function parseActionsFormState(rule: AutomationRule | null | undefined): ActionFormState[] {
  if (!hasEditingRule(rule)) return []
  return rule.actions.map((action) => ({ type: action.type, config: parseActionConfig(action.type, action.config) }))
}

/**
 * 액션 리스트 폼 상태를 요청 `ActionRequest[]`(config=JSON 문자열)로 직렬화한다(FR9).
 * 순서(배열 index)가 곧 저장될 position이므로 별도 변환 없이 그대로 매핑한다(S5).
 */
function serializeActionsFormState(actions: ActionFormState[]): ActionRequestInput[] {
  return actions.map((action) => ({ type: action.type, config: serializeActionConfig(action.type, action.config) }))
}

/** 액션 목록 저장 전 검증에서 발견된 위반 1건 — 위반 행 index + 사용자 노출 메시지 */
export interface ActionValidationError {
  readonly index: number
  readonly message: string
}

/**
 * 제출 직전 액션 목록을 검증한다(S8, load-bearing).
 *
 * {@link serializeActionsFormState}는 순수 매핑이라 에러 채널이 없고, RHF(react-hook-form)
 * `errors`도 `actions`를 덮지 않는다(`actions`는 RHF 스키마 밖 별도 state). SET_FIX_VERSIONS가
 * '교체' 모드인데 `versionIds`가 비어 있으면 {@link serializeActionConfig}가
 * `{"versionIds":[]}`를 그대로 내보내 기존 Fix Version을 전부 해제해버린다(FR-B1 재현) — 이
 * 조합만 저장을 거부한다. '전체 해제' 모드는 빈 목록이 곧 의도이므로 거부하지 않는다.
 */
// eslint-disable-next-line react-refresh/only-export-components -- S8 단위 검증용 순수 함수 공개 (ShareFilterDialog.tsx mergeShares 선례 동형, 파일 분리는 files 범위 밖)
export function validateActions(actions: ActionFormState[]): ActionValidationError[] {
  const errors: ActionValidationError[] = []
  actions.forEach((action, index) => {
    if (action.type !== 'SET_FIX_VERSIONS') return
    const mode = action.config.fixVersionsMode ?? 'replace'
    const versionIds = action.config.versionIds ?? []
    if (mode === 'replace' && versionIds.length === 0) {
      errors.push({
        index,
        message: `${index + 1}번째 액션 — 교체할 버전을 하나 이상 선택하거나 "전체 해제"를 선택해주세요.`,
      })
    }
  })
  return errors
}

// ─────────────────────────────────────────────────────────────────────────────
// condition 배선 헬퍼 — 수정 모드 초기값 로드(parseConditionExpression은 automation-rules.types에서
// 바로 가져다 쓴다)/제출 조건부 전송(G1) 전용. actions(S5, 항상 전체 교체 전송)와 달리 condition은
// "값이 있을 때만" 보내는 조건부 전송이라 별도 헬퍼로 분리한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 빈 조건 표현식의 정규형 문자열 — {@link serializeConditionExpression}이 빈 트리에 대해 반환하는 값과 동일. */
const EMPTY_CONDITION_EXPRESSION = '{"and":[]}'

/**
 * 조건 트리를 저장 payload(`{condition?: string}`)로 변환한다(G1 — actorPayload 선례 동형이되
 * "값이 있을 때만" 전송하는 actor와 달리 조건은 "지웠는지"까지 구분해야 한다).
 *
 * - 트리가 비어있지 않으면(직렬화 결과가 {@link EMPTY_CONDITION_EXPRESSION}이 아니면) 생성/수정
 *   공통으로 항상 `{condition: 직렬화값}`을 반환한다(set).
 * - 트리가 비어있고, 편집 대상 룰에 기존 condition이 있었다면(`null`이 아니었다면) 명시적으로
 *   `{condition: '{"and":[]}'}`(항상 참 정규형)를 보내 실제로 지운다(S5/[D1], clear).
 * - 트리가 비어있고(생성 모드이거나) 기존 condition이 이미 `null`이었다면 필드 자체를 생략한다 —
 *   PATCH 미지정은 "무변경" 컨벤션이므로(FR9), 굳이 보내 null↔"{"and":[]}"" 사이를 뒤집는 부작용을
 *   막는다(EC11).
 */
function resolveConditionPayload(
  conditionTree: ConditionNode,
  editingRule: AutomationRule | null | undefined,
): { condition?: string } {
  const serialized = serializeConditionExpression(conditionTree)
  if (serialized !== EMPTY_CONDITION_EXPRESSION) return { condition: serialized }
  if (hasEditingRule(editingRule) && editingRule.condition !== null) return { condition: EMPTY_CONDITION_EXPRESSION }
  return {}
}

/** create/update 두 body가 공통으로 담는 필드 — 이름·트리거타입(create 전용)·version(update 전용)은 제외. */
interface SharedSavePayload {
  triggerConfig: string
  actions: ActionRequestInput[]
  actorUserId?: string
  condition?: string
}

/**
 * onValid에서 create/update 두 분기가 공통으로 조립하는 필드(트리거설정·액션·실행주체·조건)를
 * 계산한다. 이름·트리거타입(create 전용)·version(update 전용)처럼 body 형태가 갈리는 필드는
 * 호출부(onValid)에서 각각 조립한다 — 이 계산까지 onValid가 도맡으면 함수가 §1 30줄 상한을
 * 넘기므로 분리한다.
 */
function buildSharedSavePayload(
  effectiveTriggerType: TriggerType,
  cron: string,
  fields: string[],
  actions: ActionFormState[],
  conditionTree: ConditionNode,
  actorUserId: string | null,
  editingRule: AutomationRule | null | undefined,
): SharedSavePayload {
  // 편집 모드는 editingRule.triggerConfig를 병합 시작점으로 넘겨 백엔드 미지 키를 보존한다
  // (코드리뷰 SUGGESTION 2 — 트리거 타입은 편집 모드에서 잠겨 있어 키 집합이 일관된다).
  const baseConfigJson = hasEditingRule(editingRule) ? editingRule.triggerConfig : undefined
  const triggerConfig = serializeTriggerConfig(effectiveTriggerType, { cron, fields }, baseConfigJson)
  // actorUserId는 사용자가 명시 선택했을 때만(null이 아닐 때만) body에 포함한다 — 생성 모드
  // 기본값은 미설정(백엔드 생성자 폴백), PATCH 미지정은 기존 값 유지 컨벤션이다(FR8).
  const actorPayload = actorUserId !== null ? { actorUserId } : {}
  // condition은 actions(항상 전송)와 달리 조건부 전송이다 — resolveConditionPayload 참고(G1).
  const conditionPayload = resolveConditionPayload(conditionTree, editingRule)
  return {
    triggerConfig,
    // actions는 항상 현재 폼 상태(빈 배열 포함)를 그대로 전송한다 — backend PATCH는 지정 시
    // 전체 교체 컨벤션이라 "변경 여부"를 별도 추적할 필요가 없다(EC5, 스펙 §Plan Task 6).
    actions: serializeActionsFormState(actions),
    ...actorPayload,
    ...conditionPayload,
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
  /** 저장 성공 응답에 규칙 충돌이 1건 이상 있으면 그 배열을 1회 전달하는 콜백 (FR-AT-04 D6/D7) */
  readonly onConflicts?: (conflicts: RuleConflict[]) => void
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
  readonly onConflicts?: (conflicts: RuleConflict[]) => void
}

function FormBody({
  projectKey,
  editingRule,
  onOpenChange,
  onWebhookToken,
  onConflicts,
}: FormBodyProps): JSX.Element {
  const initialConfig = hasEditingRule(editingRule)
    ? parseTriggerConfig(editingRule.triggerConfig)
    : { cron: '', fields: [] }

  const [fields, setFields] = useState<string[]>(initialConfig.fields)
  const [fieldDraft, setFieldDraft] = useState('')
  const [actions, setActions] = useState<ActionFormState[]>(() => parseActionsFormState(editingRule))
  const [conditionTree, setConditionTree] = useState<ConditionNode>(() =>
    parseConditionExpression(editingRule?.condition ?? null),
  )
  const [actorUserId, setActorUserId] = useState<string | null>(editingRule?.actorUserId ?? null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [actionsErrors, setActionsErrors] = useState<ActionValidationError[]>([])

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
    // S8 — 저장 직전 게이트(load-bearing). validateActions 참고 — 위반 시 서버 호출 자체를 막는다.
    const validationErrors = validateActions(actions)
    setActionsErrors(validationErrors)
    if (validationErrors.length > 0) return

    const sharedPayload = buildSharedSavePayload(
      effectiveTriggerType,
      values.cron,
      fields,
      actions,
      conditionTree,
      actorUserId,
      editingRule,
    )

    try {
      if (hasEditingRule(editingRule)) {
        const updated = await updateRule.mutateAsync({
          id: editingRule.id,
          body: { version: editingRule.version, name: values.name, ...sharedPayload },
        })
        emitConflicts(updated.conflicts, onConflicts)
      } else {
        const response = await createRule.mutateAsync({
          name: values.name,
          triggerType: values.triggerType,
          ...sharedPayload,
        })
        if (response.webhookToken !== null) {
          onWebhookToken?.(response.webhookToken)
        }
        emitConflicts(response.rule.conflicts, onConflicts)
      }
      onOpenChange(false)
    } catch (error) {
      // 409는 폼을 닫고 목록을 refetch해 사용자가 최신 version으로 재오픈하도록 유도한다
      // (스펙 §4 FR-7 · §6 E5 · §2 S4). 그 외 에러는 폼을 유지한다 — handleSubmitFailure 참고.
      handleSubmitFailure(error, projectKey, queryClient, onOpenChange, setSubmitError)
    }
  }

  return (
    <form onSubmit={handleSubmit(onValid)} noValidate>
      {/* 기본 — 이름 (design-review#1 섹션 그룹핑) */}
      <section className="mb-6">
        <h3 className={SECTION_HEADING_CLASS}>{labels.basicSectionLabel}</h3>
        <div>
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
      </section>

      {/* 트리거 — 수정 모드는 잠금(backend PatchAutomationRuleRequest에 triggerType 없음) */}
      <section className="mb-6">
        <h3 className={SECTION_HEADING_CLASS}>{labels.triggerSectionLabel}</h3>
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
      </section>

      {/* 액션 — 5종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK/SET_FIX_VERSIONS) 추가/삭제/순서변경(FR2·FR7) */}
      <section className="mb-6">
        <h3 className={SECTION_HEADING_CLASS}>{labels.actionsSectionLabel}</h3>
        <ActionListEditor projectKey={projectKey} value={actions} onChange={setActions} />
        {actionsErrors.length > 0 && (
          <ul className="mt-2 space-y-1">
            {actionsErrors.map((error) => (
              <li key={error.index} className="text-xs text-destructive" role="alert">
                {error.message}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* 조건 — 트리거가 발화해도 액션 실행 전 추가로 평가하는 필드 비교 조건(그룹/부정 포함),
          빈 트리는 항상 참(FR-AT-03). 저장 시 조건부 전송은 resolveConditionPayload(G1) 참고. */}
      <section className="mb-6">
        <h3 className={SECTION_HEADING_CLASS}>{labels.conditionSectionLabel}</h3>
        <ConditionBuilder projectKey={projectKey} value={conditionTree} onChange={setConditionTree} />
      </section>

      {/* 실행 주체 — 생성 기본값은 미설정(백엔드 생성자 폴백), 수정은 저장된 값 로드(FR8) */}
      <section className="mb-6">
        <h3 className={SECTION_HEADING_CLASS}>{labels.actorSectionLabel}</h3>
        <ProjectMemberSelect
          projectKey={projectKey}
          value={actorUserId}
          onChange={setActorUserId}
          label={labels.actorSectionLabel}
          id="automation-rule-actor"
        />
        <p className="text-xs text-muted-foreground mt-1">{labels.actorHint}</p>
      </section>

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
 * - `editingRule`이 있으면 수정 모드(이름·트리거설정·액션·조건·실행주체 PATCH, 트리거 타입은 잠금).
 *   없으면 생성 모드(이름+트리거타입+트리거설정+액션+조건+실행주체 POST).
 * - 폼은 기본(이름)·트리거(+설정)·액션·조건·실행 주체 5개 섹션으로 그룹핑되어 각 섹션 헤더로
 *   시각 계층을 확립한다(design-review#1).
 * - 트리거별 조건부 필드는 {@link TriggerConfigFields}로 분리 —
 *   SCHEDULED(cron 필수 사전검증)·ISSUE_UPDATED(fields 태그, 비면 전체 필드)·나머지(없음).
 * - 액션 리스트는 {@link ActionListEditor}(추가/삭제/순서변경)에 위임하고, 편집 초기값은
 *   {@link parseActionsFormState}(응답 config=객체)로, 제출은 {@link serializeActionsFormState}
 *   (config=JSON 문자열)로 변환한다 — 응답/요청 config 형태가 다른 비대칭(EC1)을 명확히 분리한다.
 * - 조건(condition)은 {@link ConditionBuilder}에 위임한다. 편집 초기값은 {@link parseConditionExpression}
 *   으로 로드하고, 제출은 {@link resolveConditionPayload}로 조건부 전송한다(FR-AT-03 D6/D7 Task 5,
 *   G1) — actions와 달리 "값이 있을 때만/지웠을 때만" 보내는 조건부 전송이라 항상 보내는 actions와
 *   구분된다. 빈 트리(항상 참)이고 편집 대상에 기존 condition도 없으면 필드 자체를 생략해 PATCH
 *   "무변경" 컨벤션을 지킨다(EC11).
 * - 실행 주체(actor)는 {@link ProjectMemberSelect}로 선택한다. 생성 모드 기본값은 미설정(null)
 *   이며, 사용자가 명시 선택했을 때만 `actorUserId`를 body에 포함한다(생성자가 프로젝트 멤버
 *   목록에 없을 수 있는 시스템 관리자 케이스를 회피, FR8).
 * - `open`/`editingRule.id` 조합을 key로 사용해 {@link FormBody}를 재마운트한다 —
 *   Dialog가 열린 채로 편집 대상이 바뀌어도 이전 입력(액션·조건·실행주체 포함)이 잔존하지 않는다
 *   (react-usestate-stale-key-prop 교훈, EC6/EC9).
 * - WEBHOOK 트리거 생성 성공 시 응답의 webhookToken 원문을 `onWebhookToken`으로 1회 전달한다.
 * - 저장 성공 응답에 규칙 충돌(conflicts)이 1건 이상 있으면 `onConflicts`로 1회 전달한다
 *   ({@link emitConflicts}, FR-AT-04 D6/D7 Task 3).
 */
export const AutomationRuleFormDialog = ({
  projectKey,
  open,
  onOpenChange,
  editingRule,
  onWebhookToken,
  onConflicts,
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
            onConflicts={onConflicts}
          />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
