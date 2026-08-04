// Cmd+K 명령 팔레트 — 슬래시 명령(goto/search/issue) + 이슈키·자유텍스트 실체 검색 (FR-UX-04 Task-2 · FR-UX-12 F4)
/* eslint-disable react-refresh/only-export-components -- runCommand 헬퍼를 컴포넌트와 같은 파일에 배치(응집도 우선, NodeMappingSection.tsx 선례) */
import { useEffect, useState, type JSX, type KeyboardEvent } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Command as CommandPrimitive } from 'cmdk'
import {
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from '@/components/ui/command'
import { buildTextQuery } from '@/lib/aql-text-query'
import { parseCommand, COMMANDS, QUICK_LINKS, type ParsedCommand } from './commands'
import { resolveNonCommandInput, type PaletteInput } from './palette-input'
import { usePaletteSearch, type PaletteResult, type PaletteSearchResult } from './use-palette-search'

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
  // ★그룹 이름을 「이슈」/「검색 결과」로 나눈다. 「검색」 단독은 쓰지 않는다 —
  // e2e 가 팔레트 안 option `'검색'`(QUICK_LINKS 2번째)을 전체 일치로 잡는 즉사 계약이다.
  issueHeading: '이슈',
  resultsHeading: '검색 결과',
  searching: '검색 중…',
  noResults: '결과가 없습니다.',
  needsProject: '프로젝트를 먼저 선택하세요.',
  pickProject: '프로젝트 선택하러 가기',
  // ★문구 출처는 `ActiveProjectGate`(같은 사실을 다루는 정본). 「먼저 선택하세요」와 **반드시**
  // 달라야 한다 — 프로젝트가 0개인 것과 목록을 못 불러온 것은 서로 다른 사실이고,
  // 후자의 탈출구는 프로젝트 목록이 아니라 재시도다.
  projectLoadFailed: '프로젝트 목록을 불러올 수 없습니다.',
  retryProjects: '다시 시도',
  allResults: '모든 결과 보기',
  allResultsWithCount: (total: number) => `모든 결과 보기 (${total}건)`,
  foundCount: (count: number) => `${count}건 찾음`,
  unknownCommand: (name: string) => `알 수 없는 명령입니다. /${name}`,
  invalidIssueKey: '이슈 키 형식이 올바르지 않습니다. 예: PROJ-12',
}

/** 안내·진행 문구 공통 스타일 — 4종 안내가 같은 옷을 입어야 상태 전환이 튀지 않는다 */
const NOTICE_CLASS = 'px-4 py-3 text-sm text-muted-foreground'

/**
 * 결과·액션 항목 공통 클래스.
 *
 * 데스크톱 밀도(래퍼 `CommandItem` 기본 `py-1.5` ≈ 30px)는 그대로 두고 **터치 입력에서만**
 * 44px를 채운다. `DESIGN.md §터치 타깃`이 모바일 최소 44×44를 규정하므로 래퍼 기본값을
 * 그대로 쓰면 정본 위반이다. `pointer: coarse`는 정밀 포인터가 없는 입력에서만 참이라
 * 마우스 사용자의 목록 밀도를 희생하지 않는다(`EditableCell.CELL_OPTION_CLASS` 선례).
 */
const RESULT_ITEM_CLASS = 'pointer-coarse:min-h-[44px]'

/** cmdk 항목 value — 이슈 키와 겹치지 않도록 콜론 접두사를 쓴다(이슈 키에는 콜론이 없다) */
const ALL_RESULTS_ITEM_VALUE = 'palette:all-results'
const PROJECT_PICKER_ITEM_VALUE = 'palette:pick-project'
const RETRY_PROJECTS_ITEM_VALUE = 'palette:retry-projects'

/**
 * 슬래시 명령을 입력하는 동안 검색 훅에 넘기는 고정 입력.
 *
 * `parseCommand`가 `not-command`를 돌려줄 때만 2차 판별을 부른다(FR2 호출 순서 계약).
 * 먼저 부르면 `/goto ATLAS-1`이 자유 텍스트로 새어 슬래시 명령이 죽는다.
 */
const NO_SEARCH_INPUT: PaletteInput = { kind: 'empty' }

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

