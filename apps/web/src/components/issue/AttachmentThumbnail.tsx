// 첨부 목록의 이미지 썸네일 타일 — 이미지가 아니거나 큰 파일은 타입 아이콘 (Jira 패리티 J6)
import type { JSX } from 'react'
import { FileText, FileImage, Film, File as FileIcon } from 'lucide-react'
import type { AttachmentResponse } from '@/api/attachments'
import { useAttachmentBlobUrl, shouldRenderThumbnail } from '@/api/use-attachment-blob'
import { isPreviewable } from '@/lib/attachment-preview'
import { attachmentLabels } from '@/i18n/attachment-labels'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

interface AttachmentThumbnailProps {
  issueKey: string
  attachment: AttachmentResponse
  /** 클릭 시 미리보기 모달을 연다. 미리보기 불가 타입이면 호출되지 않는다. */
  onOpen: () => void
}

/** MIME 별 대표 아이콘 — 썸네일을 못 그릴 때 무엇인지는 알려 준다. */
function typeIcon(contentType: string): JSX.Element {
  const mime = contentType.toLowerCase()
  if (mime.startsWith('image/')) return <FileImage className="size-4" aria-hidden="true" />
  if (mime.startsWith('video/')) return <Film className="size-4" aria-hidden="true" />
  if (mime === 'application/pdf') return <FileText className="size-4" aria-hidden="true" />
  return <FileIcon className="size-4" aria-hidden="true" />
}

/**
 * 첨부 썸네일 타일.
 *
 * ## Jira 와 같은 기본값
 *
 * "Click an image thumbnail to open a preview… If your Jira admin has disabled thumbnails,
 * the image files will appear as a list"(J6) — 썸네일이 기본이고 목록이 예외다.
 *
 * ## 언제 실제 그림을 그리나
 *
 * 이미지이면서 5MB 이하일 때만이다(편차 X5). 서버에 리사이즈 엔드포인트가 없어 **원본을
 * 통째로 받아** CSS 로 줄이므로, 상한이 없으면 100MB 이미지 하나가 목록을 여는 것만으로
 * 내려받힌다. 초과분과 비이미지는 타입 아이콘으로 남고 「미리보기」 버튼은 그대로 있다 —
 * 사용자가 의도했을 때만 받는다.
 *
 * ## 왜 blob 인가
 *
 * 첨부 다운로드가 `Authorization: Bearer` 헤더 인증이라 `<img src="/api/…">` 는 401 이다.
 * `use-attachment-blob` 이 fetch → objectURL → revoke 생명주기를 맡는다.
 */
export function AttachmentThumbnail({
  issueKey,
  attachment,
  onOpen,
}: AttachmentThumbnailProps): JSX.Element {
  const wantThumbnail = shouldRenderThumbnail(attachment.contentType, attachment.sizeBytes)
  const blobUrl = useAttachmentBlobUrl(issueKey, attachment.id, wantThumbnail)
  const canPreview = isPreviewable(attachment.contentType)

  const tileCls = 'size-10 shrink-0 rounded border border-border bg-muted/40 overflow-hidden'

  // 미리보기 가능한 타입만 클릭으로 연다 — 열 수 없는 것에 포인터를 주면 거짓 어포던스다.
  if (!canPreview) {
    return (
      <span className={cn(tileCls, 'flex items-center justify-center text-muted-foreground')}>
        {typeIcon(attachment.contentType)}
      </span>
    )
  }

  return (
    <Button
      type="button"
      variant="ghost"
      onClick={onOpen}
      aria-label={attachmentLabels.thumbnailButton(attachment.filename)}
      className={cn(tileCls, 'p-0 text-muted-foreground hover:border-ring')}
    >
      {blobUrl !== null ? (
        <img
          src={blobUrl}
          alt={attachment.filename}
          className="size-full object-cover"
        />
      ) : (
        typeIcon(attachment.contentType)
      )}
    </Button>
  )
}
