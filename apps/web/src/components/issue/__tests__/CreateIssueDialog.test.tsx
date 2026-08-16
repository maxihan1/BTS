// CreateIssueDialog 단위 테스트 — 제어 컴포넌트 계약 + 고유 접근성 이름 (FR-UX-09 F2 T7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
import { server } from '@/test/server'
import { issueHandlers } from '@/mocks/issue-handlers'
import { projectHandlers } from '@/mocks/project-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'
import { issueCreateStrings } from '@/i18n/ko'
import type { CustomField } from '@/api/custom-fields.types'

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  useParams: () => ({}),
  useSearch: () => ({}),
}))

vi.mock('@/hooks/use-custom-fields', () => ({
  useCustomFields: vi.fn(),
  CUSTOM_FIELD_KEYS: { list: (k: string) => ['custom-fields', k] },
}))

import { useCustomFields } from '@/hooks/use-custom-fields'

const EMPTY_CUSTOM_FIELDS_RESULT = {
  data: [] as CustomField[],
  isLoading: false,
  isError: false,
  isPending: false,
  isSuccess: true,
  error: null,
  status: 'success' as const,
  fetchStatus: 'idle' as const,
  dataUpdatedAt: 0,
  errorUpdatedAt: 0,
  failureCount: 0,
  failureReason: null,
  isFetched: true,
  isFetchedAfterMount: true,
  isFetching: false,
  isInitialLoading: false,
  isLoadingError: false,
  isPlaceholderData: false,
  isRefetchError: false,
  isRefetching: false,
  isStale: false,
  refetch: vi.fn(),
}

beforeEach(() => {
  vi.mocked(useCustomFields).mockReturnValue(EMPTY_CUSTOM_FIELDS_RESULT as never)
  localStorage.clear()
  server.use(...issueHandlers, ...projectHandlers, ...issueTypeHandlers, ...userHandlers)
})

/** 모달을 렌더한다. */
function renderDialog(props: {
  open: boolean
  onOpenChange?: (open: boolean) => void
  onCreated?: (key: string) => void
  initialProjectKey?: string
}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <CreateIssueDialog
        open={props.open}
        onOpenChange={props.onOpenChange ?? vi.fn()}
        onCreated={props.onCreated}
        initialProjectKey={props.initialProjectKey}
      />
    </QueryClientProvider>,
  )
}

