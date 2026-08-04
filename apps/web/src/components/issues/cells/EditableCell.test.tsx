// 편집 가능 셀 공통 래퍼 테스트 — 전파 차단 · 지연 마운트 · 어포던스 토큰 · 단축키 차단 (FR-UX-11 F9 FR2·FR3·FR12·E10)
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Button } from '@/components/ui/button'
import { EditableCell } from './EditableCell'

describe('EditableCell', () => {
  it('셀 클릭이 행 클릭 핸들러로 전파되지 않는다 (FR3)', async () => {
    const onRowClick = vi.fn()
    render(
      <table>
        <tbody>
          <tr onClick={onRowClick}>
            <td>
              <EditableCell label="우선순위 편집" display={<span>보통</span>}>
                <div>편집 내용</div>
              </EditableCell>
            </td>
          </tr>
        </tbody>
      </table>,
    )

    await userEvent.click(screen.getByRole('button', { name: '우선순위 편집' }))

    expect(onRowClick).not.toHaveBeenCalled()
    expect(screen.getByText('편집 내용')).toBeInTheDocument()
  })

  it('닫혀 있는 동안 자식(popover 내용)을 마운트하지 않는다 (FR12·NFR1)', () => {
    const spy = vi.fn()
    function Probe() {
      spy()
      return <div>편집 내용</div>
    }

    render(
      <EditableCell label="우선순위 편집" display={<span>보통</span>}>
        <Probe />
      </EditableCell>,
    )

    expect(spy).not.toHaveBeenCalled()
  })

  it('Esc 로 닫아도 행 클릭 핸들러가 불리지 않는다 (E2)', async () => {
    const onRowClick = vi.fn()
    render(
      <table>
        <tbody>
          <tr onClick={onRowClick}>
            <td>
              <EditableCell label="우선순위 편집" display={<span>보통</span>}>
                <div>편집 내용</div>
              </EditableCell>
            </td>
          </tr>
        </tbody>
      </table>,
    )

    await userEvent.click(screen.getByRole('button', { name: '우선순위 편집' }))
    await userEvent.keyboard('{Escape}')

    expect(screen.queryByText('편집 내용')).not.toBeInTheDocument()
    expect(onRowClick).not.toHaveBeenCalled()
  })

  it('hover 어포던스가 실재하는 토큰을 참조한다 (Pass 5 실버그 회귀 가드)', () => {
    render(
      <EditableCell label="우선순위 편집" display={<span>보통</span>}>
        <div />
      </EditableCell>,
    )

    const trigger = screen.getByRole('button', { name: '우선순위 편집' })
    // index.css 의 실제 토큰은 --border 다. --border-default 는 존재하지 않는다.
    // 계산값이 아니라 클래스 문자열을 본다 — jsdom 은 커스텀 프로퍼티를 해석하지 않아
    // 계산값 단언은 공허해진다 (F8 커서 단언 사고와 같은 함정).
    expect(trigger.className).toContain('hover:ring-(--border)')
    expect(trigger.className).not.toContain('--border-default')
  })

  it('트리거가 select-text 를 유지한다 — 셀 텍스트 복사 가능성 대리 지표 (F8 회귀 재발면)', () => {
    render(
      <EditableCell label="우선순위 편집" display={<span>보통</span>}>
        <div />
      </EditableCell>,
    )

    // ★`<button>` 에서 `user-select: auto` 는 CSS UI 규격상 none 으로 해석된다(Chromium 실측).
    // 이 클래스가 없으면 셀을 트리거로 감싼 순간 드래그 복사가 죽는다 — F8 이 이슈 제목에서
    // 겪은 실사고와 같은 형태다. jsdom 은 Tailwind 를 적용하지 않아 계산값 단언이 공허해지므로
    // 클래스 문자열을 본다(`issues.$key.test.tsx` F8-R1-2 와 같은 처방·같은 한계).
    expect(screen.getByRole('button', { name: '우선순위 편집' }).className).toContain('select-text')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E10 — 편집 중 목록 단축키 차단
//
// `shortcuts.ts` 의 `shouldIgnoreEvent` 는 `isComposing` · 수식키 ·
// `isEditableTarget`(input/textarea/select/contentEditable)만 본다. popover 안에서
// 포커스가 **버튼**에 있으면 편집 요소가 아니라 `j`/`k`(F10 커서 이동)가 그대로 발동해
// 편집 도중 목록 커서가 움직이고 상세가 바뀐다. 담당자 셀의 검색 <input> 은 우연히
// 안전하지만 우선순위·상태 셀은 아니다.
//
// 발화 지점은 `useKeyboardShortcuts.ts:186` 의 `document.addEventListener('keydown')`
// (**bubble 단계**)이므로, portal 컨테이너에서 전파를 끊으면 document 까지 닿지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

describe('EditableCell — 편집 중 목록 단축키 차단 (E10)', () => {
  /** 각 테스트가 붙인 document 리스너 — 누수 시 다른 테스트를 오염시키므로 반드시 뗀다 */
  let attachedListener: ((e: KeyboardEvent) => void) | null = null

  afterEach(() => {
    if (attachedListener !== null) {
      document.removeEventListener('keydown', attachedListener)
      attachedListener = null
    }
  })

  /**
   * document keydown 을 감시하는 스파이를 붙인다.
   *
   * @returns 발화 여부를 담는 vi.fn()
   */
  function spyOnDocumentKeydown(): ReturnType<typeof vi.fn> {
    const spy = vi.fn()
    attachedListener = spy
    document.addEventListener('keydown', spy)
    return spy
  }

  /** 열린 popover 를 가진 셀을 렌더한다 — 선택지는 <input> 이 아닌 **버튼**이어야 함정을 재현한다 */
  async function renderOpenedCell(): Promise<void> {
    render(
      <EditableCell label="우선순위 편집" display={<span>보통</span>}>
        <Button type="button" variant="ghost" size="sm">
          높음
        </Button>
      </EditableCell>,
    )
    await userEvent.click(screen.getByRole('button', { name: '우선순위 편집' }))
  }

  it('popover 가 열려 있으면 목록 단축키가 문서로 새어나가지 않는다 (E10)', async () => {
    await renderOpenedCell()
    const onDocumentKeyDown = spyOnDocumentKeydown()

    await userEvent.keyboard('j')

    expect(onDocumentKeyDown).not.toHaveBeenCalled()
  })

  it('popover 안 선택지에 포커스가 있어도 j/k 가 새지 않는다 (버튼 포커스가 함정)', async () => {
    await renderOpenedCell()
    screen.getByRole('button', { name: '높음' }).focus()
    const onDocumentKeyDown = spyOnDocumentKeydown()

    await userEvent.keyboard('k')

    expect(onDocumentKeyDown).not.toHaveBeenCalled()
  })

  it('Esc 는 통과시킨다 — Radix 가 닫기에 쓰므로 막으면 E2·FR4 가 깨진다', async () => {
    await renderOpenedCell()
    const onDocumentKeyDown = spyOnDocumentKeydown()

    await userEvent.keyboard('{Escape}')

    // ① document 까지 도달해야 Radix DismissableLayer 가 듣는다
    expect(onDocumentKeyDown).toHaveBeenCalled()
    // ② 그 결과 popover 가 실제로 닫힌다 — 도달 여부만 재면 공허해진다
    expect(screen.queryByRole('button', { name: '높음' })).not.toBeInTheDocument()
  })

  it('popover 가 닫혀 있으면 단축키를 막지 않는다 (목록 항법 무손상)', async () => {
    render(
      <EditableCell label="우선순위 편집" display={<span>보통</span>}>
        <div />
      </EditableCell>,
    )
    screen.getByRole('button', { name: '우선순위 편집' }).focus()
    const onDocumentKeyDown = spyOnDocumentKeydown()

    await userEvent.keyboard('j')

    // 닫힌 셀은 그냥 목록의 일부다 — 여기서까지 막으면 F10 커서 이동이 죽는다
    expect(onDocumentKeyDown).toHaveBeenCalled()
  })
})
