// 다중 액션 리스트 편집기 — 행 추가/삭제/순서변경(위/아래) + 빈 상태 CTA (FR-AT-02 D6 Task 5)
import type { JSX } from 'react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { ActionConfigEditor } from './ActionConfigEditor'
import type { ActionFormState } from './ActionConfigEditor'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — automation BC 관례 고정 한국어 (i18n 미도입)
// ─────────────────────────────────────────────────────────────────────────────

const TEXT = {
  addActionButton: '액션 추가',
  emptyStateMessage: '아직 액션이 없습니다 — 트리거만으로도 유효하지만, 액션을 추가하면 자동 실행됩니다.',
  moveUpLabel: '위로',
  moveDownLabel: '아래로',
} as const

/** "액션 추가" 클릭 시 새로 추가되는 기본 액션 — ActionConfigEditor의 SET_FIELD 기본값과 동일 형태 */
const DEFAULT_NEW_ACTION: ActionFormState = { type: 'SET_FIELD', config: { field: 'summary', value: '' } }

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ActionListEditor props */
export interface ActionListEditorProps {
  /** 담당자 피커(ASSIGN 행)에 넘길 프로젝트 식별 키 */
  readonly projectKey: string
  /** 현재 액션 목록 폼 상태(순서 = position) */
  readonly value: ActionFormState[]
  /** 액션 목록 변경 콜백 — 추가/삭제/순서변경/행 편집 모두 이 콜백으로 새 배열을 방출한다 */
  readonly onChange: (next: ActionFormState[]) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 다중 액션 리스트 편집기.
 *
 * 각 행을 보더/카드 컨테이너로 시각 격리해 렌더한다(design-review#3) — {@link ActionConfigEditor} +
 * 순서 버튼(↑/↓) + 삭제(×). 첫 행은 위로, 마지막 행은 아래로 버튼이 비활성화된다.
 * 액션 0개(EC5, 트리거만으로도 유효한 룰)일 때는 안내 문구 + "액션 추가" CTA만 보여준다(design-review#2).
 *
 * 완전한 controlled 컴포넌트다 — `value`/`onChange`로만 목록 상태를 주고받는다. 행 렌더 key는
 * 인덱스가 아니라 행별로 부여한 안정적 클라이언트 id를 쓴다 — 순서변경 시 인덱스만 같으면 같은
 * React key로 취급돼 ActionConfigEditor 내부 상태(라벨 태그 draft 등)가 다른 행으로 새는 문제를
 * 막는다([[react-usestate-stale-key-prop]]).
 */
export function ActionListEditor({ projectKey, value, onChange }: ActionListEditorProps): JSX.Element {
  const [ids, setIds] = useState<string[]>(() => value.map(() => crypto.randomUUID()))

  function handleAdd(): void {
    setIds((prev) => [...prev, crypto.randomUUID()])
    onChange([...value, DEFAULT_NEW_ACTION])
  }

  function handleRemove(index: number): void {
    setIds((prev) => prev.filter((_id, idIndex) => idIndex !== index))
    onChange(value.filter((_action, actionIndex) => actionIndex !== index))
  }

  function handleMove(index: number, direction: -1 | 1): void {
    const targetIndex = index + direction
    if (targetIndex < 0 || targetIndex >= value.length) return

    setIds((prev) => {
      const next = [...prev]
      const current = next[index]
      const target = next[targetIndex]
      if (current === undefined || target === undefined) return prev
      next[index] = target
      next[targetIndex] = current
      return next
    })

    const nextValue = [...value]
    const current = nextValue[index]
    const target = nextValue[targetIndex]
    if (current === undefined || target === undefined) return
    nextValue[index] = target
    nextValue[targetIndex] = current
    onChange(nextValue)
  }

  function handleRowChange(index: number, next: ActionFormState): void {
    onChange(value.map((action, actionIndex) => (actionIndex === index ? next : action)))
  }

  if (value.length === 0) {
    return (
      <div className="rounded-lg border-2 border-dashed border-border px-4 py-6 text-center">
        <p className="text-sm text-muted-foreground mb-3">{TEXT.emptyStateMessage}</p>
        <Button type="button" variant="outline" size="sm" onClick={handleAdd}>
          {TEXT.addActionButton}
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <ul className="space-y-3">
        {value.map((action, index) => {
          const rowId = ids[index] ?? String(index)
          return (
            <li key={rowId} className="rounded-md border border-input p-3">
              <div className="flex items-start justify-between gap-2 mb-3">
                <div className="flex gap-1">
                  <button
                    type="button"
                    aria-label={TEXT.moveUpLabel}
                    disabled={index === 0}
                    onClick={() => {
                      handleMove(index, -1)
                    }}
                    className="rounded p-1 text-muted-foreground hover:text-foreground disabled:opacity-30 disabled:cursor-not-allowed"
                  >
                    <span aria-hidden="true">↑</span>
                  </button>
                  <button
                    type="button"
                    aria-label={TEXT.moveDownLabel}
                    disabled={index === value.length - 1}
                    onClick={() => {
                      handleMove(index, 1)
                    }}
                    className="rounded p-1 text-muted-foreground hover:text-foreground disabled:opacity-30 disabled:cursor-not-allowed"
                  >
                    <span aria-hidden="true">↓</span>
                  </button>
                </div>
                <button
                  type="button"
                  aria-label={`${index + 1}번째 액션 삭제`}
                  onClick={() => {
                    handleRemove(index)
                  }}
                  className="text-muted-foreground hover:text-destructive"
                >
                  <span aria-hidden="true">×</span>
                </button>
              </div>
              <ActionConfigEditor
                projectKey={projectKey}
                value={action}
                onChange={(next) => {
                  handleRowChange(index, next)
                }}
                idPrefix={`action-${rowId}`}
              />
            </li>
          )
        })}
      </ul>
      <Button type="button" variant="outline" size="sm" onClick={handleAdd}>
        {TEXT.addActionButton}
      </Button>
    </div>
  )
}
