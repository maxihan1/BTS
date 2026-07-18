// Tabs 프리미티브 — 같은 라우트 내 패널 전환 전용. 라우트 이동엔 쓰지 말 것(nav+Link 사용)
import * as React from "react"
import { Tabs as TabsPrimitive } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * 탭 프리미티브 루트 — Radix `Tabs.Root` 래퍼.
 *
 * 같은 라우트 안에서 패널(콘텐츠)을 전환하는 용도로만 사용한다. 라우트(화면) 자체를
 * 이동하는 네비게이션에는 쓰지 말 것 — 그 경우 nav + `Link`를 사용한다(정본 plan §163,
 * PR19 이슈 상세 활동 탭이 이 프리미티브의 정당한 소비처).
 */
function Tabs({
  className,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Root>) {
  return (
    <TabsPrimitive.Root
      data-slot="tabs"
      className={cn("flex flex-col gap-2", className)}
      {...props}
    />
  )
}

function TabsList({
  className,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.List>) {
  return (
    <TabsPrimitive.List
      data-slot="tabs-list"
      className={cn(
        "inline-flex h-9 w-fit items-center justify-center rounded-lg bg-muted p-1 text-muted-foreground",
        className
      )}
      {...props}
    />
  )
}

function TabsTrigger({
  className,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      data-slot="tabs-trigger"
      className={cn(
        "inline-flex flex-1 items-center justify-center gap-1.5 rounded-md px-2 py-1 text-sm font-medium whitespace-nowrap outline-none select-none disabled:pointer-events-none disabled:opacity-50 hover:bg-(--bg-neutral-hover) focus-visible:outline-2 focus-visible:outline-(--border-focus) focus-visible:outline-offset-2 data-[state=active]:bg-(--bg-selected) data-[state=active]:text-(--text-selected) data-[state=active]:shadow-sm [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
        className
      )}
      {...props}
    />
  )
}

function TabsContent({
  className,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Content>) {
  return (
    <TabsPrimitive.Content
      data-slot="tabs-content"
      className={cn("flex-1 outline-none", className)}
      {...props}
    />
  )
}

export { Tabs, TabsList, TabsTrigger, TabsContent }
