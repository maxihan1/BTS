// 에디터 단축키 단일 출처 — 툴바 표기와 TipTap 키맵이 같은 항목에서 파생된다 (Jira J22)
import { Extension } from '@tiptap/react'
import type { AnyExtension, Editor } from '@tiptap/react'
import { editorLabels } from '@/i18n/editor-labels'

/**
 * 단축키 한 개.
 *
 * ## 왜 표기와 키를 한 객체에 두나
 *
 * 2026-09-07 실측 — 툴바는 구분선을 `⌘⇧-`, 링크를 `⌘⇧K` 로 **적고 있었는데**
 * TipTap 확장에는 그 키맵이 **없었다**. 적힌 것과 배선된 것이 서로를 검사하지 않아,
 * 사용자는 툴팁을 보고 키를 눌렀다가 아무 일도 일어나지 않는 경험을 했다.
 *
 * 이 저장소가 이름 붙인 「두 목록이 서로를 검사하지 않는다」의 판본이다. 처방으로 흔한 것은
 * 차집합 판별식이지만, **여기서는 목록을 하나로 만들어 그 결함을 원천 제거한다** —
 * 툴바는 [display] 를 그리고 키맵은 [key] 를 등록하며, 둘은 같은 항목의 두 필드다.
 * 하나만 고치는 것이 **구조적으로 불가능**하다.
 */
export interface RichTextShortcut {
  /** 툴바 툴팁에 보이는 표기. mac 기호를 쓴다 — Jira 문서와 같은 표기법이다. */
  readonly display: string
  /** TipTap `addKeyboardShortcuts` 에 넘기는 키. [toTiptapKey] 가 [display] 에서 유도한 값과 같아야 한다. */
  readonly key: string
  /** 접근성 이름 겸 툴팁 제목. `editorLabels` 가 단일 출처다. */
  readonly label: string
  /** 이 키가 실행하는 명령. 링크만 프롬프트가 필요해 [LinkPrompt] 를 받는다. */
  readonly run: (editor: Editor, promptLink: LinkPrompt) => boolean
}

/** 링크 주소를 사람에게 묻는 함수. 툴바와 키맵이 **같은 입구**를 쓰게 하려고 주입한다. */
export type LinkPrompt = () => string | null

/**
 * 표기(`⌘⇧M`)를 TipTap 키(`Mod-Shift-m`)로 옮긴다.
 *
 * 판별식이 [SHORTCUTS] 의 두 필드가 정합인지 이 함수로 되잰다 — 사람이 손으로 적은 두 값이
 * 갈리는 것을 막는 유일한 자동 장치다.
 *
 * @param display `⌘`·`⇧`·`⌥` 수식자 뒤에 키 한 글자가 오는 표기
 * @returns TipTap 키 문자열
 */
export function toTiptapKey(display: string): string {
  const parts: string[] = []
  let rest = display
  if (rest.startsWith('⌘')) {
    parts.push('Mod')
    rest = rest.slice(1)
  }
  if (rest.startsWith('⌥')) {
    parts.push('Alt')
    rest = rest.slice(1)
  }
  if (rest.startsWith('⇧')) {
    parts.push('Shift')
    rest = rest.slice(1)
  }
  // 남은 것이 키다. 알파벳은 소문자로 — TipTap 키맵 관례이며 대문자는 Shift 조합으로 읽힌다.
  parts.push(rest.toLowerCase())
  return parts.join('-')
}

/**
 * 툴바가 표기하고 에디터가 등록하는 단축키 전량.
 *
 * ## Jira 실물과의 관계 (J22 · 2026-09-07 조회)
 *
 * `support.atlassian.com/jira-software-cloud/docs/markdown-and-keyboard-shortcuts/` 의 표를
 * 그대로 따른다. 특히 **`⌘⇧9` 는 인용**이다 — TipTap `TaskList` 가 그 키를 기본으로 선점하므로
 * [buildShortcutExtension] 옆에서 `TaskList` 의 키맵을 회수한다(`rich-text-extensions.ts`).
 *
 * ## 여기 **없는** 것
 *
 * - **체크박스 목록** — Jira 에 단축키가 없다("press Space after"로 `[]` 마크다운만 안내).
 *   버튼은 남기되 툴팁에 키를 적지 않는다(편차 X-E3).
 * - **제목** — Jira 는 `#`~`######` 마크다운뿐이라 키가 없다. 툴바는 드롭다운으로 제공한다.
 * - **표·이미지** — Jira 는 `/` 퀵인서트로 넣는데 BTS 는 미구현이다(편차 X-E1).
 *   종전 툴팁이 `/` 를 적고 있었으나 **동작하지 않는 표기**라 지웠다.
 *
 * ## 여기 **더 있는** 것
 *
 * - **코드 블록 `⌘⌥C`** — Jira 에는 없고 TipTap 기본에는 있다. 실제로 동작하므로 남긴다(편차 X-E5).
 */
