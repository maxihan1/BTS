// 툴바 툴팁 판별식 — 호버·키보드 포커스 노출과 단축키 표기 정합 (Jira J22 · ADS Tooltip J24)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RichTextEditor } from '../RichTextEditor'
import { editorLabels } from '@/i18n/editor-labels'
import { SHORTCUTS } from '../rich-text-shortcuts'

/**
 * 툴바 툴팁 판별식.
 *
 * ## 왜 있나
 *
 * Maxi 지적 3번 — 「에디터 마우스 후버 시 설명이 있어야 함」. 종전 툴바는 native `title` 만
 * 썼다. 그것은 ①호버 후 1초쯤 지나야 뜨고 ②**키보드 포커스에서는 아예 안 뜨며**
 * ③스타일을 줄 수 없다. ADS 계약은 "A tooltip briefly describes an interactive element on
 * **mouse hover or keyboard focus**" 로 둘 다를 요구한다
 * (`atlassian.design/components/tooltip/usage` · 2026-09-07).
 *
 * 저장소에 `ui/tooltip.tsx` 프리미티브가 이미 있었는데 **앱 전체에서 한 번도 안 쓰였다.**
 *
 * ## 무엇을 재나
 *
 * `role="tooltip"` 이 실제로 나타나는지를 **호버와 포커스 양쪽**에서 잰다.
 * 그리고 표기된 단축키가 `SHORTCUTS` 단일 출처와 일치하는지 본다 — 툴팁에 적힌 키가
 * 동작하지 않던 것이 이 PR 이 고치는 결함이다.
 */

function renderToolbar(): void {
  render(<RichTextEditor initialHtml="<p>본문</p>" onChange={vi.fn()} />)
}

/** 라벨로 툴바 버튼을 잡는다. */
function button(name: string): HTMLElement {
  return screen.getByRole('button', { name })
}

describe('RichTextToolbar — 툴팁 (J24)', () => {
  it('마우스를 올리면 툴팁이 뜬다', async () => {
    const user = userEvent.setup()
    renderToolbar()

    await user.hover(button(editorLabels.bold))

    await waitFor(() => {
      expect(screen.getAllByRole('tooltip').length).toBeGreaterThan(0)
    })
  })

  it('키보드 포커스에서도 툴팁이 뜬다 (ADS 계약)', async () => {
    const user = userEvent.setup()
    renderToolbar()

    // native `title` 은 포커스에서 뜨지 않는다. 이 단언이 그 차이를 지킨다.
    button(editorLabels.bold).focus()
    await waitFor(() => {
      expect(screen.getAllByRole('tooltip').length).toBeGreaterThan(0)
    })
    // 사용하지 않는 변수 경고를 피하면서 user-event 초기화를 유지한다.
    expect(user).toBeDefined()
  })

  it('툴팁이 라벨과 단축키를 함께 적는다', async () => {
    const user = userEvent.setup()
    renderToolbar()

    await user.hover(button(editorLabels.bold))

    await waitFor(() => {
      const tips = screen.getAllByRole('tooltip').map((t) => t.textContent ?? '')
      expect(tips.some((t) => t.includes(editorLabels.bold) && t.includes('⌘B'))).toBe(true)
    })
  })

  it('버튼은 툴팁과 별개로 접근성 이름을 스스로 갖는다', () => {
    renderToolbar()
    // 툴팁은 보조 설명이다. `aria-label` 을 툴팁에 위임하면 툴팁이 안 뜨는 순간
    // 버튼이 이름 없는 아이콘이 된다.
    expect(button(editorLabels.bold)).toHaveAttribute('aria-label', editorLabels.bold)
  })
})

describe('RichTextToolbar — 단축키 표기 (J22)', () => {
  it('인용 툴팁이 ⌘⇧9 를 적는다 (⌘⇧B 아님)', async () => {
    const user = userEvent.setup()
    renderToolbar()

    await user.hover(button(editorLabels.blockquote))

    await waitFor(() => {
      const tips = screen.getAllByRole('tooltip').map((t) => t.textContent ?? '')
      expect(tips.some((t) => t.includes('⌘⇧9'))).toBe(true)
    })
  })

  it('인라인 코드 툴팁이 ⌘⇧M 을 적는다 (⌘E 아님)', async () => {
    const user = userEvent.setup()
    renderToolbar()

    await user.hover(button(editorLabels.code))

    await waitFor(() => {
      const tips = screen.getAllByRole('tooltip').map((t) => t.textContent ?? '')
      expect(tips.some((t) => t.includes('⌘⇧M'))).toBe(true)
    })
  })

  it('체크박스 목록 툴팁에는 단축키가 없다 (Jira 에 없다 · 편차 X-E3)', async () => {
    const user = userEvent.setup()
    renderToolbar()

    await user.hover(button(editorLabels.taskList))

    await waitFor(() => {
      const tips = screen.getAllByRole('tooltip').map((t) => t.textContent ?? '')
      const own = tips.find((t) => t.includes(editorLabels.taskList))
      expect(own).toBeDefined()
      // 종전에는 ⌘⇧9 를 적었는데, 그 키는 Jira 에서 인용이다.
      expect(own ?? '').not.toContain('⌘')
    })
  })

  it('표 툴팁에 동작하지 않는 `/` 표기가 없다 (편차 X-E1)', async () => {
    const user = userEvent.setup()
    renderToolbar()

    await user.hover(button(editorLabels.table))

    await waitFor(() => {
      const tips = screen.getAllByRole('tooltip').map((t) => t.textContent ?? '')
      const own = tips.find((t) => t.includes(editorLabels.table))
      expect(own).toBeDefined()
      // `/` 퀵인서트는 미구현이다. 적어 두면 툴팁이 다시 거짓말을 한다.
      expect(own ?? '').not.toContain('/')
    })
  })

  it('번호 목록 툴팁이 SHORTCUTS 의 표기를 그대로 쓴다', async () => {
    const user = userEvent.setup()
    renderToolbar()

    const shortcut = SHORTCUTS.find((s) => s.label === editorLabels.orderedList)
    expect(shortcut?.display).toBe('⌘⇧7')

    await user.hover(button(editorLabels.orderedList))

    await waitFor(() => {
      const tips = screen.getAllByRole('tooltip').map((t) => t.textContent ?? '')
      expect(tips.some((t) => t.includes(shortcut?.display ?? ''))).toBe(true)
    })
  })

  // ★「툴바 소스에 단축키 리터럴이 하드코딩돼 있지 않다」는 여기서 재지 않는다.
  //   DOM 을 호버해 도는 방식은 그 사실을 못 재고(툴팁에 값이 뜨는 것과 그 값이 어디서
  //   왔는지는 다른 문제다) 순회 루프가 flaky 하다. 소스 대조는 판별식의 자리다 —
  //   `scripts/workflow/toolbar-shortcut-coverage.test.ts`.
})
