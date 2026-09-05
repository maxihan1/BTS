// 보드 설정 — 카드 레이아웃 탭 본문 (부채 177 Task 16 · J17·J18)
import type { JSX } from 'react'
import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import type { BoardDetail } from '@/api/boards'
import { replaceCardLayout } from '@/api/board-settings'
import type { CardLayout } from '@/api/board-settings'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { Checkbox } from '@/components/ui/checkbox'

/** 카드에 얹을 수 있는 표준 필드 — 백엔드 `CardLayoutFieldKey` 미러(순서까지 같다). */
const STANDARD_FIELDS = [
  { key: 'EPIC', label: '에픽' },
  { key: 'PRIORITY', label: '우선순위' },
  { key: 'ASSIGNEE', label: '담당자' },
  { key: 'LABELS', label: '라벨' },
  { key: 'ESTIMATE', label: '추정치' },
  { key: 'ISSUE_TYPE', label: '이슈 종류' },
] as const

/** 커스텀 필드 키 접두사 — 백엔드 `CardLayoutSettingsService.CUSTOM_FIELD_PREFIX` 미러. */
const CUSTOM_FIELD_PREFIX = 'cf_'

/** 화면 문구. */
const labels = {
  heading: '카드에 표시할 필드',
}

/** CardLayoutPanel props */
export interface CardLayoutPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회 API 를 만들지 않았다. */
  board: BoardDetail
  /** 편집 권한 (CREATE). 없으면 후보를 고를 수 없다 — 목록은 그대로 보인다(S7). */
  canConfigure: boolean
}

/**
 * 지라 Board settings 의 **Card layout 탭** 본문 (J17·J18).
 *
 * 카드에 얹을 추가 필드를 고른다. 요약은 항상 맨 위라 후보에 없다(J19 · R2).
 */
export function CardLayoutPanel({ board, canConfigure }: CardLayoutPanelProps): JSX.Element {
  const groupId = useId()
  const [selected, setSelected] = useState<readonly string[]>([])
  const customFieldsQuery = useCustomFields(board.projectKey)

  const mutation = useMutation<CardLayout, unknown, readonly string[]>({
    mutationFn: (fieldKeys) => replaceCardLayout(board.boardId, 'BOARD', fieldKeys),
  })

  const candidates = [
    ...STANDARD_FIELDS.map((field) => ({ key: field.key as string, label: field.label as string })),
    ...(customFieldsQuery.data ?? []).map((field) => ({
      key: `${CUSTOM_FIELD_PREFIX}${field.key}`,
      label: field.name,
    })),
  ]

  function handleToggle(key: string, checked: boolean): void {
    const next = checked ? [...selected, key] : selected.filter((entry) => entry !== key)
    setSelected(next)
    mutation.mutate(next)
  }

  return (
    <section aria-labelledby={groupId} className="space-y-4">
      <h2 id={groupId} className="text-sm font-semibold">
        {labels.heading}
      </h2>

      <ul className="space-y-1">
        {candidates.map((candidate) => (
          <li key={candidate.key} className="flex items-center gap-2">
            <Checkbox
              id={`${groupId}-${candidate.key}`}
              checked={selected.includes(candidate.key)}
              disabled={!canConfigure}
              onCheckedChange={(checked) => {
                handleToggle(candidate.key, checked === true)
              }}
            />
            <label
              htmlFor={`${groupId}-${candidate.key}`}
              className="text-foreground flex min-h-11 flex-1 cursor-pointer items-center text-sm md:min-h-8"
            >
              {candidate.label}
            </label>
          </li>
        ))}
      </ul>
    </section>
  )
}
