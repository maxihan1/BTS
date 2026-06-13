// 이슈 템플릿 본문에 삽입 가능한 변수 상수 — 백엔드 TemplateVariable enum 수동 미러

/**
 * 백엔드 `TemplateVariable.kt` enum 값과 1:1로 대응하는 토큰 상수 목록.
 *
 * # 왜 이 파일이 존재하는가
 * 백엔드는 이슈 생성 시 `{{author}}`/`{{date}}`/`{{project}}` 토큰을
 * **정확히 일치할 때만** 실제 값으로 치환한다(대소문자·공백 어긋나면 리터럴 유지).
 * 프론트가 항상 올바른 토큰을 삽입하도록 이 상수가 단일 출처 역할을 한다.
 *
 * # 수동 미러 주의
 * Kotlin enum을 TypeScript에서 직접 import할 수 없으므로 수동으로 미러한다.
 * 여기서 토큰 문자열이 백엔드와 어긋나면 삽입 버튼이 치환되지 않는 토큰을
 * 넣게 되어 사용자에게 가짜 기능처럼 보인다.
 *
 * # 백엔드 출처
 * `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/template/domain/TemplateVariable.kt`
 *
 * 백엔드가 이 enum을 변경하면 이 파일도 반드시 함께 변경해야 한다.
 * 드리프트 감지를 위해 `__tests__/template-variables.test.ts`에 토큰 정합 테스트가 있다.
 */
export interface TemplateVariableSpec {
  /** 백엔드 치환 토큰 — 정확한 소문자·공백 없음 형식이어야 한다 */
  token: string
  /** 변수 삽입 버튼에 표시할 한국어 라벨 */
  insertLabel: string
  /** 변수가 이슈 생성 시 어떤 값으로 치환되는지 설명 */
  description: string
}

export const TEMPLATE_VARIABLES: readonly TemplateVariableSpec[] = [
  {
    token: '{{author}}',
    insertLabel: '작성자',
    description: '이슈 작성자 이름',
  },
  {
    token: '{{date}}',
    insertLabel: '일자',
    description: '이슈 생성 일자 (YYYY-MM-DD)',
  },
  {
    token: '{{project}}',
    insertLabel: '프로젝트',
    description: '이슈가 속한 프로젝트 키',
  },
] as const
