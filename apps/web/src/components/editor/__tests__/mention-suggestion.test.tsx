// 멘션 @ 후보 판별식 — 좌표 비의존 층(MentionList 키 위임 · Escape 인계) 회귀 가드 (FR-MN-02)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { MentionList } from '../MentionList'
import type { MentionListHandle } from '../MentionList'
import { RichTextEditor } from '../RichTextEditor'
import { isMentionSuggestionActive } from '../mention-extension'
import { editorLabels } from '@/i18n/editor-labels'
import type { UserSummary } from '@/api/users'

// ★`isMentionSuggestionActive` 만 모킹한다. 이 함수는 suggestion 플러그인 상태를 읽는
//   **신호원**이고, 판정 대상은 그 신호를 받는 `RichTextEditor.handleKeyDown` 의 Escape
//   분기다. 신호원을 고정해야 좌표 없이 그 분기만 잰다 — 이유는 아래 describe 주석 참조.
vi.mock('../mention-extension', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../mention-extension')>()
  return { ...actual, isMentionSuggestionActive: vi.fn(() => false) }
})
const mockSuggestionActive = isMentionSuggestionActive as unknown as Mock

/**
 * 멘션 후보 층 판별식.
 *
 * ## 왜 이 층인가
 *
 * #448 이전까지 이 기능의 증인은 e2e 4건뿐이었다. jsdom 에서 suggestion 팝업이 좌표
 * (`clientRect`)에 기대 재현되지 않는다는 것이 그때 확인됐다. 그래서 **좌표에 의존하지 않는
 * 두 층**만 직접 잡는다.
 *
 * 1. `MentionList` 의 키 위임 — 순수 컴포넌트 + imperative handle 이라 좌표가 없다
 * 2. `Escape` 인계 — 후보가 떠 있을 때 `RichTextEditor` 가 Escape 를 **삼키지 않는지**
 *
 * ## 2번이 왜 중요한가 (#448 실측 결함)
 *
 * ProseMirror 의 `someProp` 은 view props(`editorProps.handleKeyDown`)를 플러그인보다
 * **먼저** 훑는다. 에디터가 Escape 를 먼저 받아 「취소」로 처리해 버리면 suggestion 의
 * `onKeyDown` 이 영영 불리지 않는다. 그 결과 **후보를 닫으려던 Escape 가 작성 중인 본문을
 * 통째로 버렸다.** 되돌릴 화면이 없는 손실이다.
 */

const CANDIDATES: UserSummary[] = [
  { id: 'u1', username: 'alice', displayName: '앨리스', email: 'alice@bts.test' },
  { id: 'u2', username: 'bob', displayName: '밥', email: 'bob@bts.test' },
  { id: 'u3', username: 'carol', displayName: null, email: 'carol@bts.test' },
] as unknown as UserSummary[]

// ─────────────────────────────────────────────────────────────────────────────
// 1. MentionList — 키 위임 (좌표 비의존)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `MentionList` 를 ref 와 함께 렌더한다.
 *
 * TipTap 의 `SuggestionProps` 는 필드가 많지만 이 컴포넌트가 읽는 것은 `items` · `command`
 * 둘뿐이다. 나머지를 채우면 「무엇을 실제로 쓰는지」가 판별식에서 흐려진다.
 */
function renderList(items: UserSummary[]) {
  const command = vi.fn()
  const ref = createRef<MentionListHandle>()
  const props = { items, command } as unknown as Parameters<typeof MentionList>[0]
  render(<MentionList ref={ref} {...props} />)
  return { command, ref }
}

/** 현재 활성 후보(aria-selected="true")의 텍스트 — 좌표가 아니라 ARIA 로 읽는다. */
function activeOptionText(): string {
  const options = screen.getAllByRole('option')
  const active = options.find((o) => o.getAttribute('aria-selected') === 'true')
  return active?.textContent ?? ''
}

function press(ref: React.RefObject<MentionListHandle | null>, key: string): boolean {
  return ref.current?.onKeyDown(new KeyboardEvent('keydown', { key })) ?? false
}

