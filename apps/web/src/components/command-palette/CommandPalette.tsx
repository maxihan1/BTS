// Cmd+K 명령 팔레트 — cmdk 기반 슬래시 명령(goto/search/issue) 실행 UI (FR-UX-04 Task-2)
/* eslint-disable react-refresh/only-export-components -- runCommand 헬퍼를 컴포넌트와 같은 파일에 배치(응집도 우선, NodeMappingSection.tsx 선례) */
import { useEffect, useState, type JSX, type KeyboardEvent } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Command as CommandPrimitive } from 'cmdk'
import { CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/ui/command'
import { buildTextQuery } from '@/lib/aql-text-query'
import { parseCommand, COMMANDS, QUICK_LINKS, type ParsedCommand } from './commands'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — UI 문구 (컴포넌트 내 *Strings 관례, C5)
// ─────────────────────────────────────────────────────────────────────────────

/** parseCommand의 incomplete 판별 유니온에서 도출한 명령 이름 타입 — commands.ts 재수정 없이 재사용 */
type IncompleteCommandName = Extract<ParsedCommand, { kind: 'incomplete' }>['name']

/** goto/search/issue 명령별 사용법 힌트 — 인자 누락(E2) 시 안내 */
const COMMAND_USAGE_HINTS: Record<IncompleteCommandName, string> = {
  goto: '/goto <이슈 키> 형식으로 입력하세요. 예: /goto PROJ-12',
  search: '/search <검색어> 형식으로 입력하세요. 예: /search 로그인 버그',
  issue: '/issue <제목> 형식으로 입력하세요. 예: /issue 결제 실패 조사',
}

const commandPaletteStrings = {
  dialogLabel: '명령 팔레트',
  inputPlaceholder: '검색하거나 슬래시 명령(/goto, /search, /issue)을 입력하세요',
  quickLinksHeading: '바로가기',
  commandsHeading: '명령어',
  unknownCommand: (name: string) => `알 수 없는 명령입니다. /${name}`,
  invalidIssueKey: '이슈 키 형식이 올바르지 않습니다. 예: PROJ-12',
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** CommandPalette props */
interface CommandPaletteProps {
  /** 팔레트 열림 여부 */
  readonly open: boolean
  /** 열림 상태 변경 콜백 — Esc/오버레이 클릭/명령 실행 후 호출 */
  readonly onOpenChange: (open: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 순수 함수 (C3)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 파싱된 명령에 대응하는 안내 문구를 계산한다.
 * 정상 실행 가능한 kind(goto/search/issue) 및 not-command는 null(안내 없음).
 *
 * @param parsed parseCommand의 판별 유니온 결과
 * @returns 안내 문구, 없으면 null
 */
function buildGuidanceMessage(parsed: ParsedCommand): string | null {
  if (parsed.kind === 'unknown') {
    return commandPaletteStrings.unknownCommand(parsed.name)
  }
  if (parsed.kind === 'incomplete') {
    return parsed.reason === 'invalid-key'
      ? commandPaletteStrings.invalidIssueKey
      : COMMAND_USAGE_HINTS[parsed.name]
  }
  return null
}

/**
 * 파싱된 명령의 라우팅 부수효과를 실행하는 순수 dispatch 헬퍼(C3).
 *
 * goto/search/issue만 navigate를 호출한다. unknown/incomplete/not-command는
 * 실행할 라우트가 없으므로 아무 것도 하지 않는다(FR5 — 안내 문구만 표시, 라우팅 없음).
 * 컴포넌트 렌더 로직과 분리해 테스트하기 쉽게 만든다.
 *
 * @param parsed parseCommand의 판별 유니온 결과
 * @param navigate useNavigate()가 반환하는 TanStack Router navigate 함수
 */
export function runCommand(parsed: ParsedCommand, navigate: ReturnType<typeof useNavigate>): void {
  if (parsed.kind === 'goto') {
    void navigate({ to: '/issues/$key', params: { key: parsed.issueKey } })
  } else if (parsed.kind === 'search') {
    // ★자유 텍스트를 그대로 q 로 보내면 백엔드 AqlParser 가 SEARCH_SYNTAX_ERROR 를 낸다
    // (선재 결함, ADR D-3). text ~ "…" 로 감싸야 유효한 AQL 이다.
    const aql = buildTextQuery(parsed.query)
    // null 은 parseCommand 가 이미 걸러 도달하지 않는다(arg 는 trim 후 빈 문자열이면
    // incomplete). 타입 좁히기용 가드다 — `!` 단언 금지(DEVELOPMENT.md §1).
    if (aql !== null) {
      void navigate({ to: '/search', search: { q: aql } })
    }
  } else if (parsed.kind === 'issue') {
    void navigate({ to: '/issues/new', search: { summary: parsed.summary } })
  }
}

/** runCommand가 실제로 navigate를 실행하는(=팔레트를 닫아야 하는) kind 목록 */
const EXECUTABLE_KINDS: ReadonlySet<ParsedCommand['kind']> = new Set(['goto', 'search', 'issue'])

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cmd+K 명령 팔레트 컴포넌트.
 *
 * cmdk `Command.Dialog`(Radix Dialog 래핑 — focus trap/Esc 닫기/포커스 복원 기본 제공) 위에
 * shadcn 래퍼 `components/ui/command.tsx`의 `CommandInput`/`CommandList`/`CommandGroup`/
 * `CommandItem`으로 구성한다. `Command.Dialog`만 cmdk 프리미티브를 직접 쓰는데,
 * 래퍼가 `CommandDialog`를 "소비처 몫"이라며 의도적으로 제외했기 때문이다(래퍼 파일 L1).
 * `CommandEmpty`는 쓰지 않는다 — `shouldFilter={false}`라 cmdk가 `filtered.count`를
 * 등록 아이템 수로 두므로 0건 판정이 영영 성립하지 않는다(도달 불가 코드).
 *
 * - 입력이 비어 있으면 QUICK_LINKS(정적 바로가기) + COMMANDS(명령 힌트)를 렌더한다.
 * - 명령 힌트 선택 시 라우팅하지 않고 입력창에 prefix를 채운다(S2 보강).
 * - `/goto`·`/search`·`/issue` + Enter로 parseCommand 결과에 따라 navigate한다.
 * - 알 수 없는/불완전 명령은 안내 문구를 표시하고 실행하지 않는다(FR5).
 * - 명령 실행/정적 바로가기 선택 후 팔레트를 닫고 입력을 초기화한다(FR6).
 * - IME 조합 중 Enter는 실행하지 않는다(NFR4).
 *
 * @param open 팔레트 열림 여부
 * @param onOpenChange 열림 상태 변경 콜백
 */
export function CommandPalette({ open, onOpenChange }: CommandPaletteProps): JSX.Element {
  const navigate = useNavigate()
  const [inputValue, setInputValue] = useState('')

  // 외부(useCommandPalette)에서 open이 false로 바뀌는 경우(Cmd+K 토글 재오픈)
  // Radix Command.Dialog는 onOpenChange를 호출하지 않으므로 handleClose가 실행되지
  // 않는다 — open prop 자체를 감시해 입력을 초기화한다(codereview CONCERN-1)
  useEffect(() => {
    if (!open) {
      setInputValue('')
    }
  }, [open])

  const parsed = parseCommand(inputValue)
  const showQuickLinks = parsed.kind === 'not-command' && inputValue === ''
  const guidanceMessage = buildGuidanceMessage(parsed)

  /** 팔레트를 닫고 입력을 초기화한다(FR6) */
  function handleClose(): void {
    setInputValue('')
    onOpenChange(false)
  }

  /** Radix Dialog의 onOpenChange — Esc/오버레이 클릭으로 닫힐 때도 입력을 초기화한다 */
  function handleDialogOpenChange(next: boolean): void {
    if (next) {
      onOpenChange(true)
    } else {
      handleClose()
    }
  }

  /** 명령 힌트 선택 — 라우팅하지 않고 입력창에 prefix를 채운다(S2 보강) */
  function handleCommandHintSelect(prefix: string): void {
    setInputValue(prefix)
  }

  /** 정적 바로가기 선택 — 해당 라우트로 이동 후 닫는다 */
  function handleQuickLinkSelect(to: string): void {
    void navigate({ to })
    handleClose()
  }

  /** 입력창 Enter 키 핸들러 — 슬래시 명령 실행(C3, NFR4) */
  function handleInputKeyDown(e: KeyboardEvent<HTMLInputElement>): void {
    if (e.key !== 'Enter') return
    if (e.nativeEvent.isComposing) return // IME 조합 중 확정 Enter는 실행하지 않는다(NFR4)

    if (parsed.kind === 'not-command') return // cmdk 기본 목록 선택(Enter)에 위임

    e.preventDefault()
    runCommand(parsed, navigate)
    // unknown/incomplete → 안내 문구만 유지, 팔레트는 열린 채로 둔다(FR5)
    if (EXECUTABLE_KINDS.has(parsed.kind)) {
      handleClose()
    }
  }

  return (
    <CommandPrimitive.Dialog
      open={open}
      onOpenChange={handleDialogOpenChange}
      label={commandPaletteStrings.dialogLabel}
      shouldFilter={false}
      overlayClassName="fixed inset-0 z-50 bg-black/40"
      contentClassName="fixed left-1/2 top-[15vh] z-50 w-full max-w-lg -translate-x-1/2 overflow-hidden rounded-xl border border-border bg-popover shadow-xl"
    >
      <CommandInput
        value={inputValue}
        onValueChange={setInputValue}
        onKeyDown={handleInputKeyDown}
        placeholder={commandPaletteStrings.inputPlaceholder}
      />
      {/* p-2 만 넘긴다 — max-h/overflow 는 래퍼 기본값(max-h-[300px])이 담당한다 */}
      <CommandList className="p-2">
        {showQuickLinks && (
          <>
            <CommandGroup heading={commandPaletteStrings.quickLinksHeading}>
              {QUICK_LINKS.map((link) => (
                <CommandItem
                  key={link.to}
                  value={link.to}
                  onSelect={() => handleQuickLinkSelect(link.to)}
                >
                  {link.label}
                </CommandItem>
              ))}
            </CommandGroup>
            <CommandGroup heading={commandPaletteStrings.commandsHeading}>
              {COMMANDS.map((cmd) => (
                <CommandItem
                  key={cmd.name}
                  value={cmd.name}
                  onSelect={() => handleCommandHintSelect(cmd.prefix)}
                >
                  <span className="font-mono text-xs text-muted-foreground">{cmd.prefix.trim()}</span>
                  <span>{cmd.description}</span>
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        )}
        {guidanceMessage !== null && (
          <p
            role="alert"
            className="px-4 py-3 text-sm text-muted-foreground"
          >
            {guidanceMessage}
          </p>
        )}
      </CommandList>
    </CommandPrimitive.Dialog>
  )
}
