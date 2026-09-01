// 보드 스위처 드롭다운 단위 테스트 — 전환 · 생성 항목 게이팅(showCreate × CREATE 권한) (FR-BD-04)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { BoardSummary } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { BoardSelectorDropdown } from '@/components/board/BoardSelectorDropdown'

// ─────────────────────────────────────────────────────────────────────────────
// mock — 권한 훅 · 생성 폼
// ─────────────────────────────────────────────────────────────────────────────

const mockUseProjectPermissions = vi.fn()
vi.mock('@/hooks/use-project-permissions', () => ({
  useProjectPermissions: (projectKey: string) => mockUseProjectPermissions(projectKey),
}))

// 생성 폼은 자체 mutation 을 가져 QueryClient 를 요구한다. 여기서 재는 것은 「항목이 있느냐」와
// 「눌렀을 때 창이 열리느냐」이므로 폼 내부는 대역으로 충분하다.
vi.mock('@/components/board/CreateBoardForm', () => ({
  CreateBoardForm: ({ projectKey }: { projectKey: string }) => (
    <div data-testid="create-board-form" data-project-key={projectKey}>
      CreateBoardForm
    </div>
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixture
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_A: BoardSummary = {
  boardId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567891',
  projectKey: 'ATLAS',
  name: '스프린트 보드 A',
  boardType: 'SCRUM',
}

const BOARD_B: BoardSummary = {
  boardId: 'b2c3d4e5-f6a7-4890-abcd-ef1234567892',
  projectKey: 'ATLAS',
  name: '칸반 보드 B',
  boardType: 'KANBAN',
}

/** CREATE 권한 응답 — 이 값만 바꿔 fail-closed 축을 잰다 */
function permissionsWith(canCreate: boolean) {
  return {
    data: {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: canCreate,
        MANAGE_COMPONENTS: false,
        MANAGE_VERSIONS: false,
        MANAGE_CUSTOM_FIELDS: false,
        MANAGE_FIELD_PERMISSIONS: false,
        MANAGE_TEMPLATES: false,
      },
    },
    isLoading: false,
  }
}

function renderSelector(props?: { showCreate?: boolean; onSelect?: (id: string) => void }) {
  return render(
    <BoardSelectorDropdown
      boards={[BOARD_A, BOARD_B]}
      currentBoardId={BOARD_A.boardId}
      projectKey="ATLAS"
      onSelect={props?.onSelect ?? vi.fn()}
      {...(props?.showCreate !== undefined ? { showCreate: props.showCreate } : {})}
    />,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardSelectorDropdown', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseProjectPermissions.mockReturnValue(permissionsWith(true))
  })

  /**
   * T-BD04-SEL-1. 트리거는 현재 보드 이름을 보여주고, 열면 보드가 `menuitemradio` 로 나온다.
   * 추출 전 라우트 로컬 컴포넌트의 계약을 그대로 옮겼는지 재는 자리다.
   */
  it('T-BD04-SEL-1: 열면 보드가 menuitemradio 로 나오고 현재 보드만 checked 다', async () => {
    const user = userEvent.setup()

    renderSelector()

    const trigger = screen.getByRole('button', { name: /보드 선택/ })
    expect(trigger).toHaveTextContent(BOARD_A.name)

    await user.click(trigger)

    expect(await screen.findByRole('menuitemradio', { name: BOARD_A.name })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    expect(screen.getByRole('menuitemradio', { name: BOARD_B.name })).toHaveAttribute(
      'aria-checked',
      'false',
    )
  })

  /** T-BD04-SEL-2. 다른 보드를 고르면 그 boardId 로 onSelect 가 불린다 */
  it('T-BD04-SEL-2: 다른 보드를 고르면 onSelect 가 그 boardId 로 불린다', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()

    renderSelector({ onSelect })

    await user.click(screen.getByRole('button', { name: /보드 선택/ }))
    await user.click(await screen.findByRole('menuitemradio', { name: BOARD_B.name }))

    expect(onSelect).toHaveBeenCalledWith(BOARD_B.boardId)
  })

  /**
   * T-BD04-SEL-3. `showCreate` 기본값은 true — 보드 화면의 기존 동작이 그대로다.
   *
   * ★ T-BD04-SEL-4 의 **비-공허 짝**이다. 이 테스트가 없으면 항목을 통째로 지워도
   *   `queryByRole(...).toBeNull()` 이 그대로 통과한다.
   */
  it('T-BD04-SEL-3: showCreate 를 안 주면 「새 보드」 항목이 나오고 누르면 생성 폼이 열린다', async () => {
    const user = userEvent.setup()

    renderSelector()

    await user.click(screen.getByRole('button', { name: /보드 선택/ }))
    await user.click(
      await screen.findByRole('menuitem', { name: boardLabels.switcher.createItem }),
    )

    expect(await screen.findByTestId('create-board-form')).toBeInTheDocument()
  })

  /**
   * T-BD04-SEL-4. `showCreate={false}` 면 CREATE 권한이 있어도 생성 항목이 **DOM 에 없다**.
   *
   * 🛑 권한 축과 다른 축이라는 것이 요점이다 — 권한을 true 로 둔 채 재야 「권한이 없어서
   *   사라진 것」과 구별된다. 백로그 헤더가 이 조합으로 스위처를 쓴다.
   */
  it('T-BD04-SEL-4: showCreate=false 면 CREATE 권한이 있어도 「새 보드」 항목이 없다', async () => {
    const user = userEvent.setup()

    renderSelector({ showCreate: false })

    await user.click(screen.getByRole('button', { name: /보드 선택/ }))
    // 보드 목록은 그대로 나온다 — 스위처가 죽은 것이 아니라 생성 항목만 없는 것이다
    expect(await screen.findByRole('menuitemradio', { name: BOARD_A.name })).toBeInTheDocument()
    expect(
      screen.queryByRole('menuitem', { name: boardLabels.switcher.createItem }),
    ).not.toBeInTheDocument()
  })

  /** T-BD04-SEL-5. CREATE 권한이 없으면 showCreate 가 켜져 있어도 항목이 없다 (fail-closed) */
  it('T-BD04-SEL-5: CREATE 권한이 없으면 「새 보드」 항목이 없다', async () => {
    const user = userEvent.setup()
    mockUseProjectPermissions.mockReturnValue(permissionsWith(false))

    renderSelector({ showCreate: true })

    await user.click(screen.getByRole('button', { name: /보드 선택/ }))
    expect(await screen.findByRole('menuitemradio', { name: BOARD_A.name })).toBeInTheDocument()
    expect(
      screen.queryByRole('menuitem', { name: boardLabels.switcher.createItem }),
    ).not.toBeInTheDocument()
  })
})
