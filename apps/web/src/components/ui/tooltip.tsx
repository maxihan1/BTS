// Tooltip 프리미티브 — hover/focus 시 role="tooltip" 콘텐츠를 노출한다
import * as React from "react"
import { Tooltip as TooltipPrimitive } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * Radix Popper 기반 오버레이(Tooltip/Popover 공통) 진입·퇴장 애니메이션 클래스.
 * `data-state` 값에 따라 Radix가 부여하는 `data-open`/`data-closed` presence
 * 속성과 `data-side` 방향 속성을 조합해 fade+zoom+slide를 구성한다.
 */
const OVERLAY_TRANSITION_CLASSES =
  "duration-100 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95"

/** Tooltip 전역 delay 설정 — 기본 delayDuration을 0으로 낮춰 즉시 반응하게 한다. */
function TooltipProvider({
  delayDuration = 0,
  ...props
}: React.ComponentProps<typeof TooltipPrimitive.Provider>) {
  return (
    <TooltipPrimitive.Provider
      data-slot="tooltip-provider"
      delayDuration={delayDuration}
      {...props}
    />
  )
}

/** Tooltip Root — 별도 Provider 래핑 없이도 단독으로 동작하도록 내부에서 자체 감싼다. */
function Tooltip({
  ...props
}: React.ComponentProps<typeof TooltipPrimitive.Root>) {
  return (
    <TooltipProvider>
      <TooltipPrimitive.Root data-slot="tooltip" {...props} />
    </TooltipProvider>
  )
}

function TooltipTrigger({
  ...props
}: React.ComponentProps<typeof TooltipPrimitive.Trigger>) {
  return <TooltipPrimitive.Trigger data-slot="tooltip-trigger" {...props} />
}

/** Tooltip 콘텐츠 — Portal + `role="tooltip"` 노출(Radix 기본 계약) + fade/zoom/slide 애니메이션. */
function TooltipContent({
  className,
  sideOffset = 4,
  children,
  ...props
}: React.ComponentProps<typeof TooltipPrimitive.Content>) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Content
        data-slot="tooltip-content"
        sideOffset={sideOffset}
        className={cn(
          "z-50 w-fit origin-(--radix-tooltip-content-transform-origin) rounded-md bg-popover px-3 py-1.5 text-xs text-balance text-popover-foreground shadow-md ring-1 ring-foreground/10",
          OVERLAY_TRANSITION_CLASSES,
          className
        )}
        {...props}
      >
        {children}
      </TooltipPrimitive.Content>
    </TooltipPrimitive.Portal>
  )
}

export { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider }
