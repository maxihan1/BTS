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
// 사용자 문구 정본 — **일부러 하드코딩한다**
//
// `issueMoveStrings` 를 import 해서 비교하면 ko.ts 를 고치는 순간 테스트도 같이 따라가
// 「문구가 이래야 한다」는 계약이 사라진다. 여기 적힌 문자열이 곧 계약이고,
// ko.ts 를 고치면 여기도 같이 고쳐야 한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 형식 오류 문구 — `issueMoveStrings.errorKeyFormat` 정본 */
const KEY_FORMAT_TEXT = '프로젝트 키는 대문자로 시작하는 대문자+숫자 2~10자여야 합니다.'
/** 그 외 4xx 문구 — `issueMoveStrings.errorPreview` 정본 */
const PREVIEW_ERROR_TEXT = '이슈 이동 정보를 불러오지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.'
/** 5xx·네트워크 단절 문구 — `issueMoveStrings.errorPreviewTemporary` 정본 */
const PREVIEW_TEMPORARY_TEXT =
  '이슈 이동 정보를 불러오지 못했습니다. 일시적인 문제일 수 있으니 잠시 후 다시 시도해 주세요.'
/** preview 403 전용 문구 — `issueMoveStrings.errorPreviewForbidden` 정본 */
const PREVIEW_FORBIDDEN_TEXT =
  '대상 프로젝트 키를 확인해 주세요. 키가 맞다면 이 이슈나 대상 프로젝트 권한이 없는 것입니다.'

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

describe('T4-6: preview 실패 문구 — 403 · 일시적 · 그 외 4xx', () => {
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

  // ★이 테스트는 「500 이면 기존 문구(대상 프로젝트 키를 확인해 주세요)가 그대로 나온다」였다.
  //   그 초록은 **틀린 안내가 유지되는 것**을 지키고 있었다 — 서버가 죽은 것과 키 오타는
  //   사용자가 할 다음 행동이 정반대다. 판정을 뒤집는다(삭제가 아니다).
  it('preview 500 이면 일시적 문제 문구가 나온다 — 키를 의심하게 만들지 않는다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ errorCode: 'INTERNAL_ERROR' }, { status: 500 }),
      ),
    )
    await submitTarget()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(PREVIEW_TEMPORARY_TEXT)
    expect(alert).not.toHaveTextContent(PREVIEW_FORBIDDEN_TEXT)
    expect(alert).not.toHaveTextContent(PREVIEW_ERROR_TEXT)
    // 이 부채의 본질 — 서버 오류에 「키」를 꺼내지 않는다.
    expect(alert.textContent ?? '').not.toContain('키')
  })

  it('preview 404(이슈 미존재) 면 그 외 4xx 문구가 나온다 — 이 404 는 운영에서도 난다', async () => {
    // 도달 경로. 다이얼로그를 연 뒤 그 이슈가 삭제되면 `MovePreviewService:188` 이
    // IssueNotFoundException 을 낸다. **대상 프로젝트** 미존재 404 와 혼동하지 말 것 —
    // 그쪽만 비-prod 전용이다.
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ errorCode: 'ISSUE_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await submitTarget()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(PREVIEW_ERROR_TEXT)
    expect(alert).not.toHaveTextContent(PREVIEW_TEMPORARY_TEXT)
    expect(alert.textContent ?? '').not.toContain('키')
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

// ─────────────────────────────────────────────────────────────────────────────
// T4-9. 이동 실행 404 PROJECT_NOT_FOUND — **비-prod 전용 분기**의 특성화 (매핑 `12`)
//
// 이 분기는 **운영에서 실행되지 않는다.** `IssueMoveService.kt:189-190` 의 권한 assert 가
// `:210` 의 존재 확인보다 앞서고, 운영 리졸버가 미존재 프로젝트를 권한 거부로 판정하기
// 때문이다(`IdentityAccessIssuePermissionResolver:77`). 그래도 개발 리졸버
// (`DevAllowIssuePermissionResolver`)에서는 실제로 도달한다 — 지우면 개발 중에 원인이
// 「알 수 없는 오류」로 뭉개진다.
//
// ★이 테스트는 red-first 가 아니다. 분기는 이미 있고 올바르게 동작한다. 결함은
//   「언제 도는가」를 주석 4곳이 틀리게 적어 둔 것이었다. 다만 이 분기를 재는 테스트가
//   **한 건도 없었다** — 커버리지 0 인 채로 「비-prod 전용」이라 적으면 다음 사람이
//   지워도 아무것도 안 깨진다. 그 구멍을 메우는 특성화 테스트다.
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-9: 이동 실행 404 PROJECT_NOT_FOUND — 비-prod 전용 분기', () => {
  /** 대상 프로젝트 미존재 문구 — `issueMoveStrings.errorProjectNotFound` 정본 */
  const PROJECT_NOT_FOUND_TEXT = '대상 프로젝트를 찾을 수 없습니다.'
  /** 기본 문구 — `issueMoveStrings.errorDefault` 정본 */
  const MOVE_DEFAULT_ERROR_TEXT = '이슈 이동 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'

  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () =>
        HttpResponse.json({ data: compatiblePreviewFixture }),
      ),
      http.post('/api/v1/issues/:key/move', () =>
        HttpResponse.json({ errorCode: 'PROJECT_NOT_FOUND' }, { status: 404 }),
      ),
    )
  })

  it('404 PROJECT_NOT_FOUND 면 대상 프로젝트 미존재 토스트를 낸다', async () => {
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
      expect(toast.error).toHaveBeenCalledWith(PROJECT_NOT_FOUND_TEXT)
    })
    // 기본 문구로 떨어지지 않는다 — 떨어지면 개발 중 원인 파악이 한 단계 느려진다.
    expect(toast.error).not.toHaveBeenCalledWith(MOVE_DEFAULT_ERROR_TEXT)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-8. 요청 전 키 형식 차단 (기술부채 매핑 `11`)
