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
  it('일부 텍스트 선택 후 버튼 클릭 시 선택 영역이 토큰으로 대체된다', async () => {
    const user = userEvent.setup()
    render(<Harness initialContent="보고자: 홍길동" />)

    const textarea = screen.getByLabelText(issueTemplateLabels.form.contentLabel) as HTMLTextAreaElement
    fireEvent.focus(textarea)
    // "홍길동"(4~7번 위치) 선택
    textarea.setSelectionRange(5, 8)

    const insertBtn = screen.getByRole('button', {
      name: issueTemplateLabels.form.variableInsertAria('작성자'),
    })
    await user.click(insertBtn)

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
// 에러 메시지 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('TemplateContentField — 에러 메시지', () => {
  it('errorMessage prop이 있으면 role="alert"로 렌더된다', () => {
    const { register, setValue } = (() => {
      // 직접 useForm 훅을 컴포넌트 외부에서 쓸 수 없으므로 래퍼 인라인 정의
      let capturedRegister: ReturnType<ReturnType<typeof useForm<TestFormValues>>['register']> | undefined
      let capturedSetValue: ReturnType<typeof useForm<TestFormValues>>['setValue'] | undefined

      function ErrorHarness(): JSX.Element {
        const form = useForm<TestFormValues>({ defaultValues: { content: '' } })
        capturedRegister = form.register
        capturedSetValue = form.setValue
        return (
          <TemplateContentField
            textareaId="test-error"
            label="본문"
            registration={form.register('content')}
            errorMessage="본문은 필수입니다."
            setFieldValue={(next) => { form.setValue('content', next) }}
          />
        )
      }
      render(<ErrorHarness />)
      return { register: capturedRegister, setValue: capturedSetValue }
    })()

    // register/setValue 는 이 테스트에서 불필요 — 렌더 결과만 검증
    void register
    void setValue

    expect(screen.getByRole('alert')).toHaveTextContent('본문은 필수입니다.')
  })
})
