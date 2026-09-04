// IssueDescription 단위 테스트 — 읽기 진입 · 저장/취소 · 작성분 폐기 확인 · 필드 권한
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { issueDetailStrings } from '@/i18n/ko'
import { editorLabels } from '@/i18n/editor-labels'
import { IssueDescription } from './IssueDescription'
import { DESCRIPTION_MAX_LENGTH } from '@/lib/issue-text-constraints'

/**
 * `IssueDescription` 판별식.
 *
 * ## 무엇을 재고 무엇을 안 재나
 *
 * 2026-09-04 WYSIWYG 전환으로 **서식·단축키·저장 포맷은 `RichTextEditor` 의 몫**이 됐다.
 * 그쪽 판별식(`components/editor/__tests__/RichTextEditor.test.tsx`)이 26건으로 덮으므로
 * 여기서 다시 재지 않는다 — 두 벌로 재면 한쪽만 고쳐도 다른 쪽이 초록이라 계약이 갈라진다.
 *
 * 여기 남는 것은 `IssueDescription` **고유**의 계약이다.
 *
 * - 읽기 모드 진입 규칙(클릭 · 선택 중 미진입 · 링크 우선 · 권한)
 * - 작성분 폐기 확인 패널(편차 D-1) 전체
 * - 필드 권한 3종(`canEdit` · `restrictedFields` · `noneditableFields`)
 *
 * ## 사라진 테스트
 *
 * Write/Preview 탭 · textarea 포커스/커서 · IME keyCode 229 · Ctrl+Enter 저장 — 전부
 * 그 대상이 없어졌거나 `RichTextEditor` 로 옮겨갔다. 멘션 배선 테스트도 뺐다(TipTap Mention
 * 이식은 후속 작업이며, 그때 그쪽 판별식으로 되살린다).
 *
 * ## jsdom 제약
 *
 * ProseMirror 는 좌표에 기대므로 jsdom 에서 **타이핑이 재현되지 않는다**. 초안을 바꿔야 하는
 * 테스트는 툴바 버튼으로 변경을 일으킨다 — 사용자 조작이라는 점은 같고, 재현 가능하다.
 */

const HTML_BODY = '<p>본문 HTML</p>'

function baseProps() {
  return {
    descriptionHtml: HTML_BODY,
    // 본문 이미지의 첨부 참조를 내려받고 붙여넣은 이미지를 매다는 대상 (J7).
    issueKey: 'ATLAS-1',
    onSave: vi.fn(),
    isSaving: false,
  }
}

/** 편집 모드로 들어간 뒤 에디터 본문을 돌려준다. */
async function enterEditMode(user: ReturnType<typeof userEvent.setup>): Promise<HTMLElement> {
  await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionEditButton }))
  return await screen.findByRole('textbox', { name: issueDetailStrings.descriptionEditLabel })
}

/** 초안을 실제로 바꾼다 — 툴바 글머리 목록 토글. jsdom 에서 타이핑이 안 되기 때문이다. */
async function mutateDraft(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByRole('button', { name: editorLabels.bulletList }))
}

describe('IssueDescription — 읽기 모드', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('descriptionHtml 을 렌더한다', () => {
    render(<IssueDescription {...baseProps()} />)
    expect(screen.getByTestId('description-body')).toHaveTextContent('본문 HTML')
  })

  it('descriptionHtml=null 이면 placeholder 를 표시한다', () => {
    render(<IssueDescription {...baseProps()} descriptionHtml={null} />)
    expect(screen.getByTestId('description-empty')).toHaveTextContent(issueDetailStrings.descriptionEmpty)
  })

  it('본문을 클릭하면 편집 모드로 진입한다', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await user.click(screen.getByTestId('description-body'))

    expect(
      await screen.findByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).toBeInTheDocument()
  })

  it('본문이 비어 있어도 placeholder 클릭으로 진입한다', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} descriptionHtml={null} />)

    await user.click(screen.getByTestId('description-empty'))

    expect(
      await screen.findByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).toBeInTheDocument()
  })

  it('텍스트를 선택 중이면 본문 클릭이 편집을 열지 않는다 (편차 D-2)', () => {
    render(<IssueDescription {...baseProps()} />)
    // 드래그로 텍스트를 고르는 중에 편집이 열리면 선택이 날아간다.
    vi.spyOn(window, 'getSelection').mockReturnValue({
      toString: () => '고른 글자',
    } as unknown as Selection)

    fireEvent.click(screen.getByTestId('description-body'))

    expect(
      screen.queryByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).not.toBeInTheDocument()
  })

  it('본문 안 링크를 클릭하면 편집이 열리지 않는다', () => {
    render(
      <IssueDescription
        {...baseProps()}
        descriptionHtml='<p><a href="https://example.test">링크</a></p>'
      />,
    )

    fireEvent.click(screen.getByRole('link', { name: '링크' }))

    expect(
      screen.queryByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).not.toBeInTheDocument()
  })

  it('수정 권한이 없으면 본문 클릭이 편집을 열지 않는다', () => {
    render(<IssueDescription {...baseProps()} canEdit={false} />)

    fireEvent.click(screen.getByTestId('description-body'))

    expect(
      screen.queryByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).not.toBeInTheDocument()
  })
})

