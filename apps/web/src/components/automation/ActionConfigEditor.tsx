// 액션 1건의 타입별 조건부 편집기 — 9종 (FR-AT-02 D6 Task 4, SET_FIX_VERSIONS는 FR-AT-07 PR-B, 전환·생성·연결·관찰자는 R9 단계 C)
import type { ChangeEvent, JSX, KeyboardEvent } from 'react'
import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { actionTypeSchema, parseActionConfig } from '@/api/automation-rules.types'
import type { ActionType, ActionConfigFormState, WatcherFromField, WebhookHeaderEntry } from '@/api/automation-rules.types'
import { SUBTASK_HIERARCHY_LEVEL } from '@/api/issue-types'
import { useVersions } from '@/hooks/use-versions'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { extractStatusOptions, useProjectWorkflows } from '@/hooks/use-workflows'
import { issueLinkStrings } from '@/i18n/ko'
import { VersionMultiSelect } from '@/components/issue/VersionMultiSelect'
import { ProjectMemberSelect } from './ProjectMemberSelect'

// ─────────────────────────────────────────────────────────────────────────────
// 폼 상태 타입 — T1의 ActionConfigFormState(config 전용)에 type을 더한 액션 1건 폼 상태.
// 직렬화(JSON 문자열화)는 이 컴포넌트가 하지 않는다 — 상위(AutomationRuleFormDialog)가
// serializeActionConfig를 호출한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 액션 1건의 폼 상태 — {@link ActionType}과 그 타입에 대응하는 config 폼 상태의 쌍 */
export interface ActionFormState {
  readonly type: ActionType
  readonly config: ActionConfigFormState
}

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — automation BC 관례 고정 한국어 (i18n 미도입)
// ─────────────────────────────────────────────────────────────────────────────

const TEXT = {
  actionTypeLabel: '액션 유형',
  setFieldFieldLabel: '필드',
  setFieldValueLabel: '값',
  labelsPlaceholder: '라벨 입력 후 Enter',
  labelsListLabel: '선택된 라벨 목록',
  assigneeLabel: '담당자',
  commentBodyLabel: '댓글 본문',
  commentTemplateHint: '{{ issue.key }} 등 템플릿 변수를 본문에 사용할 수 있습니다.',
  webhookUrlLabel: 'URL',
  webhookMethodLabel: '메서드',
  webhookHeadersLabel: '헤더',
  webhookHeaderKeyLabel: '헤더 이름',
  webhookHeaderValueLabel: '헤더 값',
  webhookAddHeaderButton: '헤더 추가',
  webhookBodyLabel: '본문',
  fixVersionsModeLabel: '적용 방식',
  fixVersionsReplaceLabel: '선택한 버전으로 교체',
  fixVersionsClearLabel: '전체 해제',
  fixVersionsLoading: '버전 목록을 불러오는 중...',
  fixVersionsError: '버전 목록을 불러오지 못했습니다.',
  transitionToStateLabel: '대상 상태',
  transitionStatePlaceholder: '상태 선택',
  statesLoading: '상태를 불러오는 중...',
  statesError: '워크플로 상태를 불러오지 못했습니다.',
  createSummaryLabel: '요약',
  templateHint: '{{ issue.key }} 등 템플릿 변수를 사용할 수 있습니다.',
  createTypeLabel: '이슈 유형',
  createAsSubtaskLabel: '트리거 이슈의 하위 작업으로 만들기',
  typesLoading: '이슈 유형을 불러오는 중...',
  typesError: '이슈 유형을 불러오지 못했습니다.',
  linkTypeLabel: '연결 유형',
  linkTargetLabel: '대상 이슈 키',
  linkTargetHint: '이 프로젝트의 이슈만 연결합니다. {{ }} 템플릿 변수를 사용할 수 있습니다.',
  watcherSourceLabel: '관찰자로 추가할 사람',
  watcherUserLabel: '사용자',
} as const

/** LINK_ISSUE 연결 유형 — backend LinkType 소문자 코드(IssueLinksPanel 과 같은 목록 · 라벨) */
const LINK_TYPES = [
  { value: 'blocks', label: issueLinkStrings.linkTypeBlocks },
  { value: 'relates', label: issueLinkStrings.linkTypeRelates },
  { value: 'duplicates', label: issueLinkStrings.linkTypeDuplicates },
  { value: 'clones', label: issueLinkStrings.linkTypeClones },
] as const

/** ADD_WATCHER 관찰자 출처 select 값 — `user` 는 지정 사용자(config.fromField = null) */
type WatcherSource = WatcherFromField | 'user'
const WATCHER_SOURCES: readonly { readonly value: WatcherSource; readonly label: string }[] = [
  { value: 'assignee', label: '담당자' },
  { value: 'reporter', label: '보고자' },
  { value: 'user', label: '지정 사용자' },
]

/** 입력 필드 공통 Tailwind 클래스 — text/select 위젯 전반에서 재사용 */
const FIELD_CLASS =
  'w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20'

