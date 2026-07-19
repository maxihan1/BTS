// 대시보드 공유 모달 — 링크 생성/복사, 임베드 코드, 발급된 링크 목록(인라인 취소 확인) (FR-DB-03 D6/D7 Task 5)
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { toast } from 'sonner'
import { AlertTriangle } from 'lucide-react'
import { useShareTokens, useIssueShareToken, useRevokeShareToken } from '@/hooks/use-dashboards'
import type { IssuedShareToken, ShareTokenSummary } from '@/api/dashboards'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import { useDateFormat } from '@/hooks/use-date-format'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 버튼 스타일 (SettingsModal/DashboardDetailPage 액션 버튼 톤 계승, 터치 타깃 44px)
// ─────────────────────────────────────────────────────────────────────────────

const ACTION_BUTTON_CLASS =
  'inline-flex items-center justify-center gap-1 rounded-md border px-3 py-1.5 text-sm font-medium hover:bg-muted transition-colors min-h-[44px] disabled:opacity-50 disabled:cursor-not-allowed shrink-0'

const PRIMARY_BUTTON_CLASS =
  'inline-flex items-center justify-center gap-1 rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors min-h-[44px] disabled:opacity-50 disabled:cursor-not-allowed'

// ─────────────────────────────────────────────────────────────────────────────
// CopyButton — 클립보드 복사 + 일시 "복사됨" 전환 (BackupCodesSection/ReleaseNotesDialog 패턴)
// ─────────────────────────────────────────────────────────────────────────────

interface CopyButtonProps {
  /** 복사할 원문 텍스트 */
  readonly text: string
}

/**
 * 클립보드 복사 버튼.
 * 복사 성공 시 라벨이 "복사됨"으로 2초간 전환된다.
 * 실패(secure-context 아님 등)는 콘솔 진단만 남기고 조용히 무시한다 — graceful degrade.
 */
