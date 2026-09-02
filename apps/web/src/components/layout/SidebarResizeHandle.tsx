// 사이드바 폭 조절 핸들 — WAI-ARIA Window Splitter 패턴 (Jira 패리티 J6)
import { useRef, type JSX, type KeyboardEvent, type PointerEvent } from 'react'
import {
  useSidebarWidth,
  MIN_SIDEBAR_WIDTH,
  MAX_SIDEBAR_WIDTH,
  DEFAULT_SIDEBAR_WIDTH,
} from '@/hooks/use-sidebar-width'
import { useSidebarResizable } from '@/hooks/use-sidebar-drawer'
import { navLabels } from '@/i18n/nav-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 화살표 키 1회당 이동량(px) */
const KEY_STEP = 16

/** Shift + 화살표 1회당 이동량(px) — 끝에서 끝까지 5번이면 닿는 폭 */
const KEY_STEP_LARGE = 64

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** SidebarResizeHandle props */
export interface SidebarResizeHandleProps {
  /** 이 핸들이 크기를 바꾸는 요소의 `id` — `aria-controls` 로 연결한다 */
  controlsId: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 오른쪽 경계의 폭 조절 핸들.
 *
 * WAI-ARIA **Window Splitter** 패턴 — `role="separator"` + `tabIndex=0` 이면 스크린리더가
 * 조작 가능한 분할자로 읽고, `aria-valuenow`/`min`/`max` 로 현재 폭을 알린다.
 *
 * 🛑 **레일(64px)·모바일 드로어(264px)에서는 렌더하지 않는다.** 둘 다 고정 폭이라
 *    `aria-valuenow` 가 거짓말이 되고, 끌어도 아무 일이 없는 죽은 컨트롤이 된다. 판정은
 *    {@link useSidebarResizable} 하나가 소유한다.
 *
 * 🛑 **전역 리스너를 만들지 않는다.** `setPointerCapture` 로 이후 이벤트를 이 요소로 몰아
 *    받으므로 `document`/`window` 구독이 필요 없고, 따라서 cleanup 누락 결함이 원천 차단된다.
 *
 * 🛑 **드래그 중에는 영속하지 않는다.** `pointermove` 는 `setWidth`(스토어만), 확정은
 *    `pointerup` 의 `commitWidth` 한 번이다. 반대로 하면 60fps 로 localStorage 를 두들긴다.
 *
 * 🛑 단축키 레지스트리(`SHORTCUTS`·`CONTEXT_SHORTCUTS`)에 **등록하지 않는다.** 요소 로컬
 *    `onKeyDown` 이라, 핸들에 포커스가 있어도 전역 `[`(사이드바 토글)는 그대로 살아 있다.
 */
export function SidebarResizeHandle({ controlsId }: SidebarResizeHandleProps): JSX.Element | null {
  const width = useSidebarWidth((state) => state.width)
  const setWidth = useSidebarWidth((state) => state.setWidth)
  const commitWidth = useSidebarWidth((state) => state.commitWidth)
  const resizable = useSidebarResizable()

  /** 드래그 시작 지점 — null 이면 드래그 중이 아니다 */
  const dragRef = useRef<{ startX: number; startWidth: number } | null>(null)

  if (!resizable) return null

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>): void => {
    event.preventDefault()
    const el = event.currentTarget
    // jsdom 에는 포인터 캡처 API 가 없다 — 있으면 쓰고 없으면 그냥 넘어간다(테스트 환경 방어).
    if (typeof el.setPointerCapture === 'function') el.setPointerCapture(event.pointerId)
    dragRef.current = { startX: event.clientX, startWidth: width }
    // 드래그 중 텍스트가 선택돼 파랗게 끌리는 것을 막는다. `pointerup` 에서 반드시 되돌린다.
    document.body.style.userSelect = 'none'
  }

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>): void => {
    const drag = dragRef.current
    if (drag === null) return
    setWidth(drag.startWidth + (event.clientX - drag.startX))
  }

  const endDrag = (event: PointerEvent<HTMLDivElement>): void => {
    if (dragRef.current === null) return
    const el = event.currentTarget
    if (typeof el.releasePointerCapture === 'function') {
      // 이미 캡처가 풀린 뒤(요소 언마운트 등)면 throw 한다 — 정리 자체는 계속돼야 한다.
      try {
        el.releasePointerCapture(event.pointerId)
      } catch {
        // 캡처가 이미 없다 — 무시
      }
    }
    dragRef.current = null
    document.body.style.userSelect = ''
    // 드래그 중에는 스토어만 갱신했으므로 여기서 한 번 확정한다.
    commitWidth(useSidebarWidth.getState().width)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const step = event.shiftKey ? KEY_STEP_LARGE : KEY_STEP
    let next: number | null = null

    if (event.key === 'ArrowLeft') next = width - step
    else if (event.key === 'ArrowRight') next = width + step
    else if (event.key === 'Home') next = MIN_SIDEBAR_WIDTH
    else if (event.key === 'End') next = MAX_SIDEBAR_WIDTH

    // 처리하지 않는 키는 그대로 흘려보낸다 — 전역 단축키가 핸들 위에서 죽으면 안 된다.
    if (next === null) return
    event.preventDefault()
    commitWidth(next)
  }

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={navLabels.resizeSidebar}
      aria-controls={controlsId}
      aria-valuenow={width}
      aria-valuemin={MIN_SIDEBAR_WIDTH}
      aria-valuemax={MAX_SIDEBAR_WIDTH}
      tabIndex={0}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onDoubleClick={(): void => {
        commitWidth(DEFAULT_SIDEBAR_WIDTH)
      }}
      onKeyDown={handleKeyDown}
      className={
        'absolute inset-y-0 right-0 z-10 w-1.5 cursor-col-resize ' +
        'hover:bg-sidebar-accent focus-visible:outline-2 focus-visible:outline-(--border-focus)'
      }
    />
  )
}
