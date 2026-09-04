// 공용 리치 텍스트 에디터 판별식 — 툴바 서식 · 폼 단축키 · 저장 포맷 (Jira 패리티 J8)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RichTextEditor } from '../RichTextEditor'
import { editorLabels } from '@/i18n/editor-labels'

/**
 * `RichTextEditor` 판별식.
 *
 * ## 무엇을 재나
 *
 * 툴바 버튼이 **기대 태그를 실제로 만드는지**를 잰다. 「버튼이 렌더된다」만 재면 명령을
 * 잘못 물려도 초록이다 — 사용자는 눌러도 아무 일이 없는 버튼을 만난다.
 *
 * 서식 단축키(⌘B 등)는 TipTap 확장이 소유하므로 여기서 재지 않는다. 대신 이 컴포넌트가
 * **직접 배선한 폼 키 2개**(⌘Enter 저장 · Escape 취소)와 IME 예외를 잰다.
 */

/** 에디터 본문 영역(contenteditable)을 잡는다. */
function getBody(): HTMLElement {
  return screen.getByRole('textbox', { name: editorLabels.editorLabel })
}

function renderEditor(overrides: Partial<Parameters<typeof RichTextEditor>[0]> = {}) {
  const onChange = vi.fn()
  const onSubmit = vi.fn()
  const onCancel = vi.fn()
  const { container } = render(
    <RichTextEditor
      initialHtml="<p>처음 내용</p>"
      onChange={onChange}
      onSubmit={onSubmit}
      onCancel={onCancel}
      {...overrides}
    />,
  )
  return { onChange, onSubmit, onCancel, container }
}

describe('RichTextEditor — 툴바 서식 (J8)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('초기 HTML 을 그대로 렌더한다', async () => {
    renderEditor()
    await waitFor(() => { expect(getBody()).toHaveTextContent('처음 내용') })
  })

  // ★표로 전수를 돈다 — 서식이 하나 늘면 여기 한 줄이 늘고, 빠뜨리면 그 버튼이
  //   명령을 잘못 물려도 아무도 모른다.
  const marks: ReadonlyArray<readonly [string, string]> = [
    [editorLabels.bold, 'strong'],
    [editorLabels.italic, 'em'],
    [editorLabels.underline, 'u'],
    [editorLabels.strike, 's'],
    [editorLabels.code, 'code'],
  ]

  marks.forEach(([label, tag]) => {
    it(`${label} 버튼이 <${tag}> 를 만든다`, async () => {
      const user = userEvent.setup()
      const { onChange } = renderEditor({ initialHtml: '<p>본문</p>' })

      const body = getBody()
      await user.tripleClick(body)
      await user.click(screen.getByRole('button', { name: label }))

      await waitFor(() => {
        const html = onChange.mock.calls.at(-1)?.[0] as string | undefined
        expect(html ?? '').toContain(`<${tag}>`)
      })
    })
  })

  const blocks: ReadonlyArray<readonly [string, string]> = [
    [editorLabels.bulletList, '<ul'],
    [editorLabels.orderedList, '<ol'],
    [editorLabels.blockquote, '<blockquote'],
    [editorLabels.codeBlock, '<pre'],
    [editorLabels.horizontalRule, '<hr'],
    [editorLabels.table, '<table'],
  ]

  blocks.forEach(([label, marker]) => {
    it(`${label} 버튼이 ${marker} 를 만든다`, async () => {
      const user = userEvent.setup()
      const { onChange } = renderEditor({ initialHtml: '<p>본문</p>' })

      await user.click(getBody())
      await user.click(screen.getByRole('button', { name: label }))

      await waitFor(() => {
        const html = onChange.mock.calls.at(-1)?.[0] as string | undefined
        expect(html ?? '').toContain(marker)
      })
    })
  })

  it('제목 드롭다운이 h1~h6 을 전부 제공한다 — 서버 allowlist 와 같은 범위', async () => {
    renderEditor()
    const select = screen.getByRole('combobox', { name: editorLabels.heading })

    // 본문 + H1~H6 = 7개. 하나라도 빠지면 서버가 허용하는 태그를 넣을 방법이 없어진다.
    expect(select.querySelectorAll('option')).toHaveLength(7)
  })

  it('제목 드롭다운에서 H2 를 고르면 <h2> 가 된다', async () => {
    const user = userEvent.setup()
    const { onChange } = renderEditor({ initialHtml: '<p>제목이 될 줄</p>' })

    await user.click(getBody())
    await user.selectOptions(screen.getByRole('combobox', { name: editorLabels.heading }), '2')

    await waitFor(() => {
      const html = onChange.mock.calls.at(-1)?.[0] as string | undefined
      expect(html ?? '').toContain('<h2>')
    })
  })

  it('서식 버튼은 활성 상태를 aria-pressed 로 노출한다', async () => {
    const user = userEvent.setup()
    renderEditor({ initialHtml: '<p><strong>굵은 글자</strong></p>' })

    await user.click(getBody())

    await waitFor(() => {
      expect(screen.getByRole('button', { name: editorLabels.bold })).toHaveAttribute('aria-pressed', 'true')
    })
    expect(screen.getByRole('button', { name: editorLabels.italic })).toHaveAttribute('aria-pressed', 'false')
  })

  it('이미지 버튼은 이슈 키가 있을 때만 그린다 — 생성 폼에는 첨부할 곳이 없다', () => {
    renderEditor()
    expect(screen.queryByRole('button', { name: editorLabels.image })).not.toBeInTheDocument()
  })

  it('imageIssueKey 를 주면 이미지 버튼과 파일 입력이 생긴다 (J7)', () => {
    const { container } = renderEditor({ imageIssueKey: 'ATLAS-1' })

    expect(screen.getByRole('button', { name: editorLabels.image })).toBeInTheDocument()
    // 실제 업로드 입구가 함께 있어야 버튼이 무동작이 아니다.
    expect(container.querySelector('input[type="file"]')).toBeInTheDocument()
  })
})

