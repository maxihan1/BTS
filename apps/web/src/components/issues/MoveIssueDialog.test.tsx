// 이슈 이동 마법사 Dialog 단위 테스트 — FR-MV-01 Task 4
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { MoveIssueDialog } from './MoveIssueDialog'

// sonner toast mock
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// navigate mock
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const srcCompId = '550e8400-e29b-41d4-a716-446655440001'
const tgtCompId = '550e8400-e29b-41d4-a716-446655440004'
const srcVerIdAffects = '550e8400-e29b-41d4-a716-446655440002'
const tgtVerIdAffects = '550e8400-e29b-41d4-a716-446655440005'

const componentFixture = {
  id: srcCompId,
  projectId: '550e8400-e29b-41d4-a716-446655440010',
  name: 'Backend',
  description: null,
  leadUserId: null,
}
const targetComponentFixture = {
  id: tgtCompId,
  projectId: '550e8400-e29b-41d4-a716-446655440011',
  name: 'Backend',
  description: null,
  leadUserId: null,
}
const versionFixture = {
  id: srcVerIdAffects,
  projectId: '550e8400-e29b-41d4-a716-446655440010',
  name: 'v1.0',
  description: null,
  startDate: null,
  releaseDate: null,
  status: 'UNRELEASED' as const,
}
const targetVersionFixture = {
  id: tgtVerIdAffects,
  projectId: '550e8400-e29b-41d4-a716-446655440011',
  name: 'v1.0',
  description: null,
  startDate: null,
  releaseDate: null,
  status: 'UNRELEASED' as const,
}
const customFieldDefFixture = {
  id: '550e8400-e29b-41d4-a716-446655440003',
  projectId: '550e8400-e29b-41d4-a716-446655440011',
  key: 'cf_priority',
  name: '우선순위 레이블',
  description: null,
  fieldType: 'SHORT_TEXT' as const,
  required: true,
  displayOrder: 1,
  options: [],
}

/** 완전 호환 preview — 상태 호환, 컴포넌트/버전 자동매핑, 필수 커스텀필드 없음 */
const compatiblePreviewFixture = {
  version: 5,
  workflow: {
    compatible: true,
    targetStates: [
      { key: 'open', name: '열림', isDone: false },
      { key: 'done', name: '완료', isDone: true },
    ],
    suggestedStateKey: 'open',
  },
  components: {
    current: [componentFixture],
    target: [targetComponentFixture],
    autoMapping: { [srcCompId]: tgtCompId },
  },
  affectsVersions: {
    current: [versionFixture],
    target: [targetVersionFixture],
    autoMapping: { [srcVerIdAffects]: tgtVerIdAffects },
  },
  fixVersions: {
    current: [],
    target: [],
    autoMapping: {},
  },
  customFields: {
    removed: [],
    requiredMissing: [],
  },
  subtasks: [],
}

/** 비호환 preview — 상태 불일치, 컴포넌트 미매핑, 필수 커스텀필드 있음 */
const incompatiblePreviewFixture = {
  version: 5,
  workflow: {
    compatible: false,
    targetStates: [
      { key: 'todo', name: '할 일', isDone: false },
      { key: 'done', name: '완료', isDone: true },
    ],
    suggestedStateKey: 'todo',
  },
  components: {
    current: [componentFixture],
    target: [targetComponentFixture],
    autoMapping: { [srcCompId]: null },
  },
  affectsVersions: {
    current: [],
    target: [],
    autoMapping: {},
  },
  fixVersions: {
    current: [],
    target: [],
    autoMapping: {},
  },
  customFields: {
    removed: [],
    requiredMissing: [customFieldDefFixture],
  },
  subtasks: [],
}

/** 서브태스크 포함 preview */
const subtaskPreviewFixture = {
  version: 5,
  workflow: {
    compatible: true,
    targetStates: [
      { key: 'open', name: '열림', isDone: false },
    ],
    suggestedStateKey: 'open',
  },
  components: {
    current: [],
    target: [],
    autoMapping: {},
  },
  affectsVersions: {
    current: [],
    target: [],
    autoMapping: {},
  },
  fixVersions: {
    current: [],
    target: [],
    autoMapping: {},
  },
  customFields: {
    removed: [],
    requiredMissing: [],
  },
  subtasks: [
    {
      issueKey: 'ATLAS-2',
      issueTypeKey: 'subtask',
      version: 3,
      workflow: {
        compatible: false,
        targetStates: [
          { key: 'todo', name: '할 일', isDone: false },
        ],
        suggestedStateKey: 'todo',
      },
      components: { current: [], target: [], autoMapping: {} },
      affectsVersions: { current: [], target: [], autoMapping: {} },
      fixVersions: { current: [], target: [], autoMapping: {} },
      customFields: { removed: [], requiredMissing: [] },
    },
  ],
}

