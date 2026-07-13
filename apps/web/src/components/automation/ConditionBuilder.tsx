// 조건 트리(And/Or/Not 그룹 + Comparison leaf) 재귀 편집 컴포넌트 (FR-AT-03 D6 Task 3)
import type { JSX } from 'react'
import { useRef } from 'react'
import { Button } from '@/components/ui/button'
import { createEmptyConditionTree } from '@/api/automation-rules.types'
import type { ConditionComparison, ConditionGroup, ConditionNode } from '@/api/automation-rules.types'
import { ConditionComparisonRow } from './ConditionComparisonRow'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — automation BC 관례 고정 한국어 (i18n 미도입, ActionListEditor 선례 동형)
// ─────────────────────────────────────────────────────────────────────────────

const TEXT = {
  addComparisonButton: '조건 추가',
  addGroupButton: '그룹 추가',
  removeComparisonLabel: '조건 삭제',
  removeGroupLabel: '그룹 삭제',
  negateLabel: '아님(NOT)',
  emptyGroupHint: '조건을 추가하세요 — 그룹이 비어있으면 저장 시 제거됩니다.',
} as const

/** "조건 추가" 클릭 시 새로 추가되는 기본 Comparison — 필드 화이트리스트 첫 값(텍스트 위젯). */
const DEFAULT_COMPARISON: ConditionComparison = { kind: 'comparison', field: 'issue.key', operator: 'EQUALS', value: '' }

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ConditionBuilder props */
export interface ConditionBuilderProps {
  /** 편집 중인 조건 트리(루트 노드) — 보통 그룹이지만 단일 Comparison도 지원한다. */
  readonly value: ConditionNode
  /** 트리 변경 콜백 — 완전한 다음 {@link ConditionNode} 트리를 방출한다. */
  readonly onChange: (next: ConditionNode) => void
  /** 담당자/보고자 값 위젯({@link ConditionComparisonRow})에 넘길 프로젝트 식별 키 */
  readonly projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 그룹 노드 서브컴포넌트 — 재귀 렌더(자식이 그룹이면 자기 자신을 다시 렌더)
// ─────────────────────────────────────────────────────────────────────────────

interface ConditionGroupNodeProps {
  /** 편집 중인 그룹 노드 */
  readonly group: ConditionGroup
  /** 재귀 깊이(0=루트) */
  readonly depth: number
  /** 담당자/보고자 값 위젯에 넘길 프로젝트 식별 키 */
  readonly projectKey: string
  /** 이 그룹 자체의 변경 콜백 — 완전한 다음 {@link ConditionNode}를 방출한다. */
  readonly onChange: (next: ConditionNode) => void
  /** 이 그룹을 부모에서 제거하는 콜백 — 루트(부모 없음)면 undefined(삭제 버튼 미노출). */
  readonly onRemove?: () => void
  /** 노드 객체 참조별 안정 key 조회 함수 */
  readonly idFor: (node: ConditionNode) => string
}

/**
 * 그룹 노드 — op(and/or) 토글 + negated 체크박스 + 자식 순회 + 조건/그룹 추가 버튼 + (있으면) 삭제 버튼.
 *
 * 자식이 없으면 "조건을 추가하세요" 힌트를 보여준다(EC12/G2 — 빈 그룹은 직렬화 시 prune되어 사라진다는
 * 사실을 사용자가 인지하도록). 각 자식은 좌측 테두리 + 들여쓰기로 depth를 시각적으로 표현한다(D1).
 * 자식이 그룹이면 이 컴포넌트를 재귀 호출한다.
 */
function ConditionGroupNode({ group, depth, projectKey, onChange, onRemove, idFor }: ConditionGroupNodeProps): JSX.Element {
  return (
    <div data-testid="condition-group" className="rounded-md border border-input p-3">
      <div className="flex flex-wrap items-center gap-3 mb-2">
        <button
          type="button"
          data-testid="condition-group-op-toggle"
          onClick={() => {
            onChange({ ...group, op: group.op === 'and' ? 'or' : 'and' })
          }}
          className="rounded-md border border-input px-2 py-1 text-xs font-semibold uppercase hover:bg-accent"
        >
          {group.op === 'and' ? 'AND' : 'OR'}
        </button>
        <label className="flex items-center gap-1 text-sm">
          <input
            type="checkbox"
            role="checkbox"
            data-testid="condition-group-negate"
            aria-label={TEXT.negateLabel}
            checked={group.negated}
            onChange={() => {
              onChange({ ...group, negated: !group.negated })
            }}
            className="h-4 w-4 cursor-pointer accent-primary"
          />
          {TEXT.negateLabel}
        </label>
        {onRemove && (
          <button
            type="button"
            aria-label={TEXT.removeGroupLabel}
            data-testid="condition-remove-node"
            onClick={onRemove}
            className="ml-auto text-muted-foreground hover:text-destructive"
          >
            {TEXT.removeGroupLabel}
          </button>
        )}
      </div>

      {group.children.length === 0 && <p className="text-sm text-muted-foreground mb-2">{TEXT.emptyGroupHint}</p>}

      <div className="space-y-2">
        {group.children.map((child, index) => {
          function handleChildChange(next: ConditionNode): void {
            onChange({ ...group, children: group.children.map((c, i) => (i === index ? next : c)) })
          }
          function handleChildRemove(): void {
            onChange({ ...group, children: group.children.filter((_c, i) => i !== index) })
          }

          return (
            <div key={idFor(child)} className="border-l-2 border-border pl-4">
              {child.kind === 'comparison' ? (
                <div data-testid="condition-comparison-node" className="flex items-start gap-2">
                  <div className="flex-1">
                    <ConditionComparisonRow value={child} onChange={handleChildChange} projectKey={projectKey} />
                  </div>
                  <button
                    type="button"
                    aria-label={TEXT.removeComparisonLabel}
                    data-testid="condition-remove-node"
                    onClick={handleChildRemove}
                    className="text-muted-foreground hover:text-destructive"
                  >
                    <span aria-hidden="true">×</span>
                  </button>
                </div>
              ) : (
                <ConditionGroupNode
                  group={child}
                  depth={depth + 1}
                  projectKey={projectKey}
                  onChange={handleChildChange}
                  onRemove={handleChildRemove}
                  idFor={idFor}
                />
              )}
            </div>
          )
        })}
      </div>

      <div className="flex gap-2 mt-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          data-testid="condition-add-comparison"
          onClick={() => {
            onChange({ ...group, children: [...group.children, DEFAULT_COMPARISON] })
          }}
        >
          {TEXT.addComparisonButton}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          data-testid="condition-add-group"
          onClick={() => {
            onChange({ ...group, children: [...group.children, createEmptyConditionTree()] })
          }}
        >
          {TEXT.addGroupButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 조건 트리(And/Or/Not 그룹 + Comparison leaf) 재귀 편집 컴포넌트.
 *
 * `ActionListEditor` 위임 패턴 동형 — 완전한 controlled 컴포넌트로 `value`/`onChange`로만 트리 상태를
 * 주고받는다. 루트 노드는 삭제 버튼을 렌더하지 않는다(부모가 없어 제거할 대상이 없다). 루트가 그룹이 아닌
 * 단일 Comparison이어도({@link ConditionComparisonRow}만 렌더) 지원한다.
 *
 * 노드 객체 참조별 uuid를 캐시해 React key로 사용한다({@link WeakMap}) — 불변 업데이트로 변경되지 않은
 * 형제 노드는 참조가 유지되므로 추가/삭제 후에도 나머지 노드의 key가 안정적으로 보존된다
 * ({@link ActionListEditor}의 배열 인덱스 id 캐시 선례를 트리 구조에 맞게 확장).
 *
 * JSON 직렬화(`serializeConditionExpression`)는 이 컴포넌트의 책임이 아니다 — 폼 상태(`ConditionNode`)만
 * 편집하고, 저장 시 직렬화는 소비자(`AutomationRuleFormDialog`, Task 5)가 수행한다.
 */
export function ConditionBuilder({ value, onChange, projectKey }: ConditionBuilderProps): JSX.Element {
  const nodeIdCacheRef = useRef(new WeakMap<ConditionNode, string>())

  function idFor(node: ConditionNode): string {
    const cache = nodeIdCacheRef.current
    const existing = cache.get(node)
    if (existing !== undefined) return existing
    const id = crypto.randomUUID()
    cache.set(node, id)
    return id
  }

  if (value.kind === 'comparison') {
    return <ConditionComparisonRow value={value} onChange={onChange} projectKey={projectKey} />
  }

  return <ConditionGroupNode group={value} depth={0} projectKey={projectKey} onChange={onChange} idFor={idFor} />
}
