// 본인 Slack 계정 연결 카드 — 연결 상태 조회 + 연결/해제 mutation (FR-SL-02 D6)
import type { JSX, ReactNode } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useDateFormat } from '@/hooks/use-date-format'
import { getMyConnection, connectSlack, disconnectSlack } from '@/api/slack'
import { ApiError } from '@/api/client'
import type { SlackConnection } from '@/api/slack'

/** `useQuery`/`invalidateQueries`가 공유하는 본인 Slack 연결 상태 캐시 키 */
const SLACK_ME_CONNECTION_QUERY_KEY = ['slack', 'me', 'connection'] as const

/** `GET /api/v1/slack/me/connection` 조회 실패 시 표시할 오류 메시지 */
const LOAD_ERROR_MESSAGE = 'Slack 연결 상태를 불러오지 못했습니다.'

/** 매핑에 없는(알 수 없는) 에러 코드/네트워크 오류에 대한 일반 폴백 메시지 */
const FALLBACK_ERROR_MESSAGE = 'Slack 연결 중 문제가 발생했습니다'

/**
 * `connectSlack`/`disconnectSlack` 실패 시 `ApiError.body.code` → 한국어 안내 메시지 매핑.
 *
 * 스펙 EC3/EC4/EC5(워크스페이스 미설치/봇 스코프 부족/이메일 미발견) 대응.
 * 매핑에 없는 코드(EMAIL_UNAVAILABLE/SLACK_TEMPORARILY_UNAVAILABLE 등)는 {@link FALLBACK_ERROR_MESSAGE}로 폴백한다 —
 * 에러 코드 원문을 화면에 그대로 노출하지 않는다(공유 error-key 매핑 관례, PR #106).
 */
const CONNECT_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  WORKSPACE_NOT_INSTALLED: '먼저 관리자가 워크스페이스에 Slack을 연결해야 합니다',
  SLACK_SCOPE_MISSING: '관리자가 Slack 앱을 다시 연결해야 합니다',
  SLACK_USER_NOT_FOUND: 'Slack에서 회원님 이메일로 계정을 찾을 수 없습니다',
}

/**
 * mutation 에러를 한국어 안내 메시지로 변환한다.
 *
 * `ApiError`가 아니거나(`error.body`가 객체가 아니거나) `code`가 매핑에 없으면
 * {@link FALLBACK_ERROR_MESSAGE}로 폴백한다.
 *
 * @param error `useMutation`의 error 값 (unknown)
 * @returns 사용자에게 노출할 한국어 메시지
 */
function mapConnectionError(error: unknown): string {
  if (!(error instanceof ApiError)) return FALLBACK_ERROR_MESSAGE
  if (error.body === null || typeof error.body !== 'object') return FALLBACK_ERROR_MESSAGE

  const code = (error.body as { code?: unknown }).code
  if (typeof code !== 'string') return FALLBACK_ERROR_MESSAGE

  return CONNECT_ERROR_MESSAGES[code] ?? FALLBACK_ERROR_MESSAGE
}

/**
 * 로딩/에러/본문 세 상태가 공유하는 카드 레이아웃(Card > CardHeader > CardTitle).
 * 제목("Slack 연결")을 한 곳에서만 관리해 상태별 중복을 없앤다.
 */
function SlackUserConnectionCardShell({ children }: { readonly children: ReactNode }): JSX.Element {
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
 * 본인 Slack 계정 연결 카드 — 진입점.
 *
 * `GET /api/v1/slack/me/connection` 조회 로딩/에러 게이트 후
 * {@link SlackUserConnectionCardContent}를 렌더한다.
 */
export function SlackUserConnectionCard(): JSX.Element {
  const { data, isLoading, isError } = useQuery({
    queryKey: SLACK_ME_CONNECTION_QUERY_KEY,
    queryFn: getMyConnection,
  })

  if (isLoading) {
    return (
      <SlackUserConnectionCardShell>
        <CardContent>
          <p className="text-sm text-muted-foreground">불러오는 중...</p>
        </CardContent>
      </SlackUserConnectionCardShell>
    )
  }

  if (isError || data === undefined) {
    return (
      <SlackUserConnectionCardShell>
        <CardContent>
          <p role="alert" aria-live="polite" className="text-sm text-destructive">
            {LOAD_ERROR_MESSAGE}
          </p>
        </CardContent>
      </SlackUserConnectionCardShell>
    )
  }

  return <SlackUserConnectionCardContent connection={data} />
}

interface SlackUserConnectionCardContentProps {
  readonly connection: SlackConnection
}

/**
 * 조회 성공 후에만 렌더되는 실제 카드 본문.
 *
 * - connected=true → 워크스페이스 이름 + 연결 시각 + "연결 해제" 버튼
 * - connected=false → 미연결 안내 + "Slack 연결" 버튼
 * - 두 mutation 모두 성공 시 `invalidateQueries`로 재조회한다(캐시 통째 덮어쓰기 금지 — 부분응답 플리커 회귀 방지).
 * - 실패하면 카드를 유지한 채 인라인 오류 배너를 표시한다.
 */
function SlackUserConnectionCardContent({ connection }: SlackUserConnectionCardContentProps): JSX.Element {
  const { formatDateTime } = useDateFormat()
  const queryClient = useQueryClient()

  const invalidate = (): Promise<void> =>
    queryClient.invalidateQueries({ queryKey: SLACK_ME_CONNECTION_QUERY_KEY })

  const connectMutation = useMutation({
    mutationFn: connectSlack,
    onSuccess: invalidate,
  })
  const disconnectMutation = useMutation({
    mutationFn: disconnectSlack,
    onSuccess: invalidate,
  })

  const errorMessage =
    connectMutation.error !== null
      ? mapConnectionError(connectMutation.error)
      : disconnectMutation.error !== null
        ? mapConnectionError(disconnectMutation.error)
        : null

  const isPending = connectMutation.isPending || disconnectMutation.isPending

  return (
    <SlackUserConnectionCardShell>
      <CardContent className="space-y-3">
        {connection.connected ? (
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt className="text-muted-foreground">워크스페이스</dt>
            <dd>{connection.workspaceName}</dd>
            <dt className="text-muted-foreground">연결 시각</dt>
            <dd>{formatDateTime(connection.linkedAt)}</dd>
          </dl>
        ) : (
          <p className="text-sm text-muted-foreground">Slack에 연결되어 있지 않습니다.</p>
        )}

        {errorMessage !== null && (
          <p role="alert" aria-live="polite" className="text-sm text-destructive">
            {errorMessage}
          </p>
        )}
      </CardContent>
      <CardFooter className="justify-end">
        {connection.connected ? (
          <Button
            type="button"
            variant="outline"
            disabled={isPending}
            onClick={() => disconnectMutation.mutate()}
          >
            연결 해제
          </Button>
        ) : (
          <Button type="button" disabled={isPending} onClick={() => connectMutation.mutate()}>
            Slack 연결
          </Button>
        )}
      </CardFooter>
    </SlackUserConnectionCardShell>
  )
}
