// 이슈 생성 폼의 순수 검증 헬퍼 — required 커스텀 필드 빈값 판정 + 에러 코드 → 문구 매핑
import { ApiError } from '@/api/client'
import { issueCreateStrings } from '@/i18n/ko'
import type { CustomField } from '@/api/custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// isRequiredFieldEmpty — required 필드 빈값 판정 헬퍼 (스펙 E-3)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * required 커스텀 필드의 현재 값이 비어 있는지 판정한다 (스펙 E-3 기준).
 *
 * ★판정을 **열거가 아니라 선판정**으로 짠다. 「값이 아예 없다」(`undefined`/`null`)는
 * 필드 유형과 무관하게 빈값이므로 switch 앞에서 한 번에 거른다. 분기마다 `undefined` 를
 * 다시 적는 방식은 한 분기만 빠뜨려도 조용히 뚫리고, 새 `fieldType` 이 생길 때
 * 같은 구멍이 재발한다 — 실제로 MULTI_SELECT 와 CHECKBOX 두 분기가 그렇게 뚫려 있었다
 * (TODOS 「required MULTI_SELECT 가 클라이언트 검증을 그냥 통과한다」, 2026-08-09 봉합).
 *
 * 선판정 이후 각 분기는 **그 유형 고유의 빈값**만 본다.
 * - 텍스트류(SHORT_TEXT/LONG_TEXT/URL/DATE/DATETIME/SINGLE_SELECT/RADIO): `''`
 * - NUMBER: `NaN`. **0 은 유효값**이므로 falsy 검사를 쓰면 안 된다.
 * - MULTI_SELECT: 빈 배열
 * - CHECKBOX: 체크되지 않음(`raw !== true`)
 *
 * ★CHECKBOX 가 **백엔드보다 엄격**한 것은 의도된 것이다(2026-08-09 Maxi 확정).
 * 백엔드 `CustomFieldValueValidator` 는 `value == null` 만 거부해 `false` 를 충족으로 본다.
 * 프론트는 「필수 체크박스는 체크해야 제출 가능」으로 둔다 — 필수 체크박스의 실제 용도가
 * 약관 동의류라 「해제된 채 통과」가 의미를 잃기 때문이다.
 * 판정을 `undefined → 빈값 / false → 유효` 로 두는 절충안은 **택하지 않았다**. 그러면
 * 화면상 똑같이 해제된 두 상태(첫 방문 / 토글 왕복 후)가 서로 다르게 판정돼
 * 사용자가 원인을 알 수 없는 데드락이 된다.
 */
export function isRequiredFieldEmpty(
  fieldType: CustomField['fieldType'],
  raw: unknown,
): boolean {
  // ★선판정 — 값이 아예 없으면 유형과 무관하게 빈값이다.
  if (raw === undefined || raw === null) return true

  switch (fieldType) {
    case 'SHORT_TEXT':
    case 'LONG_TEXT':
    case 'URL':
    case 'DATE':
    case 'DATETIME':
    case 'SINGLE_SELECT':
    case 'RADIO':
      return raw === ''
    case 'NUMBER':
      return typeof raw === 'number' && isNaN(raw)
    case 'MULTI_SELECT':
      return Array.isArray(raw) && raw.length === 0
    case 'CHECKBOX':
      return raw !== true
  }
}

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

