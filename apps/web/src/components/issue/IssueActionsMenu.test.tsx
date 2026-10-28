// 이슈 작업 메뉴 테스트 — 키 복사 · 우선순위/담당자 변경 요청 · 화면이 준 이동 항목 · 409 안내 (JB-B-2 · JCB-17)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import type { JSX } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import type { WhoamiResponse } from '@/api/schemas'
import { issueDetailStrings } from '@/i18n/ko'
import { IssueActionsMenu } from './IssueActionsMenu'
import { IssueMoveActionsContext } from './issue-move-actions-context'
import type { IssueMoveActionsResolver } from './issue-move-actions-context'

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('sonner', () => ({ toast: toastMock }))

const ME = '00000000-0000-0000-0000-0000000000aa'

function Harness({ resolver = null, assigneeId = null }: {
  resolver?: IssueMoveActionsResolver | null
  assigneeId?: string | null
}): JSX.Element {
  const [open, setOpen] = useState(false)
  return (
    <IssueMoveActionsContext.Provider value={resolver}>
      <IssueActionsMenu
        issueKey="ATLAS-7"
        version={3}
        priority={3}
        assigneeId={assigneeId}
        open={open}
        onOpenChange={setOpen}
      />
    </IssueMoveActionsContext.Provider>
  )
}

function renderMenu(props: Parameters<typeof Harness>[0] = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Harness {...props} />
    </QueryClientProvider>,
  )
}

/**
 * 하위 메뉴 항목을 키보드로 고른다.
 *
 * jsdom 은 요소 크기가 0 이라 Radix 하위 메뉴의 「포인터 유예 영역」이 비어, 항목으로 포인터를
 * 옮기는 순간 하위 메뉴가 닫힌다. 그래서 하위 메뉴는 →(열기) · ↓(이동) · Enter(선택)로 다룬다.
 */
async function pickInSub(user: ReturnType<typeof userEvent.setup>, sub: string, item: string) {
  screen.getByRole('menuitem', { name: sub }).focus()
  await user.keyboard('{ArrowRight}')
  for (let i = 0; i < 10 && document.activeElement?.textContent !== item; i++) {
    await user.keyboard('{ArrowDown}')
  }
  expect(document.activeElement).toHaveTextContent(item)
  await user.keyboard('{Enter}')
}

async function openMenu() {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'ATLAS-7 작업' }))
  return user
}

beforeEach(() => {
  toastMock.success.mockClear()
  toastMock.error.mockClear()
  useAuthStore.setState({ user: { userId: ME } as WhoamiResponse })
})

describe('IssueActionsMenu', () => {
  it('키 복사 — 클립보드에 이슈 키를 쓴다', async () => {
    renderMenu()
    const user = await openMenu()
    await user.click(screen.getByRole('menuitem', { name: '키 복사' }))
    await expect(navigator.clipboard.readText()).resolves.toBe('ATLAS-7')
    expect(toastMock.success).toHaveBeenCalledWith('ATLAS-7 키를 복사했습니다.')
  })

  it('우선순위 ▸ 가장 높음 — PATCH 에 priority 1 과 현재 version 을 싣는다', async () => {
    let body: unknown
    server.use(
      http.patch('/api/v1/issues/ATLAS-7', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({}, { status: 500 })
      }),
    )
    renderMenu()
    const user = await openMenu()
    await pickInSub(user, '우선순위', '가장 높음')
    await waitFor(() => expect(body).toEqual({ priority: 1, expectedVersion: 3 }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(issueDetailStrings.priorityChangeError))
  })

  it('담당자 ▸ 나에게 할당 — 409 면 버전 충돌 안내', async () => {
    let body: unknown
    server.use(
      http.patch('/api/v1/issues/ATLAS-7/assignee', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ error: { code: 'VERSION_CONFLICT' } }, { status: 409 })
      }),
    )
    renderMenu()
    const user = await openMenu()
    await pickInSub(user, '담당자', '나에게 할당')
    await waitFor(() => expect(body).toEqual({ assigneeId: ME, expectedVersion: 3 }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(issueDetailStrings.versionConflictError))
  })

  it('화면이 준 이동 항목을 이동 ▸ 아래에 그리고 고르면 부른다', async () => {
    const onTop = vi.fn()
    renderMenu({
      resolver: (key) => [
        { label: `${key} 맨 위로`, onSelect: onTop },
        { label: '위로', disabled: true, onSelect: vi.fn() },
      ],
    })
    const user = await openMenu()
    await pickInSub(user, '이동', 'ATLAS-7 맨 위로')
    expect(screen.queryByRole('menuitem', { name: '이동' })).toBeNull()
    expect(onTop).toHaveBeenCalledOnce()
  })

  it('미배정 이슈는 할당 해제가 · 비활성 이동 항목은 그대로 비활성이다', async () => {
    renderMenu({ resolver: () => [{ label: '위로', disabled: true, onSelect: vi.fn() }] })
    const user = await openMenu()
    screen.getByRole('menuitem', { name: '담당자' }).focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('menuitem', { name: '할당 해제' })).toHaveAttribute('aria-disabled', 'true')
    await user.keyboard('{ArrowLeft}')
    screen.getByRole('menuitem', { name: '이동' }).focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('menuitem', { name: '위로' })).toHaveAttribute('aria-disabled', 'true')
  })

  it('이동을 주는 화면이 없으면 이동 항목이 없다', async () => {
    renderMenu()
    await openMenu()
    expect(screen.queryByRole('menuitem', { name: '이동' })).toBeNull()
    expect(screen.getByRole('menuitem', { name: '링크 복사' })).toBeInTheDocument()
  })
})
