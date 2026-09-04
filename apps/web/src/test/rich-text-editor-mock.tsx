// 단위 테스트용 RichTextEditor 대역 — jsdom 에서 재현되지 않는 ProseMirror 를 textarea 로 갈음
import type { JSX, RefObject } from 'react'
import { useEffect, useState } from 'react'

/** 대역이 받는 prop — 실제 `RichTextEditorProps` 의 부분집합이되 **이름은 같아야 한다**. */
export interface RichTextEditorMockProps {
  initialHtml: string
  onChange: (html: string) => void
  onSubmit?: () => void
  onCancel?: () => void
  editable?: boolean
  ariaLabel?: string
  placeholder?: string
  contentRef?: RefObject<HTMLDivElement | null>
}

/**
 * `RichTextEditor` 대역.
 *
 * ## 왜 필요한가
 *
 * ProseMirror 는 레이아웃 좌표(`elementFromPoint`·`getBoundingClientRect`)에 기대므로
 * **jsdom 에서 타이핑이 재현되지 않는다**. 에디터를 쓰는 화면의 단위 테스트는 그 화면의
 * 흐름(제출 · 토스트 · 잠금 · 포커스 배선)을 재는 것이지 에디터 내부를 재는 것이 아니므로,
 * 입력만 가능하면 충분하다. 에디터 자체는 `components/editor/__tests__/RichTextEditor.test.tsx`
 * 26건이 지킨다.
 *
 * ## ★prop 을 삼키지 않는다
 *
 * 이 저장소에는 「mock 이 prop 서명을 삼켜 유닛은 전부 초록인데 e2e 만 빨강」인 결함 양식이
 * 이름까지 붙어 기록돼 있다(`mock-swallowed-prop-is-invisible-to-unit-tests`).
 * 그래서 이 대역은 받은 prop 을 **전부 실제로 쓴다**.
 *
 * | prop | 대역에서의 쓰임 |
 * |---|---|
 * | `ariaLabel` | textarea 의 접근성 이름 — 셀렉터가 이것으로 잡는다 |
 * | `initialHtml` | 초기값 + 변화 동기화(저장 후 부모가 비우는 것을 반영) |
 * | `onChange` | 입력 이벤트 |
 * | `onSubmit` | ⌘/Ctrl+Enter |
 * | `onCancel` | Escape |
 * | `editable` | `disabled` |
 * | `placeholder` | placeholder |
 * | `contentRef` | 컨테이너 DOM 참조 — 단축키 `m` 의 포커스 대상 |
 *
 * 하나라도 이름이 바뀌면 여기서 즉시 깨진다.
 *
 * ## 쓰는 법
 *
 * ```ts
 * vi.mock('@/components/editor/RichTextEditor', async () => ({
 *   RichTextEditor: (await import('@/test/rich-text-editor-mock')).RichTextEditorMock,
 * }))
 * ```
 */
export function RichTextEditorMock({
  initialHtml,
  onChange,
  onSubmit,
  onCancel,
  editable = true,
  ariaLabel,
  placeholder,
  contentRef,
}: RichTextEditorMockProps): JSX.Element {
  // 실제 컴포넌트처럼 `initialHtml` 변화를 반영한다 — 저장 성공 후 부모가 빈 값으로
  // 되돌리는 것을 대역이 무시하면 「입력이 비워진다」 계약을 재지 못한다.
  const [value, setValue] = useState(initialHtml)
  useEffect(() => { setValue(initialHtml) }, [initialHtml])

  return (
    <div ref={contentRef as unknown as RefObject<HTMLDivElement>} tabIndex={-1}>
      <textarea
        aria-label={ariaLabel}
        placeholder={placeholder}
        disabled={!editable}
        value={value}
        onChange={(e) => {
          setValue(e.target.value)
          onChange(e.target.value)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault()
            onSubmit?.()
            return
          }
          if (e.key === 'Escape' && !e.nativeEvent.isComposing) {
            e.preventDefault()
            onCancel?.()
          }
        }}
      />
    </div>
  )
}