const moveResponseFixture = {
  issueKey: 'INFRA-5',
  previousKey: 'ATLAS-12',
  movedSubtasks: [],
}

const moveResponseWithSubtasksFixture = {
  issueKey: 'INFRA-5',
  previousKey: 'ATLAS-12',
  movedSubtasks: [
    { previousKey: 'ATLAS-2', issueKey: 'INFRA-6' },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

interface RenderProps {
  readonly issueKey?: string
  readonly issueVersion?: number
}

function renderDialog({ issueKey = 'ATLAS-12', issueVersion = 5 }: RenderProps = {}) {
  const client = makeClient()
  const onOpenChange = vi.fn()
  render(
    <QueryClientProvider client={client}>
      <MoveIssueDialog
        issueKey={issueKey}
        issueVersion={issueVersion}
        open={true}
        onOpenChange={onOpenChange}
      />
    </QueryClientProvider>,
  )
  return { onOpenChange }
}

// ─────────────────────────────────────────────────────────────────────────────
// T4-1. Step 1 — 대상 프로젝트 키 입력 + preview 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-1: Step1 대상 키 입력 → preview 호출', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ data: compatiblePreviewFixture }),
      ),
    )
  })

  it('다이얼로그가 Step 1(대상 프로젝트 키 입력) 상태로 열린다', () => {
    renderDialog()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText(/대상 프로젝트 키/i)).toBeInTheDocument()
  })

  it('흡수 후 우상단 X 닫기 버튼(Jira 시각 통일)이 렌더된다', () => {
    renderDialog()

    // ui/dialog 래퍼로 흡수되면 DialogContent가 우상단 X(sr-only "Close")를 강제 렌더한다.
    expect(screen.getByRole('button', { name: /close/i })).toBeTruthy()
  })

  it('대상 키 입력 후 "다음" 클릭 시 preview를 호출하고 Step 2로 이동한다', async () => {
    renderDialog()
    const user = userEvent.setup()
    const input = screen.getByLabelText(/대상 프로젝트 키/i)
    await user.type(input, 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      // Step 2 — 매핑 확인 섹션이 표시됨
      expect(screen.getByText(/이동 매핑 확인/i)).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-2. Step 2 — 노드별 매핑 폼 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-2: Step2 노드별 매핑 폼 렌더', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ data: incompatiblePreviewFixture }),
      ),
    )
  })

  it('비호환 상태가 있으면 상태 선택 select가 표시된다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByText(/대상 상태 선택/i)).toBeInTheDocument()
    })
  })

  it('필수 커스텀 필드가 있으면 입력 필드가 표시된다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByLabelText(/우선순위 레이블/i)).toBeInTheDocument()
    })
  })

  it('비호환 상태 미선택 시 이동 버튼이 비활성화된다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByText(/이동 매핑 확인/i)).toBeInTheDocument()
    })

    // 필수 필드 미입력 상태에서 이동 버튼 비활성
    const moveButton = screen.getByRole('button', { name: /이동/i })
    expect(moveButton).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-3. 단건 이동 (subtasks 빈) — move 성공
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-3: 단건 이동 (subtasks=[]) — move 성공', () => {
  let capturedBody: unknown = null

  beforeEach(() => {
    capturedBody = null
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ data: compatiblePreviewFixture }),
      ),
      http.post('/api/v1/issues/:key/move', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: moveResponseFixture })
      }),
    )
  })

  it('완전 호환 이슈 이동 시 subtasks=[]로 move를 호출한다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByText(/이동 매핑 확인/i)).toBeInTheDocument()
    })

    const moveButton = screen.getByRole('button', { name: /이동/i })
    await user.click(moveButton)

    await waitFor(() => {
      expect(capturedBody).toBeDefined()
    })

    const body = capturedBody as Record<string, unknown>
    expect(body['targetProjectKey']).toBe('INFRA')
    expect(body['expectedVersion']).toBe(5)
    expect(Array.isArray(body['subtasks'])).toBe(true)
    expect((body['subtasks'] as unknown[]).length).toBe(0)
  })

  it('move 성공 시 navigate로 새 키 상세 페이지로 이동한다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByText(/이동 매핑 확인/i)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /이동/i }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith(
        expect.objectContaining({ params: { key: 'INFRA-5' } }),
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-4. 서브태스크 동반 이동 — 노드별 페이로드
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-4: 서브태스크 동반 이동 — 노드별 페이로드 + movedSubtasks 토스트', () => {
  let capturedBody: unknown = null

  beforeEach(() => {
    capturedBody = null
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ data: subtaskPreviewFixture }),
      ),
      http.post('/api/v1/issues/:key/move', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: moveResponseWithSubtasksFixture })
      }),
    )
  })

  it('서브태스크가 있으면 자식 섹션이 렌더된다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      // 자식 이슈 키가 표시됨
      expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    })
  })

  it('자식 비호환 상태 선택 후 move 페이로드에 자식 issueKey + version + targetStateIsDone이 포함된다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    })

    // 자식 섹션에서 상태 선택
    const atlas2Section = screen.getByTestId('node-section-ATLAS-2')
    const stateSelect = within(atlas2Section).getByRole('combobox', { name: /대상 상태 선택/i })
    await user.selectOptions(stateSelect, 'todo')

    // 이동 버튼 클릭
    await user.click(screen.getByRole('button', { name: /이동/i }))

    await waitFor(() => {
      expect(capturedBody).toBeDefined()
    })

    const body = capturedBody as Record<string, unknown>
    const subtasks = body['subtasks'] as Array<Record<string, unknown>>
    expect(subtasks).toHaveLength(1)
    expect(subtasks[0]?.['issueKey']).toBe('ATLAS-2')
    expect(subtasks[0]?.['expectedVersion']).toBe(3)
    expect(subtasks[0]?.['targetStateKey']).toBe('todo')
    expect(subtasks[0]?.['targetStateIsDone']).toBe(false)
  })

  it('move 성공 시 movedSubtasks 토스트가 표시된다', async () => {
    const { toast } = await import('sonner')
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    })

    // 자식 상태 선택
    const atlas2Section = screen.getByTestId('node-section-ATLAS-2')
    const stateSelect = within(atlas2Section).getByRole('combobox', { name: /대상 상태 선택/i })
    await user.selectOptions(stateSelect, 'todo')

    await user.click(screen.getByRole('button', { name: /이동/i }))

    await waitFor(() => {
      expect(toast.success).toHaveBeenCalled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-5. targetStateIsDone — 선택한 상태의 isDone 값이 payload에 반영된다
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-5: targetStateIsDone = 선택한 targetState의 isDone', () => {
  let capturedBody: unknown = null

  beforeEach(() => {
    capturedBody = null
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({
          data: {
            ...incompatiblePreviewFixture,
            customFields: { removed: [], requiredMissing: [] },
          },
        }),
      ),
      http.post('/api/v1/issues/:key/move', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: moveResponseFixture })
      }),
    )
  })

  it('DONE 상태 선택 시 targetStateIsDone=true로 move 호출한다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByText(/이동 매핑 확인/i)).toBeInTheDocument()
    })

    // 루트 상태 select에서 done 선택
    const rootSection = screen.getByTestId('node-section-root')
    const stateSelect = within(rootSection).getByRole('combobox', { name: /대상 상태 선택/i })
    await user.selectOptions(stateSelect, 'done')

    await user.click(screen.getByRole('button', { name: /이동/i }))

    await waitFor(() => {
      expect(capturedBody).toBeDefined()
    })

    const body = capturedBody as Record<string, unknown>
    expect(body['targetStateKey']).toBe('done')
    expect(body['targetStateIsDone']).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-6. preview 실패 문구 — 403(권한 계열) vs 그 외
