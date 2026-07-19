// 캘린더 iCal 구독 URL 발급/재발급/취소 카드 — PatTokenModal 1회노출 패턴 재사용 (FR-CA-02 Task 9)
import type { JSX } from 'react'
import { useState } from 'react'
import { useCalendarFeedStatus, useIssueCalendarFeed, useRevokeCalendarFeed } from '@/api/useCalendarFeed'
import type { CalendarFeedIssued } from '@/api/calendarFeed'
import { useDateFormat } from '@/hooks/use-date-format'
import { Button } from '@/components/ui/button'
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (PatTokenModal/PatList 관례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  heading: '캘린더 구독',
  description: '외부 캘린더 앱(Google Calendar, Apple Calendar, Outlook)에서 구독할 수 있는 URL을 발급합니다.',
  loading: '구독 상태를 불러오는 중입니다.',
  issueButton: '구독 URL 발급',
  enabledLabel: '구독 URL이 발급되어 있습니다.',
  createdAtLabel: '발급일',
  reissueButton: '재발급',
  reissueConfirmMessage: '재발급하면 기존 구독 URL이 즉시 무효화되어 더 이상 동작하지 않습니다. 계속하시겠습니까?',
  revokeButton: '구독 취소',
  revokeConfirmMessage: '구독을 취소하면 발급된 URL이 더 이상 동작하지 않습니다. 계속하시겠습니까?',
  confirmButton: '확인',
  cancelButton: '취소',
  revealTitle: '구독 URL이 발급되었습니다',
  revealWarning: '이 URL은 지금 한 번만 표시되며, 창을 닫으면 다시 표시되지 않습니다. 안전한 곳에 보관하세요.',
  webcalHintPrefix: 'Apple Calendar/Outlook은 아래 webcal:// 형식 URL을 주소창에 붙여넣으면 자동으로 구독이 추가됩니다.',
  copyButton: '복사',
  copiedLabel: '복사됨',
  copyFailed: '복사에 실패했습니다. 직접 선택해 복사해 주세요.',
  close: '닫기',
} as const

