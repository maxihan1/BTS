// 이슈 생성 폼의 순수 검증 헬퍼 — 백엔드 에러 코드 → 사용자 문구 매핑
// (required 빈값 판정은 공용 단일 출처로 이관. @/components/custom-fields/required-empty)
import { ApiError } from '@/api/client'
import { issueCreateStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → 사용자 메시지 매핑
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ApiError body에서 errorCode 를 추출해 사용자 노출 메시지로 변환한다.
 *
 * @param err 임의 에러 — ApiError 가 아니면 기본 메시지 반환
 * @returns 사용자 노출 한국어 에러 메시지
 */
export function resolveCreateErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    // 403 — 권한 부족. 상태 코드를 먼저 본다. errorCode 가 무엇이든 「잠시 후 다시
    // 시도해 주세요」는 거짓말이다 (재시도해도 안 되는데 재시도를 권한다).
    if (err.status === 403) {
      return issueCreateStrings.errorCreateForbidden
    }
    const body = err.body
    if (typeof body === 'object' && body !== null && 'errorCode' in body) {
      const { errorCode } = body as { errorCode: unknown }
      if (errorCode === 'PROJECT_NOT_FOUND') {
        return issueCreateStrings.errorProjectNotFound
      }
      // FR-UX-09 F2 — 지정한 담당자가 존재하지 않을 때 백엔드가 내는 422.
      if (errorCode === 'ASSIGNEE_NOT_FOUND') {
        return issueCreateStrings.errorAssigneeNotFound
      }
      // 커스텀 필드 값 검증 실패 (422). 매핑이 없으면 errorDefault 로 떨어져
      // 「잠시 후 다시 시도해 주세요」가 뜨는데, **재시도해도 안 되는** 입력 오류다.
      // 클라이언트가 못 잡는 형식 오류(URL 타입에 `abc` 등)가 이 경로로 온다 —
      // 폼이 noValidate 라 브라우저 검증이 꺼져 있고 위젯에 형식 검사가 없다.
      if (errorCode === 'CUSTOM_FIELD_VALIDATION_FAILED') {
        return issueCreateStrings.errorCustomFieldInvalid
      }
    }
  }
  return issueCreateStrings.errorDefault
}

