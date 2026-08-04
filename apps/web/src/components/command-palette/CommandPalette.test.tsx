// CommandPalette 단위 테스트 — 빈 목록 렌더/명령 힌트 prefill/goto·search·issue 실행/E1~E3/IME 가드 (FR-UX-04 Task-2)
// + 이슈키·자유텍스트 결과 렌더 / 안내 4종 / 탈출구 2종 / 접근성 (FR-UX-12 F4 Task-7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { issueHandlers } from '@/mocks/issue-handlers'
import {
  searchHandlers,
  searchAqlEmptyHandler,
  searchAqlSyntaxErrorHandler,
} from '@/mocks/search-handlers'
import { DEFAULT_SEARCH_PAGE, makeSearchPage } from '@/mocks/search-fixtures'
import { CommandPalette } from './CommandPalette'
import { QUICK_LINKS, COMMANDS } from './commands'

// jsdom은 scrollIntoView를 구현하지 않는다 — cmdk Command.Item이 활성 항목 변경 시
// 내부적으로 호출하므로(cmdk 소스 `ne()`), 이 파일 범위에서만 no-op stub을 등록한다.
if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
  window.HTMLElement.prototype.scrollIntoView = () => {}
}

// TanStack Router useNavigate 모킹 — 라우터 컨텍스트 없이 단위 테스트 (Header.test.tsx 패턴 미러)
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  // usePaletteSearch 가 URL 프로젝트 키를 읽는다. 라우터 컨텍스트 밖에서 실물 `useSearch` 는
  // undefined 를 주는 게 아니라 **던진다**(use-palette-search.test.tsx 실측) — 최소 표면만 대체한다.
  useSearch: () => ({}),
}))

// 활성 프로젝트 해소를 훅 경계에서 갈아끼운다 — 프로젝트 상태별 분기(FR7)를 네트워크 없이 검증.
const mockResolved = vi.fn()
vi.mock('@/hooks/use-resolved-active-project', () => ({
  useResolvedActiveProject: () => mockResolved(),
}))

/** 전역 `retry: false`(main.tsx 기본값) 재현 — 실패 경로가 3회 재시도로 늘어지지 않게 한다 */
const makeClient = (): QueryClient =>
  new QueryClient({ defaultOptions: { queries: { retry: false } } })

// 클라이언트를 wrapper 바깥에 둔다 — wrapper 본문에서 new 하면 rerender 마다 캐시가 통째로
// 리셋돼 "재오픈 시 입력 초기화" 테스트가 엉뚱한 이유로 흔들린다.
let queryClient = makeClient()

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

/** CommandPalette를 열린 상태로 렌더하고 onOpenChange mock을 반환하는 헬퍼 */
function renderPalette() {
  const onOpenChange = vi.fn()
  render(<CommandPalette open onOpenChange={onOpenChange} />, { wrapper })
  return { onOpenChange }
}

beforeEach(() => {
  // `src/test/handlers.ts` 기본 목록은 auth refresh 하나뿐이라 BC 핸들러를 파일마다 등록한다
  // (저장소 관례 — CreateIssueDialog.test.tsx 등). 빼먹으면 미핸들 에러가
  // 「검색에 실패했습니다.」로 둔갑해 거짓 신호를 진짜로 착각하게 만든다.
  server.use(...issueHandlers, ...searchHandlers)
  queryClient = makeClient()
  mockNavigate.mockReset()
  mockResolved.mockReturnValue({ status: 'ready', projectKey: 'ATLAS', source: 'stored' })
})

