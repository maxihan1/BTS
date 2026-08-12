// 이슈 이동 preview 실패를 상태별 사용자 문구로 가르는 순수 함수 — 500·단절이 「키 확인」으로 새지 않게 한다

import { ApiError } from '@/api/client'
import { extractMoveErrorCode, MOVE_ERROR_CODES } from '@/api/issue-move'
import { issueMoveStrings as s } from '@/i18n/ko'

/**
 * preview 실패 오류를 사용자에게 보여 줄 문구로 옮긴다.
 *
 * **왜 세 갈래인가.** 이 세 부류는 사용자가 할 **다음 행동이 서로 다르다**.
 *
 * | 부류 | 원인 | 다음 행동 |
 * |---|---|---|
 * | 403 | 키 오타·미존재 · 이슈 UPDATE 없음 · 대상 CREATE 없음 (셋이 한 응답에 합쳐진다) | 키를 다시 본다 |
 * | 5xx · 응답 없음 | 서버 장애 · 네트워크 단절 · 응답 해석 실패 | 잠시 후 재시도 |
 * | 그 외 4xx | 이슈가 사라짐(404) · 세션 만료(401) · 요청 형식(400) | 새로고침 |
 *
 * 한 문구로 뭉치면 서버가 죽었을 때도 「키를 확인해 주세요」라고 말하게 된다 —
 * 그게 이 함수가 생긴 이유다.
 *
 * **403 은 게이트 이후에도 남는다.** `isValidProjectKey` 가 요청 전에 거르는 것은
 * **형식 위반**뿐이다. `NOPE` 처럼 형식이 맞고 존재하지 않는 키는 그대로 403 이 되므로
 * 403 문구는 여전히 「키 확인」을 앞세운다.
 *
 * @param err `previewMove` 가 던진 값. `ApiError` 가 아닐 수 있다(fetch 거부 · Zod 파싱 실패).
 */
export function resolvePreviewErrorMessage(err: unknown): string {
  // 상태 코드 자체가 없는 실패 — 응답을 받지 못했거나 해석하지 못했다.
  if (!(err instanceof ApiError)) return s.errorPreviewTemporary
  if (err.status === 403) return s.errorPreviewForbidden
  if (err.status >= 500) return s.errorPreviewTemporary
  // 대상 프로젝트 미존재만 상태 코드가 아니라 errorCode 로 갈라낸다 — 「그 외 4xx」의
  // 「새로고침」은 이 경우 틀린 안내다(새로고침해도 키는 그대로다).
  // 실행 단계(`MoveIssueDialog` onError)가 이미 같은 코드로 갈라내므로, 여기서 안 갈라내면
  // 같은 다이얼로그가 단계마다 다른 설명을 한다.
  // ★운영에서는 403 이 먼저 걸려 도달하지 않는다 — `errorProjectNotFound` KDoc 참조.
  if (extractMoveErrorCode(err) === MOVE_ERROR_CODES.PROJECT_NOT_FOUND) {
    return s.errorProjectNotFound
  }
  return s.errorPreview
}
