// 단축키 도움말 모달 단위 테스트 — SHORTCUTS 레지스트리 렌더, 비구현 키 미표시, 닫힘 콜백 (FR-UX-05 Task-3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ShortcutsHelpDialog } from './ShortcutsHelpDialog'
import { DEFAULT_KEYMAP, PALETTE_HELP_ITEM, SHORTCUTS } from './shortcuts'
import { CONTEXT_SHORTCUTS, type ShortcutContext } from './context-shortcuts'

/**
 * 컨텍스트 레이어 → 그 항목이 실려야 할 도움말 그룹 라벨.
 *
 * 🛑 `Record<ShortcutContext, string>` 이지만 **타입이 지켜주지 않는다** — `tsc --noEmit` 의
 *    대상에 테스트 파일이 들어 있지 않아, 새 레이어를 여기 빠뜨려도 컴파일은 통과한다.
 *    그 경우 `GROUP_LABEL_OF[context]` 가 `undefined` 가 되고 `getByRole(..., {name: undefined})`
 *    가 **모든 region 에 매칭돼** 「Found multiple elements」라는 엉뚱한 메시지로 죽는다(실측).
 *    새 `ShortcutContext` 를 만들면 여기와 `ShortcutsHelpDialog.tsx` 의 그룹 조립 **둘 다** 고쳐라.
 */
const GROUP_LABEL_OF: Record<ShortcutContext, string> = {
  'app-shell': '어디서나',
  'issue-list': '이슈 목록에서',
  'issue-detail': '이슈 상세에서',
  // 모바일 전용 한 줄이라 전용 섹션을 두지 않고 전역 그룹에 함께 싣는다(컴포넌트 주석 참조).
  'sidebar-drawer': '어디서나',
}

/**
 * 별칭 구분자 문구 — 컴포넌트의 `ALIAS_SEPARATOR` 미러.
 *
 * export 하지 않는 이유는 이 모듈의 다른 문자열(모달 제목·그룹 라벨)과 같은 관례이며
 * (e2e 도 "하드코딩 선례"로 문서화), 문구가 바뀌면 아래 **양성 단언이 먼저 red** 라
 * 조용히 어긋나지 않기 때문이다.
 */
const ALIAS_SEPARATOR = '또는'

/** 모달에 렌더된 설명(`<dt>`) 전량 — 파생형 단언들이 공유하는 실측값 */
function renderedDescriptions(): readonly string[] {
  return [...screen.getByRole('dialog').querySelectorAll('dt')].map((dt) => dt.textContent ?? '')
}

