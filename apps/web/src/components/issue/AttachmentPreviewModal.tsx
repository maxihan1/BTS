// 첨부 파일 미리보기 모달 — image/pdf/video 렌더러 + blob URL 생명주기 관리
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
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
    // 닫힐 때 이전 에러/blobUrl 상태를 리셋한다(P2 — 닫은 뒤 재열기 시 stale 에러 방지).
    // 직전 effect cleanup이 objectURL revoke를 담당하므로 여기서는 state만 정리한다.
    if (!open) {
      setHasError(false)
      setBlobUrl(null)
      setIsLoading(false)
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
      // sandbox 미부여(G3). sandbox=""는 브라우저 내장 PDF 뷰어를 막아 빈 화면이 된다.
      // XSS 방어는 (1) 1차 화이트리스트(text/html·svg는 previewCategory가 null이라 여기 도달 불가)와
      // (2) blob URL이 선언 MIME(application/pdf)으로만 렌더되고 콘텐츠 스니핑하지 않는다는 점에 둔다.
      // HTML을 application/pdf로 위장 업로드해도 PDF 뷰어가 파싱 실패할 뿐 스크립트는 실행되지 않는다.
      return (
        <iframe
          src={blobUrl}
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
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-4xl" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{attachmentLabels.previewTitle(attachment.filename)}</DialogTitle>
        </DialogHeader>

        <div className="flex items-center justify-center min-h-32">
          {renderPreview()}
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" size="sm">
              {attachmentLabels.previewClose}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
