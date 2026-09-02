// 스프린트 `⋯` 메뉴 테스트 — 권한 부재 게이팅 · 삭제 확인 문구 갈래 · 실패 3갈래 (FR-BL-02 D6 FR-3·FR-5)
import { describe, it, expect, vi } from 'vitest'
import type { JSX } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { backlogLabels } from '@/i18n/backlog-labels'
import type { SprintMeta } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 편집 다이얼로그 스텁
//
// ★이 파일의 관심사는 **메뉴의 배선과 삭제 흐름**이다. 진짜 폼(입력 4칸·409 복구)은
//   `EditSprintDialog.test.tsx` 가 이미 전수로 잰다 — 여기서 또 구동하면 검증하려는 것보다
//   목 셋업이 커지고, 폼이 바뀔 때마다 이 파일이 함께 깨진다
//   (`BacklogBoard.test.tsx` 의 `CreateIssueDialog` 스텁이 세운 관례).
//
// ★★스텁이라야 **`boardId` 가 실제로 전달되는지**를 관측할 수 있다. 진짜 다이얼로그는
//   그 값을 409 복구 경로에서만 쓰므로, 통째로 빠뜨려도 화면이 똑같아 보인다.
//   진짜 다이얼로그가 열리는지(접근성 이름 포함)는 `SprintColumnHeader.test.tsx` 가 잰다.
// ─────────────────────────────────────────────────────────────────────────────

/** 스텁 다이얼로그의 접근성 이름 — 진짜 이름과 **일부러 다르다**(스텁임을 화면에서 구별) */
const EDIT_DIALOG_STUB_NAME = '스프린트 편집 스텁'

/** `boardId` 가 `undefined` 로 왔을 때 스텁이 대신 그리는 표식 */
const NO_BOARD_MARK = '(보드 없음)'

vi.mock('./EditSprintDialog', () => ({
  EditSprintDialog: ({
    open,
    sprint,
    projectKey,
    boardId,
  }: {
    open: boolean
    sprint: SprintMeta
    projectKey: string
    boardId: string | undefined
  }): JSX.Element | null =>
    open ? (
      <div role="dialog" aria-label={EDIT_DIALOG_STUB_NAME}>
        <span data-testid="edit-dialog-board">{boardId ?? NO_BOARD_MARK}</span>
        <span data-testid="edit-dialog-sprint">{sprint.sprintId}</span>
        <span data-testid="edit-dialog-project">{projectKey}</span>
      </div>
    ) : null,
}))

import { SprintActionsMenu } from './SprintActionsMenu'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

/** 화면이 보고 있는 보드 — `?board=` 로 들어온 값 (FR-BD-04) */
const BOARD_ID = 'b0000000-0000-4000-8000-00000000000b'

const SPRINT: SprintMeta = {
  sprintId: '11111111-1111-4111-8111-111111111111',
  name: 'Sprint 1',
  goal: null,
  status: 'PLANNED',
  startDate: null,
  endDate: null,
  version: 3,
}

const A = backlogLabels.sprintActions

/** 삭제 엔드포인트 — `server.use` 오버라이드 대상 */
const DELETE_PATH = '/api/v1/sprints/:id'

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 재시도를 끈 QueryClient — 실패 갈래를 재는 테스트가 3회 재시도를 기다리지 않게 한다 */
function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

/** 메뉴 렌더 옵션 */
interface RenderOptions {
  canManage?: boolean
  issueCount?: number
  boardId?: string | undefined
  sprint?: SprintMeta
}

/**
 * `⋯` 메뉴를 그린다.
 *
 * @param opts 권한·이슈 수·보드·스프린트 (전부 기본값 있음)
 */
function renderMenu(opts: RenderOptions = {}) {
  const {
    canManage = true,
    issueCount = 3,
    boardId = BOARD_ID,
    sprint = SPRINT,
  } = opts
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <SprintActionsMenu
        projectKey={PROJECT_KEY}
        sprint={sprint}
        boardId={boardId}
        issueCount={issueCount}
        canManage={canManage}
      />
    </QueryClientProvider>,
  )
}

/** `⋯` 트리거를 눌러 메뉴를 편다 */
async function openMenu(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(await screen.findByRole('button', { name: A.triggerAriaLabel(SPRINT.name) }))
}

/** `⋯` → 「스프린트 삭제」 까지 열고 확인 다이얼로그를 돌려준다 */
async function openDeleteDialog(
  user: ReturnType<typeof userEvent.setup>,
): Promise<HTMLElement> {
  await openMenu(user)
  await user.click(await screen.findByRole('menuitem', { name: backlogLabels.deleteSprint }))
  return await screen.findByRole('dialog', { name: backlogLabels.deleteSprint })
}

