// 로딩 상태 표시용 스켈레톤 프리미티브
import * as React from "react"

import { cn } from "@/lib/utils"

function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="skeleton"
      className={cn("animate-pulse rounded-md bg-(--bg-neutral)", className)}
      {...props}
    />
  )
}

export { Skeleton }