//
// 백엔드는 프로젝트 키를 **정확 일치**로 조회하고(`ProjectDirectory` 의 raw SQL
// `WHERE key = :key`), 운영 리졸버는 미존재 프로젝트를 **권한 거부**로 판정한다
// (`IdentityAccessIssuePermissionResolver:77` — `resolveProjectId(scope) ?: return false`).
// 그래서 소문자 `infra` 는 서버까지 가서 **403 「권한 없음」** 으로 되돌아온다.
// 사용자가 고칠 수 있는 것은 대소문자인데 화면은 권한 이야기를 한다.
//
// 처방은 생성 화면과 **같은 판정**(`isValidProjectKey`)을 요청 전에 거는 것이다.
// 그러므로 이 블록이 재는 것은 「문구가 떴다」가 아니라 **「요청이 나가지 않았다」** 이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('T4-8: 요청 전 키 형식 차단', () => {
  /** preview 가 실제로 몇 번 나갔는지. 이 숫자가 이 블록의 단언 대상이다. */
  let previewRequestCount = 0

  beforeEach(() => {
    previewRequestCount = 0
    server.use(
      http.post('/api/v1/issues/:key/move/preview', () => {
        previewRequestCount += 1
        return HttpResponse.json({ data: compatiblePreviewFixture })
      }),
    )
  })

  it('소문자 키로 「다음」을 누르면 preview 요청이 나가지 않는다', async () => {
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'infra')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(KEY_FORMAT_TEXT)
    expect(previewRequestCount).toBe(0)
    // Step 1 에 머문다 — 매핑 화면으로 넘어가지 않는다.
    expect(screen.queryByText(/이동 매핑 확인/i)).not.toBeInTheDocument()
  })

  it('★비-공허 짝. 형식이 맞는 키는 preview 요청이 그대로 나간다', async () => {
    // 이 짝이 없으면 위 단언은 「게이트가 전부를 막아 버린 것」과 구분되지 않는다.
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'INFRA')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    await waitFor(() => {
      expect(screen.getByText(/이동 매핑 확인/i)).toBeInTheDocument()
    })
    expect(previewRequestCount).toBe(1)
  })

  it('Enter 로 제출해도 같은 게이트가 걸린다', async () => {
    // 제출 경로가 버튼과 Enter 둘이면 게이트도 둘 다 타야 한다 — 한쪽만 막으면 봉합이 절반이다.
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'infra{Enter}')

    expect(await screen.findByRole('alert')).toHaveTextContent(KEY_FORMAT_TEXT)
    expect(previewRequestCount).toBe(0)
  })

  it('형식 오류 문구는 403 문구와 다른 문장이다', async () => {
    // 같으면 「고칠 수 있는 오타」와 「고칠 수 없는 권한」이 사용자 눈에 한 덩어리가 된다.
    renderDialog()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/대상 프로젝트 키/i), 'infra')
    await user.click(screen.getByRole('button', { name: /다음/i }))

    expect(await screen.findByRole('alert')).not.toHaveTextContent(PREVIEW_FORBIDDEN_TEXT)
  })
})
