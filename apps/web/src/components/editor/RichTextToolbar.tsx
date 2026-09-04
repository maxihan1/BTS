// 리치 텍스트 에디터 툴바 — Jira Cloud 서식 15종 (J8)
import type { JSX } from 'react'
import type { Editor } from '@tiptap/react'
import {
  Bold, Italic, Underline, Strikethrough, Code, List, ListOrdered, ListChecks,
  Quote, Minus, Link2, Table as TableIcon, Image as ImageIcon, SquareCode,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { editorLabels } from '@/i18n/editor-labels'
import { cn } from '@/lib/utils'

/** 제목 드롭다운이 다루는 수준 — 서버가 h1~h6 을 전부 허용한다(J8). */
const HEADING_LEVELS = [1, 2, 3, 4, 5, 6] as const

interface RichTextToolbarProps {
  /** 대상 에디터. null 이면 아직 마운트되지 않아 아무것도 그리지 않는다. */
  editor: Editor | null
  /** 이미지 삽입 버튼 핸들러. 미전달이면 버튼을 그리지 않는다 (PR④ 가 배선). */
  onInsertImage?: () => void
}

/** 토글 버튼 한 개 — 활성 상태를 `aria-pressed` 로 노출한다(스크린리더가 켜짐/꺼짐을 읽는다). */
function ToggleButton({
  label, shortcut, icon, active, disabled, onClick,
}: {
  label: string
  shortcut: string
  icon: JSX.Element
  active: boolean
  disabled?: boolean
  onClick: () => void
}): JSX.Element {
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={label}
      aria-pressed={active}
      title={`${label} (${shortcut})`}
      disabled={disabled === true}
      onClick={onClick}
      className={cn(active && 'bg-muted text-foreground')}
    >
      {icon}
    </Button>
  )
}

/**
 * 에디터 툴바.
 *
 * ## 단축키는 여기서 만들지 않는다
 *
 * ⌘B·⌘I·⌘U·⌘⇧S·⌘⇧7/8/9 등은 TipTap 확장이 자기 키맵으로 이미 처리한다(J8 표와 일치).
 * 툴바는 **같은 명령의 마우스 입구**일 뿐이라 버튼과 단축키가 갈라질 여지가 없다.
 * 툴팁에 키를 함께 적어 사용자가 마우스에서 키보드로 옮겨 갈 수 있게 한다.
 *
 * ## 왜 `aria-pressed` 인가
 *
 * 서식 버튼은 누르는 동작이 아니라 **상태 토글**이다. 커서가 굵은 글자 안에 있으면 굵게
 * 버튼이 눌린 상태여야 하고, 그것을 시각(배경색)만이 아니라 스크린리더에도 알려야 한다.
 */
