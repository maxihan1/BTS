// Cmd+K 명령 팔레트 — cmdk 기반 슬래시 명령(goto/search/issue) 실행 UI (FR-UX-04 Task-2)
import { useState, type JSX, type KeyboardEvent } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Command as CommandPrimitive } from 'cmdk'
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
  unknownCommand: (name: string) => `알 수 없는 명령입니다: /${name}`,
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

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cmd+K 명령 팔레트 컴포넌트.
 *
 * cmdk `Command.Dialog`(Radix Dialog 래핑 — focus trap/Esc 닫기/포커스 복원 기본 제공) 위에
 * `Command.Input`/`Command.List`/`Command.Item`으로 구성한다.
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
    if (parsed.kind === 'goto') {
      void navigate({ to: '/issues/$key', params: { key: parsed.issueKey } })
      handleClose()
    } else if (parsed.kind === 'search') {
      void navigate({ to: '/search', search: { q: parsed.query } })
      handleClose()
    } else if (parsed.kind === 'issue') {
      void navigate({ to: '/issues/new', search: { summary: parsed.summary } })
      handleClose()
    }
    // unknown/incomplete → 안내 문구만 유지, 팔레트는 열린 채로 둔다(FR5)
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
      <CommandPrimitive.Input
        value={inputValue}
        onValueChange={setInputValue}
        onKeyDown={handleInputKeyDown}
        placeholder={commandPaletteStrings.inputPlaceholder}
        className="w-full border-b border-border bg-transparent px-4 py-3 text-sm outline-none placeholder:text-muted-foreground"
      />
      <CommandPrimitive.List className="max-h-80 overflow-y-auto p-2">
        {showQuickLinks && (
          <>
            <CommandPrimitive.Group
              heading={commandPaletteStrings.quickLinksHeading}
              className="[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-muted-foreground"
            >
              {QUICK_LINKS.map((link) => (
                <CommandPrimitive.Item
                  key={link.to}
                  value={link.to}
                  onSelect={() => handleQuickLinkSelect(link.to)}
                  className="flex cursor-pointer select-none items-center rounded-md px-2 py-2 text-sm outline-none data-[selected=true]:bg-accent data-[selected=true]:text-accent-foreground"
                >
                  {link.label}
                </CommandPrimitive.Item>
              ))}
            </CommandPrimitive.Group>
            <CommandPrimitive.Group
              heading={commandPaletteStrings.commandsHeading}
              className="[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-muted-foreground"
            >
              {COMMANDS.map((cmd) => (
                <CommandPrimitive.Item
                  key={cmd.name}
                  value={cmd.name}
                  onSelect={() => handleCommandHintSelect(cmd.prefix)}
                  className="flex cursor-pointer select-none items-center gap-2 rounded-md px-2 py-2 text-sm outline-none data-[selected=true]:bg-accent data-[selected=true]:text-accent-foreground"
                >
                  <span className="font-mono text-xs text-muted-foreground">{cmd.prefix.trim()}</span>
                  <span>{cmd.description}</span>
                </CommandPrimitive.Item>
              ))}
            </CommandPrimitive.Group>
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
      </CommandPrimitive.List>
    </CommandPrimitive.Dialog>
  )
}
