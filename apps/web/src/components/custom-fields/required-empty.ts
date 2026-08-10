// required 커스텀 필드 「빈값」 판정의 단일 출처 — 생성 폼과 편집 화면이 같은 규칙을 쓴다
import type { CustomField } from '@/api/custom-fields.types'

/**
 * required 커스텀 필드의 현재 값이 비어 있는지 판정한다 (스펙 E-3 기준).
 *
 * ## 왜 별도 모듈인가
 *
 * 2026-08-10 이전에는 이 함수가 **글자 단위로 같은 사본 2개**로 존재했다 —
 * `IssueCreateForm`(생성)과 `IssueCustomFieldsEdit`(편집).
 * 그 상태에서 생성 폼만 고치자 **두 화면의 계약이 갈라졌다.**
 *
 * | 입력 | 생성 폼(봉합 후) | 편집 화면(옛 사본) |
 * |---|---|---|
 * | required MULTI_SELECT 가 `undefined` | 빈값 → 차단 | **통과** → 백엔드 422 |
 * | required CHECKBOX 미체크 | 빈값 → 차단 | **통과** |
 *
 * 같은 이슈의 같은 필드가 **만들 때와 고칠 때 규칙이 다르고**, 사용자는 이유를 알 수 없다.
 * 사본을 없애는 것이 이 모듈의 존재 이유다.
 *
 * ## 판정 규칙
 *
 * ★**열거가 아니라 선판정**으로 짠다. 「값이 아예 없다」(`undefined`/`null`)는 필드 유형과
 * 무관하게 빈값이므로 switch 앞에서 한 번에 거른다. 분기마다 `undefined` 를 다시 적는 방식은
 * 한 분기만 빠뜨려도 조용히 뚫리고, 새 `fieldType` 이 생길 때 같은 구멍이 재발한다 —
 * 실제로 MULTI_SELECT 와 CHECKBOX 두 분기가 그렇게 뚫려 있었다.
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
 * `undefined → 빈값 / false → 유효` 절충안은 **택하지 않았다**. 그러면 화면상 똑같이 해제된
 * 두 상태(첫 방문 / 토글 왕복 후)가 다르게 판정돼 원인을 알 수 없는 데드락이 된다.
 *
 * @param fieldType 커스텀 필드 유형
 * @param raw 현재 값 (폼 draft 또는 저장된 값)
 * @returns 빈값이면 `true`
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
