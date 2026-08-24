// 워크플로우 관리 errorCode 한국어 매핑 + sonner toast 헬퍼 (use-workflows-admin 전용)
import { toast } from 'sonner'
import { WorkflowAdminApiError } from '@/api/workflows-admin'

/**
 * 백엔드 errorCode → 한국어 사용자 메시지.
 *
 * ★ 백엔드가 던지는 코드와 **차집합 0** 이어야 한다. 한쪽만 검사하면 「매핑에만 있고
 * 백엔드엔 없는」 죽은 항목이 조용히 남고, 반대로 새 코드는 기본 메시지로 뭉개진다.
 * `__tests__/workflow-admin-error.test.ts` 가 양방향으로 대조한다.
 *
 * ★ 상태 제거 실패는 사유가 **둘**이다 — 이슈가 쓰고 있어서(`WORKFLOW_STATUS_IN_USE`)와
 * 전환이 가리켜서(`..._REFERENCED_BY_TRANSITION`). 문구를 합치면 사용자가 무엇을 고쳐야
 * 하는지 알 수 없다(전자는 이슈 이관, 후자는 전환 삭제).
 */
export const WORKFLOW_ADMIN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  WORKFLOW_NOT_FOUND: '워크플로우를 찾을 수 없습니다',
  WORKFLOW_KEY_CONFLICT: '이미 사용 중인 워크플로우 키입니다',
  WORKFLOW_IN_USE: '스킴이 사용 중인 워크플로우는 삭제할 수 없습니다',
  WORKFLOW_LOCKED: '잠긴 워크플로우는 편집할 수 없습니다',
  WORKFLOW_INVALID_REQUEST: '요청 내용이 올바르지 않습니다',
  WORKFLOW_PERMISSION_DENIED: '워크플로우를 편집할 권한이 없습니다',
  WORKFLOW_STATUS_COMPOSITION_INVALID: '마지막 상태는 뺄 수 없습니다',
  WORKFLOW_STATUS_IN_USE: '이 상태를 쓰는 이슈가 있어 뺄 수 없습니다',
  WORKFLOW_STATUS_REFERENCED_BY_TRANSITION: '이 상태를 가리키는 전환이 있어 뺄 수 없습니다',
  WORKFLOW_TRANSITION_CONFLICT: '같은 조건의 전환이 이미 있습니다',
  STATUS_KEY_CONFLICT: '이미 사용 중인 상태 키입니다',
  STATUS_NAME_CONFLICT: '이미 사용 중인 상태 이름입니다',
  STATUS_NOT_FOUND: '상태를 찾을 수 없습니다',
  STATUS_IN_USE: '워크플로우가 쓰는 상태는 삭제할 수 없습니다',
  STATUS_PROTECTED: '시스템 상태는 변경할 수 없습니다',
}

/** 알 수 없는 코드에 쓰는 기본 메시지 */
export const DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE = '요청 처리 중 오류가 발생했습니다'

/** errorCode 를 한국어 메시지로 옮긴다. 모르는 코드면 기본 메시지다. */
export function mapWorkflowAdminError(errorCode: string): string {
  return WORKFLOW_ADMIN_ERROR_MESSAGES[errorCode] ?? DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE
}

/** mutation `onError` 에서 받은 에러를 toast 로 띄운다. */
export function notifyWorkflowAdminError(error: unknown): void {
  if (error instanceof WorkflowAdminApiError) {
    toast.error(mapWorkflowAdminError(error.errorCode))
  } else {
    toast.error(DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE)
  }
}