describe('CommandPalette', () => {
  it('빈 입력 시 QUICK_LINKS 4개 + 명령 힌트 3개를 렌더한다 (S2)', () => {
    renderPalette()

    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(QUICK_LINKS.length + COMMANDS.length)

    for (const link of QUICK_LINKS) {
      expect(screen.getByRole('option', { name: link.label })).toBeInTheDocument()
    }
  })

  it('명령 힌트 /goto 선택 시 입력창에 /goto 를 prefill하고 라우팅하지 않는다 (S2 보강)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const gotoHint = screen.getByRole('option', { name: /goto/i })
    await user.click(gotoHint)

    const input = screen.getByRole('combobox') as HTMLInputElement
    expect(input.value).toBe('/goto ')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('/goto PROJ-12 입력 후 Enter → 이슈 상세로 navigate하고 팔레트를 닫는다 (S3)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto PROJ-12')
    await user.keyboard('{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/$key', params: { key: 'PROJ-12' } })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('/search 로그인 버그 입력 후 Enter → text ~ 로 감싼 AQL 로 navigate 한다 (S7 · FR9)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/search 로그인 버그')
    await user.keyboard('{Enter}')

    // 봉합 전에는 { q: '로그인 버그' } 였고 그것은 실서버에서 SEARCH_SYNTAX_ERROR 다
    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/search',
      search: { q: 'text ~ "로그인 버그"' },
    })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('/search 에 따옴표가 들어가도 유효한 AQL 이 된다 (E4)', async () => {
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '/search 로그인"버그')
    await user.keyboard('{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/search',
      search: { q: 'text ~ "로그인\\"버그"' },
    })
  })

  it('/issue 결제 실패 조사 입력 후 Enter → 새 이슈 폼으로 navigate한다 (S5)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/issue 결제 실패 조사')
    await user.keyboard('{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/new', search: { summary: '결제 실패 조사' } })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('알 수 없는 명령(/foo)은 안내 문구를 표시하고 navigate하지 않는다 (E1)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/foo x')

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('알 수 없는 명령입니다. /foo')
    // 문장 종결에 콜론을 쓰지 않는다 (§5 회귀 방지, codereview CONCERN-2)
    expect(alert.textContent).not.toContain(':')

    await user.keyboard('{Enter}')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('외부 open이 false로 바뀌었다가 다시 true가 되면 입력을 초기화한다 (Cmd+K 토글 재오픈, codereview CONCERN-1)', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    const { rerender } = render(<CommandPalette open onOpenChange={onOpenChange} />, { wrapper })

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto ')
    // 인자 없는 /goto → 안내 문구만 표시, 빠른이동 목록은 숨겨진다
    expect(screen.queryAllByRole('option')).toHaveLength(0)

    // Radix Command.Dialog는 controlled open이 외부에서 false로 바뀌어도
    // onOpenChange를 호출하지 않는다 — useCommandPalette의 토글(Cmd+K)을 흉내
    rerender(<CommandPalette open={false} onOpenChange={onOpenChange} />)
    rerender(<CommandPalette open onOpenChange={onOpenChange} />)

    const reopenedInput = screen.getByRole('combobox') as HTMLInputElement
    expect(reopenedInput.value).toBe('')
    expect(screen.getAllByRole('option')).toHaveLength(QUICK_LINKS.length + COMMANDS.length)
  })

  it('인자 없는 /goto는 사용법 안내를 표시하고 navigate하지 않는다 (E2)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto')

    expect(screen.getByRole('alert')).toHaveTextContent(/goto/i)

    await user.keyboard('{Enter}')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('이슈 키 형식이 아닌 /goto 안녕은 형식 안내를 표시하고 navigate하지 않는다 (E3)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto 안녕')

    expect(screen.getByRole('alert')).toHaveTextContent(/PROJ-12/)

    await user.keyboard('{Enter}')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('입력창이 combobox 로, 바로가기가 option 으로 노출된다 (NFR5 무회귀)', () => {
    renderPalette()

    expect(screen.getByRole('combobox')).toBeInTheDocument()
    // RTL 의 ByRoleOptions 에는 exact 키가 없다(타입 에러) — 문자열 name 은 이미
    // 접근성 이름 전체 일치로 매칭되므로 Playwright 의 exact:true 와 같은 의미다.
    expect(screen.getByRole('option', { name: '내 이슈' })).toBeInTheDocument()
  })

  it('★래퍼 채택 후에도 팔레트 안에 「검색」 접근성 이름이 하나뿐이다 (즉사 계약)', () => {
    renderPalette()

    // command-palette.spec.ts:259 가 exact:true 로 이 이름을 잡는다. 래퍼 CommandInput 이
    // 추가하는 돋보기 아이콘이 접근성 이름을 만들면 strict mode 로 즉사한다.
    // 명령 힌트 '/search 검색 결과로 이동' 이 '검색' 을 부분 문자열로 품고 있으므로,
    // 전체 일치가 아니면 이 단언은 2 가 되어 깨진다 — 공허하지 않다.
    expect(screen.getAllByRole('option', { name: '검색' })).toHaveLength(1)

    // 위 단언만으로는 아이콘을 재지 못한다(svg 는 애초에 option 이 아니다) — 아이콘이
    // 접근성 트리에서 빠져 있다는 것 자체를 증인으로 세운다. lucide 는 children/aria-*/
    // role/title 이 없을 때만 aria-hidden 을 자동 부여하므로, 누가 아이콘에 aria-label 을
    // 붙이는 순간 이 단언이 깨진다.
    const inputWrapper = screen.getByRole('combobox').closest('[data-slot="command-input-wrapper"]')
    expect(inputWrapper).not.toBeNull()
    const searchIcon = inputWrapper?.querySelector('svg') ?? null
    expect(searchIcon).not.toBeNull()
    expect(searchIcon?.getAttribute('aria-hidden')).toBe('true')
  })

  it('IME 조합 중 Enter는 명령을 실행하지 않는다 (NFR4)', async () => {
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '/goto PROJ-12')
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })

    expect(mockNavigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-12 F4 Task-7 — 이슈키/자유텍스트 결과 렌더 · 안내 4종 · 탈출구 · 접근성
// ─────────────────────────────────────────────────────────────────────────────

describe('CommandPalette — 실체 검색 (FR-UX-12 F4)', () => {
  it('이슈키 입력 시 그 이슈가 결과 최상단에 뜨고 Enter 로 이동한다 (S1)', async () => {
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), 'ATLAS-1')
    // 정확일치는 검색 결과에서 걷어내므로 ATLAS-1 항목은 정확히 하나다
    const hit = await screen.findByRole('option', { name: /ATLAS-1/ })
    expect(screen.getAllByRole('option')[0]).toBe(hit)

    await user.keyboard('{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/$key', params: { key: 'ATLAS-1' } })
  })

  it('자유 텍스트 입력 시 결과 목록이 뜬다 (S4)', async () => {
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '로그인')

    expect(await screen.findAllByRole('option')).not.toHaveLength(0)
    // 즉사 계약 — 결과가 그려지는 상태에서도 팔레트 안에 option 「검색」을 새로 만들지 않는다
    // (command-palette.spec.ts:259 가 전체 일치로 잡는다). 「검색 결과」는 그룹 heading 이라
    // option 이 아니므로 이 단언에 걸리지 않아야 한다.
    expect(screen.queryAllByRole('option', { name: '검색' })).toHaveLength(0)
  })

  it('이슈 정확일치와 검색 결과가 다른 그룹에 놓인다 (design 리뷰 1-1)', async () => {
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), 'ATLAS-1')

    expect(await screen.findByText('이슈')).toBeInTheDocument()
    expect(await screen.findByText('검색 결과')).toBeInTheDocument()
  })

  it('「모든 결과 보기」가 감싼 AQL 로 /search 에 넘긴다 (S5 · FR6)', async () => {
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '로그인')
    await user.click(await screen.findByRole('option', { name: /모든 결과 보기/ }))

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/search',
      search: { q: 'text ~ "로그인"' },
    })
  })

  it('★결과와 「모든 결과 보기」 사이에 구분선이 실제로 그려진다 (design 리뷰 5-1)', async () => {
    // cmdk Separator 는 입력이 비어 있을 때만 그린다(`!alwaysRender && !d ? null` — dist 실측).
    // 결과 화면은 늘 입력이 차 있으므로 `alwaysRender` 를 빠뜨리면 **조용히 사라진다** —
    // 렌더 코드는 그대로인데 화면에서만 없어지는 유형이라 눈확인 없이는 못 잡는다.
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '로그인')
    // ★「모든 결과 보기」는 질의가 확정되는 순간(응답 **전**)부터 뜬다 — 그것으로 기다리면
    // 결과가 아직 0건이라 구분선이 없는 시점을 재게 된다(실측으로 잡은 red). 결과 도착을 기다린다.
    await screen.findByRole('option', { name: /ATLAS-1/ })

    expect(screen.getByRole('separator')).toBeInTheDocument()
  })

  it('「모든 결과 보기」가 총 건수를 표기한다 — 7건이 전부로 오독되지 않는다 (design 리뷰 2-2)', async () => {
    // ★기본 픽스처는 totalElements 가 결과 수(3)와 같아 `results.length` 대체를 못 잡는다.
    // plan 스니펫의 「기본 MSW 는 totalElements=50」은 실측과 다르다 — makeSearchPage 가
    // 오버라이드 없으면 hits.length 로 계산한다. 표시분 3 ↔ 총계 42 로 어긋나게 만든다.
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(makeSearchPage(DEFAULT_SEARCH_PAGE.data, { totalElements: 42 })),
      ),
    )
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '로그인')

    expect(await screen.findByRole('option', { name: /모든 결과 보기 \(42건\)/ })).toBeInTheDocument()
  })

  it('★검색 중에는 진행 표시가 뜬다 — isSearching 이 공허하지 않다 (design 리뷰 2-1)', async () => {
    // ★디바운스 대기 구간도 「검색 중…」으로 덮이므로, 그것만으로 단언하면 `isSearching` 을
    // 지워도 초록인 공허한 테스트가 된다. 응답을 붙잡아 두고 **요청이 실제로 나간 뒤**를
    // 재면 남는 근거는 isSearching 하나뿐이다.
    let searchStarted = false
    let releaseSearch: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      releaseSearch = resolve
    })
    server.use(
      http.post('/api/v1/search/aql', async () => {
        searchStarted = true
        await held
        return HttpResponse.json(DEFAULT_SEARCH_PAGE)
      }),
    )
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '로그인')
    await waitFor(() => {
      expect(searchStarted).toBe(true)
    })

    expect(screen.getByText('검색 중…')).toBeInTheDocument()

    releaseSearch()
    await screen.findByRole('option', { name: /ATLAS-1/ })
  })

  it('결과 0건이면 안내를 표시한다 (FR12 · E11)', async () => {
    server.use(searchAqlEmptyHandler)
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '없는것')

    expect(await screen.findByText('결과가 없습니다.')).toBeInTheDocument()
    // 「모든 결과 보기」는 0건에도 남는다 — 전체 페이지에선 더 나올 수 있다(E11).
    // 총계를 모르는 상태에서 「(0건)」을 붙이지 않으므로 이름은 접미사 없는 원형이다.
    expect(screen.getByRole('option', { name: '모든 결과 보기' })).toBeInTheDocument()
  })

  it('검색 실패 시 팔레트를 닫지 않고 안내만 표시한다 (FR13)', async () => {
    server.use(searchAqlSyntaxErrorHandler)
    const user = userEvent.setup()
    const { onOpenChange } = renderPalette()

    await user.type(screen.getByRole('combobox'), '로그인')

    expect(await screen.findByText('검색에 실패했습니다.')).toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    // 탈출구는 남긴다 — 검색 페이지의 상세 진단(resolveErrorMessage 4갈래)으로 갈 길을 연다
    expect(screen.getByRole('option', { name: '모든 결과 보기' })).toBeInTheDocument()
  })

  it('프로젝트 미해소 시 「프로젝트 선택하러 가기」로 탈출할 수 있다 (design 리뷰 3-2)', async () => {
    mockResolved.mockReturnValue({ status: 'empty' })
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '로그인')

    expect(await screen.findByText('프로젝트를 먼저 선택하세요.')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: '프로젝트 선택하러 가기' }))

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/projects' })
  })

  it('★결과 개수가 스크린리더에 알려진다 (design 리뷰 6-2)', async () => {
    const user = userEvent.setup()
    renderPalette()

    await user.type(screen.getByRole('combobox'), '로그인')

    const live = await screen.findByText(/건 찾음/)
    expect(live).toHaveAttribute('aria-live', 'polite')
  })

  it('★IME 조합 중 Enter 는 결과를 선택하지 않는다 (NFR4)', async () => {
    // cmdk 루트가 `isComposing || keyCode === 229` 를 가드한다(dist 소스 실측).
    // 우리 코드가 아니라 **라이브러리 동작**이므로, cmdk 업그레이드가 조용히 이걸
    // 없애면 한국어 입력 중 Enter 가 엉뚱한 결과를 연다. 이 테스트가 그 증인이다.
    const user = userEvent.setup()
    renderPalette()

    const input = screen.getByRole('combobox')
    await user.type(input, '로그인')
    await screen.findAllByRole('option')
    mockNavigate.mockClear()

    fireEvent.keyDown(input, { key: 'Enter', isComposing: true, keyCode: 229 })

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('★빈 입력 동작은 무회귀다 — 바로가기 4개와 순서 (즉사 계약 · S6)', () => {
    renderPalette()

    const options = screen.getAllByRole('option')
    expect(options.slice(0, 4).map((o) => o.textContent)).toEqual([
      '내 이슈',
      '검색',
      '대시보드',
      '받은 편지함',
    ])
  })
})
