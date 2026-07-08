// 이슈 첨부 파일 섹션 컴포넌트 — 업로드(드롭존)/목록/다운로드/삭제/미리보기 (FR-AC-01 D6, FR-AC-02 Task 3)
import type { JSX, DragEvent, ChangeEvent } from 'react'
import { useState, useRef } from 'react'
import { toast } from 'sonner'
import { useAttachmentList, useUploadAttachment, useDeleteAttachment } from '@/api/useAttachments'
import type { AttachmentResponse } from '@/api/attachments'
import { downloadAttachment, MAX_ATTACHMENT_BYTES } from '@/api/attachments'
import { triggerBlobDownload } from '@/lib/download'
import { attachmentLabels } from '@/i18n/attachment-labels'
import { isPreviewable } from '@/lib/attachment-preview'
import { useDateFormat } from '@/hooks/use-date-format'
import { AttachmentPreviewModal } from './AttachmentPreviewModal'

// ─────────────────────────────────────────────────────────────────────────────
// 파일 크기 포맷 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 바이트를 KB 또는 MB 문자열로 변환한다.
 * - 1MB(1048576) 이상이면 MB 단위 (소수점 1자리)
 * - 그 이하이면 KB 단위 (소수점 1자리)
 * - 1024 미만이면 B 단위 (정수)
 *
 * @param bytes 바이트 수
 * @returns 포맷된 문자열 (예: "2.0 KB", "1.5 MB", "512 B")
 */