/** 액션 타입 9종 한국어 라벨 — backend ActionType enum 1:1 대응 (SET_FIX_VERSIONS는 FR-AT-07 PR-B, 뒤 4종은 R9 단계 C) */
const ACTION_TYPE_LABELS: Record<ActionType, string> = {
  SET_FIELD: '필드 값 설정',
  ASSIGN: '담당자 지정',
  ADD_COMMENT: '댓글 추가',
  CALL_WEBHOOK: '웹훅 호출',
  SET_FIX_VERSIONS: '수정 예정 버전 설정',
  TRANSITION: '이슈 전환',
  CREATE_ISSUE: '이슈 생성',
  LINK_ISSUE: '이슈 연결',
  ADD_WATCHER: '관찰자 추가',
}

/** SET_FIELD가 지원하는 필드 6종 — backend SetFieldAction 계약(FR3, 스펙 §백엔드 계약) */
const SET_FIELD_FIELDS = ['summary', 'description', 'environment', 'priority', 'impact', 'labels'] as const
type KnownSetField = (typeof SET_FIELD_FIELDS)[number]

/** SET_FIELD 필드 6종 한국어 라벨 */
const SET_FIELD_LABELS: Record<KnownSetField, string> = {
  summary: '요약',
  description: '설명',
  environment: '환경',
  priority: '우선순위',
  impact: '영향도',
  labels: '라벨',
}

/** SET_FIELD 새 액션 생성 시 기본 필드 — 큐레이션 6종 중 첫 항목 */
const DEFAULT_SET_FIELD: KnownSetField = 'summary'

/** priority select 옵션(1~5) — backend Int 1..5 제약(EC2, 범위 밖 값 생성 불가) */
const PRIORITY_OPTIONS = [1, 2, 3, 4, 5] as const
/** impact select 옵션(1~3) — backend Int 1..3 제약(EC2) */
const IMPACT_OPTIONS = [1, 2, 3] as const
/** priority/impact select 기본값 — 범위 내 최솟값 */
const DEFAULT_NUMERIC_VALUE = 1

/** CALL_WEBHOOK method select 옵션 — backend 지원 HTTP 메서드 5종 */
const WEBHOOK_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const
/** CALL_WEBHOOK method 기본값 — backend Action.DEFAULT_METHOD와 동일 */
const DEFAULT_WEBHOOK_METHOD = 'POST'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** field가 SET_FIELD 큐레이션 6종에 속하는지 판별한다(EC10 unknown field 분기 기준) */
function isKnownSetField(field: string): field is KnownSetField {
  return (SET_FIELD_FIELDS as readonly string[]).includes(field)
}

type SetFieldWidgetKind = 'text' | 'priority' | 'impact' | 'labels'

/** field에 대응하는 값 위젯 종류를 판정한다. 6종 밖 field는 텍스트 위젯으로 fallback한다(EC10) */
function resolveSetFieldWidgetKind(field: string): SetFieldWidgetKind {
  if (field === 'priority') return 'priority'
  if (field === 'impact') return 'impact'
  if (field === 'labels') return 'labels'
  return 'text'
}

/** value가 문자열 배열인지 타입 가드로 확인한다(labels 위젯 전용) */
function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

/** raw select 값을 안전하게 {@link ActionType}으로 변환한다 — select 옵션은 항상 유효값이라 fallback은 이론상 미도달 */
function toActionType(raw: string): ActionType {
  const parsed = actionTypeSchema.safeParse(raw)
  return parsed.success ? parsed.data : 'SET_FIELD'
}

/**
 * 액션 타입 전환 시 적용할 기본 config 폼 상태.
 * ASSIGN/ADD_COMMENT/CALL_WEBHOOK은 {@link parseActionConfig}(빈 객체)의 기본값을 재사용하고,
 * SET_FIELD/SET_FIX_VERSIONS는 명시 분기를 둔다 — select가 유효한 초기값을 갖도록 한다.
 * SET_FIX_VERSIONS를 {@link parseActionConfig}(빈 객체)로 떨어뜨리면 `versionIds` 부재를
 * 빈 배열로 채우고 그 빈 배열을 근거로 `clear` 모드로 오판한다(fail-closed 위반) — 반드시
 * `replace` 모드를 명시해야 한다(Maxi 확정 FR-6, undefined ≡ replace).
 */
function defaultConfigForType(type: ActionType): ActionConfigFormState {
  if (type === 'SET_FIELD') {
    return { field: DEFAULT_SET_FIELD, value: '' }
  }
  if (type === 'SET_FIX_VERSIONS') {
    return { fixVersionsMode: 'replace', versionIds: [] }
  }
  return parseActionConfig(type, {})
}

/** SET_FIELD field 전환 시 적용할 기본 value — 위젯 종류에 맞는 타입으로 정규화한다 */
function defaultValueForField(field: string, previousValue: unknown): unknown {
  switch (resolveSetFieldWidgetKind(field)) {
    case 'priority':
      return typeof previousValue === 'number' && previousValue >= 1 && previousValue <= 5
        ? previousValue
        : DEFAULT_NUMERIC_VALUE
    case 'impact':
      return typeof previousValue === 'number' && previousValue >= 1 && previousValue <= 3
        ? previousValue
        : DEFAULT_NUMERIC_VALUE
    case 'labels':
      return isStringArray(previousValue) ? previousValue : []
    case 'text':
    default:
      return typeof previousValue === 'string' ? previousValue : ''
  }
}

