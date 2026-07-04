// 명령 팔레트 슬래시 명령 파서 + 레지스트리 — FR-UX-04 Task-1
/** 슬래시 명령 접두사 문자 */
const SLASH_PREFIX = '/'

/** 이슈 키 형식 — 대문자로 시작, 대문자/숫자 뒤 하이픈 + 숫자 (예: PROJ-12) */
const ISSUE_KEY_PATTERN = /^[A-Z][A-Z0-9]*-\d+$/

/** 명령 팔레트가 지원하는 명령 이름 */
type CommandName = 'goto' | 'search' | 'issue'

/** 지원 명령 이름 목록 — COMMANDS 노출 순서와 동일 */
const COMMAND_NAMES: readonly CommandName[] = ['goto', 'search', 'issue']

/**
 * 슬래시 명령 파싱 결과 — 판별 유니온.
 *
 * - `goto`: 이슈 키로 이동
 * - `search`: 검색 질의로 이동
 * - `issue`: 새 이슈 폼으로 이동(제목 프리필)
 * - `unknown`: 등록되지 않은 명령
 * - `incomplete`: 인자가 없거나(reason 없음) 형식이 틀린 경우(reason 명시)
 * - `not-command`: `/`로 시작하지 않는 일반 입력
 */
export type ParsedCommand =
  | { kind: 'goto'; issueKey: string }
  | { kind: 'search'; query: string }
  | { kind: 'issue'; summary: string }
  | { kind: 'unknown'; name: string }
  | { kind: 'incomplete'; name: CommandName; reason?: 'invalid-key' }
  | { kind: 'not-command' }

/** 명령 팔레트 명령 힌트 메타 — 빈 입력 시 힌트 목록에 노출 */
interface CommandMeta {
  /** 명령 이름 (goto/search/issue) */
  name: CommandName
  /** 명령 힌트 선택 시 입력창에 채울 접두사 (예: '/goto ') */
  prefix: string
  /** 사용자에게 보여줄 설명 문구 */
  description: string
}

/** 명령 팔레트 정적 바로가기 항목 */
interface QuickLink {
  /** 사용자에게 보여줄 라벨 */
  label: string
  /** 이동할 라우트 경로 */
  to: string
}

/** goto/search/issue 3종 명령 힌트 메타 (빈 입력 시 노출되는 명령 힌트 목록) */
export const COMMANDS: readonly CommandMeta[] = [
  { name: 'goto', prefix: '/goto ', description: '이슈 키로 이동' },
  { name: 'search', prefix: '/search ', description: '검색 결과로 이동' },
  { name: 'issue', prefix: '/issue ', description: '새 이슈 폼으로 이동' },
]

/** 빈 입력 시 노출하는 정적 바로가기 4종 (내 이슈/검색/대시보드/받은 편지함) */
export const QUICK_LINKS: readonly QuickLink[] = [
  { label: '내 이슈', to: '/issues' },
  { label: '검색', to: '/search' },
  { label: '대시보드', to: '/dashboards' },
  { label: '받은 편지함', to: '/inbox' },
]

/** 입력 문자열이 등록된 명령 이름(goto/search/issue)인지 판별하는 타입 가드 */
function isCommandName(name: string): name is CommandName {
  return COMMAND_NAMES.includes(name as CommandName)
}

/**
 * 슬래시 명령 문자열을 판별 유니온(`ParsedCommand`)으로 변환하는 순수 함수.
 *
 * `/`로 시작하지 않으면 `not-command`, 첫 토큰이 등록된 명령명이 아니면
 * `unknown`, 인자가 비어 있으면 `incomplete`를 반환한다. `goto`는 인자를
 * 대문자로 정규화한 뒤 이슈 키 형식을 검증하고, 형식이 아니면
 * `incomplete`(`reason: 'invalid-key'`)를 반환한다.
 * 라우팅/실행 등 부수효과는 다루지 않는다(호출부 책임, C3).
 *
 * @param input 팔레트 입력창의 원본 문자열
 * @returns 파싱된 명령 판별 유니온
 */
export function parseCommand(input: string): ParsedCommand {
  if (!input.startsWith(SLASH_PREFIX)) {
    return { kind: 'not-command' }
  }

  const body = input.slice(SLASH_PREFIX.length)
  const spaceIndex = body.indexOf(' ')
  const name = spaceIndex === -1 ? body : body.slice(0, spaceIndex)
  const arg = spaceIndex === -1 ? '' : body.slice(spaceIndex + 1).trim()

  if (!isCommandName(name)) {
    return { kind: 'unknown', name }
  }

  if (arg === '') {
    return { kind: 'incomplete', name }
  }

  if (name === 'goto') {
    const normalizedKey = arg.toUpperCase()
    if (!ISSUE_KEY_PATTERN.test(normalizedKey)) {
      return { kind: 'incomplete', name, reason: 'invalid-key' }
    }
    return { kind: 'goto', issueKey: normalizedKey }
  }

  if (name === 'search') {
    return { kind: 'search', query: arg }
  }

  return { kind: 'issue', summary: arg }
}
