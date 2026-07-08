// 관리자 Slack 연결 상태 카드 — 현재 연결 표시 + 연결/다시연결 버튼 (FR-SL-01 D6)
import type { JSX, ReactNode } from 'react'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useDateFormat } from '@/hooks/use-date-format'
import { getSlackInstallation, getSlackInstallUrl } from '@/api/slack'
import type { SlackInstallation } from '@/api/slack'

/** `useQuery`의 Slack 연결 상태 캐시 키 */
const SLACK_INSTALLATION_QUERY_KEY = ['slack', 'installation'] as const

/** 연결됨인데 teamName이 없을 때(계약상 발생하지 않아야 하는 방어적 폴백) 표시할 문구 */
const UNKNOWN_TEAM_NAME = '알 수 없는 워크스페이스'

/** botUserId가 null일 때(연결 직후 아직 봇 정보가 채워지지 않은 과도기 등) 표시할 자리표시자 — 날짜 포맷의 "—" 관례와 통일 */
const UNKNOWN_BOT_USER_ID = '—'

/** `GET /api/v1/slack/installation` 조회 실패 시 표시할 오류 메시지 */
const LOAD_ERROR_MESSAGE = 'Slack 연결 상태를 불러오지 못했습니다.'

/** authorize URL 조회(`getSlackInstallUrl`) 실패 시 표시할 일반 오류 메시지 */
const CONNECT_ERROR_MESSAGE = 'Slack 연결을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 로딩/에러/본문 세 상태가 공유하는 카드 레이아웃(Card > CardHeader > CardTitle).
 * 제목("Slack 연결")을 한 곳에서만 관리해 상태별 중복을 없앤다.
 */
function SlackConnectionCardShell({ children }: { readonly children: ReactNode }): JSX.Element {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Slack 연결</CardTitle>
      </CardHeader>
      {children}
    </Card>
  )
}

/**
 * 관리자 Slack 연결 상태 카드 — 진입점.
 *
 * `GET /api/v1/slack/installation` 조회 로딩/에러 게이트 후
 * {@link SlackConnectionCardContent}를 렌더한다.
 */
export function SlackConnectionCard(): JSX.Element {
  const { data, isLoading, isError } = useQuery({
    queryKey: SLACK_INSTALLATION_QUERY_KEY,
    queryFn: getSlackInstallation,
  })

  if (isLoading) {
    return (
      <SlackConnectionCardShell>
        <CardContent>
          <p className="text-sm text-muted-foreground">불러오는 중...</p>
        </CardContent>
      </SlackConnectionCardShell>
    )
  }

  if (isError || data === undefined) {
    return (
      <SlackConnectionCardShell>
        <CardContent>
          <p role="alert" aria-live="polite" className="text-sm text-destructive">
            {LOAD_ERROR_MESSAGE}
          </p>
        </CardContent>
      </SlackConnectionCardShell>
    )
  }

  return <SlackConnectionCardContent installation={data} />
}

interface SlackConnectionCardContentProps {
  readonly installation: SlackInstallation
}

/**
 * 조회 성공 후에만 렌더되는 실제 카드 본문.
 *
 * - connected=true → 워크스페이스 이름 + 설치자(있을 때만) + 봇 사용자 ID + 설치일 + 최근 갱신 + "다시 연결" 버튼
 * - connected=false → 미연결 안내 + "Slack에 연결" 버튼
 * - 두 경우 모두 버튼 클릭 시 authorize URL을 조회해 `window.location.assign`으로 이동한다.
 *   실패하면 카드를 유지한 채 인라인 오류 메시지를 표시한다.
 */
function SlackConnectionCardContent({ installation }: SlackConnectionCardContentProps): JSX.Element {
  const { formatDate } = useDateFormat()
  const [connectError, setConnectError] = useState<string | null>(null)
  const [isConnecting, setIsConnecting] = useState(false)

  async function handleConnect(): Promise<void> {
    setConnectError(null)
    setIsConnecting(true)
    try {
      const { url } = await getSlackInstallUrl()
      window.location.assign(url)
    } catch {
      setConnectError(CONNECT_ERROR_MESSAGE)
      setIsConnecting(false)
    }
  }

  return (
    <SlackConnectionCardShell>
      <CardContent className="space-y-3">
        {installation.connected ? (
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt className="text-muted-foreground">워크스페이스</dt>
            <dd>{installation.teamName ?? UNKNOWN_TEAM_NAME}</dd>
            {installation.installerName !== null && (
              <>
                <dt className="text-muted-foreground">설치자</dt>
                <dd>{installation.installerName}</dd>
              </>
            )}
            <dt className="text-muted-foreground">봇 사용자 ID</dt>
            <dd>{installation.botUserId ?? UNKNOWN_BOT_USER_ID}</dd>
            <dt className="text-muted-foreground">설치일</dt>
            <dd>{formatDate(installation.installedAt)}</dd>
            <dt className="text-muted-foreground">최근 갱신</dt>
            <dd>{formatDate(installation.updatedAt)}</dd>
          </dl>
        ) : (
          <p className="text-sm text-muted-foreground">Slack에 연결되어 있지 않습니다.</p>
        )}

        {connectError !== null && (
          <p role="alert" aria-live="polite" className="text-sm text-destructive">
            {connectError}
          </p>
        )}
      </CardContent>
      <CardFooter className="justify-end">
        <Button type="button" disabled={isConnecting} onClick={handleConnect}>
          {installation.connected ? '다시 연결' : 'Slack에 연결'}
        </Button>
      </CardFooter>
    </SlackConnectionCardShell>
  )
}