export function RichTextToolbar({ editor, onInsertImage }: RichTextToolbarProps): JSX.Element | null {
  if (editor === null) return null

  const headingValue = HEADING_LEVELS.find((l) => editor.isActive('heading', { level: l })) ?? 0

  return (
    <div
      role="toolbar"
      aria-label={editorLabels.toolbarLabel}
      className="flex flex-wrap items-center gap-0.5 border-b border-border px-1.5 py-1"
    >
      <ToggleButton
        label={editorLabels.bold} shortcut="⌘B" icon={<Bold className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('bold')}
        onClick={() => editor.chain().focus().toggleBold().run()}
      />
      <ToggleButton
        label={editorLabels.italic} shortcut="⌘I" icon={<Italic className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('italic')}
        onClick={() => editor.chain().focus().toggleItalic().run()}
      />
      <ToggleButton
        label={editorLabels.underline} shortcut="⌘U" icon={<Underline className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('underline')}
        onClick={() => editor.chain().focus().toggleUnderline().run()}
      />
      <ToggleButton
        label={editorLabels.strike} shortcut="⌘⇧S" icon={<Strikethrough className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('strike')}
        onClick={() => editor.chain().focus().toggleStrike().run()}
      />
      <ToggleButton
        label={editorLabels.code} shortcut="⌘E" icon={<Code className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('code')}
        onClick={() => editor.chain().focus().toggleCode().run()}
      />

      <span className="mx-1 h-4 w-px bg-border" aria-hidden="true" />

      {/* 제목 — 드롭다운 하나로 본문/H1~H6 을 다룬다. 버튼 7개를 늘어놓으면 툴바가 압도된다. */}
      <select
        aria-label={editorLabels.heading}
        title={editorLabels.heading}
        value={headingValue}
        onChange={(e) => {
          const level = Number(e.target.value)
          if (level === 0) editor.chain().focus().setParagraph().run()
          else editor.chain().focus().toggleHeading({ level: level as 1 | 2 | 3 | 4 | 5 | 6 }).run()
        }}
        className="h-7 rounded-md border border-border bg-background px-1.5 text-xs text-foreground"
      >
        <option value={0}>{editorLabels.paragraph}</option>
        {HEADING_LEVELS.map((l) => (
          <option key={l} value={l}>{editorLabels.headingLevel(l)}</option>
        ))}
      </select>

      <span className="mx-1 h-4 w-px bg-border" aria-hidden="true" />

      <ToggleButton
        label={editorLabels.bulletList} shortcut="⌘⇧8" icon={<List className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('bulletList')}
        onClick={() => editor.chain().focus().toggleBulletList().run()}
      />
      <ToggleButton
        label={editorLabels.orderedList} shortcut="⌘⇧7" icon={<ListOrdered className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('orderedList')}
        onClick={() => editor.chain().focus().toggleOrderedList().run()}
      />
      <ToggleButton
        label={editorLabels.taskList} shortcut="⌘⇧9" icon={<ListChecks className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('taskList')}
        onClick={() => editor.chain().focus().toggleTaskList().run()}
      />
      <ToggleButton
        label={editorLabels.blockquote} shortcut="⌘⇧B" icon={<Quote className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('blockquote')}
        onClick={() => editor.chain().focus().toggleBlockquote().run()}
      />
      <ToggleButton
        label={editorLabels.codeBlock} shortcut="⌘⌥C" icon={<SquareCode className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('codeBlock')}
        onClick={() => editor.chain().focus().toggleCodeBlock().run()}
      />

      <span className="mx-1 h-4 w-px bg-border" aria-hidden="true" />

      <ToggleButton
        label={editorLabels.horizontalRule} shortcut="⌘⇧-" icon={<Minus className="size-3.5" aria-hidden="true" />}
        active={false}
        onClick={() => editor.chain().focus().setHorizontalRule().run()}
      />
      <ToggleButton
        label={editorLabels.link}
        shortcut="⌘⇧K"
        icon={<Link2 className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('link')}
        onClick={() => {
          // 이미 링크면 해제한다 — 같은 버튼이 켜고 끄는 토글이라 상태가 하나다.
          if (editor.isActive('link')) {
            editor.chain().focus().unsetLink().run()
            return
          }
          // 링크 입력 전용 최소 프롬프트. 전용 팝오버 UI 는 후속 — 새 프리미티브를 만들지
          // 않는다는 계약 §4 를 따르고, 지금은 입력 수단이 있는 편이 없는 것보다 낫다.
          const url = window.prompt(editorLabels.linkPrompt)
          if (url === null || url.trim() === '') return
          editor.chain().focus().setLink({ href: url.trim() }).run()
        }}
      />
      <ToggleButton
        label={editorLabels.table} shortcut="/" icon={<TableIcon className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('table')}
        onClick={() => editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()}
      />
      {onInsertImage !== undefined && (
        <ToggleButton
          label={editorLabels.image} shortcut="/" icon={<ImageIcon className="size-3.5" aria-hidden="true" />}
          active={false}
          onClick={onInsertImage}
        />
      )}
    </div>
  )
}
