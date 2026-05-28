// 워크플로우 스킴 errorCode 한국어 매핑 + sonner toast 헬퍼 (use-workflow-schemes, use-workflow-scheme-assignment 공유)
import { toast } from 'sonner'
import { WorkflowSchemeApiError } from '@/api/workflow-schemes'

/** 워크플로우 스킴 errorCode → 한국어 사용자 메시지 매핑 */
const SCHEME_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  SCHEME_IN_USE: '사용 중인 스킴은 삭제할 수 없습니다',
  MAPPING_DUPLICATE: '이미 매핑된 이슈 타입입니다',
  MAPPING_DEFAULT_DUPLICATE: '기본 매핑은 한 개만 허용됩니다',
  SCHEME_STANDARD_NOT_DELETABLE: '표준 스킴은 삭제할 수 없습니다',
  SCHEME_STANDARD_FIELD_LOCKED: '표준 스킴의 키/이름은 변경할 수 없습니다',
}

/** 알 수 없는 에러에 사용되는 기본 메시지 */
export const DEFAULT_SCHEME_ERROR_MESSAGE = '요청 처리 중 오류가 발생했습니다'

/**
 * errorCode를 한국어 사용자 메시지로 변환한다.
 * 알 수 없는 코드면 기본 메시지를 반환한다.
 *
 * @param errorCode WorkflowSchemeApiError.errorCode
 */
export function mapWorkflowSchemeError(errorCode: string): string {
  return SCHEME_ERROR_MESSAGES[errorCode] ?? DEFAULT_SCHEME_ERROR_MESSAGE
}

/**
 * 에러에서 errorCode를 추출해 toast.error를 호출한다.
 * WorkflowSchemeApiError가 아니면 기본 메시지를 사용한다.
 *
 * @param error mutation onError 콜백에서 수신한 unknown 에러
 */
export function notifySchemeError(error: unknown): void {
  if (error instanceof WorkflowSchemeApiError) {
    toast.error(mapWorkflowSchemeError(error.errorCode))
  } else {
    toast.error(DEFAULT_SCHEME_ERROR_MESSAGE)
  }
}
