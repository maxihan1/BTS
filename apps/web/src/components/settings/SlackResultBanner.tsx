// Slack OAuth 콜백 결과 배너 — ?installed/?error를 사용자 메시지로 표시(순수 컴포넌트, 라우터 의존 없음)
import type { JSX } from 'react'
import { CheckCircle2, AlertTriangle } from 'lucide-react'

/** Slack OAuth 콜백 에러 코드 → 한국어 메시지. 여기 없는 코드는 일반 실패 메시지로 폴백한다. */
const slackErrorMessages: Record<string, string> = {
  access_denied: 'Slack 연결이 취소되었습니다.',
  invalid_state: '연결 요청이 만료되었거나 유효하지 않습니다. 다시 시도해 주세요.',
  missing_params: '연결 정보가 누락되었습니다. 다시 시도해 주세요.',
  exchange_failed: 'Slack과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  unsupported_install_type: '워크스페이스 단위 설치만 지원합니다(조직 전체 설치 불가).',
  missing_access_token: 'Slack이 유효한 봇 토큰을 반환하지 않았습니다. 다시 시도해 주세요.',
}

/**
 * installed/error 쿼리 값으로부터 배너에 표시할 메시지를 계산한다.
 * error가 있으면(installed 동시 존재 여부와 무관하게) 항상 error를 우선한다.
 * 둘 다 없으면 null(배너 미표시).
 */
function resolveSlackBannerMessage(
  installed?: string,
  error?: string,
): { kind: 'success' | 'error'; text: string } | null {
  if (error !== undefined) {
    return {
      kind: 'error',
      text: slackErrorMessages[error] ?? 'Slack 연결에 실패했습니다. 다시 시도해 주세요.',
    }
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
}

/**
 * Slack OAuth 콜백 결과 배너 — `installed`/`error` props만으로 성공/실패 메시지를 렌더한다.
 * 라우터 search 파싱·쿼리 정리는 이 컴포넌트를 사용하는 페이지(T7)가 담당한다.
 */
export function SlackResultBanner({ installed, error }: SlackResultBannerProps): JSX.Element | null {
  const message = resolveSlackBannerMessage(installed, error)
  if (message === null) {
    return null
  }

  const isSuccess = message.kind === 'success'

  return (
    <div
      role="alert"
      className={
        isSuccess
          ? 'flex items-start gap-2 rounded-lg bg-primary/10 p-3 text-sm text-primary'
          : 'flex items-start gap-2 rounded-lg bg-destructive/10 p-3 text-sm text-destructive'
      }
    >
      {isSuccess ? (
        <CheckCircle2 className="h-4 w-4 shrink-0 mt-0.5" aria-hidden="true" />
      ) : (
        <AlertTriangle className="h-4 w-4 shrink-0 mt-0.5" aria-hidden="true" />
      )}
      <span>{message.text}</span>
    </div>
  )
}
