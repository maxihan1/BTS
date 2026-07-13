// Comparison 조건 1행 편집 컴포넌트 — 필드/연산자 select + 필드별 값 위젯 (FR-AT-03 D6 Task 2)
import type { ChangeEvent, JSX } from 'react'
import { COMPARISON_OPERATOR_META, FIELD_WHITELIST } from '@/api/automation-rules.types'
import type { ComparisonOperator, ComparisonOperatorMeta, ConditionComparison } from '@/api/automation-rules.types'
import { ProjectMemberSelect } from './ProjectMemberSelect'

// ─────────────────────────────────────────────────────────────────────────────
// 문구/상수 — ActionConfigEditor 선례 동형 (automation BC 관례 고정 한국어, i18n 미도입)
// ─────────────────────────────────────────────────────────────────────────────

const TEXT = {
  fieldLabel: '필드',
  operatorLabel: '연산자',
  valueLabel: '값',
} as const

/** {@link FIELD_WHITELIST} 9종의 한국어 라벨 — backend `Condition.FIELD_WHITELIST`와 1:1 대응. */
const FIELD_LABELS: Record<string, string> = {
  'issue.key': '이슈 키',
  'issue.type': '이슈 유형',
  'issue.status': '상태',
  'issue.priority': '우선순위',
  'issue.assignee': '담당자',
  'issue.reporter': '보고자',
  'issue.labels': '라벨',
  'issue.summary': '요약',
  'issue.projectKey': '프로젝트 키',
}

/** 입력 필드 공통 Tailwind 클래스 — automation BC select/input 전반에서 재사용(ActionConfigEditor FIELD_CLASS 동형) */
const FIELD_CLASS =
  'w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20'

/** priority 값 위젯 select 옵션(1~5) — backend Int 1..5 제약(ActionConfigEditor PRIORITY_OPTIONS와 동일 범위) */
const PRIORITY_OPTIONS = [1, 2, 3, 4, 5] as const
/** priority 값 위젯 기본값 — 범위 내 최솟값 */
const DEFAULT_PRIORITY_VALUE = 1

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** Comparison 값 위젯 종류 — field에 따라 결정된다([D2]). */
type ConditionValueWidgetKind = 'number' | 'member' | 'text'

/**
 * field로부터 값 위젯 종류를 결정한다([D2]).
 * `issue.priority`는 숫자 select, `issue.assignee`/`issue.reporter`는 {@link ProjectMemberSelect},
 * 그 외(key/type/status/labels/summary/projectKey)는 텍스트 input이다.
 */
function resolveConditionValueWidgetKind(field: string): ConditionValueWidgetKind {
  if (field === 'issue.priority') return 'number'
  if (field === 'issue.assignee' || field === 'issue.reporter') return 'member'
  return 'text'
}

/** raw select 값이 유효한 {@link ComparisonOperator}인지 판별하는 타입 가드 */
function isComparisonOperator(raw: string): raw is ComparisonOperator {
  return raw in COMPARISON_OPERATOR_META
}

/** 필드 select 옵션 라벨을 해석한다 — {@link FIELD_LABELS}에 없으면 field 원문으로 fallback한다. */
function resolveFieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field
}

/**
 * 위젯 종류 전환 시 적용할 기본 값.
 *
 * `previousKind`(전환 전 위젯 종류)와 `nextKind`(전환 후 위젯 종류)가 같으면 이전 값을 타입이
 * 맞는 한 보존하고, 다르면(위젯 종류 자체가 바뀐 필드 전환) 위젯별 기본값으로 초기화한다.
 * `member` 위젯은 이 구분이 특히 중요하다 — 텍스트 위젯에서 타이핑한 임의 문자열(예: 요약 필드의
 * `"x"`)이 담당자 uuid로 오인되어 넘어가지 않도록, 이전 위젯도 `member`였을 때만 문자열 값을
 * 보존한다. `number`/`text`는 값 타입 자체(number/string)가 위젯 종류를 안전하게 구분해주므로
 * 종류 일치 여부와 무관하게 타입 검사만으로 충분하다.
 *
 * - `number` — 1~5 범위의 숫자면 보존, 아니면 {@link DEFAULT_PRIORITY_VALUE}
 * - `member` — 이전 위젯도 `member`이고 값이 문자열(uuid)이면 보존, 아니면 `null`(미선택)
 * - `text` — 문자열이면 보존, 아니면 빈 문자열
 */
function defaultValueForWidget(
  nextKind: ConditionValueWidgetKind,
  previousKind: ConditionValueWidgetKind,
  previousValue: unknown,
): unknown {
  switch (nextKind) {
    case 'number':
      return typeof previousValue === 'number' && previousValue >= 1 && previousValue <= 5
        ? previousValue
        : DEFAULT_PRIORITY_VALUE
    case 'member':
      return previousKind === 'member' && typeof previousValue === 'string' ? previousValue : null
    case 'text':
    default:
      return typeof previousValue === 'string' ? previousValue : ''
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 값 위젯 서브컴포넌트 — ActionConfigEditor의 타입별 서브컴포넌트 선례 동형
// ─────────────────────────────────────────────────────────────────────────────

interface ConditionValueWidgetProps {
  /** 렌더할 위젯 종류 — {@link resolveConditionValueWidgetKind} 결과 */
  readonly widgetKind: ConditionValueWidgetKind
  /** 현재 Comparison의 `value` — 위젯 종류에 맞지 않는 타입이면 각 위젯이 자체 기본값으로 표시한다 */
  readonly rawValue: unknown
  /** `member` 위젯({@link ProjectMemberSelect})에 넘길 프로젝트 식별 키 */
  readonly projectKey: string
  /** 입력 id 접두사 — 한 그룹에 여러 행을 렌더할 때 고유하게 지정한다({@link ConditionComparisonRow} 참고) */
  readonly idPrefix: string
  /** 값 변경 콜백 — 위젯 종류에 맞는 새 값(문자열/숫자/uuid 또는 null)을 그대로 전달한다 */
  readonly onValueChange: (next: unknown) => void
}

/**
 * Comparison 값 위젯 — `widgetKind`에 따라 숫자 select/{@link ProjectMemberSelect}/텍스트 input
 * 중 하나를 렌더한다. 상위({@link ConditionComparisonRow})가 연산자 arity가 `binary`일 때만
 * 이 컴포넌트를 렌더한다(단항 연산자는 값 자체를 쓰지 않는다).
 */
function ConditionValueWidget({
  widgetKind,
  rawValue,
  projectKey,
  idPrefix,
  onValueChange,
}: ConditionValueWidgetProps): JSX.Element {
  if (widgetKind === 'number') {
    return (
      <div>
        <label htmlFor={`${idPrefix}-value-number`} className="block text-sm font-medium mb-1">
          {TEXT.valueLabel}
        </label>
        <select
          id={`${idPrefix}-value-number`}
          data-testid="condition-value-input"
          aria-label={TEXT.valueLabel}
          value={String(typeof rawValue === 'number' ? rawValue : DEFAULT_PRIORITY_VALUE)}
          onChange={(event) => {
            onValueChange(Number(event.target.value))
          }}
          className={FIELD_CLASS}
        >
          {PRIORITY_OPTIONS.map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
      </div>
    )
  }

  if (widgetKind === 'member') {
    return (
      <div data-testid="condition-value-input">
        <ProjectMemberSelect
          projectKey={projectKey}
          value={typeof rawValue === 'string' ? rawValue : null}
          onChange={onValueChange}
          label={TEXT.valueLabel}
          id={`${idPrefix}-value-member`}
        />
      </div>
    )
  }

  return (
    <div>
      <label htmlFor={`${idPrefix}-value-text`} className="block text-sm font-medium mb-1">
        {TEXT.valueLabel}
      </label>
      <input
        id={`${idPrefix}-value-text`}
        type="text"
        data-testid="condition-value-input"
        aria-label={TEXT.valueLabel}
        value={typeof rawValue === 'string' ? rawValue : ''}
        onChange={(event) => {
          onValueChange(event.target.value)
        }}
        className={FIELD_CLASS}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ConditionComparisonRow props */
export interface ConditionComparisonRowProps {
  /** 편집 중인 Comparison 조건 노드 */
  readonly value: ConditionComparison
  /** 편집 변경 콜백 — 완전한 다음 {@link ConditionComparison} 노드를 방출한다 */
  readonly onChange: (next: ConditionComparison) => void
  /** 담당자/보고자 값 위젯({@link ProjectMemberSelect})에 넘길 프로젝트 식별 키 */
  readonly projectKey: string
  /**
   * 입력 id 접두사 — {@link ConditionBuilder}가 한 그룹에 여러 행을 렌더할 때 행별로 고유하게
   * 지정한다(`ActionConfigEditor`의 idPrefix 관례 동형). 미지정 시 기본값(`'condition'`)을 쓰지만,
   * 같은 그룹에 Comparison이 2개 이상이면 반드시 고유 값을 넘겨야 DOM id 충돌을 피할 수 있다.
   */
  readonly idPrefix?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Comparison 조건 1행 편집 컴포넌트 — 필드 select + 연산자 select + 필드/arity에 따른 값 위젯.
 *
 * - 필드 select — {@link FIELD_WHITELIST} 9종({@link FIELD_LABELS} 한국어 라벨).
 * - 연산자 select — {@link COMPARISON_OPERATOR_META} 9종.
 * - 값 위젯 — 연산자 arity가 `unary`(비어있음/존재함)면 값 위젯을 렌더하지 않는다(값 자체를 쓰지
 *   않는 연산자). `binary`(같음/다름/초과/이상/미만/이하/포함)면 field에 따라 위젯이 갈린다([D2]).
 *   - `issue.priority` → 1~5 숫자 select
 *   - `issue.assignee`/`issue.reporter` → {@link ProjectMemberSelect} 재사용(값=uuid 또는 null)
 *   - 그 외 → 텍스트 input
 *
 * 필드를 바꾸면 값 위젯 종류가 전환되고, 새 위젯에 맞지 않는 이전 값은 위젯별 기본값으로
 * 초기화된다({@link defaultValueForWidget}). 연산자를 단항으로 바꾸면 `value` 키 자체를 제거한
 * 노드를 방출한다(단항 연산자는 값을 쓰지 않는다는 도메인 계약, {@link ConditionComparison} 참고).
 *
 * 완전한 controlled 컴포넌트다 — `value`/`onChange`로만 상태를 주고받는다.
 *
 * `idPrefix`로 필드/연산자/값 위젯의 DOM id를 행별로 고유하게 만든다({@link ConditionBuilder}가
 * 노드별 안정 key로 전달) — 한 그룹에 Comparison이 2개 이상일 때 정적 id 중복으로 라벨 클릭이
 * 엉뚱한 행에 포커스되는 것을 막는다(`ActionConfigEditor` idPrefix 관례 동형).
 */
export function ConditionComparisonRow({
  value,
  onChange,
  projectKey,
  idPrefix = 'condition',
}: ConditionComparisonRowProps): JSX.Element {
  const meta = COMPARISON_OPERATOR_META[value.operator]
  const widgetKind = resolveConditionValueWidgetKind(value.field)

  function handleFieldChange(event: ChangeEvent<HTMLSelectElement>): void {
    const nextField = event.target.value
    if (meta.arity === 'unary') {
      onChange({ kind: 'comparison', field: nextField, operator: value.operator })
      return
    }
    const nextWidgetKind = resolveConditionValueWidgetKind(nextField)
    onChange({
      kind: 'comparison',
      field: nextField,
      operator: value.operator,
      value: defaultValueForWidget(nextWidgetKind, widgetKind, value.value),
    })
  }

  function handleOperatorChange(event: ChangeEvent<HTMLSelectElement>): void {
    const raw = event.target.value
    if (!isComparisonOperator(raw)) return
    const nextMeta = COMPARISON_OPERATOR_META[raw]
    if (nextMeta.arity === 'unary') {
      onChange({ kind: 'comparison', field: value.field, operator: raw })
      return
    }
    onChange({
      kind: 'comparison',
      field: value.field,
      operator: raw,
      value: defaultValueForWidget(widgetKind, widgetKind, value.value),
    })
  }

  /** 값 위젯의 변경을 그대로 `value` 필드에 반영한다 — 세 위젯(텍스트/숫자/멤버) 공통 로직 */
  function handleValueChange(nextValue: unknown): void {
    onChange({ ...value, value: nextValue })
  }

  return (
    <div className="flex flex-wrap items-start gap-2">
      <div>
        <label htmlFor={`${idPrefix}-field`} className="block text-sm font-medium mb-1">
          {TEXT.fieldLabel}
        </label>
        <select
          id={`${idPrefix}-field`}
          data-testid="condition-field-select"
          aria-label={TEXT.fieldLabel}
          value={value.field}
          onChange={handleFieldChange}
          className={FIELD_CLASS}
        >
          {FIELD_WHITELIST.map((field) => (
            <option key={field} value={field}>
              {resolveFieldLabel(field)}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor={`${idPrefix}-operator`} className="block text-sm font-medium mb-1">
          {TEXT.operatorLabel}
        </label>
        <select
          id={`${idPrefix}-operator`}
          data-testid="condition-operator-select"
          aria-label={TEXT.operatorLabel}
          value={value.operator}
          onChange={handleOperatorChange}
          className={FIELD_CLASS}
        >
          {(Object.entries(COMPARISON_OPERATOR_META) as Array<[ComparisonOperator, ComparisonOperatorMeta]>).map(
            ([operator, operatorMeta]) => (
              <option key={operator} value={operator}>
                {operatorMeta.label}
              </option>
            ),
          )}
        </select>
      </div>

      {meta.arity === 'binary' && (
        <ConditionValueWidget
          widgetKind={widgetKind}
          rawValue={value.value}
          projectKey={projectKey}
          idPrefix={idPrefix}
          onValueChange={handleValueChange}
        />
      )}
    </div>
  )
}
