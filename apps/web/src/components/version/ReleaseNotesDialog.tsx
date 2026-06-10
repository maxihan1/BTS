// 버전 릴리즈 노트 미리보기 + 클립보드 복사 다이얼로그 (FR-VR-04 Task 6)
import { useState, useCallback } from 'react'
import type { JSX } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { useReleaseNotes } from '@/hooks/use-versions'

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
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

const OVERLAY_CLASS =
  'fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0'

const CONTENT_CLASS =
  'fixed left-1/2 top-1/2 z-50 w-full max-w-2xl -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 flex flex-col max-h-[80vh]'

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
 * - radix Dialog 직접 import (shadcn Dialog 래퍼 부재 패턴 — AddAccountDialog.tsx 동형).
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
      // 2초 후 idle로 복귀
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

  function renderCopyButtonLabel(): string {
    if (copyState === 'copied') return '복사됨'
    if (copyState === 'error') return '복사 실패'
    return '복사'
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={handleOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className={OVERLAY_CLASS} />

        <DialogPrimitive.Content className={CONTENT_CLASS}>
          {/* 제목 */}
          <DialogPrimitive.Title className="text-lg font-semibold mb-1 shrink-0">
            {versionName} 릴리즈 노트
          </DialogPrimitive.Title>

          <p className="text-sm text-muted-foreground mb-4 shrink-0">
            이 버전의 Fix Version 이슈를 기반으로 자동 생성된 릴리즈 노트입니다.
          </p>

          {/* 콘텐츠 영역 */}
          <div className="flex-1 overflow-hidden flex flex-col min-h-0">
            {isLoading && (
              <div
                role="status"
                aria-label="릴리즈 노트 로딩 중"
                className="flex items-center justify-center py-8"
              >
                <span className="text-sm text-muted-foreground animate-pulse">
                  릴리즈 노트를 불러오는 중...
                </span>
              </div>
            )}

            {isError && (
              <div
                role="alert"
                className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
              >
                릴리즈 노트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
              </div>
            )}

            {releaseNotes !== undefined && !isLoading && !isError && (
              <div
                role="region"
                aria-label="릴리즈 노트 내용"
                className="flex-1 overflow-auto rounded-md border bg-muted/30 p-4 min-h-0"
              >
                <pre className="text-sm font-mono whitespace-pre-wrap break-words">
                  {releaseNotes.markdown}
                </pre>
              </div>
            )}
          </div>

          {/* 액션 버튼 */}
          <div className="flex items-center justify-between mt-4 shrink-0">
            {/* 이슈 수 메타정보 */}
            {releaseNotes !== undefined && (
              <span className="text-xs text-muted-foreground">
                이슈 {releaseNotes.issueCount}개 포함
              </span>
            )}
            {releaseNotes === undefined && <span />}

            <div className="flex gap-2">
              {releaseNotes !== undefined && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => { void handleCopy() }}
                  aria-label="릴리즈 노트 복사"
                >
                  {renderCopyButtonLabel()}
                </Button>
              )}

              <DialogPrimitive.Close asChild>
                <Button variant="outline" size="sm">
                  닫기
                </Button>
              </DialogPrimitive.Close>
            </div>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