/** feedUrl(https://...)을 webcal:// 스킴으로 변환한다 — Apple/Outlook 자동 구독 힌트용(spec FR7) */
function toWebcalUrl(feedUrl: string): string {
  return feedUrl.replace(/^https?:\/\//, 'webcal://')
}

/** 인라인 확인 단계 종류 — null이면 확인 중이 아님(PatList confirmingId 선례의 2-way 버전) */
type ConfirmKind = 'reissue' | 'revoke' | null

/**
 * 캘린더 iCal 구독 URL 발급/재발급/취소 카드.
 *
 * - 미발급 시 발급 버튼만 노출한다. 발급 성공 시 raw 구독 URL을 로컬 state(`issued`)로 1회만
 *   보관해 화면에 노출하고(PatTokenModal 선례 — localStorage/sessionStorage 저장 금지),
 *   "닫기"를 누르면 그 즉시 화면에서 사라진다(§1.18).
 * - 발급됨 상태에서는 재발급/취소 각각 인라인 확인 단계를 거친다(PatList 선례) — 재발급은
 *   "기존 URL 무효화" 경고를, 취소는 "URL 동작 중단" 경고를 보여준다.
 * - 재발급도 최초 발급과 동일 mutation({@link useIssueCalendarFeed})을 쓴다 — 서버가
 *   upsert(rotate)로 처리하므로 프론트는 발급/재발급을 구분할 필요가 없다.
 * - `navigator.clipboard.writeText` 실패(권한 거부 등) 시 빈 catch로 무시하지 않고
 *   {@link labels.copyFailed} 안내 문구를 노출한다(PatTokenModal 동형 폴백).
 */
export function CalendarFeedCard(): JSX.Element {
  const { data: status, isLoading } = useCalendarFeedStatus()
  const issueMutation = useIssueCalendarFeed()
  const revokeMutation = useRevokeCalendarFeed()
  const { formatDateTime } = useDateFormat()

  const [issued, setIssued] = useState<CalendarFeedIssued | null>(null)
  const [confirming, setConfirming] = useState<ConfirmKind>(null)
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)

  function handleIssue(): void {
    issueMutation.mutate(undefined, {
      onSuccess: (data) => {
        setIssued(data)
        setConfirming(null)
      },
    })
  }

  function handleRevokeConfirm(): void {
    revokeMutation.mutate(undefined, {
      onSuccess: () => {
        setConfirming(null)
      },
    })
  }

  function handleCancelConfirm(): void {
    setConfirming(null)
  }

  function handleCloseReveal(): void {
    setIssued(null)
    setCopied(false)
    setCopyError(null)
  }

  async function handleCopy(url: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      setCopyError(null)
    } catch {
      setCopyError(labels.copyFailed)
      setCopied(false)
    }
  }

  if (isLoading) {
    return (
      <Card>
        <CardContent>
          <p role="status">{labels.loading}</p>
        </CardContent>
      </Card>
    )
  }

  // 발급 직후 1회 노출 화면 — 상태(status.enabled)와 무관하게 issued가 있으면 최우선 렌더
  if (issued !== null) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{labels.revealTitle}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p role="alert" className="text-sm text-warning-text">
            {labels.revealWarning}
          </p>
          <code className="block break-all rounded bg-muted px-3 py-2 text-sm font-mono">
            {issued.feedUrl}
          </code>
          <p className="text-xs text-muted-foreground">
            {labels.webcalHintPrefix}
            <br />
            <span className="break-all font-mono">{toWebcalUrl(issued.feedUrl)}</span>
          </p>
          {copyError !== null && <p role="alert" className="text-xs text-destructive">{copyError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => { void handleCopy(issued.feedUrl) }}>
              {copied ? labels.copiedLabel : labels.copyButton}
            </Button>
            <Button size="sm" onClick={handleCloseReveal}>{labels.close}</Button>
          </div>
        </CardContent>
      </Card>
    )
  }

  const enabled = status?.enabled === true

  return (
    <Card>
      <CardHeader>
        <CardTitle>{labels.heading}</CardTitle>
        <CardDescription>{labels.description}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {!enabled && (
          <Button onClick={handleIssue} disabled={issueMutation.isPending}>
            {labels.issueButton}
          </Button>
        )}

        {enabled && confirming === null && (
          <div className="space-y-3">
            <p className="text-sm">
              {labels.enabledLabel}
              {status?.createdAt != null && (
                <span className="text-muted-foreground">
                  {' '}
                  ({labels.createdAtLabel}: {formatDateTime(status.createdAt)})
                </span>
              )}
            </p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" onClick={() => { setConfirming('reissue') }}>
                {labels.reissueButton}
              </Button>
              <Button variant="outline" size="sm" onClick={() => { setConfirming('revoke') }}>
                {labels.revokeButton}
              </Button>
            </div>
          </div>
        )}

        {enabled && confirming === 'reissue' && (
          <div className="space-y-3 rounded-lg border border-destructive/20 bg-destructive/5 p-3">
            <p className="text-sm">{labels.reissueConfirmMessage}</p>
            <div className="flex gap-2">
              <Button variant="destructive" size="sm" disabled={issueMutation.isPending} onClick={handleIssue}>
                {labels.confirmButton}
              </Button>
              <Button variant="outline" size="sm" disabled={issueMutation.isPending} onClick={handleCancelConfirm}>
                {labels.cancelButton}
              </Button>
            </div>
          </div>
        )}

        {enabled && confirming === 'revoke' && (
          <div className="space-y-3 rounded-lg border border-destructive/20 bg-destructive/5 p-3">
            <p className="text-sm">{labels.revokeConfirmMessage}</p>
            <div className="flex gap-2">
              <Button variant="destructive" size="sm" disabled={revokeMutation.isPending} onClick={handleRevokeConfirm}>
                {labels.confirmButton}
              </Button>
              <Button variant="outline" size="sm" disabled={revokeMutation.isPending} onClick={handleCancelConfirm}>
                {labels.cancelButton}
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