describe('MentionList — 후보 표시와 키 위임 (FR-MN-02)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('후보를 listbox 로 그리고 첫 항목을 활성으로 둔다', () => {
    renderList(CANDIDATES)

    expect(screen.getByRole('listbox', { name: '멘션 사용자 자동완성' })).toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(3)
    expect(activeOptionText()).toContain('앨리스')
  })

  it('displayName 이 없으면 username 으로 표시한다', () => {
    renderList(CANDIDATES)
    expect(screen.getByTestId('mention-option-carol')).toHaveTextContent('carol')
  })

  it('후보가 0건이면 아무것도 그리지 않는다 — 빈 팝업이 뜨지 않는다', () => {
    renderList([])
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('후보가 0건이면 키를 가로채지 않는다 — 에디터가 그대로 받는다', () => {
    const { ref } = renderList([])
    expect(press(ref, 'ArrowDown')).toBe(false)
    expect(press(ref, 'Enter')).toBe(false)
  })

  it('ArrowDown 이 다음 후보로 내려가고 끝에서 처음으로 순환한다', async () => {
    const { ref } = renderList(CANDIDATES)

    expect(press(ref, 'ArrowDown')).toBe(true)
    await waitFor(() => { expect(activeOptionText()).toContain('밥') })

    press(ref, 'ArrowDown')
    await waitFor(() => { expect(activeOptionText()).toContain('carol') })

    press(ref, 'ArrowDown')
    await waitFor(() => { expect(activeOptionText()).toContain('앨리스') })
  })

  it('ArrowUp 이 첫 후보에서 마지막으로 역순환한다', async () => {
    const { ref } = renderList(CANDIDATES)

    expect(press(ref, 'ArrowUp')).toBe(true)
    await waitFor(() => { expect(activeOptionText()).toContain('carol') })
  })

  it('Enter 는 활성 후보를 username 으로 확정한다 — 서버 마크업과 같은 형태', async () => {
    const { ref, command } = renderList(CANDIDATES)

    press(ref, 'ArrowDown')
    await waitFor(() => { expect(activeOptionText()).toContain('밥') })
    expect(press(ref, 'Enter')).toBe(true)

    expect(command).toHaveBeenCalledWith({ id: 'bob', label: '밥' })
  })

  it('Tab 도 확정으로 받는다 — textarea 시절 관례를 보존한다', () => {
    const { ref, command } = renderList(CANDIDATES)

    expect(press(ref, 'Tab')).toBe(true)
    expect(command).toHaveBeenCalledWith({ id: 'alice', label: '앨리스' })
  })

  it('displayName 이 없는 후보는 label 도 username 이다', () => {
    const { ref, command } = renderList([CANDIDATES[2]!])

    press(ref, 'Enter')

    expect(command).toHaveBeenCalledWith({ id: 'carol', label: 'carol' })
  })

  it('그 밖의 키는 가로채지 않는다 — 글자 입력이 에디터로 흘러야 한다', () => {
    const { ref } = renderList(CANDIDATES)
    expect(press(ref, 'a')).toBe(false)
    expect(press(ref, 'Escape')).toBe(false)
  })

  it('마우스로 고르면 그 후보가 확정된다', async () => {
    const user = userEvent.setup()
    const { command } = renderList(CANDIDATES)

    await user.click(screen.getByTestId('mention-option-bob'))

    expect(command).toHaveBeenCalledWith({ id: 'bob', label: '밥' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 2. Escape 인계 — #448 결함 회귀 가드
// ─────────────────────────────────────────────────────────────────────────────

describe('RichTextEditor — 멘션 후보가 떠 있을 때 Escape 인계 (#448 회귀 가드)', () => {
  // ★왜 팝업을 실제로 띄우지 않고 신호를 모킹하나.
  //
  // suggestion 팝업은 jsdom 에서 재현되지 않는다. #448 이 그것을 기록했고, 이번에 좌표 API
  // (`getClientRects`·`getBoundingClientRect`)를 Text/Element/Range 에 채워 넣고도 팝업이
  // 뜨지 않는 것을 재확인했다(2회 시도). 그 층의 증인은 e2e 4건이 계속 맡는다
  // (`issue-mention-autocomplete` S1~S4).
  //
  // 그래서 **결함이 실제로 났던 한 분기**를 직접 겨눈다. #448 의 결함은 팝업 렌더가 아니라
  // `RichTextEditor.handleKeyDown` 의 Escape 처리였다 — ProseMirror `someProp` 이 view props 를
  // 플러그인보다 먼저 훑기 때문에, 여기서 Escape 를 삼키면 suggestion 의 `onKeyDown` 이 영영
  // 불리지 않고 **작성 중인 본문이 통째로 날아갔다**.
  //
  // 판정 대상은 「후보가 떠 있다는 신호를 받았을 때 취소로 처리하지 않는가」다. 그 신호를
  // 만드는 층(`isMentionSuggestionActive`)을 모킹하면 좌표 없이 그 분기만 정확히 잰다.
  // 분기를 지우면 red 가 된다 — 이 판별식이 지키는 것이 바로 그 줄이다.
  beforeEach(() => {
    vi.clearAllMocks()
    mockSuggestionActive.mockReturnValue(false)
  })

  function renderEditor() {
    const onCancel = vi.fn()
    const onChange = vi.fn()
    render(<RichTextEditor initialHtml="<p>쓰던 글</p>" onChange={onChange} onCancel={onCancel} />)
    return { onCancel, onChange }
  }

  function body(): HTMLElement {
    return screen.getByRole('textbox', { name: editorLabels.editorLabel })
  }

  it('멘션 후보가 떠 있으면 Escape 가 편집을 취소하지 않는다 — 작성분을 버리지 않는다', async () => {
    const user = userEvent.setup()
    mockSuggestionActive.mockReturnValue(true)
    const { onCancel } = renderEditor()

    await user.click(body())
    await user.keyboard('{Escape}')

    expect(onCancel).not.toHaveBeenCalled()
    // 본문도 그대로다 — 취소가 안 불렸다는 것과 별개로 실제 손실이 없음을 확인한다.
    expect(body()).toHaveTextContent('쓰던 글')
  })

  it('후보가 없으면 Escape 는 정상 취소한다 — 위 단언의 비-공허 짝', async () => {
    const user = userEvent.setup()
    mockSuggestionActive.mockReturnValue(false)
    const { onCancel } = renderEditor()

    await user.click(body())
    await user.keyboard('{Escape}')

    await waitFor(() => { expect(onCancel).toHaveBeenCalledOnce() })
  })

  it('후보가 떠 있어도 ⌘+Enter 저장은 그대로 동작한다 — 인계가 다른 키를 죽이지 않는다', async () => {
    const user = userEvent.setup()
    mockSuggestionActive.mockReturnValue(true)
    const onSubmit = vi.fn()
    render(
      <RichTextEditor
        initialHtml="<p>쓰던 글</p>"
        onChange={vi.fn()}
        onCancel={vi.fn()}
        onSubmit={onSubmit}
      />,
    )

    await user.click(body())
    await user.keyboard('{Meta>}{Enter}{/Meta}')

    await waitFor(() => { expect(onSubmit).toHaveBeenCalledOnce() })
  })
})
