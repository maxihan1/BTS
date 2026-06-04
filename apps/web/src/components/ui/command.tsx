// shadcn Command 컴포넌트 — cmdk 래퍼, 라벨 자동완성 등 커맨드 팔레트 UI에서 사용
import * as React from 'react'
import { Command as CommandPrimitive } from 'cmdk'

import { cn } from '@/lib/utils'

// ─────────────────────────────────────────────────────────────────────────────
// Command — 루트 컨테이너
// ─────────────────────────────────────────────────────────────────────────────

function Command({ className, ...props }: React.ComponentProps<typeof CommandPrimitive>) {
  return (
    <CommandPrimitive
      data-slot="command"
      className={cn(
        'flex h-full w-full flex-col overflow-hidden rounded-md bg-popover text-popover-foreground',
        className,
      )}
      {...props}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CommandInput — 검색 입력창
// ─────────────────────────────────────────────────────────────────────────────

function CommandInput({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.Input>) {
  return (
    <CommandPrimitive.Input
      data-slot="command-input"
      className={cn(
        'flex h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none placeholder:text-muted-foreground disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CommandList — 후보 목록 스크롤 컨테이너
// ─────────────────────────────────────────────────────────────────────────────

function CommandList({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.List>) {
  return (
    <CommandPrimitive.List
      data-slot="command-list"
      className={cn('max-h-48 overflow-y-auto overflow-x-hidden', className)}
      {...props}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CommandEmpty — 후보 없음 메시지
// ─────────────────────────────────────────────────────────────────────────────

function CommandEmpty({ ...props }: React.ComponentProps<typeof CommandPrimitive.Empty>) {
  return (
    <CommandPrimitive.Empty
      data-slot="command-empty"
      className="py-2 text-center text-sm text-muted-foreground"
      {...props}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CommandItem — 개별 후보 항목
// ─────────────────────────────────────────────────────────────────────────────

function CommandItem({
  className,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.Item>) {
  return (
    <CommandPrimitive.Item
      data-slot="command-item"
      className={cn(
        'relative flex cursor-pointer select-none items-center rounded-sm px-2 py-1.5 text-sm outline-none data-[disabled=true]:pointer-events-none data-[selected=true]:bg-accent data-[selected=true]:text-accent-foreground data-[disabled=true]:opacity-50',
        className,
      )}
      {...props}
    />
  )
}

export { Command, CommandInput, CommandList, CommandEmpty, CommandItem }