describe('IssueDescription — 저장', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('저장 버튼이 onSave 에 **HTML** 을 넘긴다 — 마크다운이 아니다', async () => {
    const user = userEvent.setup()
    const props = baseProps()
    render(<IssueDescription {...props} />)

    await enterEditMode(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionSaveButton }))

    expect(props.onSave).toHaveBeenCalledOnce()
    const saved = props.onSave.mock.calls[0]?.[0] as string
    expect(saved).toContain('<p>')
    expect(saved).toContain('본문 HTML')
  })

  it('isSaving=true 이면 저장/취소 버튼이 잠긴다', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    rerender(<IssueDescription {...baseProps()} isSaving />)

    expect(screen.getByRole('button', { name: issueDetailStrings.descriptionSaveButton })).toBeDisabled()
    expect(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton })).toBeDisabled()
  })
})

describe('IssueDescription — 작성분 폐기 확인 (편차 D-1)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('변경분이 없으면 취소가 즉시 편집을 닫는다 (S8)', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))

    expect(
      screen.queryByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).not.toBeInTheDocument()
  })

  it('변경분이 있으면 취소가 확인을 먼저 띄운다 (S7)', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    await mutateDraft(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      issueDetailStrings.descriptionDiscardConfirm,
    )
    // 아직 편집은 열려 있다 — 확인 없이 버리지 않는다.
    expect(
      screen.getByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).toBeInTheDocument()
  })

  it('계속 편집을 고르면 편집 모드가 유지된다', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    await mutateDraft(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))
    await user.click(
      await screen.findByRole('button', { name: issueDetailStrings.descriptionDiscardCancelButton }),
    )

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(
      screen.getByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).toBeInTheDocument()
  })

  it('편집 그만두기를 고르면 편집이 닫힌다', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    await mutateDraft(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))
    await user.click(
      await screen.findByRole('button', { name: issueDetailStrings.descriptionDiscardConfirmButton }),
    )

    expect(
      screen.queryByRole('textbox', { name: issueDetailStrings.descriptionEditLabel }),
    ).not.toBeInTheDocument()
  })

  it('확인 패널이 뜬 동안 상단 저장·취소가 잠긴다 (리뷰 C-1)', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    await mutateDraft(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: issueDetailStrings.descriptionSaveButton }),
      ).toBeDisabled()
    })
    expect(
      screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }),
    ).toBeDisabled()
  })

  it('계속 편집으로 패널을 닫으면 상단 버튼이 다시 활성이다 — 위 잠금의 비-공허 짝', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    await mutateDraft(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))
    await user.click(
      await screen.findByRole('button', { name: issueDetailStrings.descriptionDiscardCancelButton }),
    )

    expect(
      screen.getByRole('button', { name: issueDetailStrings.descriptionSaveButton }),
    ).toBeEnabled()
  })

  it('확인 패널이 뜨면 포커스가 계속 편집 버튼으로 간다 (R-2·R-3)', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    await mutateDraft(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))

    const keepEditing = await screen.findByRole('button', {
      name: issueDetailStrings.descriptionDiscardCancelButton,
    })
    await waitFor(() => { expect(keepEditing).toHaveFocus() })
  })

  it('확인 패널의 Escape 는 패널만 닫고 위로 새지 않는다', async () => {
    const user = userEvent.setup()
    // ★전역 Escape 리스너(usePaneEscapeClose)가 pane 을 닫아 작성분이 날아가는 것을 막는
    //   preventDefault 계약. 이것이 없으면 확인 패널이 지키기로 한 바로 그것을 못 지킨다.
    const onDocumentEscape = vi.fn()
    document.addEventListener('keydown', (e) => { if (!e.defaultPrevented) onDocumentEscape() })

    render(<IssueDescription {...baseProps()} />)
    await enterEditMode(user)
    await mutateDraft(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))

    const keepEditing = await screen.findByRole('button', {
      name: issueDetailStrings.descriptionDiscardCancelButton,
    })
    onDocumentEscape.mockClear()
    fireEvent.keyDown(keepEditing, { key: 'Escape' })

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(onDocumentEscape).not.toHaveBeenCalled()
  })

  it('확인 패널이 떠도 취소·저장 문자열 버튼이 둘 이상 생기지 않는다 (리뷰 F-2)', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)

    await enterEditMode(user)
    await mutateDraft(user)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.descriptionCancelButton }))
    await screen.findByRole('alert')

    // e2e strict mode violation 방지 — 같은 이름의 버튼이 둘이면 셀렉터가 터진다.
    expect(
      screen.getAllByRole('button', { name: issueDetailStrings.descriptionCancelButton }),
    ).toHaveLength(1)
    expect(
      screen.getAllByRole('button', { name: issueDetailStrings.descriptionSaveButton }),
    ).toHaveLength(1)
  })
})