//
// 근거 전문(게이트를 안 붙이는 이유 · 문구 선택)은 **TODOS.md 「이동·임포트 진입점」 ②** 하나다.
// 여기에 사본을 두지 않는다 — 같은 논거가 4벌로 복제됐던 것을 2026-08-10 게이트2 리뷰가 잡았다.
//
// ★이 파일이 알아야 할 요지 하나. 서버 403 은 **세 원인**이 한 응답으로 합쳐진다 —
//   ① 대상 키 오타/미존재 ② 원본 UPDATE 없음 ③ 대상 CREATE 없음.
//   `MovePreviewService.kt:185-186` 이 두 권한을 **존재 확인(:190-192)보다 먼저** assert 하고,
//   운영 리졸버(`IdentityAccessIssuePermissionResolver.kt:77`)는 미존재 프로젝트를 거부로
//   판정하기 때문이다. 그래서 문구는 어느 하나로 단정하지 않고 **키 확인 → 권한** 순으로 말한다.
//
// ★404(PROJECT_NOT_FOUND) 케이스를 두지 않는 이유. 위 순서 때문에 prod 는 그 입력에 403 을 낸다.
//   404 는 `DevAllowIssuePermissionResolver`(`@Profile("!prod")`)가 권한을 항상 통과시키는
//   비-prod 에서만 나오고, 클라이언트 동작은 아래 500 케이스와 동일하다 — 순 중복이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-6: preview 실패 문구 — 403 권한 vs 그 외', () => {
  /** 기존 preview 실패 문구 — `issueMoveStrings.errorPreview` 정본 */
  const PREVIEW_ERROR_TEXT = '이슈 이동 정보를 불러오지 못했습니다. 대상 프로젝트 키를 확인해 주세요.'
  /** 403 전용 문구 — `issueMoveStrings.errorPreviewForbidden` 정본 */
  const PREVIEW_FORBIDDEN_TEXT =
    '대상 프로젝트 키를 확인해 주세요. 키가 맞다면 이 이슈나 대상 프로젝트 권한이 없는 것입니다.'

  async function submitTarget(targetKey = 'INFRA'): Promise<void> {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), targetKey)
    await user.click(screen.getByRole('button', { name: /다음/i }))
  }

  it('preview 403 이면 키 확인 → 권한 순의 전용 문구를 보여 준다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json(
          { errorCode: 'ACCESS_DENIED', detail: '이 작업을 수행할 권한이 없습니다.' },
          { status: 403 },
        ),
      ),
    )
    await submitTarget()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(PREVIEW_FORBIDDEN_TEXT)
    expect(alert).not.toHaveTextContent(PREVIEW_ERROR_TEXT)
    // Step 1 에 머문다 — 매핑 화면으로 넘어가지 않는다.
    expect(screen.queryByText(/이동 매핑 확인/i)).not.toBeInTheDocument()
  })

  it('403 문구가 「권한 없음」으로 단정하지 않고 대상 키 확인을 먼저 말한다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ errorCode: 'ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    await submitTarget('INRA') // 오타 — prod 리졸버는 미존재 프로젝트도 403 으로 돌려준다

    const alert = await screen.findByRole('alert')
    const text = alert.textContent ?? ''
    // ① 키 오타 가능성이 **먼저** 읽혀야 한다 (자유 텍스트 입력에서 가장 흔한 경로).
    expect(text.indexOf('대상 프로젝트 키')).toBeGreaterThanOrEqual(0)
    expect(text.indexOf('대상 프로젝트 키')).toBeLessThan(text.indexOf('권한'))
    // ②③ 권한 가능성도 남긴다. 다만 「관리자에게 문의」로 막다른 길을 만들지 않는다.
    expect(text).toContain('권한')
    expect(text).not.toContain('문의')
  })

  it('preview 500 이면 기존 문구가 그대로 나온다 (비-공허 짝)', async () => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ errorCode: 'INTERNAL_ERROR' }, { status: 500 }),
      ),
    )
    await submitTarget()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(PREVIEW_ERROR_TEXT)
    expect(alert).not.toHaveTextContent(PREVIEW_FORBIDDEN_TEXT)
  })

  it('키를 다시 치기 시작하면 남아 있던 경고가 사라진다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ errorCode: 'ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    await submitTarget('INRA')
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    // 오타를 고치는 첫 글자에서 경고가 걷힌다 — 안 그러면 「키를 확인하라」는 안내를
    // 따르는 내내 그 안내가 화면에 남아 고쳤는지 아닌지를 알 수 없다.
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'F')

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-7. 이동 **실행** 단계 403 토스트
//
// 서버는 `IssueMoveService.kt:189-190` 에서 preview 와 **같은 두 assert** 를 하고 같은
// `ACCESS_DENIED` 를 낸다. 그러니 원인 설명도 preview 와 같은 어휘여야 한다 —
// 옛 문구(「이슈를 이동할 권한이 없습니다.」)는 원인을 **원본 이슈 쪽으로 단정**해,
// 대상 프로젝트만 막힌 사용자에게 「이 이슈는 영영 못 옮긴다」로 읽혔다.
//
// 다만 실행 단계는 preview 를 통과한 뒤라 **대상 키는 이미 서버가 받아들인 값**이다.
// 그래서 preview 문구의 「키부터 확인」 도입부는 빼고 권한 두 갈래만 말한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-7: 이동 실행 403 — preview 와 같은 원인 설명', () => {
  /** 실행 단계 403 문구 — `issueMoveStrings.errorForbidden` 정본 */
  const MOVE_FORBIDDEN_TEXT = '이 이슈나 대상 프로젝트 권한이 없어 이동하지 못했습니다.'

  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ data: compatiblePreviewFixture }),
      ),
      http.post('/api/v1/issues/:key/move', () =>
        HttpResponse.json({ errorCode: 'ACCESS_DENIED' }, { status: 403 }),
      ),
    )
  })

  it('move 403 이면 원인을 이슈 쪽으로 단정하지 않는 토스트를 낸다', async () => {
    const { toast } = await import('sonner')
    vi.mocked(toast.error).mockClear()

    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))
    await waitFor(() => {
      expect(screen.getByText(/이동 매핑 확인/i)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /이동/i }))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith(MOVE_FORBIDDEN_TEXT)
    })
    // 옛 문구는 더 이상 나오지 않는다 — 같은 다이얼로그가 단계마다 다른 설명을 내면 안 된다.
    expect(toast.error).not.toHaveBeenCalledWith('이슈를 이동할 권한이 없습니다.')
  })
})
