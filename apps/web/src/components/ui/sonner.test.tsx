// sonner Toaster 래퍼 — 렌더 및 접근성 노출 검증
import { render, screen, act, waitFor } from '@testing-library/react'
import { toast } from 'sonner'
import { describe, it, expect } from 'vitest'
import { Toaster } from './sonner'

describe('Toaster', () => {
  it('<Toaster />가 DOM에 마운트된다', () => {
    render(<Toaster />)
    // sonner v2는 aria-live="polite" section을 document.body에 포탈로 삽입한다
    expect(document.querySelector('[aria-live="polite"]')).not.toBeNull()
  })

  it('toast.error(msg) 호출 시 aria-live 영역에 메시지가 노출된다', async () => {
    render(<Toaster />)

    await act(async () => {
      toast.error('서버 오류가 발생했습니다')
    })

    // sonner v2는 토스트 아이템을 aria-live 영역 안 ol > li 구조로 삽입한다
    // waitFor로 비동기 DOM 삽입을 기다린다
    await waitFor(() => {
      expect(screen.getByText('서버 오류가 발생했습니다')).toBeInTheDocument()
    })
  })
})
