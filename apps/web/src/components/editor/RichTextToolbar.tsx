// 리치 텍스트 에디터 툴바 — Jira Cloud 서식 15종 (J8) · 단축키 표기는 SHORTCUTS 단일 출처 (J22)
import type { JSX } from 'react'
import type { Editor } from '@tiptap/react'
import {
  Bold, Italic, Underline, Strikethrough, Code, List, ListOrdered, ListChecks,
  Quote, Minus, Link2, Table as TableIcon, Image as ImageIcon, SquareCode,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { editorLabels } from '@/i18n/editor-labels'
import { SHORTCUTS, toggleLink } from './rich-text-shortcuts'
import { cn } from '@/lib/utils'

/** 제목 드롭다운이 다루는 수준 — 서버가 h1~h6 을 전부 허용한다(J8). */
const HEADING_LEVELS = [1, 2, 3, 4, 5, 6] as const

interface RichTextToolbarProps {
  /** 대상 에디터. null 이면 아직 마운트되지 않아 아무것도 그리지 않는다. */
  editor: Editor | null
  /** 이미지 삽입 버튼 핸들러. 미전달이면 버튼을 그리지 않는다 (PR④ 가 배선). */
  onInsertImage?: () => void
}

/**
 * 라벨로 단축키 표기를 찾는다.
 *
 * ★**단축키 문자열을 이 파일에 적지 않는다.** 종전에는 `shortcut="⌘⇧-"` 같은 리터럴이
 * 버튼마다 인라인으로 박혀 있었고, 그 표기가 실제 키맵과 갈려도 아무도 몰랐다 —
 * 구분선 `⌘⇧-` 과 링크 `⌘⇧K` 는 **키맵 자체가 없는데** 툴팁이 적고 있었다(2026-09-07 실측).
 * 이제 `SHORTCUTS` 한 배열이 표기와 키를 함께 소유하므로 툴바는 조회만 한다.
 *
 * @returns 표기 문자열. 그 라벨에 단축키가 없으면 undefined (체크박스 목록·제목·표·이미지)
 */
function shortcutFor(label: string): string | undefined {
  return SHORTCUTS.find((s) => s.label === label)?.display
}

/**
 * 토글 버튼 한 개 — 활성 상태를 `aria-pressed` 로, 설명을 `Tooltip` 으로 노출한다.
 *
 * ## 왜 native `title` 이 아닌가 (J24)
 *
 * ADS 계약이 "A tooltip briefly describes an interactive element on **mouse hover or
 * keyboard focus**" 다(`atlassian.design/components/tooltip/usage` · 2026-09-07).
 * native `title` 은 **키보드 포커스에서 아예 뜨지 않고** 호버에도 1초쯤 지연이 있다.
 * Maxi 지적 3번이 그 지연을 「설명이 없다」로 겪은 것이다.
 *
 * ## `aria-label` 을 남기는 이유
 *
 * 툴팁은 **보조** 설명이다. 접근성 이름을 툴팁에 위임하면 툴팁이 뜨지 않는 순간
 * 버튼이 이름 없는 아이콘이 된다. 이름은 버튼이 스스로 갖는다.
 */
function ToggleButton({
  label, icon, active, disabled, onClick,
}: {
  label: string
  icon: JSX.Element
  active: boolean
  disabled?: boolean
  onClick: () => void
}): JSX.Element {
  const shortcut = shortcutFor(label)
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={label}
          aria-pressed={active}
          disabled={disabled === true}
          onClick={onClick}
          className={cn(active && 'bg-muted text-foreground')}
        >
          {icon}
        </Button>
      </TooltipTrigger>
      <TooltipContent>
        {shortcut === undefined ? label : `${label} (${shortcut})`}
      </TooltipContent>
    </Tooltip>
  )
}

