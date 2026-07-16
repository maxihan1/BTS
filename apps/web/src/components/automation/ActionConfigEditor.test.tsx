// 액션 1건 타입별 조건부 편집기 테스트 — 타입 전환/SET_FIELD 값위젯/ASSIGN/ADD_COMMENT/CALL_WEBHOOK/EC10 (FR-AT-02 D6 Task 4) + SET_FIX_VERSIONS 모드·S8 저장거부 (FR-AT-07 PR-B Task 9)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { JSX, ReactNode } from 'react'
import { server } from '@/test/server'
import { projectMemberHandlers } from '@/mocks/project-member-handlers'
import type { Version } from '@/api/versions.types'
import { ActionConfigEditor } from './ActionConfigEditor'
import type { ActionFormState } from './ActionConfigEditor'
import { validateActions } from './AutomationRuleFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const ALICE_ID = '00000000-0000-4000-8000-000000000001'
const FIX_VERSION_ID_1 = '30000000-0000-4000-8000-000000000001'
const FIX_VERSION_ID_2 = '30000000-0000-4000-8000-000000000002'
const VERSION_PROJECT_ID = '40000000-0000-4000-8000-000000000001'

/** SET_FIX_VERSIONS 테스트 공용 버전 목록 픽스처 — GET /versions 응답으로 사용한다 */
const versionsFixture: Version[] = [
  {
    id: FIX_VERSION_ID_1,
    projectId: VERSION_PROJECT_ID,
    name: '1.0.0',
    description: null,
    startDate: null,
    releaseDate: null,
    status: 'UNRELEASED',
  },
  {
    id: FIX_VERSION_ID_2,
    projectId: VERSION_PROJECT_ID,
    name: '1.2.0',
    description: null,
    startDate: null,
    releaseDate: null,
    status: 'UNRELEASED',
  },
]

