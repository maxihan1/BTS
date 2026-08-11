// 이슈 템플릿 본문 textarea + 변수 삽입 버튼 + 도움말을 묶은 공유 컴포넌트
import type { JSX } from 'react'
import { useRef, useCallback } from 'react'
import type { UseFormRegisterReturn } from 'react-hook-form'
import { Button } from '@/components/ui/button'
import { issueTemplateLabels } from '@/i18n/issue-template-labels'
import { TEMPLATE_VARIABLES } from './template-variables'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** TemplateContentField Props */
export interface TemplateContentFieldProps {
  /** textarea id — label htmlFor 연결 + aria-* 참조용 */
  readonly textareaId: string
  /** textarea 접근성 라벨 (aria-label, label 텍스트 동일) */
  readonly label: string
  /**
   * 호출부 useForm에서 `register('content')` 결과를 그대로 전달한다.
   * ref는 내부에서 병합 콜백으로 처리하므로 외부에서 ref를 덮어쓰지 않아도 된다.
   */
  readonly registration: UseFormRegisterReturn
  /** 검증 실패 시 표시할 에러 메시지 — undefined/없으면 미표시 */
  readonly errorMessage?: string
  /**
   * 변수 삽입 후 RHF 상태를 업데이트하는 콜백.
   * 호출부: `(next) => setValue('content', next, { shouldDirty: true, shouldValidate: true })`
   * 이 컴포넌트는 RHF에 직접 의존하지 않고 props로만 받는다 — 테스트/재사용 용이.
   */
  readonly setFieldValue: (next: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** textarea aria-describedby 연결용 suffix — textareaId에 붙여 고유 id 생성 */
const HELP_ID_SUFFIX = '-var-help'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 커서/선택 영역 위치에 token을 삽입한 새 문자열을 반환한다.
 *
 * @param currentValue - 삽입 전 textarea 전체 값
 * @param token - 삽입할 변수 토큰 (예: `{{author}}`)
 * @param selectionStart - 선택 시작 위치
 * @param selectionEnd - 선택 끝 위치
 * @returns 토큰이 삽입된 새 문자열과 삽입 직후 caret 위치
 */
function spliceToken(
  currentValue: string,
  token: string,
  selectionStart: number,
  selectionEnd: number,
): { next: string; caretPos: number } {
  const next = currentValue.slice(0, selectionStart) + token + currentValue.slice(selectionEnd)
  return { next, caretPos: selectionStart + token.length }
}

/**
 * requestAnimationFrame 이후 textarea에 포커스를 두고 caret을 지정 위치로 복원한다.
 *
 * DOM 업데이트(RHF setValue 반영)가 페인트 이전에 완료되길 보장하기 위해
 * rAF을 사용한다. jsdom 환경에서는 rAF이 즉시 실행되지 않아 테스트에서는
 * caret 위치를 직접 단언하지 않는다.
 *
 * F1: 삽입 직후 다이얼로그가 닫히면(Esc/overlay) 언마운트된 노드에 focus()를
 * 거는 race를 막기 위해 isConnected를 검사한다.
 */
function restoreCaretAfterFrame(el: HTMLTextAreaElement, caretPos: number): void {
  requestAnimationFrame(() => {
    // F1: 언마운트된 노드에 focus() 거는 race 방지
    if (!el.isConnected) return
    el.focus()
    el.setSelectionRange(caretPos, caretPos)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// TemplateContentField
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿 본문 편집 필드.
 *
 * - textarea + 변수 삽입 버튼 3개({{author}}/{{date}}/{{project}}) + 도움말 문구를 묶는다.
 * - create/edit 다이얼로그가 이 컴포넌트를 공유하며, 배선(register/setValue 주입)은 각 다이얼로그에서 담당한다.
 * - RHF의 ref와 자체 textareaRef를 병합 콜백으로 공존시켜 caret 조작이 가능하게 한다.
 * - 삽입 후 caret 복원은 requestAnimationFrame으로 DOM 업데이트 이후 실행한다.
 */
export const TemplateContentField = ({
  textareaId,
  label,
  registration,
  errorMessage,
  setFieldValue,
}: TemplateContentFieldProps): JSX.Element => {
  const { form: labels } = issueTemplateLabels

  // 자체 ref — caret 복원·selectionStart 읽기에 필요
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  /**
   * F4: IME 조합 상태 추적 ref.
   * 한글/일본어 입력 시 브라우저는 compositionstart→compositionend 사이에
   * selectionStart를 조합 중인 글자 위치로 가리킨다. 이 상태에서 splice하면
   * half-composed jamo를 끊거나 위치가 어긋날 수 있다.
   * ref를 쓰는 이유: 상태 변경으로 리렌더링을 유발할 필요 없이 플래그만 추적하면 충분.
   */
  const isComposingRef = useRef(false)

  // RHF ref와 자체 ref 병합 콜백 — 두 ref에 모두 DOM 요소를 할당한다
  const mergedRef = useCallback(
    (el: HTMLTextAreaElement | null) => {
      textareaRef.current = el
      // registration.ref는 함수형 ref이므로 직접 호출
      if (typeof registration.ref === 'function') {
        registration.ref(el)
      }
    },
    [registration],
  )

  const helpId = `${textareaId}${HELP_ID_SUFFIX}`

  /**
   * 변수 삽입 핸들러.
   *
   * F2: 삽입 위치를 포커스 여부로 결정한다.
   * - 포커스 상태(onMouseDown preventDefault로 보존됨): 실제 caret 위치에 삽입.
   * - 포커스 없음(textarea를 한 번도 누르지 않은 상태): 끝에 append.
   *
   * F4: 조합 중(isComposingRef.current === true)에는 caret을 무시하고 끝에 append.
   * 한글/일본어 IME 조합 중 selectionStart는 조합 영역 내부를 가리키므로
   * splice하면 half-composed jamo를 끊을 수 있다. 조합이 끝난 뒤에는 정상 caret 사용.
   */
  function handleInsert(token: string): void {
    const el = textareaRef.current
    if (el === null) return

    const isFocused = document.activeElement === el
    const composing = isComposingRef.current
    // F4: 조합 중에는 selectionStart가 조합 영역을 가리켜 jamo를 끊을 수 있으므로 끝에 append
    const start = (isFocused && !composing) ? el.selectionStart : el.value.length
    const end = (isFocused && !composing) ? el.selectionEnd : el.value.length

    const { next, caretPos } = spliceToken(el.value, token, start, end)
    setFieldValue(next)
    restoreCaretAfterFrame(el, caretPos)
  }

  return (
    <div className="mb-4">
      <label htmlFor={textareaId} className="block text-sm font-medium mb-1">
        {label}
      </label>

      {/* 삽입 버튼 영역 */}
      <div className="flex flex-wrap gap-1 mb-2">
        {TEMPLATE_VARIABLES.map((variable) => (
          <Button
            key={variable.token}
            type="button"
            variant="outline"
            size="sm"
            aria-label={labels.variableInsertAria(variable.insertLabel)}
            // F2: mousedown에서 preventDefault → textarea가 blur되지 않아 caret/포커스가 보존된다.
            // onClick은 그대로 fire되므로 handleInsert가 정상 실행됨.
            onMouseDown={(e) => { e.preventDefault() }}
            onClick={() => { handleInsert(variable.token) }}
          >
            + {variable.insertLabel}
          </Button>
        ))}
      </div>

      {/* 본문 textarea — F3: registration 스프레드로 RHF props 전체 보존 후 ref 덮어쓰기 */}
      {/*
        F4: onCompositionStart/End는 registration(RHF register)이 제공하지 않으므로 명시 추가.
        register는 이 이벤트를 미사용하므로 충돌 없음.
      */}
      <textarea
        {...registration}
        id={textareaId}
        aria-label={label}
        aria-describedby={helpId}
        placeholder={issueTemplateLabels.contentPlaceholder}
        rows={6}
        className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground resize-y"
        ref={mergedRef}
        onCompositionStart={() => { isComposingRef.current = true }}
        onCompositionEnd={() => { isComposingRef.current = false }}
      />

      {/* 필드 에러 */}
      {errorMessage !== undefined && (
        <p className="text-xs text-destructive mt-1" role="alert">
          {errorMessage}
        </p>
      )}

      {/* 도움말 — 사용 가능 변수 안내 */}
      <p id={helpId} className="text-xs text-muted-foreground mt-1">
        {labels.variableHelpPrefix}
        {': '}
        {TEMPLATE_VARIABLES.map((variable, index) => (
          <span key={variable.token}>
            {index > 0 && ' · '}
            <code className="font-mono">{variable.token}</code>
            {' '}
            {variable.insertLabel}
          </span>
        ))}
        {' — '}
        {labels.variableHelpSuffix}
      </p>
    </div>
  )
}
