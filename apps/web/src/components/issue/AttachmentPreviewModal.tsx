// 첨부 파일 미리보기 모달 — image/pdf/video 렌더러 + blob URL 생명주기 관리
import type { JSX, KeyboardEvent as ReactKeyboardEvent } from 'react'
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
import { useReportModalOpen } from '@/components/keyboard-shortcuts/useOpenModalRegistry'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface AttachmentPreviewModalProps {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  readonly issueKey: string
  /**
   * 갤러리에 실을 첨부 목록.
   *
   * ★**미리보기 가능한 것만** 담아 넘긴다. 그러면 「미리보기 안 되는 항목을 이동에서
   * 건너뛸까」라는 경계 처리가 아예 사라진다 — 건너뛸 것이 목록에 없기 때문이다.
   * 이 계약을 깨고 전체 목록을 넘기면 이동으로 닿을 수 없는 자리가 생기고 위치 표시가
   * 거짓말을 한다.
   */
  readonly attachments: readonly AttachmentResponse[]
  /** 열 때 보여줄 첨부의 [attachments] 안 위치 */
  readonly startIndex: number
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
 *
 * ★열림을 전역 레지스트리에 보고한다 (FR-UX-10 F11 리뷰 C-1). 미리보기 안에는 포커스를
 * 받는 입력이 없어 단축키 파이프라인의 `shouldIgnoreEvent` 를 통과한다 — 보고하지 않으면
 * 이미지를 띄워 둔 채 `e` 가 뒤에서 제목 편집을 열고 `m` 이 활동 탭을 조용히 바꾼다.
 */
export function AttachmentPreviewModal({
  issueKey,
  attachments,
  startIndex,
  open,
  onOpenChange,
}: AttachmentPreviewModalProps): JSX.Element {
  useReportModalOpen(open)
  const [index, setIndex] = useState(startIndex)
  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [hasError, setHasError] = useState(false)

  // 열 때마다 시작 위치로 되돌린다. 안 그러면 지난번에 넘겨 본 자리에서 열려,
  // 사용자가 누른 썸네일과 다른 파일이 뜬다.
  useEffect(() => {
    if (open) setIndex(startIndex)
  }, [open, startIndex])

  // 목록이 줄어(삭제) 인덱스가 범위를 벗어나면 마지막으로 당긴다.
  const safeIndex = Math.min(Math.max(index, 0), Math.max(attachments.length - 1, 0))
  const attachment = attachments[safeIndex]
  const hasPrevious = safeIndex > 0
  const hasNext = safeIndex < attachments.length - 1

  function goPrevious(): void {
    if (hasPrevious) setIndex(safeIndex - 1)
  }

  function goNext(): void {
    if (hasNext) setIndex(safeIndex + 1)
  }

  /**
   * ←/→ 로 이동한다.
   *
   * 컨테이너에 거는 이유 — Radix `DialogContent` 가 열릴 때 포커스를 자기 안으로 가져오므로
   * 전역 리스너 없이 여기서 받는다. 전역에 걸면 모달이 닫힌 뒤에도 살아 있어야 할지를
   * 따로 관리해야 하고, 단축키 파이프라인과 겹친다.
   *
   * ## ★두 가지를 먼저 걸러낸다 (리뷰 BLOCKER-1)
   *
   * 컨테이너에 걸었다는 것은 **모달 안의 모든 키가 여기로 버블한다**는 뜻이다. 조건 없이
   * `preventDefault` 하면 포커스를 가진 자식의 기본동작을 통째로 빼앗는다.
   *
   * ① **이동할 곳이 없으면 잡지 않는다.** 첨부가 하나뿐이면 이동 UI 조차 안 그리면서
   *    키만 삼키는 것은 사용자에게 「아무 일도 안 일어남」으로 보인다.
   * ② **기본동작이 있는 자식 위에서는 잡지 않는다.** `<video controls>` 의 ←/→ 는 5초
   *    되감기·빨리감기다. 가로채면 되감기가 죽고, 갤러리가 여럿이면 되감기 대신 다른
   *    첨부로 넘어가면서 blob 이 revoke 되어 **재생 위치를 통째로 잃는다.**
   *    `iframe`(PDF 뷰어)도 자체 스크롤·페이지 이동을 쓴다.
   */
  function handleKeyDown(e: ReactKeyboardEvent<HTMLDivElement>): void {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    // ① 이동할 곳이 없다.
    if (attachments.length <= 1) return
    // ② 자식이 그 키를 이미 쓴다.
    const target = e.target
    if (
      target instanceof Element &&
      target.closest('video, iframe, input, textarea, select, [contenteditable="true"]') !== null
    ) {
      return
    }
    e.preventDefault()
    if (e.key === 'ArrowLeft') goPrevious()
    else goNext()
  }

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

    if (attachment === undefined) return
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
  }, [open, attachment, issueKey])

  function handleOpenChange(next: boolean): void {
    // 닫힐 때 현재 blobUrl을 revoke (cleanup이 담당 — useEffect cleanup으로 통합)
    onOpenChange(next)
  }

  const category = attachment === undefined ? null : previewCategory(attachment.contentType)

  function renderPreview(): JSX.Element | null {
    if (isLoading) {
      return <p className="text-sm text-muted-foreground">{attachmentLabels.previewLoading}</p>
    }
    if (hasError) {
      return <p className="text-sm text-destructive">{attachmentLabels.previewError}</p>
    }
    if (blobUrl === null || category === null || attachment === undefined) {
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
      {/*
        ★`onKeyDown` 을 컨테이너에 건다. Radix 가 열릴 때 포커스를 모달 안으로 가져오므로
        여기서 ←/→ 를 받는다 — 전역 리스너를 새로 걸면 단축키 파이프라인과 겹친다.
      */}
      <DialogContent className="max-w-4xl" aria-describedby={undefined} onKeyDown={handleKeyDown}>
        <DialogHeader>
          <DialogTitle>
            {attachmentLabels.previewTitle(attachment?.filename ?? '')}
          </DialogTitle>
        </DialogHeader>

        <div className="flex items-center justify-center min-h-32">
          {renderPreview()}
        </div>

        <DialogFooter className="sm:justify-between">
          {/*
            갤러리 이동 — 첨부가 하나뿐이면 그리지 않는다. 늘 그리면 항상 비활성인 버튼 둘이
            남아 「눌러도 아무 일이 없다」가 된다.
          */}
          {attachments.length > 1 ? (
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={goPrevious}
                disabled={!hasPrevious}
                aria-label={attachmentLabels.previewPrevious}
              >
                ←
              </Button>
              {/*
                ★`aria-live` 가 필요하다 (리뷰 CONCERNS-4). Radix `DialogTitle` 은 내용이 바뀌어도
                재알림되지 않으므로, 이것이 없으면 스크린리더 사용자는 ←/→ 로 넘겼을 때
                **몇 번째로 갔는지도 파일이 바뀌었는지도 아무 신호를 못 받는다** —
                시각 사용자만 쓸 수 있는 이동이 된다. 파일명을 함께 읽어 「어디로 갔나」가
                위치 숫자만으로 끝나지 않게 한다.
              */}
              <span
                data-testid="attachment-preview-position"
                aria-live="polite"
                aria-atomic="true"
                className="text-xs text-muted-foreground tabular-nums"
              >
                <span className="sr-only">{attachment?.filename ?? ''} </span>
                {attachmentLabels.previewPosition(safeIndex + 1, attachments.length)}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={goNext}
                disabled={!hasNext}
                aria-label={attachmentLabels.previewNext}
              >
                →
              </Button>
            </div>
          ) : (
            <span />
          )}

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
