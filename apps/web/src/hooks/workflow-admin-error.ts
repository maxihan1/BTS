// 워크플로우 관리 errorCode 한국어 매핑 + sonner toast 헬퍼 (use-workflows-admin 전용)
import { toast } from 'sonner'
import { WorkflowAdminApiError } from '@/api/workflows-admin'

/**
 * 백엔드 errorCode → 한국어 사용자 메시지.
 *
 * ★ **이 표는 `HANDLER_FILES` 에 등재된 핸들러가 내는 코드 전량과 차집합 0 이어야 한다.**
 * (개수를 문장에 박지 않는다 — 판별식이 그 숫자를 읽지 않으므로 핸들러가 늘 때마다 조용히 썩는다.
 * 실제로 이 PR 이 5번째 핸들러를 더하면서 「4종」이 어긋났다.)
 * `__tests__/workflow-admin-error.test.ts` 가 그 핸들러 `.kt` 파일을 **직접 읽어** 코드를
 * 뽑고 양방향으로 대조한다. 종전에는 프론트에 손으로 적은 배열과 비교해서
 * 「양쪽 다 프론트」인 채로 초록이었다 — `two-lists-never-check-each-other` 그대로였고,
 * 그 결과 실제로 나오는 `WORKFLOW_DEFINITION_ACCESS_DENIED` 가 빠져 있었다.
 *
 * ★★ 상태 제거 실패는 사유가 **둘**이다 — 이슈가 쓰고 있어서(`WORKFLOW_STATUS_IN_USE`)와
 * 전환이 가리켜서(`..._REFERENCED_BY_TRANSITION`). 사용자가 할 일이 다르다(이슈 이관 vs
 * 전환 삭제)라 문구를 합치지 않는다.
 */
export const WORKFLOW_ADMIN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  // WorkflowExceptionHandler
  WORKFLOW_NOT_FOUND: '워크플로우를 찾을 수 없습니다',
  WORKFLOW_KEY_CONFLICT: '이미 사용 중인 워크플로우 키입니다',
  WORKFLOW_IN_USE: '스킴이 사용 중인 워크플로우는 삭제할 수 없습니다',
  WORKFLOW_LOCKED: '잠긴 워크플로우는 편집할 수 없습니다',
  WORKFLOW_INVALID_REQUEST: '요청 내용이 올바르지 않습니다',
  WORKFLOW_VALIDATION_FAILED: '전환 규칙 검증에 실패했습니다',
  WORKFLOW_UNAVAILABLE: '워크플로우 서비스가 잠시 응답하지 않습니다. 잠시 후 다시 시도하세요',
  /** 명시적 resolver 경로. 이 화면의 **모든 쓰기**가 여기를 지난다 */
  WORKFLOW_DEFINITION_ACCESS_DENIED: '워크플로우를 편집할 권한이 없습니다',
  /** Spring `AccessDeniedException` 경로. 이 BC 는 `@PreAuthorize` 를 안 쓰므로 지금은 안 나온다 */
  WORKFLOW_PERMISSION_DENIED: '접근이 거부되었습니다',
  // WorkflowStatusCompositionExceptionHandler
  //   ★ 이 코드는 사유 넷(미편성 상태 · 마지막 상태 · 중복 · 집합 불일치)이 공용으로 쓴다.
  //     「마지막 상태」로 단정하면 나머지 셋에서 거짓 안내가 된다 — 서버 문구를 덧붙인다.
  WORKFLOW_STATUS_COMPOSITION_INVALID: '상태 편성을 바꿀 수 없습니다',
  WORKFLOW_STATUS_IN_USE: '이 상태를 쓰는 이슈가 있어 뺄 수 없습니다',
  WORKFLOW_STATUS_REFERENCED_BY_TRANSITION: '이 상태를 가리키는 전환이 있어 뺄 수 없습니다',
  // TransitionConflictExceptionHandler
  WORKFLOW_TRANSITION_CONFLICT: '같은 조건의 전환이 이미 있습니다',
  // WorkflowPublishExceptionHandler
  //   ★ 두 코드는 사용자가 할 일이 다르다. 버전 충돌은 「다시 불러오기」, 이관 필요는
  //     「옮길 상태 고르기」다 — 문구를 합치면 어느 쪽도 안내가 되지 않는다.
  //   ★ 「다시 불러오세요」로 끝내면 안 된다 — 초안의 기준 버전은 저장으로 바뀌지 않아
  //     다시 불러와 발행해도 같은 409 가 반복된다. 출구는 초안 폐기뿐이다.
  WORKFLOW_VERSION_CONFLICT: '다른 사용자가 먼저 발행했습니다. 초안을 폐기하고 다시 편집하세요',
  WORKFLOW_PUBLISH_MAPPING_REQUIRED: '사라지는 상태에 이슈가 남아 있습니다. 옮길 상태를 정하세요',
  // 축은 여덟이지만 코드는 하나다 — 무엇을 고쳐야 하는지는 서버 메시지가 싣는다.
  WORKFLOW_MIGRATION_INVALID_MAPPING: '옮길 상태 지정이 올바르지 않습니다',
  //   ★ 「초안이 없다」를 범용 400 문구로 접으면 「요청이 잘못됐다」로 보인다 — 관리자가 할 일이 다르다.
  WORKFLOW_DRAFT_NOT_FOUND: '편집 중인 초안이 없습니다',
  // StatusExceptionHandler
  STATUS_KEY_CONFLICT: '이미 사용 중인 상태 키입니다',
  STATUS_NAME_CONFLICT: '이미 사용 중인 상태 이름입니다',
  STATUS_NOT_FOUND: '상태를 찾을 수 없습니다',
  STATUS_IN_USE: '워크플로우가 쓰는 상태는 삭제할 수 없습니다',
  STATUS_PROTECTED: '시스템 상태는 변경할 수 없습니다',
}

