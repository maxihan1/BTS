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
        blue: "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400",
        green: "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400",
        red: "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400",
        yellow: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400",
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
