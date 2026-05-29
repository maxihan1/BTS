// 활성 세션 목록 UI 컴포넌트 — 각 세션 카드 렌더, current 배지, 강제 로그아웃 버튼
import type { JSX } from 'react'
import { useSessionsQuery } from '@/auth/useSessionsQuery'
import { useRevokeSessionMutation } from '@/auth/useRevokeSessionMutation'
import type { Session } from '@/api/sessions'
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 포맷 유틸
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ISO 8601 날짜 문자열을 한국어 로컬 형식으로 포맷한다.
 * 예: "2026-05-29T10:00:00Z" → "2026. 5. 29. 오후 7:00"
 */
function formatDateTime(iso: string): string {
  const date = new Date(iso)
  return date.toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 현재 세션 배지 — shadcn badge 미존재 → styled span 대체
// ─────────────────────────────────────────────────────────────────────────────

/**
 * "현재 세션" 배지 — badge.tsx 미존재로 인라인 스타일 대신 Tailwind 토큰 사용.
 */
function CurrentSessionBadge(): JSX.Element {
  return (
    <span className="inline-flex items-center rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-medium text-primary">
      현재 세션
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 세션 항목 카드
// ─────────────────────────────────────────────────────────────────────────────

interface SessionCardProps {
  /** 렌더할 세션 데이터 */
  readonly session: Session
  /** 강제 종료 버튼 클릭 핸들러 — 진행 중 여부 */
  readonly isRevoking: boolean
  /** 강제 종료 버튼 클릭 시 호출되는 콜백 */
  readonly onRevoke: (sid: string) => void
}

/**
 * 단일 세션을 카드 형태로 렌더하는 서브 컴포넌트.
 *
 * - current 세션에는 "현재 세션" 배지 표시 + 강제 종료 버튼 disabled (FR-7)
 * - userAgent/ipAddress null 시 fallback 텍스트 표시 (EC-6)
 */
function SessionCard({ session, isRevoking, onRevoke }: SessionCardProps): JSX.Element {
  const device = session.userAgent ?? '알 수 없는 기기'
  const location = session.ipAddress ?? '알 수 없는 위치'

  function handleRevoke(): void {
    onRevoke(session.sid)
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <CardTitle className="flex-1 text-sm leading-snug break-all">
            {device}
          </CardTitle>
          {session.current && <CurrentSessionBadge />}
        </div>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">위치</dt>
          <dd>{location}</dd>
          <dt className="text-muted-foreground">마지막 활동</dt>
          <dd>{formatDateTime(session.lastSeenAt)}</dd>
          <dt className="text-muted-foreground">로그인</dt>
          <dd>{formatDateTime(session.createdAt)}</dd>
          <dt className="text-muted-foreground">인증 방식</dt>
          <dd>{session.providerId}</dd>
        </dl>
      </CardContent>
      <CardFooter className="justify-end">
        <Button
          variant="destructive"
          size="sm"
          disabled={session.current || isRevoking}
          aria-label="세션 종료"
          onClick={handleRevoke}
        >
          세션 종료
        </Button>
      </CardFooter>
    </Card>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SessionList — 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 사용자의 활성 세션 목록을 렌더하는 컴포넌트.
 *
 * - `useSessionsQuery`로 목록 조회, 각 세션을 `SessionCard`로 렌더
 * - current 세션: "현재 세션" 배지 + 강제 종료 버튼 disabled (FR-7)
 * - null userAgent/ipAddress: fallback 텍스트 (EC-6)
 * - 강제 종료 영역에 EC-29 5초 캐시 지연 안내 문구 (FR-8)
 *
 * @returns 세션 목록 또는 로딩/에러/빈 상태 UI
 */
export function SessionList(): JSX.Element {
  const { data: sessions, isLoading, isError } = useSessionsQuery()
  const revokeMutation = useRevokeSessionMutation()

  if (isLoading) {
    return (
      <div className="space-y-4">
        {[1, 2].map((i) => (
          <Card key={i} className="animate-pulse">
            <CardHeader>
              <div className="h-4 w-3/4 rounded bg-muted" />
            </CardHeader>
            <CardContent>
              <div className="h-16 rounded bg-muted" />
            </CardContent>
          </Card>
        ))}
      </div>
    )
  }

  if (isError) {
    return (
      <p className="text-sm text-destructive">
        세션 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
      </p>
    )
  }

  if (sessions === undefined || sessions.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">활성 세션이 없습니다.</p>
    )
  }

  return (
    <div className="space-y-4">
      {/* 세션 종료 지연 안내 (FR-8, EC-29 5초 캐시) */}
      <p className="text-xs text-muted-foreground">
        세션 종료는 최대 5초 내 완전히 적용됩니다.
      </p>

      {sessions.map((session) => (
        <SessionCard
          key={session.sid}
          session={session}
          isRevoking={revokeMutation.isPending}
          onRevoke={revokeMutation.mutate}
        />
      ))}
    </div>
  )
}