// ─────────────────────────────────────────────────────────────────────────────
// M1. 권한 게이팅 — 비활성이 아니라 **부재** (FR-5)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintActionsMenu — M1 권한 게이팅 (FR-5)', () => {
  /**
   * M1-1. 권한이 있으면 두 항목이 **있다.**
   *
   * ★아래 부재 단언의 **비-공허 짝**이다. 이게 없으면 라벨을 오타 내거나 메뉴를 통째로
   *   지워도 부재 단언이 전부 그대로 통과한다.
   */
  it('M1-1: 권한이 있으면 「스프린트 편집」·「스프린트 삭제」가 메뉴에 있다', async () => {
    const user = userEvent.setup()
    renderMenu({ canManage: true })

    await openMenu(user)

    expect(
      await screen.findByRole('menuitem', { name: backlogLabels.editSprint }),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('menuitem', { name: backlogLabels.deleteSprint }),
    ).toBeInTheDocument()
  })

  /**
   * M1-2. 권한이 없으면 두 항목이 **DOM 에 없다.**
   *
   * `toBeDisabled()` 로 재면 「비활성으로 보이지만 마크업에는 있는」 상태를 통과시키게 되고,
   * 그건 FR-5 가 말하는 동작이 아니다 (보드 PR #416 과 같은 규율).
   */
  it('M1-2: 권한이 없으면 편집·삭제 항목이 DOM 에 없다', () => {
    renderMenu({ canManage: false })

    expect(screen.queryByRole('menuitem', { name: backlogLabels.editSprint })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: backlogLabels.deleteSprint })).toBeNull()
  })

  /**
   * M1-3. 권한이 없으면 `⋯` 트리거 자체가 없다.
   *
   * 열면 비는 메뉴는 「권한이 없다」가 아니라 「고장났다」로 읽힌다.
   */
  it('M1-3: 권한이 없으면 `⋯` 트리거도 렌더하지 않는다', () => {
    renderMenu({ canManage: false })

    expect(
      screen.queryByRole('button', { name: A.triggerAriaLabel(SPRINT.name) }),
    ).toBeNull()
  })

  /** M1-4. 트리거 이름은 스프린트마다 다르다 — 같은 화면에 N개가 공존한다 */
  it('M1-4: `⋯` 트리거 이름이 스프린트마다 갈린다', () => {
    expect(A.triggerAriaLabel('A')).not.toBe(A.triggerAriaLabel('B'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// M2. 편집 진입 — 다이얼로그에 무엇이 전달되는가
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintActionsMenu — M2 편집 진입', () => {
  it('M2-1: 「스프린트 편집」을 누르면 편집 다이얼로그가 열린다', async () => {
    const user = userEvent.setup()
    renderMenu()

    expect(screen.queryByRole('dialog', { name: EDIT_DIALOG_STUB_NAME })).toBeNull()

    await openMenu(user)
    await user.click(await screen.findByRole('menuitem', { name: backlogLabels.editSprint }))

    expect(
      await screen.findByRole('dialog', { name: EDIT_DIALOG_STUB_NAME }),
    ).toBeInTheDocument()
  })

  /**
   * M2-2. **`boardId` 를 그대로 넘긴다.**
   *
   * 409 복구가 보드 스코프 캐시를 **완전 일치** 키로 읽으므로, 여기서 빠뜨리면 재시도가
   * 409 를 되풀이한다. 화면은 똑같아 보이므로 이 단언 말고는 관측 지점이 없다.
   */
  it('M2-2: 편집 다이얼로그에 보고 있는 boardId 를 넘긴다', async () => {
    const user = userEvent.setup()
    renderMenu({ boardId: BOARD_ID })

    await openMenu(user)
    await user.click(await screen.findByRole('menuitem', { name: backlogLabels.editSprint }))

    expect(await screen.findByTestId('edit-dialog-board')).toHaveTextContent(BOARD_ID)
    expect(screen.getByTestId('edit-dialog-sprint')).toHaveTextContent(SPRINT.sprintId)
    expect(screen.getByTestId('edit-dialog-project')).toHaveTextContent(PROJECT_KEY)
  })

  /** M2-3. `?board=` 가 없으면 `undefined` 가 그대로 간다 — 기본 보드는 **서버**가 고른다 */
  it('M2-3: boardId 가 undefined 면 undefined 그대로 넘긴다', async () => {
    const user = userEvent.setup()
    renderMenu({ boardId: undefined })

    await openMenu(user)
    await user.click(await screen.findByRole('menuitem', { name: backlogLabels.editSprint }))

    expect(await screen.findByTestId('edit-dialog-board')).toHaveTextContent(NO_BOARD_MARK)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// M3. 삭제 확인 문구 — 이슈 개수로 갈린다 (Sanity ❓3 · E-5)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintActionsMenu — M3 삭제 확인 문구', () => {
  /**
   * M3-1. 이슈가 1건 이상이면 **어디로 가는지** 말한다.
   *
   * 문구 리터럴을 여기 적는 것이 요점이다 — 라벨 함수로 단언하면 함수가 무엇을 반환하든
   * 통과하는 동어반복이 된다.
   */
  it('M3-1: 이슈 3건이면 「이슈 3건은 삭제되지 않고 백로그로 돌아갑니다」를 보여준다', async () => {
    const user = userEvent.setup()
    renderMenu({ issueCount: 3 })

    const dialog = await openDeleteDialog(user)

    expect(dialog).toHaveTextContent('이슈 3건은 삭제되지 않고 백로그로 돌아갑니다')
  })

  /**
   * M3-2. 이슈가 0건이면 그 문장이 **아예 없다.**
   *
   * 옮길 것이 없는데 옮긴다고 적으면 문구가 거짓이 된다 (Sanity ❓3).
   */
  it('M3-2: 이슈 0건이면 「백로그로 돌아갑니다」 문장이 없다', async () => {
    const user = userEvent.setup()
    renderMenu({ issueCount: 0 })

    const dialog = await openDeleteDialog(user)

    // 짝 단언 — 창은 열려 있고 대상 이름은 말한다. 「창이 안 열려서 없다」와 구별한다.
    expect(dialog).toHaveTextContent(SPRINT.name)
    expect(dialog).not.toHaveTextContent('백로그로 돌아갑니다')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// M4. 삭제 실행 — 성공은 닫고, 실패는 **열린 채** 사유를 창 안에 남긴다 (E-3)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintActionsMenu — M4 삭제 실행', () => {
  it('M4-1: 삭제에 성공하면 확인 창이 닫힌다', async () => {
    const user = userEvent.setup()
    server.use(http.delete(DELETE_PATH, () => new HttpResponse(null, { status: 204 })))
    renderMenu()

    const dialog = await openDeleteDialog(user)
    await user.click(within(dialog).getByRole('button', { name: A.deleteConfirm }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: backlogLabels.deleteSprint })).toBeNull()
    })
  })

  it('M4-2: 403 이면 창이 열린 채 권한 사유를 창 안에 보여준다', async () => {
    const user = userEvent.setup()
    server.use(
      http.delete(DELETE_PATH, () =>
        HttpResponse.json({ errorCode: 'AGILE_ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    renderMenu()

    const dialog = await openDeleteDialog(user)
    await user.click(within(dialog).getByRole('button', { name: A.deleteConfirm }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(A.deleteForbidden)
    expect(
      screen.getByRole('dialog', { name: backlogLabels.deleteSprint }),
    ).toBeInTheDocument()
  })

  it('M4-3: 404 이면 「이미 지워졌을 수 있다」를 창 안에 보여준다', async () => {
    const user = userEvent.setup()
    server.use(
      http.delete(DELETE_PATH, () =>
        HttpResponse.json({ errorCode: 'AGILE_SPRINT_NOT_FOUND' }, { status: 404 }),
      ),
    )
    renderMenu()

    const dialog = await openDeleteDialog(user)
    await user.click(within(dialog).getByRole('button', { name: A.deleteConfirm }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(A.deleteNotFound)
  })

  /**
   * M4-4. **상태 코드가 없는 실패** — 연결이 끊겼거나 상한이 요청을 끊었다.
   *
   * `DeleteTimeoutError`(`lib/delete-timeout.ts`)도 `ApiError` 가 아니므로 같은 갈래로
   * 온다. 사용자가 할 다음 행동이 같기 때문에 두 경우를 한 문구로 묶는다.
   */
  it('M4-4: 상태 코드 없는 실패는 「서버 응답이 없습니다」로 안내한다', async () => {
    const user = userEvent.setup()
    server.use(http.delete(DELETE_PATH, () => HttpResponse.error()))
    renderMenu()

    const dialog = await openDeleteDialog(user)
    await user.click(within(dialog).getByRole('button', { name: A.deleteConfirm }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(A.noResponse)
  })

  /**
   * M4-5. 실패한 창을 닫았다 다시 열면 **지난 실패가 없다.**
   *
   * 창의 수명과 실패 상태의 수명이 같아야 한다 — 남아 있으면 다른 스프린트를 지우려고 연
   * 창에 앞 실패가 되살아난다.
   */
  it('M4-5: 실패 뒤 창을 닫았다 다시 열면 지난 사유가 남지 않는다', async () => {
    const user = userEvent.setup()
    server.use(http.delete(DELETE_PATH, () => HttpResponse.error()))
    renderMenu()

    const dialog = await openDeleteDialog(user)
    await user.click(within(dialog).getByRole('button', { name: A.deleteConfirm }))
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: A.deleteCancel }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: backlogLabels.deleteSprint })).toBeNull()
    })

    const reopened = await openDeleteDialog(user)
    expect(within(reopened).queryByRole('alert')).toBeNull()
  })
})
