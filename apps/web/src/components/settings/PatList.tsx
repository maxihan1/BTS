// PAT 목록 — name·scope 배지·만료/최근사용 표시 + 인라인 폐기 확인 (FR-API-04 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import type { Pat } from '@/api/pats'
import { useDateFormat } from '@/hooks/use-date-format'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (WebhookRow.tsx 관례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  empty: '발급된 PAT가 없습니다.',
  loading: '로딩 중...',
  expiresLabel: '만료',
  expiredBadge: '만료됨',
  unlimitedBadge: '무기한',
  lastUsedLabel: '최근 사용',
  lastUsedNever: '미사용',
  revokeButton: '폐기',
  revokeConfirmButton: '확인',
  revokeCancelButton: '취소',
  revokeConfirmMessage: '이 PAT를 폐기하면 되돌릴 수 없습니다. 계속하시겠습니까?',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 만료 판정/표시 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** expiresAt이 현재 시각보다 과거인지 판정한다. null(레거시 무기한)은 항상 false */
function isExpired(expiresAt: string | null): boolean {
  if (expiresAt === null) return false
  const date = new Date(expiresAt)
  if (isNaN(date.getTime())) return false
  return date.getTime() < Date.now()
}

/**
 * 만료 배지 텍스트 — 과거면 "만료됨", null(레거시)이면 "무기한", 그 외 포맷된 날짜.
 *
 * @param expiresAt 만료 시각 ISO 문자열 또는 null
 * @param formatDateTime 사용자 date_format 프리셋이 바인딩된 포맷 함수 (useDateFormat 훅 반환값)
 */
function expiryLabel(expiresAt: string | null, formatDateTime: (iso: string) => string): string {
  if (expiresAt === null) return labels.unlimitedBadge
  if (isExpired(expiresAt)) return labels.expiredBadge
  return formatDateTime(expiresAt)
}

// ─────────────────────────────────────────────────────────────────────────────
// 행 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface PatRowProps {
  readonly pat: Pat
  readonly isConfirming: boolean
  readonly isRevoking: boolean
  readonly onRevokeClick: (id: string) => void
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/** PAT 단건 행 — name·scope 배지·만료/최근사용 + 인라인 폐기 확인 UI(WebhookRow 동형 구조) */
function PatRow({ pat, isConfirming, isRevoking, onRevokeClick, onConfirm, onCancel }: PatRowProps): JSX.Element {
  const { formatDateTime } = useDateFormat()
  const lastUsed = pat.lastUsedAt !== null ? formatDateTime(pat.lastUsedAt) : labels.lastUsedNever
  const expired = isExpired(pat.expiresAt)

  return (
    <li className="space-y-2 rounded-lg border p-3" aria-label={pat.name}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 space-y-1">
          <p className="truncate text-sm font-medium">{pat.name}</p>
          <div className="flex flex-wrap gap-1">
            {pat.scopes.map((scope) => (
              <span key={scope} className="rounded bg-muted px-1.5 py-0.5 text-xs font-mono">
                {scope}
              </span>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">
            {labels.expiresLabel}:{' '}
            <span
              className={expired ? 'rounded bg-destructive/10 px-1.5 py-0.5 font-medium text-destructive' : undefined}
            >
              {expiryLabel(pat.expiresAt, formatDateTime)}
            </span>
          </p>
          <p className="text-xs text-muted-foreground">
            {labels.lastUsedLabel}: <span>{lastUsed}</span>
          </p>
        </div>

        {!isConfirming && (
          <Button
            variant="outline"
            size="sm"
            className="shrink-0"
            aria-label={`${pat.name} ${labels.revokeButton}`}
            onClick={() => { onRevokeClick(pat.id) }}
          >
            {labels.revokeButton}
          </Button>
        )}
      </div>

      {isConfirming && (
        <div className="space-y-3 rounded-lg border border-destructive/20 bg-destructive/5 p-3">
          <p className="text-sm">{labels.revokeConfirmMessage}</p>
          <div className="flex gap-2">
            <Button
              variant="destructive"
              size="sm"
              disabled={isRevoking}
              aria-label={`${pat.name} ${labels.revokeButton} ${labels.revokeConfirmButton}`}
              onClick={onConfirm}
            >
              {labels.revokeConfirmButton}
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={isRevoking}
              aria-label={`${pat.name} ${labels.revokeButton} ${labels.revokeCancelButton}`}
              onClick={onCancel}
            >
              {labels.revokeCancelButton}
            </Button>
          </div>
        </div>
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 목록 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** PatList props */
export interface PatListProps {
  /** 렌더할 PAT 요약 목록 */
  readonly pats: Pat[]
  /** 로딩 중 여부 — true이면 로딩 상태 표시 */
  readonly isLoading: boolean
  /** 인라인 확인 후 호출되는 폐기 콜백 */
  readonly onRevoke: (id: string) => void
  /** 폐기 mutation 진행 중 여부 — true면 모든 행의 확인 버튼을 disabled 처리한다 */
  readonly isRevoking?: boolean
}

/**
 * 본인 PAT 목록 (presentational).
 *
 * isLoading=true면 로딩 상태(role="status"), pats 빈 배열이면 빈 상태 문구를 표시한다.
 * expiresAt이 과거면 "만료됨" 배지, null(레거시)이면 "무기한", 그 외엔 포맷된 날짜를 보여준다.
 * lastUsedAt이 null이면 "미사용". 폐기는 인라인 확인(useState, 모달 없음) → "확인" 클릭 시
 * onRevoke(id)를 호출한다(WebhookTable 선례).
 */
export function PatList({ pats, isLoading, onRevoke, isRevoking = false }: PatListProps): JSX.Element {
  const [confirmingId, setConfirmingId] = useState<string | null>(null)

  function handleRevokeClick(id: string): void {
    setConfirmingId(id)
  }

  function handleConfirm(): void {
    if (confirmingId !== null) onRevoke(confirmingId)
  }

  function handleCancel(): void {
    setConfirmingId(null)
  }

  if (isLoading) {
    return (
      <div role="status" aria-label={labels.loading} className="py-8 text-center text-sm text-muted-foreground">
        {labels.loading}
      </div>
    )
  }

  if (pats.length === 0) {
    return <p className="py-8 text-center text-sm text-muted-foreground">{labels.empty}</p>
  }

  return (
    <ul className="space-y-2">
      {pats.map((pat) => (
        <PatRow
          key={pat.id}
          pat={pat}
          isConfirming={confirmingId === pat.id}
          isRevoking={isRevoking}
          onRevokeClick={handleRevokeClick}
          onConfirm={handleConfirm}
          onCancel={handleCancel}
        />
      ))}
    </ul>
  )
}
