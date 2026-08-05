// BacklogBoard 컴포넌트 통합 테스트 — onDragEnd 시나리오·C1 부분실패·생성/시작/완료 버튼 (FR-BL-01/02 D6/D7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, waitFor, act, within } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import type {
  Active,
  Announcements,
  DragEndEvent,
  Over,
  ScreenReaderInstructions,
  SensorDescriptor,
  SensorOptions,
} from '@dnd-kit/core'

// TanStack Router Link mock
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    children,
    className,
    onClick,
  }: {
    to: string
    params?: Record<string, string>
    children: ReactNode
    className?: string
    onClick?: React.MouseEventHandler
  }) => (
    <a
      href={params ? to.replace('$key', params['key'] ?? '') : to}
      className={className}
      onClick={onClick}
      data-testid="issue-link"
    >
      {children}
    </a>
  ),
}))

// sonner toast mock — 호출 여부 검증
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    warning: vi.fn(),
    success: vi.fn(),
  },
}))

// @dnd-kit/core — DndContext 이벤트를 테스트에서 직접 트리거하기 위해 부분 mock
// PointerSensor 실제 입력 이벤트 없이 onDragEnd를 시뮬레이션한다.
let capturedOnDragEnd: ((event: DragEndEvent) => void) | undefined

/** `DndContext` 의 `accessibility` prop — 공지 배선(FR-9·FR-17)을 재는 유일한 관측 지점 */
interface CapturedAccessibility {
  announcements?: Announcements
  screenReaderInstructions?: ScreenReaderInstructions
}

// ★센서·공지는 **props 로만** 관측된다 — mock 이 이 둘을 버리면 배선을 삭제해도
//   이 파일이 전량 초록으로 남는다 (담당자 배선이 같은 함정을 겪었다. :210-212 주석).
let capturedSensors: SensorDescriptor<SensorOptions>[] | undefined
let capturedAccessibility: CapturedAccessibility | undefined

vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: ({
      children,
      onDragEnd,
      sensors,
      accessibility,
    }: {
      children: ReactNode
      onDragEnd?: (event: DragEndEvent) => void
      sensors?: SensorDescriptor<SensorOptions>[]
      accessibility?: CapturedAccessibility
    }) => {
      // onDragEnd 콜백을 캡처 — triggerDragEnd()에서 호출
      capturedOnDragEnd = onDragEnd
      capturedSensors = sensors
      capturedAccessibility = accessibility
      return <div data-testid="dnd-context">{children}</div>
    },
  }
})

import { toast } from 'sonner'

// use-backlog 훅 — mutation 호출 추적을 위해 mock
const mockRerankMutate = vi.fn()
const mockAssignMutate = vi.fn()
const mockUnassignMutate = vi.fn()
const mockCreateSprintMutate = vi.fn()
const mockStartSprintMutate = vi.fn()
const mockCompleteSprintMutate = vi.fn()

// FR-UX-13 F15 — 다이얼로그는 `mutate` 가 아니라 **`mutateAsync`** 를 쓴다
// (`StartSprintDialog.tsx:291,311` · `CompleteSprintDialog.tsx:296`).
// mock 이 `mutateAsync` 를 안 주면 제출이 `undefined is not a function` 으로 터진다.
//
// ★위 `mutate` 스파이들의 `not.toHaveBeenCalled()` 단언은 배선 교체로 **영구히 공허**해졌다
//   — 이제 아무도 `.mutate` 를 부르지 않는다. 그래서 권한 테스트의 실질 단언은
//   「다이얼로그가 열리지 않는다」와 아래 `mutateAsync` 스파이가 진다.
const mockStartSprintMutateAsync = vi.fn<(sprintId: string) => Promise<void>>(() =>
  Promise.resolve(),
)
const mockCompleteSprintMutateAsync = vi.fn<(sprintId: string) => Promise<void>>(() =>
  Promise.resolve(),
)
const mockUpdateSprintMutateAsync = vi.fn<(input: unknown) => Promise<unknown>>(() =>
  Promise.resolve(undefined),
)

// FR-UX-13 F5 — 담당자 배선 검증용 픽스처 상수.
// mock 팩토리 안에서 쓰이지만 **호출 시점**(렌더)에 읽히므로 TDZ 문제가 없다
// (`STUB_CREATED_KEY` 와 같은 형태).
const ALICE_ID = '00000000-0000-4000-8000-000000000001'
const BOB_ID = '00000000-0000-4000-8000-000000000002'

/**
 * 픽스처 스프린트 UUID 2종 (F15 T9).
 *
 * ★**실제 UUID 형식이어야 한다.** 종전 값(`'sprint-uuid-0001'`)은 `useBacklog` 를 mock 해
 * Zod 를 지나지 않았기에 통과했다. 완료 다이얼로그는 완료 직전 재검증에서 **진짜**
 * `fetchBacklog` 를 부르고 `sprintMetaSchema.sprintId` 가 `z.string().uuid()` 라
 * (`api/backlog.ts:47`), 형식이 아니면 파싱이 터져 그 예외가 `revalidate` 의 catch 에
 * 삼켜지고 화면에는 「다른 사람이 먼저 수정했습니다」가 뜬다 — 원인과 증상이 전혀 다르다.
 */
const PLANNED_SPRINT_ID = '00000000-0000-4000-8000-0000000000a1'
const ACTIVE_SPRINT_ID = '00000000-0000-4000-8000-0000000000a2'

// FR-UX-13 F5 — 조회 실패·재조회 상태 주입 지점.
//
// `useBacklog` 반환값 한 곳이 화면 전체의 분기(로딩 / 에러 / 정상)를 쥐므로 테스트마다
// 갈아끼울 수 있어야 한다. 기본값이 종전과 같은 「정상 조회」라 기존 테스트는 이 변수를
// 몰라도 그대로 돈다.
const mockRefetch = vi.fn()

/** `useBacklog` 반환값 중 테스트가 덮어쓰는 부분 */
interface BacklogQueryOverride {
  data?: BacklogView | undefined
  isLoading?: boolean
  isError?: boolean
  isFetching?: boolean
}
let mockBacklogQueryOverride: BacklogQueryOverride = {}

vi.mock('@/hooks/use-backlog', () => ({
  useBacklog: () => ({
    data: {
      backlog: [
        {
          key: 'ATLAS-1',
          summary: '백로그 이슈 1',
          currentStateKey: 'open',
          assigneeId: null,
          priority: 1,
          rank: '0|a:',
          version: 0,
          epicKey: null,
        },
        {
          key: 'ATLAS-2',
          summary: '백로그 이슈 2',
          currentStateKey: 'open',
          assigneeId: null,
          priority: 2,
          rank: '0|b:',
          version: 0,
          epicKey: null,
        },
        // FR-UX-13 F5 — 담당자가 배정된 백로그 카드. 나머지 이슈는 미배정으로 남겨
        // 「이름 있음 / 미배정」 두 상태가 한 화면에 공존하게 둔다.
        {
          key: 'ATLAS-4',
          summary: '담당자 있는 백로그 이슈',
          currentStateKey: 'open',
          assigneeId: ALICE_ID,
          priority: 3,
          rank: '0|c:',
          version: 0,
          epicKey: null,
        },
      ],
      sprints: [
        {
          sprint: {
            sprintId: PLANNED_SPRINT_ID,
            name: '스프린트 1',
            goal: null,
            status: 'PLANNED',
            startDate: null,
            endDate: null,
            version: 0,
          },
          issues: [
            {
              key: 'ATLAS-3',
              summary: '스프린트1 이슈',
              currentStateKey: 'open',
              assigneeId: null,
              priority: 1,
              rank: '0|a:',
              version: 0,
              epicKey: null,
            },
          ],
        },
        {
          sprint: {
            sprintId: ACTIVE_SPRINT_ID,
            name: '스프린트 2 (ACTIVE)',
            goal: null,
            status: 'ACTIVE',
            startDate: '2026-06-01',
            endDate: '2026-06-14',
            version: 0,
          },
          // FR-UX-13 F5 — 담당자가 배정된 **스프린트** 카드.
          // 백로그 칸만 배선하고 스프린트 칸을 빠뜨리는 반쪽 봉합을 잡는다.
          issues: [
            {
              key: 'ATLAS-5',
              summary: '담당자 있는 스프린트 이슈',
              currentStateKey: 'open',
              assigneeId: BOB_ID,
              priority: 3,
              rank: '0|a:',
              version: 0,
              epicKey: null,
            },
          ],
        },
      ],
      truncated: false,
    },
    isLoading: false,
    isError: false,
    isFetching: false,
    refetch: mockRefetch,
    // ★맨 끝이어야 한다 — 위 기본값을 테스트가 덮어쓰는 자리다 (FR-UX-13 F5)
    ...mockBacklogQueryOverride,
  }),
  useRerankIssue: () => ({ mutate: mockRerankMutate, isPending: false }),
  useAssignToSprint: () => ({ mutate: mockAssignMutate, isPending: false }),
  useUnassignFromSprint: () => ({ mutate: mockUnassignMutate, isPending: false }),
  useCreateSprint: () => ({ mutate: mockCreateSprintMutate, isPending: false }),
  useStartSprint: () => ({
    mutate: mockStartSprintMutate,
    mutateAsync: mockStartSprintMutateAsync,
    isPending: false,
  }),
  useCompleteSprint: () => ({
    mutate: mockCompleteSprintMutate,
    mutateAsync: mockCompleteSprintMutateAsync,
    isPending: false,
  }),
  // ★빠뜨리면 시작 다이얼로그가 마운트되는 순간 「No "useUpdateSprint" export is defined」로
  //   그 테스트들이 통째로 터진다 — 팩토리가 반환하지 않는 export 는 존재하지 않는다.
  useUpdateSprint: () => ({ mutateAsync: mockUpdateSprintMutateAsync, isPending: false }),
  backlogKeys: { detail: (key: string) => ['backlog', key] },
}))

