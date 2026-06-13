// 신뢰 디바이스 관리 섹션 — 목록 조회·단건 취소·전체 취소 담당
import type { JSX } from 'react'
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  listTrustedDevices,
  revokeTrustedDevice,
  revokeAllTrustedDevices,
  type TrustedDevice,
} from '@/api/trusted-devices'
import { mfaStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 포맷 헬퍼 — InvalidDate 방어 (IssueChangelog 선례)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ISO Instant 문자열을 한국어 날짜+시각으로 포맷한다.
 * 파싱 불가한 값은 "—"를 반환한다 (IssueChangelog InvalidDate 방어 패턴).
 *
 * @param iso ISO 8601 날짜 문자열
 * @returns ko-KR 로컬 날짜+시각 문자열, 파싱 실패 시 "—"
 */
function formatDate(iso: string): string {
  const date = new Date(iso)
  if (isNaN(date.getTime())) {
    return '—'
  }
  return date.toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트 — 인라인 확인 박스
// ─────────────────────────────────────────────────────────────────────────────

interface InlineConfirmBoxProps {
  readonly message: string
  readonly isPending: boolean
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/**
 * 취소 전 인라인 확인 박스.
 * BackupCodesSection의 RegenerateConfirmBox와 동형 구조.
 */
function InlineConfirmBox({
  message,
  isPending,
  onConfirm,
  onCancel,
}: InlineConfirmBoxProps): JSX.Element {
  return (
    <div className="space-y-3 rounded-lg border border-destructive/20 bg-destructive/5 p-3">
      <p className="text-sm">{message}</p>
      <div className="flex gap-2">
        <Button
          variant="destructive"
          size="sm"
          disabled={isPending}
          onClick={onConfirm}
        >
          {mfaStrings.trustedDevicesConfirmButton}
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={isPending}
          onClick={onCancel}
        >
          {mfaStrings.trustedDevicesCancelButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트 — 기기 행
// ─────────────────────────────────────────────────────────────────────────────

interface DeviceRowProps {
  readonly device: TrustedDevice
  readonly onRevokeClick: (id: string) => void
  readonly isConfirming: boolean
  readonly isPending: boolean
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/**
 * 신뢰 디바이스 단건 행.
 * label·createdAt·lastUsedAt·expiresAt 표시 + 인라인 확인 취소 UI.
 */
function DeviceRow({
  device,
  onRevokeClick,
  isConfirming,
  isPending,
  onConfirm,
  onCancel,
}: DeviceRowProps): JSX.Element {
  const label = device.label ?? mfaStrings.trustedDevicesLabelFallback
  const lastUsed = device.lastUsedAt !== null
    ? formatDate(device.lastUsedAt)
    : mfaStrings.trustedDevicesLastUsedNever

  return (
    <li className="space-y-2 rounded-lg border p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 space-y-1">
          <p className="truncate text-sm font-medium">{label}</p>
          <p className="text-xs text-muted-foreground">
            {mfaStrings.trustedDevicesRegisteredLabel} {formatDate(device.createdAt)}
          </p>
          <p className="text-xs text-muted-foreground">
            {mfaStrings.trustedDevicesLastUsedLabel} <span>{lastUsed}</span>
          </p>
          <p className="text-xs text-muted-foreground">
            {mfaStrings.trustedDevicesExpiresLabel} {formatDate(device.expiresAt)}
          </p>
        </div>
        {!isConfirming && (
          <Button
            variant="outline"
            size="sm"
            className="shrink-0"
            onClick={() => { onRevokeClick(device.id) }}
          >
            {mfaStrings.trustedDevicesRevokeButton}
          </Button>
        )}
      </div>

      {isConfirming && (
        <InlineConfirmBox
          message={mfaStrings.trustedDevicesRevokeConfirm}
          isPending={isPending}
          onConfirm={onConfirm}
          onCancel={onCancel}
        />
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// TrustedDevicesSection — 메인 내보내기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 신뢰 디바이스 관리 섹션 컴포넌트.
 *
 * - 목록 조회: useQuery(['mfa','trusted-devices'], listTrustedDevices).
 * - 단건 취소: revokeTrustedDevice(id) mutation + 인라인 확인.
 * - 전체 취소: revokeAllTrustedDevices() mutation + 인라인 확인.
 * - 빈 목록: 안내 메시지 + "모든 기기 신뢰 해제" 버튼 숨김.
 * - mutation 성공 후 invalidateQueries(['mfa','trusted-devices'])만 사용(setQueryData 금지).
 */
export function TrustedDevicesSection(): JSX.Element {
  const queryClient = useQueryClient()

  // ── 목록 조회 ────────────────────────────────────────────────────────────
  const {
    data: devices,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ['mfa', 'trusted-devices'],
    queryFn: listTrustedDevices,
    staleTime: 30_000,
  })

  // ── 단건 취소 인라인 확인 상태 (null = 확인 중 없음, id = 해당 기기 확인 중) ──
  const [confirmingId, setConfirmingId] = useState<string | null>(null)

  // ── 전체 취소 인라인 확인 상태 ──────────────────────────────────────────
  const [showRevokeAllConfirm, setShowRevokeAllConfirm] = useState(false)

  // ── 단건 취소 mutation ───────────────────────────────────────────────────
  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeTrustedDevice(id),
    onSuccess: () => {
      setConfirmingId(null)
      void queryClient.invalidateQueries({ queryKey: ['mfa', 'trusted-devices'] })
    },
    onError: () => {
      setConfirmingId(null)
    },
  })

  // ── 전체 취소 mutation ───────────────────────────────────────────────────
  const revokeAllMutation = useMutation({
    mutationFn: revokeAllTrustedDevices,
    onSuccess: () => {
      setShowRevokeAllConfirm(false)
      void queryClient.invalidateQueries({ queryKey: ['mfa', 'trusted-devices'] })
    },
    onError: () => {
      setShowRevokeAllConfirm(false)
    },
  })

  // ── 이벤트 핸들러 ────────────────────────────────────────────────────────

  function handleRevokeClick(id: string): void {
    setShowRevokeAllConfirm(false)
    setConfirmingId(id)
  }

  function handleRevokeConfirm(): void {
    if (confirmingId !== null) {
      revokeMutation.mutate(confirmingId)
    }
  }

  function handleRevokeCancel(): void {
    setConfirmingId(null)
  }

  function handleRevokeAllClick(): void {
    setConfirmingId(null)
    setShowRevokeAllConfirm(true)
  }

  function handleRevokeAllConfirm(): void {
    revokeAllMutation.mutate()
  }

  function handleRevokeAllCancel(): void {
    setShowRevokeAllConfirm(false)
  }

  // ── 렌더 ────────────────────────────────────────────────────────────────

  const isEmpty = devices !== undefined && devices.length === 0

  return (
    <div className="space-y-4 border-t pt-6">
      {/* 섹션 헤더 */}
      <div>
        <h3 className="text-sm font-semibold">{mfaStrings.trustedDevicesSectionTitle}</h3>
        <p className="mt-1 text-sm text-muted-foreground">
          {mfaStrings.trustedDevicesSectionDescription}
        </p>
      </div>

      {/* 로딩 스켈레톤 */}
      {isLoading && (
        <div
          role="status"
          aria-label="로딩 중"
          className="h-16 w-full animate-pulse rounded-lg bg-muted"
        />
      )}

      {/* 에러 */}
      {isError && (
        <div
          role="alert"
          className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
        >
          {mfaStrings.trustedDevicesLoadError}
        </div>
      )}

      {/* 빈 상태 */}
      {isEmpty && (
        <p className="text-sm text-muted-foreground">{mfaStrings.trustedDevicesEmptyState}</p>
      )}

      {/* 기기 목록 */}
      {devices !== undefined && devices.length > 0 && (
        <ul className="space-y-2">
          {devices.map((device) => (
            <DeviceRow
              key={device.id}
              device={device}
              onRevokeClick={handleRevokeClick}
              isConfirming={confirmingId === device.id}
              isPending={revokeMutation.isPending}
              onConfirm={handleRevokeConfirm}
              onCancel={handleRevokeCancel}
            />
          ))}
        </ul>
      )}

      {/* 전체 취소 영역 — 기기가 있을 때만 표시 */}
      {devices !== undefined && devices.length > 0 && (
        <div className="space-y-2">
          {showRevokeAllConfirm ? (
            <InlineConfirmBox
              message={mfaStrings.trustedDevicesRevokeConfirm}
              isPending={revokeAllMutation.isPending}
              onConfirm={handleRevokeAllConfirm}
              onCancel={handleRevokeAllCancel}
            />
          ) : (
            <Button
              variant="outline"
              size="sm"
              onClick={handleRevokeAllClick}
              disabled={revokeAllMutation.isPending}
            >
              {mfaStrings.trustedDevicesRevokeAllButton}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
