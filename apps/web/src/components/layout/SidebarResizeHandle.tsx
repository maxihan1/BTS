// 사이드바 폭 조절 핸들 — WAI-ARIA Window Splitter 패턴 (Jira 패리티 J6)
import { useEffect, useRef, type JSX, type KeyboardEvent, type PointerEvent } from 'react'
import { useSidebarWidth, MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH } from '@/hooks/use-sidebar-width'
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

  // 🛑 드래그 중 언마운트되면 `userSelect: none` 이 body 에 남아 **앱 전체에서 텍스트 선택이
  //    죽는다.** `endDrag` 는 요소 자신의 pointerup/pointercancel 에만 물려 있어 복구가 안 된다.
  //    재현 — 핸들을 누른 채 `[`(전역 사이드바 토글, 의도적으로 살려 둔 키)를 누르면
  //    `useSidebarResizable()` 이 false 가 되어 아래 조기 반환이 이 컴포넌트를 지운다.
  //    ⚠️ 이 훅은 조기 반환 **위**에 있어야 한다 — 아래로 내리면 훅 순서 규칙 위반이다.
  useEffect(
    () => () => {
      document.body.style.userSelect = ''
    },
    [],
  )

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
    // 폭 읽기를 `endDrag` 와 같은 방식(스토어 최신값)으로 통일한다 — 한 파일에서 클로저와
    // getState 를 섞으면 「여기는 왜 다른가」를 다음 사람이 매번 다시 판단해야 한다.
    const current = useSidebarWidth.getState().width
    let next: number | null = null

    if (event.key === 'ArrowLeft') next = current - step
    else if (event.key === 'ArrowRight') next = current + step
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
      // `aria-valuenow` 만 있으면 스크린리더가 「264」라고만 읽어 단위를 알 수 없다.
      aria-valuetext={`${String(width)}픽셀`}
      tabIndex={0}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onKeyDown={handleKeyDown}
      // 히트 영역(6px)과 시각 표시(2px)를 분리한다. 영역 전체를 칠하면 사이드바 경계가
      // 두꺼워진 것처럼 보이고, 반대로 2px 만 두면 조준이 어려워 잡기 전에 포기한다.
      className="group absolute inset-y-0 right-0 z-10 w-1.5 cursor-col-resize focus-visible:outline-2 focus-visible:outline-(--border-focus)"
    >
      {/*
        🛑 `hover:bg-sidebar-accent` 로는 안 된다 — 사이드바 배경과 한 스텝 차이라 실측 결과
           「끌 수 있다」는 신호가 화면에 나타나지 않았다(눈확인 1회차). 브랜드 블루를 써야
           경계선과 구분된다.
      */}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-y-0 left-1/2 w-0.5 -translate-x-1/2 bg-transparent transition-colors group-hover:bg-primary group-focus-visible:bg-primary motion-reduce:transition-none"
      />
    </div>
  )
}