describe('ShortcutsHelpDialog', () => {
  it('open=true이면 role=dialog로 표시되고 제목이 "키보드 단축키"이다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('키보드 단축키')).toBeInTheDocument()
  })

  it('open=false이면 화면에 표시되지 않는다', () => {
    render(<ShortcutsHelpDialog open={false} onOpenChange={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('SHORTCUTS 5종 설명이 각각 표시된다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.getByText('단축키 도움말 열기/닫기')).toBeInTheDocument()
    expect(screen.getByText('새 이슈 생성')).toBeInTheDocument()
    expect(screen.getByText('검색으로 이동')).toBeInTheDocument()
    expect(screen.getByText('내 이슈로 이동')).toBeInTheDocument()
    expect(screen.getByText('대시보드로 이동')).toBeInTheDocument()
  })

  it('Cmd+K 명령 팔레트 항목이 표시된다 (혼합 OS 대응 Cmd/Ctrl 병기)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.getByText('명령 팔레트 열기')).toBeInTheDocument()
    // 혼합 OS에서 Windows 사용자에게 Cmd만 오표기하지 않도록 Cmd/Ctrl 병기 검증 (C1)
    expect(screen.getByText('Cmd/Ctrl')).toBeInTheDocument()
  })

  it('비구현 단축키(편집/담당자 변경/상태 변경)는 표시되지 않는다 (FR8)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.queryByText('편집')).not.toBeInTheDocument()
    expect(screen.queryByText('담당자 변경')).not.toBeInTheDocument()
    expect(screen.queryByText('상태 변경')).not.toBeInTheDocument()
  })

  it('onOpenChange prop이 전달되고 Esc 입력 시 false로 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    render(<ShortcutsHelpDialog open onOpenChange={onOpenChange} />)
    await user.keyboard('{Escape}')
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('open=false → true로 리렌더하면 모달이 나타난다', () => {
    const { rerender } = render(<ShortcutsHelpDialog open={false} onOpenChange={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    rerender(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('X 닫기 버튼 클릭 시 onOpenChange가 false로 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    render(<ShortcutsHelpDialog open onOpenChange={onOpenChange} />)
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 컨텍스트 단축키 그룹 (FR-UX-10 F10 Task-4)
//
// 현재 화면 것만 거르지 않고 전체를 보여주되 그룹으로 나눈다 — 화면마다 목록이
// 바뀌면 학습이 안 된다.
// ─────────────────────────────────────────────────────────────────────────────

describe('ShortcutsHelpDialog — 컨텍스트 단축키 그룹 (FR-UX-10 F10)', () => {
  it('그룹 헤딩 3개(「어디서나」·「이슈 목록에서」·「이슈 상세에서」)로 나뉜다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    expect(screen.getByRole('heading', { name: '어디서나' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '이슈 목록에서' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '이슈 상세에서' })).toBeInTheDocument()
  })

  it('목록 항법 4종이 「이슈 목록에서」 그룹 안에 있다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const listGroup = screen.getByRole('region', { name: '이슈 목록에서' })
    expect(within(listGroup).getByText('다음 이슈로 이동')).toBeInTheDocument()
    expect(within(listGroup).getByText('이전 이슈로 이동')).toBeInTheDocument()
    expect(within(listGroup).getByText('선택한 이슈 열기')).toBeInTheDocument()
    expect(within(listGroup).getByText('상세 패널 열기/닫기')).toBeInTheDocument()
  })

  it('★사이드바 토글은 목록 전용이 아니므로 「어디서나」 그룹에 있다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const globalGroup = screen.getByRole('region', { name: '어디서나' })
    expect(within(globalGroup).getByText('사이드바 접기/펼치기')).toBeInTheDocument()
  })

  it('★C5-b 기존 5종 + Cmd/Ctrl K 는 「어디서나」에 문구 그대로 남는다 (e2e가 텍스트로 찾는다)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const globalGroup = screen.getByRole('region', { name: '어디서나' })
    for (const description of [
      '단축키 도움말 열기/닫기',
      '새 이슈 생성',
      '검색으로 이동',
      '내 이슈로 이동',
      '대시보드로 이동',
      '명령 팔레트 열기',
    ]) {
      expect(within(globalGroup).getByText(description)).toBeInTheDocument()
    }
  })

  /**
   * ★F10 시점의 「5종」 하드코딩을 레지스트리 파생으로 교체했다 — 레지스트리가 13종으로
   * 늘어도(F11) 스냅샷이 썩지 않고, 각 키가 **소속 그룹 안**에 있는지까지 함께 잰다.
   * 키만 dialog 전체에서 찾으면 엉뚱한 그룹에 실려도 통과한다.
   */
  it('★컨텍스트 단축키 전량이 소속 그룹 안에 설명+키 표기로 렌더된다 (파생)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    for (const shortcut of CONTEXT_SHORTCUTS) {
      const group = screen.getByRole('region', { name: GROUP_LABEL_OF[shortcut.context] })
      const row = within(group).getByText(shortcut.description).closest('div')
      expect(row).not.toBeNull()
      expect(within(row as HTMLElement).getByText(shortcut.key)).toBeInTheDocument()
    }
  })

  it('상세 액션 7종이 「이슈 상세에서」 그룹 안에 있다 (F11)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const detailGroup = screen.getByRole('region', { name: '이슈 상세에서' })
    const expected = CONTEXT_SHORTCUTS.filter((s) => s.context === 'issue-detail')
    expect(detailGroup.querySelectorAll('dt')).toHaveLength(expected.length)
    for (const shortcut of expected) {
      expect(within(detailGroup).getByText(shortcut.description)).toBeInTheDocument()
    }
  })

  /**
   * ★`.` 은 `Cmd/Ctrl+K` 와 **같은 팔레트를 여는 두 번째 열쇠**다(스펙 §Jira 대조 3-b).
   * 두 행으로 두면 같은 접근성 이름이 둘이 돼 e2e 가 strict 위반으로 죽고 React key 도
   * 충돌한다(둘 다 실측). 한 행 · 키 표기 둘로 접혀야 한다.
   */
  it('★팔레트 별칭 `.` 은 Cmd/Ctrl K 와 같은 행에 접힌다 (행 중복 0)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const globalGroup = screen.getByRole('region', { name: '어디서나' })
    expect(within(globalGroup).getAllByText(PALETTE_HELP_ITEM.description)).toHaveLength(1)

    const paletteRow = within(globalGroup).getByText(PALETTE_HELP_ITEM.description).closest('div')
    expect(paletteRow).not.toBeNull()
    for (const key of [...PALETTE_HELP_ITEM.keys, '.']) {
      expect(within(paletteRow as HTMLElement).getByText(key)).toBeInTheDocument()
    }
  })

  /**
   * ★대안(둘 중 아무거나)과 순차 입력(차례로)을 눈으로 가르는 구분자.
   *
   * 칩만 나란히 두면 `Cmd/Ctrl` `K` `.` 이 `g` `i` 와 모양이 같아 "셋을 차례로 누른다"로
   * 읽힌다(눈확인 실측 → Maxi 확정). 색만 흐리게 하는 안은 「못 쓰는 키」로 오해되고
   * 색약자·스크린리더에 닿지 않아 기각됐다. 그래서 **텍스트**로 넣고 톤만 낮춘다.
   */
  it('★별칭이 둘이면 키 사이에 「또는」 구분자가 들어간다 (칩 아님 · 낭독 순서 유지)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const globalGroup = screen.getByRole('region', { name: '어디서나' })
    const paletteRow = within(globalGroup).getByText(PALETTE_HELP_ITEM.description).closest('div')
    expect(paletteRow).not.toBeNull()

    const separator = within(paletteRow as HTMLElement).getByText(ALIAS_SEPARATOR)
    // 칩과 톤이 구별돼야 한다 — `<kbd>` 로 렌더되면 세 번째 키처럼 보인다.
    expect(separator.tagName).not.toBe('KBD')
    expect(separator).toHaveClass('text-muted-foreground')

    // 스크린리더 낭독 순서가 "Cmd/Ctrl K 또는 ." 이어야 한다.
    const keysText = (paletteRow as HTMLElement).querySelector('dd')?.textContent ?? ''
    expect(keysText.indexOf(ALIAS_SEPARATOR)).toBeGreaterThan(keysText.indexOf('K'))
    expect(keysText.lastIndexOf('.')).toBeGreaterThan(keysText.indexOf(ALIAS_SEPARATOR))
  })

  it('★대안이 하나뿐인 행에는 구분자가 없다 (순차 입력 `g` `i` 와 구별되는 짝)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const globalGroup = screen.getByRole('region', { name: '어디서나' })
    for (const description of ['새 이슈 생성', '내 이슈로 이동']) {
      const row = within(globalGroup).getByText(description).closest('div')
      expect(row).not.toBeNull()
      expect(within(row as HTMLElement).queryByText(ALIAS_SEPARATOR)).not.toBeInTheDocument()
    }

    // 모달 전체에서 구분자가 있는 행은 별칭이 실제로 둘인 행뿐이다.
    const aliasRowCount = CONTEXT_SHORTCUTS.filter(
      (s) => s.description === PALETTE_HELP_ITEM.description,
    ).length
    expect(within(screen.getByRole('dialog')).getAllByText(ALIAS_SEPARATOR)).toHaveLength(
      aliasRowCount,
    )
  })

  /**
   * ★F10 의 「미구현 키 금지 문구 목록」을 파생형으로 교체했다.
   *
   * 그 목록은 F10 시점 스냅샷이라 F11 이 `담당자 지정`·`라벨 편집` 을 구현하는 순간
   * 통째로 거짓이 된다. 지켜야 할 계약(FR-UX-05 FR8 — 비구현 단축키를 동작하는 것처럼
   * 보여주지 않는다)은 그대로 살리되, **레지스트리에 없는 설명은 뜰 수 없다**는
   * 집합 단언으로 바꿔 F12 이후에도 같은 보호가 유지되게 한다.
   */
  it('★레지스트리에 없는 설명은 표시되지 않는다 (FR8 — 스냅샷 아닌 파생형)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const allowed = new Set([
      ...SHORTCUTS.map((s) => s.description),
      PALETTE_HELP_ITEM.description,
      ...CONTEXT_SHORTCUTS.map((s) => s.description),
    ])
    // 양방향 등식 — 없는 것은 뜨지 않고(FR8), 있는 것은 빠짐없이 뜬다(누락 회귀 차단).
    expect(new Set(renderedDescriptions())).toEqual(allowed)
  })

  it('★단일 진실 출처 — 렌더된 설명 수가 두 레지스트리 합계와 정확히 같다 (하드코딩 줄 0)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    // 별칭(`.`)은 팔레트 행에 접히므로 그만큼 행이 줄어든다 — 개수도 파생으로 센다.
    const aliasCount = CONTEXT_SHORTCUTS.filter(
      (s) => s.description === PALETTE_HELP_ITEM.description,
    ).length
    const expected = SHORTCUTS.length + 1 + CONTEXT_SHORTCUTS.length - aliasCount // +1 = 명령 팔레트
    expect(renderedDescriptions()).toHaveLength(expected)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★M-8 — 전역 5종 표기가 실효 키맵을 따른다.
//
// `SHORTCUTS[].keys` 는 정적 표기라, 사용자가 FR-PF-03 으로 재배치하면 모달이
// **없는 키를 계속 광고**했다(선재 결함). 훅이 이미 계산해 두는 실효 키맵을
// 받아 표기한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('ShortcutsHelpDialog — 실효 키맵 표기 (M-8)', () => {
  it('★사용자가 재배치한 키를 표기한다 (정적 표기 c 가 아니라 n)', () => {
    const remapped = { ...DEFAULT_KEYMAP, 'create-issue': 'n' }
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} keymap={remapped} />)

    const globalGroup = screen.getByRole('region', { name: '어디서나' })
    const createIssueRow = within(globalGroup).getByText('새 이슈 생성').closest('div')
    expect(createIssueRow).not.toBeNull()
    expect(within(createIssueRow as HTMLElement).getByText('n')).toBeInTheDocument()
    expect(within(createIssueRow as HTMLElement).queryByText('c')).not.toBeInTheDocument()
  })

  it('leader combo 재배치도 두 칸으로 쪼개 표기한다', () => {
    const remapped = { ...DEFAULT_KEYMAP, 'goto-my-issues': 'g m' }
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} keymap={remapped} />)

    const globalGroup = screen.getByRole('region', { name: '어디서나' })
    const row = within(globalGroup).getByText('내 이슈로 이동').closest('div')
    expect(within(row as HTMLElement).getByText('g')).toBeInTheDocument()
    expect(within(row as HTMLElement).getByText('m')).toBeInTheDocument()
  })

  it('keymap 을 생략하면 기본 키맵으로 폴백한다 (기존 호출부 무회귀)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const globalGroup = screen.getByRole('region', { name: '어디서나' })
    const row = within(globalGroup).getByText('새 이슈 생성').closest('div')
    expect(within(row as HTMLElement).getByText('c')).toBeInTheDocument()
  })

  it('컨텍스트 키는 재배치 대상이 아니므로 키맵과 무관하게 고정이다 (v1 고정 키)', () => {
    const remapped = { ...DEFAULT_KEYMAP, 'create-issue': 'n' }
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} keymap={remapped} />)

    const listGroup = screen.getByRole('region', { name: '이슈 목록에서' })
    expect(within(listGroup).getByText('j')).toBeInTheDocument()
  })
})