function CopyButton({ text }: CopyButtonProps): JSX.Element {
  const [copied, setCopied] = useState(false)

  async function handleCopy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => { setCopied(false) }, 2000)
    } catch (err) {
      console.error('클립보드 복사에 실패했습니다', err)
    }
  }

  return (
    <button
      type="button"
      className={ACTION_BUTTON_CLASS}
      onClick={() => { void handleCopy() }}
    >
      {copied ? dashboardLabels.share.copied : dashboardLabels.share.copy}
    </button>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ShareTokenListItem — 발급된 링크 목록 개별 항목 (인라인 취소 확인)
// ─────────────────────────────────────────────────────────────────────────────

interface ShareTokenListItemProps {
  readonly item: ShareTokenSummary
  readonly isConfirming: boolean
  readonly isPending: boolean
  readonly onRevokeClick: () => void
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/**
 * 발급된 공유 토큰 목록의 개별 항목.
 *
 * 복사 링크·임베드 스니펫은 표시하지 않는다 — 원문 토큰은 발급 시점에만 노출되고
 * 목록 조회 응답에는 해시만 저장되어 재조회가 불가능하다.
 * lastAccessedAt도 표시하지 않는다 — 백엔드 요약 DTO에 해당 필드가 없다.
 *
 * 취소는 즉시 하드삭제라 실수 방지를 위해 인라인 확인을 거친다
 * (SettingsModal의 showDeleteConfirm 패턴 준용).
 */
function ShareTokenListItem({
  item,
  isConfirming,
  isPending,
  onRevokeClick,
  onConfirm,
  onCancel,
}: ShareTokenListItemProps): JSX.Element {
  const { formatDateTime } = useDateFormat()
  const expiresLabel =
    item.expiresAt !== null && item.expiresAt !== undefined
      ? `${dashboardLabels.share.expiresAt}: ${formatDateTime(item.expiresAt)}`
      : dashboardLabels.share.noExpiry

  return (
    <li className="flex items-center justify-between gap-2 rounded-md border p-2 text-sm">
      <div className="min-w-0">
        <p className="truncate">{formatDateTime(item.createdAt)}</p>
        <p className="text-xs text-muted-foreground">{expiresLabel}</p>
      </div>

      {isConfirming ? (
        <div className="flex items-center gap-2 shrink-0">
          <span className="text-xs text-muted-foreground">{dashboardLabels.share.revokeConfirm}</span>
          <button type="button" className={ACTION_BUTTON_CLASS} disabled={isPending} onClick={onConfirm}>
            {dashboardLabels.detail.confirmButton}
          </button>
          <button type="button" className={ACTION_BUTTON_CLASS} disabled={isPending} onClick={onCancel}>
            {dashboardLabels.detail.cancelButton}
          </button>
        </div>
      ) : (
        <button type="button" className={ACTION_BUTTON_CLASS} onClick={onRevokeClick}>
          {dashboardLabels.share.revoke}
        </button>
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ShareDashboardModal 컴포넌트 props */
export interface ShareDashboardModalProps {
  /** 대상 대시보드 UUID */
  readonly dashboardId: string
  /** 대시보드 공개 범위 (PRIVATE/TEAM/ORG) — ORG가 아니면 경고 배너를 표시한다 */
  readonly visibility: string
  /** 모달 열림 여부 */
  readonly open: boolean
  /** 닫기 콜백 */
  readonly onClose: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// ShareDashboardModal — 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 공유 모달.
 *
 * 3영역.
 * 1. 링크 생성 — "링크 생성" 클릭 시 발급 mutation을 호출하고, 원문 token을 컴포넌트
 *    state로 보관해 공개 URL을 조립·표시한다(재조회 불가 — DB에는 해시만 저장).
 *    visibility !== 'ORG'이면 warning caution 배너로 접근 범위 경고를 표시한다.
 * 2. 임베드 코드 — 방금 발급한 토큰으로 same-origin iframe 스니펫을 조립해 표시한다.
 * 3. 발급된 링크 목록 — 메타(id/createdAt/expiresAt)만 표시하고 복사/임베드는 없다.
 *    취소는 인라인 확인 후 실행한다(즉시 하드삭제 — 실수 방지).
 *
 * 모달 스캐폴드는 SettingsModal(dashboards.$dashboardId.tsx)을 그대로 계승한다
 * (role=dialog aria-modal bg-black/40 오버레이 + 닫기 버튼 + Escape 닫기).
 */
export function ShareDashboardModal({
  dashboardId,
  visibility,
  open,
  onClose,
}: ShareDashboardModalProps): JSX.Element | null {
  const { data: shareTokens } = useShareTokens(dashboardId)
  const issueMutation = useIssueShareToken()
  const revokeMutation = useRevokeShareToken()

  /** 방금 발급한 토큰 — 원문 재조회 불가라 컴포넌트 state에만 보관 */
  const [issuedToken, setIssuedToken] = useState<IssuedShareToken | null>(null)
  /** 인라인 취소 확인 중인 shareId */
  const [confirmRevokeId, setConfirmRevokeId] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    function handleKeyDown(e: KeyboardEvent): void {
      if (e.key === 'Escape') {
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open, onClose])

  if (!open) return null

  /** 링크 생성 — 발급 성공 시 원문 토큰을 state에 보관한다 */
  async function handleGenerate(): Promise<void> {
    try {
      const result = await issueMutation.mutateAsync({ id: dashboardId })
      setIssuedToken(result)
    } catch {
      toast.error('공유 링크 발급 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.')
    }
  }

  /**
   * 인라인 확인 후 취소 실행.
   *
   * 방금 발급해 상단에 표시 중인 토큰(issuedToken)을 목록에서 취소한 경우, issuedToken도
   * 함께 정리한다 — 그러지 않으면 상단 공개 URL/임베드 영역이 이미 취소된(404) 링크를
   * 복사 가능한 상태로 계속 표시하는 데드엔드가 된다.
   */
  async function handleRevokeConfirm(shareId: string): Promise<void> {
    try {
      await revokeMutation.mutateAsync({ id: dashboardId, shareId })
      setConfirmRevokeId(null)
      if (issuedToken?.id === shareId) {
        setIssuedToken(null)
      }
    } catch {
      toast.error('공유 링크 취소 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.')
    }
  }

  const publicUrl =
    issuedToken !== null ? `${window.location.origin}/dashboards/shared/${issuedToken.token}` : null
  const embedSnippet =
    issuedToken !== null
      ? `<iframe src="${window.location.origin}/dashboards/shared/${issuedToken.token}?embed=1" width="100%" height="600" style="border:0"></iframe>`
      : null

  const items = shareTokens?.items ?? []

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={dashboardLabels.share.modalTitle}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
    >
      <div className="bg-background rounded-lg shadow-xl p-6 w-full max-w-lg mx-4 max-h-[85vh] overflow-y-auto space-y-6">
        {/* 헤더 */}
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">{dashboardLabels.share.modalTitle}</h2>
          <button
            type="button"
            aria-label="공유 닫기"
            className="rounded p-1 hover:bg-muted"
            onClick={onClose}
          >
            ✕
          </button>
        </div>

        {/* PRIVATE/TEAM 경고 배너 — warning caution, destructive 금지 */}
        {visibility !== 'ORG' && (
          <div
            role="note"
            className="flex items-start gap-2 rounded-md border border-warning bg-warning/10 p-3 text-sm text-warning-text"
          >
            <AlertTriangle className="h-4 w-4 shrink-0 mt-0.5" aria-hidden="true" />
            <span>{dashboardLabels.share.visibilityWarning}</span>
          </div>
        )}

        {/* 링크 생성 영역 */}
        <section className="space-y-2">
          {issuedToken === null ? (
            <button
              type="button"
              className={PRIMARY_BUTTON_CLASS}
              disabled={issueMutation.isPending}
              onClick={() => { void handleGenerate() }}
            >
              {dashboardLabels.share.generateLink}
            </button>
          ) : (
            <div className="space-y-2">
              <p className="text-xs text-muted-foreground">{dashboardLabels.share.copyOnceNotice}</p>
              <div className="flex items-center gap-2">
                <input
                  readOnly
                  value={publicUrl ?? ''}
                  className="flex-1 min-w-0 truncate rounded border px-2 py-1.5 text-sm bg-muted"
                />
                <CopyButton text={publicUrl ?? ''} />
              </div>
            </div>
          )}
        </section>

        {/* 임베드 코드 영역 — 방금 발급한 토큰이 있을 때만 표시 */}
        {issuedToken !== null && (
          <section className="space-y-2">
            <h3 className="text-sm font-semibold">{dashboardLabels.share.embedCode}</h3>
            <div className="flex items-start gap-2">
              <pre className="flex-1 min-w-0 overflow-x-auto whitespace-pre-wrap break-all rounded border bg-muted px-2 py-1.5 text-xs">
                {embedSnippet}
              </pre>
              <CopyButton text={embedSnippet ?? ''} />
            </div>
          </section>
        )}

        {/* 발급된 링크 목록 */}
        <section className="space-y-2">
          <h3 className="text-sm font-semibold">{dashboardLabels.share.issuedLinks}</h3>
          {items.length === 0 ? (
            <p className="text-sm text-muted-foreground">{dashboardLabels.share.empty}</p>
          ) : (
            <ul className="space-y-2">
              {items.map((item) => (
                <ShareTokenListItem
                  key={item.id}
                  item={item}
                  isConfirming={confirmRevokeId === item.id}
                  isPending={revokeMutation.isPending}
                  onRevokeClick={() => setConfirmRevokeId(item.id)}
                  onConfirm={() => { void handleRevokeConfirm(item.id) }}
                  onCancel={() => setConfirmRevokeId(null)}
                />
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  )
}
