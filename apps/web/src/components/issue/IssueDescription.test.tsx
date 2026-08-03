// IssueDescription 컴포넌트 단위 테스트 — Write/Preview 탭, 저장/취소 흐름, .mention 강조 검증
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import type { ReactNode, JSX } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { userHandlers } from '@/mocks/user-handlers'
import { IssueDescription } from './IssueDescription'

// ─────────────────────────────────────────────────────────────────────────────
// 멘션 테스트용 wrapper 팩토리 — QueryClientProvider 제공
// ─────────────────────────────────────────────────────────────────────────────

/**
 * QueryClient wrapper 생성 팩토리.
 *
 * IssueDescription의 EditMode는 useMentionAutocomplete → useUsers(TanStack Query)를
 * 내부 호출하므로, 편집 모드로 진입하는 모든 테스트에 QueryClientProvider가 필요하다.
 * 테스트마다 독립된 QueryClient 인스턴스를 생성해 캐시가 테스트 간 공유되지 않게 한다.
 */
function makeWrapper(): ({ children }: { children: ReactNode }) => JSX.Element {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

describe('IssueDescription', () => {
  const defaultProps = {
    descriptionHtml: '<p>본문 HTML</p>',
    description: '본문 마크다운',
    onSave: vi.fn(),
    isSaving: false,
  }

  // ── Preview 탭 (읽기 모드) ────────────────────────────────────────────────

  it('Preview 탭에서 descriptionHtml을 렌더한다', () => {
    render(<IssueDescription {...defaultProps} />)
    // dangerouslySetInnerHTML으로 삽입된 HTML 내용 확인
    const content = screen.getByTestId('description-preview-content')
    expect(content.innerHTML).toBe('<p>본문 HTML</p>')
  })

  it('description=null이면 descriptionEmpty placeholder를 표시한다', () => {
    render(
      <IssueDescription
        descriptionHtml={null}
        description={null}
        onSave={vi.fn()}
        isSaving={false}
      />,
    )
    expect(screen.getByText('본문이 없습니다.')).toBeInTheDocument()
    // dangerouslySetInnerHTML 분기가 호출되지 않아야 함
    expect(screen.queryByTestId('description-preview-content')).not.toBeInTheDocument()
  })

  it('descriptionHtml=null이면 descriptionEmpty placeholder를 표시한다', () => {
    render(
      <IssueDescription
        descriptionHtml={null}
        description="원본 마크다운"
        onSave={vi.fn()}
        isSaving={false}
      />,
    )
    expect(screen.getByText('본문이 없습니다.')).toBeInTheDocument()
  })

  // ── 편집 모드 진입 ────────────────────────────────────────────────────────

  it('본문 편집 버튼 클릭 시 Write 탭 textarea에 raw description이 표시된다', () => {
    render(<IssueDescription {...defaultProps} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    expect(textarea).toHaveValue('본문 마크다운')
  })

  it('편집 모드에서 Write/Preview 탭이 표시된다', () => {
    render(<IssueDescription {...defaultProps} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    expect(screen.getByRole('tab', { name: '편집' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '미리보기' })).toBeInTheDocument()
  })

  // ── 본문 클릭 진입 (FR-UX-11 F8 Task 3 / FR4) ─────────────────────────────
  //
  // 진입 경로가 늘어난 만큼 **열리면 안 되는 경우**를 함께 고정한다.
  // 특히 텍스트 선택 중 진입은 Jira Cloud 의 미해결 결함(JRA-64389 · JRA-29063)이라
  // 편차 D-2 로 의도적으로 배제했다 — 이 테스트가 그 결함의 복제를 막는 가드다.

  it('본문을 클릭하면 편집 모드로 진입한다', () => {
    render(<IssueDescription {...defaultProps} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByTestId('description-preview-content'))
    expect(screen.getByRole('textbox', { name: '본문 편집' })).toBeInTheDocument()
  })

  it('본문이 비어 있어도 placeholder 클릭으로 편집 모드에 진입한다', () => {
    render(
      <IssueDescription
        descriptionHtml={null}
        description={null}
        onSave={vi.fn()}
        isSaving={false}
      />,
      { wrapper: makeWrapper() },
    )
    fireEvent.click(screen.getByText('본문이 없습니다.'))
    expect(screen.getByRole('textbox', { name: '본문 편집' })).toBeInTheDocument()
  })

  it('텍스트를 선택 중이면 본문 클릭이 편집을 열지 않는다 (편차 D-2)', () => {
    // 드래그로 본문을 복사하려는 상태를 재현 — Selection.isCollapsed=false
    const selectionSpy = vi
      .spyOn(window, 'getSelection')
      .mockReturnValue({ isCollapsed: false } as Selection)
    try {
      render(<IssueDescription {...defaultProps} />, { wrapper: makeWrapper() })
      fireEvent.click(screen.getByTestId('description-preview-content'))
      expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
    } finally {
      // setup.ts에 restoreMocks 설정이 없으므로 전역 spy를 이 테스트 안에서 직접 원복한다
      selectionSpy.mockRestore()
    }
  })

  it('본문 안 링크를 클릭하면 편집이 열리지 않는다', () => {
    render(
      <IssueDescription
        {...defaultProps}
        descriptionHtml='<p><a href="/x">링크</a></p>'
      />,
      { wrapper: makeWrapper() },
    )
    fireEvent.click(screen.getByRole('link', { name: '링크' }))
    expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
  })

  it('수정 권한이 없으면 본문 클릭이 편집을 열지 않는다', () => {
    render(<IssueDescription {...defaultProps} canEdit={false} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByTestId('description-preview-content'))
    expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
  })

  // ── 저장 흐름 ─────────────────────────────────────────────────────────────

  it('저장 버튼 클릭 시 onSave(rawMarkdown)을 호출한다', () => {
    const onSave = vi.fn()
    render(<IssueDescription {...defaultProps} onSave={onSave} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    fireEvent.change(textarea, { target: { value: '수정된 내용' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(onSave).toHaveBeenCalledWith('수정된 내용')
  })

  it('빈 입력 저장 시 onSave("")를 호출한다 (클리어)', () => {
    const onSave = vi.fn()
    render(<IssueDescription {...defaultProps} onSave={onSave} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    fireEvent.change(textarea, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(onSave).toHaveBeenCalledWith('')
  })

  // ── 키보드 저장 (FR-UX-11 F8 Task 4 / FR5) ───────────────────────────────
  //
  // 본문은 여러 줄 마크다운 편집기다. 맨 Enter 를 저장에 쓰면 개행을 할 수 없으므로
  // 저장은 수식키(Ctrl/Cmd)를 요구하고 맨 Enter 는 줄바꿈으로 남긴다.

  it('본문 편집 중 Ctrl+Enter 로 저장한다', () => {
    const onSave = vi.fn()
    render(<IssueDescription {...defaultProps} onSave={onSave} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    fireEvent.change(textarea, { target: { value: '새 본문' } })
    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true })
    expect(onSave).toHaveBeenCalledWith('새 본문')
  })

  it('본문 편집 중 Cmd+Enter(metaKey) 로도 저장한다 (mac)', () => {
    const onSave = vi.fn()
    render(<IssueDescription {...defaultProps} onSave={onSave} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    fireEvent.change(textarea, { target: { value: '맥에서 저장' } })
    fireEvent.keyDown(textarea, { key: 'Enter', metaKey: true })
    expect(onSave).toHaveBeenCalledWith('맥에서 저장')
  })

  it('본문 편집 중 맨 Enter 는 저장하지 않는다 (줄바꿈)', () => {
    const onSave = vi.fn()
    render(<IssueDescription {...defaultProps} onSave={onSave} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    fireEvent.change(textarea, { target: { value: '첫 줄' } })
    fireEvent.keyDown(textarea, { key: 'Enter' })
    expect(onSave).not.toHaveBeenCalled()
  })

  it('한글 IME 조합 확정 중의 Ctrl+Enter 는 저장하지 않는다 (keyCode 229)', () => {
    const onSave = vi.fn()
    render(<IssueDescription {...defaultProps} onSave={onSave} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    fireEvent.change(textarea, { target: { value: '한글' } })
    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true, keyCode: 229 })
    expect(onSave).not.toHaveBeenCalled()
  })

  // ── 취소 흐름 ─────────────────────────────────────────────────────────────

  it('취소 버튼 클릭 시 편집 모드가 닫힌다', () => {
    render(<IssueDescription {...defaultProps} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    expect(screen.getByRole('textbox', { name: '본문 편집' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
  })

  // ── isSaving 상태 ─────────────────────────────────────────────────────────

  it('isSaving=true이면 저장/취소 버튼이 disabled된다', () => {
    render(<IssueDescription {...defaultProps} isSaving={true} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  })

  // ── 보안 NFR1/NFR2: raw description은 표시 경로에 절대 렌더 금지 ─────────

  it('Preview 탭에서 raw description 텍스트가 직접 노출되지 않는다', () => {
    render(
      <IssueDescription
        descriptionHtml="<p>HTML 본문</p>"
        description="raw_마크다운_절대_노출금지"
        onSave={vi.fn()}
        isSaving={false}
      />,
    )
    // 편집 모드가 아닌 상태에서 raw 텍스트가 DOM에 없어야 함
    expect(screen.queryByText('raw_마크다운_절대_노출금지')).not.toBeInTheDocument()
  })

  // ── canEdit 게이트 (FR-PM-02 C1) ─────────────────────────────────────────

  it('canEdit=false이면 편집 버튼이 disabled된다', () => {
    render(<IssueDescription {...defaultProps} canEdit={false} />)
    const editBtn = screen.getByRole('button', { name: '본문 편집' })
    expect(editBtn).toBeDisabled()
  })

  it('canEdit=false이면 편집 버튼에 수정 권한 없음 title이 붙는다', () => {
    render(<IssueDescription {...defaultProps} canEdit={false} />)
    const editBtn = screen.getByRole('button', { name: '본문 편집' })
    expect(editBtn).toHaveAttribute('title', '수정 권한이 없습니다')
  })

  it('canEdit=true(기본값)이면 편집 버튼이 활성화된다', () => {
    render(<IssueDescription {...defaultProps} canEdit={true} />)
    const editBtn = screen.getByRole('button', { name: '본문 편집' })
    expect(editBtn).not.toBeDisabled()
  })

  // ── restrictedFields — 본문 열람 차단 (FR-PM-07 Task 6 보강) ─────────

  it('restrictedFields에 "description"이 포함되면 본문 대신 열람 불가 placeholder가 표시된다', () => {
    render(
      <IssueDescription
        {...defaultProps}
        restrictedFields={['description']}
      />,
    )
    expect(screen.getByTestId('description-restricted')).toBeInTheDocument()
    expect(screen.queryByTestId('description-preview-content')).not.toBeInTheDocument()
    expect(screen.queryByText('본문이 없습니다.')).not.toBeInTheDocument()
  })

  it('restrictedFields에 "description"이 포함되면 편집 버튼이 렌더되지 않는다', () => {
    render(
      <IssueDescription
        {...defaultProps}
        restrictedFields={['description']}
      />,
    )
    expect(screen.queryByRole('button', { name: '본문 편집' })).not.toBeInTheDocument()
  })

  it('restrictedFields가 빈 배열이면 본문이 정상 렌더된다', () => {
    render(<IssueDescription {...defaultProps} restrictedFields={[]} />)
    expect(screen.getByTestId('description-preview-content')).toBeInTheDocument()
    expect(screen.queryByTestId('description-restricted')).not.toBeInTheDocument()
  })

  // ── noneditableFields — 편집 비활성 AND 조합 (FR-PM-07 Task 6 보강) ──

  it('noneditableFields에 "description"이 포함되면 편집 버튼이 disabled된다', () => {
    render(
      <IssueDescription
        {...defaultProps}
        canEdit={true}
        noneditableFields={['description']}
      />,
    )
    expect(screen.getByRole('button', { name: '본문 편집' })).toBeDisabled()
  })

  it('canEdit=true + noneditableFields=[] 이면 편집 버튼이 활성이다', () => {
    render(
      <IssueDescription
        {...defaultProps}
        canEdit={true}
        noneditableFields={[]}
      />,
    )
    expect(screen.getByRole('button', { name: '본문 편집' })).not.toBeDisabled()
  })

  it('canEdit=false + noneditableFields=[] 이면 편집 버튼이 disabled된다 (canEdit이 막음)', () => {
    render(
      <IssueDescription
        {...defaultProps}
        canEdit={false}
        noneditableFields={[]}
      />,
    )
    expect(screen.getByRole('button', { name: '본문 편집' })).toBeDisabled()
  })

  it('canEdit=true + noneditableFields=["description"] 이면 편집 버튼이 disabled된다 (noneditableFields가 막음)', () => {
    render(
      <IssueDescription
        {...defaultProps}
        canEdit={true}
        noneditableFields={['description']}
      />,
    )
    expect(screen.getByRole('button', { name: '본문 편집' })).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 멘션 자동완성 배선 테스트 — FR-MN-02 Task 3
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDescription — 멘션 자동완성 배선', () => {
  const defaultProps = {
    descriptionHtml: '<p>본문 HTML</p>',
    description: '본문 마크다운',
    onSave: vi.fn(),
    isSaving: false,
  }

  /**
   * 편집 모드 진입 헬퍼 — 편집 버튼 클릭 후 textarea를 반환한다.
   * wrapper 포함 렌더가 필요하므로 미리 server.use(userHandlers)를 호출한 상태에서 사용할 것.
   */
  function enterEditMode() {
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    return screen.getByRole('textbox', { name: '본문 편집' }) as HTMLTextAreaElement
  }

  it('@al 입력 후 debounce 완료 시 멘션 드롭다운(role=listbox)이 노출된다', async () => {
    server.use(...userHandlers)
    render(
      <IssueDescription {...defaultProps} />,
      { wrapper: makeWrapper() },
    )

    const textarea = enterEditMode()

    // @al 입력 — selectionStart를 3으로 맞춰 caret 위치를 시뮬레이션한다
    act(() => {
      Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
      fireEvent.change(textarea, { target: { value: '@al' } })
    })

    // debounce(250ms) + useUsers 응답 대기
    await waitFor(() => {
      expect(screen.getByRole('listbox')).toBeInTheDocument()
    }, { timeout: 2000 })
  })

  /**
   * FR-UX-11 F8 Task 4 / E10 — 멘션 팝업이 키를 먼저 소비한다.
   *
   * 드롭다운이 열린 상태의 Enter 는 **후보 선택**이지 저장이 아니다.
   * use-mention-autocomplete 의 onKeyDown 이 preventDefault() 하므로
   * handleEditorKeyDown 은 defaultPrevented 를 보고 손을 뗀다.
   * 이 순서가 깨지면 자동완성 확정이 그대로 저장으로 새는 회귀가 된다.
   */
  it('멘션 팝업이 열린 상태의 Ctrl+Enter 는 저장으로 새지 않는다', async () => {
    server.use(...userHandlers)
    const onSave = vi.fn()
    render(
      <IssueDescription {...defaultProps} onSave={onSave} />,
      { wrapper: makeWrapper() },
    )

    const textarea = enterEditMode()

    act(() => {
      Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
      fireEvent.change(textarea, { target: { value: '@al' } })
    })

    // 드롭다운 노출 대기 — 이 상태에서만 훅이 Enter 를 가로챈다
    await waitFor(() => {
      expect(screen.getByRole('listbox')).toBeInTheDocument()
    }, { timeout: 2000 })

    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true })
    expect(onSave).not.toHaveBeenCalled()
  })

  it('드롭다운 후보 클릭(onMouseDown) 시 textarea 값에 @<username> 공백이 반영된다', async () => {
    server.use(...userHandlers)
    render(
      <IssueDescription {...defaultProps} />,
      { wrapper: makeWrapper() },
    )

    const textarea = enterEditMode()

    act(() => {
      Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
      fireEvent.change(textarea, { target: { value: '@al' } })
    })

    // 드롭다운 노출 대기
    await waitFor(() => {
      expect(screen.getByRole('listbox')).toBeInTheDocument()
    }, { timeout: 2000 })

    // alice 항목 클릭 — onMouseDown으로 선택
    const aliceOption = screen.getByTestId('mention-option-alice')
    fireEvent.mouseDown(aliceOption)

    // textarea 값에 @alice 공백 반영 확인
    await waitFor(() => {
      expect(textarea).toHaveValue('@alice ')
    })
  })

  it('CR2: Preview → Write 복귀 시 멘션 상태가 초기화되어 드롭다운이 재출현하지 않는다', async () => {
    /**
     * Write 탭에서 @al 입력으로 open=true 만든 뒤 Preview로 전환하면
     * 훅의 open 상태가 reset()으로 초기화되어야 한다.
     * Write 탭으로 돌아왔을 때 listbox가 즉시 재출현하면 reset 미적용 증거.
     *
     * 캐시 시드(staleTime: Infinity)를 사용해 Write 복귀 즉시 드롭다운 여부를 동기로 확인한다.
     * reset이 없으면 open=true 잔존 → Write 복귀 시 즉시 listbox 재출현 → 실패.
     */
    server.use(...userHandlers)
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity } },
    })
    const aliceResult = [
      { id: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5', username: 'alice', displayName: '앨리스', email: null as string | null },
    ]

    render(
      <IssueDescription {...defaultProps} />,
      { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider> },
    )

    const textarea = enterEditMode()

    // 캐시 시드 — useUsers('al') 즉시 반환
    act(() => { qc.setQueryData(['users', 'al'], aliceResult) })

    // @al 입력
    act(() => {
      Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
      fireEvent.change(textarea, { target: { value: '@al' } })
    })

    // 드롭다운 노출 대기
    await waitFor(() => {
      expect(screen.getByRole('listbox')).toBeInTheDocument()
    }, { timeout: 2000 })

    // Preview 탭으로 전환 → listbox DOM에서 사라짐(조건부 렌더)
    act(() => {
      fireEvent.click(screen.getByRole('tab', { name: '미리보기' }))
    })
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()

    // Write 탭으로 복귀 — reset이 없으면 open=true 잔존 → listbox 즉시 재출현
    act(() => {
      fireEvent.click(screen.getByRole('tab', { name: '편집' }))
    })

    // reset()이 호출되었다면 open=false → listbox 없음
    // reset() 미호출이라면 open=true 잔존 → candidates 있으면 즉시 재출현 → 실패
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('Escape 키 입력 시 드롭다운이 닫히고 textarea 값은 유지된다', async () => {
    server.use(...userHandlers)
    render(
      <IssueDescription {...defaultProps} />,
      { wrapper: makeWrapper() },
    )

    const textarea = enterEditMode()

    act(() => {
      Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
      fireEvent.change(textarea, { target: { value: '@al' } })
    })

    // 드롭다운 노출 대기
    await waitFor(() => {
      expect(screen.getByRole('listbox')).toBeInTheDocument()
    }, { timeout: 2000 })

    // Escape 키 입력
    fireEvent.keyDown(textarea, { key: 'Escape' })

    // 드롭다운 닫힘 확인
    await waitFor(() => {
      expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    })

    // textarea 값 유지 확인
    expect(textarea).toHaveValue('@al')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// .mention 강조 스타일 검증 — FR-MN-01 D6 Task 2
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDescription — .mention 강조 (FR-MN-01 D6)', () => {
  /** 백엔드가 마크업한 멘션 span을 포함하는 샘플 HTML */
  const mentionHtml = '<p><span class="mention">@alice</span> 확인</p>'
  const props = {
    descriptionHtml: mentionHtml,
    description: '@alice 확인',
    onSave: vi.fn(),
    isSaving: false,
  }

  /**
   * index.css 파일 내용을 직접 읽어 .mention 규칙 존재를 검증한다.
   * jsdom은 CSS를 적용하지 않으므로 computed style 검증 대신 CSS 소스 검사를 사용한다.
   *
   * - import.meta.dirname: 이 테스트 파일의 절대 디렉토리(components/issue) 기준으로
   *   경로를 계산해 process.cwd() 의존을 제거한다.
   * - 블록 주석(/* ... *‌/) 제거 후 .mention { ... } 선언 블록이 실재하는지 확인해
   *   주석 내 .mention 텍스트를 오매칭하지 않는다.
   * - 블록 내 강조 속성(color/background/font-weight) 최소 1개 존재를 추가 단언해
   *   빈 규칙셋이 통과하는 가짜 그린을 차단한다.
   */
  it('index.css에 .mention 강조 스타일 규칙이 정의되어 있다', () => {
    // 테스트 파일(components/issue/) 기준 상대경로로 src/index.css 도달
    const cssPath = resolve(import.meta.dirname, '../../index.css')
    const css = readFileSync(cssPath, 'utf-8')

    // 블록 주석 제거 — /* .mention */ 같은 주석이 단언에 매칭되는 것 방지
    const stripped = css.replace(/\/\*[\s\S]*?\*\//g, '')

    // .mention { ... } 선언 블록 실재 확인
    const ruleMatch = stripped.match(/\.mention\s*\{([^}]+)\}/)
    expect(ruleMatch).not.toBeNull()

    // 강조 속성(color / background-color / font-weight) 최소 1개 포함 확인
    const block = ruleMatch?.[1] ?? ''
    expect(block).toMatch(/\b(?:color|background(?:-color)?|font-weight)\s*:/)
  })

  it('읽기 모드에서 .mention 클래스 요소가 prose 컨테이너 안에 존재한다', () => {
    render(<IssueDescription {...props} />)
    const container = screen.getByTestId('description-preview-content')
    const mentionEl = container.querySelector('.mention')
    expect(mentionEl).toBeInTheDocument()
    expect(mentionEl?.textContent).toBe('@alice')
  })

  it('편집 모드 미리보기 탭에서도 .mention 요소가 prose 컨테이너 안에 존재한다', () => {
    render(<IssueDescription {...props} />, { wrapper: makeWrapper() })
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    fireEvent.click(screen.getByRole('tab', { name: '미리보기' }))
    // 편집 모드 미리보기 컨테이너(.prose)에서 .mention 요소 검색
    const proseDivs = document.querySelectorAll('.prose')
    let mentionEl: Element | null = null
    for (const div of proseDivs) {
      const el = div.querySelector('.mention')
      if (el !== null) { mentionEl = el; break }
    }
    expect(mentionEl).toBeInTheDocument()
    expect(mentionEl?.textContent).toBe('@alice')
  })
})