describe('RichTextEditor — 폼 단축키', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('⌘+Enter 는 저장을 부른다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderEditor()

    await user.click(getBody())
    await user.keyboard('{Meta>}{Enter}{/Meta}')

    expect(onSubmit).toHaveBeenCalledOnce()
  })

  it('Ctrl+Enter 도 저장을 부른다 — Windows/Linux', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderEditor()

    await user.click(getBody())
    await user.keyboard('{Control>}{Enter}{/Control}')

    expect(onSubmit).toHaveBeenCalledOnce()
  })

  it('맨 Enter 는 저장하지 않는다 — 줄바꿈이다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderEditor()

    await user.click(getBody())
    await user.keyboard('{Enter}')

    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('Escape 는 취소를 부른다', async () => {
    const user = userEvent.setup()
    const { onCancel } = renderEditor()

    await user.click(getBody())
    await user.keyboard('{Escape}')

    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('IME 조합 중 Escape 는 취소하지 않는다 — 한글 조합 취소를 빼앗지 않는다', async () => {
    const user = userEvent.setup()
    const { onCancel } = renderEditor()

    await user.click(getBody())
    // userEvent 는 isComposing 을 만들지 못하므로 이벤트를 직접 만든다.
    const event = new KeyboardEvent('keydown', {
      key: 'Escape',
      bubbles: true,
      cancelable: true,
    })
    Object.defineProperty(event, 'isComposing', { value: true })
    getBody().dispatchEvent(event)

    expect(onCancel).not.toHaveBeenCalled()
  })
})

describe('RichTextEditor — 저장 포맷·잠금', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('onChange 가 마크다운이 아니라 HTML 을 넘긴다', async () => {
    // ★jsdom 에는 레이아웃이 없어 contenteditable 타이핑이 재현되지 않는다(ProseMirror 가
    //   좌표를 쓴다). 그래서 「타이핑」이 아니라 툴바 명령으로 변경을 일으키고, 넘어온 값이
    //   HTML 인지를 본다. 실제 타이핑 경로는 e2e 의 몫이다.
    const user = userEvent.setup()
    const { onChange } = renderEditor({ initialHtml: '<p>본문</p>' })

    await user.click(getBody())
    await user.click(screen.getByRole('button', { name: editorLabels.bulletList }))

    await waitFor(() => {
      const html = onChange.mock.calls.at(-1)?.[0] as string | undefined
      expect(html ?? '').toContain('<li>')
      // 마크다운 왕복이 없다는 표지 — 리스트 마커가 `-` 가 아니라 태그다.
      expect(html ?? '').not.toMatch(/^- /m)
    })
  })

  it('editable=false 면 본문이 편집 불가로 잠긴다 — 저장 중', async () => {
    renderEditor({ initialHtml: '<p>고정</p>', editable: false })

    await waitFor(() => {
      expect(getBody()).toHaveAttribute('contenteditable', 'false')
    })
  })

  it('editable=true 면 편집 가능하다 — 위 잠금 단언의 비-공허 짝', async () => {
    renderEditor({ initialHtml: '<p>수정 가능</p>' })

    await waitFor(() => {
      expect(getBody()).toHaveAttribute('contenteditable', 'true')
    })
  })

  it('ariaLabel 로 접근성 이름을 바꿀 수 있다 — 한 화면에 본문·댓글 둘이 있다', async () => {
    render(<RichTextEditor initialHtml="<p>x</p>" onChange={vi.fn()} ariaLabel="댓글 작성" />)

    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: '댓글 작성' })).toBeInTheDocument()
    })
  })
})