/** 알 수 없는 코드에 쓰는 기본 메시지 */
export const DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE = '요청 처리 중 오류가 발생했습니다'

/**
 * 서버 문구를 덧붙여야 하는 코드.
 *
 * 백엔드가 **막은 대상의 이름**을 message 에 일부러 싣는 자리다 — 예컨대 상태 제거를 막은
 * 전환 이름. 그 핸들러 KDoc 이 「개수만 주면 어느 것을 먼저 지워야 할지 알 수 없어 409 가
 * 그냥 「안 됨」이 된다」고 명시한다. 우리 쪽 고정 문구만 띄우면 그 정보를 두 번 버린다.
 */
const APPEND_SERVER_MESSAGE = new Set([
  'WORKFLOW_STATUS_COMPOSITION_INVALID',
  'WORKFLOW_STATUS_IN_USE',
  'WORKFLOW_STATUS_REFERENCED_BY_TRANSITION',
  'WORKFLOW_VALIDATION_FAILED',
])

/**
 * errorCode 를 한국어 메시지로 옮긴다. 모르는 코드면 기본 메시지다.
 *
 * @param errorCode 백엔드 `error.code`
 * @param serverMessage 백엔드 `error.message`. 위 집합의 코드에서만 뒤에 붙는다
 */
export function mapWorkflowAdminError(errorCode: string, serverMessage?: string): string {
  const base = WORKFLOW_ADMIN_ERROR_MESSAGES[errorCode] ?? DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE
  const detail = serverMessage?.trim() ?? ''
  if (detail.length > 0 && APPEND_SERVER_MESSAGE.has(errorCode)) {
    return `${base} — ${detail}`
  }
  return base
}

/** mutation `onError` 에서 받은 에러를 toast 로 띄운다. */
export function notifyWorkflowAdminError(error: unknown): void {
  if (error instanceof WorkflowAdminApiError) {
    toast.error(mapWorkflowAdminError(error.errorCode, error.detail))
  } else {
    toast.error(DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE)
  }
}