describe('CreateIssueDialog — 제어 컴포넌트 계약 (FR-1)', () => {
  it('open=false 면 아무것도 렌더하지 않는다', () => {
    renderDialog({ open: false })

    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('★고유한 접근성 이름을 갖는다 — role="dialog" 가 e2e 에 164 발생이라 이름이 없으면 충돌한다', async () => {
    renderDialog({ open: true })

    const dialog = await screen.findByRole('dialog', { name: issueCreateStrings.dialogTitle })
    expect(dialog).toBeInTheDocument()
  })

  it('취소를 누르면 onOpenChange(false) 가 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    renderDialog({ open: true, onOpenChange })

    await user.click(await screen.findByRole('button', { name: issueCreateStrings.cancelButton }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('Esc 를 누르면 onOpenChange(false) 가 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    renderDialog({ open: true, onOpenChange })

    await screen.findByRole('dialog')
    await user.keyboard('{Escape}')

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('CreateIssueDialog — 생성 성공 (FR-16)', () => {
  it('생성에 성공하면 모달을 닫고 onCreated(key) 를 부른다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    const onCreated = vi.fn()
    renderDialog({ open: true, onOpenChange, onCreated })

    await screen.findByRole('dialog')
    await waitFor(() => {
      const sel = screen.getByLabelText(issueCreateStrings.projectKeyLabel) as HTMLSelectElement
      expect(sel.querySelectorAll('option').length).toBeGreaterThan(1)
    })
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '모달에서 만든 이슈')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(onCreated).toHaveBeenCalled())
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 게이트 2 C-2 — 제출 중 피드백이 모달에도 있어야 한다
//
// 라우트 경로의 내부 제출 버튼은 `disabled={mutation.isPending}` 인데, 모달 푸터 버튼은
// 폼 **밖**에 있어(NFR-2 스크롤 경계) 그 상태를 못 받았다. 눌러도 아무 반응이 없어
// 사용자는 안 눌린 줄 알고 다시 누른다. 두 경로가 같은 피드백을 줘야 한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('CreateIssueDialog — 제출 중 피드백 (게이트 2 C-2)', () => {
  it('제출 중에는 푸터 만들기 버튼이 비활성화되고 진행 중 문구로 바뀐다', async () => {
    const user = userEvent.setup()
    // 응답을 붙잡아 pending 구간을 관찰 가능하게 만든다
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.post('/api/v1/issues', async () => {
        await held
        return HttpResponse.json({ data: { key: 'ATLAS-1' } }, { status: 201 })
      }),
    )
    renderDialog({ open: true })

    await screen.findByRole('dialog')
    await waitFor(() => {
      const sel = screen.getByLabelText(issueCreateStrings.projectKeyLabel) as HTMLSelectElement
      expect(sel.querySelectorAll('option').length).toBeGreaterThan(1)
    })
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제출 중 표시 확인')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    const pending = await screen.findByRole('button', {
      name: issueCreateStrings.submitButtonPending,
    })
    expect(pending).toBeDisabled()

    release()
    await settlePendingMutations()

    // ★완료 프레임 재단언 (부채 매핑 41). 위 진입 프레임 단언만으로는 진행 신호가 `true` 만
    //   보내고 `false` 를 **안 보내도** 초록이다 — 사용자는 생성이 끝난 뒤에도 푸터 버튼이
    //   「생성 중…」에 비활성으로 갇힌 화면을 본다. 모달을 닫지 않고 계속 만드는 연쇄 생성
    //   경로가 그 자리에서 막힌다. `open` 은 고정 프로프라 성공 뒤에도 모달이 남는다.
    const settled = await screen.findByRole('button', {
      name: issueCreateStrings.submitButton,
    })
    expect(settled).not.toBeDisabled()
  })
})

describe('CreateIssueDialog — 스크롤 경계 (NFR-2)', () => {
  it('본문만 스크롤하고 만들기 버튼은 스크롤 영역 밖에 있다', async () => {
    const { container } = renderDialog({ open: true })

    await screen.findByRole('dialog')
    const scrollArea = container.ownerDocument.querySelector('[data-testid="create-issue-scroll"]')
    expect(scrollArea).not.toBeNull()

    const submit = screen.getByRole('button', { name: issueCreateStrings.submitButton })
    expect(scrollArea?.contains(submit)).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-09 F3 — 진입점이 프로젝트를 명시로 넘긴다 (스펙 §8 D-A)
//
// ★왜 활성 프로젝트 경유로 두지 않았나.
// 보드·백로그는 `/projects/$projectKey/*` 라 `useTrackActiveProject` 가 활성 프로젝트를
// 기록한다. 그런데 그 기록에는 `isKnownProject`(프로젝트 목록 대조) 가드가 있어서,
// **목록이 아직 도착하지 않은 순간에는 활성 프로젝트가 직전 값이거나 없다.**
// 그 순간 모달을 열면 **다른 프로젝트가 채워진 채로 열린다.** 페이지는 projectKey 를
// 직접 알고 있으므로 전역 스토어를 한 바퀴 돌 이유가 없다.
// ─────────────────────────────────────────────────────────────────────────────

describe('CreateIssueDialog — 프로젝트 명시 전달 (F3 FR-7)', () => {
  it('initialProjectKey 를 주면 그 프로젝트가 선택된 채로 열린다', async () => {
    renderDialog({ open: true, initialProjectKey: 'MIDDLE' })

    const select = await screen.findByLabelText(issueCreateStrings.projectKeyLabel)
    await waitFor(() => {
      expect((select as HTMLSelectElement).value).toBe('MIDDLE')
    })
  })

  it('★미전달이면 기존 기본값 경로가 그대로 산다 (무회귀 — 상단바·딥링크 2경로)', async () => {
    renderDialog({ open: true })

    const select = await screen.findByLabelText(issueCreateStrings.projectKeyLabel)
    await waitFor(() => {
      // 활성 프로젝트가 없으면 목록 첫 번째 — 기존 동작
      expect((select as HTMLSelectElement).value).toBe('ATLAS')
    })
  })
})
