// ComponentFormDialog 단위 테스트 — 생성/수정 모드, 이름 검증, prefill, submitError 표시
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import type { Component } from '@/api/components.types'
import { ComponentFormDialog } from './ComponentFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_UUID = '00000000-0000-4000-8000-000000000001'
const PROJECT_UUID = '00000000-0000-4000-8000-000000000010'
const COMPONENT_UUID = '00000000-0000-4000-8000-000000000020'

const ALICE_USER = {
  id: ALICE_UUID,
  username: 'alice',
  displayName: '앨리스',
  email: 'alice@example.com',
}

const EXISTING_COMPONENT: Component = {
  id: COMPONENT_UUID,
  projectId: PROJECT_UUID,
  name: '기존 컴포넌트',
  description: '기존 설명',
  leadUserId: ALICE_UUID,
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentFormDialog — 생성 모드', () => {
  it('open=true 시 다이얼로그가 렌더된다', () => {
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="create"
        onSubmit={vi.fn()}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('생성 모드에서 이름 필드가 빈 상태로 렌더된다', () => {
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="create"
        onSubmit={vi.fn()}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    expect(nameInput).toHaveValue('')
  })

  it('이름을 비운 채 저장 시 onSubmit이 호출되지 않고 오류 메시지가 표시된다', async () => {
    const handleSubmit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="create"
        onSubmit={handleSubmit}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const saveButton = screen.getByRole('button', { name: '저장' })
    await user.click(saveButton)

    expect(handleSubmit).not.toHaveBeenCalled()
    // 이름 필수 검증 오류 표시 — role="alert" 로 정확히 탐색
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  it('이름을 입력하고 저장 시 onSubmit이 올바른 payload로 호출된다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json([])),
    )
    const handleSubmit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="create"
        onSubmit={handleSubmit}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.type(nameInput, '새 컴포넌트')

    const saveButton = screen.getByRole('button', { name: '저장' })
    await user.click(saveButton)

    await waitFor(() => {
      expect(handleSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ name: '새 컴포넌트' }),
      )
    })
  })
})

describe('ComponentFormDialog — 수정 모드', () => {
  it('수정 모드에서 initial 값이 폼에 prefill된다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json([ALICE_USER])),
    )

    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="edit"
        initial={EXISTING_COMPONENT}
        onSubmit={vi.fn()}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    expect(nameInput).toHaveValue('기존 컴포넌트')

    const descInput = screen.getByRole('textbox', { name: /설명/ })
    expect(descInput).toHaveValue('기존 설명')
  })

  it('수정 모드에서 저장 시 onSubmit이 현재 폼 값으로 호출된다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json([])),
    )
    const handleSubmit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="edit"
        initial={EXISTING_COMPONENT}
        onSubmit={handleSubmit}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()

    // 이름 변경
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, '수정된 컴포넌트')

    const saveButton = screen.getByRole('button', { name: '저장' })
    await user.click(saveButton)

    await waitFor(() => {
      expect(handleSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ name: '수정된 컴포넌트' }),
      )
    })
  })

  it('수정 모드에서 설명을 비우면 빈 문자열을 전송한다 (설명 비우기 허용)', async () => {
    const handleSubmit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="edit"
        initial={EXISTING_COMPONENT}
        onSubmit={handleSubmit}
        onOpenChange={vi.fn()}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()

    // 기존 설명('기존 설명')을 지운다
    const descriptionInput = screen.getByRole('textbox', { name: /설명/ })
    await user.clear(descriptionInput)

    const saveButton = screen.getByRole('button', { name: '저장' })
    await user.click(saveButton)

    // null/undefined가 아니라 ''를 보내야 백엔드가 설명을 실제로 비운다
    await waitFor(() => {
      expect(handleSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ description: '' }),
      )
    })
  })
})

describe('ComponentFormDialog — submitError 표시', () => {
  it('submitError prop이 있으면 폼 내 에러 메시지를 표시한다', () => {
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="create"
        onSubmit={vi.fn()}
        onOpenChange={vi.fn()}
        submitError="이미 같은 이름의 컴포넌트가 있습니다."
      />,
      { wrapper: Wrapper },
    )

    expect(screen.getByText('이미 같은 이름의 컴포넌트가 있습니다.')).toBeInTheDocument()
  })

  it('submitError가 있어도 Dialog가 열린 채로 유지된다', () => {
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="create"
        onSubmit={vi.fn()}
        onOpenChange={vi.fn()}
        submitError="이미 같은 이름의 컴포넌트가 있습니다."
      />,
      { wrapper: Wrapper },
    )

    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('submitError가 null이면 에러 메시지를 표시하지 않는다', () => {
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="create"
        onSubmit={vi.fn()}
        onOpenChange={vi.fn()}
        submitError={null}
      />,
      { wrapper: Wrapper },
    )

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('ComponentFormDialog — 취소 버튼', () => {
  it('취소 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
    const handleOpenChange = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentFormDialog
        open={true}
        mode="create"
        onSubmit={vi.fn()}
        onOpenChange={handleOpenChange}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const cancelButton = screen.getByRole('button', { name: '취소' })
    await user.click(cancelButton)

    expect(handleOpenChange).toHaveBeenCalledWith(false)
  })
})
