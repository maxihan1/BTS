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

/**
 * "조건 추가" 클릭 시 새로 추가되는 기본 Comparison을 생성한다 — 필드 화이트리스트 첫 값(텍스트 위젯).
 *
 * 호출마다 새 객체를 반환한다(중복 key 방지) — 모듈 상수를 공유 참조로 재사용하면 같은 그룹에 "조건
 * 추가"를 여러 번 눌렀을 때 서로 다른 Comparison 노드가 동일 객체 참조를 갖게 되고, {@link useNodeIdCache}의
 * WeakMap 키(노드 참조)가 두 노드에 같은 React key를 부여해 편집 시 유령 노드가 생긴다
 * ({@link createEmptyConditionTree}의 "호출마다 새 객체" 선례와 대칭).
 */
function createDefaultComparison(): ConditionComparison {
  return { kind: 'comparison', field: 'issue.key', operator: 'EQUALS', value: '' }
}

/** 그룹 컨테이너 공통 클래스 — 카드 테두리/라운드/패딩(depth 무관 공통부, ActionListEditor 행 스타일 동형). */
const GROUP_CONTAINER_CLASS = 'rounded-md border border-input p-3'

/** 자식 노드 래퍼 클래스 — 좌측 테두리 + 들여쓰기로 depth를 시각적으로 표현한다(D1). 모든 자식(depth≥1)에 적용. */
const NESTED_CHILD_CLASS = 'border-l-2 border-border pl-4'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 트리 변형 헬퍼 — 불변 업데이트(원본 mutate 금지). ActionListEditor의 swapAt/removeAt 선례 동형.
// ─────────────────────────────────────────────────────────────────────────────

/** 그룹의 `children[index]`를 `next`로 교체한 새 그룹을 반환한다. */
function updateChildAt(group: ConditionGroup, index: number, next: ConditionNode): ConditionGroup {
  return { ...group, children: group.children.map((child, childIndex) => (childIndex === index ? next : child)) }
}

/** 그룹의 `children[index]`를 제거한 새 그룹을 반환한다. */
function removeChildAt(group: ConditionGroup, index: number): ConditionGroup {
  return { ...group, children: group.children.filter((_child, childIndex) => childIndex !== index) }
}

/** 그룹 끝에 기본 Comparison 1건을 추가한 새 그룹을 반환한다(호출마다 새 객체 — {@link createDefaultComparison}). */
function addComparisonChild(group: ConditionGroup): ConditionGroup {
  return { ...group, children: [...group.children, createDefaultComparison()] }
}

/** 그룹 끝에 빈 중첩 그룹 1건을 추가한 새 그룹을 반환한다(호출마다 새 객체 — {@link createEmptyConditionTree}). */
function addGroupChild(group: ConditionGroup): ConditionGroup {
  return { ...group, children: [...group.children, createEmptyConditionTree()] }
}

/** 그룹의 결합자(op)를 and↔or로 토글한 새 그룹을 반환한다. */
function toggleGroupOp(group: ConditionGroup): ConditionGroup {
  return { ...group, op: group.op === 'and' ? 'or' : 'and' }
}

/** 그룹의 `negated` 플래그를 토글한 새 그룹을 반환한다. */
function toggleGroupNegate(group: ConditionGroup): ConditionGroup {
  return { ...group, negated: !group.negated }
}

// ─────────────────────────────────────────────────────────────────────────────
// 안정 key — 노드 객체 참조별 uuid 캐시
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 노드 객체 참조 → crypto.randomUUID 캐시를 반환하는 훅.
 *
 * 불변 업데이트로 변경되지 않은 형제 노드는 참조가 유지되므로 같은 id를 계속 반환한다 — 추가/삭제 시에도
 * 나머지 노드의 React key가 안정적으로 보존된다({@link ActionListEditor}의 배열 인덱스 id 캐시 선례를
 * 트리 구조에 맞게 확장). {@link WeakMap}이라 더 이상 참조되지 않는 노드는 자동으로 회수된다.
 */
