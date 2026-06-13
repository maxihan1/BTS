// TemplateContentField 컴포넌트 단위 테스트 — 변수 삽입 버튼 동작 + 도움말 렌더 검증
import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { JSX } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { TemplateContentField } from '../TemplateContentField'
import { issueTemplateLabels } from '@/i18n/issue-template-labels'
import { TEMPLATE_VARIABLES } from '../template-variables'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트용 래퍼 컴포넌트 — useForm harness (setValue mock 금지)
// ─────────────────────────────────────────────────────────────────────────────

const testSchema = z.object({ content: z.string() })
type TestFormValues = z.infer<typeof testSchema>

interface HarnessProps {
  /** 테스트 시작 시 content 필드 초기값 */
  readonly initialContent?: string
}

/**
 * 실제 useForm/setValue를 TemplateContentField에 주입하는 테스트 래퍼.
 * setValue mock을 쓰지 않고 RHF 실제 동작을 검증한다.
 */
function Harness({ initialContent = '' }: HarnessProps): JSX.Element {
  const { register, setValue, formState: { errors } } = useForm<TestFormValues>({
    resolver: zodResolver(testSchema),
    defaultValues: { content: initialContent },
  })

  return (
    <TemplateContentField
      textareaId="test-content"
      label={issueTemplateLabels.form.contentLabel}
      registration={register('content')}
      errorMessage={errors.content?.message}
      setFieldValue={(next) => {
        setValue('content', next, { shouldDirty: true, shouldValidate: true })
      }}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 빈 본문에서 작성자 버튼 클릭 → {{author}} 삽입
// ─────────────────────────────────────────────────────────────────────────────

describe('TemplateContentField — S1 빈 본문 변수 삽입', () => {
  it('빈 textarea에서 "작성자" 버튼 클릭 시 textarea value가 {{author}}가 된다', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    const insertBtn = screen.getByRole('button', {
      name: issueTemplateLabels.form.variableInsertAria('작성자'),
    })
    await user.click(insertBtn)

    const textarea = screen.getByLabelText(issueTemplateLabels.form.contentLabel) as HTMLTextAreaElement
    expect(textarea.value).toBe('{{author}}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 기존 텍스트 뒤에 커서 위치에서 버튼 클릭 → 끝에 append
// ─────────────────────────────────────────────────────────────────────────────

describe('TemplateContentField — S2 기존 텍스트 뒤 append', () => {
  it('"보고자: " 입력 후 커서 끝에서 작성자 버튼 클릭 시 "보고자: {{author}}"가 된다', async () => {
    const user = userEvent.setup()
    render(<Harness initialContent="보고자: " />)

    const textarea = screen.getByLabelText(issueTemplateLabels.form.contentLabel) as HTMLTextAreaElement
    // 커서를 끝으로 이동
    fireEvent.focus(textarea)
    textarea.setSelectionRange(textarea.value.length, textarea.value.length)

    const insertBtn = screen.getByRole('button', {
      name: issueTemplateLabels.form.variableInsertAria('작성자'),
    })
    await user.click(insertBtn)

    expect(textarea.value).toBe('보고자: {{author}}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 일부 텍스트 선택 후 버튼 클릭 → 선택 영역 대체
// ─────────────────────────────────────────────────────────────────────────────

describe('TemplateContentField — S4 선택 영역 대체', () => {
  it('일부 텍스트 선택 후 버튼 클릭 시 선택 영역이 토큰으로 대체된다', () => {
    render(<Harness initialContent="보고자: 홍길동" />)

    const textarea = screen.getByLabelText(issueTemplateLabels.form.contentLabel) as HTMLTextAreaElement

    // jsdom에서 document.activeElement를 textarea로 설정한다.
    // fireEvent.focus는 포커스 이벤트를 dispatch하지만 document.activeElement를 설정하지 않을 수 있다.
    // textarea.focus()를 직접 호출해 document.activeElement를 보장한다.
    textarea.focus()
    // "홍길동"(5~8번 위치) 선택
    textarea.setSelectionRange(5, 8)
    // 이 시점: document.activeElement === textarea → isFocused = true

    const insertBtn = screen.getByRole('button', {
      name: issueTemplateLabels.form.variableInsertAria('작성자'),
    })
    // fireEvent.click: React onClick만 트리거, document.activeElement를 바꾸지 않음
    // → textarea isFocused 유지 → selectionRange(5,8) 사용 → 대체 삽입
    fireEvent.click(insertBtn)

    expect(textarea.value).toBe('보고자: {{author}}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — 도움말 prefix/suffix + 토큰 텍스트 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('TemplateContentField — S6 도움말 텍스트 렌더', () => {
  it('도움말 prefix 텍스트가 렌더된다', () => {
    render(<Harness />)
    expect(screen.getByText(issueTemplateLabels.form.variableHelpPrefix, { exact: false })).toBeInTheDocument()
  })

  it('도움말 suffix 텍스트가 렌더된다', () => {
    render(<Harness />)
    expect(screen.getByText(issueTemplateLabels.form.variableHelpSuffix, { exact: false })).toBeInTheDocument()
  })

  it('모든 토큰 텍스트({{author}}, {{date}}, {{project}})가 도움말에 렌더된다', () => {
    render(<Harness />)
    for (const variable of TEMPLATE_VARIABLES) {
      expect(screen.getByText(variable.token, { exact: false })).toBeInTheDocument()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-D6-1 — 모든 삽입 버튼 type="button" (폼 submit 방지)
// ─────────────────────────────────────────────────────────────────────────────

describe('TemplateContentField — FR-D6-1 버튼 type="button"', () => {
  it('모든 삽입 버튼이 type="button" 속성을 가진다', () => {
    render(<Harness />)

    for (const variable of TEMPLATE_VARIABLES) {
      const btn = screen.getByRole('button', {
        name: issueTemplateLabels.form.variableInsertAria(variable.insertLabel),
      })
      expect(btn).toHaveAttribute('type', 'button')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7 — selectionStart=0(브라우저 미포커스 시뮬레이션) → 끝에 append (F2 회귀 방지)
//
// 배경.
//   실브라우저에서 포커스 없는 textarea의 selectionStart = 0 (jsdom에서는 null).
//   수정 전(selectionStart ?? length): 0이 truthy가 아닌 nullish check라 0도 0으로 사용 → prepend.
//   더 정확히: selectionStart=0이면 ?? fallback이 동작하지 않아 splice(value, token, 0, 0) = prepend.
//   수정 후(isFocused 분기): 포커스 없으면 항상 length → 끝에 append.
//
//   jsdom에서는 포커스 없는 textarea.selectionStart = null이라 기존 코드도 통과하지만,
//   실 브라우저 동작(selectionStart=0)을 시뮬레이션하기 위해
//   selectionStart를 0으로 강제 설정한 뒤 비포커스 상태로 버튼을 클릭한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('TemplateContentField — S7 selectionStart=0 시뮬레이션 → 끝에 append (F2 회귀)', () => {
  it('selectionStart=0(미포커스 브라우저 동작) 상태에서 버튼 클릭 시 끝에 append된다', () => {
    render(<Harness initialContent="## 재현 방법" />)

    const textarea = screen.getByLabelText(issueTemplateLabels.form.contentLabel) as HTMLTextAreaElement

    // 실 브라우저의 "포커스 없이 selectionStart=0" 상태를 jsdom에서 시뮬레이션.
    // textarea에 포커스를 주되, selectionStart=0으로 강제 설정한 뒤 blur.
    // — jsdom에서 blur 후에도 setSelectionRange로 지정한 위치가 유지됨.
    fireEvent.focus(textarea)
    textarea.setSelectionRange(0, 0)
    fireEvent.blur(textarea)
    // 이 시점: selectionStart=0, document.activeElement !== textarea

    const insertBtn = screen.getByRole('button', {
      name: issueTemplateLabels.form.variableInsertAria('일자'),
    })
    // fireEvent.click: React onClick 트리거, 포커스 이동 없음 → textarea isFocused=false 유지
    fireEvent.click(insertBtn)

    // 수정 전(selectionStart ?? length): selectionStart=0 → fallback 없이 0 사용 → prepend
    //   결과: "{{date}}## 재현 방법" (RED)
    // 수정 후(isFocused 분기): isFocused=false → length 사용 → 끝에 append
    //   결과: "## 재현 방법{{date}}" (GREEN)
    expect(textarea.value).toBe('## 재현 방법{{date}}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 에러 메시지 렌더
// ─────────────────────────────────────────────────────────────────────────────

/**
 * errorMessage 표시 전용 래퍼 — Harness와 동일 구조지만 errorMessage를 고정 주입한다.
 * useForm을 컴포넌트 바깥에서 직접 호출할 수 없으므 별도 함수형 컴포넌트로 정의한다.
 */
function ErrorHarness(): JSX.Element {
  const { register, setValue } = useForm<TestFormValues>({ defaultValues: { content: '' } })
  return (
    <TemplateContentField
      textareaId="test-error"
      label="본문"
      registration={register('content')}
      errorMessage="본문은 필수입니다."
      setFieldValue={(next) => { setValue('content', next) }}
    />
  )
}

describe('TemplateContentField — 에러 메시지', () => {
  it('errorMessage prop이 있으면 role="alert"로 렌더된다', () => {
    render(<ErrorHarness />)
    expect(screen.getByRole('alert')).toHaveTextContent('본문은 필수입니다.')
  })
})
