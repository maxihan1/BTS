// AQL 쿼리 입력창 — textarea(투명 텍스트/caret) + 절대배치 overlay(syntax highlight) 합성 컴포넌트 (FR-SR-02 Task 4)
import { useRef, useCallback, type ReactNode, type ChangeEvent, type KeyboardEvent } from 'react'
import { tokenizeAql, type AqlTokenType } from '@/lib/aql-tokenizer'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** textarea/overlay 공통 최대 입력 길이 — 백엔드 DoS 가드와 동일 */
const MAX_LENGTH = 2000

// ─────────────────────────────────────────────────────────────────────────────
// 토큰→색상 className 매핑 (T8 syntax 토큰 클래스만 사용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AQL 토큰 타입을 Tailwind 색상 유틸리티 클래스로 변환한다.
 *
 * T8(DESIGN.md §2)에서 등록된 `--color-syntax-*` CSS 변수에서 생성된 유틸리티만 사용.
 * 즉흥 색 발명 절대 금지.
 */
const TOKEN_CLASS_MAP: Readonly<Partial<Record<AqlTokenType, string>>> = {
  KEYWORD: 'text-syntax-keyword',
  FIELD: 'text-syntax-field',
  OPERATOR: 'text-syntax-operator',
  STRING: 'text-syntax-string',
  NUMBER: 'text-syntax-number',
  // PAREN / COMMA / PLAIN — 색상 없음(기본 foreground)
}

// ─────────────────────────────────────────────────────────────────────────────
// 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** AqlHighlighter props */
export interface AqlHighlighterProps {
  /** 현재 AQL 쿼리 문자열 (controlled) */
  value: string
  /** 텍스트 변경 콜백 */
  onChange: (value: string) => void
  /**
   * 검색 트리거 콜백 — Cmd/Ctrl+Enter 시 호출.
   * 검색 실행을 상위 컴포넌트에 위임한다.
   */
  onSubmit: () => void
  /** textarea placeholder 텍스트 */
  placeholder?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — overlay 콘텐츠 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * value 문자열을 토큰화하여 색상 span 배열을 반환한다.
 *
 * 토큰 사이의 공백은 원본 위치 기반으로 복원한다.
 * 마지막 줄바꿈 뒤 빈 줄이 있으면 공백 문자를 추가해 pre 높이를 보정한다.
 * (브라우저가 trailing newline을 pre에서 무시하는 현상 방지)
 */
function buildOverlayContent(value: string): ReactNode[] {
  const tokens = tokenizeAql(value)
  const nodes: ReactNode[] = []
  let cursor = 0

  for (const token of tokens) {
    // 토큰 앞 공백 원문 복원
    if (token.start > cursor) {
      nodes.push(value.slice(cursor, token.start))
    }

    const className = TOKEN_CLASS_MAP[token.type]
    if (className !== undefined) {
      nodes.push(
        <span key={token.start} className={className}>
          {token.value}
        </span>,
      )
    } else {
      nodes.push(token.value)
    }

    cursor = token.end
  }

  // 마지막 토큰 이후 남은 텍스트(공백, 닫히지 않은 따옴표 등)
  if (cursor < value.length) {
    nodes.push(value.slice(cursor))
  }

  // trailing newline → pre 높이 보정 (브라우저가 마지막 \n을 무시)
  if (value.endsWith('\n')) {
    nodes.push('​')
  }

  return nodes
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 Tailwind 클래스 — textarea·overlay 정렬 정본
// ─────────────────────────────────────────────────────────────────────────────

/**
 * textarea와 overlay가 공유하는 스타일 클래스.
 *
 * 정렬 체크리스트(C6):
 * - box-sizing: border-box (Tailwind 기본)
 * - padding: p-3
 * - border-width: border (1px)
 * - letter-spacing: tracking-normal
 * - tab-size: (기본 8, 둘 다 동일)
 * - line-height: leading-normal
 * - font: font-mono (--font-mono — T8 등록)
 */
const SHARED_FONT_CLASSES = 'font-mono text-sm leading-normal tracking-normal'

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AQL 쿼리 입력 + syntax highlight 컴포넌트.
 *
 * textarea(투명 텍스트, caret 보임) 위에 절대배치 overlay(`<pre aria-hidden>`)를
 * 겹쳐 syntax highlight 효과를 낸다. textarea에서 직접 입력하므로 접근성·IME·caret이
 * 브라우저 기본 동작대로 작동한다.
 *
 * IME(한글): `isComposingRef`로 조합 중 overlay 갱신을 보류한다.
 * ([[use-mention-autocomplete.ts:70]] 패턴 미러)
 *
 * @see AqlHighlighterProps
 */
export const AqlHighlighter = ({ value, onChange, onSubmit, placeholder }: AqlHighlighterProps) => {
  /** IME 조합 진행 중 여부 — overlay 갱신 보류 판단 */
  const isComposingRef = useRef(false)

  // ─── 이벤트 핸들러 ───────────────────────────────────────────────────────

  const handleChange = useCallback(
    (e: ChangeEvent<HTMLTextAreaElement>) => {
      onChange(e.currentTarget.value)
    },
    [onChange],
  )

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
        e.preventDefault()
        onSubmit()
      }
    },
    [onSubmit],
  )

  const handleCompositionStart = useCallback(() => {
    isComposingRef.current = true
  }, [])

  const handleCompositionEnd = useCallback(() => {
    isComposingRef.current = false
  }, [])

  /** overlay mousedown은 클릭을 가로채지 않는다 — caret이 textarea에 유지된다 */
  const handleOverlayMouseDown = useCallback((e: React.MouseEvent<HTMLPreElement>) => {
    e.preventDefault()
  }, [])

  // ─── overlay 콘텐츠 — 조합 중에는 색상 없이 plain text 표시 ─────────────
  const overlayContent = isComposingRef.current
    ? value
    : buildOverlayContent(value)

  // ─── 렌더 ────────────────────────────────────────────────────────────────

  return (
    <div className="relative w-full">
      {/* overlay — aria-hidden, 포인터 이벤트 차단, 색상 span 담당 */}
      <pre
        aria-hidden="true"
        className={[
          'pointer-events-none absolute inset-0',
          'box-border overflow-hidden whitespace-pre-wrap break-words',
          'rounded-md border border-transparent p-3',
          SHARED_FONT_CLASSES,
          'text-foreground',
        ].join(' ')}
        onMouseDown={handleOverlayMouseDown}
      >
        {overlayContent}
      </pre>

      {/* textarea — 텍스트는 투명(overlay가 색상 담당), caret만 표시 */}
      <textarea
        value={value}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
        onCompositionStart={handleCompositionStart}
        onCompositionEnd={handleCompositionEnd}
        maxLength={MAX_LENGTH}
        placeholder={placeholder}
        className={[
          'relative z-10 w-full resize-none bg-transparent',
          'rounded-md border p-3',
          SHARED_FONT_CLASSES,
          'text-transparent caret-foreground',
          'focus:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        ].join(' ')}
        rows={3}
      />
    </div>
  )
}
