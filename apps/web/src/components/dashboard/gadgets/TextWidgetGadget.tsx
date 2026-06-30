// 텍스트 위젯 가젯 — markdown plain text 렌더 + XSS 차단 (FR-DB-02 D6/D7 Task-5)
import type { JSX } from 'react'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** TextWidgetGadget props */
export interface TextWidgetGadgetProps {
  /**
   * 렌더할 markdown 텍스트.
   * plain text로 안전 렌더링한다 (라이브러리 미사용).
   */
  markdown: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 텍스트 위젯 가젯.
 *
 * config.markdown을 plain text로 렌더링한다.
 *
 * 보안 (스펙 §6).
 * - dangerouslySetInnerHTML 절대 금지.
 * - React 자동 이스케이프로 XSS 차단 — `<script>` 등이 DOM 요소로 생성되지 않는다.
 * - 마크다운 파서 미사용 — 별도 라이브러리 의존성 없음.
 *
 * 레이아웃.
 * - whitespace-pre-wrap: 줄바꿈(\n) 보존.
 * - break-words: 긴 단어 줄 바꿈.
 */
export function TextWidgetGadget({ markdown }: TextWidgetGadgetProps): JSX.Element {
  return (
    <div className="h-full overflow-y-auto p-3">
      <p className="text-sm text-foreground whitespace-pre-wrap break-words">{markdown}</p>
    </div>
  )
}