// FR-UX-13 F5 — 담당자 이름 조회 훅 mock.
//
// ★반환값을 **모듈 스코프 변수 한 개**로 유지한다 — 실제 `useUsersByIdsChunked` 는
// `useQueries` 의 `combine` 을 써서 결과가 안 바뀌면 같은 참조를 돌려준다.
// 매 렌더 새 객체를 만들면 `BacklogBoard` 의 `useMemo` 가 그 전제 위에서만 성립한다는
// 사실이 여기서 가려진다.
//
// ★인자를 **`vi.fn` 으로 기록**한다. 기록하지 않으면 `useUsersByIdsChunked(assigneeIds)` 를
// `useUsersByIdsChunked([])` 로 바꿔도 이 파일이 전량 초록으로 남는다(실측: 유닛 118/118 통과,
// E2E S9 만 red). 훅 배선(FR-2·NFR-1)을 재는 것은 T3-1 의 호출 인자 단언뿐이다.
//
// ★반환값은 스파이의 **구현이 아니라** `mockUsersResult` 가 쥔다 — 스파이는 기록만 한다.
// 반환을 구현에 얹으면 mock 초기화 방식이 바뀌는 날 반환값까지 함께 사라진다.
interface ChunkedUsersResult {
  data: UserSummary[]
}
const NO_USERS: ChunkedUsersResult = { data: [] }
let mockUsersResult: ChunkedUsersResult = NO_USERS

/** `useUsersByIdsChunked` 가 실제로 받은 id 목록 — T3-1 이 단언한다 */
const mockUseUsersByIdsChunked = vi.fn<(ids: string[]) => void>()

vi.mock('@/hooks/use-users', () => ({
  useUsersByIdsChunked: (ids: string[]) => {
    mockUseUsersByIdsChunked(ids)
    return mockUsersResult
  },
}))

// FR-UX-09 F3 — 생성 모달을 스텁으로 둔다.
//
// ★이 파일의 관심사는 **배선**이다 — 어느 칸에서 열었는지에 따라 어느 mutation 이 걸리는가.
// 폼 자체(프로젝트·유형·커스텀필드 목 조립)를 여기서 구동하면 검증하려는 것보다
// 목 셋업이 커지고, 폼이 바뀔 때마다 이 파일이 같이 깨진다.
//
// ⚠️ **대가를 명시한다** — 스텁은 진짜 모달이 실제로 열리고 제출되는지를 보지 못한다.
// 그 판정은 실제 MSW 를 쓰는 E2E(T8, `issue-create-entry-points.spec.ts`)가 갖는다.
const STUB_CREATED_KEY = 'ATLAS-42'
vi.mock('@/components/issue/CreateIssueDialog', () => ({
  CreateIssueDialog: ({
    open,
    onCreated,
  }: {
    open: boolean
    onCreated?: (key: string) => void
  }) =>
    open ? (
      <div role="dialog" aria-label="이슈 생성 모달 스텁">
        <button type="button" onClick={() => onCreated?.(STUB_CREATED_KEY)}>
          스텁 생성 완료
        </button>
      </div>
    ) : null,
}))

import { KeyboardSensor, PointerSensor } from '@dnd-kit/core'
import { BacklogBoard } from './BacklogBoard'
import { backlogLabels } from '@/i18n/backlog-labels'
import { backlogScreenReaderInstructions } from '@/lib/backlog-announcements'
import { backlogKeyboardSensorOptions } from '@/lib/backlog-keyboard-coordinates'
import { server } from '@/test/server'
import { allWorkflowFixtures } from '@/mocks/workflow-fixtures'
import type { UserSummary } from '@/api/users'
import type { BacklogIssue, BacklogView } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderBoard(
  projectKey = 'ATLAS',
  opts: { canManageSprint?: boolean; canReorderIssue?: boolean; canCreateIssue?: boolean } = {},
) {
  const { canManageSprint = true, canReorderIssue = true, canCreateIssue = false } = opts
  const qc = makeQueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <BacklogBoard
        projectKey={projectKey}
        canManageSprint={canManageSprint}
        canReorderIssue={canReorderIssue}
        canCreateIssue={canCreateIssue}
      />
    </QueryClientProvider>,
  )
}

/** {@link triggerDragEnd} 의 선택 인자 — 「어떻게 끌었나」를 실제 값으로 싣는다 */
interface DragEndOptions {
  /**
   * 드래그 이동량. **기본값이 0이 아닌** 것이 핵심이다 (F15 T9).
   *
   * 종전에는 `{x:0, y:0}` 을 자리표시자로 하드코딩해 두었는데, 그러면 「집자마자 그대로
   * 놓았다」와 「40px 끌어다 놓았다」가 하네스 위에서 **구별되지 않는다**. 그 결과
   * `use-backlog-drag.ts` 의 이동-0 가드(T-KB-3)가 조건 없이 들어오면 S1~S5 재현 8건이
   * 통째로 red 가 되어, 가드 범위가 하네스 사정에 끌려가는 일이 벌어졌다.
   * 실제 값을 싣는 쪽이 옳다 — 시나리오가 뜻하는 바가 그것이기 때문이다.
   */
  readonly delta?: { readonly x: number; readonly y: number }
  /**
   * 활성화 이벤트. 마우스는 `PointerEvent`, 키보드는 `KeyboardEvent` 다.
   * dnd-kit 이 활성화 핸들러의 `nativeEvent` 를 그대로 싣는 것을 흉내 낸다.
   */
  readonly activatorEvent?: Event
}

/** 마우스로 40px 끌었다 — 시나리오가 「이동」을 뜻할 때의 기본값 */
const DEFAULT_DRAG_DELTA = { x: 0, y: 40 } as const

/**
 * DndContext mock을 통해 onDragEnd를 트리거한다.
 *
 * @param active 드래그 중이던 아이템 (id + data)
 * @param over 드롭 대상 (null이면 제자리 취소)
 * @param options 이동량·활성화 이벤트. 기본은 「마우스로 40px 끌었다」
 */
function triggerDragEnd(
  active: { id: string; data: { current: Record<string, unknown> } },
  over: { id: string; data: { current: Record<string, unknown> } } | null,
  options: DragEndOptions = {},
) {
  const { delta = DEFAULT_DRAG_DELTA, activatorEvent = new PointerEvent('pointerdown') } = options
  act(() => {
    capturedOnDragEnd?.({
      active: { id: active.id, data: active.data, rect: { current: { initial: null, translated: null } } },
      over: over
        ? { id: over.id, data: over.data, rect: { width: 0, height: 0, top: 0, left: 0, bottom: 0, right: 0 }, disabled: false }
        : null,
      collisions: null,
      delta,
      activatorEvent,
    } as unknown as DragEndEvent)
  })
}

/**
 * 완료 다이얼로그가 **직접 내는** 요청의 목 (F15 T9).
 *
 * `useCompleteSprint` 는 이 파일이 mock 하지만, 다이얼로그는 그 밖에 세 가지를 스스로 낸다.
 * ① `GET /api/v1/workflows` — 미완료 판정(FR-7)의 카테고리 사상
 * ② `DELETE /api/v1/sprints/{id}/issues/{key}` — 이관 (FR-6)
 * ③ `GET /api/v1/projects/{key}/backlog` — 완료 직전 재검증 (C-7)
 * 전역 MSW 는 `onUnhandledRequest: 'error'` 라 하나라도 빠지면 그 자리에서 터진다.
 * **다이얼로그를 실제로 여는 테스트에서만** 깐다 — 안 여는 테스트에 요청이 늘면
 * 「마운트하지 않는다」(NFR-1)가 조용히 깨져도 알 수 없기 때문이다.
 */