export const SHORTCUTS: readonly RichTextShortcut[] = [
  {
    display: '⌘B',
    key: 'Mod-b',
    label: editorLabels.bold,
    run: (editor) => editor.chain().focus().toggleBold().run(),
  },
  {
    display: '⌘I',
    key: 'Mod-i',
    label: editorLabels.italic,
    run: (editor) => editor.chain().focus().toggleItalic().run(),
  },
  {
    display: '⌘U',
    key: 'Mod-u',
    label: editorLabels.underline,
    run: (editor) => editor.chain().focus().toggleUnderline().run(),
  },
  {
    display: '⌘⇧S',
    key: 'Mod-Shift-s',
    label: editorLabels.strike,
    run: (editor) => editor.chain().focus().toggleStrike().run(),
  },
  {
    // ★Jira 는 `⌘⇧M`(monospace)이다. TipTap 기본은 `⌘E` 이고 그것도 살아 있지만,
    //   사용자가 배우는 키는 Jira 쪽으로 맞춘다.
    display: '⌘⇧M',
    key: 'Mod-Shift-m',
    label: editorLabels.code,
    run: (editor) => editor.chain().focus().toggleCode().run(),
  },
  {
    display: '⌘⇧8',
    key: 'Mod-Shift-8',
    label: editorLabels.bulletList,
    run: (editor) => editor.chain().focus().toggleBulletList().run(),
  },
  {
    display: '⌘⇧7',
    key: 'Mod-Shift-7',
    label: editorLabels.orderedList,
    run: (editor) => editor.chain().focus().toggleOrderedList().run(),
  },
  {
    // ★Jira 의 `⌘⇧9` 는 **인용**이다. TipTap TaskList 가 이 키를 기본으로 갖고 있어
    //   회수하지 않으면 「인용을 눌렀는데 체크박스가 나온다」가 된다.
    display: '⌘⇧9',
    key: 'Mod-Shift-9',
    label: editorLabels.blockquote,
    run: (editor) => editor.chain().focus().toggleBlockquote().run(),
  },
  {
    display: '⌘⌥C',
    key: 'Mod-Alt-c',
    label: editorLabels.codeBlock,
    run: (editor) => editor.chain().focus().toggleCodeBlock().run(),
  },
  {
    display: '⌘⇧-',
    key: 'Mod-Shift--',
    label: editorLabels.horizontalRule,
    run: (editor) => editor.chain().focus().setHorizontalRule().run(),
  },
  {
    display: '⌘⇧K',
    key: 'Mod-Shift-k',
    label: editorLabels.link,
    run: (editor, promptLink) => toggleLink(editor, promptLink),
  },
]

/**
 * 링크 토글 — 툴바 버튼과 `⌘⇧K` 가 **같은 함수**를 부른다.
 *
 * 두 입구가 각자 구현을 가지면 「버튼과 키가 다르게 동작한다」가 생긴다. 이미 링크면 해제하고,
 * 아니면 주소를 물어 건다. 빈 입력·취소는 아무것도 하지 않는다.
 *
 * @param editor 대상 에디터
 * @param promptLink 주소를 묻는 함수. 취소 시 null 을 돌려준다
 * @returns 문서를 바꿨으면 true
 */
export function toggleLink(editor: Editor, promptLink: LinkPrompt): boolean {
  if (editor.isActive('link')) {
    return editor.chain().focus().unsetLink().run()
  }
  const url = promptLink()
  if (url === null || url.trim() === '') return false
  return editor.chain().focus().setLink({ href: url.trim() }).run()
}

/**
 * [SHORTCUTS] 전량을 등록하는 TipTap 확장.
 *
 * ## 왜 확장 하나에 몰아넣나
 *
 * TipTap 기본 키맵은 `node_modules` 안에 있어 판별식이 파싱하려면 버전마다 깨진다. 툴바가
 * 표기하는 키를 **전부 여기에 명시 등록**하면 검증 대상이 저장소 안의 파일 하나로 좁혀진다.
 * 기본 키맵과 중복되는 것(⌘B 등)이 있으나 **같은 명령**이라 무해하다 — 먼저 매치한 쪽이
 * 실행하고 결과가 같다.
 *
 * @param promptLink 링크 주소 입력 수단. 툴바와 같은 것을 넘겨야 두 입구가 갈리지 않는다
 * @returns 키맵 확장
 */
export function buildShortcutExtension(promptLink: LinkPrompt): AnyExtension {
  return Extension.create({
    name: 'jiraShortcuts',
    addKeyboardShortcuts() {
      const map: Record<string, () => boolean> = {}
      for (const shortcut of SHORTCUTS) {
        map[shortcut.key] = () => shortcut.run(this.editor, promptLink)
      }
      return map
    },
  })
}