function renderWithClient(ui: JSX.Element) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(ui, {
    wrapper: ({ children }: { readonly children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  })
}

beforeEach(() => {
  server.use(...projectMemberHandlers)
})

const setFieldDefault: ActionFormState = { type: 'SET_FIELD', config: { field: 'summary', value: '' } }

// ─────────────────────────────────────────────────────────────────────────────
// 타입 전환
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionConfigEditor — 타입 전환', () => {
  it('액션 유형 select에 5종 옵션을 렌더한다', () => {
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={setFieldDefault} onChange={vi.fn()} />)

    expect(screen.getByRole('option', { name: '필드 값 설정' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '담당자 지정' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '댓글 추가' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '수정 예정 버전 설정' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '웹훅 호출' })).toBeInTheDocument()
  })

  it('유형을 ASSIGN으로 바꾸면 onChange가 기본 ASSIGN config로 호출된다', async () => {
    const onChange = vi.fn()
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={setFieldDefault} onChange={onChange} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByLabelText('액션 유형'), '담당자 지정')

    expect(onChange).toHaveBeenCalledWith({ type: 'ASSIGN', config: { assigneeId: null } })
  })

  it('유형을 ADD_COMMENT로 바꾸면 onChange가 기본 ADD_COMMENT config로 호출된다', async () => {
    const onChange = vi.fn()
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={setFieldDefault} onChange={onChange} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByLabelText('액션 유형'), '댓글 추가')

    expect(onChange).toHaveBeenCalledWith({ type: 'ADD_COMMENT', config: { body: '' } })
  })

  it('유형을 CALL_WEBHOOK으로 바꾸면 onChange가 기본 CALL_WEBHOOK config(method=POST)로 호출된다', async () => {
    const onChange = vi.fn()
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={setFieldDefault} onChange={onChange} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByLabelText('액션 유형'), '웹훅 호출')

    expect(onChange).toHaveBeenCalledWith({
      type: 'CALL_WEBHOOK',
      config: { url: '', method: 'POST', headers: [], body: '' },
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// SET_FIELD — 필드 드롭다운 6종 + 타입별 값 위젯
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionConfigEditor — SET_FIELD 값 위젯', () => {
  it('기본(summary) 필드는 텍스트 입력이며 입력 시 onChange가 문자열 값을 방출한다', async () => {
    const onChange = vi.fn()
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={setFieldDefault} onChange={onChange} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('값'), 'x')

    expect(onChange).toHaveBeenCalledWith({ type: 'SET_FIELD', config: { field: 'summary', value: 'x' } })
  })

  it('필드를 priority로 바꾸면 onChange가 기본값 1(숫자)과 함께 호출된다', async () => {
    const onChange = vi.fn()
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={setFieldDefault} onChange={onChange} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByLabelText('필드'), '우선순위')

    expect(onChange).toHaveBeenCalledWith({ type: 'SET_FIELD', config: { field: 'priority', value: 1 } })
  })

  it('priority 필드는 1~5 select를 렌더하고 선택 시 숫자 값을 방출한다(EC9)', async () => {
    const onChange = vi.fn()
    const value: ActionFormState = { type: 'SET_FIELD', config: { field: 'priority', value: 3 } }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={onChange} />)
    const user = userEvent.setup()

    const select = screen.getByLabelText('값') as HTMLSelectElement
    expect(select.tagName).toBe('SELECT')
    expect(select.value).toBe('3')
    ;[1, 2, 3, 4, 5].forEach((n) => {
      expect(screen.getByRole('option', { name: String(n) })).toBeInTheDocument()
    })

    await user.selectOptions(select, '5')

    const lastCall = onChange.mock.calls.at(-1)?.[0] as ActionFormState
    expect(lastCall).toEqual({ type: 'SET_FIELD', config: { field: 'priority', value: 5 } })
    expect(typeof lastCall.config.value).toBe('number')
  })

  it('impact 필드는 1~3 select를 렌더한다', () => {
    const value: ActionFormState = { type: 'SET_FIELD', config: { field: 'impact', value: 2 } }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={vi.fn()} />)

    const select = screen.getByLabelText('값') as HTMLSelectElement
    expect(select.tagName).toBe('SELECT')
    expect(select.value).toBe('2')
    ;[1, 2, 3].forEach((n) => {
      expect(screen.getByRole('option', { name: String(n) })).toBeInTheDocument()
    })
    expect(screen.queryByRole('option', { name: '4' })).not.toBeInTheDocument()
  })

  it('labels 필드는 태그 입력이며 Enter로 태그를 추가한다', async () => {
    const onChange = vi.fn()
    const value: ActionFormState = { type: 'SET_FIELD', config: { field: 'labels', value: [] } }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={onChange} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('값'), 'urgent{Enter}')

    expect(onChange).toHaveBeenCalledWith({ type: 'SET_FIELD', config: { field: 'labels', value: ['urgent'] } })
  })

  it('labels 필드는 중복 태그 추가를 방지한다(EC8)', async () => {
    const onChange = vi.fn()
    const value: ActionFormState = { type: 'SET_FIELD', config: { field: 'labels', value: ['urgent'] } }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={onChange} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('값'), 'urgent{Enter}')

    expect(onChange).not.toHaveBeenCalled()
  })

  it('labels 칩 제거 버튼으로 태그를 삭제한다', async () => {
    const onChange = vi.fn()
    const value: ActionFormState = { type: 'SET_FIELD', config: { field: 'labels', value: ['a', 'b'] } }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={onChange} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'a 제거' }))

    expect(onChange).toHaveBeenCalledWith({ type: 'SET_FIELD', config: { field: 'labels', value: ['b'] } })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// EC10 — 알 수 없는 SET_FIELD field fallback
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionConfigEditor — EC10 unknown SET_FIELD field fallback', () => {
  it('6종 밖 field는 텍스트 값 위젯으로 표시하고 값을 보존한다', () => {
    const onChange = vi.fn()
    const value: ActionFormState = { type: 'SET_FIELD', config: { field: 'customField', value: 'legacyValue' } }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={onChange} />)

    const valueInput = screen.getByLabelText('값') as HTMLInputElement
    expect(valueInput.tagName).toBe('INPUT')
    expect(valueInput.value).toBe('legacyValue')
    expect(screen.getByRole('option', { name: 'customField' })).toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ASSIGN — 담당자 피커 (ProjectMemberSelect 재사용) + 해제
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionConfigEditor — ASSIGN', () => {
  it('ProjectMemberSelect를 담당자 해제 옵션과 함께 렌더하고 멤버 선택 시 onChange(assigneeId)를 호출한다', async () => {
    const onChange = vi.fn()
    const value: ActionFormState = { type: 'ASSIGN', config: { assigneeId: null } }
    renderWithClient(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={onChange} />)
    const user = userEvent.setup()

    const aliceOption = await screen.findByRole('option', { name: '앨리스' })
    expect(screen.getByRole('option', { name: '담당자 해제' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('담당자'), aliceOption)

    expect(onChange).toHaveBeenCalledWith({ type: 'ASSIGN', config: { assigneeId: ALICE_ID } })
  })

  it('"담당자 해제" 선택 시 onChange(assigneeId: null)를 호출한다(EC3)', async () => {
    const onChange = vi.fn()
    const value: ActionFormState = { type: 'ASSIGN', config: { assigneeId: ALICE_ID } }
    renderWithClient(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={onChange} />)
    const user = userEvent.setup()

    const unassignOption = await screen.findByRole('option', { name: '담당자 해제' })
    await user.selectOptions(screen.getByLabelText('담당자'), unassignOption)

    expect(onChange).toHaveBeenCalledWith({ type: 'ASSIGN', config: { assigneeId: null } })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ADD_COMMENT — 본문 + 템플릿 힌트 (FR5)
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionConfigEditor — ADD_COMMENT', () => {
  it('본문 textarea와 템플릿 변수 힌트를 렌더하고 입력 시 onChange(body)를 호출한다', async () => {
    const onChange = vi.fn()
    const value: ActionFormState = { type: 'ADD_COMMENT', config: { body: '' } }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={onChange} />)
    const user = userEvent.setup()

    expect(screen.getByText(/\{\{ issue\.key \}\}/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('댓글 본문'), 'x')

    expect(onChange).toHaveBeenCalledWith({ type: 'ADD_COMMENT', config: { body: 'x' } })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// CALL_WEBHOOK — url·method·headers·body (FR6)
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionConfigEditor — CALL_WEBHOOK', () => {
  const webhookDefault: ActionFormState = {
    type: 'CALL_WEBHOOK',
    config: { url: '', method: 'POST', headers: [], body: '' },
  }

  it('url·method(기본 POST)·body 필드를 렌더하고 입력 시 onChange를 호출한다', async () => {
    const onChange = vi.fn()
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={webhookDefault} onChange={onChange} />)
    const user = userEvent.setup()

    const methodSelect = screen.getByLabelText('메서드') as HTMLSelectElement
    expect(methodSelect.value).toBe('POST')

    await user.type(screen.getByLabelText('URL'), 'h')
    expect(onChange).toHaveBeenLastCalledWith({
      type: 'CALL_WEBHOOK',
      config: { url: 'h', method: 'POST', headers: [], body: '' },
    })

    await user.selectOptions(methodSelect, 'PUT')
    expect(onChange).toHaveBeenLastCalledWith({
      type: 'CALL_WEBHOOK',
      config: { url: '', method: 'PUT', headers: [], body: '' },
    })

    await user.type(screen.getByLabelText('본문'), 'b')
    expect(onChange).toHaveBeenLastCalledWith({
      type: 'CALL_WEBHOOK',
      config: { url: '', method: 'POST', headers: [], body: 'b' },
    })
  })

  it('"헤더 추가" 클릭 시 빈 키·값의 새 헤더 쌍이 onChange(headers)에 추가된다(C1 — placeholder 키 미생성)', async () => {
    const onChange = vi.fn()
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={webhookDefault} onChange={onChange} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '헤더 추가' }))

    expect(onChange).toHaveBeenLastCalledWith({
      type: 'CALL_WEBHOOK',
      config: { url: '', method: 'POST', headers: [{ key: '', value: '' }], body: '' },
    })
  })

  it('기존 헤더 행의 값을 편집하면 onChange(headers)에 반영된다', async () => {
    const onChange = vi.fn()
    const withHeader: ActionFormState = {
      type: 'CALL_WEBHOOK',
      config: { url: '', method: 'POST', headers: [{ key: 'X-Token', value: '' }], body: '' },
    }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={withHeader} onChange={onChange} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('헤더 값'), 'a')

    expect(onChange).toHaveBeenLastCalledWith({
      type: 'CALL_WEBHOOK',
      config: { url: '', method: 'POST', headers: [{ key: 'X-Token', value: 'a' }], body: '' },
    })
  })

  it('헤더 행 삭제 버튼으로 헤더를 제거한다', async () => {
    const onChange = vi.fn()
    const withHeader: ActionFormState = {
      type: 'CALL_WEBHOOK',
      config: { url: '', method: 'POST', headers: [{ key: 'X-Token', value: 'abc' }], body: '' },
    }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={withHeader} onChange={onChange} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '1번째 헤더 삭제' }))

    expect(onChange).toHaveBeenCalledWith({
      type: 'CALL_WEBHOOK',
      config: { url: '', method: 'POST', headers: [], body: '' },
    })
  })

  it('두 헤더 행에 같은 키를 입력해도 두 행 모두 폼 상태 배열에 보존된다(C2 — map dedup 소실 방지)', async () => {
    const onChange = vi.fn()
    const twoHeaders: ActionFormState = {
      type: 'CALL_WEBHOOK',
      config: {
        url: '',
        method: 'POST',
        headers: [
          { key: 'X-Token', value: 'first' },
          { key: 'Other', value: 'second' },
        ],
        body: '',
      },
    }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={twoHeaders} onChange={onChange} />)

    // 컴포넌트는 완전한 controlled라 이 테스트(정적 props, re-render 없음)에서는 다중 키스트로크
    // 축적이 이전 키 입력에 반영되지 않는다 — 단일 change 이벤트로 최종값을 한 번에 반영한다.
    const keyInputs = screen.getAllByLabelText('헤더 이름')
    const secondKeyInput = keyInputs[1]
    if (secondKeyInput === undefined) throw new Error('두 번째 헤더 키 입력을 찾지 못함')
    fireEvent.change(secondKeyInput, { target: { value: 'X-Token' } })

    const lastCall = onChange.mock.calls.at(-1)?.[0] as ActionFormState
    expect(lastCall.config.headers).toEqual([
      { key: 'X-Token', value: 'first' },
      { key: 'X-Token', value: 'second' },
    ])
  })

  it('중복 키 헤더 중 한 행만 삭제해도 나머지 행(동일 키 포함)은 유지된다', async () => {
    const onChange = vi.fn()
    const duplicateKeyHeaders: ActionFormState = {
      type: 'CALL_WEBHOOK',
      config: {
        url: '',
        method: 'POST',
        headers: [
          { key: 'X-Token', value: 'first' },
          { key: 'X-Token', value: 'second' },
        ],
        body: '',
      },
    }
    render(<ActionConfigEditor projectKey={PROJECT_KEY} value={duplicateKeyHeaders} onChange={onChange} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '1번째 헤더 삭제' }))

    expect(onChange).toHaveBeenCalledWith({
      type: 'CALL_WEBHOOK',
      config: {
        url: '',
        method: 'POST',
        headers: [{ key: 'X-Token', value: 'second' }],
        body: '',
      },
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// SET_FIX_VERSIONS — 모드(교체/전체 해제) + VersionMultiSelect(variant=fix) (FR-AT-07 PR-B Task 9)
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionConfigEditor — SET_FIX_VERSIONS', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/versions', () => HttpResponse.json({ data: versionsFixture })),
    )
  })

  it('SET_FIX_VERSIONS 를 고르면 교체 모드가 기본 선택되고 버전 목록이 뜬다 (S1)', async () => {
    const onChange = vi.fn()
    const { rerender } = renderWithClient(
      <ActionConfigEditor projectKey={PROJECT_KEY} value={setFieldDefault} onChange={onChange} />,
    )
    const user = userEvent.setup()

    await user.selectOptions(screen.getByLabelText('액션 유형'), '수정 예정 버전 설정')

    expect(onChange).toHaveBeenCalledWith({
      type: 'SET_FIX_VERSIONS',
      config: { fixVersionsMode: 'replace', versionIds: [] },
    })

    const nextValue = onChange.mock.calls[0]?.[0] as ActionFormState
    rerender(<ActionConfigEditor projectKey={PROJECT_KEY} value={nextValue} onChange={onChange} />)

    expect(screen.getByRole('radio', { name: '선택한 버전으로 교체' })).toBeChecked()
    expect(await screen.findByRole('checkbox', { name: '1.0.0' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: '1.2.0' })).toBeInTheDocument()
  })

  it('전체 해제 모드면 버전 목록이 숨겨진다', () => {
    const value: ActionFormState = {
      type: 'SET_FIX_VERSIONS',
      config: { fixVersionsMode: 'clear', versionIds: [] },
    }
    renderWithClient(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={vi.fn()} />)

    expect(screen.getByRole('radio', { name: '전체 해제' })).toBeChecked()
    expect(screen.queryByRole('checkbox', { name: '1.0.0' })).not.toBeInTheDocument()
  })

  it('fixVersionsMode 키가 없으면(undefined) 교체 모드로 렌더한다(undefined ≡ replace, fail-closed 기본값)', () => {
    const value: ActionFormState = {
      type: 'SET_FIX_VERSIONS',
      config: { versionIds: [] },
    }
    renderWithClient(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={vi.fn()} />)

    expect(screen.getByRole('radio', { name: '선택한 버전으로 교체' })).toBeChecked()
  })

  it('useVersions 로딩이면 disabled shell 문구가 뜨고 모드 선택은 계속 가능하다(EC13)', () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/versions', async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ data: versionsFixture })
      }),
    )
    const value: ActionFormState = {
      type: 'SET_FIX_VERSIONS',
      config: { fixVersionsMode: 'replace', versionIds: [] },
    }
    renderWithClient(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={vi.fn()} />)

    expect(screen.getByText('버전 목록을 불러오는 중...')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '선택한 버전으로 교체' })).toBeInTheDocument()
  })

  it('useVersions 에러면 disabled shell + 에러 문구가 뜬다(EC13)', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/versions', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Internal Server Error', status: 500, detail: 'boom' },
          { status: 500 },
        ),
      ),
    )
    const value: ActionFormState = {
      type: 'SET_FIX_VERSIONS',
      config: { fixVersionsMode: 'replace', versionIds: [] },
    }
    renderWithClient(<ActionConfigEditor projectKey={PROJECT_KEY} value={value} onChange={vi.fn()} />)

    await waitFor(() => {
      expect(screen.getByText('버전 목록을 불러오지 못했습니다.')).toBeInTheDocument()
    })
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('replace→clear→replace 왕복에도 선택이 보존된다', async () => {
    const onChange = vi.fn()
    const initial: ActionFormState = {
      type: 'SET_FIX_VERSIONS',
      config: { fixVersionsMode: 'replace', versionIds: [FIX_VERSION_ID_1] },
    }
    const { rerender } = renderWithClient(
      <ActionConfigEditor projectKey={PROJECT_KEY} value={initial} onChange={onChange} />,
    )
    const user = userEvent.setup()
    await screen.findByRole('checkbox', { name: '1.0.0' })

    await user.click(screen.getByRole('radio', { name: '전체 해제' }))
    const afterClear = onChange.mock.calls.at(-1)?.[0] as ActionFormState
    expect(afterClear.config.versionIds).toEqual([FIX_VERSION_ID_1])
    rerender(<ActionConfigEditor projectKey={PROJECT_KEY} value={afterClear} onChange={onChange} />)
    expect(screen.queryByRole('checkbox', { name: '1.0.0' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: '선택한 버전으로 교체' }))
    const afterReplace = onChange.mock.calls.at(-1)?.[0] as ActionFormState
    expect(afterReplace.config.versionIds).toEqual([FIX_VERSION_ID_1])
    rerender(<ActionConfigEditor projectKey={PROJECT_KEY} value={afterReplace} onChange={onChange} />)
    expect(await screen.findByRole('checkbox', { name: '1.0.0' })).toBeChecked()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8 — validateActions 저장 거부 (AutomationRuleFormDialog 소유 순수 함수, load-bearing)
//
// 저장 거부 기제는 AutomationRuleFormDialog(제출 직전 게이트)가 소유하지만, 그 판정 로직인
// validateActions는 폼 상태만 받는 순수 함수라 Dialog 전체를 렌더하지 않고 여기서 단위 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('validateActions — S8 저장 거부', () => {
  it('교체 모드 + 빈 목록이면 저장이 거부된다', () => {
    const actions: ActionFormState[] = [
      { type: 'SET_FIX_VERSIONS', config: { fixVersionsMode: 'replace', versionIds: [] } },
    ]

    expect(validateActions(actions)).toEqual([{ index: 0, message: expect.any(String) as unknown as string }])
  })

  it('전체 해제 모드면 빈 목록이어도 저장을 거부하지 않는다', () => {
    const actions: ActionFormState[] = [
      { type: 'SET_FIX_VERSIONS', config: { fixVersionsMode: 'clear', versionIds: [] } },
    ]

    expect(validateActions(actions)).toEqual([])
  })

  it('교체 모드 + 버전 1개 이상 선택이면 저장을 거부하지 않는다', () => {
    const actions: ActionFormState[] = [
      { type: 'SET_FIX_VERSIONS', config: { fixVersionsMode: 'replace', versionIds: [FIX_VERSION_ID_1] } },
    ]

    expect(validateActions(actions)).toEqual([])
  })

  it('SET_FIX_VERSIONS 가 아닌 액션은 검사하지 않는다', () => {
    const actions: ActionFormState[] = [{ type: 'SET_FIELD', config: { field: 'summary', value: '' } }]

    expect(validateActions(actions)).toEqual([])
  })

  it('fixVersionsMode 키가 아예 없으면 replace로 간주해 저장을 거부한다(undefined ≡ replace, fail-closed 기본값)', () => {
    const actions: ActionFormState[] = [{ type: 'SET_FIX_VERSIONS', config: { versionIds: [] } }]

    expect(validateActions(actions)).toEqual([{ index: 0, message: expect.any(String) as unknown as string }])
  })
})
