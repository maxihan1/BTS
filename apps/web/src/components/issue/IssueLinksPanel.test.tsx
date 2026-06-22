// IssueLinksPanel 컨테이너 컴포넌트 단위 테스트 — FR-LK-01 D6 Task-4 + FR-EP-01 D6 Task-5
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { IssueLinksPanel } from './IssueLinksPanel'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 목록 렌더 — outward/inward 링크 + 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S1 목록 렌더', () => {
  it('S1a: outward 링크가 label + 상대 이슈 key/summary/statusKey 로 표시된다', async () => {
    // server.use()로 직접 seed — linkStore module 인스턴스 공유 신뢰 대신 핸들러 override 방식
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({
          data: {
            outward: [
              {
                id: 1,
                linkType: 'BLOCKS',
                direction: 'OUTWARD',
                label: 'blocks',
                otherIssue: { key: 'ATLAS-2', summary: '차단된 이슈', statusKey: 'open' },
              },
            ],
            inward: [],
          },
        }),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    expect(await screen.findByText('blocks')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    expect(screen.getByText('차단된 이슈')).toBeInTheDocument()
  })

  it('S1b: inward 링크가 label + 상대 이슈 정보로 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({
          data: {
            outward: [],
            inward: [
              {
                id: 2,
                linkType: 'RELATES',
                direction: 'INWARD',
                label: 'is related to',
                otherIssue: { key: 'ATLAS-3', summary: '연관 이슈', statusKey: 'in_progress' },
              },
            ],
          },
        }),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    expect(await screen.findByText('is related to')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-3')).toBeInTheDocument()
    expect(screen.getByText('연관 이슈')).toBeInTheDocument()
  })

  it('S1c: 링크가 없으면 빈 상태 메시지가 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    expect(await screen.findByText(/링크가 없습니다/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 링크 추가 happy path
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S2 링크 추가 happy path', () => {
  it('S2a: 유형 선택 + 대상 키 입력 후 추가 버튼 클릭 시 링크가 목록에 나타난다', async () => {
    const user = userEvent.setup()
    // stateful mock — GET 호출 횟수로 응답 분기
    let getCallCount = 0
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () => {
        getCallCount++
        if (getCallCount === 1) {
          return HttpResponse.json({ data: { outward: [], inward: [] } })
        }
        // invalidate 후 두 번째 GET — 생성된 링크 포함
        return HttpResponse.json({
          data: {
            outward: [
              {
                id: 1,
                linkType: 'BLOCKS',
                direction: 'OUTWARD',
                label: 'blocks',
                otherIssue: { key: 'ATLAS-2', summary: 'ATLAS-2 이슈', statusKey: 'open' },
              },
            ],
            inward: [],
          },
        })
      }),
      http.post('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json(
          {
            data: {
              id: 1,
              linkType: 'BLOCKS',
              direction: 'OUTWARD',
              label: 'blocks',
              otherIssue: { key: 'ATLAS-2', summary: 'ATLAS-2 이슈', statusKey: 'open' },
            },
          },
          { status: 201 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    // 로딩 완료 대기
    await screen.findByText(/링크가 없습니다/)

    // 유형 select — "blocks" 선택 (native select이므로 selectOptions 사용 가능)
    const linkTypeSelect = screen.getByRole('combobox', { name: /링크 유형/ })
    await user.selectOptions(linkTypeSelect, 'blocks')

    // 대상 키 입력
    const targetInput = screen.getByRole('textbox', { name: /대상 이슈 키/ })
    await user.type(targetInput, 'ATLAS-2')

    // 추가 버튼 클릭
    const addButton = screen.getByRole('button', { name: /링크 추가/ })
    await user.click(addButton)

    // 링크 생성 후 목록 갱신
    expect(await screen.findByText('ATLAS-2')).toBeInTheDocument()
  })

  it('S2b: 대상 키가 비어있으면 추가 버튼이 disabled 된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByText(/링크가 없습니다/)

    const addButton = screen.getByRole('button', { name: /링크 추가/ })
    expect(addButton).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 에러 인라인 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S3 에러 인라인 표시', () => {
  it('S3a: LINK_SELF_REFERENCE(422) → 자기 참조 에러 메시지가 인라인 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.post('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json(
          { errorCode: 'LINK_SELF_REFERENCE', message: '' },
          { status: 422 },
        ),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByText(/링크가 없습니다/)

    const targetInput = screen.getByRole('textbox', { name: /대상 이슈 키/ })
    await user.type(targetInput, 'ATLAS-1')
    await user.click(screen.getByRole('button', { name: /링크 추가/ }))

    expect(await screen.findByText(/자기 자신/)).toBeInTheDocument()
  })

  it('S3b: DUPLICATE_LINK(409) → 중복 링크 에러 메시지가 인라인 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.post('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json(
          { errorCode: 'DUPLICATE_LINK', message: '' },
          { status: 409 },
        ),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByText(/링크가 없습니다/)

    const targetInput = screen.getByRole('textbox', { name: /대상 이슈 키/ })
    await user.type(targetInput, 'ATLAS-2')
    await user.click(screen.getByRole('button', { name: /링크 추가/ }))

    expect(await screen.findByText(/이미.*링크/)).toBeInTheDocument()
  })

  it('S3c: ISSUE_NOT_FOUND(404) → 이슈 없음 에러 메시지가 인라인 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.post('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_NOT_FOUND', message: '' },
          { status: 404 },
        ),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByText(/링크가 없습니다/)

    const targetInput = screen.getByRole('textbox', { name: /대상 이슈 키/ })
    await user.type(targetInput, 'UNKNOWN-9')
    await user.click(screen.getByRole('button', { name: /링크 추가/ }))

    expect(await screen.findByText(/이슈를 찾을 수 없습니다/)).toBeInTheDocument()
  })

  it('S3d: LINK_CYCLE(409) → 순환 참조 에러 메시지가 인라인 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.post('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json(
          { errorCode: 'LINK_CYCLE', message: '' },
          { status: 409 },
        ),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByText(/링크가 없습니다/)

    const targetInput = screen.getByRole('textbox', { name: /대상 이슈 키/ })
    await user.type(targetInput, 'ATLAS-3')
    await user.click(screen.getByRole('button', { name: /링크 추가/ }))

    expect(await screen.findByText(/순환/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. 링크 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S7 링크 제거', () => {
  it('S7a: 행 제거 버튼 클릭 시 해당 링크가 목록에서 사라진다', async () => {
    const user = userEvent.setup()
    let linkList = [
      {
        id: 10,
        linkType: 'CLONES',
        direction: 'OUTWARD',
        label: 'clones',
        otherIssue: { key: 'ATLAS-5', summary: '클론 대상', statusKey: 'open' },
      },
    ]
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: linkList, inward: [] } }),
      ),
      http.delete('/api/v1/issues/ATLAS-1/links/:linkId', ({ params }) => {
        const id = parseInt(params['linkId'] as string, 10)
        linkList = linkList.filter((l) => l.id !== id)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    // 링크 렌더 대기
    await screen.findByText('ATLAS-5')

    // 제거 버튼 클릭
    const removeButton = screen.getByRole('button', { name: /링크 제거/ })
    await user.click(removeButton)

    // 링크 사라짐 확인
    await waitFor(() => {
      expect(screen.queryByText('ATLAS-5')).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. 부모 이슈 섹션
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S8 부모 이슈 섹션', () => {
  it('S8a: parent prop이 있으면 KEY + 요약 + 해제 버튼이 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={{ key: 'ATLAS-1', summary: '상위 이슈 제목' }}
      />,
      { wrapper: Wrapper },
    )

    // ATLAS-1 링크 텍스트 확인
    expect(await screen.findByText('ATLAS-1')).toBeInTheDocument()
    expect(screen.getByText('상위 이슈 제목')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /해제/ })).toBeInTheDocument()
  })

  it('S8b: parent prop이 null이면 키 input + 지정 버튼이 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-2" parent={null} />, { wrapper: Wrapper })

    // 부모 지정 input이 존재해야 함
    expect(await screen.findByRole('textbox', { name: /부모 이슈 키/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /부모 지정/ })).toBeInTheDocument()
  })

  it('S8c: 부모 해제 버튼 클릭 시 clearParent mutation이 호출된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.patch('/api/v1/issues/ATLAS-2/parent', () =>
        HttpResponse.json({ data: { key: 'ATLAS-2' } }),
      ),
    )
    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={{ key: 'ATLAS-1', summary: '상위 이슈 제목' }}
      />,
      { wrapper: Wrapper },
    )

    expect(await screen.findByText('ATLAS-1'))
    const clearButton = screen.getByRole('button', { name: /해제/ })
    await user.click(clearButton)

    // 에러 없이 완료 (PARENT_ 에러코드가 표시되지 않음)
    await waitFor(() => {
      expect(screen.queryByText(/PARENT_/)).not.toBeInTheDocument()
    })
  })

  it('S8d: PARENT_SELF_REFERENCE(422) → 자기 참조 에러 메시지가 인라인 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.patch('/api/v1/issues/ATLAS-1/parent', () =>
        HttpResponse.json(
          { errorCode: 'PARENT_SELF_REFERENCE', message: '' },
          { status: 422 },
        ),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByRole('textbox', { name: /부모 이슈 키/ })

    const parentInput = screen.getByRole('textbox', { name: /부모 이슈 키/ })
    await user.type(parentInput, 'ATLAS-1')
    await user.click(screen.getByRole('button', { name: /부모 지정/ }))

    expect(await screen.findByText(/자기 자신/)).toBeInTheDocument()
  })

  it('S8e: PARENT_CYCLE(409) → 순환 참조 에러 메시지가 인라인 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.patch('/api/v1/issues/ATLAS-1/parent', () =>
        HttpResponse.json(
          { errorCode: 'PARENT_CYCLE', message: '' },
          { status: 409 },
        ),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByRole('textbox', { name: /부모 이슈 키/ })

    const parentInput = screen.getByRole('textbox', { name: /부모 이슈 키/ })
    await user.type(parentInput, 'ATLAS-3')
    await user.click(screen.getByRole('button', { name: /부모 지정/ }))

    expect(await screen.findByText(/순환/)).toBeInTheDocument()
  })

  it('S8f: parent 404 에러 → 이슈 없음 에러 메시지가 인라인 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.patch('/api/v1/issues/ATLAS-1/parent', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_NOT_FOUND', message: '' },
          { status: 404 },
        ),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByRole('textbox', { name: /부모 이슈 키/ })

    const parentInput = screen.getByRole('textbox', { name: /부모 이슈 키/ })
    await user.type(parentInput, 'UNKNOWN-99')
    await user.click(screen.getByRole('button', { name: /부모 지정/ }))

    expect(await screen.findByText(/이슈를 찾을 수 없습니다/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S9. disabled 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S9 disabled', () => {
  it('S9a: disabled=true이면 추가 버튼과 input이 비활성화된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} disabled />, { wrapper: Wrapper })

    await screen.findByText(/링크가 없습니다/)

    const addButton = screen.getByRole('button', { name: /링크 추가/ })
    expect(addButton).toBeDisabled()

    const targetInput = screen.getByRole('textbox', { name: /대상 이슈 키/ })
    expect(targetInput).toBeDisabled()
  })

  it('S9b: disabled=true + parent prop 있으면 해제 버튼이 비활성화된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={{ key: 'ATLAS-1', summary: '상위 이슈' }}
        disabled
      />,
      { wrapper: Wrapper },
    )

    await screen.findByText('ATLAS-1')
    const clearButton = screen.getByRole('button', { name: /해제/ })
    expect(clearButton).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S10. linkType 소문자 전송 확인
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S10 linkType 소문자 전송', () => {
  it('S10a: 추가 시 linkType은 소문자로 전송된다 (백엔드 계약)', async () => {
    const user = userEvent.setup()
    let capturedLinkType: string | undefined

    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.post('/api/v1/issues/ATLAS-1/links', async ({ request }) => {
        const body = (await request.json()) as { targetKey?: string; linkType?: string }
        capturedLinkType = body.linkType
        return HttpResponse.json(
          {
            data: {
              id: 99,
              linkType: 'RELATES',
              direction: 'OUTWARD',
              label: 'is related to',
              otherIssue: { key: 'ATLAS-9', summary: '대상', statusKey: 'open' },
            },
          },
          { status: 201 },
        )
      }),
    )

    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByText(/링크가 없습니다/)

    const linkTypeSelect = screen.getByRole('combobox', { name: /링크 유형/ })
    await user.selectOptions(linkTypeSelect, 'relates')

    const targetInput = screen.getByRole('textbox', { name: /대상 이슈 키/ })
    await user.type(targetInput, 'ATLAS-9')

    await user.click(screen.getByRole('button', { name: /링크 추가/ }))

    await waitFor(() => {
      expect(capturedLinkType).toBe('relates')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S11. outward/inward 여러 링크 동시 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S11 다중 링크 렌더', () => {
  it('S11a: outward 와 inward 링크가 함께 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({
          data: {
            outward: [
              {
                id: 1,
                linkType: 'BLOCKS',
                direction: 'OUTWARD',
                label: 'blocks',
                otherIssue: { key: 'ATLAS-2', summary: '아웃바운드 이슈', statusKey: 'open' },
              },
            ],
            inward: [
              {
                id: 2,
                linkType: 'RELATES',
                direction: 'INWARD',
                label: 'is related to',
                otherIssue: { key: 'ATLAS-3', summary: '인바운드 이슈', statusKey: 'done' },
              },
            ],
          },
        }),
      ),
    )

    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    expect(await screen.findByText('ATLAS-2')).toBeInTheDocument()
    expect(screen.getByText('아웃바운드 이슈')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-3')).toBeInTheDocument()
    expect(screen.getByText('인바운드 이슈')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S13. 소속 에픽 섹션 (EpicSection) — FR-EP-01 D6 Task-5
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S13 소속 에픽 섹션', () => {
  // S13a: epic prop이 있으면 KEY + 요약 + 해제 버튼이 표시된다 (level-0 이슈 자격)
  it('S13a: epic prop이 있으면 에픽 KEY + 요약 + 해제 버튼이 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={{ key: 'ATLAS-10', summary: '대형 에픽 제목' }}
        showEpicSection
      />,
      { wrapper: Wrapper },
    )

    await screen.findByText('ATLAS-10')
    expect(screen.getByText('대형 에픽 제목')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /해제/ })).toBeInTheDocument()
  })

  // S13b: epic prop이 null이면 에픽 키 input + 지정 버튼이 표시된다
  it('S13b: epic prop이 null이면 에픽 키 input + 지정 버튼이 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={null}
        showEpicSection
      />,
      { wrapper: Wrapper },
    )

    expect(await screen.findByRole('textbox', { name: /에픽 이슈 키/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /에픽 지정/ })).toBeInTheDocument()
  })

  // S13c: showEpicSection이 false이면 소속 에픽 섹션이 렌더되지 않는다
  it('S13c: showEpicSection=false이면 소속 에픽 섹션이 렌더되지 않는다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-10/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-10"
        parent={null}
        epic={null}
        showEpicSection={false}
      />,
      { wrapper: Wrapper },
    )

    await screen.findByText(/링크가 없습니다/)
    expect(screen.queryByRole('textbox', { name: /에픽 이슈 키/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /에픽 지정/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/소속 에픽/)).not.toBeInTheDocument()
  })

  // S13d: showEpicSection prop 미전달(기본값 false)이면 소속 에픽 섹션이 노출되지 않는다
  it('S13d: showEpicSection prop 미전달(기본값 false)이면 소속 에픽 섹션이 노출되지 않는다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByText(/링크가 없습니다/)
    expect(screen.queryByText(/소속 에픽/)).not.toBeInTheDocument()
  })

  // S13e: disabled=true이면 에픽 지정 버튼과 input이 비활성화된다
  it('S13e: disabled=true이면 에픽 지정 input과 버튼이 비활성화된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={null}
        showEpicSection
        disabled
      />,
      { wrapper: Wrapper },
    )

    await screen.findByText(/링크가 없습니다/)
    const epicInput = screen.getByRole('textbox', { name: /에픽 이슈 키/ })
    expect(epicInput).toBeDisabled()
    // 에픽 지정 버튼 — 빈 input이면 disabled
    expect(screen.getByRole('button', { name: /에픽 지정/ })).toBeDisabled()
  })

  // S13f: disabled=true + epic 있으면 해제 버튼이 비활성화된다
  it('S13f: disabled=true + epic prop 있으면 해제 버튼이 비활성화된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
    )
    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={{ key: 'ATLAS-10', summary: '에픽 제목' }}
        showEpicSection
        disabled
      />,
      { wrapper: Wrapper },
    )

    await screen.findByText('ATLAS-10')
    const clearButton = screen.getByRole('button', { name: /해제/ })
    expect(clearButton).toBeDisabled()
  })

  // S13g: 에픽 지정 성공 — POST /api/v1/issues/{epicKey}/epic-children 호출
  it('S13g: 에픽 키 입력 후 지정 버튼 클릭 시 useSetIssueEpic mutation이 호출된다', async () => {
    const user = userEvent.setup()
    let postCalled = false
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      // useSetIssueEpic: POST /api/v1/issues/{epicKey}/epic-children body { childKey }
      http.post('/api/v1/issues/ATLAS-10/epic-children', () => {
        postCalled = true
        return HttpResponse.json(
          {
            data: {
              key: 'ATLAS-2',
              summary: '자식 이슈',
              typeKey: 'task',
              currentStateKey: 'open',
            },
          },
          { status: 201 },
        )
      }),
      // onSettled invalidate issueQueryKey(childKey)
      http.get('/api/v1/issues/ATLAS-2', () =>
        HttpResponse.json({
          data: {
            key: 'ATLAS-2',
            id: '00000000-0000-0000-0000-000000000002',
            projectKey: 'ATLAS',
            summary: '자식 이슈',
            currentStateKey: 'open',
            reporterId: '00000000-0000-0000-0000-000000000001',
            assigneeId: null,
            version: 1,
            createdAt: null,
            updatedAt: null,
            typeId: 3,
            typeKey: 'task',
            typeName: '작업',
            description: null,
            descriptionHtml: null,
            priority: 3,
            priorityName: 'Medium',
            labels: [],
            environment: null,
            impact: null,
            impactName: null,
          },
        }),
      ),
      // onSettled invalidate epicChildrenKey
      http.get('/api/v1/issues/ATLAS-10/epic-children', () =>
        HttpResponse.json({ data: { children: [] } }),
      ),
    )

    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={null}
        showEpicSection
      />,
      { wrapper: Wrapper },
    )

    await screen.findByRole('textbox', { name: /에픽 이슈 키/ })

    const epicInput = screen.getByRole('textbox', { name: /에픽 이슈 키/ })
    await user.type(epicInput, 'ATLAS-10')
    await user.click(screen.getByRole('button', { name: /에픽 지정/ }))

    await waitFor(() => {
      expect(postCalled).toBe(true)
    })
  })

  // S13h: 에픽 해제 성공 — DELETE /api/v1/issues/{epicKey}/epic-children/{childKey} 호출
  it('S13h: 해제 버튼 클릭 시 useClearIssueEpic mutation이 호출된다', async () => {
    const user = userEvent.setup()
    let deleteCalled = false
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      // useClearIssueEpic: DELETE /api/v1/issues/{epicKey}/epic-children/{childKey}
      http.delete('/api/v1/issues/ATLAS-10/epic-children/ATLAS-2', () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
      // onSettled invalidate issueQueryKey(childKey)
      http.get('/api/v1/issues/ATLAS-2', () =>
        HttpResponse.json({
          data: {
            key: 'ATLAS-2',
            id: '00000000-0000-0000-0000-000000000002',
            projectKey: 'ATLAS',
            summary: '자식 이슈',
            currentStateKey: 'open',
            reporterId: '00000000-0000-0000-0000-000000000001',
            assigneeId: null,
            version: 1,
            createdAt: null,
            updatedAt: null,
            typeId: 3,
            typeKey: 'task',
            typeName: '작업',
            description: null,
            descriptionHtml: null,
            priority: 3,
            priorityName: 'Medium',
            labels: [],
            environment: null,
            impact: null,
            impactName: null,
          },
        }),
      ),
      http.get('/api/v1/issues/ATLAS-10/epic-children', () =>
        HttpResponse.json({ data: { children: [] } }),
      ),
    )

    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={{ key: 'ATLAS-10', summary: '대형 에픽' }}
        showEpicSection
      />,
      { wrapper: Wrapper },
    )

    await screen.findByText('ATLAS-10')
    await user.click(screen.getByRole('button', { name: /해제/ }))

    await waitFor(() => {
      expect(deleteCalled).toBe(true)
    })
  })

  // S13i: 에픽 지정 오류 — ISSUE_EPIC_TARGET_NOT_EPIC(422) → 인라인 에러 표시
  it('S13i: ISSUE_EPIC_TARGET_NOT_EPIC(422) → 인라인 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.post('/api/v1/issues/ATLAS-3/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_TARGET_NOT_EPIC', message: '' },
          { status: 422 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={null}
        showEpicSection
      />,
      { wrapper: Wrapper },
    )

    await screen.findByRole('textbox', { name: /에픽 이슈 키/ })
    const epicInput = screen.getByRole('textbox', { name: /에픽 이슈 키/ })
    await user.type(epicInput, 'ATLAS-3')
    await user.click(screen.getByRole('button', { name: /에픽 지정/ }))

    // ISSUE_EPIC_TARGET_NOT_EPIC → epicChildrenStrings.errorDefault (매핑 없음이면 fallback)
    // 단, ISSUE_EPIC_TARGET_NOT_EPIC은 EpicChildrenSection의 EPIC_CHILD_ERROR_MESSAGES에 없음
    // → issueLinkStrings의 에픽 에러 fallback이 표시된다
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  // S13j: 에픽 지정 오류 — ISSUE_EPIC_CHILD_ALREADY_LINKED(409) → 인라인 에러 표시
  it('S13j: ISSUE_EPIC_CHILD_ALREADY_LINKED(409) → 인라인 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.post('/api/v1/issues/ATLAS-10/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_CHILD_ALREADY_LINKED', message: '' },
          { status: 409 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={null}
        showEpicSection
      />,
      { wrapper: Wrapper },
    )

    await screen.findByRole('textbox', { name: /에픽 이슈 키/ })
    const epicInput = screen.getByRole('textbox', { name: /에픽 이슈 키/ })
    await user.type(epicInput, 'ATLAS-10')
    await user.click(screen.getByRole('button', { name: /에픽 지정/ }))

    expect(await screen.findByText(/이미.*에픽/)).toBeInTheDocument()
  })

  // S13k: 에픽 지정 오류 — ISSUE_EPIC_OR_CHILD_NOT_FOUND(404) → 인라인 에러 표시
  it('S13k: ISSUE_EPIC_OR_CHILD_NOT_FOUND(404) → 이슈 없음 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/issues/ATLAS-2/links', () =>
        HttpResponse.json({ data: { outward: [], inward: [] } }),
      ),
      http.post('/api/v1/issues/UNKNOWN-99/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: '' },
          { status: 404 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(
      <IssueLinksPanel
        issueKey="ATLAS-2"
        parent={null}
        epic={null}
        showEpicSection
      />,
      { wrapper: Wrapper },
    )

    await screen.findByRole('textbox', { name: /에픽 이슈 키/ })
    const epicInput = screen.getByRole('textbox', { name: /에픽 이슈 키/ })
    await user.type(epicInput, 'UNKNOWN-99')
    await user.click(screen.getByRole('button', { name: /에픽 지정/ }))

    expect(await screen.findByText(/이슈를 찾을 수 없습니다/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S12. within 컨테이너 격리 테스트 — 제거 버튼이 각 행에만 존재
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLinksPanel — S12 행별 제거 버튼 격리', () => {
  it('S12a: 각 링크 행에 독립된 제거 버튼이 존재한다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/links', () =>
        HttpResponse.json({
          data: {
            outward: [
              {
                id: 11,
                linkType: 'BLOCKS',
                direction: 'OUTWARD',
                label: 'blocks',
                otherIssue: { key: 'ATLAS-10', summary: '첫 번째', statusKey: 'open' },
              },
              {
                id: 12,
                linkType: 'BLOCKS',
                direction: 'OUTWARD',
                label: 'blocks',
                otherIssue: { key: 'ATLAS-11', summary: '두 번째', statusKey: 'open' },
              },
            ],
            inward: [],
          },
        }),
      ),
    )

    const Wrapper = createWrapper()
    render(<IssueLinksPanel issueKey="ATLAS-1" parent={null} />, { wrapper: Wrapper })

    await screen.findByText('ATLAS-10')

    const rows = screen.getAllByRole('listitem')
    expect(rows.length).toBeGreaterThanOrEqual(2)

    // 첫 번째 행에 제거 버튼 존재 확인
    const firstRow = rows[0]
    expect(firstRow).toBeDefined()
    if (firstRow !== undefined) {
      expect(within(firstRow).getByRole('button', { name: /링크 제거/ })).toBeInTheDocument()
    }
  })
})
