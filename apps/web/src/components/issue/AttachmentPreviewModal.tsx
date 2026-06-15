// 첨부 파일 미리보기 모달 — image/pdf/video 렌더러 + blob URL 생명주기 관리
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import type { AttachmentResponse } from '@/api/attachments'
import { downloadAttachment } from '@/api/attachments'
import { previewCategory } from '@/lib/attachment-preview'
import { attachmentLabels } from '@/i18n/attachment-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface AttachmentPreviewModalProps {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  readonly issueKey: string
  /** 미리보기 대상 첨부 파일 */
  readonly attachment: AttachmentResponse
  /** 모달 열림 여부 */
  readonly open: boolean
  /** 모달 열림 상태 변경 핸들러 */
  readonly onOpenChange: (o: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 첨부 파일 미리보기 모달.
 *
 * - image/png, image/jpeg, image/gif, image/webp → `<img>`
 * - application/pdf → `<iframe sandbox>`
 * - video/mp4, video/webm → `<video controls>`
 * - 그 외 → null (미리보기 불가)
 *
 * open=true 시 downloadAttachment로 Blob을 내려받아 objectURL을 생성한다.
 * 닫힘·prop 전환·언마운트 시 이전 objectURL을 revokeObjectURL로 해제한다 (G4/C1).
 */
export function AttachmentPreviewModal({
  issueKey,
  attachment,
  open,
  onOpenChange,
}: AttachmentPreviewModalProps): JSX.Element {
  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [hasError, setHasError] = useState(false)

  useEffect(() => {
    // open=false 이거나 attachment가 없으면 아무것도 하지 않는다
    if (!open) {
      return
    }

    let ignore = false
    let createdUrl: string | null = null

    setIsLoading(true)
    setHasError(false)
    setBlobUrl(null)

    downloadAttachment(issueKey, attachment.id)
      .then((blob) => {
        if (ignore) return
        const url = URL.createObjectURL(blob)
        createdUrl = url
        setBlobUrl(url)
        setIsLoading(false)
      })
      .catch(() => {
        if (ignore) return
        setIsLoading(false)
        setHasError(true)
      })

    // cleanup: stale 방지 + 직전 objectURL 해제 (G4/C1)
    return () => {
      ignore = true
      if (createdUrl !== null) {
        URL.revokeObjectURL(createdUrl)
        createdUrl = null
      }
    }
  }, [open, attachment.id, issueKey])

  function handleOpenChange(next: boolean): void {
    // 닫힐 때 현재 blobUrl을 revoke (cleanup이 담당 — useEffect cleanup으로 통합)
    onOpenChange(next)
  }

  const category = previewCategory(attachment.contentType)

  function renderPreview(): JSX.Element | null {
    if (isLoading) {
      return <p className="text-sm text-muted-foreground">{attachmentLabels.previewLoading}</p>
    }
    if (hasError) {
      return <p className="text-sm text-destructive">{attachmentLabels.previewError}</p>
    }
    if (blobUrl === null || category === null) {
      return null
    }

    if (category === 'image') {
      return (
        <img
          src={blobUrl}
          alt={attachment.filename}
          className="max-h-[70vh] max-w-full object-contain"
        />
      )
    }
    if (category === 'pdf') {
      return (
        <iframe
          src={blobUrl}
          sandbox=""
          title={attachment.filename}
          className="w-full h-[70vh]"
        />
      )
    }
    // video
    return (
      <video
        src={blobUrl}
        controls
        data-testid="preview-video"
        className="max-h-[70vh] max-w-full"
      />
    )
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={handleOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-4xl -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">
            {attachmentLabels.previewTitle(attachment.filename)}
          </DialogPrimitive.Title>

          <div className="flex items-center justify-center min-h-32">
            {renderPreview()}
          </div>

          <div className="flex justify-end mt-4">
            <DialogPrimitive.Close asChild>
              <Button variant="outline" size="sm">
                {attachmentLabels.previewClose}
              </Button>
            </DialogPrimitive.Close>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
