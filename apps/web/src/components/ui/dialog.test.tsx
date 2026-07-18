// Dialog 프리미티브 단위 테스트 — role="dialog" 노출 계약(e2e 147건 의존) + 제목 연결 + 닫기 동작
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogClose,
  DialogHeader,
  DialogFooter,
  DialogTitle,
  DialogDescription,
} from './dialog'

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: 열린 Dialog는 role="dialog"를 노출한다 (★핵심 계약 — e2e 147건 의존)
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-1: role="dialog" 노출 (핵심 계약)', () => {
  it('open 상태에서 role="dialog"가 노출된다', () => {
    render(
      <Dialog open>
        <DialogContent>
          <DialogTitle>제목텍스트</DialogTitle>
          <DialogDescription>설명</DialogDescription>
        </DialogContent>
      </Dialog>,
    )

    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('트리거 클릭으로 열어도 role="dialog"가 노출된다', async () => {
    const user = userEvent.setup()
    render(
      <Dialog>
        <DialogTrigger>열기</DialogTrigger>
        <DialogContent>
          <DialogTitle>제목텍스트</DialogTitle>
          <DialogDescription>설명</DialogDescription>
        </DialogContent>
      </Dialog>,
    )

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '열기' }))

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-2: DialogTitle이 accessible name으로 연결된다
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-2: DialogTitle accessible name 연결', () => {
  it('dialog의 accessible name이 DialogTitle 텍스트와 일치한다', () => {
    render(
      <Dialog open>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>제목텍스트</DialogTitle>
            <DialogDescription>설명텍스트</DialogDescription>
          </DialogHeader>
        </DialogContent>
      </Dialog>,
    )

    expect(screen.getByRole('dialog', { name: '제목텍스트' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-3: DialogClose 클릭 시 닫힌다
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-3: DialogClose 클릭 시 닫힘', () => {
  it('내장 close 버튼 클릭 시 dialog가 사라진다', async () => {
    const user = userEvent.setup()
    render(
      <Dialog defaultOpen>
        <DialogContent>
          <DialogTitle>제목텍스트</DialogTitle>
          <DialogDescription>설명</DialogDescription>
        </DialogContent>
      </Dialog>,
    )

    expect(screen.getByRole('dialog')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /close/i }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('커스텀 DialogClose 클릭 시 dialog가 사라진다', async () => {
    const user = userEvent.setup()
    render(
      <Dialog defaultOpen>
        <DialogContent>
          <DialogTitle>제목텍스트</DialogTitle>
          <DialogDescription>설명</DialogDescription>
          <DialogFooter>
            <DialogClose>취소</DialogClose>
          </DialogFooter>
        </DialogContent>
      </Dialog>,
    )

    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
