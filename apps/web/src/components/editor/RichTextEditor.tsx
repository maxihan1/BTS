// 본문·댓글이 공유하는 TipTap WYSIWYG 에디터 (Jira 패리티 J8)
import type { JSX, RefObject } from 'react'
import { useEffect } from 'react'
import { EditorContent, useEditor } from '@tiptap/react'
import { isMentionSuggestionActive } from './mention-extension'
import { buildRichTextExtensions } from './rich-text-extensions'
import { RichTextToolbar } from './RichTextToolbar'
import { editorLabels } from '@/i18n/editor-labels'
import { cn } from '@/lib/utils'

export interface RichTextEditorProps {
  /** 초기 HTML. 편집 대상이 바뀌면 이 값으로 다시 채운다. */
  initialHtml: string
  /** 내용이 바뀔 때마다 현재 HTML 을 알린다. */
  onChange: (html: string) => void
  /** ⌘/Ctrl+Enter — 저장. 미전달이면 배선하지 않는다. */
  onSubmit?: () => void
  /** Escape — 취소. 미전달이면 배선하지 않는다. */
  onCancel?: () => void
  /** 빈 상태 안내 문구 */
  placeholder?: string
  /** 편집 가능 여부. false 면 읽기 전용으로 잠근다(저장 중 등). */
  editable?: boolean
  /** 접근성 이름 — 한 화면에 에디터가 둘 이상일 때 구분한다(본문/댓글). */
  ariaLabel?: string
  /** 툴바 이미지 버튼 핸들러 (PR④ 가 배선) */
  onInsertImage?: () => void
  /** 마운트 시 포커스를 줄지 */
  autoFocus?: boolean
  /** 외부에서 포커스를 주기 위한 DOM 참조 — 단축키 `m` 이 댓글 입력으로 이동할 때 쓴다. */
  contentRef?: RefObject<HTMLDivElement | null>
}

/**
 * 본문·댓글 공용 리치 텍스트 에디터.
 *
 * ## 왜 하나인가
 *
 * Jira 는 본문과 댓글이 **같은 에디터**다. 두 벌로 두면 「본문에는 표가 들어가는데 댓글에는
 * 안 되는」 식의 차이가 조용히 생기고, 서버 allowlist 와의 대조도 두 번 해야 한다.
 *
 * ## 저장 포맷
 *
 * `editor.getHTML()` 을 그대로 올린다. 서버가 `sanitizeHtml` 로 정화해 `description_html` /
 * `body_html` 에 저장한다(V039). 마크다운 왕복이 없으므로 서식이 손실되지 않는다.
 *
 * ## 단축키
 *
 * 서식 키(⌘B·⌘I·⌘U·⌘⇧S·⌘⇧7/8/9 …)는 TipTap 확장이 소유한다 — 여기서 다시 배선하지 않는다.
 * 이 컴포넌트가 더하는 것은 **폼 수준 키 2개**뿐이다.
 *
 * - `⌘/Ctrl+Enter` → [onSubmit]. 줄바꿈이 Enter 라서 저장은 수정자와 함께여야 한다.
 * - `Escape` → [onCancel]. 단, **IME 조합 중에는 무시한다** — 한글 입력 중 Escape 는 조합
 *   취소이지 편집 취소가 아니다. 여기서 가로채면 글자를 지우려다 편집이 통째로 닫힌다.
 *
 * ## 상세 단축키와의 충돌
 *
 * `shortcuts.ts` 의 `isEditableTarget` 이 `isContentEditable` 을 이미 본다. 그래서 에디터에
 * 포커스가 있는 동안 `i`(담당자)·`m`(댓글)·`e`(편집) 같은 상세 단축키가 발화하지 않는다.
 * 이 사실은 회귀 가드가 지킨다 — 그 판정이 사라지면 타이핑이 이슈를 조작한다.
 */
export function RichTextEditor({
  initialHtml,
  onChange,
  onSubmit,
  onCancel,
  placeholder = '',
  editable = true,
  ariaLabel = editorLabels.editorLabel,
  onInsertImage,
  autoFocus = false,
  contentRef,
}: RichTextEditorProps): JSX.Element {
  const editor = useEditor({
    extensions: buildRichTextExtensions(placeholder),
    content: initialHtml,
    editable,
    autofocus: autoFocus ? 'end' : false,
    // ★false 가 필수다. TipTap 3 기본값(true)은 SSR 용이고, 브라우저에서 켜면 첫 렌더가
    //   서버 스냅샷으로 그려져 초기 content 가 한 프레임 늦게 들어온다.
    immediatelyRender: false,
    editorProps: {
      attributes: {
        role: 'textbox',
        'aria-label': ariaLabel,
        'aria-multiline': 'true',
        class: cn(
          'prose prose-sm max-w-none px-3 py-2 min-h-[8rem] text-sm text-foreground',
          'focus:outline-none',
        ),
      },
      handleKeyDown: (view, event) => {
        // ⌘/Ctrl+Enter — 저장.
        if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
          if (onSubmit === undefined) return false
          event.preventDefault()
          onSubmit()
          return true
        }
        // Escape — 취소. ★IME 조합 중에는 넘긴다(한글 조합 취소를 빼앗지 않는다).
        if (event.key === 'Escape' && !event.isComposing) {
          // ★멘션 후보가 떠 있으면 그 Escape 는 팝업의 것이다. 여기서 가로채면 후보를 닫으려던
          //   입력이 **편집 취소**가 되어 작성분이 통째로 날아간다.
          //   ProseMirror `someProp` 은 view props(이 함수)를 플러그인보다 **먼저** 훑으므로,
          //   넘겨주지 않으면 suggestion 의 `onKeyDown` 이 영영 불리지 않는다(실측 2026-09-04).
          if (isMentionSuggestionActive(view.state)) return false
          if (onCancel === undefined) return false
          event.preventDefault()
          onCancel()
          return true
        }
        return false
      },
    },
    onUpdate: ({ editor: e }) => { onChange(e.getHTML()) },
  })

  // 편집 대상이 바뀌면(다른 이슈로 갈아탐 · 저장 후 refetch) 내용을 다시 채운다.
  // `setContent` 는 onUpdate 를 발화시키므로 `emitUpdate: false` 로 되먹임 고리를 끊는다.
  useEffect(() => {
    if (editor === null) return
    if (editor.getHTML() === initialHtml) return
    editor.commands.setContent(initialHtml, { emitUpdate: false })
  }, [editor, initialHtml])

  // 저장 중 잠금 등 외부 사유로 편집 가능 여부가 바뀐다.
  useEffect(() => {
    editor?.setEditable(editable)
  }, [editor, editable])

  return (
    <div className="rounded-md border border-border bg-background focus-within:ring-2 focus-within:ring-(--border-focus)">
      <RichTextToolbar editor={editor} onInsertImage={onInsertImage} />
      <EditorContent editor={editor} ref={contentRef} />
    </div>
  )
}
