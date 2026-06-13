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
// 도움말 id 상수 — textareaId에 suffix를 붙여 고유성 확보
// ─────────────────────────────────────────────────────────────────────────────

const HELP_ID_SUFFIX = '-var-help'

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
   * 1. textarea가 없으면 즉시 return.
   * 2. selectionStart/End를 읽고, 포커스가 없어 null이면 끝 위치로 fallback.
   * 3. 선택 영역을 token으로 대체한 새 값을 setFieldValue로 RHF에 동기화.
   * 4. requestAnimationFrame으로 DOM 업데이트 후 caret을 삽입 끝으로 복원.
   */
  function handleInsert(token: string): void {
    const el = textareaRef.current
    if (el === null) return

    const start = el.selectionStart ?? el.value.length
    const end = el.selectionEnd ?? el.value.length
    const next = el.value.slice(0, start) + token + el.value.slice(end)

    setFieldValue(next)

    // RHF setValue는 비제어 textarea의 DOM el.value를 갱신하므로(Approach A)
    // rAF 후 el.value가 갱신된 상태에서 caret을 복원한다.
    const caretPos = start + token.length
    requestAnimationFrame(() => {
      el.focus()
      el.setSelectionRange(caretPos, caretPos)
    })
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
            onClick={() => { handleInsert(variable.token) }}
          >
            + {variable.insertLabel}
          </Button>
        ))}
      </div>

      {/* 본문 textarea — ref는 병합 콜백으로 교체, 나머지 RHF 속성은 명시 전달 */}
      <textarea
        id={textareaId}
        aria-label={label}
        aria-describedby={helpId}
        placeholder="Markdown 형식으로 본문을 입력하세요."
        rows={6}
        className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground resize-y"
        name={registration.name}
        onChange={registration.onChange}
        onBlur={registration.onBlur}
        ref={mergedRef}
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
