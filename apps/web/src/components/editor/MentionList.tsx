// TipTap suggestion 팝업 — 기존 MentionDropdown 을 그대로 감싼다 (FR-MN-02 UI 재사용)
import type { JSX } from 'react'
import { forwardRef, useEffect, useImperativeHandle, useState } from 'react'
import type { SuggestionProps } from '@tiptap/suggestion'
import type { UserSummary } from '@/api/users'
import { MentionDropdown } from '@/components/issue/mention/MentionDropdown'

/** TipTap 이 키 이벤트를 위임하기 위해 요구하는 인터페이스. */
export interface MentionListHandle {
  /** 처리했으면 true — 그러면 TipTap 이 에디터로 키를 넘기지 않는다. */
  onKeyDown: (event: KeyboardEvent) => boolean
}

/**
 * 멘션 자동완성 목록.
 *
 * ## 왜 새 UI 를 만들지 않았나
 *
 * `MentionDropdown` 은 props 만 받는 순수 컴포넌트다 — `candidates` · `activeIndex` ·
 * `onSelect`. textarea 시절의 훅에 묶여 있지 않아 그대로 재사용된다(계약 §4 재사용 자산).
 * 목록 항목의 생김새·ARIA(`role="listbox"`/`option`)·키보드 관례가 전부 보존된다.
 *
 * 이 컴포넌트가 더하는 것은 **TipTap 과의 접점**뿐이다 — 키 위임과 선택 커밋.
 */
export const MentionList = forwardRef<MentionListHandle, SuggestionProps<UserSummary>>(
  function MentionList({ items, command }, ref): JSX.Element | null {
    const [activeIndex, setActiveIndex] = useState(0)

    // 후보가 바뀌면 커서를 처음으로 돌린다 — 이전 인덱스가 범위를 벗어나면 Enter 가 무동작이 된다.
    useEffect(() => { setActiveIndex(0) }, [items])

    /** 후보를 확정한다. `id` 가 저장될 값이므로 username 을 넣는다 — 서버 마크업과 같은 형태다. */
    function select(index: number): void {
      const picked = items[index]
      if (picked === undefined) return
      command({ id: picked.username, label: picked.displayName ?? picked.username } as never)
    }

    useImperativeHandle(ref, () => ({
      onKeyDown: (event) => {
        if (items.length === 0) return false
        if (event.key === 'ArrowUp') {
          setActiveIndex((i) => (i + items.length - 1) % items.length)
          return true
        }
        if (event.key === 'ArrowDown') {
          setActiveIndex((i) => (i + 1) % items.length)
          return true
        }
        // Tab 도 확정으로 받는다 — 기존 textarea 구현과 같은 관례다.
        if (event.key === 'Enter' || event.key === 'Tab') {
          select(activeIndex)
          return true
        }
        return false
      },
    }))

    if (items.length === 0) return null

    return (
      <MentionDropdown
        candidates={items}
        activeIndex={activeIndex}
        onSelect={(candidate) => {
          const index = items.indexOf(candidate)
          if (index >= 0) select(index)
        }}
      />
    )
  },
)
