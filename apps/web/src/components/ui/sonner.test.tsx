// sonner Toaster 래퍼 — 렌더 및 접근성 노출 검증
import { render, screen, act } from '@testing-library/react'
import { toast } from 'sonner'
import { describe, it, expect } from 'vitest'
import { Toaster } from './sonner'

describe('Toaster', () => {
  it('<Toaster />가 DOM에 마운트된다', () => {
    render(<Toaster />)
    // sonner는 data-sonner-toaster 속성을 가진 ol 요소를 렌더한다
    expect(document.querySelector('[data-sonner-toaster]')).not.toBeNull()
  })

  it('toast.error(msg) 호출 시 메시지가 접근성 영역으로 노출된다', async () => {
    render(<Toaster />)

    await act(async () => {
      toast.error('서버 오류가 발생했습니다')
    })

    // sonner는 role="status" 또는 role="alert" 영역에 토스트를 삽입한다
    const alert = await screen.findByRole('status')
    expect(alert).toBeInTheDocument()
  })
})