/** CALL_WEBHOOK config에서 headers 쌍 배열을 안전하게 읽는다(다른 3종 config에는 headers가 없다) */
function resolveHeaders(config: ActionConfigFormState): WebhookHeaderEntry[] {
  return config.headers ?? []
}

/** `index` 위치의 헤더 쌍을 `patch`로 부분 갱신한 새 배열을 반환한다. 범위 밖 index는 원본을 그대로 반환한다. */
function updateHeaderAt(
  headers: readonly WebhookHeaderEntry[],
  index: number,
  patch: Partial<WebhookHeaderEntry>,
): WebhookHeaderEntry[] {
  const current = headers[index]
  if (current === undefined) return [...headers]
  const next = [...headers]
  next[index] = { ...current, ...patch }
  return next
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입별 조건부 필드 서브컴포넌트 — TriggerConfigFields 선례 동형
// ─────────────────────────────────────────────────────────────────────────────

interface TypedFieldsProps {
  readonly config: ActionConfigFormState
  readonly onChange: (next: ActionFormState) => void
  readonly idPrefix: string
}

interface SetFieldFieldsProps extends TypedFieldsProps {
  readonly labelDraft: string
  readonly onLabelDraftChange: (value: string) => void
}

/**
 * SET_FIELD 전용 필드 — 필드 6종 드롭다운 + 필드 타입에 맞는 값 위젯.
 *
 * EC10(6종 밖 field) — 기존 룰에 저장된 unknown field는 드롭다운 맨 앞에 raw 문자열 그대로
 * 추가 옵션으로 노출해(값 보존) 선택된 상태를 유지하고, 값 위젯은 텍스트로 fallback한다.
 */
function SetFieldFields({ config, onChange, idPrefix, labelDraft, onLabelDraftChange }: SetFieldFieldsProps): JSX.Element {
  const currentField = config.field ?? DEFAULT_SET_FIELD
  const widgetKind = resolveSetFieldWidgetKind(currentField)
  const unknownField = isKnownSetField(currentField) ? null : currentField

  function handleFieldChange(event: ChangeEvent<HTMLSelectElement>): void {
    const nextField = event.target.value
    onChange({ type: 'SET_FIELD', config: { field: nextField, value: defaultValueForField(nextField, config.value) } })
  }

  function handleTextValueChange(event: ChangeEvent<HTMLInputElement>): void {
    onChange({ type: 'SET_FIELD', config: { ...config, value: event.target.value } })
  }

  function handleNumericValueChange(event: ChangeEvent<HTMLSelectElement>): void {
    onChange({ type: 'SET_FIELD', config: { ...config, value: Number(event.target.value) } })
  }

  function handleAddLabel(): void {
    const trimmed = labelDraft.trim()
    if (trimmed === '') return
    const currentLabels = isStringArray(config.value) ? config.value : []
    if (!currentLabels.includes(trimmed)) {
      onChange({ type: 'SET_FIELD', config: { ...config, value: [...currentLabels, trimmed] } })
    }
    onLabelDraftChange('')
  }

  function handleLabelDraftKeyDown(event: KeyboardEvent<HTMLInputElement>): void {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      handleAddLabel()
    }
  }

  function handleRemoveLabel(label: string): void {
    const currentLabels = isStringArray(config.value) ? config.value : []
    onChange({ type: 'SET_FIELD', config: { ...config, value: currentLabels.filter((item) => item !== label) } })
  }

  return (
    <div className="space-y-3">
      <div>
        <label htmlFor={`${idPrefix}-set-field-field`} className="block text-sm font-medium mb-1">
          {TEXT.setFieldFieldLabel}
        </label>
        <select
          id={`${idPrefix}-set-field-field`}
          aria-label={TEXT.setFieldFieldLabel}
          value={currentField}
          onChange={handleFieldChange}
          className={FIELD_CLASS}
        >
          {unknownField !== null && <option value={unknownField}>{unknownField}</option>}
          {SET_FIELD_FIELDS.map((field) => (
            <option key={field} value={field}>
              {SET_FIELD_LABELS[field]}
            </option>
          ))}
        </select>
      </div>

      {widgetKind === 'text' && (
        <div>
          <label htmlFor={`${idPrefix}-set-field-value`} className="block text-sm font-medium mb-1">
            {TEXT.setFieldValueLabel}
          </label>
          <input
            id={`${idPrefix}-set-field-value`}
            type="text"
            aria-label={TEXT.setFieldValueLabel}
            value={typeof config.value === 'string' ? config.value : ''}
            onChange={handleTextValueChange}
            className={FIELD_CLASS}
          />
        </div>
      )}

      {(widgetKind === 'priority' || widgetKind === 'impact') && (
        <div>
          <label htmlFor={`${idPrefix}-set-field-value`} className="block text-sm font-medium mb-1">
            {TEXT.setFieldValueLabel}
          </label>
          <select
            id={`${idPrefix}-set-field-value`}
            aria-label={TEXT.setFieldValueLabel}
            value={String(typeof config.value === 'number' ? config.value : DEFAULT_NUMERIC_VALUE)}
            onChange={handleNumericValueChange}
            className={FIELD_CLASS}
          >
            {(widgetKind === 'priority' ? PRIORITY_OPTIONS : IMPACT_OPTIONS).map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </div>
      )}

      {widgetKind === 'labels' && (
        <div>
          <label htmlFor={`${idPrefix}-set-field-value-labels`} className="block text-sm font-medium mb-1">
            {TEXT.setFieldValueLabel}
          </label>
          <input
            id={`${idPrefix}-set-field-value-labels`}
            type="text"
            aria-label={TEXT.setFieldValueLabel}
            placeholder={TEXT.labelsPlaceholder}
            value={labelDraft}
            onChange={(event) => {
              onLabelDraftChange(event.target.value)
            }}
            onKeyDown={handleLabelDraftKeyDown}
            className={FIELD_CLASS}
          />
          {isStringArray(config.value) && config.value.length > 0 && (
            <ul className="mt-2 flex flex-wrap gap-1.5" aria-label={TEXT.labelsListLabel}>
              {config.value.map((label) => (
                <li key={label}>
                  <Button
                    type="button"
                    variant="ghost"
                    size="xs"
                    aria-label={`${label} 제거`}
                    onClick={() => {
                      handleRemoveLabel(label)
                    }}
                    className="rounded-full bg-muted px-2 text-xs"
                  >
                    {label}
                    <span aria-hidden="true">×</span>
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}

interface AssignFieldProps extends TypedFieldsProps {
  readonly projectKey: string
}

/** ASSIGN 전용 필드 — ProjectMemberSelect(담당자 해제 허용, EC3) */
function AssignField({ projectKey, config, onChange, idPrefix }: AssignFieldProps): JSX.Element {
  function handleAssigneeChange(assigneeId: string | null): void {
    onChange({ type: 'ASSIGN', config: { assigneeId } })
  }

  return (
    <ProjectMemberSelect
      projectKey={projectKey}
      value={config.assigneeId ?? null}
      onChange={handleAssigneeChange}
      allowUnassign
      label={TEXT.assigneeLabel}
      id={`${idPrefix}-assignee`}
    />
  )
}

/** ADD_COMMENT 전용 필드 — 본문 textarea + 템플릿 변수 힌트(FR5) */
function AddCommentField({ config, onChange, idPrefix }: TypedFieldsProps): JSX.Element {
  function handleBodyChange(event: ChangeEvent<HTMLTextAreaElement>): void {
    onChange({ type: 'ADD_COMMENT', config: { ...config, body: event.target.value } })
  }

  return (
    <div>
      <label htmlFor={`${idPrefix}-comment-body`} className="block text-sm font-medium mb-1">
        {TEXT.commentBodyLabel}
      </label>
      <textarea
        id={`${idPrefix}-comment-body`}
        aria-label={TEXT.commentBodyLabel}
        value={config.body ?? ''}
        onChange={handleBodyChange}
        rows={3}
        className={FIELD_CLASS}
      />
      <p className="text-xs text-muted-foreground mt-1">{TEXT.commentTemplateHint}</p>
    </div>
  )
}

/**
 * CALL_WEBHOOK 전용 필드 — url·method(기본 POST)·헤더 행 추가/삭제·본문(FR6).
 *
 * 헤더는 쌍 배열({@link WebhookHeaderEntry}[])로 관리한다(코드리뷰 PR #260 CONCERNS C1·C2 회귀 방지).
 * - "헤더 추가"는 placeholder 키(과거 `header-1`) 대신 빈 키·값 행을 만든다 — 미입력 시
 *   {@link serializeActionConfig}가 저장 단계에서 걸러낸다(C1).
 * - 행 렌더 key는 인덱스가 아니라 행별 클라이언트 id(`headerRowIds`)를 쓴다 — `ActionListEditor`의
 *   행 id 선례([[react-usestate-stale-key-prop]])와 동형. 인덱스 key였다면 중간 행 삭제 시 뒤 행들이
 *   밀리며 다른 입력 DOM 노드로 상태가 새는 문제가 있었다. 배열 순서·길이가 바뀌는 추가/삭제
 *   핸들러에서만 `headerRowIds`를 동기 갱신하고, 키/값 편집은 길이를 바꾸지 않아 그대로 둔다.
 */
function CallWebhookFields({ config, onChange, idPrefix }: TypedFieldsProps): JSX.Element {
  const [headerRowIds, setHeaderRowIds] = useState<string[]>(() => resolveHeaders(config).map(() => crypto.randomUUID()))

  function handleUrlChange(event: ChangeEvent<HTMLInputElement>): void {
    onChange({ type: 'CALL_WEBHOOK', config: { ...config, url: event.target.value } })
  }

  function handleMethodChange(event: ChangeEvent<HTMLSelectElement>): void {
    onChange({ type: 'CALL_WEBHOOK', config: { ...config, method: event.target.value } })
  }

  function handleBodyChange(event: ChangeEvent<HTMLTextAreaElement>): void {
    onChange({ type: 'CALL_WEBHOOK', config: { ...config, body: event.target.value } })
  }

  function handleAddHeaderRow(): void {
    const headers = resolveHeaders(config)
    setHeaderRowIds((prev) => [...prev, crypto.randomUUID()])
    onChange({ type: 'CALL_WEBHOOK', config: { ...config, headers: [...headers, { key: '', value: '' }] } })
  }

  function handleHeaderKeyChange(index: number, nextKey: string): void {
    const headers = resolveHeaders(config)
    if (headers[index] === undefined) return
    onChange({ type: 'CALL_WEBHOOK', config: { ...config, headers: updateHeaderAt(headers, index, { key: nextKey }) } })
  }

  function handleHeaderValueChange(index: number, nextValue: string): void {
    const headers = resolveHeaders(config)
    if (headers[index] === undefined) return
    onChange({ type: 'CALL_WEBHOOK', config: { ...config, headers: updateHeaderAt(headers, index, { value: nextValue }) } })
  }

  function handleRemoveHeader(index: number): void {
    const headers = resolveHeaders(config).filter((_entry, entryIndex) => entryIndex !== index)
    setHeaderRowIds((prev) => prev.filter((_id, idIndex) => idIndex !== index))
    onChange({ type: 'CALL_WEBHOOK', config: { ...config, headers } })
  }

  return (
    <div className="space-y-3">
      <div>
        <label htmlFor={`${idPrefix}-webhook-url`} className="block text-sm font-medium mb-1">
          {TEXT.webhookUrlLabel}
        </label>
        <input
          id={`${idPrefix}-webhook-url`}
          type="text"
          aria-label={TEXT.webhookUrlLabel}
          value={config.url ?? ''}
          onChange={handleUrlChange}
          className={FIELD_CLASS}
        />
      </div>

      <div>
        <label htmlFor={`${idPrefix}-webhook-method`} className="block text-sm font-medium mb-1">
          {TEXT.webhookMethodLabel}
        </label>
        <select
          id={`${idPrefix}-webhook-method`}
          aria-label={TEXT.webhookMethodLabel}
          value={config.method ?? DEFAULT_WEBHOOK_METHOD}
          onChange={handleMethodChange}
          className={FIELD_CLASS}
        >
          {WEBHOOK_METHODS.map((method) => (
            <option key={method} value={method}>
              {method}
            </option>
          ))}
        </select>
      </div>

      <div>
        <span className="block text-sm font-medium mb-1">{TEXT.webhookHeadersLabel}</span>
        <ul className="space-y-2">
          {resolveHeaders(config).map((entry, index) => {
            const rowId = headerRowIds[index] ?? String(index)
            return (
              <li key={rowId} className="flex gap-2 items-center">
                <input
                  type="text"
                  aria-label={TEXT.webhookHeaderKeyLabel}
                  value={entry.key}
                  onChange={(event) => {
                    handleHeaderKeyChange(index, event.target.value)
                  }}
                  className="flex-1 rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
                <input
                  type="text"
                  aria-label={TEXT.webhookHeaderValueLabel}
                  value={entry.value}
                  onChange={(event) => {
                    handleHeaderValueChange(index, event.target.value)
                  }}
                  className="flex-1 rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-xs"
                  aria-label={`${index + 1}번째 헤더 삭제`}
                  onClick={() => {
                    handleRemoveHeader(index)
                  }}
                  className="text-muted-foreground hover:text-destructive"
                >
                  <span aria-hidden="true">×</span>
                </Button>
              </li>
            )
          })}
        </ul>
        <Button type="button" variant="outline" size="sm" onClick={handleAddHeaderRow} className="mt-2">
          {TEXT.webhookAddHeaderButton}
        </Button>
      </div>

      <div>
        <label htmlFor={`${idPrefix}-webhook-body`} className="block text-sm font-medium mb-1">
          {TEXT.webhookBodyLabel}
        </label>
        <textarea
          id={`${idPrefix}-webhook-body`}
          aria-label={TEXT.webhookBodyLabel}
          value={config.body ?? ''}
          onChange={handleBodyChange}
          rows={3}
          className={FIELD_CLASS}
        />
      </div>
    </div>
  )
}

interface SetFixVersionsFieldsProps extends TypedFieldsProps {
  readonly projectKey: string
}

/**
 * SET_FIX_VERSIONS 전용 필드 — 적용 방식 라디오(교체/전체 해제) + {@link VersionMultiSelect}(variant="fix").
 *
 * - '교체' 모드일 때만 버전 목록을 렌더한다. '전체 해제' 모드는 목록을 숨기되 `versionIds`는
 *   폼 상태에 그대로 보존한다 — 사용자가 다시 '교체'로 돌아오면 이전 선택이 그대로 복원된다
 *   (목록을 disabled로 남겨두면 체크된 값이 조용히 버려지는 문제가 있어 렌더 자체를 하지 않는다).
 * - 로딩/에러는 {@link ProjectMemberSelect} 선례(disabled shell + 안내 문구) 동형이다 — 빈 목록으로
 *   은폐하지 않고, 로딩/에러 중에는 {@link VersionMultiSelect}를 렌더하지 않는다. 적용 방식 라디오는
 *   로딩/에러와 무관하게 항상 조작 가능하다(버전 목록이 실패해도 '전체 해제'로 전환할 수 있어야 한다).
 */
function SetFixVersionsFields({ projectKey, config, onChange, idPrefix }: SetFixVersionsFieldsProps): JSX.Element {
  const { data: versions, isLoading, isError } = useVersions(projectKey)
  const mode = config.fixVersionsMode ?? 'replace'
  const versionIds = config.versionIds ?? []
  const radioGroupName = `${idPrefix}-fix-versions-mode`

  function handleModeChange(nextMode: 'replace' | 'clear'): void {
    onChange({ type: 'SET_FIX_VERSIONS', config: { ...config, fixVersionsMode: nextMode } })
  }

  function handleVersionsChange(ids: string[]): void {
    onChange({ type: 'SET_FIX_VERSIONS', config: { ...config, versionIds: ids } })
  }

  return (
    <div className="space-y-3">
      <fieldset>
        <legend className="block text-sm font-medium mb-1">{TEXT.fixVersionsModeLabel}</legend>
        <div className="flex flex-col gap-1.5">
          <label className="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="radio"
              name={radioGroupName}
              value="replace"
              checked={mode === 'replace'}
              onChange={() => {
                handleModeChange('replace')
              }}
              className="h-4 w-4 cursor-pointer accent-primary"
            />
            {TEXT.fixVersionsReplaceLabel}
          </label>
          <label className="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="radio"
              name={radioGroupName}
              value="clear"
              checked={mode === 'clear'}
              onChange={() => {
                handleModeChange('clear')
              }}
              className="h-4 w-4 cursor-pointer accent-primary"
            />
            {TEXT.fixVersionsClearLabel}
          </label>
        </div>
      </fieldset>

      {mode === 'replace' && isLoading && (
        <p className="text-sm text-muted-foreground">{TEXT.fixVersionsLoading}</p>
      )}
      {mode === 'replace' && isError && (
        <p className="text-sm text-destructive" role="alert">
          {TEXT.fixVersionsError}
        </p>
      )}
      {mode === 'replace' && !isLoading && !isError && (
        <VersionMultiSelect variant="fix" value={versionIds} options={versions ?? []} onChange={handleVersionsChange} />
      )}
    </div>
  )
}

interface ProjectFieldsProps extends TypedFieldsProps {
  readonly projectKey: string
}

/** TRANSITION 전용 필드 — 프로젝트 워크플로 상태 select(R9 단계 C). 「상태 전환됨」 트리거 피커와 같은 목록. */
function TransitionFields({ projectKey, config, onChange, idPrefix }: ProjectFieldsProps): JSX.Element {
  const workflowsQuery = useProjectWorkflows(projectKey)
  const options = useMemo(() => extractStatusOptions(workflowsQuery.data ?? []), [workflowsQuery.data])

  if (workflowsQuery.isPending) return <p className="text-sm text-muted-foreground">{TEXT.statesLoading}</p>
  if (workflowsQuery.isError) {
    return (
      <p className="text-sm text-destructive" role="alert">
        {TEXT.statesError}
      </p>
    )
  }

  return (
    <div>
      <label htmlFor={`${idPrefix}-transition-state`} className="block text-sm font-medium mb-1">
        {TEXT.transitionToStateLabel}
      </label>
      <select
        id={`${idPrefix}-transition-state`}
        aria-label={TEXT.transitionToStateLabel}
        value={config.toState ?? ''}
        onChange={(event) => {
          onChange({ type: 'TRANSITION', config: { toState: event.target.value } })
        }}
        className={FIELD_CLASS}
      >
        <option value="">{TEXT.transitionStatePlaceholder}</option>
        {options.map((option) => (
          <option key={option.key} value={option.key}>
            {option.name}
          </option>
        ))}
      </select>
    </div>
  )
}

/**
 * CREATE_ISSUE 전용 필드 — 요약(템플릿) · 이슈 유형 · 하위 작업 여부(R9 단계 C). 이슈는 이 룰의 프로젝트에 만든다.
 * 하위 작업 유형(hierarchyLevel -1)을 고르면 부모 없이 만들 수 없어 「하위 작업으로 만들기」를 함께 켠다.
 */
function CreateIssueFields({ config, onChange, idPrefix }: TypedFieldsProps): JSX.Element {
  const typesQuery = useIssueTypes()

  function emit(patch: Partial<ActionConfigFormState>): void {
    onChange({ type: 'CREATE_ISSUE', config: { ...config, ...patch } })
  }

  function handleTypeChange(event: ChangeEvent<HTMLSelectElement>): void {
    const key = event.target.value
    const isSubtask = typesQuery.data?.find((type) => type.key === key)?.hierarchyLevel === SUBTASK_HIERARCHY_LEVEL
    emit(isSubtask ? { issueTypeKey: key, asSubtask: true } : { issueTypeKey: key })
  }

  return (
    <div className="space-y-3">
      <div>
        <label htmlFor={`${idPrefix}-create-summary`} className="block text-sm font-medium mb-1">
          {TEXT.createSummaryLabel}
        </label>
        <input
          id={`${idPrefix}-create-summary`}
          type="text"
          aria-label={TEXT.createSummaryLabel}
          value={config.summary ?? ''}
          onChange={(event) => {
            emit({ summary: event.target.value })
          }}
          className={FIELD_CLASS}
        />
        <p className="text-xs text-muted-foreground mt-1">{TEXT.templateHint}</p>
      </div>
      <div>
        <label htmlFor={`${idPrefix}-create-type`} className="block text-sm font-medium mb-1">
          {TEXT.createTypeLabel}
        </label>
        {typesQuery.isPending && <p className="text-sm text-muted-foreground">{TEXT.typesLoading}</p>}
        {typesQuery.isError && (
          <p className="text-sm text-destructive" role="alert">
            {TEXT.typesError}
          </p>
        )}
        {typesQuery.isSuccess && (
          <select
            id={`${idPrefix}-create-type`}
            aria-label={TEXT.createTypeLabel}
            value={config.issueTypeKey ?? 'task'}
            onChange={handleTypeChange}
            className={FIELD_CLASS}
          >
            {typesQuery.data.map((type) => (
              <option key={type.key} value={type.key}>
                {type.name}
              </option>
            ))}
          </select>
        )}
      </div>
      <label className="flex cursor-pointer items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={config.asSubtask ?? false}
          onChange={(event) => {
            emit({ asSubtask: event.target.checked })
          }}
          className="h-4 w-4 cursor-pointer accent-primary"
        />
        {TEXT.createAsSubtaskLabel}
      </label>
    </div>
  )
}

/** LINK_ISSUE 전용 필드 — 연결 유형 · 대상 이슈 키(템플릿, 룰 프로젝트 소속만 — 서버가 실행 시점에 검사)(R9 단계 C) */
function LinkIssueFields({ config, onChange, idPrefix }: TypedFieldsProps): JSX.Element {
  function emit(patch: Partial<ActionConfigFormState>): void {
    onChange({ type: 'LINK_ISSUE', config: { ...config, ...patch } })
  }

  return (
    <div className="space-y-3">
      <div>
        <label htmlFor={`${idPrefix}-link-type`} className="block text-sm font-medium mb-1">
          {TEXT.linkTypeLabel}
        </label>
        <select
          id={`${idPrefix}-link-type`}
          aria-label={TEXT.linkTypeLabel}
          value={config.linkTypeCode ?? 'relates'}
          onChange={(event) => {
            emit({ linkTypeCode: event.target.value })
          }}
          className={FIELD_CLASS}
        >
          {LINK_TYPES.map((linkType) => (
            <option key={linkType.value} value={linkType.value}>
              {linkType.label}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor={`${idPrefix}-link-target`} className="block text-sm font-medium mb-1">
          {TEXT.linkTargetLabel}
        </label>
        <input
          id={`${idPrefix}-link-target`}
          type="text"
          aria-label={TEXT.linkTargetLabel}
          value={config.targetIssueKey ?? ''}
          onChange={(event) => {
            emit({ targetIssueKey: event.target.value })
          }}
          className={FIELD_CLASS}
        />
        <p className="text-xs text-muted-foreground mt-1">{TEXT.linkTargetHint}</p>
      </div>
    </div>
  )
}

/** ADD_WATCHER 전용 필드 — 담당자 · 보고자 · 지정 사용자 중 하나(R9 단계 C). 지정 사용자면 프로젝트 멤버 select. */
function AddWatcherFields({ projectKey, config, onChange, idPrefix }: ProjectFieldsProps): JSX.Element {
  const source: WatcherSource = config.fromField ?? 'user'

  function handleSourceChange(event: ChangeEvent<HTMLSelectElement>): void {
    const next = event.target.value
    onChange({
      type: 'ADD_WATCHER',
      config: next === 'reporter' || next === 'assignee' ? { userId: null, fromField: next } : { userId: null, fromField: null },
    })
  }

  return (
    <div className="space-y-3">
      <div>
        <label htmlFor={`${idPrefix}-watcher-source`} className="block text-sm font-medium mb-1">
          {TEXT.watcherSourceLabel}
        </label>
        <select
          id={`${idPrefix}-watcher-source`}
          aria-label={TEXT.watcherSourceLabel}
          value={source}
          onChange={handleSourceChange}
          className={FIELD_CLASS}
        >
          {WATCHER_SOURCES.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      {source === 'user' && (
        <ProjectMemberSelect
          projectKey={projectKey}
          value={config.userId ?? null}
          onChange={(userId) => {
            onChange({ type: 'ADD_WATCHER', config: { userId, fromField: null } })
          }}
          label={TEXT.watcherUserLabel}
          id={`${idPrefix}-watcher-user`}
        />
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ActionConfigEditor props */
export interface ActionConfigEditorProps {
  /** 담당자 피커(ASSIGN)에 넘길 프로젝트 식별 키 */
  readonly projectKey: string
  /** 액션 1건의 현재 폼 상태 */
  readonly value: ActionFormState
  /** 폼 상태 변경 콜백 — 직렬화는 이 컴포넌트 책임이 아니다(상위 AutomationRuleFormDialog가 담당) */
  readonly onChange: (next: ActionFormState) => void
  /** 입력 id 접두사 — ActionListEditor가 여러 행을 렌더할 때 고유하게 지정한다 */
  readonly idPrefix?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 액션 1건의 타입별 조건부 편집기.
 *
 * 상단의 액션 유형 select로 9종({@link ACTION_TYPE_LABELS}) 중
 * 하나를 고르면, 그 아래에 타입별 서브컴포넌트가 조건부로 전환된다(`TriggerConfigFields` 선례 동형).
 * - {@link SetFieldFields} — 필드 6종 드롭다운 + 필드 타입에 맞는 값 위젯(텍스트/1~5·1~3 select/태그입력).
 *   6종 밖 field(EC10, 기존 룰에 저장된 값)는 텍스트 위젯으로 fallback해 값을 보존한다.
 * - {@link AssignField} — {@link ProjectMemberSelect}(담당자 해제 허용, EC3).
 * - {@link AddCommentField} — 본문 textarea + 템플릿 변수 힌트(FR5).
 * - {@link CallWebhookFields} — url·method(기본 POST)·헤더 행 추가/삭제·본문.
 * - {@link SetFixVersionsFields} — 적용 방식(교체/전체 해제) + {@link VersionMultiSelect}(FR-AT-07 PR-B).
 * - {@link TransitionFields} · {@link CreateIssueFields} · {@link LinkIssueFields} · {@link AddWatcherFields} — R9 단계 C.
 *
 * 완전한 controlled 컴포넌트다 — `value`/`onChange`로만 상태를 주고받고, 자체 상태는 라벨 태그
 * 입력의 임시 draft에만 쓴다. 직렬화(JSON 문자열화)는 하지 않는다(상위 AutomationRuleFormDialog가
 * `serializeActionConfig`로 수행).
 */
export function ActionConfigEditor({
  projectKey,
  value,
  onChange,
  idPrefix = 'action-config',
}: ActionConfigEditorProps): JSX.Element {
  const [labelDraft, setLabelDraft] = useState('')

  function handleTypeChange(event: ChangeEvent<HTMLSelectElement>): void {
    const nextType = toActionType(event.target.value)
    onChange({ type: nextType, config: defaultConfigForType(nextType) })
  }

  return (
    <div className="space-y-3">
      <div>
        <label htmlFor={`${idPrefix}-type`} className="block text-sm font-medium mb-1">
          {TEXT.actionTypeLabel}
        </label>
        <select
          id={`${idPrefix}-type`}
          aria-label={TEXT.actionTypeLabel}
          value={value.type}
          onChange={handleTypeChange}
          className={FIELD_CLASS}
        >
          {actionTypeSchema.options.map((type) => (
            <option key={type} value={type}>
              {ACTION_TYPE_LABELS[type]}
            </option>
          ))}
        </select>
      </div>

      {value.type === 'SET_FIELD' && (
        <SetFieldFields
          config={value.config}
          onChange={onChange}
          idPrefix={idPrefix}
          labelDraft={labelDraft}
          onLabelDraftChange={setLabelDraft}
        />
      )}
      {value.type === 'ASSIGN' && (
        <AssignField projectKey={projectKey} config={value.config} onChange={onChange} idPrefix={idPrefix} />
      )}
      {value.type === 'ADD_COMMENT' && <AddCommentField config={value.config} onChange={onChange} idPrefix={idPrefix} />}
      {value.type === 'CALL_WEBHOOK' && <CallWebhookFields config={value.config} onChange={onChange} idPrefix={idPrefix} />}
      {value.type === 'SET_FIX_VERSIONS' && (
        <SetFixVersionsFields projectKey={projectKey} config={value.config} onChange={onChange} idPrefix={idPrefix} />
      )}
      {value.type === 'TRANSITION' && (
        <TransitionFields projectKey={projectKey} config={value.config} onChange={onChange} idPrefix={idPrefix} />
      )}
      {value.type === 'CREATE_ISSUE' && <CreateIssueFields config={value.config} onChange={onChange} idPrefix={idPrefix} />}
      {value.type === 'LINK_ISSUE' && <LinkIssueFields config={value.config} onChange={onChange} idPrefix={idPrefix} />}
      {value.type === 'ADD_WATCHER' && (
        <AddWatcherFields projectKey={projectKey} config={value.config} onChange={onChange} idPrefix={idPrefix} />
      )}
    </div>
  )
}
