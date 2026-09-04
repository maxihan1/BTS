// TipTap @멘션 확장 — 서버가 만드는 span.mention 마크업과 왕복 호환 (FR-MN-02)
import Mention from '@tiptap/extension-mention'
import { ReactRenderer } from '@tiptap/react'
import { PluginKey } from '@tiptap/pm/state'
import type { EditorState } from '@tiptap/pm/state'
import type { SuggestionOptions } from '@tiptap/suggestion'
import type { AnyExtension } from '@tiptap/react'
import { fetchUsers } from '@/api/users'
import type { UserSummary } from '@/api/users'
import { MentionList } from './MentionList'
import type { MentionListHandle } from './MentionList'

/** 자동완성에 보여 줄 최대 후보 수 — 기존 textarea 구현과 같은 값이다. */
const MAX_SUGGESTIONS = 8

/**
 * 멘션 suggestion 플러그인 키.
 *
 * 기본값(`MentionPluginKey`)은 `@tiptap/extension-mention` 이 **export 하지 않아** 밖에서
 * 상태를 조회할 수 없다. 직접 만들어 [isMentionSuggestionActive] 가 쓰게 한다.
 */
const MENTION_SUGGESTION_KEY = new PluginKey('mentionSuggestion')

/**
 * 멘션 후보 팝업이 떠 있나.
 *
 * ★[RichTextEditor] 의 `editorProps.handleKeyDown` 이 이것을 봐야 한다. ProseMirror 의
 * `someProp` 은 **view props 를 플러그인보다 먼저** 훑기 때문에, 에디터가 `Escape` 를
 * 먼저 받아 취소로 처리해 버리면 suggestion 의 `onKeyDown` 이 **영영 불리지 않는다**.
 * 그러면 후보를 닫으려던 `Escape` 가 작성분을 통째로 버리는 취소가 된다(실측 2026-09-04 ·
 * `issue-mention-autocomplete` S3 가 증인).
 */
export function isMentionSuggestionActive(state: EditorState): boolean {
  const s = MENTION_SUGGESTION_KEY.getState(state) as { active?: boolean } | undefined
  return s?.active === true
}

/**
 * 후보 조회.
 *
 * `useUsers` 훅이 아니라 `fetchUsers` 를 직접 부른다 — TipTap suggestion 의 `items` 는 React
 * 렌더 트리 밖에서 호출되는 순수 비동기 함수라 훅을 쓸 수 없다. 서버 응답 자체는 같은
 * 엔드포인트이므로 후보 목록이 화면마다 갈라지지 않는다.
 *
 * 조회 실패는 빈 목록으로 흡수한다 — 자동완성이 안 뜨는 것은 불편이지만, 여기서 throw 하면
 * 에디터 입력 자체가 멈춘다.
 */
async function loadCandidates(query: string): Promise<UserSummary[]> {
  try {
    const users = await fetchUsers(query)
    return users.slice(0, MAX_SUGGESTIONS)
  } catch {
    return []
  }
}

/**
 * suggestion 팝업 수명 관리.
 *
 * TipTap 은 React 를 모르므로 `ReactRenderer` 로 컴포넌트를 붙였다 뗀다.
 * 위치는 CSS 로 잡는다 — `tippy.js` 같은 배치 라이브러리를 새로 들이지 않는다(계약 §4:
 * 만들기 전에 있는 것을 먼저 본다. 이 팝업은 에디터 바로 아래 고정 배치로 충분하다).
 */
function buildSuggestionRender(): SuggestionOptions<UserSummary>['render'] {
  return () => {
    let component: ReactRenderer<MentionListHandle> | null = null
    let container: HTMLDivElement | null = null

    return {
      onStart: (props) => {
        component = new ReactRenderer(MentionList, { props, editor: props.editor })
        container = document.createElement('div')
        container.className = 'absolute z-50 left-3'
        container.appendChild(component.element)

        // ★ProseMirror 의 DOM(`view.dom`)의 부모에 붙인다 — body 에 붙이면 모달 안에서
        //   z-index 가 뒤집혀 팝업이 오버레이 뒤로 숨는다. `editor.options.element` 는
        //   타입이 유니온(Element | {mount} | 콜백)이라 쓰지 않는다.
        const host = props.editor.view.dom.parentElement
        if (host === null) return
        // 절대 배치의 기준을 만든다 — 이미 잡혀 있으면 건드리지 않는다.
        if (getComputedStyle(host).position === 'static') host.style.position = 'relative'
        host.appendChild(container)
      },
      onUpdate: (props) => { component?.updateProps(props) },
      onKeyDown: (props) => {
        // Escape 는 팝업만 닫는다 — 편집 취소로 새면 작성분이 날아간다.
        if (props.event.key === 'Escape') {
          container?.remove()
          return true
        }
        return component?.ref?.onKeyDown(props.event) ?? false
      },
      onExit: () => {
        container?.remove()
        component?.destroy()
        component = null
        container = null
      },
    }
  }
}

/**
 * @멘션 확장.
 *
 * ## 서버 마크업과의 왕복
 *
 * 서버(`MentionExtension.kt`)는 `<span class="mention">@username</span>` 을 만들고,
 * sanitize 는 **정확히 `class="mention"` 인 span 만** 통과시킨다(EC8 — `"mention evil"` 은 거부).
 * 그래서 이 확장의 `HTMLAttributes` 도 그 한 클래스만 붙인다. 여기에 클래스를 더하면
 * 저장 → 서버 정화 → 재조회 왕복에서 **속성이 통째로 사라져** 멘션이 평문이 된다.
 *
 * `renderText` 도 같은 형태(`@username`)로 맞춘다 — 알림·검색이 평문 표현을 쓴다.
 */
export function buildMentionExtension(): AnyExtension {
  return Mention.configure({
    HTMLAttributes: { class: 'mention' },
    // ★서버 sanitize 가 `class="mention"` 만 허용하므로 data-* 속성을 남기지 않는다.
    //   저장 후 되읽으면 어차피 사라져, 있는 편이 「있다가 없어지는」 혼란만 만든다.
    renderHTML: ({ node }) => [
      'span',
      { class: 'mention' },
      `@${String(node.attrs.id ?? node.attrs.label ?? '')}`,
    ],
    renderText: ({ node }) => `@${String(node.attrs.id ?? node.attrs.label ?? '')}`,
    suggestion: {
      // ★밖에서 상태를 조회하려고 키를 직접 준다 — [isMentionSuggestionActive] 참조.
      pluginKey: MENTION_SUGGESTION_KEY,
      char: '@',
      // `@` 앞에 올 수 있는 문자 — 공백·여는 괄호만 허용한다.
      // 글자 뒤에 바로 붙는 `@`(예: `user@example.com`)는 이메일이지 멘션이 아니다.
      // 기존 `mention-detect.isBoundaryChar` 와 같은 취지이며, TipTap 은 정규식이 아니라
      // **문자 목록**을 받는다(`string[] | null`).
      allowedPrefixes: [' ', '(', '\n'],
      items: async ({ query }) => await loadCandidates(query),
      render: buildSuggestionRender(),
    },
  })
}
