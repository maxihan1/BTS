// 백업코드 설정 섹션 — 생성·재생성·복사·다운로드·상태 표시 담당
import type { JSX } from 'react'
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { generateBackupCodes, getBackupCodesStatus } from '@/api/mfa'
import { ApiError } from '@/api/client'
import { extractErrorCode } from '@/lib/extract-error-code'
import { mfaStrings, mfaErrorMessage } from '@/i18n/ko'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 복사/다운로드 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백업코드 목록을 클립보드에 복사한다.
 *
 * @param codes 복사할 백업코드 배열
 * @returns 성공 시 true, 실패(API 없음/권한 거부) 시 false
 */
async function copyToClipboard(codes: readonly string[]): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(codes.join('\n'))
    return true
  } catch {
    return false
  }
}

/**
 * 백업코드를 텍스트 파일로 다운로드한다.
 * Blob을 생성해 임시 anchor click으로 트리거하고 Object URL을 즉시 해제한다.
 *
 * @param codes 다운로드할 백업코드 배열
 */
function downloadCodes(codes: readonly string[]): void {
  const content = `${mfaStrings.backupDownloadHeader}\n${codes.join('\n')}`
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = mfaStrings.backupDownloadFileName
  anchor.click()
  URL.revokeObjectURL(url)
}

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트 — 평문 코드 표시 박스
// ─────────────────────────────────────────────────────────────────────────────

interface CodesRevealBoxProps {
  readonly codes: readonly string[]
  readonly onClose: () => void
}

/**
 * generate 직후 평문 코드를 표시하는 박스.
 * 저장 경고·복사·다운로드·저장 완료(닫기) 버튼을 포함한다.
 */
