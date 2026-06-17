// IssueDescription 컴포넌트 단위 테스트 — Write/Preview 탭, 저장/취소 흐름 검증
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
