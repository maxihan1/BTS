// 툴바가 표기하는 단축키가 실제 에디터에서 동작하는지 재는 판별식 (Jira J22)
import { describe, it, expect, afterEach } from 'vitest'
import { Editor } from '@tiptap/react'
import { buildRichTextExtensions } from '../rich-text-extensions'
import { SHORTCUTS, toTiptapKey } from '../rich-text-shortcuts'

/**
 * 단축키 판별식.
 *
 * ## 왜 있나
 *
 * 2026-09-07 실측 — 툴바가 구분선 `⌘⇧-` · 링크 `⌘⇧K` 를 툴팁에 적고 있었는데
 * `@tiptap/extension-horizontal-rule` · `@tiptap/extension-link` 에는 `addKeyboardShortcuts`
 * 가 **없었다.** 적혀 있는데 눌러도 아무 일이 안 났다. 표기와 배선이 서로를 검사하지 않았다.
 *
 * 더 나쁜 것이 하나 더 있었다. Jira 에서 `⌘⇧9` 는 **인용**인데
 * (`support.atlassian.com/jira-software-cloud/docs/markdown-and-keyboard-shortcuts/` · 2026-09-07),
 * TipTap `TaskList` 가 그 키를 기본으로 선점해 BTS 에서는 **체크박스 목록**이 나왔다.
 *
 * ## 무엇을 재나
 *
 * 실제 `Editor` 인스턴스에 키를 쏘고 **결과 문서의 노드**를 본다. 「버튼이 있다」가 아니라
 * 「눌렀더니 그것이 됐다」를 재는 것이 요점이다.
 */

/** 테스트용 에디터. `document` 가 필요하므로 jsdom 환경에서만 돈다. */
function makeEditor(): Editor {
  const element = document.createElement('div')
  document.body.appendChild(element)
  return new Editor({
    element,
    extensions: buildRichTextExtensions(''),
    content: '<p>본문</p>',
  })
}

let editor: Editor | null = null

afterEach(() => {
  editor?.destroy()
  editor = null
})

/**
 * 키를 쏜다.
 *
 * ProseMirror 키맵은 `view.someProp('handleKeyDown')` 경로로 처리되므로 실제 `KeyboardEvent`
 * 를 만들어 넣는다. `metaKey` 로 `Mod` 를 흉내낸다 — 테스트가 도는 jsdom 은 mac 이 기본이다.
 */
function press(ed: Editor, key: string, opts: { shift?: boolean; alt?: boolean } = {}): void {
  ed.view.dom.dispatchEvent(
    new KeyboardEvent('keydown', {
      key,
      metaKey: true,
      ctrlKey: false,
      shiftKey: opts.shift ?? false,
      altKey: opts.alt ?? false,
      bubbles: true,
      cancelable: true,
    }),
  )
}

describe('rich-text 단축키 — Jira J22 실물 대조', () => {
  it('⌘⇧9 는 인용이다 (Jira J22 · TaskList 기본 키맵 회수)', () => {
    editor = makeEditor()
    editor.commands.focus()
    press(editor, '9', { shift: true })
    expect(editor.isActive('blockquote')).toBe(true)
    // ★같은 키가 체크박스 목록을 만들면 안 된다. TipTap TaskList 가 Mod-Shift-9 를
    //   기본으로 선점하므로, 그것을 회수했는지가 이 단언의 실질이다.
    expect(editor.isActive('taskList')).toBe(false)
  })

  it('⌘⇧M 이 인라인 코드를 토글한다 (Jira 는 ⌘E 가 아니다)', () => {
    editor = makeEditor()
    editor.commands.focus()
    press(editor, 'm', { shift: true })
    expect(editor.isActive('code')).toBe(true)
  })

  it('⌘⇧- 가 구분선을 넣는다', () => {
    editor = makeEditor()
    editor.commands.focus()
    press(editor, '-', { shift: true })
    expect(editor.getHTML()).toContain('<hr')
  })

  it('⌘⇧7 은 번호 목록, ⌘⇧8 은 글머리 목록이다', () => {
    editor = makeEditor()
    editor.commands.focus()
    press(editor, '7', { shift: true })
    expect(editor.isActive('orderedList')).toBe(true)

    editor.commands.setContent('<p>본문</p>')
    editor.commands.focus()
    press(editor, '8', { shift: true })
    expect(editor.isActive('bulletList')).toBe(true)
  })

  it('⌘B · ⌘I · ⌘U · ⌘⇧S 가 그대로 동작한다 (기존 키 회귀 가드)', () => {
    editor = makeEditor()
    editor.commands.focus()
    press(editor, 'b')
    expect(editor.isActive('bold')).toBe(true)
    press(editor, 'i')
    expect(editor.isActive('italic')).toBe(true)
    press(editor, 'u')
    expect(editor.isActive('underline')).toBe(true)
    press(editor, 's', { shift: true })
    expect(editor.isActive('strike')).toBe(true)
  })

  it('⌘⌥C 가 코드 블록을 토글한다', () => {
    editor = makeEditor()
    editor.commands.focus()
    press(editor, 'c', { alt: true })
    expect(editor.isActive('codeBlock')).toBe(true)
  })
})

describe('SHORTCUTS 단일 출처', () => {
  it('표기와 TipTap 키가 같은 항목에서 나온다 (두 목록을 만들지 않는다)', () => {
    // 항목마다 사람이 읽는 표기(⌘⇧M)와 기계가 쓰는 키(Mod-Shift-m)가 **함께** 산다.
    // 둘을 따로 두면 하나만 고쳤을 때 툴팁이 거짓말을 한다 — 그것이 이 PR 이 고치는 결함이다.
    for (const s of SHORTCUTS) {
      expect(toTiptapKey(s.display)).toBe(s.key)
    }
  })

  it('표기→키 변환이 수식자를 정확히 옮긴다', () => {
    expect(toTiptapKey('⌘B')).toBe('Mod-b')
    expect(toTiptapKey('⌘⇧M')).toBe('Mod-Shift-m')
    expect(toTiptapKey('⌘⇧-')).toBe('Mod-Shift--')
    expect(toTiptapKey('⌘⌥C')).toBe('Mod-Alt-c')
  })

  it('같은 키를 두 항목이 다투지 않는다', () => {
    const keys = SHORTCUTS.map((s) => s.key)
    expect(new Set(keys).size).toBe(keys.length)
  })
})