function CodesRevealBox({ codes, onClose }: CodesRevealBoxProps): JSX.Element {
  const [copyError, setCopyError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  async function handleCopy(): Promise<void> {
    const ok = await copyToClipboard(codes)
    if (ok) {
      setCopied(true)
      setCopyError(null)
    } else {
      setCopyError(mfaStrings.backupCopyFailed)
      setCopied(false)
    }
  }

  function handleDownload(): void {
    downloadCodes(codes)
  }

  return (
    <div className="space-y-4 rounded-lg border border-amber-200 bg-amber-50 p-4 dark:border-amber-800 dark:bg-amber-950/30">
      <p className="text-sm font-medium text-amber-800 dark:text-amber-200">
        {mfaStrings.backupSaveWarning}
      </p>

      {/* 평문 코드 그리드 */}
      <ul className="grid grid-cols-2 gap-1" aria-label={mfaStrings.backupCodesListLabel}>
        {codes.map((code) => (
          <li key={code}>
            <code className="block rounded bg-white px-2 py-1 text-sm font-mono dark:bg-neutral-900">
              {code}
            </code>
          </li>
        ))}
      </ul>

      {/* 복사 에러 메시지 */}
      {copyError !== null && (
        <p role="alert" className="text-sm text-destructive">
          {copyError}
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" onClick={() => { void handleCopy() }}>
          {copied ? mfaStrings.backupCopiedLabel : mfaStrings.backupCopyButton}
        </Button>
        <Button variant="outline" size="sm" onClick={handleDownload}>
          {mfaStrings.backupDownloadButton}
        </Button>
        <Button variant="secondary" size="sm" onClick={onClose}>
          {mfaStrings.backupCloseButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트 — 재생성 인라인 확인 박스
// ─────────────────────────────────────────────────────────────────────────────

interface RegenerateConfirmBoxProps {
  readonly isPending: boolean
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/**
 * 재생성 전 인라인 확인 박스.
 * MfaSettings의 disable 인라인 패턴(showDisableForm)과 동형 구조.
 */
function RegenerateConfirmBox({ isPending, onConfirm, onCancel }: RegenerateConfirmBoxProps): JSX.Element {
  return (
    <div className="space-y-3 rounded-lg border border-destructive/20 bg-destructive/5 p-4">
      <p className="text-sm font-medium">{mfaStrings.backupRegenerateConfirmTitle}</p>
      <p className="text-sm text-muted-foreground">{mfaStrings.backupRegenerateConfirmBody}</p>
      <div className="flex gap-2">
        <Button
          variant="destructive"
          size="sm"
          disabled={isPending}
          onClick={onConfirm}
        >
          {mfaStrings.backupRegenerateConfirmButton}
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={isPending}
          onClick={onCancel}
        >
          {mfaStrings.backupRegenerateCancelButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// BackupCodesSection — 메인 내보내기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백업코드 설정 섹션 컴포넌트.
 *
 * - TOTP 활성 분기 안에서만 렌더된다 (백엔드 409 totp_not_active 선행 조건).
 * - 상태 조회: useQuery(['mfa','backup-codes'], getBackupCodesStatus).
 * - 생성/재생성: useMutation(generateBackupCodes).
 * - 평문 코드는 state 메모리에만 보관(localStorage/sessionStorage/authStore 금지, NFR-1).
 * - mutation 성공 후 invalidateQueries(['mfa','backup-codes'])만 사용(setQueryData 금지).
 */
export function BackupCodesSection(): JSX.Element {
  const queryClient = useQueryClient()

  // ── 상태 조회 ─────────────────────────────────────────────────────────────
  const {
    data: backupStatus,
    isLoading: statusLoading,
  } = useQuery({
    queryKey: ['mfa', 'backup-codes'],
    queryFn: getBackupCodesStatus,
    staleTime: 30_000,
  })

  // ── 생성된 평문 코드 (비영속 state, 닫기 시 null 리셋) ────────────────────
  const [plainCodes, setPlainCodes] = useState<readonly string[] | null>(null)

  // ── 재생성 인라인 확인 상태 ────────────────────────────────────────────────
  const [showRegenerateConfirm, setShowRegenerateConfirm] = useState(false)

  // ── 에러 메시지 ────────────────────────────────────────────────────────────
  const [generateError, setGenerateError] = useState<string | null>(null)

  // ── generate mutation ─────────────────────────────────────────────────────
  const generateMutation = useMutation({
    mutationFn: generateBackupCodes,
    onSuccess: (data) => {
      setPlainCodes(data.codes)
      setGenerateError(null)
      setShowRegenerateConfirm(false)
      void queryClient.invalidateQueries({ queryKey: ['mfa', 'backup-codes'] })
    },
    onError: (err) => {
      setShowRegenerateConfirm(false)
      if (err instanceof ApiError) {
        const code = extractErrorCode(err.body)
        setGenerateError(mfaErrorMessage(code ?? ''))
      } else {
        setGenerateError(mfaErrorMessage(''))
      }
    },
  })

  // ── 이벤트 핸들러 ─────────────────────────────────────────────────────────

  function handleGenerate(): void {
    setGenerateError(null)
    generateMutation.mutate()
  }

  function handleRegenerateClick(): void {
    setGenerateError(null)
    setShowRegenerateConfirm(true)
  }

  function handleRegenerateConfirm(): void {
    generateMutation.mutate()
  }

  function handleRegenerateCancel(): void {
    setShowRegenerateConfirm(false)
  }

  function handleCodesClose(): void {
    setPlainCodes(null)
  }

  // ── 렌더 ─────────────────────────────────────────────────────────────────

  const isGenerated = backupStatus?.generated === true
  const remaining = backupStatus?.remaining ?? 0

  return (
    <div className="space-y-4 border-t pt-6">
      <div>
        <h3 className="text-sm font-semibold">{mfaStrings.backupSectionTitle}</h3>
        <p className="mt-1 text-sm text-muted-foreground">
          {mfaStrings.backupSectionDescription}
        </p>
      </div>

      {/* 에러 메시지 */}
      {generateError !== null && (
        <div
          role="alert"
          aria-live="polite"
          className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
        >
          {generateError}
        </div>
      )}

      {/* 평문 코드 표시 박스 (생성 직후) */}
      {plainCodes !== null && (
        <CodesRevealBox codes={plainCodes} onClose={handleCodesClose} />
      )}

      {/* 상태 표시 (generated=true, 평문 노출 중이 아닐 때) */}
      {isGenerated && plainCodes === null && (
        <div className="space-y-3">
          {/* 남은 코드 수 */}
          <p className="text-sm">
            {mfaStrings.backupRemainingPrefix}
            {': '}
            <span className="font-medium">{remaining}</span>{mfaStrings.backupRemainingUnit}
          </p>

          {/* remaining=0 강조 경고 */}
          {remaining === 0 && (
            <div
              role="alert"
              className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
            >
              {mfaStrings.backupNoneWarning}
            </div>
          )}

          {/* remaining≤3 경고 배너 (0 제외 — 위에서 처리) */}
          {remaining > 0 && remaining <= 3 && (
            <div
              role="alert"
              className="rounded-lg bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950/30 dark:text-amber-200"
            >
              {mfaStrings.backupLowWarning}
            </div>
          )}
        </div>
      )}

      {/* 상태 로딩 중이면 버튼 영역 스켈레톤 */}
      {statusLoading && (
        <div className="h-8 w-36 animate-pulse rounded bg-muted" />
      )}

      {/* 버튼 영역 — 평문 노출 중이면 숨김 */}
      {!statusLoading && plainCodes === null && (
        <div className="space-y-3">
          {/* 재생성 인라인 확인 박스 */}
          {showRegenerateConfirm && (
            <RegenerateConfirmBox
              isPending={generateMutation.isPending}
              onConfirm={handleRegenerateConfirm}
              onCancel={handleRegenerateCancel}
            />
          )}

          {/* 버튼 (확인 박스 열려 있으면 숨김) */}
          {!showRegenerateConfirm && (
            <>
              {!isGenerated && (
                <Button
                  onClick={handleGenerate}
                  disabled={generateMutation.isPending}
                >
                  {mfaStrings.backupGenerateButton}
                </Button>
              )}
              {isGenerated && (
                <Button
                  variant="outline"
                  onClick={handleRegenerateClick}
                  disabled={generateMutation.isPending}
                >
                  {mfaStrings.backupRegenerateButton}
                </Button>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
