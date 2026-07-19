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

// ─────────────────────────────────────────────────────────────────────────────
// TC-4: 애니메이션 variant 문법 회귀 가드 (FR-UX-06 PR5 R1)
//   래퍼는 `data-open:`/`data-closed:` variant를 쓴다. 이는 shadcn/tailwind.css의
//   `@custom-variant data-open { &:where([data-state="open"]) ... }`가 Radix의
//   `data-state="open/closed"`로 컴파일해주기 때문에 정상 동작한다(dropdown/popover/
//   select/tooltip 4종이 이미 공유하는 컨벤션). 누가 `data-[state=open]:`으로 바꾸면
//   4종과 표기가 갈리므로, 이 가드로 원 문법 유지를 강제한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-4: 애니메이션 variant 문법 회귀 가드', () => {
  it('DialogContent가 data-open/data-closed 애니메이션 variant를 유지한다', () => {
    render(
      <Dialog open>
        <DialogContent>
          <DialogTitle>제목텍스트</DialogTitle>
          <DialogDescription>설명</DialogDescription>
        </DialogContent>
      </Dialog>,
    )

    const content = document.querySelector('[data-slot="dialog-content"]')
    expect(content).not.toBeNull()
    expect(content?.className).toContain('data-open:animate-in')
    expect(content?.className).toContain('data-closed:animate-out')
  })

  it('DialogOverlay가 data-open/data-closed 애니메이션 variant를 유지한다', () => {
    render(
      <Dialog open>
        <DialogContent>
          <DialogTitle>제목텍스트</DialogTitle>
          <DialogDescription>설명</DialogDescription>
        </DialogContent>
      </Dialog>,
    )

    const overlay = document.querySelector('[data-slot="dialog-overlay"]')
    expect(overlay).not.toBeNull()
    expect(overlay?.className).toContain('data-open:animate-in')
    expect(overlay?.className).toContain('data-closed:animate-out')
  })
})
