// 버전 릴리즈 노트 미리보기 + 클립보드 복사 다이얼로그 (FR-VR-04 Task 6)
import { useState, useCallback } from 'react'
import type { JSX } from 'react'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { useReleaseNotes } from '@/hooks/use-versions'
import { versionLabels } from '@/i18n/version-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface ReleaseNotesDialogProps {
  /** 릴리즈 노트를 조회할 버전 UUID */
  readonly versionId: string
  /** dialog 제목에 표시할 버전 이름 */
  readonly versionName: string
  /**
   * 프로젝트 식별 키 — VersionRow props에서 직접 전달 받는다.
   * VersionRow가 이미 projectKey prop을 보유하므로 useParams 재조회 불필요.
   */
  readonly projectKey: string
  /** dialog 열림 여부 */
  readonly open: boolean
  /** dialog 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// ReleaseNotesDialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 릴리즈 노트 미리보기 다이얼로그.
 *
 * - open=true일 때 useReleaseNotes(enabled=true)로 lazy fetch한다.
 * - 로딩 중: role=status 스피너 표시.
 * - 에러 시: role=alert 에러 메시지 표시.
 * - 성공 시: markdown을 pre 태그로 렌더 + 클립보드 복사 버튼.
 * - 클립보드 실패(secure-context 아님 등): graceful — 에러 throw 없이 사용자에게 안내.
 * - projectKey는 부모(VersionRow)에서 props로 전달받는다 — router context 의존 없음.
 * - shadcn Dialog 래퍼(`@/components/ui/dialog`) 위에 build (CloneIssueDialog.tsx 동형).
 *
 * @param versionId 릴리즈 노트를 조회할 버전 UUID
 * @param versionName dialog 제목에 표시할 버전 이름
 * @param projectKey 프로젝트 식별 키
 * @param open dialog 열림 여부
 * @param onOpenChange dialog 열림 상태 변경 콜백
 */
export function ReleaseNotesDialog({
  versionId,
  versionName,
  projectKey,
  open,
  onOpenChange,
}: ReleaseNotesDialogProps): JSX.Element {
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'error'>('idle')
  const { releaseNotes: labels } = versionLabels

  const { data: releaseNotes, isLoading, isError } = useReleaseNotes(projectKey, versionId, open)

  /**
   * 마크다운을 클립보드에 복사한다.
   * secure-context가 아니거나 권한이 거부된 경우 에러를 throw하지 않고
   * copyState를 'error'로 설정해 UI에 표시한다.
   */
  const handleCopy = useCallback(async (): Promise<void> => {
    if (releaseNotes === undefined) return
    try {
      await navigator.clipboard.writeText(releaseNotes.markdown)
      setCopyState('copied')
      setTimeout(() => { setCopyState('idle') }, 2000)
    } catch {
      setCopyState('error')
      setTimeout(() => { setCopyState('idle') }, 3000)
    }
  }, [releaseNotes])

  function handleOpenChange(next: boolean): void {
    if (!next) {
      setCopyState('idle')
    }
    onOpenChange(next)
  }

  function copyButtonLabel(): string {
    if (copyState === 'copied') return labels.copiedText
    if (copyState === 'error') return labels.copyFailText
    return labels.copyButton
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-2xl max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>
            {versionName} {labels.dialogTitleSuffix}
          </DialogTitle>
          <DialogDescription>{labels.dialogDescription}</DialogDescription>
        </DialogHeader>

        {/* 콘텐츠 영역 */}
        <div className="flex-1 overflow-hidden flex flex-col min-h-0">
          {isLoading && (
            <div
              role="status"
              aria-label={labels.loadingAriaLabel}
              className="flex items-center justify-center py-8"
            >
              <span className="text-sm text-muted-foreground">
                {labels.loadingText}
              </span>
            </div>
          )}

          {isError && (
            <div
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
            >
              {labels.errorMessage}
            </div>
          )}

          {releaseNotes !== undefined && !isLoading && !isError && (
            <div
              role="region"
              aria-label={labels.contentAriaLabel}
              className="flex-1 overflow-auto rounded-md border bg-muted/30 p-4 min-h-0"
            >
              <pre className="text-sm font-mono whitespace-pre-wrap break-words">
                {releaseNotes.markdown}
              </pre>
            </div>
          )}
        </div>

        {/* 액션 버튼 */}
        <DialogFooter className="sm:justify-between items-center">
          {/* 이슈 수 메타정보 */}
          {releaseNotes !== undefined ? (
            <span className="text-xs text-muted-foreground">
              {labels.issueCountLabel(releaseNotes.issueCount)}
            </span>
          ) : (
            <span />
          )}

          <div className="flex gap-2">
            {releaseNotes !== undefined && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => { void handleCopy() }}
                aria-label={labels.copyButtonAriaLabel}
              >
                {copyButtonLabel()}
              </Button>
            )}

            <DialogClose asChild>
              <Button variant="outline" size="sm">
                {labels.closeButton}
              </Button>
            </DialogClose>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
