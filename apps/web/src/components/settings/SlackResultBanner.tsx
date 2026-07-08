// Slack OAuth 콜백 결과 배너 — ?installed/?error를 사용자 메시지로 표시(순수 컴포넌트, 라우터 의존 없음)
import type { JSX } from 'react'
import { CheckCircle2, AlertTriangle, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * Slack OAuth 콜백 에러 코드 → 한국어 메시지 매핑.
 *
 * 이 맵에 없는 코드(oauth_failed·install_failed·미지원 코드 전부)는
 * {@link SLACK_FALLBACK_ERROR_MESSAGE}로 폴백한다 — 에러 코드 원문을 화면에 그대로 노출하지 않는다.
 * T7(콜백 결과 페이지)이 재사용할 수 있도록 export한다.
 */
export const slackErrorMessages: Record<string, string> = {
  access_denied: 'Slack 연결이 취소되었습니다.',
  invalid_state: '연결 요청이 만료되었거나 유효하지 않습니다. 다시 시도해 주세요.',
  invalid_code: 'Slack이 인증 코드를 거부했습니다. 다시 시도해 주세요.',
  missing_params: '연결 정보가 누락되었습니다. 다시 시도해 주세요.',
  exchange_failed: 'Slack과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  unsupported_install_type: '워크스페이스 단위 설치만 지원합니다(조직 전체 설치 불가).',
  missing_access_token: 'Slack이 유효한 봇 토큰을 반환하지 않았습니다. 다시 시도해 주세요.',
}

/** {@link slackErrorMessages}에 없는(미지원) 에러 코드에 대한 일반 폴백 메시지. */
export const SLACK_FALLBACK_ERROR_MESSAGE = 'Slack 연결에 실패했습니다. 다시 시도해 주세요.'

/** {@link resolveSlackBannerMessage}의 반환 형태 — 배너 종류와 표시 문구. */
export interface SlackBannerMessage {
  readonly kind: 'success' | 'error'
  readonly text: string
}

/**
 * installed/error 쿼리 값으로부터 배너에 표시할 메시지를 계산하는 순수 함수.
 * error가 있으면(installed 동시 존재 여부와 무관하게) 항상 error를 우선한다.
 * 둘 다 없으면 null(배너 미표시). T7이 라우터 search 파싱 결과로 그대로 재사용한다.
 *
 * @param installed 연결된 Slack 워크스페이스 이름(성공 시)
 * @param error OAuth 콜백 에러 코드(실패 시)
 * @returns 배너 종류+문구, 또는 표시할 것이 없으면 null
 */
export function resolveSlackBannerMessage(
  installed?: string,
  error?: string,
): SlackBannerMessage | null {
  if (error !== undefined) {
    return { kind: 'error', text: slackErrorMessages[error] ?? SLACK_FALLBACK_ERROR_MESSAGE }
  }
  if (installed !== undefined) {
    return { kind: 'success', text: `${installed} 워크스페이스에 연결되었습니다` }
  }
  return null
}

export interface SlackResultBannerProps {
  /** 연결 성공 시 워크스페이스 이름 */
  readonly installed?: string
  /** 연결 실패 시 에러 코드 */
  readonly error?: string
  /** "다시 시도" 버튼 클릭 핸들러 — 주어지고 오류 배너일 때만 버튼이 렌더된다(재시도 흐름은 페이지가 배선) */
  readonly onRetry?: () => void
  /** "닫기" 버튼 클릭 핸들러 — 주어지면 성공/실패 배너 모두에 렌더된다(배너 숨김은 페이지가 배선) */
  readonly onDismiss?: () => void
}

/**
 * Slack OAuth 콜백 결과 배너 — `installed`/`error` props로 성공/실패 메시지를 렌더한다.
 * 라우터 search 파싱·쿼리 정리·재시도/닫기 시 상태 변경은 이 컴포넌트를 사용하는 페이지(T7)가 담당한다.
 */
export function SlackResultBanner({
  installed,
  error,
  onRetry,
  onDismiss,
}: SlackResultBannerProps): JSX.Element | null {
  const message = resolveSlackBannerMessage(installed, error)
  if (message === null) {
    return null
  }

  const isSuccess = message.kind === 'success'

  return (
    <div
      role="alert"
      className={cn(
        'flex items-start gap-2 rounded-lg border p-3 text-sm',
        isSuccess ? 'border-primary/20 bg-primary/10 text-primary' : 'border-destructive/30 bg-destructive/10 text-destructive',
      )}
    >
      {isSuccess ? (
        <CheckCircle2 className="h-4 w-4 shrink-0 mt-0.5" aria-hidden="true" />
      ) : (
        <AlertTriangle className="h-4 w-4 shrink-0 mt-0.5" aria-hidden="true" />
      )}
      <span className="flex-1">{message.text}</span>
      <div className="flex shrink-0 items-center gap-1">
        {!isSuccess && onRetry !== undefined && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            다시 시도
          </Button>
        )}
        {onDismiss !== undefined && (
          <Button type="button" variant="ghost" size="icon-xs" aria-label="닫기" onClick={onDismiss}>
            <X className="h-3.5 w-3.5" aria-hidden="true" />
          </Button>
        )}
      </div>
    </div>
  )
}
