// 가젯 카탈로그 모달 + 설정 폼 단위 테스트 — 카탈로그 표시·enabled 게이팅·검증·추가 콜백 (FR-DB-02 Task 6)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { GADGET_CATALOG_FIXTURE } from '@/mocks/gadget-catalog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — QueryClient + 모달 렌더
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

interface ModalProps {
  open?: boolean
}

/**
 * GadgetCatalogModal을 렌더하고 새로 생성한 vi.fn() 모크를 반환한다.
 *
 * 테스트마다 독립적인 모크 인스턴스를 보장한다.
 */
async function renderModal(props: ModalProps = {}): Promise<{
  onAdd: ReturnType<typeof vi.fn>
  onClose: ReturnType<typeof vi.fn>
}> {
  const { GadgetCatalogModal } = await import('@/components/dashboard/GadgetCatalogModal')
  const client = makeClient()
  const onAdd = vi.fn()
  const onClose = vi.fn()
  render(
    <QueryClientProvider client={client}>
      <GadgetCatalogModal
        open={props.open ?? true}
        onAdd={onAdd}
        onClose={onClose}
      />
    </QueryClientProvider>,
  )
  return { onAdd, onClose }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('GadgetCatalogModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 카탈로그 MSW 핸들러 등록 (setup.ts afterEach에서 자동 제거됨)
    server.use(
      http.get('/api/v1/dashboards/gadget-catalog', () =>
        HttpResponse.json({ data: { gadgets: GADGET_CATALOG_FIXTURE } }),
      ),
    )
  })

  /**
   * C0. 흡수 후 우상단 X 닫기 버튼(Jira 시각 통일)이 렌더된다.
   *
   * ui/dialog 래퍼로 흡수되면 DialogContent가 우상단 X(sr-only "Close")를 강제 렌더한다.
   */
  it('C0: 흡수 후 우상단 X 닫기 버튼이 렌더된다', async () => {
    await renderModal()
    expect(await screen.findByRole('button', { name: /close/i })).toBeInTheDocument()
  })

  /**
   * C1. 모달이 열리면 카탈로그를 조회하고 12종 가젯 레이블이 화면에 표시된다.
   */
  it('C1: 모달이 열리면 12종 가젯 레이블이 표시된다', async () => {
    await renderModal()
    // enabled=true 6종 확인
    expect(await screen.findByText('Assigned To Me')).toBeInTheDocument()
    expect(screen.getByText('Recently Created')).toBeInTheDocument()
    expect(screen.getByText('Filter Result')).toBeInTheDocument()
    expect(screen.getByText('Issue Count')).toBeInTheDocument()
    expect(screen.getByText('Text Widget')).toBeInTheDocument()
    expect(screen.getByText('Link List')).toBeInTheDocument()
    // enabled=false 6종도 표시됨 (비활성 상태로)
    expect(screen.getByText('Pie Chart')).toBeInTheDocument()
    expect(screen.getByText('Bar Chart')).toBeInTheDocument()
    expect(screen.getByText('Sprint Burndown')).toBeInTheDocument()
    expect(screen.getByText('Activity Stream')).toBeInTheDocument()
    expect(screen.getByText('Comments Recent')).toBeInTheDocument()
  })

  /**
   * C2. category 그룹 헤더 (ISSUE / STATIC / CHART / ACTIVITY)가 화면에 표시된다.
   */
  it('C2: category 그룹 헤더가 표시된다', async () => {
    await renderModal()
    await screen.findByText('Assigned To Me') // 카탈로그 로딩 대기
    expect(screen.getByText('ISSUE')).toBeInTheDocument()
    expect(screen.getByText('STATIC')).toBeInTheDocument()
    expect(screen.getByText('CHART')).toBeInTheDocument()
    expect(screen.getByText('ACTIVITY')).toBeInTheDocument()
  })

  /**
   * C3. enabled=false 가젯은 "준비 중" 레이블과 함께 선택 불가 상태다 (S6).
   *
   * ★예시가 Pie Chart 에서 Comments Recent 로 바뀌었다.
   *   이 PR 이 PIE_CHART 를 켰기 때문이다. 예시를 바꾸기만 하고 끝내면 「전부 disabled 인
   *   카탈로그」도 통과하므로, 켠 쪽이 실제로 **활성**인 것을 같은 테스트에서 함께 잰다.
   *   백엔드 DashboardGadgetIntegrationTest 와 e2e S6 도 같은 처방을 받았다.
   */
  it('C3: enabled=false 가젯은 준비 중 표시와 함께 비활성화된다', async () => {
    await renderModal()
    await screen.findByText('Pie Chart') // 로딩 대기
    // "준비 중" 텍스트가 비활성 가젯 수만큼 존재
    const comingSoon = screen.getAllByText('준비 중')
    expect(comingSoon.length).toBeGreaterThan(0)
    // enabled=false 가젯 버튼은 disabled
    const disabledBtn = screen.getByRole('button', { name: /comments recent/i })
    expect(disabledBtn).toBeDisabled()

    // 이 PR 이 켠 가젯은 반대로 활성이다.
    expect(screen.getByRole('button', { name: /pie chart/i })).toBeEnabled()
  })

  /**
   * C4. enabled=true 가젯 선택 시 2단계 설정 폼으로 전환된다.
   */
  it('C4: enabled 가젯 선택 시 설정 폼 단계로 전환된다', async () => {
    const user = userEvent.setup()
    await renderModal()
    await screen.findByText('Text Widget')
    await user.click(screen.getByRole('button', { name: /text widget/i }))
    // Markdown 레이블 연결 textarea 확인
    expect(await screen.findByLabelText(/markdown/i)).toBeInTheDocument()
  })

  /**
   * C5. filter_result — filterId 없이 추가 버튼 클릭 시 에러를 표시하고 onAdd를 차단한다 (EC3).
   */
  it('C5: filter_result filterId 없이 추가 시 EC3 에러로 차단된다', async () => {
    const user = userEvent.setup()
    const { onAdd } = await renderModal()
    await screen.findByText('Filter Result')
    await user.click(screen.getByRole('button', { name: /filter result/i }))
    // 설정 폼 진입 후 filterId 입력 없이 추가 클릭
    const addBtn = await screen.findByRole('button', { name: '추가' })
    await user.click(addBtn)
    // EC3 에러 표시 (filterId / aql 그룹 requireAtLeastOne 위반)
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(onAdd).not.toHaveBeenCalled()
  })

  /**
   * C6. text_widget — markdown 비어있으면 추가가 차단된다 (EC5 required 위반).
   */
  it('C6: text_widget markdown 비어있으면 EC5 에러로 차단된다', async () => {
    const user = userEvent.setup()
    const { onAdd } = await renderModal()
    await screen.findByText('Text Widget')
    await user.click(screen.getByRole('button', { name: /text widget/i }))
    // markdown 입력 없이 추가 클릭
    const addBtn = await screen.findByRole('button', { name: '추가' })
    await user.click(addBtn)
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(onAdd).not.toHaveBeenCalled()
  })

  /**
   * C7. link_list — url이 http/https로 시작하지 않으면 추가가 차단된다 (EC7).
   *
   * 초기 상태에 1행이 있으므로 "항목 추가" 클릭 없이 바로 입력 가능.
   */
  it('C7: link_list url이 http/https 스킴이 아니면 EC7 에러로 차단된다', async () => {
    const user = userEvent.setup()
    const { onAdd } = await renderModal()
    await screen.findByText('Link List')
    await user.click(screen.getByRole('button', { name: /link list/i }))
    // 초기 1행의 label과 url 입력
    const labelInput = await screen.findByPlaceholderText('label')
    await user.type(labelInput, '테스트 링크')
    const urlInput = screen.getByPlaceholderText('url')
    await user.type(urlInput, 'ftp://invalid.com')
    // 추가 시도
    await user.click(screen.getByRole('button', { name: '추가' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(onAdd).not.toHaveBeenCalled()
  })

  /**
   * C8. 유효한 config 입력 후 추가 시 onAdd가 gadgetType + config와 함께 호출된다.
   */
  it('C8: 유효한 config 입력 후 추가 시 onAdd가 호출된다', async () => {
    const user = userEvent.setup()
    const { onAdd } = await renderModal()
    await screen.findByText('Text Widget')
    await user.click(screen.getByRole('button', { name: /text widget/i }))
    // markdown 입력
    const markdownInput = await screen.findByLabelText(/markdown/i)
    await user.type(markdownInput, '# 테스트 위젯')
    await user.click(screen.getByRole('button', { name: '추가' }))
    expect(onAdd).toHaveBeenCalledOnce()
    // noUncheckedIndexedAccess 안전 접근 — .at(0) 사용
    const firstArg = onAdd.mock.calls.at(0)?.at(0) as
      | { gadgetType: string; config: Record<string, unknown> }
      | undefined
    expect(firstArg?.gadgetType).toBe('text_widget')
    expect(firstArg?.config['markdown']).toBe('# 테스트 위젯')
  })

  /**
   * C9. 가젯 종류 변경(뒤로→다른 가젯 선택) 시 설정 폼이 초기화된다 (key prop 재마운트).
   */
  it('C9: 가젯 종류 변경 시 설정 폼이 초기화된다', async () => {
    const user = userEvent.setup()
    await renderModal()
    await screen.findByText('Text Widget')
    // Text Widget 선택 → markdown 타이핑
    await user.click(screen.getByRole('button', { name: /text widget/i }))
    const markdownInput = await screen.findByLabelText(/markdown/i)
    await user.type(markdownInput, '사라져야 할 텍스트')
    // 뒤로 가기
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    // 다른 가젯 선택 (Assigned To Me)
    await user.click(screen.getByRole('button', { name: /assigned to me/i }))
    // key 재마운트로 폼 초기화 — markdown 값이 사라짐
    await waitFor(() => {
      expect(screen.queryByDisplayValue('사라져야 할 텍스트')).toBeNull()
    })
  })

  /**
   * C10. ESC 키 또는 오버레이 클릭 시 onClose가 호출된다.
   */
  it('C10: ESC 키로 모달 닫기 시 onClose가 호출된다', async () => {
    const user = userEvent.setup()
    const { onClose } = await renderModal()
    await screen.findByText('Assigned To Me')
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })
})