function formatFileSize(bytes: number): string {
  if (bytes >= 1_048_576) {
    return `${(bytes / 1_048_576).toFixed(1)} ${attachmentLabels.sizeMB}`
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} ${attachmentLabels.sizeKB}`
  }
  return `${bytes} ${attachmentLabels.sizeBytes}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — AttachmentRow
// ─────────────────────────────────────────────────────────────────────────────

interface AttachmentRowProps {
  /** 첨부 파일 단건 */
  attachment: AttachmentResponse
  /** 이슈 키 */
  issueKey: string
  /** 삭제 허용 여부 */
  canDelete: boolean
}

/**
 * 첨부 파일 행 컴포넌트.
 *
 * - 파일명(truncate) / 크기 / 업로드 시각 표시
 * - 다운로드 버튼 — downloadAttachment + triggerBlobDownload
 * - 삭제 버튼 (canDelete=false면 미표시) → 인라인 확인(확인/취소) → useDeleteAttachment mutate
 *
 * 삭제는 하드삭제이므로 경고 문구와 인라인 확인 단계를 거친다 (FR-MF-02 선례).
 */
function AttachmentRow({ attachment, issueKey, canDelete }: AttachmentRowProps): JSX.Element {
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [isDownloading, setIsDownloading] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)
  const { mutate: deleteMutate, isPending: isDeleting } = useDeleteAttachment(issueKey)
  const { formatDateTime } = useDateFormat()
  const canPreview = isPreviewable(attachment.contentType)

  async function handleDownload(): Promise<void> {
    setIsDownloading(true)
    try {
      const blob = await downloadAttachment(issueKey, attachment.id)
      triggerBlobDownload(blob, attachment.filename)
    } catch {
      toast.error(attachmentLabels.downloadError)
    } finally {
      setIsDownloading(false)
    }
  }

  function handleDeleteClick(): void {
    setConfirmingDelete(true)
  }

  function handleDeleteConfirm(): void {
    deleteMutate(attachment.id, {
      onSuccess: () => {
        setConfirmingDelete(false)
      },
      onError: () => {
        setConfirmingDelete(false)
      },
    })
  }

  function handleDeleteCancel(): void {
    setConfirmingDelete(false)
  }

  return (
    <tr className="border-b border-border last:border-b-0">
      <td className="py-2 pr-3 max-w-[200px]">
        <span className="text-sm text-foreground truncate block" title={attachment.filename}>
          {attachment.filename}
        </span>
      </td>
      <td className="py-2 pr-3 text-xs text-muted-foreground whitespace-nowrap">
        {formatFileSize(attachment.sizeBytes)}
      </td>
      <td className="py-2 pr-3 text-xs text-muted-foreground whitespace-nowrap">
        {formatDateTime(attachment.createdAt)}
      </td>
      <td className="py-2 text-right">
        <div className="flex items-center justify-end gap-1">
          {/* 미리보기 버튼 — isPreviewable 타입만 표시 */}
          {canPreview && (
            <button
              type="button"
              onClick={() => { setPreviewOpen(true) }}
              aria-label={`${attachment.filename} ${attachmentLabels.previewButton}`}
              className="text-xs text-primary hover:underline focus:outline-none focus:ring-1 focus:ring-ring px-1.5 py-1 min-h-[32px]"
            >
              {attachmentLabels.previewButton}
            </button>
          )}

          {/* 다운로드 버튼 */}
          <button
            type="button"
            onClick={() => { void handleDownload() }}
            disabled={isDownloading}
            aria-label={`${attachment.filename} ${attachmentLabels.downloadButton}`}
            className="text-xs text-primary hover:underline focus:outline-none focus:ring-1 focus:ring-ring px-1.5 py-1 disabled:opacity-40 disabled:cursor-not-allowed min-h-[32px]"
          >
            {attachmentLabels.downloadButton}
          </button>

          {/* 삭제 버튼 (canDelete=false 면 미표시) */}
          {canDelete && !confirmingDelete && (
            <button
              type="button"
              onClick={handleDeleteClick}
              aria-label={attachmentLabels.deleteButton}
              className="text-xs text-muted-foreground hover:text-destructive focus:outline-none focus:ring-1 focus:ring-ring px-1.5 py-1 min-h-[32px]"
            >
              {attachmentLabels.deleteButton}
            </button>
          )}

          {/* 인라인 삭제 확인 */}
          {canDelete && confirmingDelete && (
            <span className="flex flex-col items-end gap-1">
              <span className="text-xs text-destructive text-right" role="alert">
                {attachmentLabels.deleteWarning}
              </span>
              <span className="flex gap-1">
                <button
                  type="button"
                  onClick={handleDeleteConfirm}
                  disabled={isDeleting}
                  aria-label={attachmentLabels.deleteConfirmButton}
                  className="text-xs bg-destructive text-destructive-foreground px-2 py-1 rounded hover:bg-destructive/90 focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed min-h-[28px]"
                >
                  {attachmentLabels.deleteConfirmButton}
                </button>
                <button
                  type="button"
                  onClick={handleDeleteCancel}
                  disabled={isDeleting}
                  aria-label={attachmentLabels.deleteCancelButton}
                  className="text-xs border border-border px-2 py-1 rounded hover:bg-muted focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed min-h-[28px]"
                >
                  {attachmentLabels.deleteCancelButton}
                </button>
              </span>
            </span>
          )}
        </div>
      </td>

      {/* 미리보기 모달 — isPreviewable 타입만 마운트 */}
      {canPreview && (
        <AttachmentPreviewModal
          issueKey={issueKey}
          attachment={attachment}
          open={previewOpen}
          onOpenChange={setPreviewOpen}
        />
      )}
    </tr>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — DropZone (canUpdate=true일 때만 렌더)
// ─────────────────────────────────────────────────────────────────────────────

interface DropZoneProps {
  /** 이슈 키 */
  issueKey: string
}

/**
 * 파일 업로드 드롭존 컴포넌트.
 *
 * - 네이티브 `<input type="file" multiple>` + drag/drop 이벤트
 * - 파일 선택/드롭 시 100MB 사전 검증 → 초과 시 toast.error + 건너뜀
 * - 통과한 파일을 useUploadAttachment.mutate로 순차 업로드
 * - 신규 외부 의존성 0 (react-dropzone 금지)
 */
function DropZone({ issueKey }: DropZoneProps): JSX.Element {
  const [isDragOver, setIsDragOver] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const { mutate: uploadMutate, isPending: isUploading } = useUploadAttachment(issueKey)

  /**
   * 파일 목록을 검증 후 업로드한다.
   * 100MB 초과 파일은 개별 토스트를 표시하고 건너뛴다.
   */
  function processFiles(files: FileList | File[]): void {
    const fileArray = Array.from(files)
    for (const file of fileArray) {
      if (file.size > MAX_ATTACHMENT_BYTES) {
        toast.error(attachmentLabels.uploadFileTooLarge(file.name))
        continue
      }
      uploadMutate(file)
    }
  }

  function handleDragOver(e: DragEvent<HTMLDivElement>): void {
    e.preventDefault()
    setIsDragOver(true)
  }

  function handleDragLeave(): void {
    setIsDragOver(false)
  }

  function handleDrop(e: DragEvent<HTMLDivElement>): void {
    e.preventDefault()
    setIsDragOver(false)
    if (e.dataTransfer.files.length > 0) {
      processFiles(e.dataTransfer.files)
    }
  }

  function handleClick(): void {
    fileInputRef.current?.click()
  }

  function handleFileChange(e: ChangeEvent<HTMLInputElement>): void {
    if (e.target.files !== null && e.target.files.length > 0) {
      processFiles(e.target.files)
      // 동일 파일 재선택 허용을 위해 input 값 초기화
      e.target.value = ''
    }
  }

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={handleClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          if (!isUploading) handleClick()
        }
      }}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      aria-label={attachmentLabels.dropzoneHint}
      aria-disabled={isUploading}
      className={[
        'border-2 border-dashed rounded-lg px-4 py-6 text-center cursor-pointer',
        'focus:outline-none focus:ring-2 focus:ring-ring',
        'transition-colors',
        isDragOver
          ? 'border-primary bg-primary/5'
          : 'border-border hover:border-primary/50 hover:bg-muted/30',
        isUploading ? 'opacity-60 pointer-events-none' : '',
      ].join(' ')}
    >
      <p className="text-sm text-muted-foreground">
        {isUploading ? attachmentLabels.uploadingState : attachmentLabels.dropzoneHint}
      </p>
      {/* 숨겨진 파일 input — 브라우저 네이티브 파일 선택 대화상자 */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        aria-label={attachmentLabels.fileInputLabel}
        onChange={handleFileChange}
        className="sr-only"
        tabIndex={-1}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — AttachmentSection
// ─────────────────────────────────────────────────────────────────────────────

interface AttachmentSectionProps {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  issueKey: string
  /**
   * 업로드/삭제 권한 여부.
   * - true: 드롭존 + 삭제 버튼 표시
   * - false: 목록·다운로드만 (읽기 전용)
   */
  canUpdate: boolean
}

/**
 * 이슈 첨부 파일 섹션 컴포넌트.
 *
 * - `useAttachmentList`로 목록 조회
 * - `canUpdate=true`이면 드롭존(업로드) + 각 행 삭제 버튼 표시
 * - `canUpdate=false`이면 목록·다운로드만 (읽기 전용)
 * - 삭제는 인라인 확인 후 `useDeleteAttachment` 호출 (하드삭제 경고 포함)
 * - 다운로드는 `downloadAttachment` + `triggerBlobDownload` 재사용
 *
 * @param issueKey 이슈 키
 * @param canUpdate 업로드/삭제 권한 여부
 */
export function AttachmentSection({ issueKey, canUpdate }: AttachmentSectionProps): JSX.Element {
  const { data: attachments = [], isLoading } = useAttachmentList(issueKey)

  return (
    <section aria-label={attachmentLabels.sectionTitle} className="mt-6">
      {/* 섹션 제목 */}
      <h2 className="text-sm font-semibold text-foreground mb-3">
        {attachmentLabels.sectionTitle}
      </h2>

      {/* 업로드 드롭존 — canUpdate=true만 표시 */}
      {canUpdate && (
        <div className="mb-4">
          <DropZone issueKey={issueKey} />
        </div>
      )}

      {/* 목록 영역 */}
      {isLoading ? (
        <p
          className="text-sm text-muted-foreground"
          aria-label={attachmentLabels.loadingState}
        >
          {attachmentLabels.loadingState}
        </p>
      ) : attachments.length === 0 ? (
        <p className="text-sm text-muted-foreground">{attachmentLabels.emptyState}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <tbody>
              {attachments.map((att) => (
                <AttachmentRow
                  key={att.id}
                  attachment={att}
                  issueKey={issueKey}
                  canDelete={canUpdate}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
