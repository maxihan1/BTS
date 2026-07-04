// 단축키 도움말 모달 — SHORTCUTS 단일 진실 출처를 렌더 (FR-UX-05)
import type { JSX } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { PALETTE_HELP_ITEM, SHORTCUTS } from './shortcuts'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ShortcutsHelpDialog Props */
export interface ShortcutsHelpDialogProps {
  /** 모달 열림 여부 */
  readonly open: boolean
  /** 열림/닫힘 상태 변경 시 호출(Esc·오버레이 클릭·`?` 토글은 Radix/호출부가 발화) */
  readonly onOpenChange: (open: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 보조 컴포넌트 — 키 조합 표기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 단축키 키 배열을 `<kbd>` 시퀀스로 표기한다 (예: `['g', 'i']` → `g` `i`).
 *
 * @param keys 표기할 키 목록
 */
function ShortcutKeys({ keys }: { readonly keys: readonly string[] }): JSX.Element {
  return (
    <span className="flex gap-1">
      {keys.map((key, index) => (
        <kbd
          key={`${key}-${index}`}
          className="rounded border border-border bg-muted px-1.5 py-0.5 text-xs font-mono"
        >
          {key}
        </kbd>
      ))}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 키보드 단축키 도움말 모달.
 *
 * - `SHORTCUTS` 레지스트리(shortcuts.ts)를 그대로 렌더한다 — 표기 drift 없는
 *   단일 진실 출처(FR8). 비구현 단축키(`j`/`k`/`e`/`m`/`s`)는 레지스트리에
 *   없으므로 자동으로 표시되지 않는다.
 * - 명령 팔레트(`Cmd+K`, FR-UX-04)는 별도 훅이 처리하므로 표기 전용 항목
 *   `PALETTE_HELP_ITEM`을 마지막에 덧붙인다.
 * - 접근성(포커스 트랩·Esc 닫기·포커스 복원)은 radix-ui Dialog가 제공한다
 *   (ResolutionPickerModal 선례).
 */
export function ShortcutsHelpDialog({
  open,
  onOpenChange,
}: ShortcutsHelpDialogProps): JSX.Element {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="text-base font-semibold mb-4">
            키보드 단축키
          </DialogPrimitive.Title>

          <dl className="space-y-2">
            {SHORTCUTS.map((shortcut) => (
              <div
                key={shortcut.description}
                className="flex items-center justify-between gap-4"
              >
                <dt className="text-sm text-muted-foreground">{shortcut.description}</dt>
                <dd>
                  <ShortcutKeys keys={shortcut.keys} />
                </dd>
              </div>
            ))}
            <div className="flex items-center justify-between gap-4">
              <dt className="text-sm text-muted-foreground">{PALETTE_HELP_ITEM.description}</dt>
              <dd>
                <ShortcutKeys keys={PALETTE_HELP_ITEM.keys} />
              </dd>
            </div>
          </dl>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