/**
 * 에디터 툴바.
 *
 * ## 단축키는 여기서 만들지도, 적지도 않는다
 *
 * 표기와 배선을 모두 `rich-text-shortcuts.ts` 의 `SHORTCUTS` 가 소유한다. 툴바는 **같은 명령의
 * 마우스 입구**일 뿐이라 버튼과 단축키가 갈라질 여지가 없다.
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
        label={editorLabels.bold} icon={<Bold className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('bold')}
        onClick={() => editor.chain().focus().toggleBold().run()}
      />
      <ToggleButton
        label={editorLabels.italic} icon={<Italic className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('italic')}
        onClick={() => editor.chain().focus().toggleItalic().run()}
      />
      <ToggleButton
        label={editorLabels.underline} icon={<Underline className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('underline')}
        onClick={() => editor.chain().focus().toggleUnderline().run()}
      />
      <ToggleButton
        label={editorLabels.strike} icon={<Strikethrough className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('strike')}
        onClick={() => editor.chain().focus().toggleStrike().run()}
      />
      <ToggleButton
        label={editorLabels.code} icon={<Code className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('code')}
        onClick={() => editor.chain().focus().toggleCode().run()}
      />

      <span className="mx-1 h-4 w-px bg-border" aria-hidden="true" />

      {/* 제목 — 드롭다운 하나로 본문/H1~H6 을 다룬다. 버튼 7개를 늘어놓으면 툴바가 압도된다.
          Jira 도 제목에는 단축키를 주지 않는다(J22) — 그래서 툴팁에 키를 적지 않는다. */}
      <Tooltip>
        <TooltipTrigger asChild>
          <select
            aria-label={editorLabels.heading}
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
        </TooltipTrigger>
        <TooltipContent>{editorLabels.heading}</TooltipContent>
      </Tooltip>

      <span className="mx-1 h-4 w-px bg-border" aria-hidden="true" />

      <ToggleButton
        label={editorLabels.bulletList} icon={<List className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('bulletList')}
        onClick={() => editor.chain().focus().toggleBulletList().run()}
      />
      <ToggleButton
        label={editorLabels.orderedList} icon={<ListOrdered className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('orderedList')}
        onClick={() => editor.chain().focus().toggleOrderedList().run()}
      />
      {/* 체크박스 목록 — Jira 에 단축키가 없다(J22 "press Space after"). 툴팁도 키를 적지
          않는다. 종전에는 ⌘⇧9 를 적었는데 그 키는 Jira 에서 **인용**이다(편차 X-E3). */}
      <ToggleButton
        label={editorLabels.taskList} icon={<ListChecks className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('taskList')}
        onClick={() => editor.chain().focus().toggleTaskList().run()}
      />
      <ToggleButton
        label={editorLabels.blockquote} icon={<Quote className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('blockquote')}
        onClick={() => editor.chain().focus().toggleBlockquote().run()}
      />
      <ToggleButton
        label={editorLabels.codeBlock} icon={<SquareCode className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('codeBlock')}
        onClick={() => editor.chain().focus().toggleCodeBlock().run()}
      />

      <span className="mx-1 h-4 w-px bg-border" aria-hidden="true" />

      <ToggleButton
        label={editorLabels.horizontalRule} icon={<Minus className="size-3.5" aria-hidden="true" />}
        active={false}
        onClick={() => editor.chain().focus().setHorizontalRule().run()}
      />
      <ToggleButton
        label={editorLabels.link}
        icon={<Link2 className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('link')}
        // ★`⌘⇧K` 와 **같은 함수**를 부른다. 두 입구가 각자 구현을 가지면
        //   「버튼과 키가 다르게 동작한다」가 생긴다.
        onClick={() => { toggleLink(editor, () => window.prompt(editorLabels.linkPrompt)) }}
      />
      {/* 표·이미지 — Jira 는 `/` 퀵인서트로 넣지만 BTS 는 미구현이다(편차 X-E1).
          종전 툴팁이 `/` 를 적고 있었으나 **동작하지 않는 표기**라 지웠다. */}
      <ToggleButton
        label={editorLabels.table} icon={<TableIcon className="size-3.5" aria-hidden="true" />}
        active={editor.isActive('table')}
        onClick={() => editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()}
      />
      {onInsertImage !== undefined && (
        <ToggleButton
          label={editorLabels.image} icon={<ImageIcon className="size-3.5" aria-hidden="true" />}
          active={false}
          onClick={onInsertImage}
        />
      )}
    </div>
  )
}
