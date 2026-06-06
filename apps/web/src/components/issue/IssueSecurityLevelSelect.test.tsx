// IssueSecurityLevelSelect 컴포넌트 단위 테스트 — FR-PM-06 PR-B D6
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import { IssueSecurityLevelSelect } from './IssueSecurityLevelSelect'
import { issueDetailStrings } from '@/i18n/ko'

const LEVELS_URL = '/api/v1/projects/ATLAS/issue-security-scheme/levels'

const levelsFixture = [
  {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name: '기밀',
    description: '팀 내부용',
    isDefault: false,
  },
  {
    id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    name: '내부',
    description: null,
    isDefault: true,
  },
]

function renderSelect(
  props: Partial<React.ComponentProps<typeof IssueSecurityLevelSelect>> = {},
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const defaultProps = {
    projectKey: 'ATLAS',
    value: null as string | null,
    onChange: vi.fn(),
    disabled: false,
  }
  return render(
    <QueryClientProvider client={client}>
      <IssueSecurityLevelSelect {...defaultProps} {...props} />
    </QueryClientProvider>,
  )
}

describe('IssueSecurityLevelSelect', () => {
  beforeEach(() => {
    server.resetHandlers()
    server.use(
      http.get(LEVELS_URL, () => HttpResponse.json({ levels: levelsFixture })),
    )
  })

  /**
   * SL-SEL-1. 등급 목록 로드 후 "선택 안 함" + 등급 옵션이 렌더된다.
   */
  it('SL-SEL-1: 등급 목록 로드 후 "선택 안 함"과 각 등급 옵션이 렌더된다', async () => {
    renderSelect()

    // "선택 안 함" 옵션 존재
    await waitFor(() =>
      expect(
        screen.getByRole('option', { name: issueDetailStrings.securityLevelNone }),
      ).toBeInTheDocument(),
    )

    expect(screen.getByRole('option', { name: '기밀' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '내부' })).toBeInTheDocument()
  })

  /**
   * SL-SEL-2. value=null이면 "선택 안 함" 옵션이 선택된 상태다.
   */
  it('SL-SEL-2: value=null이면 "선택 안 함"이 선택된 상태다', async () => {
    renderSelect({ value: null })

    const select = await screen.findByLabelText(issueDetailStrings.securityLevelSelectLabel)
    expect((select as HTMLSelectElement).value).toBe('')
  })

  /**
   * SL-SEL-3. value가 등급 UUID이면 해당 옵션이 선택된 상태다.
   */
  it('SL-SEL-3: value가 UUID이면 해당 등급 옵션이 선택된 상태다', async () => {
    renderSelect({ value: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' })

    const select = await screen.findByLabelText(issueDetailStrings.securityLevelSelectLabel)
    await waitFor(() =>
      expect((select as HTMLSelectElement).value).toBe('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    )
  })

  /**
   * SL-SEL-4. 등급 선택 시 onChange(uuid)가 호출된다.
   */
  it('SL-SEL-4: 등급 선택 시 onChange에 UUID가 전달된다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    renderSelect({ onChange })

    const select = await screen.findByLabelText(issueDetailStrings.securityLevelSelectLabel)
    await user.selectOptions(select, '기밀')

    expect(onChange).toHaveBeenCalledWith('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
  })

  /**
   * SL-SEL-5. "선택 안 함" 선택 시 onChange(null)이 호출된다.
   */
  it('SL-SEL-5: "선택 안 함" 선택 시 onChange(null)이 호출된다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    // 처음에 값 있는 상태
    renderSelect({ value: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', onChange })

    const select = await screen.findByLabelText(issueDetailStrings.securityLevelSelectLabel)
    await user.selectOptions(select, issueDetailStrings.securityLevelNone)

    expect(onChange).toHaveBeenCalledWith(null)
  })

  /**
   * SL-SEL-6. disabled=true이면 셀렉터가 비활성 상태다.
   */
  it('SL-SEL-6: disabled=true이면 select가 disabled 상태다', async () => {
    renderSelect({ disabled: true })

    const select = await screen.findByLabelText(issueDetailStrings.securityLevelSelectLabel)
    expect(select).toBeDisabled()
  })

  /**
   * SL-SEL-7. 스킴 미적용(빈 배열) 프로젝트는 "선택 안 함"만 렌더된다.
   */
  it('SL-SEL-7: 스킴 미적용 시 "선택 안 함"만 렌더된다', async () => {
    server.use(
      http.get(LEVELS_URL, () => HttpResponse.json({ levels: [] })),
    )

    renderSelect()

    const select = await screen.findByLabelText(issueDetailStrings.securityLevelSelectLabel)
    const options = (select as HTMLSelectElement).options
    expect(options).toHaveLength(1)
    expect(options[0]?.value).toBe('')
  })
})
