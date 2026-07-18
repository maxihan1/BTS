// Separator 프리미티브 — 수평/수직 구분선, decorative 기본 true(장식용)
import * as React from "react"
import { Separator as SeparatorPrimitive } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * 구분선 프리미티브 — Radix `Separator.Root` 래퍼.
 *
 * `decorative`가 기본 `true`(장식용, `role="none"`)라 스크린 리더가 무시한다. 콘텐츠
 * 그룹을 의미상 실제로 나누는 자리에는 `decorative={false}`를 넘겨 `role="separator"`를
 * 노출해야 한다.
 */
function Separator({
  className,
  orientation = "horizontal",
  decorative = true,
  ...props
}: React.ComponentProps<typeof SeparatorPrimitive.Root>) {
  return (
    <SeparatorPrimitive.Root
      data-slot="separator"
      decorative={decorative}
      orientation={orientation}
      className={cn(
        "shrink-0 bg-border data-[orientation=horizontal]:h-px data-[orientation=horizontal]:w-full data-[orientation=vertical]:h-full data-[orientation=vertical]:w-px",
        className
      )}
      {...props}
    />
  )
}

export { Separator }
