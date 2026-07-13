// Comparison 조건 1행 편집 컴포넌트 — 필드/연산자 select + 필드별 값 위젯 (FR-AT-03 D6 Task 2)
import type { ChangeEvent, JSX } from 'react'
import {
  COMPARISON_OPERATOR_META,
  FIELD_WHITELIST,
} from '@/api/automation-rules.types'
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
 */
export function ConditionComparisonRow({ value, onChange, projectKey }: ConditionComparisonRowProps): JSX.Element {
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

  function handleTextValueChange(event: ChangeEvent<HTMLInputElement>): void {
    onChange({ ...value, value: event.target.value })
  }

  function handleNumberValueChange(event: ChangeEvent<HTMLSelectElement>): void {
    onChange({ ...value, value: Number(event.target.value) })
  }

  function handleMemberValueChange(userId: string | null): void {
    onChange({ ...value, value: userId })
  }

  return (
    <div className="flex flex-wrap items-start gap-2">
      <div>
        <label htmlFor="condition-field" className="block text-sm font-medium mb-1">
          {TEXT.fieldLabel}
        </label>
        <select
          id="condition-field"
          data-testid="condition-field-select"
          aria-label={TEXT.fieldLabel}
          value={value.field}
          onChange={handleFieldChange}
          className={FIELD_CLASS}
        >
          {FIELD_WHITELIST.map((field) => (
            <option key={field} value={field}>
              {FIELD_LABELS[field] ?? field}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="condition-operator" className="block text-sm font-medium mb-1">
          {TEXT.operatorLabel}
        </label>
        <select
          id="condition-operator"
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

      {meta.arity === 'binary' && widgetKind === 'text' && (
        <div>
          <label htmlFor="condition-value-text" className="block text-sm font-medium mb-1">
            {TEXT.valueLabel}
          </label>
          <input
            id="condition-value-text"
            type="text"
            data-testid="condition-value-input"
            aria-label={TEXT.valueLabel}
            value={typeof value.value === 'string' ? value.value : ''}
            onChange={handleTextValueChange}
            className={FIELD_CLASS}
          />
        </div>
      )}

      {meta.arity === 'binary' && widgetKind === 'number' && (
        <div>
          <label htmlFor="condition-value-number" className="block text-sm font-medium mb-1">
            {TEXT.valueLabel}
          </label>
          <select
            id="condition-value-number"
            data-testid="condition-value-input"
            aria-label={TEXT.valueLabel}
            value={String(typeof value.value === 'number' ? value.value : DEFAULT_PRIORITY_VALUE)}
            onChange={handleNumberValueChange}
            className={FIELD_CLASS}
          >
            {PRIORITY_OPTIONS.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </div>
      )}

      {meta.arity === 'binary' && widgetKind === 'member' && (
        <div data-testid="condition-value-input">
          <ProjectMemberSelect
            projectKey={projectKey}
            value={typeof value.value === 'string' ? value.value : null}
            onChange={handleMemberValueChange}
            label={TEXT.valueLabel}
            id="condition-value-member"
          />
        </div>
      )}
    </div>
  )
}
