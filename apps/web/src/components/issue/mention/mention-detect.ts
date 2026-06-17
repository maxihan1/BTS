// 멘션 트리거 감지 및 텍스트 splice 순수 함수 — FR-MN-02 Task 1

/** '@' + 뒤 공백 1개 — splice 시 삽입되는 접두 문자 수 */
const MENTION_PREFIX_LEN = 2

/** caret 위치에서 활성 멘션(@쿼리)을 역방향 스캔으로 감지한 결과 */
export interface ActiveMentionResult {
  active: boolean
  query?: string
  start?: number
  end?: number
}

/** spliceMention 치환 결과 — 다음 텍스트와 새 caret 위치 */
export interface SpliceMentionResult {
  next: string
  caret: number
}

/**
 * 주어진 문자가 멘션 경계(공백·개행·문자열 시작)인지 판별한다.
 *
 * `@` 앞에 이 문자가 있어야 멘션 트리거로 인정된다.
 * `undefined`는 문자열 인덱스 0 이전, 즉 문자열 시작을 의미한다.
 *
 * @param ch - 검사할 문자 (undefined = 문자열 시작 이전)
 */
export function isBoundaryChar(ch: string | undefined): boolean {
  if (ch === undefined) return true
  return /\s/.test(ch)
}

/**
 * textarea/input의 text와 caret 위치를 받아 활성 멘션(@쿼리)을 감지한다.
 *
 * 역방향으로 caret-1부터 스캔:
 * - 공백·개행을 만나면 즉시 `{ active: false }` 반환 (멘션 구간 종료)
 * - `@`를 만나면 앞 문자가 경계인지 확인:
 *   - 경계이면 `{ active: true, query, start, end }` 반환
 *   - 경계가 아니면 `{ active: false }` 반환 (이메일 등)
 *
 * @param text - 전체 텍스트
 * @param caret - 현재 커서 위치 (텍스트 인덱스 기준)
 */
export function detectActiveMention(text: string, caret: number): ActiveMentionResult {
  for (let i = caret - 1; i >= 0; i--) {
    const ch = text[i]
    if (ch === undefined) break

    if (/\s/.test(ch)) {
      return { active: false }
    }

    if (ch === '@') {
      const preceding = i > 0 ? text[i - 1] : undefined
      if (!isBoundaryChar(preceding)) {
        return { active: false }
      }
      const query = text.slice(i + 1, caret)
      return { active: true, query, start: i, end: caret }
    }
  }

  return { active: false }
}

/**
 * text의 start..end 구간(@ 포함)을 `@<username> `(뒤 공백 1개)으로 치환한다.
 *
 * @param text - 원본 텍스트
 * @param start - 치환 시작 인덱스 (`@`의 위치)
 * @param end - 치환 끝 인덱스 (caret 위치)
 * @param username - 삽입할 사용자명
 */
export function spliceMention(
  text: string,
  start: number,
  end: number,
  username: string,
): SpliceMentionResult {
  const next = text.slice(0, start) + '@' + username + ' ' + text.slice(end)
  const caret = start + username.length + MENTION_PREFIX_LEN
  return { next, caret }
}
