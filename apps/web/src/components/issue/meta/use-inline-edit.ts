// 이슈 상세 「누르면 편집」 칸의 열고 닫기 — 바깥 클릭 · Esc · 포커스 · 단축키 손잡이 (JV-17)
import type { KeyboardEvent, RefObject } from 'react'
import { useEffect, useImperativeHandle, useRef, useState } from 'react'

/** 단축키(`a` · `l`)가 쥐는 손잡이 — `routes/issues.$key.tsx` 는 `.focus()` 만 부른다 */
export type FocusHandle = Pick<HTMLElement, 'focus'>

/**
 * 모달 Esc 가드가 찾는 표지 — `IssueDetailModal` 의 `onEscapeKeyDown` 이 이 속성 안에서 난 Esc 는
 * 창 닫기로 쓰지 않는다. Radix 는 document capture 로 Esc 를 먼저 잡아서 칸 쪽 `preventDefault` 가 늦다.
 */
export const INLINE_EDIT_ATTR = 'data-inline-edit'

interface UseInlineEditOptions {
  /** 단축키 손잡이. 접힌 상태에서 `.focus()` 가 오면 편집을 열고, 열린 뒤 입력칸에 포커스한다 */
  focusRef?: RefObject<FocusHandle | null>
  /** false 면 열지 않는다 — 단축키도 무동작(입력칸이 disabled 이던 종전과 같다) */
  canEdit: boolean
  /** 닫힐 때마다 부른다(검색어 비우기 · 고치던 칩 되돌리기) */
  onClose?: () => void
  /** 바깥 클릭이면 닫을지(기본 true). 라벨은 고치던 칩을 잃지 않게 저장 · 취소 · Esc 로만 닫는다 */
  closeOnOutsideClick?: boolean
  /** true 면 늘 열린 칸 — 생성 폼 · 전환 화면처럼 폼 안에 들어가는 쓰임 */
  alwaysOpen?: boolean
}

/** 칸 안 첫 입력칸에 포커스 — 라벨 입력은 `LabelChipsEditor` 두 단계 아래라 ref 대신 찾아 쓴다 */
function focusFirstInput(container: HTMLElement | null): void {
  container?.querySelector('input')?.focus()
}

/**
 * 「평소엔 글자, 누르면 편집」 칸의 상태.
 *
 * - 열리면 칸 안 첫 입력칸에 포커스, Esc 로 닫히면 여는 단추(`triggerRef`)로 포커스를 돌려준다(WCAG 2.4.3).
 * - 바깥 `mousedown` 이면 닫는다(`closeOnOutsideClick`). `blur` 로 재지 않는 이유 — Safari 는 단추를 눌러도 포커스를 주지 않아
 *   후보를 고르는 클릭이 닿기 전에 칸이 먼저 닫힌다.
 * - Esc 는 `preventDefault` 한다. 안 하면 pane 의 document Esc 리스너가 상세 창까지 닫는다.
 */
export function useInlineEdit({
  focusRef,
  canEdit,
  onClose,
  closeOnOutsideClick = true,
  alwaysOpen = false,
}: UseInlineEditOptions) {
  const [editing, setEditing] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const returnFocusRef = useRef(false)
  const isOpen = alwaysOpen || editing

  useImperativeHandle(
    focusRef,
    () => ({
      focus: () => {
        if (isOpen) focusFirstInput(containerRef.current)
        else if (canEdit) setEditing(true)
      },
    }),
    [isOpen, canEdit],
  )

  useEffect(() => {
    if (editing) {
      focusFirstInput(containerRef.current)
    } else if (returnFocusRef.current) {
      returnFocusRef.current = false
      triggerRef.current?.focus()
    }
  }, [editing])

  useEffect(() => {
    if (!editing || !closeOnOutsideClick) return
    function handleMouseDown(e: MouseEvent): void {
      if (e.target instanceof Node && containerRef.current?.contains(e.target) === true) return
      setEditing(false)
      onClose?.()
    }
    document.addEventListener('mousedown', handleMouseDown)
    return () => document.removeEventListener('mousedown', handleMouseDown)
  }, [editing, closeOnOutsideClick, onClose])

  /** 닫는다. `returnFocus` 면 여는 단추로 포커스를 돌려준다(Esc · 고르기 · 저장 · 취소) */
  function close(returnFocus: boolean): void {
    returnFocusRef.current = returnFocus
    setEditing(false)
    onClose?.()
  }

  function handleKeyDown(e: KeyboardEvent): void {
    if (e.key !== 'Escape' || alwaysOpen) return
    e.preventDefault()
    close(true)
  }

  return {
    isOpen,
    open: () => setEditing(true),
    close,
    triggerRef,
    // 늘 열린 칸(폼 안)은 Esc 가 칸이 아니라 바깥(창)의 몫이라 표지를 달지 않는다
    containerProps: { ref: containerRef, onKeyDown: handleKeyDown, [INLINE_EDIT_ATTR]: alwaysOpen ? undefined : '' },
  }
}
