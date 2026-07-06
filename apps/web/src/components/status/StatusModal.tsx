// 상태 설정 모달 — 이모지/텍스트/만료 프리셋 입력 + 저장(replace)/해제 (FR-PR-02 Task 8)
import type { JSX } from 'react'
import { useEffect, useState } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useStatusQuery, useUpdateStatusMutation } from '@/api/useStatus'
import { refreshWhoami } from '@/api/useProfile'
import { resolveExpiry, EXPIRY_PRESETS } from '@/lib/status-expiry'
import type { ExpiryPreset } from '@/lib/status-expiry'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { statusLabels } from '@/i18n/status-labels'

/** StatusModal props — controlled(open/onOpenChange). Header가 소유. */
export interface StatusModalProps {
  readonly open: boolean
  readonly onOpenChange: (open: boolean) => void
}

/** 이모지 최대 길이 — 백엔드 UserStatusService.MAX_EMOJI_LENGTH·NFR3와 일치(단일 이모지+변형선택자 수용). */
const EMOJI_MAX_LENGTH = 32
/** 상태 텍스트 최대 길이 — 백엔드 UserStatusService.MAX_TEXT_LENGTH·NFR3(Slack 관례)와 일치. */
const TEXT_MAX_LENGTH = 100

/** 공백을 null로 정규화한다 — 서버 replace 시맨틱과 일치(둘 다 null이면 서버가 해제). */
function normalize(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/**
 * 상태 설정 모달 (FR-PR-02).
 *
 * 이모지·텍스트·만료 프리셋을 입력해 상태를 원자적으로 교체(replace)하거나 해제한다. 저장 성공 시
 * {@link refreshWhoami}로 authStore.user를 최신화해 Header 상태 배지를 즉시 반영한다(FR-PR-01
 * 프로필 저장 패턴 재사용). 만료 프리셋은 {@link resolveExpiry}로 로컬 기준 절대시각으로 변환한다.
 *
 * 열릴 때마다 현재 상태로 폼을 초기화한다 — 재열림 시 이전 세션 입력이 stale하게 남지 않도록 한다
 * (Radix controlled Dialog 토글닫기 stale 함정 방어).
 */
export function StatusModal({ open, onOpenChange }: StatusModalProps): JSX.Element {
  const { data: current } = useStatusQuery()
  const mutation = useUpdateStatusMutation()

  const [emoji, setEmoji] = useState('')
  const [text, setText] = useState('')
  const [preset, setPreset] = useState<ExpiryPreset>('none')

  useEffect(() => {
    if (open) {
      setEmoji(current?.emoji ?? '')
      setText(current?.text ?? '')
      setPreset('none')
    }
  }, [open, current?.emoji, current?.text])

  function handleSaved(): void {
    void refreshWhoami()
    onOpenChange(false)
  }

  function handleSave(): void {
    mutation.mutate(
      { emoji: normalize(emoji), text: normalize(text), expiresAt: resolveExpiry(preset, new Date()) },
      { onSuccess: handleSaved },
    )
  }

  function handleClear(): void {
    mutation.mutate({ emoji: null, text: null }, { onSuccess: handleSaved })
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95">
          <DialogPrimitive.Title className="mb-4 text-lg font-semibold">
            {statusLabels.title}
          </DialogPrimitive.Title>

          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="status-emoji">{statusLabels.emojiLabel}</Label>
              <Input
                id="status-emoji" value={emoji} placeholder={statusLabels.emojiPlaceholder}
                maxLength={EMOJI_MAX_LENGTH}
                disabled={mutation.isPending} onChange={(e) => { setEmoji(e.target.value) }}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="status-text">{statusLabels.textLabel}</Label>
              <Input
                id="status-text" value={text} placeholder={statusLabels.textPlaceholder}
                maxLength={TEXT_MAX_LENGTH}
                disabled={mutation.isPending} onChange={(e) => { setText(e.target.value) }}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="status-expiry">{statusLabels.expiryLabel}</Label>
              <select
                id="status-expiry" value={preset} disabled={mutation.isPending}
                onChange={(e) => { setPreset(e.target.value as ExpiryPreset) }}
                className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm"
              >
                {EXPIRY_PRESETS.map((p) => (
                  <option key={p} value={p}>{statusLabels.presets[p]}</option>
                ))}
              </select>
            </div>

            {mutation.isError && (
              <p role="alert" className="text-sm text-destructive">
                {statusLabels.errorMessage}
              </p>
            )}

            <div className="flex justify-between gap-2 pt-2">
              <Button
                type="button" variant="outline" size="sm"
                disabled={mutation.isPending} onClick={handleClear}
              >
                {statusLabels.clearButton}
              </Button>
              <Button type="button" size="sm" disabled={mutation.isPending} onClick={handleSave}>
                {mutation.isPending ? statusLabels.savingButton : statusLabels.saveButton}
              </Button>
            </div>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
