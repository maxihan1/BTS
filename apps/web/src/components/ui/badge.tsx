// 상태/라벨 로젠지 프리미티브 — cva 기반 5색 variant (Jira 로젠지 대응, 색상만 담당)
import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const badgeVariants = cva(
  "inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-full border border-transparent px-2 py-0.5 text-xs font-medium whitespace-nowrap",
  {
    variants: {
      variant: {
        default: "bg-(--bg-neutral) text-foreground",
        neutral: "bg-(--bg-neutral) text-foreground",
        blue: "bg-info/10 text-info-text",
        green: "bg-success/10 text-success-text",
        red: "bg-danger/10 text-danger-text",
        yellow: "bg-warning/10 text-warning-text",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
)

function Badge({
  className,
  variant,
  ...props
}: React.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return (
    <span
      data-slot="badge"
      data-variant={variant ?? "default"}
      className={cn(badgeVariants({ variant }), className)}
      {...props}
    />
  )
}

export { Badge, badgeVariants }
