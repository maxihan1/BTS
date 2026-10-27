// 한국어 조사 「으로/로」를 앞말의 받침으로 고르는 헬퍼 — 보드 변경 문구 · SSO 로그인 버튼이 같이 쓴다

/** 한글 음절 블록 시작 코드포인트('가') — 받침 유무 계산의 기준점 */
const HANGUL_SYLLABLE_START = 0xac00
/** 한글 음절 블록 끝 코드포인트('힣') */
const HANGUL_SYLLABLE_END = 0xd7a3
/** 한글 음절 하나가 가질 수 있는 종성(받침) 조합 가짓수 — 종성 없음(1가지) 포함 28가지 */
const HANGUL_JONGSEONG_COUNT = 28

/** 숫자 한 글자(0~9)의 한글 발음 — 우선순위처럼 숫자로 끝나는 단어의 받침 판정에 사용(예: '3' → '삼') */
const DIGIT_KOREAN_READING: Readonly<Record<string, string>> = {
  '0': '영',
  '1': '일',
  '2': '이',
  '3': '삼',
  '4': '사',
  '5': '오',
  '6': '육',
  '7': '칠',
  '8': '팔',
  '9': '구',
}

/**
 * 단어 끝에 붙일 한국어 조사("으로" 또는 "로")를 마지막 글자의 받침 유무로 결정한다.
 *
 * 한국어는 받침(종성)이 있는 글자 뒤엔 "으로", 없는 글자 뒤엔 "로"를 쓴다
 * (예: "박밥으로"는 자연스럽지만 "박밥로"는 어색하다). 이전엔 describeAssigneeFieldChange가
 * 항상 "(으)로", describePriorityFieldChange·describeEpicFieldChange가 항상 bare "로"를
 * 하드코딩해 조사가 불일치했다(리뷰 S1) — 이 헬퍼로 담당자 이름·에픽 키·우선순위 숫자 표기를 통일한다.
 * `KanbanBoard.tsx` 의 비-export 함수였고, S21 에서 SSO 버튼(「QA Hub 통합계정으로 로그인」)이 같이 쓰려고 여기로 옮겼다.
 *
 * - 마지막 글자가 숫자(0~9)면 그 숫자의 한글 발음(DIGIT_KOREAN_READING)의 마지막 글자로 판정한다
 *   (예: 우선순위 "1" → "일"의 받침 ㄹ 있음 → "으로").
 * - 마지막 글자가 한글 음절(가~힣)이면 유니코드 코드포인트로 받침 유무를 계산한다
 *   ((코드 - 0xAC00) % 28 !== 0 이면 받침 있음 — 종성 없는 음절이 각 초성×중성 조합의 첫 번째다).
 * - 그 외(빈 문자열·한글도 숫자도 아닌 문자)는 안전한 기본값 "로"를 반환한다.
 *
 * @param word 조사를 붙일 대상 단어(담당자 이름·에픽 키·우선순위 숫자 등)
 * @returns '으로' 또는 '로'
 */
export function josaEuro(word: string): string {
  const lastChar = word.at(-1)
  if (lastChar === undefined) return '로'

  const digitReading = DIGIT_KOREAN_READING[lastChar]
  const charToCheck = digitReading !== undefined ? digitReading.at(-1) : lastChar
  const code = charToCheck?.codePointAt(0)
  if (code === undefined || code < HANGUL_SYLLABLE_START || code > HANGUL_SYLLABLE_END) return '로'

  const hasBatchim = (code - HANGUL_SYLLABLE_START) % HANGUL_JONGSEONG_COUNT !== 0
  return hasBatchim ? '으로' : '로'
}
