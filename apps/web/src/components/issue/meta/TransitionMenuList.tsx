// 이슈 상태 메뉴의 전환 목록 — 상세 화면과 목록 셀이 같은 순서·같은 모양으로 쓴다 (FR-WF-01 D9)
import type { JSX } from 'react'
import { ArrowRightIcon, RepeatIcon } from 'lucide-react'
import type { IssueTransition } from '@/api/issues'
import { stateCategorySchema } from '@/api/workflows'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { CELL_OPTION_CLASS } from '@/components/issues/cells/EditableCell'
import type { StatusMeta } from '@/components/issues/IssueTable'
import { statusBadgeVariant } from '@/components/issues/issue-visuals'
import { issueDetailStrings } from '@/i18n/ko'
import { transitionElementKey } from '@/lib/transition-key'

/** TransitionMenuList props */
export interface TransitionMenuListProps {
  /** 가용 전환 — **서버 순서 그대로** 그린다. 순서의 정본은 편집기가 정한 `display_order` 다 */
  transitions: IssueTransition[]
  /** 상태 키 → 표시 이름·카테고리. 도착 상태 이름의 출처다. 못 찾으면 원시 키로 폴백한다 */
  statusMetaByKey: Map<string, StatusMeta>
  /** 전 항목 비활성 — 권한 없음 · 저장 중 */
  disabled: boolean
  /** 고른 전환 자체를 넘긴다 — 같은 상태쌍의 전환 둘을 호출자가 되찾을 수 있게 */
  onSelect: (transition: IssueTransition) => void
}

/** 상태를 바꾸지 않는 전환인가 — GLOBAL 은 엔진이 현재 상태 도착을 이미 뺀다 */
function isLoop(transition: IssueTransition): boolean {
  return transition.fromStateKey === transition.toStateKey
}

/**
 * 전환 목록.
 *
 * - 상태를 옮기는 전환은 「전환 이름 → 도착 상태 배지(범주 색)」. 이름이 도착 상태와 같으면
 *   배지만 둔다(「완료 → 완료」 중복을 피한다).
 * - 자기 루프 전환은 아래에 따로 묶는다 — 눌러도 상태가 그대로라 같은 줄에 섞으면 상태를 옮기는
 *   항목으로 읽힌다(Jira 는 「work item actions」로 따로 둔다).
 * - 한 목록 안에서 순서는 서버가 준 그대로다. 묶음을 나눌 뿐 다시 정렬하지 않는다.
 *
 * @param props 전환 · 상태 맵 · 비활성 · 선택 콜백
 * @returns 전환 버튼 목록
 */
export function TransitionMenuList({
  transitions,
  statusMetaByKey,
  disabled,
  onSelect,
}: TransitionMenuListProps): JSX.Element {
  // ★key 는 원래 배열 위치로 만든다 — 묶음별로 다시 세면 형제 사이 고유성이 깨질 수 있다.
  const indexed = transitions.map((transition, index) => ({ transition, key: transitionElementKey(transition, index) }))
  const moves = indexed.filter(({ transition }) => !isLoop(transition))
  const loops = indexed.filter(({ transition }) => isLoop(transition))

  return (
    <div className="flex flex-col gap-0.5">
      {moves.map(({ transition, key }) => {
        const meta = statusMetaByKey.get(transition.toStateKey)
        const targetName = meta?.name ?? transition.toStateKey
        // 엔진이 준 범주가 이 워크플로우의 정본이다. 없으면(구 응답) 카탈로그 범주로 폴백한다.
        const parsed = stateCategorySchema.safeParse(transition.toCategory)
        const category = parsed.success ? parsed.data : meta?.category
        return (
          <Button
            key={key}
            type="button"
            variant="ghost"
            size="sm"
            disabled={disabled}
            onClick={() => onSelect(transition)}
            className={`${CELL_OPTION_CLASS} gap-1.5`}
          >
            {transition.name !== targetName && (
              <>
                <span className="truncate">{transition.name}</span>
                {/* 접근성 이름이 「이름도착」으로 붙지 않게 — 화살표 아이콘은 aria-hidden 이다 */}{' '}
                <ArrowRightIcon aria-hidden="true" className="size-3 shrink-0 text-(--text-subtle)" />
              </>
            )}
            <Badge variant={statusBadgeVariant(category)} className="max-w-full">
              <span className="truncate">{targetName}</span>
            </Badge>
          </Button>
        )
      })}

      {loops.length > 0 && (
        <>
          {moves.length > 0 && <Separator className="my-1" />}
          <p className="px-2 py-1 text-xs text-(--text-subtle)">{issueDetailStrings.transitionLoopHeading}</p>
          {loops.map(({ transition, key }) => (
            <Button
              key={key}
              type="button"
              variant="ghost"
              size="sm"
              disabled={disabled}
              onClick={() => onSelect(transition)}
              className={`${CELL_OPTION_CLASS} gap-1.5`}
            >
              <RepeatIcon aria-hidden="true" className="size-3 shrink-0 text-(--text-subtle)" />
              <span className="truncate">{transition.name}</span>
            </Button>
          ))}
        </>
      )}
    </div>
  )
}