function useNodeIdCache(): (node: ConditionNode) => string {
  const cacheRef = useRef(new WeakMap<ConditionNode, string>())
  return function idFor(node: ConditionNode): string {
    const cache = cacheRef.current
    const existing = cache.get(node)
    if (existing !== undefined) return existing
    const id = crypto.randomUUID()
    cache.set(node, id)
    return id
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 재귀 노드 렌더 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface ConditionNodeEditorProps {
  /** 편집 중인 노드(그룹 또는 Comparison leaf) */
  readonly node: ConditionNode
  /** 재귀 깊이(0=루트) */
  readonly depth: number
  /** 담당자/보고자 값 위젯에 넘길 프로젝트 식별 키 */
  readonly projectKey: string
  /** 이 노드 자체의 변경 콜백 */
  readonly onChange: (next: ConditionNode) => void
  /** 이 노드를 부모에서 제거하는 콜백 — 루트(부모 없음)면 undefined(삭제 버튼 미노출). */
  readonly onRemove?: () => void
  /** 노드 객체 참조별 안정 key 조회 함수 */
  readonly idFor: (node: ConditionNode) => string
}

/**
 * 조건 노드 1건을 재귀적으로 렌더한다.
 *
 * Comparison leaf는 {@link ConditionComparisonRow}에 위임하고 옆에 삭제 버튼만 붙인다. 그룹은
 * {@link ConditionGroupNode}로 넘겨 자식을 순회한다(자식이 그룹이면 이 컴포넌트를 다시 호출해 재귀한다).
 */
function ConditionNodeEditor({ node, depth, projectKey, onChange, onRemove, idFor }: ConditionNodeEditorProps): JSX.Element {
  if (node.kind === 'comparison') {
    return (
      <div data-testid="condition-comparison-node" className="flex items-start gap-2">
        <div className="flex-1">
          <ConditionComparisonRow value={node} onChange={onChange} projectKey={projectKey} />
        </div>
        {onRemove && (
          <button
            type="button"
            aria-label={TEXT.removeComparisonLabel}
            data-testid="condition-remove-node"
            onClick={onRemove}
            className="text-muted-foreground hover:text-destructive"
          >
            <span aria-hidden="true">×</span>
          </button>
        )}
      </div>
    )
  }

  return (
    <ConditionGroupNode group={node} depth={depth} projectKey={projectKey} onChange={onChange} onRemove={onRemove} idFor={idFor} />
  )
}

interface ConditionGroupNodeProps {
  /** 편집 중인 그룹 노드 */
  readonly group: ConditionGroup
  /** 재귀 깊이(0=루트) */
  readonly depth: number
  /** 담당자/보고자 값 위젯에 넘길 프로젝트 식별 키 */
  readonly projectKey: string
  /** 이 그룹 자체의 변경 콜백 */
  readonly onChange: (next: ConditionGroup) => void
  /** 이 그룹을 부모에서 제거하는 콜백 — 루트(부모 없음)면 undefined(삭제 버튼 미노출). */
  readonly onRemove?: () => void
  /** 노드 객체 참조별 안정 key 조회 함수 */
  readonly idFor: (node: ConditionNode) => string
}

/**
 * 그룹 노드 — op(and/or) 토글 + negated 체크박스 + 자식 순회({@link ConditionNodeEditor}에 위임) +
 * 조건/그룹 추가 버튼 + (있으면) 삭제 버튼.
 *
 * 자식이 없으면 "조건을 추가하세요" 힌트를 보여준다(EC12/G2 — 빈 그룹은 직렬화 시 prune되어 사라진다는
 * 사실을 사용자가 인지하도록). 각 자식은 {@link NESTED_CHILD_CLASS}로 좌측 테두리 + 들여쓰기를 적용해
 * depth를 시각적으로 표현한다(D1).
 */
function ConditionGroupNode({ group, depth, projectKey, onChange, onRemove, idFor }: ConditionGroupNodeProps): JSX.Element {
  return (
    <div data-testid="condition-group" className={GROUP_CONTAINER_CLASS}>
      <div className="flex flex-wrap items-center gap-3 mb-2">
        <button
          type="button"
          data-testid="condition-group-op-toggle"
          onClick={() => {
            onChange(toggleGroupOp(group))
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
              onChange(toggleGroupNegate(group))
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
        {group.children.map((child, index) => (
          <div key={idFor(child)} className={NESTED_CHILD_CLASS}>
            <ConditionNodeEditor
              node={child}
              depth={depth + 1}
              projectKey={projectKey}
              onChange={(next) => {
                onChange(updateChildAt(group, index, next))
              }}
              onRemove={() => {
                onChange(removeChildAt(group, index))
              }}
              idFor={idFor}
            />
          </div>
        ))}
      </div>

      <div className="flex gap-2 mt-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          data-testid="condition-add-comparison"
          onClick={() => {
            onChange(addComparisonChild(group))
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
            onChange(addGroupChild(group))
          }}
        >
          {TEXT.addGroupButton}
        </Button>
      </div>
    </div>
  )
}

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
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 조건 트리(And/Or/Not 그룹 + Comparison leaf) 재귀 편집 컴포넌트.
 *
 * `ActionListEditor` 위임 패턴 동형 — 완전한 controlled 컴포넌트로 `value`/`onChange`로만 트리 상태를
 * 주고받는다. 루트 노드는 삭제 버튼을 렌더하지 않는다(부모가 없어 제거할 대상이 없다). 루트가 그룹이 아닌
 * 단일 Comparison이어도({@link ConditionComparisonRow}만 렌더) 지원한다.
 *
 * 재귀 렌더는 {@link ConditionNodeEditor}/{@link ConditionGroupNode}에, 트리 변형은
 * {@link updateChildAt}/{@link removeChildAt}/{@link addComparisonChild}/{@link addGroupChild}/
 * {@link toggleGroupOp}/{@link toggleGroupNegate} 순수 함수에 위임한다.
 *
 * JSON 직렬화(`serializeConditionExpression`)는 이 컴포넌트의 책임이 아니다 — 폼 상태(`ConditionNode`)만
 * 편집하고, 저장 시 직렬화는 소비자(`AutomationRuleFormDialog`, Task 5)가 수행한다.
 */
export function ConditionBuilder({ value, onChange, projectKey }: ConditionBuilderProps): JSX.Element {
  const idFor = useNodeIdCache()

  return <ConditionNodeEditor node={value} depth={0} projectKey={projectKey} onChange={onChange} idFor={idFor} />
}
