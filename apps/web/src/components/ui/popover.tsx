// Popover 프리미티브 — 트리거 클릭 시 Portal 콘텐츠를 노출한다
import * as React from "react"
import { Popover as PopoverPrimitive } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * Radix Popper 기반 오버레이(Tooltip/Popover 공통) 진입·퇴장 애니메이션 클래스.
 * `data-state` 값에 따라 Radix가 부여하는 `data-open`/`data-closed` presence
 * 속성과 `data-side` 방향 속성을 조합해 fade+zoom+slide를 구성한다.
 */
const OVERLAY_TRANSITION_CLASSES =
  "duration-100 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95"

function Popover({
  ...props
}: React.ComponentProps<typeof PopoverPrimitive.Root>) {
  return <PopoverPrimitive.Root data-slot="popover" {...props} />
}

function PopoverTrigger({
  ...props
}: React.ComponentProps<typeof PopoverPrimitive.Trigger>) {
  return <PopoverPrimitive.Trigger data-slot="popover-trigger" {...props} />
}

function PopoverAnchor({
  ...props
}: React.ComponentProps<typeof PopoverPrimitive.Anchor>) {
  return <PopoverPrimitive.Anchor data-slot="popover-anchor" {...props} />
}

/**
 * Popover 콘텐츠 — 트리거 클릭 시 Portal로 렌더, focus-visible은 `--border-focus` 토큰을 사용한다.
 *
 * ★ `container` 는 **다이얼로그 안에서 쓸 때 필요하다.** Portal 은 기본으로
 * `document.body` 에 그리는데, 열린 Radix Dialog 는 body 의 형제 노드를
 * `aria-hidden="true"` 로 덮는다. 그러면 popover 안의 옵션이 접근성 트리에서 사라져
 * 스크린리더 사용자가 **고를 수 없다**(실측 — `getByRole('option')` 이 못 찾는다).
 * 다이얼로그 안쪽 요소를 container 로 주면 그 덮개 밖으로 나가지 않는다.
 */
function PopoverContent({
  className,
  align = "center",
  sideOffset = 4,
  container,
  ...props
}: React.ComponentProps<typeof PopoverPrimitive.Content> & {
  /** Portal 대상. 생략하면 `document.body` (Radix 기본) */
  container?: HTMLElement | null
}) {
  return (
    <PopoverPrimitive.Portal container={container ?? undefined}>
      <PopoverPrimitive.Content
        data-slot="popover-content"
        align={align}
        sideOffset={sideOffset}
        className={cn(
          "z-50 w-72 origin-(--radix-popover-content-transform-origin) rounded-lg bg-popover p-4 text-popover-foreground shadow-md ring-1 ring-foreground/10 focus-visible:outline-2 focus-visible:outline-(--border-focus) focus-visible:outline-offset-2",
          OVERLAY_TRANSITION_CLASSES,
          className
        )}
        {...props}
      />
    </PopoverPrimitive.Portal>
  )
}

export { Popover, PopoverTrigger, PopoverContent, PopoverAnchor }
