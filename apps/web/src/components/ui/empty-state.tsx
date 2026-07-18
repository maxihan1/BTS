// 빈 상태(empty state) 프리미티브 — 아이콘/타이틀/설명/액션 슬롯을 중앙 정렬로 배치
import * as React from "react"

import { cn } from "@/lib/utils"

interface EmptyStateProps extends React.ComponentProps<"div"> {
  icon?: React.ReactNode
  title: string
  description?: string
  action?: React.ReactNode
}

function EmptyState({
  icon,
  title,
  description,
  action,
  className,
  ...props
}: EmptyStateProps) {
  return (
    <div
      data-slot="empty-state"
      className={cn(
        "flex flex-col items-center justify-center gap-2 px-4 py-12 text-center",
        className
      )}
      {...props}
    >
      {icon ? (
        <div data-slot="empty-state-icon" className="mb-2 text-(--text-subtle)">
          {icon}
        </div>
      ) : null}
      <p data-slot="empty-state-title" className="text-sm font-medium">
        {title}
      </p>
      {description ? (
        <p data-slot="empty-state-description" className="text-sm text-(--text-subtle)">
          {description}
        </p>
      ) : null}
      {action ? (
        <div data-slot="empty-state-action" className="mt-4">
          {action}
        </div>
      ) : null}
    </div>
  )
}

export { EmptyState }
export type { EmptyStateProps }
