// 인증 필요 아바타 이미지 표시 컴포넌트 (blob fetch → objectURL, 이니셜 폴백)
import type { JSX } from 'react'
import { useEffect, useState } from 'react'
import { fetchAvatarBlob } from '@/api/profile'
import { cn } from '@/lib/utils'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface AvatarProps {
  /** 프로필/whoami 응답의 아바타 다운로드 경로 — 없으면 이니셜 폴백 */
  readonly avatarUrl: string | null | undefined
  /** 표시 이름 — 폴백 우선순위 1순위 */
  readonly displayName?: string | null
  /** 사용자명 — displayName 없을 때 폴백 2순위 */
  readonly username?: string | null
  /** 크기 프리셋 */
  readonly size?: 'sm' | 'md' | 'lg'
}

const SIZE_CLASSES: Record<'sm' | 'md' | 'lg', string> = {
  sm: 'size-6 text-xs',
  md: 'size-8 text-sm',
  lg: 'size-16 text-xl',
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 이미지 blob fetch 훅
// ─────────────────────────────────────────────────────────────────────────────

interface AvatarObjectUrlState {
  /** 표시 가능한 objectURL — 아직 로드 전이거나 실패하면 null */
  readonly objectUrl: string | null
  /** fetch 실패 여부 (404 AVATAR_NOT_FOUND 포함, EC8) */
  readonly hasError: boolean
}

/**
 * 인증이 필요한 아바타 이미지를 objectURL로 변환해 반환하는 훅.
 *
 * 아바타 GET 엔드포인트(`/api/v1/users/{userId}/avatar`)는 STATELESS JWT 인증
 * (`@PreAuthorize("isAuthenticated()")`)이 걸려 있어 `<img src={avatarUrl}>`로
 * 직접 로드하면 Authorization 헤더가 실리지 않아 401로 깨진다. 그래서 JWT Bearer를
 * 포함해 fetch하는 `fetchAvatarBlob`으로 Blob을 받아 `URL.createObjectURL`로 변환한
 * 뒤 `<img>`에 표시한다(AttachmentPreviewModal.tsx의 blob fetch 선례와 동일 패턴).
 *
 * avatarUrl이 없거나(null/undefined/빈 문자열) fetch가 실패하면 objectUrl은 null로
 * 유지되고, 호출측이 hasError를 보고 이니셜 폴백을 렌더한다 — 에러 배너는 띄우지 않는다.
 *
 * avatarUrl이 바뀌면 이전 objectURL을 `revokeObjectURL`로 해제한 뒤 재요청하고,
 * 언마운트 시에도 동일하게 해제한다(cleanup).
 *
 * @param avatarUrl 아바타 다운로드 경로
 * @returns 현재 objectURL과 fetch 실패 여부
 */
function useAvatarObjectUrl(avatarUrl: string | null | undefined): AvatarObjectUrlState {
  const [objectUrl, setObjectUrl] = useState<string | null>(null)
  const [hasError, setHasError] = useState(false)

  useEffect(() => {
    setObjectUrl(null)
    setHasError(false)

    if (avatarUrl === null || avatarUrl === undefined || avatarUrl === '') {
      return
    }

    let ignore = false
    let createdUrl: string | null = null

    fetchAvatarBlob(avatarUrl)
      .then((blob) => {
        if (ignore) return
        const url = URL.createObjectURL(blob)
        createdUrl = url
        setObjectUrl(url)
      })
      .catch(() => {
        // 404 AVATAR_NOT_FOUND 포함 모든 실패 — 조용히 이니셜 폴백으로 전환(EC8)
        if (ignore) return
        setHasError(true)
      })

    return () => {
      ignore = true
      if (createdUrl !== null) {
        URL.revokeObjectURL(createdUrl)
        createdUrl = null
      }
    }
  }, [avatarUrl])

  return { objectUrl, hasError }
}

// ─────────────────────────────────────────────────────────────────────────────
// 이니셜 폴백 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function resolveLabel(displayName?: string | null, username?: string | null): string {
  return displayName ?? username ?? '아바타'
}

function resolveInitial(displayName?: string | null, username?: string | null): string {
  const source = displayName ?? username
  if (source === null || source === undefined || source.length === 0) {
    return '?'
  }
  return source.charAt(0)
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 아바타 이미지를 표시하는 재사용 컴포넌트.
 *
 * avatarUrl이 있으면 인증 blob fetch 후 `<img>`로 렌더하고, 없거나 fetch가
 * 실패하면 displayName/username 첫 글자를 이니셜 폴백으로 렌더한다(둘 다 없으면 '?').
 * 프로필 페이지·Header 등에서 공통으로 재사용한다.
 */
export function Avatar({ avatarUrl, displayName, username, size = 'md' }: AvatarProps): JSX.Element {
  const { objectUrl, hasError } = useAvatarObjectUrl(avatarUrl)
  const sizeClass = SIZE_CLASSES[size]
  const label = resolveLabel(displayName, username)
  const hasAvatarUrl = avatarUrl !== null && avatarUrl !== undefined && avatarUrl !== ''

  if (objectUrl !== null) {
    return (
      <img
        src={objectUrl}
        alt={label}
        className={cn('rounded-full object-cover', sizeClass)}
      />
    )
  }

  // fetch 대기 중(avatarUrl 있음 + 아직 성공/실패 미확정) — 접근성 이름 없는 자리표시자
  if (hasAvatarUrl && !hasError) {
    return <div aria-hidden="true" className={cn('rounded-full bg-muted', sizeClass)} />
  }

  return (
    <div
      role="img"
      aria-label={label}
      className={cn(
        'flex items-center justify-center rounded-full bg-muted font-medium text-muted-foreground',
        sizeClass,
      )}
    >
      {resolveInitial(displayName, username)}
    </div>
  )
}
