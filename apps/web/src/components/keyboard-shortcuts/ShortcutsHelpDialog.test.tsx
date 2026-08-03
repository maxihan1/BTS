// 단축키 도움말 모달 단위 테스트 — SHORTCUTS 레지스트리 렌더, 비구현 키 미표시, 닫힘 콜백 (FR-UX-05 Task-3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ShortcutsHelpDialog } from './ShortcutsHelpDialog'
import { DEFAULT_KEYMAP, SHORTCUTS } from './shortcuts'
import { CONTEXT_SHORTCUTS } from './context-shortcuts'

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
  it('그룹 헤딩 2개(「어디서나」·「이슈 목록에서」)로 나뉜다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    expect(screen.getByRole('heading', { name: '어디서나' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '이슈 목록에서' })).toBeInTheDocument()
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

  it('컨텍스트 키 5종의 키 표기가 모두 렌더된다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const dialog = screen.getByRole('dialog')
    for (const key of ['j', 'k', 'o', 't', '[']) {
      expect(within(dialog).getByText(key)).toBeInTheDocument()
    }
  })

  it('★C5 F11 미구현 키(담당자·라벨·관심·즐겨찾기)는 표시되지 않는다 (FR8 승계)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    for (const notYet of [
      '담당자 지정',
      '나에게 배정',
      '댓글 작성',
      '라벨 편집',
      '관심 토글',
      '즐겨찾기 토글',
      '액션 메뉴',
    ]) {
      expect(screen.queryByText(notYet)).not.toBeInTheDocument()
    }
  })

  it('★단일 진실 출처 — 렌더된 설명 수가 두 레지스트리 합계와 정확히 같다 (하드코딩 줄 0)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)

    const expected = SHORTCUTS.length + 1 + CONTEXT_SHORTCUTS.length // +1 = 명령 팔레트
    expect(screen.getByRole('dialog').querySelectorAll('dt')).toHaveLength(expected)
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
