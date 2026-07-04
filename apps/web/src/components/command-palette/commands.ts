// 명령 팔레트 슬래시 명령 파서 + 레지스트리 — FR-UX-04 Task-1
type CommandName = 'goto' | 'search' | 'issue'

export type ParsedCommand =
  | { kind: 'goto'; issueKey: string }
  | { kind: 'search'; query: string }
  | { kind: 'issue'; summary: string }
  | { kind: 'unknown'; name: string }
  | { kind: 'incomplete'; name: CommandName; reason?: 'invalid-key' }
  | { kind: 'not-command' }

interface CommandMeta {
  name: CommandName
  prefix: string
  description: string
}

interface QuickLink {
  label: string
  to: string
}

export const COMMANDS: readonly CommandMeta[] = [
  { name: 'goto', prefix: '/goto ', description: '이슈 키로 이동' },
  { name: 'search', prefix: '/search ', description: '검색 결과로 이동' },
  { name: 'issue', prefix: '/issue ', description: '새 이슈 폼으로 이동' },
]

export const QUICK_LINKS: readonly QuickLink[] = [
  { label: '내 이슈', to: '/issues' },
  { label: '검색', to: '/search' },
  { label: '대시보드', to: '/dashboards' },
  { label: '받은 편지함', to: '/inbox' },
]

function isCommandName(name: string): name is CommandName {
  return name === 'goto' || name === 'search' || name === 'issue'
}

export function parseCommand(input: string): ParsedCommand {
  if (!input.startsWith('/')) {
    return { kind: 'not-command' }
  }

  const body = input.slice(1)
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
    if (!/^[A-Z][A-Z0-9]*-\d+$/.test(normalizedKey)) {
      return { kind: 'incomplete', name, reason: 'invalid-key' }
    }
    return { kind: 'goto', issueKey: normalizedKey }
  }

  if (name === 'search') {
    return { kind: 'search', query: arg }
  }

  return { kind: 'issue', summary: arg }
}