describe('IssueDescription — 필드 권한', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('canEdit=false 면 편집 버튼이 비활성이다', () => {
    render(<IssueDescription {...baseProps()} canEdit={false} />)
    expect(
      screen.getByRole('button', { name: issueDetailStrings.descriptionEditButton }),
    ).toBeDisabled()
  })

  it('canEdit=true(기본값)이면 편집 버튼이 활성이다', () => {
    render(<IssueDescription {...baseProps()} />)
    expect(
      screen.getByRole('button', { name: issueDetailStrings.descriptionEditButton }),
    ).toBeEnabled()
  })

  it('restrictedFields 에 description 이 있으면 열람 불가 placeholder 를 표시한다 (FR-PM-07 §3.1)', () => {
    render(<IssueDescription {...baseProps()} restrictedFields={['description']} />)

    expect(screen.getByTestId('description-restricted')).toHaveTextContent(
      issueDetailStrings.descriptionRestricted,
    )
    // 본문도 편집 버튼도 없다 — 열람 자체가 막힌 상태다.
    expect(screen.queryByTestId('description-body')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: issueDetailStrings.descriptionEditButton }),
    ).not.toBeInTheDocument()
  })

  it('restrictedFields 가 빈 배열이면 본문이 정상 렌더된다 — 위 차단의 비-공허 짝', () => {
    render(<IssueDescription {...baseProps()} restrictedFields={[]} />)
    expect(screen.getByTestId('description-body')).toBeInTheDocument()
  })

  it('noneditableFields 에 description 이 있으면 편집 버튼이 비활성이다 (FR-PM-07 §3.2)', () => {
    render(<IssueDescription {...baseProps()} noneditableFields={['description']} />)
    expect(
      screen.getByRole('button', { name: issueDetailStrings.descriptionEditButton }),
    ).toBeDisabled()
  })

  it('canEdit=true + noneditableFields=[] 이면 활성이다 — AND 조합의 비-공허 짝', () => {
    render(<IssueDescription {...baseProps()} canEdit noneditableFields={[]} />)
    expect(
      screen.getByRole('button', { name: issueDetailStrings.descriptionEditButton }),
    ).toBeEnabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 길이 카운터 · 상한 잠금
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 상한 배선 판별식.
 *
 * ## 왜 초기 HTML 로 길이를 만드나
 *
 * jsdom 에서 ProseMirror 타이핑이 재현되지 않는다(이 파일 머리 §jsdom 제약). 편집 진입 시
 * `draftHtml` 이 `descriptionHtml` 로 seed 되므로, 긴 본문을 넘겨 렌더하면 타이핑 없이
 * 초과 상태를 만들 수 있다.
 *
 * ## 왜 HTML 길이인가
 *
 * 서버 `UpdateIssueRequest` 가 `description` 과 `descriptionHtml` **양쪽**에
 * `@Size(max = DESCRIPTION_MAX)` 를 건다. 프론트가 보내는 것은 HTML 이므로 재야 할 것도
 * HTML 이다. 보이는 글자 수를 세면 서식이 많은 본문에서 카운터가 거짓말을 한다.
 */
describe('IssueDescription — 길이 카운터·상한 잠금', () => {
  beforeEach(() => { vi.clearAllMocks() })

  /** 지정한 HTML 길이를 갖는 본문을 만든다. */
  function htmlOfLength(length: number): string {
    const wrapper = '<p></p>'
    return `<p>${'가'.repeat(Math.max(0, length - wrapper.length))}</p>`
  }

  it('평범한 길이에서는 카운터를 띄우지 않는다 — 숫자가 늘 붙어 있으면 신호가 죽는다', async () => {
    const user = userEvent.setup()
    render(<IssueDescription {...baseProps()} />)
    await enterEditMode(user)

    expect(screen.queryByTestId('description-length-counter')).not.toBeInTheDocument()
  })

  it('상한을 넘으면 카운터가 나타나고 저장 버튼이 잠긴다', async () => {
    const user = userEvent.setup()
    render(
      <IssueDescription
        {...baseProps()}
        descriptionHtml={htmlOfLength(DESCRIPTION_MAX_LENGTH + 10)}
      />,
    )
    await enterEditMode(user)

    expect(screen.getByTestId('description-length-counter')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: issueDetailStrings.descriptionSaveButton }),
    ).toBeDisabled()
  })

  it('상한 이하이면 저장 버튼이 열려 있다 — 위 단언의 비-공허 짝', async () => {
    const user = userEvent.setup()
    render(
      <IssueDescription {...baseProps()} descriptionHtml={htmlOfLength(DESCRIPTION_MAX_LENGTH)} />,
    )
    await enterEditMode(user)

    // 임계(90%)는 넘었으므로 카운터는 보이고, 상한은 안 넘었으므로 저장은 열려 있다.
    expect(screen.getByTestId('description-length-counter')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: issueDetailStrings.descriptionSaveButton }),
    ).toBeEnabled()
  })

  it('초과 상태에서는 저장을 눌러도 onSave 가 불리지 않는다', async () => {
    const user = userEvent.setup()
    const props = baseProps()
    render(
      <IssueDescription {...props} descriptionHtml={htmlOfLength(DESCRIPTION_MAX_LENGTH + 10)} />,
    )
    await enterEditMode(user)

    await user.click(
      screen.getByRole('button', { name: issueDetailStrings.descriptionSaveButton }),
    )

    expect(props.onSave).not.toHaveBeenCalled()
  })

  it('초과 상태에서는 ⌘+Enter 로도 저장되지 않는다 — 버튼만 막으면 우회된다', async () => {
    const user = userEvent.setup()
    const props = baseProps()
    render(
      <IssueDescription {...props} descriptionHtml={htmlOfLength(DESCRIPTION_MAX_LENGTH + 10)} />,
    )
    const body = await enterEditMode(user)

    await user.click(body)
    await user.keyboard('{Meta>}{Enter}{/Meta}')

    expect(props.onSave).not.toHaveBeenCalled()
  })

  it('상한 이하에서는 ⌘+Enter 가 정상 저장한다 — 위 단언의 비-공허 짝', async () => {
    const user = userEvent.setup()
    const props = baseProps()
    render(
      <IssueDescription {...props} descriptionHtml={htmlOfLength(DESCRIPTION_MAX_LENGTH)} />,
    )
    const body = await enterEditMode(user)

    await user.click(body)
    await user.keyboard('{Meta>}{Enter}{/Meta}')

    await waitFor(() => { expect(props.onSave).toHaveBeenCalledOnce() })
  })
})