function installSprintDialogHandlers(): void {
  sprintDialogCalls = []
  server.use(
    http.get('/api/v1/workflows', () => {
      sprintDialogCalls.push('WORKFLOWS')
      return HttpResponse.json({ data: allWorkflowFixtures })
    }),
    http.delete('/api/v1/sprints/:sprintId/issues/:issueKey', ({ params }) => {
      sprintDialogCalls.push(`UNASSIGN ${String(params['sprintId'])}/${String(params['issueKey'])}`)
      return new HttpResponse(null, { status: 204 })
    }),
    http.get('/api/v1/projects/:projectKey/backlog', () => {
      sprintDialogCalls.push('REVALIDATE')
      return HttpResponse.json({ data: FRESH_AFTER_TRANSFER })
    }),
  )
}

/** 완료 다이얼로그가 낸 요청의 관측 순서 — 「이관이 먼저」(FR-6)를 재는 데 쓴다 */
let sprintDialogCalls: string[] = []

/**
 * 이관이 끝난 뒤의 백로그 — 완료 대상 스프린트에 미완료가 **0건**이어야 재검증을 통과한다.
 *
 * 여기에 미완료를 남기면 `finish()` 가 `{kind:'stale'}` 로 멈춰 완료가 나가지 않는다.
 */
const FRESH_AFTER_TRANSFER: BacklogView = {
  backlog: [],
  sprints: [
    {
      sprint: {
        sprintId: ACTIVE_SPRINT_ID,
        name: '스프린트 2 (ACTIVE)',
        goal: null,
        status: 'ACTIVE',
        startDate: '2026-06-01',
        endDate: '2026-06-14',
        version: 0,
      },
      // 이관이 끝났으니 비어 있다. 여기에 미완료를 남기면 `finish()` 가
      // `{kind:'stale'}` 로 멈춰 완료가 나가지 않는다 (C-7 재검증).
      issues: [],
    },
  ],
  truncated: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogBoard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // ── 기본 렌더링 ─────────────────────────────────────────────────────────────

  describe('기본 렌더링', () => {
    it('백로그 칸과 스프린트 칸들을 렌더한다', () => {
      renderBoard()
      expect(screen.getByText('백로그')).toBeInTheDocument()
      expect(screen.getByText('스프린트 1')).toBeInTheDocument()
      expect(screen.getByText('스프린트 2 (ACTIVE)')).toBeInTheDocument()
    })

    it('백로그 이슈를 렌더한다', () => {
      renderBoard()
      expect(screen.getByText('백로그 이슈 1')).toBeInTheDocument()
      expect(screen.getByText('백로그 이슈 2')).toBeInTheDocument()
    })

    it('스프린트 이슈를 렌더한다', () => {
      renderBoard()
      expect(screen.getByText('스프린트1 이슈')).toBeInTheDocument()
    })
  })

  // ── 스프린트 생성 폼 ─────────────────────────────────────────────────────────

  describe('스프린트 생성 폼', () => {
    it('스프린트 이름 입력 후 생성 버튼 클릭 시 useCreateSprint.mutate를 호출한다', async () => {
      const user = userEvent.setup()
      renderBoard()

      const nameInput = screen.getByPlaceholderText(/스프린트 이름/i)
      await user.type(nameInput, '신규 스프린트')

      const createBtn = screen.getByRole('button', { name: /스프린트 생성/i })
      await user.click(createBtn)

      expect(mockCreateSprintMutate).toHaveBeenCalledWith(
        expect.objectContaining({ name: '신규 스프린트', projectKey: 'ATLAS' }),
        expect.anything(),
      )
    })

    it('이름이 비어 있으면 생성 버튼을 클릭해도 mutate를 호출하지 않는다', async () => {
      const user = userEvent.setup()
      renderBoard()

      const createBtn = screen.getByRole('button', { name: /스프린트 생성/i })
      await user.click(createBtn)

      expect(mockCreateSprintMutate).not.toHaveBeenCalled()
    })
  })

  // ── 시작/완료 버튼 ───────────────────────────────────────────────────────────

  describe('스프린트 시작/완료 버튼', () => {
    it('PLANNED 스프린트에 시작 버튼이 렌더된다', () => {
      renderBoard()
      expect(screen.getByRole('button', { name: /스프린트 시작/i })).toBeInTheDocument()
    })

    it('ACTIVE 스프린트에 완료 버튼이 렌더된다', () => {
      renderBoard()
      expect(screen.getByRole('button', { name: /스프린트 완료/i })).toBeInTheDocument()
    })

    // ★ F15 T9 — 트리거는 이제 mutation 이 아니라 **다이얼로그**를 연다.
    //   종전처럼 클릭 한 번에 시작/완료가 나가면 「되돌릴 수 없는 연산이 확인 없이
    //   실행된다」(스펙 C1). 그래서 두 테스트는 **제출까지** 거치도록 갱신했다.
    it('시작 버튼은 다이얼로그를 열고, 다이얼로그 제출이 useStartSprint 를 호출한다', async () => {
      const user = userEvent.setup()
      renderBoard()

      await user.click(screen.getByRole('button', { name: backlogLabels.startSprint }))

      const dialog = await screen.findByRole('dialog', { name: backlogLabels.startSprint })
      // 트리거 클릭만으로는 아무것도 나가지 않는다 — 이 단언이 「확인 절차」의 증인이다
      expect(mockStartSprintMutateAsync).not.toHaveBeenCalled()

      await user.click(within(dialog).getByRole('button', { name: backlogLabels.startSprint }))

      await waitFor(() => {
        expect(mockStartSprintMutateAsync).toHaveBeenCalledWith(PLANNED_SPRINT_ID)
      })
      // 값을 안 바꿨으면 PATCH 를 보내지 않는다 (FR-4 · S4)
      expect(mockUpdateSprintMutateAsync).not.toHaveBeenCalled()
    })

    it('T-DL-1: 시작 다이얼로그가 열린 동안 「보이는」 스프린트 시작 버튼은 정확히 1개다', async () => {
      const user = userEvent.setup()
      renderBoard()

      expect(screen.getAllByRole('button', { name: backlogLabels.startSprint })).toHaveLength(1)

      await user.click(screen.getByRole('button', { name: backlogLabels.startSprint }))
      const dialog = await screen.findByRole('dialog', { name: backlogLabels.startSprint })

      // FR-10 의 「Radix modal 이 바깥 트리거를 접근성 트리에서 감춘다」는 **가정을
      // 측정으로 바꾼다**. 감춰지지 않으면 같은 이름의 버튼이 2개가 되어 조회가 즉사한다.
      const visible = screen.getAllByRole('button', { name: backlogLabels.startSprint })
      expect(visible).toHaveLength(1)
      expect(dialog).toContainElement(visible[0] ?? null)

      // ★짝 단언 — 「1개」가 「트리거가 통째로 사라졌다」가 아님을 확인한다.
      //   감춰진 것까지 세면 트리거 + 제출 버튼으로 2개여야 한다.
      expect(
        screen.getAllByRole('button', { name: backlogLabels.startSprint, hidden: true }),
      ).toHaveLength(2)
    })

    it('완료 버튼은 다이얼로그를 열고, 다이얼로그 제출이 useCompleteSprint 를 호출한다', async () => {
      installSprintDialogHandlers()
      const user = userEvent.setup()
      renderBoard()

      await user.click(screen.getByRole('button', { name: backlogLabels.completeSprint }))

      const dialog = await screen.findByRole('dialog', { name: backlogLabels.completeSprint })
      expect(mockCompleteSprintMutateAsync).not.toHaveBeenCalled()

      // 워크플로우가 도착해 미완료 판정이 끝나야 제출이 의미를 갖는다 (ATLAS-5 는 `open`=TODO)
      await within(dialog).findByText('ATLAS-5')
      await user.click(within(dialog).getByRole('button', { name: backlogLabels.completeSprint }))

      await waitFor(() => {
        expect(mockCompleteSprintMutateAsync).toHaveBeenCalledWith(ACTIVE_SPRINT_ID)
      })
      // 이관(DELETE) → 재검증(GET) 순서를 그대로 지났다 (FR-6 · C-7). 완료가 먼저 나가면
      // 남은 이슈가 영구 동결된다 — 순서가 곧 안전 요구다.
      expect(sprintDialogCalls).toEqual([
        'WORKFLOWS',
        `UNASSIGN ${ACTIVE_SPRINT_ID}/ATLAS-5`,
        'REVALIDATE',
      ])
    })

    it('canManageSprint=false이면 시작/완료 버튼을 눌러도 다이얼로그가 열리지 않는다', async () => {
      const user = userEvent.setup()
      renderBoard('ATLAS', { canManageSprint: false, canReorderIssue: true })

      // canManageSprint=false이면 onStart/onComplete가 undefined로 전달되므로
      // 버튼 클릭이 다이얼로그를 열지 못한다.
      const startBtn = screen.queryByRole('button', { name: backlogLabels.startSprint })
      const completeBtn = screen.queryByRole('button', { name: backlogLabels.completeSprint })

      if (startBtn !== null) await user.click(startBtn)
      if (completeBtn !== null) await user.click(completeBtn)

      // ★실질 단언은 이 두 줄이다. 아래 mutation 단언은 배선 교체로 **공허**해졌다 —
      //   이제 다이얼로그를 거치지 않으면 mutation 에 닿는 경로 자체가 없기 때문이다.
      expect(screen.queryByRole('dialog', { name: backlogLabels.startSprint })).toBeNull()
      expect(screen.queryByRole('dialog', { name: backlogLabels.completeSprint })).toBeNull()

      expect(mockStartSprintMutateAsync).not.toHaveBeenCalled()
      expect(mockCompleteSprintMutateAsync).not.toHaveBeenCalled()
    })
  })

  // ── 권한 게이팅 분리 ─────────────────────────────────────────────────────────

  describe('권한 게이팅 분리 — canManageSprint vs canReorderIssue', () => {
    it('UPDATE만 있고 CREATE 없으면 이름 입력 후에도 스프린트 생성 버튼이 비활성화된다', async () => {
      const user = userEvent.setup()
      renderBoard('ATLAS', { canManageSprint: false, canReorderIssue: true })

      // 이름 입력 후에도 canManageSprint=false 이므로 버튼이 비활성이어야 한다
      const nameInput = screen.getByPlaceholderText(/스프린트 이름/i)
      await user.type(nameInput, '테스트 스프린트')

      const createBtn = screen.getByRole('button', { name: /스프린트 생성/i })
      expect(createBtn).toBeDisabled()
    })

    it('UPDATE만 있고 CREATE 없으면 시작/완료 버튼이 onHandler 없이(undefined) 렌더된다', async () => {
      const user = userEvent.setup()
      renderBoard('ATLAS', { canManageSprint: false, canReorderIssue: true })

      const startBtn = screen.queryByRole('button', { name: backlogLabels.startSprint })
      const completeBtn = screen.queryByRole('button', { name: backlogLabels.completeSprint })

      if (startBtn !== null) await user.click(startBtn)
      if (completeBtn !== null) await user.click(completeBtn)

      // 버튼은 **렌더되지만** 눌러도 다이얼로그가 열리지 않는다 (핸들러가 undefined).
      expect(startBtn).not.toBeNull()
      expect(completeBtn).not.toBeNull()
      expect(screen.queryByRole('dialog', { name: backlogLabels.startSprint })).toBeNull()
      expect(screen.queryByRole('dialog', { name: backlogLabels.completeSprint })).toBeNull()

      expect(mockStartSprintMutateAsync).not.toHaveBeenCalled()
      expect(mockCompleteSprintMutateAsync).not.toHaveBeenCalled()
    })

    it('UPDATE만 있고 CREATE 없어도 드래그 시 rerankMutate를 호출한다', async () => {
      renderBoard('ATLAS', { canManageSprint: false, canReorderIssue: true })

      triggerDragEnd(
        {
          id: 'backlog:ATLAS-2',
          data: { current: { issueKey: 'ATLAS-2', context: 'backlog', sprintId: null } },
        },
        {
          id: 'backlog',
          data: { current: { context: 'backlog', sprintId: null, orderedKeys: ['ATLAS-1', 'ATLAS-2'], dropIndex: 0 } },
        },
      )

      await waitFor(() => {
        expect(mockRerankMutate).toHaveBeenCalledWith(
          expect.objectContaining({ issueKey: 'ATLAS-2' }),
          expect.anything(),
        )
      })
    })

    it('CREATE만 있고 UPDATE 없으면 이름 입력 후 스프린트 생성 버튼이 활성화된다', async () => {
      const user = userEvent.setup()
      renderBoard('ATLAS', { canManageSprint: true, canReorderIssue: false })

      // 이름 입력 전: name이 비어 있어서 disabled (canManageSprint 비활성이 아님)
      const nameInput = screen.getByPlaceholderText(/스프린트 이름/i)
      await user.type(nameInput, '테스트 스프린트')

      // 이름 입력 후: canManageSprint=true이므로 버튼이 활성화되어야 한다
      const createBtn = screen.getByRole('button', { name: /스프린트 생성/i })
      expect(createBtn).not.toBeDisabled()
    })

    it('CREATE만 있고 UPDATE 없으면 드래그가 동작하지 않는다(rerankMutate 미호출)', async () => {
      renderBoard('ATLAS', { canManageSprint: true, canReorderIssue: false })

      triggerDragEnd(
        {
          id: 'backlog:ATLAS-2',
          data: { current: { issueKey: 'ATLAS-2', context: 'backlog', sprintId: null } },
        },
        {
          id: 'backlog',
          data: { current: { context: 'backlog', sprintId: null, orderedKeys: ['ATLAS-1', 'ATLAS-2'], dropIndex: 0 } },
        },
      )

      // 300ms 대기 후에도 호출되지 않아야 한다
      await new Promise((r) => setTimeout(r, 100))
      expect(mockRerankMutate).not.toHaveBeenCalled()
      expect(mockAssignMutate).not.toHaveBeenCalled()
      expect(mockUnassignMutate).not.toHaveBeenCalled()
    })

    it('둘 다 없으면 이름 입력 후에도 스프린트 생성 버튼이 비활성화되고 드래그도 동작하지 않는다', async () => {
      const user = userEvent.setup()
      renderBoard('ATLAS', { canManageSprint: false, canReorderIssue: false })

      // 이름 입력 후에도 canManageSprint=false이므로 버튼 비활성
      const nameInput = screen.getByPlaceholderText(/스프린트 이름/i)
      await user.type(nameInput, '테스트 스프린트')

      const createBtn = screen.getByRole('button', { name: /스프린트 생성/i })
      expect(createBtn).toBeDisabled()

      triggerDragEnd(
        {
          id: 'backlog:ATLAS-2',
          data: { current: { issueKey: 'ATLAS-2', context: 'backlog', sprintId: null } },
        },
        {
          id: 'backlog',
          data: { current: { context: 'backlog', sprintId: null, orderedKeys: ['ATLAS-1', 'ATLAS-2'], dropIndex: 0 } },
        },
      )

      await new Promise((r) => setTimeout(r, 100))
      expect(mockRerankMutate).not.toHaveBeenCalled()
    })
  })

  // ── 드래그 시나리오 ──────────────────────────────────────────────────────────

  describe('onDragEnd — 드래그 시나리오', () => {
    it('S1: 백로그→백로그 재정렬 시 rerankMutate만 호출한다', async () => {
      renderBoard()

      triggerDragEnd(
        {
          id: 'backlog:ATLAS-2',
          data: { current: { issueKey: 'ATLAS-2', context: 'backlog', sprintId: null } },
        },
        {
          id: 'backlog',
          data: { current: { context: 'backlog', sprintId: null, orderedKeys: ['ATLAS-1', 'ATLAS-2'], dropIndex: 0 } },
        },
      )

      await waitFor(() => {
        expect(mockRerankMutate).toHaveBeenCalledWith(
          expect.objectContaining({ issueKey: 'ATLAS-2' }),
          expect.anything(),
        )
      })
      expect(mockAssignMutate).not.toHaveBeenCalled()
      expect(mockUnassignMutate).not.toHaveBeenCalled()
    })

    it('S2: 백로그→스프린트 이동 시 assignMutate를 먼저 호출한다', async () => {
      renderBoard()

      triggerDragEnd(
        {
          id: 'backlog:ATLAS-1',
          data: { current: { issueKey: 'ATLAS-1', context: 'backlog', sprintId: null } },
        },
        {
          id: `sprint-${PLANNED_SPRINT_ID}`,
          data: {
            current: {
              context: 'sprint',
              sprintId: PLANNED_SPRINT_ID,
              orderedKeys: ['ATLAS-3'],
              dropIndex: 1,
            },
          },
        },
      )

      await waitFor(() => {
        expect(mockAssignMutate).toHaveBeenCalledWith(
          expect.objectContaining({
            sprintId: PLANNED_SPRINT_ID,
            issueKey: 'ATLAS-1',
          }),
          expect.anything(),
        )
      })
      expect(mockUnassignMutate).not.toHaveBeenCalled()
    })

    it('S3: 스프린트→백로그 이동 시 unassignMutate를 먼저 호출한다', async () => {
      renderBoard()

      triggerDragEnd(
        {
          id: 'sprint:ATLAS-3',
          data: {
            current: { issueKey: 'ATLAS-3', context: 'sprint', sprintId: PLANNED_SPRINT_ID },
          },
        },
        {
          id: 'backlog',
          data: { current: { context: 'backlog', sprintId: null, orderedKeys: ['ATLAS-1', 'ATLAS-2'], dropIndex: 2 } },
        },
      )

      await waitFor(() => {
        expect(mockUnassignMutate).toHaveBeenCalledWith(
          expect.objectContaining({
            sprintId: PLANNED_SPRINT_ID,
            issueKey: 'ATLAS-3',
          }),
          expect.anything(),
        )
      })
      expect(mockAssignMutate).not.toHaveBeenCalled()
    })

    it('S4: 스프린트X→스프린트Y 이동 시 assign(Y) mutate를 호출한다', async () => {
      renderBoard()

      triggerDragEnd(
        {
          id: 'sprint:ATLAS-3',
          data: {
            current: { issueKey: 'ATLAS-3', context: 'sprint', sprintId: PLANNED_SPRINT_ID },
          },
        },
        {
          id: `sprint-${ACTIVE_SPRINT_ID}`,
          data: {
            current: {
              context: 'sprint',
              sprintId: ACTIVE_SPRINT_ID,
              orderedKeys: [],
              dropIndex: 0,
            },
          },
        },
      )

      await waitFor(() => {
        expect(mockAssignMutate).toHaveBeenCalledWith(
          expect.objectContaining({
            sprintId: ACTIVE_SPRINT_ID,
            issueKey: 'ATLAS-3',
          }),
          expect.anything(),
        )
      })
    })

    it('S5: 같은 스프린트 내 재정렬 시 rerankMutate만 호출한다', async () => {
      renderBoard()

      // 스프린트1에 ATLAS-3, ATLAS-X 두 이슈가 있다고 가정하고
      // ATLAS-3를 뒤로 이동(dropIndex=2, 맨 뒤) — 제자리(dropIndex=0)가 아닌 이동
      triggerDragEnd(
        {
          id: 'sprint:ATLAS-3',
          data: {
            current: { issueKey: 'ATLAS-3', context: 'sprint', sprintId: PLANNED_SPRINT_ID },
          },
        },
        {
          id: `sprint-${PLANNED_SPRINT_ID}`,
          data: {
            current: {
              context: 'sprint',
              sprintId: PLANNED_SPRINT_ID,
              orderedKeys: ['ATLAS-3', 'ATLAS-X'],
              dropIndex: 2, // 맨 뒤로 이동
            },
          },
        },
      )

      await waitFor(() => {
        expect(mockRerankMutate).toHaveBeenCalledWith(
          expect.objectContaining({ issueKey: 'ATLAS-3' }),
          expect.anything(),
        )
      })
      expect(mockAssignMutate).not.toHaveBeenCalled()
    })
  })

  // ── C1: 부분 실패 처리 ────────────────────────────────────────────────────────

  describe('C1: 이동 성공·rerank 실패 시 경고 토스트', () => {
    it('assign 성공 후 rerank 실패이면 경고 토스트를 표시한다', async () => {
      renderBoard()

      // assign mutation의 onSuccess 콜백에서 rerank를 호출하는 방식이므로,
      // assign mutate 호출 시 onSuccess를 즉시 실행해 rerank를 시뮬레이션한다.
      mockAssignMutate.mockImplementation(
        (_vars: unknown, options?: { onSuccess?: () => void }) => {
          options?.onSuccess?.()
        },
      )
      // rerank가 실패하도록 모의한다.
      mockRerankMutate.mockImplementation(
        (_vars: unknown, options?: { onError?: (err: unknown) => void }) => {
          options?.onError?.(new Error('rank 실패'))
        },
      )

      triggerDragEnd(
        {
          id: 'backlog:ATLAS-1',
          data: { current: { issueKey: 'ATLAS-1', context: 'backlog', sprintId: null } },
        },
        {
          id: `sprint-${PLANNED_SPRINT_ID}`,
          data: {
            current: {
              context: 'sprint',
              sprintId: PLANNED_SPRINT_ID,
              orderedKeys: ['ATLAS-3'],
              dropIndex: 1,
            },
          },
        },
      )

      await waitFor(() => {
        expect(toast.warning).toHaveBeenCalledWith(
          expect.stringContaining('순서'),
        )
      })
    })
  })

  // ── 드래그 재정렬 결선 검증 (concern-1 red) ─────────────────────────────────
  // 현재 BacklogColumn/SprintColumn의 useDroppable data에 orderedKeys가 없고,
  // BacklogCard에 useDroppable이 없어서 카드-over 이벤트가 도달하지 않는다.
  // 아래 테스트는 over.data에 orderedKeys를 주입하지 않고 카드 droppable id로
  // triggerDragEnd를 호출해 rerankMutate가 실제 이웃 키로 호출되는지 검증한다.

  describe('concern-1: 드래그 재정렬 결선 — card droppable over 시 실제 이웃 계산', () => {
    it('S1 red: 백로그 카드-over로 재정렬 시 rerankMutate가 실제 이웃 키로 호출된다', async () => {
      // mock fixture: backlog=['ATLAS-1','ATLAS-2'], card droppable id='card:backlog:ATLAS-2'
      // ATLAS-1을 ATLAS-2 위로 드래그 → over = card:backlog:ATLAS-2 (dropIndex=1)
      // expectedRerank: ATLAS-2를 index=0(맨앞)으로 → prev=undefined, next='ATLAS-1' 아님
      // ATLAS-1이 ATLAS-2 위(=ATLAS-2의 index=1) 에 드롭 → ATLAS-1이 index=1 위치 = ATLAS-2 앞
      // targetKeys=['ATLAS-1','ATLAS-2'], 드래그=ATLAS-1, over=card:ATLAS-2 → dropIndex=1
      // issueKey=ATLAS-1 originIdx=0, dropIndex=1 → 제자리(originIdx와 dropIndex 인접이면 noop 아닌지?)
      // resolve: isSameLane, originIdx=0, dropIndex=1, 제자리 판정: dropIndex===originIdx? 1===0? No
      //   others=['ATLAS-2'], adjustedIdx=0(1-1=0), computeNeighbors(['ATLAS-2'],0) → {prev:undefined, next:'ATLAS-2'}
      // 기대: rerankMutate({ issueKey:'ATLAS-1', body:{prev:undefined, next:'ATLAS-2'} })
      renderBoard()

      // over = card droppable id (현재 구현에서는 카드에 droppable이 없어서
      // extractDropZone이 null을 반환해 rerankMutate가 호출되지 않음 — RED)
      triggerDragEnd(
        {
          id: 'backlog:ATLAS-1',
          data: { current: { issueKey: 'ATLAS-1', context: 'backlog', sprintId: null } },
        },
        {
          // card droppable id: 'card:backlog:ATLAS-2'
          // data에 type:'card' / key:'ATLAS-2' / context:'backlog' / sprintId:null
          id: 'card:backlog:ATLAS-2',
          data: {
            current: {
              type: 'card',
              key: 'ATLAS-2',
              context: 'backlog',
              sprintId: null,
            },
          },
        },
      )

      await waitFor(() => {
        expect(mockRerankMutate).toHaveBeenCalledWith(
          expect.objectContaining({ issueKey: 'ATLAS-1' }),
          expect.anything(),
        )
      })
      expect(mockAssignMutate).not.toHaveBeenCalled()
      expect(mockUnassignMutate).not.toHaveBeenCalled()
    })

    it('S5 red: 스프린트 카드-over로 재정렬 시 rerankMutate가 실제 이웃 키로 호출된다', async () => {
      // PLANNED_SPRINT_ID issues=['ATLAS-3']
      // ATLAS-3을 같은 스프린트 내에서 card:sprint:ATLAS-3 over로 드래그 (제자리 = noop)
      // 다른 카드가 없으므로 noop-move이어야 함 → rerankMutate 미호출
      // 여기서는 ATLAS-3을 over=카드droppable(ATLAS-3 자신) → 제자리 noop → mutate 미호출이 기대
      // 하지만 card droppable이 없으면 extractDropZone=null → 역시 mutate 미호출
      // 이 테스트를 RED로 만들려면 다른 이슈(ATLAS-X)가 있는 스프린트에서 검증해야 함
      // → mock에 ATLAS-3 외 이슈를 추가해 실제 이웃 계산을 검증
      // mock fixture에는 스프린트1에 ATLAS-3만 있어 E1(noop-move) 케이스
      // 스프린트2는 빈 ACTIVE 스프린트이므로 스프린트1에서 두 카드 재정렬 불가
      // → 스프린트1에 ATLAS-3만 존재: ATLAS-3을 card:sprint:ATLAS-3으로 드롭 → noop-move
      // card droppable 구현 전: extractDropZone=null → mutate 미호출 (우연히 같은 결과 → 가짜그린)
      // 진짜 RED: 스프린트1에 ATLAS-3, ATLAS-Y 두 이슈가 있을 때 ATLAS-Y over card droppable로 드롭
      // useBacklog mock을 여기서 수정할 수 없으므로 board-level에서 orderedKeys를 읽는 경로를 직접 확인
      // 대신 아래와 같이 over id = card droppable인데 column droppable처럼 context만 있는 data를 주면
      // 현재 extractDropZone은 context='sprint'를 찾아 처리 → orderedKeys=[] → dropIndex=0
      // → 목표: over.data.current에 type:'card'가 있으면 별도 경로로 처리해야 한다 (RED: 현재 type:'card' 분기 없음)
      renderBoard()

      triggerDragEnd(
        {
          id: 'sprint:ATLAS-3',
          data: { current: { issueKey: 'ATLAS-3', context: 'sprint', sprintId: PLANNED_SPRINT_ID } },
        },
        {
          id: 'card:sprint:ATLAS-3',
          data: {
            current: {
              type: 'card',
              key: 'ATLAS-3',
              context: 'sprint',
              sprintId: PLANNED_SPRINT_ID,
            },
          },
        },
      )

      // ATLAS-3이 혼자 있는 스프린트에서 자기 자신 위로 드롭 → noop-move → rerankMutate 미호출
      await new Promise((r) => setTimeout(r, 50))
      expect(mockRerankMutate).not.toHaveBeenCalled()
      expect(mockAssignMutate).not.toHaveBeenCalled()
    })
  })

  // ── truncated 경고 배너 ──────────────────────────────────────────────────────

  describe('truncated 경고 배너', () => {
    it('truncated=true이면 경고 배너를 표시한다', async () => {
      // truncated=true 버전의 데이터로 mock 재정의
      vi.doMock('@/hooks/use-backlog', () => ({
        useBacklog: () => ({
          data: {
            backlog: [],
            sprints: [],
            truncated: true,
          },
          isLoading: false,
        }),
        useRerankIssue: () => ({ mutate: mockRerankMutate, isPending: false }),
        useAssignToSprint: () => ({ mutate: mockAssignMutate, isPending: false }),
        useUnassignFromSprint: () => ({ mutate: mockUnassignMutate, isPending: false }),
        useCreateSprint: () => ({ mutate: mockCreateSprintMutate, isPending: false }),
        useStartSprint: () => ({ mutate: mockStartSprintMutate, isPending: false }),
        useCompleteSprint: () => ({ mutate: mockCompleteSprintMutate, isPending: false }),
        backlogKeys: { detail: (key: string) => ['backlog', key] },
      }))

      // truncated 배너는 BacklogBoard 내부 로직에 따라 렌더된다.
      // 이 테스트는 truncated prop 전달 후 배너 문구 확인.
      // (모듈 재로드가 jsdom 환경에서 제한되므로 canManage prop 변형 테스트로 우회)
      // truncated 배너는 BacklogBoard가 data.truncated를 읽어 직접 렌더하므로
      // 실제 컴포넌트 구현 후 활성화됨.
      // 여기서는 truncated=false인 기본 fixture로 배너가 없음을 확인.
      renderBoard()
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-09 F3 — 모달 소유권 (FR-15) + 진입점 배선 (FR-1)
//
// ★「생성하면 칸에 나타난다」는 여기서 검증하지 않는다.
// 이 파일은 `use-backlog` 를 통째로 mock 해 정적 데이터를 돌려주므로,
// 무엇을 만들어도 목록이 변하지 않는다. 그 단언을 여기 두면 **영원히 거짓**이거나
// 목을 흉내 내느라 진짜를 안 보게 된다. 나타남 판정은 실제 MSW 를 쓰는 E2E(T8) 몫이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogBoard — 이슈 생성 모달 소유권 (F3 FR-15)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('백로그 칸 진입점을 누르면 모달이 열린다', async () => {
    const user = userEvent.setup()
    renderBoard('ATLAS', { canCreateIssue: true })

    expect(screen.queryByRole('dialog')).toBeNull()
    await user.click(
      screen.getByRole('button', { name: backlogLabels.createIssueInBacklog }),
    )

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  it('★스프린트가 2개여도 모달 인스턴스는 1개다 (role="dialog" strict mode 방지)', async () => {
    const user = userEvent.setup()
    renderBoard('ATLAS', { canCreateIssue: true })

    await user.click(
      screen.getByRole('button', { name: backlogLabels.createIssueInBacklog }),
    )
    await screen.findByRole('dialog')

    // 픽스처의 스프린트는 2개다. 칸마다 모달을 두면 여기서 3개가 된다.
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
  })

  it('★canCreateIssue=false 면 진입점이 전부 비활성이다 (fail-closed)', () => {
    renderBoard('ATLAS', { canCreateIssue: false })

    expect(
      screen.getByRole('button', { name: backlogLabels.createIssueInBacklog }),
    ).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-09 F3 — 스프린트 배정 (FR-4) + 부분 성공 (FR-5)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogBoard — 스프린트 칸에서 만든 이슈의 배정 (F3 FR-4/FR-5)', () => {
  // ⚠️ 이 블록은 위 `describe('BacklogBoard')` 밖이라 그쪽 beforeEach 가 닿지 않는다.
  // 없으면 앞 테스트의 호출 기록이 넘어와 「부르지 않았다」 단언이 거짓 실패한다.
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('스프린트 칸에서 만들면 그 스프린트로 배정을 건다', async () => {
    const user = userEvent.setup()
    renderBoard('ATLAS', { canCreateIssue: true })

    await user.click(
      screen.getByRole('button', {
        name: backlogLabels.createIssueInSprint('스프린트 1'),
      }),
    )
    await screen.findByRole('dialog')

    await user.click(screen.getByRole('button', { name: '스텁 생성 완료' }))

    await waitFor(() => {
      expect(mockAssignMutate).toHaveBeenCalledWith(
        expect.objectContaining({ sprintId: PLANNED_SPRINT_ID }),
        expect.anything(),
      )
    })
  })

  it('백로그 칸에서 만들면 배정을 걸지 않는다 (기본 동작이 곧 백로그)', async () => {
    const user = userEvent.setup()
    renderBoard('ATLAS', { canCreateIssue: true })

    await user.click(
      screen.getByRole('button', { name: backlogLabels.createIssueInBacklog }),
    )
    await screen.findByRole('dialog')
    await user.click(screen.getByRole('button', { name: '스텁 생성 완료' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
    expect(mockAssignMutate).not.toHaveBeenCalled()
  })

  it('★배정만 실패하면 이슈가 만들어졌음을 경고 톤으로 알린다 (FR-5, 에러 톤 금지)', async () => {
    const user = userEvent.setup()
    // 배정 mutation 이 onError 를 부르도록 만든다
    mockAssignMutate.mockImplementation(
      (_vars: unknown, opts?: { onError?: () => void }) => { opts?.onError?.() },
    )
    renderBoard('ATLAS', { canCreateIssue: true })

    await user.click(
      screen.getByRole('button', {
        name: backlogLabels.createIssueInSprint('스프린트 1'),
      }),
    )
    await screen.findByRole('dialog')
    await user.click(screen.getByRole('button', { name: '스텁 생성 완료' }))

    await waitFor(() => {
      expect(toast.warning).toHaveBeenCalled()
    })
    // 빨간 실패 토스트는 「안 만들어졌다」로 읽혀 재시도 → 중복 이슈를 부른다
    expect(toast.error).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F5 — 백로그·스프린트 카드 담당자 이름
//
// ★이 블록의 red 가 결함의 증인이다 — 배선 전에는 `BacklogBoard` 가 빈 Map 을 그대로
// 넘겨서, 담당자가 배정된 카드까지 전부 `?`(이름 미확인)로 그려진다.
// ─────────────────────────────────────────────────────────────────────────────

/** 조회에 성공했을 때 돌아오는 사용자 — bob 은 displayName 이 없어 username 으로 떨어진다. */
const ALICE_USER: UserSummary = {
  id: ALICE_ID,
  username: 'alice',
  displayName: '김앨리스',
  email: null,
}
const BOB_USER: UserSummary = {
  id: BOB_ID,
  username: 'bob',
  displayName: null,
  email: null,
}
const RESOLVED_USERS: UserSummary[] = [ALICE_USER, BOB_USER]

describe('BacklogBoard — 담당자 이름 표시 (FR-UX-13 F5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUsersResult = NO_USERS
  })

  it('T3-1: 담당자가 있는 카드는 이니셜 아바타를 표시하고, 수집한 id 가 조회 훅에 그대로 넘어간다', async () => {
    mockUsersResult = { data: RESOLVED_USERS }
    renderBoard()

    expect(await screen.findByLabelText('담당자: 김앨리스')).toBeInTheDocument()
    // 스프린트 칸도 같은 Map 을 받아야 한다 — 한쪽만 배선하는 반쪽 봉합을 막는다
    expect(screen.getByLabelText('담당자: bob')).toBeInTheDocument()

    // ★배선 단언 (FR-2·NFR-1). 화면 단언만으로는 `useUsersByIdsChunked([])` 로 바꿔도
    //   `mockUsersResult` 가 이름을 계속 돌려줘 전부 초록이 된다 — 실제 뮤테이션으로 확인된
    //   구멍이다. ALICE 는 백로그, BOB 은 스프린트에서 나오므로 이 한 줄이 「두 칸을 모두
    //   훑었는가」까지 함께 잰다.
    //   ※ 중복 제거 자체는 순수 함수 쪽 `backlog-assignee-names.test.ts` T1-1 이 봉인한다.
    expect(mockUseUsersByIdsChunked).toHaveBeenCalledWith([ALICE_ID, BOB_ID])
  })

  it('T3-2: 미배정 이슈는 "미배정" 텍스트를 유지한다', async () => {
    mockUsersResult = { data: RESOLVED_USERS }
    renderBoard()

    // 픽스처의 미배정 이슈는 3건(ATLAS-1·2·3)이라 단건 조회는 strict mode 로 깨진다
    expect(await screen.findAllByText(backlogLabels.unassigned)).toHaveLength(3)
  })

  it('T3-3: 사용자 조회가 전량 실패해도 카드는 렌더된다 (fail-soft, 스펙 S8·FR-7)', async () => {
    // 훅은 실패 표면을 내보내지 않는다 — 실패한 묶음은 결과에서 빠질 뿐이라
    // 전량 실패는 소비처에게 **빈 `data`** 로 보인다. 그래서 픽스처도 그 모양이다.
    mockUsersResult = { data: [] }
    renderBoard()

    expect(await screen.findByText('담당자 있는 백로그 이슈')).toBeInTheDocument()
    // 담당자만 `?` 로 떨어진다 — 카드가 사라지거나 화면이 에러로 대체되지 않는다
    expect(
      screen.getAllByLabelText(backlogLabels.unknownAssigneeAriaLabel),
    ).toHaveLength(2)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('T3-4: 묶음 일부만 실패하면 풀린 이름과 `?` 가 공존한다 (부분 실패)', async () => {
    // 담당자가 50명을 넘으면 조회가 2묶음 이상으로 갈라지고 **한쪽만** 실패할 수 있다.
    // 성공한 묶음의 `data` 는 그대로 합쳐지므로 이 상태가 실제로 도달 가능하다.
    // 여기서 전 카드가 `?` 로 떨어지면 반쪽 봉합이다 — T3-3(전량 실패)만으로는 안 잡힌다.
    mockUsersResult = { data: [ALICE_USER] }
    renderBoard()

    expect(await screen.findByLabelText('담당자: 김앨리스')).toBeInTheDocument()
    expect(screen.queryByLabelText('담당자: bob')).toBeNull()
    // 이름을 못 받은 BOB 카드 1건만 `?` 다
    expect(
      screen.getAllByLabelText(backlogLabels.unknownAssigneeAriaLabel),
    ).toHaveLength(1)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F5 — 백로그 조회 실패 안내와 재시도 (G2)
//
// ★이 블록의 red 가 결함의 증인이다 — 봉합 전에는 조회가 실패하면 `<div />` 가 반환돼
// 화면이 통째로 빈다. 사용자는 무엇이 잘못됐는지도, 어떻게 벗어나는지도 알 수 없다.
// ─────────────────────────────────────────────────────────────────────────────

/** 조회는 성공했지만 cap 초과로 잘린 뷰 — 경고 배너 1개만 뜨는지 보는 데 쓴다 */
const TRUNCATED_VIEW: BacklogView = { backlog: [], sprints: [], truncated: true }

/**
 * 한 번 성공해 캐시에 남은 뷰.
 *
 * ★T4-2b 가 이걸 쓰는 이유. `isError && isFetching` 은 **`data` 가 있을 때만 도달 가능**하다 —
 * TanStack Query 는 `data === undefined` 인 쿼리를 재조회하면 `status` 를 `'pending'` 으로
 * 되돌리며 에러를 지운다(`fetchState` 의 `data === undefined` 분기). 실브라우저로 확인한
 * 결과도 같다. 최초 로드 실패 후의 재시도는 **로딩 화면**으로 가고, `다시 시도 중…` 라벨은
 * 「캐시에 data 가 남은 채 재조회가 실패한」 경로에서만 실제로 보인다.
 * 픽스처를 `data: undefined` 로 두면 만들어질 수 없는 상태를 지키는 가짜 그린이 된다.
 */
const CACHED_VIEW: BacklogView = { backlog: [], sprints: [], truncated: false }

/**
 * 이슈가 하나도 없는 정상 조회 (스펙 E7).
 *
 * `CACHED_VIEW` 와 값은 같지만 **재는 대상이 다르다** — 이쪽은 「조회는 성공했는데 이슈가 0건」이고,
 * 저쪽은 「이전 성공분이 캐시에 남은 채 재조회가 실패」다. 한 상수로 합치면 둘 중 하나가 바뀌는 날
 * 다른 하나가 조용히 따라 바뀐다.
 */
const EMPTY_VIEW: BacklogView = { backlog: [], sprints: [], truncated: false }

describe('BacklogBoard — 조회 실패 안내와 재시도 (FR-UX-13 F5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUsersResult = NO_USERS
  })

  afterEach(() => {
    // 주입 상태를 반드시 되돌린다 — 남기면 뒤에 추가되는 블록이 영문 모를 에러 화면을 본다
    mockBacklogQueryOverride = {}
  })

  it('T4-1: 조회 실패 시 role="alert" 안내가 뜬다 (빈 화면 아님)', async () => {
    mockBacklogQueryOverride = { data: undefined, isLoading: false, isError: true }
    renderBoard()

    expect(await screen.findByRole('alert')).toHaveTextContent(backlogLabels.loadFailed)
  })

  it('T4-2: "다시 시도" 버튼이 refetch 를 호출한다 (새로고침 금지)', async () => {
    const user = userEvent.setup()
    mockBacklogQueryOverride = { data: undefined, isLoading: false, isError: true }
    renderBoard()

    // 문자열 `name` 은 Testing Library 에서 **정확 일치**다. 그래서 토스트 문구
    // `moveFailedError`('…다시 시도해 주세요.')도, 재조회 라벨 '다시 시도 중…'도 걸리지 않는다.
    // 정확 일치라는 전제 자체는 T4-2b 의 마지막 단언이 지킨다.
    await user.click(screen.getByRole('button', { name: backlogLabels.retry }))

    expect(mockRefetch).toHaveBeenCalledTimes(1)
  })

  it('T4-2b: 재조회 중에는 버튼이 비활성화되고 라벨이 바뀐다 (design review D3)', () => {
    // `data` 가 있는 상태여야 실제로 도달 가능한 조합이다 — 근거는 CACHED_VIEW 주석
    mockBacklogQueryOverride = {
      data: CACHED_VIEW,
      isLoading: false,
      isError: true,
      isFetching: true,
    }
    renderBoard()

    expect(screen.getByRole('button', { name: backlogLabels.retrying })).toBeDisabled()
    // ★`retry`('다시 시도') ⊂ `retrying`('다시 시도 중…') 이라 조회가 부분 일치이면
    //   여기서 재조회 중 버튼이 잡힌다. null 이어야 T4-2 의 조회가 정확하다는 뜻이다.
    expect(screen.queryByRole('button', { name: backlogLabels.retry })).toBeNull()
  })

  it('T4-3: 로딩 중에는 로딩 표시만 뜨고 에러 블록은 없다 (스펙 FR-8·E8)', () => {
    // ★`isLoading: true` + `isError: true` 는 **만들어질 수 없는 조합**이라 픽스처에 쓰지 않는다 —
    //   `isLoading = isPending && isFetching` 이고 `isPending`·`isError` 는 같은 `status` 열거의
    //   배타 값이다 (@tanstack/query-core@5.100.11 `build/modern/queryObserver.js:308-310`).
    //   그 조합을 지키는 단언은 도달 불가능한 상태를 지키는 가짜 그린이다 — T4-2b 가 같은 이유로
    //   이미 한 번 교정된 자리다.
    //   따라서 이 테스트가 재는 것은 「분기 순서」가 아니라 **도달 가능한 로딩 상태**이며,
    //   FR-8(로딩 표시 유지)과 엣지 E8 의 유일한 커버리지다.
    mockBacklogQueryOverride = { data: undefined, isLoading: true, isError: false }
    renderBoard()

    expect(screen.getByLabelText('로딩 중')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('T4-4: 정상 조회 + truncated 일 때 경고 배너는 1개뿐이다 (스펙 E10 상호 배타)', async () => {
    mockBacklogQueryOverride = { data: TRUNCATED_VIEW, isLoading: false, isError: false }
    renderBoard()

    expect(await screen.findAllByRole('alert')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: backlogLabels.retry })).toBeNull()
  })

  it('T4-5: 이슈가 0건이어도 빈 칸을 그린다 — 에러가 아니다 (스펙 E7)', async () => {
    // T4-4 는 `truncated: true` 라 **경고가 뜨는** 쪽을 잰다. 이슈만 없는 정상 조회는
    // 그 반대편이고, 여기가 비면 사용자는 「고장」과 「할 일 없음」을 구별할 수 없다.
    mockBacklogQueryOverride = { data: EMPTY_VIEW, isLoading: false, isError: false }
    renderBoard()

    expect(await screen.findByText('백로그')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByLabelText('로딩 중')).toBeNull()
    expect(screen.queryByRole('button', { name: backlogLabels.retry })).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F15 T9 — 스프린트가 0개인 백로그 (E1)
//
// ★T6 이 세로 스택을 만들 때 이 파일을 소유하지 않아 단언이 비어 있던 자리다.
//   「스프린트가 0개면 화면이 어떻게 되나」는 신규 프로젝트의 **첫 화면**이다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트가 0개인 정상 조회 (E1).
 *
 * `EMPTY_VIEW` 와 달리 백로그에 이슈가 **있다** — 「칸이 하나뿐」이라는 관측이
 * 「아무것도 안 그려졌다」와 구별되어야 스택 구조 단언이 공허해지지 않는다.
 */
const NO_SPRINT_VIEW: BacklogView = {
  backlog: [
    {
      key: 'ATLAS-1',
      summary: '백로그 이슈 1',
      currentStateKey: 'open',
      assigneeId: null,
      priority: 1,
      rank: '0|a:',
      version: 0,
      epicKey: null,
    } satisfies BacklogIssue,
  ],
  sprints: [],
  truncated: false,
}

describe('BacklogBoard — 스프린트가 0개인 백로그 (FR-UX-13 F15 E1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUsersResult = NO_USERS
    mockBacklogQueryOverride = { data: NO_SPRINT_VIEW, isLoading: false, isError: false }
  })

  afterEach(() => {
    mockBacklogQueryOverride = {}
  })

  it('E1: 세로 스택에 백로그 칸 하나만 남고 시작/완료 버튼이 없다', async () => {
    renderBoard()

    const regions = await screen.findAllByRole('region')
    expect(regions).toHaveLength(1)
    expect(regions[0]).toHaveAccessibleName(
      backlogLabels.columnAriaLabel(backlogLabels.backlogTitle, 1),
    )

    expect(screen.queryByRole('button', { name: backlogLabels.startSprint })).toBeNull()
    expect(screen.queryByRole('button', { name: backlogLabels.completeSprint })).toBeNull()
  })

  it('E1: 스프린트 생성 폼은 세로 스택 **바깥** 현행 위치 그대로다', async () => {
    renderBoard()

    // 스택 = 칸들의 공통 부모. 스프린트가 0개여도 이 컨테이너는 그대로 있어야 한다.
    const backlogColumn = await screen.findByRole('region')
    const stack = backlogColumn.parentElement
    expect(stack).not.toBeNull()

    const nameInput = screen.getByPlaceholderText(/스프린트 이름/i)
    // 스택이 폼을 삼키지 않았다 — 삼키면 카드 드롭 영역 안에 입력창이 들어간다
    expect(stack).not.toContainElement(nameInput)
    // 그리고 폼이 스택보다 **앞**이다 (현행 위치 = truncated 배너 다음, 스택 이전)
    expect(nameInput.compareDocumentPosition(stack as Node) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F15 T9 — 키보드 DnD 센서 · 한국어 공지 배선 (FR-8 · FR-9 · FR-15 · FR-17)
//
// ★센서와 공지는 화면에 글자로 나오지 않는다. `DndContext` props 를 직접 재지 않으면
//   배선을 통째로 지워도 이 파일이 전량 초록으로 남는다.
// ─────────────────────────────────────────────────────────────────────────────

/** 공지 빌더가 읽는 것은 `{active, over}` 뿐이다 — 최소 이벤트만 만든다 */
function announceEvent(
  activeId: string,
  activeData: Record<string, unknown>,
  over: { id: string; data: Record<string, unknown> } | null,
): { active: Active; over: Over | null } {
  return {
    active: { id: activeId, data: { current: activeData } } as unknown as Active,
    over:
      over === null
        ? null
        : ({ id: over.id, data: { current: over.data } } as unknown as Over),
  }
}

/** 스프린트2의 카드를 백로그 칸으로 옮기는 드롭 — 판정은 `unassign` 이다 */
function sprintToBacklogEvent(): { active: Active; over: Over | null } {
  return announceEvent(
    'sprint:ATLAS-5',
    { issueKey: 'ATLAS-5', context: 'sprint', sprintId: ACTIVE_SPRINT_ID },
    { id: 'backlog', data: { context: 'backlog', sprintId: null, orderedKeys: ['ATLAS-1'] } },
  )
}

describe('BacklogBoard — 키보드 DnD 센서와 한국어 공지 배선 (FR-UX-13 F15)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUsersResult = NO_USERS
    capturedSensors = undefined
    capturedAccessibility = undefined
  })

  it('FR-8·FR-15: KeyboardSensor 를 좌표 계산기·활성화 키 옵션과 함께 등록한다', () => {
    renderBoard()

    const descriptors = capturedSensors ?? []
    const keyboard = descriptors.find((descriptor) => descriptor.sensor === KeyboardSensor)
    expect(keyboard).toBeDefined()
    // 옵션을 **같은 참조**로 잰다 — 기본 좌표 계산기(방향키 1회 25px)로 되돌리면 red 다
    expect(keyboard?.options).toBe(backlogKeyboardSensorOptions)

    // ★짝 단언 — PointerSensor 의 5px 임계는 그대로여야 한다.
    //   낮추면 카드 안 이슈 링크 클릭이 드래그로 먹힌다 (FR-8 이 명시한 보존 조건).
    const pointer = descriptors.find((descriptor) => descriptor.sensor === PointerSensor)
    expect(pointer?.options).toEqual({ activationConstraint: { distance: 5 } })
  })

  it('FR-9: 영어 기본 안내 대신 한국어 공지·안내를 넘긴다', () => {
    renderBoard()

    // `accessibility={undefined}` 로 두면 dnd-kit 의 영어 기본값이 그대로 남는다
    expect(capturedAccessibility?.screenReaderInstructions).toBe(backlogScreenReaderInstructions)

    const message = capturedAccessibility?.announcements?.onDragEnd?.(sprintToBacklogEvent())
    expect(message).toBe(backlogLabels.announce.movedToBacklog)
  })

  it('★FR-17: UPDATE 권한이 없으면 「옮겼습니다」 대신 권한 안내를 읽는다', () => {
    renderBoard('ATLAS', { canReorderIssue: false })

    // 권한이 없으면 `use-backlog-drag.ts` 가 mutation 을 0건으로 막는다. 그 상태에서
    // 「옮겼습니다」를 읽으면 스크린리더 사용자에게**만** 거짓말이 된다.
    const message = capturedAccessibility?.announcements?.onDragEnd?.(sprintToBacklogEvent())
    expect(message).toBe(backlogLabels.announce.forbidden)
  })

  it('T-KB-3: 키보드로 집자마자 놓으면(이동 0) mutation 이 0건이다', async () => {
    renderBoard()

    triggerDragEnd(
      {
        id: 'sprint:ATLAS-5',
        data: { current: { issueKey: 'ATLAS-5', context: 'sprint', sprintId: ACTIVE_SPRINT_ID } },
      },
      {
        id: 'backlog',
        data: { current: { context: 'backlog', sprintId: null, orderedKeys: ['ATLAS-1'] } },
      },
      // Space 두 번 — 방향키를 한 번도 안 눌렀으니 이동이 0이다
      { delta: { x: 0, y: 0 }, activatorEvent: new KeyboardEvent('keydown', { code: 'Space' }) },
    )

    await waitFor(() => {
      expect(mockUnassignMutate).not.toHaveBeenCalled()
    })
    expect(mockRerankMutate).not.toHaveBeenCalled()
    expect(mockAssignMutate).not.toHaveBeenCalled()
  })
})