/** 결과 영역이 부모에게서 받는 선택 콜백 3종 */
interface PaletteSelectHandlers {
  /** 결과 한 줄 선택 — 이슈 상세로 이동 */
  readonly onSelectIssue: (issueKey: string) => void
  /** 「모든 결과 보기」 선택 — 검색 페이지로 같은 AQL 전달 */
  readonly onSelectAllResults: (aqlQuery: string) => void
  /** 「프로젝트 선택하러 가기」 선택 — 프로젝트 목록으로 이동 */
  readonly onSelectProjectPicker: () => void
}

/** PaletteResults props — 훅 반환값 한 덩어리 + 선택 콜백 3종 */
interface PaletteResultsProps extends PaletteSelectHandlers {
  /** usePaletteSearch 반환값 그대로 */
  readonly search: PaletteSearchResult
  /** 정확일치와 겹치는 항목을 걷어낸 유사일치 목록 */
  readonly similarResults: readonly PaletteResult[]
}

/** PaletteSearchSection props */
interface PaletteSearchSectionProps extends PaletteSelectHandlers {
  /** palette-input 의 판별 결과 */
  readonly input: PaletteInput
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
 * 이슈키 정확일치와 겹치는 검색 결과를 걷어낸다.
 *
 * 같은 이슈가 「이슈」와 「검색 결과」에 두 줄로 나오면 그룹을 나눠 얻으려던
 * "이건 정확히 그거야" 신호가 오히려 흐려진다.
 *
 * @param issueHit 이슈키 정확일치 결과(없으면 null)
 * @param results 자유 텍스트 검색 결과
 * @returns 정확일치를 제외한 유사일치 목록
 */
function dedupeAgainstIssueHit(
  issueHit: PaletteResult | null,
  results: readonly PaletteResult[],
): readonly PaletteResult[] {
  if (issueHit === null) return results
  return results.filter((result) => result.key !== issueHit.key)
}

/**
 * 결과 개수를 화면낭독기에 알릴 문구를 만든다.
 *
 * 안내 3종(프로젝트 미해소·검색 실패·0건)은 `role="alert"`가 이미 읽으므로 여기서는
 * 비운다 — 같은 사실이 두 번 읽히면 방해가 된다. 진행 중에도 비운다(확정 전 숫자는 거짓말이다).
 *
 * @param search usePaletteSearch 반환값
 * @param visibleCount 목록에 실제로 그려진 결과 수
 * @returns 낭독 문구. 알릴 것이 없으면 빈 문자열
 */
function buildResultAnnouncement(search: PaletteSearchResult, visibleCount: number): string {
  if (search.needsProject || search.errorMessage !== null) return ''
  if (search.isSearching || search.fullSearchQuery === null) return ''
  if (visibleCount === 0) return ''
  return commandPaletteStrings.foundCount(visibleCount)
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
// 하위 컴포넌트 — 결과 영역 (같은 파일 유지: 상태를 prop으로 길게 넘기지 않기 위함)
// ─────────────────────────────────────────────────────────────────────────────

/** 결과 한 줄 — 키(고정폭) + 요약(1줄 말줄임) */
function PaletteResultItem({
  result,
  onSelect,
}: {
  readonly result: PaletteResult
  readonly onSelect: (issueKey: string) => void
}): JSX.Element {
  return (
    <CommandItem
      value={result.key}
      className={RESULT_ITEM_CLASS}
      onSelect={() => {
        onSelect(result.key)
      }}
    >
      <span className="shrink-0 font-mono text-xs text-muted-foreground">{result.key}</span>
      {/* truncate 는 overflow:hidden 을 포함해 flex 아이템의 자동 최소너비를 0으로 만든다 —
          375px 폭에서 긴 요약이 팔레트를 가로로 밀어내지 않는 근거다 */}
      <span className="truncate">{result.summary}</span>
    </CommandItem>
  )
}

/**
 * 검색 결과 영역 — 「이슈」(정확일치) / 「검색 결과」(유사일치) / 안내 4종 / 탈출구 2종.
 *
 * **그룹을 둘로 나누는 이유.** 정확일치와 유사일치가 한 그룹에 섞이면 "이건 정확히 그거야"
 * 신호가 사라진다 (Jira Cloud 공식 *"Labels separate different types of results"*).
 * 보이는 순서 = 확신의 순서 — 정확일치 → 유사일치 → 탈출구.
 *
 * **`CommandEmpty`를 쓰지 않는 이유.** `CommandEmpty`는 `filtered.count === 0`일 때만
 * 렌더하는데 이 팔레트는 `shouldFilter={false}`라 cmdk가 `filtered.count`를 등록 아이템
 * 수로 둔다. 「모든 결과 보기」가 늘 있으므로 0건이어도 count는 1이라 영영 발동하지 않는다.
 */
function PaletteResults({
  search,
  similarResults,
  onSelectIssue,
  onSelectAllResults,
  onSelectProjectPicker,
}: PaletteResultsProps): JSX.Element {
  const {
    issueHit,
    totalCount,
    isSearching,
    needsProject,
    isResolvingProject,
    projectError,
    errorMessage,
    fullSearchQuery,
  } = search

  /**
   * 프로젝트 쪽 사정으로 자유 텍스트 검색이 **성립하지 않는** 상태 3종.
   *
   * ★셋을 함께 묶는 이유. 어느 쪽이든 요청이 0건이라 "결과"에 대해 말할 자격이 없다.
   * 하나라도 빠뜨리면 그 갈래에서 0건 안내나 탈출구가 새어나온다(코드리뷰 C-1·C-2).
   */
  const searchBlocked = needsProject || isResolvingProject || projectError !== null

  // 디바운스가 아직 안 끝난 구간(질의 미확정)도 "검색 중"으로 본다. 그러지 않으면 250ms 동안
  // 「결과가 없습니다.」가 번쩍인 뒤 결과가 들어와 사용자가 같은 자리를 두 번 읽게 된다.
  // ★프로젝트 해소 중도 같은 자리에 넣는다 — 사용자 입장에서 "치는 중이고 아직 답이 없다"는
  // 같은 상황이고, 여기서 빠지면 요청 0건인 채로 「결과가 없습니다.」가 켜진다.
  const showSearching =
    !needsProject &&
    projectError === null &&
    (isSearching || isResolvingProject || fullSearchQuery === null)
  const hasResult = issueHit !== null || similarResults.length > 0
  const showEmptyNotice = !searchBlocked && errorMessage === null && !showSearching && !hasResult
  /**
   * 「모든 결과 보기」 노출 조건.
   *
   * - 프로젝트가 성립하지 않으면(`searchBlocked`) 검색 페이지도 **같은 이유로** 막히므로
   *   탈출구가 되지 못한다. 해소 중에 먼저 띄우면 아래 선택 가로채기까지 겹친다.
   * - ★**결과보다 먼저 마운트되면 안 된다.** `fullSearchQuery` 는 디바운스가 끝나는 즉시
   *   확정되므로, 그것만 보고 렌더하면 응답이 오기 전 이 항목이 **혼자** 마운트된다.
   *   cmdk 는 항목 등록 시 `n.current.value || W()` 로 **선택이 비어 있을 때만** 첫 항목을
   *   잡으므로(dist 실측), 한 번 탈출구가 선택을 차지하면 뒤늦게 붙는 결과는 선택을 되찾지
   *   못한다. 그 상태에서 Enter 는 첫 결과가 아니라 검색 페이지로 가고(스펙 S4 위반),
   *   `loop` 미지정이라 마지막 항목에서 `ArrowDown` 이 무동작이라 **결과로 내려갈 수도 없다**
   *   (브라우저 실측). 결과와 **같은 커밋**에 함께 붙여 첫 결과가 선택되게 한다.
   * - `hasResult ||` 를 앞에 두는 이유. 결과가 이미 있으면 백그라운드 재조회 중에도 계속
   *   보여 깜빡임을 막는다. 0건·검색 실패는 `!isSearching` 으로 들어와 **탈출구가 유지된다**
   *   (스펙 E11 — 전체 페이지엔 더 있을 수 있다).
   */
  const showAllResults = !searchBlocked && fullSearchQuery !== null && (hasResult || !isSearching)

  return (
    <>
      {issueHit !== null && (
        <CommandGroup heading={commandPaletteStrings.issueHeading}>
          <PaletteResultItem result={issueHit} onSelect={onSelectIssue} />
        </CommandGroup>
      )}
      {similarResults.length > 0 && (
        <CommandGroup heading={commandPaletteStrings.resultsHeading}>
          {similarResults.map((result) => (
            <PaletteResultItem key={result.key} result={result} onSelect={onSelectIssue} />
          ))}
        </CommandGroup>
      )}
      {showSearching && <p className={NOTICE_CLASS}>{commandPaletteStrings.searching}</p>}
      {showEmptyNotice && (
        <p role="alert" className={NOTICE_CLASS}>
          {commandPaletteStrings.noResults}
        </p>
      )}
      {errorMessage !== null && (
        <p role="alert" className={NOTICE_CLASS}>
          {errorMessage}
        </p>
      )}
      {needsProject && (
        <>
          <p role="alert" className={NOTICE_CLASS}>
            {commandPaletteStrings.needsProject}
          </p>
          {/* 안내만 띄우면 팔레트 안에서 할 수 있는 게 없다 — 막다른 길을 열어 준다 */}
          <CommandItem
            value={PROJECT_PICKER_ITEM_VALUE}
            className={RESULT_ITEM_CLASS}
            onSelect={onSelectProjectPicker}
          >
            {commandPaletteStrings.pickProject}
          </CommandItem>
        </>
      )}
      {projectError !== null && (
        <>
          <p role="alert" className={NOTICE_CLASS}>
            {commandPaletteStrings.projectLoadFailed}
          </p>
          {/* ★탈출구가 프로젝트 목록이 아니라 **재시도**다. 목록을 못 불러온 상태에서
              목록으로 보내면 같은 실패를 한 번 더 보여줄 뿐이다. `<button>` 이 아니라
              CommandItem 인 이유 — 팔레트는 키보드 전용으로 완결돼야 하는데(NFR2) 포커스는
              입력창에 있고 방향키 이동은 cmdk 항목에만 걸린다. */}
          <CommandItem
            value={RETRY_PROJECTS_ITEM_VALUE}
            className={RESULT_ITEM_CLASS}
            onSelect={projectError.retry}
          >
            {commandPaletteStrings.retryProjects}
          </CommandItem>
        </>
      )}
      {showAllResults && (
        <>
          {/* ★alwaysRender 필수 — cmdk Separator 는 입력이 비어 있을 때만 그려진다(dist 실측).
              결과가 있는 화면은 항상 입력이 차 있으므로 이 prop 없이는 영영 안 보인다.
              액션과 결과가 같은 옷을 입으면 무엇이 결과인지 구분이 안 된다. */}
          {hasResult && <CommandSeparator alwaysRender className="my-1" />}
          <CommandItem
            value={ALL_RESULTS_ITEM_VALUE}
            className={RESULT_ITEM_CLASS}
            onSelect={() => {
              onSelectAllResults(fullSearchQuery)
            }}
          >
            {/* 총계를 모르는 상태(조회 전·실패·0건)에서 「(0건)」을 붙이면 거짓 정보가 된다.
                아는 경우에만 표기한다 — 7건 상한만 보고 "이게 전부"라 오독하는 것을 막는 값이다. */}
            {totalCount > 0
              ? commandPaletteStrings.allResultsWithCount(totalCount)
              : commandPaletteStrings.allResults}
          </CommandItem>
        </>
      )}
    </>
  )
}

/**
 * 검색 훅을 소유하는 영역 — **다이얼로그 안쪽**에 둔다.
 *
 * ★왜 부모(CommandPalette)가 아니라 여기서 `usePaletteSearch` 를 부르는가.
 * `CommandPalette` 는 인증만 되면 `RootLayout` 에 **항상 마운트**돼 있고 `open` 은 prop 일
 * 뿐이다. 훅을 부모에 두면 팔레트가 닫혀 있는 내내 프로젝트 목록 조회·디바운스 타이머가
 * 전 페이지에서 돌아간다. 실제로 그것이 부팅 직후 렌더/네트워크 순서를 밀어
 * `useKeyboardShortcuts` 의 리스너 재등록 타이밍과 겹치면서 leader 키(`g` `i`) 시퀀스를
 * 지우는 회귀를 만들었다(e2e `keyboard-shortcuts.spec.ts` S3a 실측).
 * cmdk 의 `Command.Dialog` 는 Radix Portal 이라 닫힌 동안 자식을 아예 렌더하지 않는다 —
 * 훅을 이 안으로 내리면 **닫힌 팔레트의 비용이 0** 이 된다.
 */
function PaletteSearchSection({
  input,
  onSelectIssue,
  onSelectAllResults,
  onSelectProjectPicker,
}: PaletteSearchSectionProps): JSX.Element {
  const search = usePaletteSearch(input)
  const similarResults = dedupeAgainstIssueHit(search.issueHit, search.results)
  const visibleResultCount = (search.issueHit === null ? 0 : 1) + similarResults.length
  const showResults = input.kind !== 'empty'

  return (
    <>
      {showResults && (
        <PaletteResults
          search={search}
          similarResults={similarResults}
          onSelectIssue={onSelectIssue}
          onSelectAllResults={onSelectAllResults}
          onSelectProjectPicker={onSelectProjectPicker}
        />
      )}
      {/* ★결과 도착은 시각 변화뿐이라 화면낭독기에는 무음이다. 개수를 live region 으로 알린다.
          팔레트가 열려 있는 동안 **항상 마운트**해 두고 문구만 갈아끼운다 — 결과가 생길 때
          영역째 새로 붙이면 낭독기가 삽입을 놓칠 수 있다. */}
      <p aria-live="polite" className="sr-only">
        {showResults ? buildResultAnnouncement(search, visibleResultCount) : ''}
      </p>
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cmd+K 명령 팔레트 컴포넌트.
 *
 * cmdk `Command.Dialog`(Radix Dialog 래핑 — focus trap/Esc 닫기/포커스 복원 기본 제공) 위에
 * shadcn 래퍼 `components/ui/command.tsx`의 프리미티브로 구성한다. `Command.Dialog`만 cmdk
 * 프리미티브를 직접 쓰는데, 래퍼가 `CommandDialog`를 "소비처 몫"이라며 의도적으로 제외했기
 * 때문이다(래퍼 파일 L1).
 *
 * - 입력이 비어 있으면 QUICK_LINKS(정적 바로가기) + COMMANDS(명령 힌트)를 렌더한다.
 * - 명령 힌트 선택 시 라우팅하지 않고 입력창에 prefix를 채운다(S2 보강).
 * - `/goto`·`/search`·`/issue` + Enter로 parseCommand 결과에 따라 navigate한다.
 * - 알 수 없는/불완전 명령은 안내 문구를 표시하고 실행하지 않는다(FR5).
 * - 슬래시가 아닌 입력은 이슈키/자유텍스트로 판별해 결과를 인라인으로 그린다(FR-UX-12 F4).
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
  // ★순서가 계약이다(FR2). 슬래시 판별이 먼저 끝나야 `/goto ATLAS-1`이 이슈키로 새지 않는다.
  const paletteInput =
    parsed.kind === 'not-command' ? resolveNonCommandInput(inputValue) : NO_SEARCH_INPUT

  // ★`inputValue === ''` 를 보지 않는다. 판별 레이어가 `"   "` 를 이미 `empty` 로 분류하는데
  // 원문을 다시 보면 그 결과를 버리는 셈이라, 공백 하나에 팔레트가 통째로 빈 화면이 된다
  // (E12 위반 — 한글 입력 중 앞 공백으로 쉽게 도달한다).
  // `parsed.kind === 'not-command'` 는 남긴다 — 슬래시 입력 시 `paletteInput` 은 검색을
  // 끄기 위한 고정값 `NO_SEARCH_INPUT`(=empty)이라 그것만 보면 `/goto` 에도 바로가기가 뜬다.
  const showQuickLinks = parsed.kind === 'not-command' && paletteInput.kind === 'empty'
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

  /** 결과 한 줄 선택 — 이슈 상세로 이동 후 닫는다 */
  function handleIssueSelect(issueKey: string): void {
    void navigate({ to: '/issues/$key', params: { key: issueKey } })
    handleClose()
  }

  /** 「모든 결과 보기」 — 팔레트가 만든 것과 **같은** AQL을 검색 페이지에 넘긴다 */
  function handleAllResultsSelect(aqlQuery: string): void {
    void navigate({ to: '/search', search: { q: aqlQuery } })
    handleClose()
  }

  /** 「프로젝트 선택하러 가기」 — 활성 프로젝트를 고르러 목록으로 보낸다 */
  function handleProjectPickerSelect(): void {
    void navigate({ to: '/projects' })
    handleClose()
  }

  /** 입력창 Enter 키 핸들러 — 슬래시 명령 실행(C3, NFR4) */
  function handleInputKeyDown(e: KeyboardEvent<HTMLInputElement>): void {
    if (e.key !== 'Enter') return
    if (e.nativeEvent.isComposing) return // IME 조합 중 확정 Enter는 실행하지 않는다(NFR4)

    // ★not-command는 cmdk에 위임한다. 여기서 Enter를 가로채면 하이라이트가 어디에 있든
    // 늘 첫 항목으로 가버린다 — 각 CommandItem이 자기 라우팅을 소유해야 방향키 조작이 산다.
    if (parsed.kind === 'not-command') return

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
        <PaletteSearchSection
          input={paletteInput}
          onSelectIssue={handleIssueSelect}
          onSelectAllResults={handleAllResultsSelect}
          onSelectProjectPicker={handleProjectPickerSelect}
        />
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
