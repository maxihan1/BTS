// LoginPage 랜드마크 테스트 — 폼과 라우팅 우선순위는 LoginDialog.test.tsx 가 소유한다
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LoginPage } from './login'

describe('LoginPage — main 랜드마크 (C3, _shell 밖이라 자체 main 필요)', () => {
  it('main 랜드마크가 정확히 1개 존재한다', () => {
    render(<LoginPage />)

    expect(screen.getAllByRole('main')).toHaveLength(1)
  })

  it('배경만 렌더하고 로그인 폼을 소유하지 않는다', () => {
    // 폼은 RootLayout 에 마운트된 전역 LoginDialog 가 그린다. 이 페이지가 폼을 또 렌더하면
    // 같은 이름의 필드가 둘이 되어 e2e strict mode 가 깨진다.
    render(<LoginPage />)

    expect(screen.queryByLabelText('비밀번호')).toBeNull()
    expect(screen.queryByRole('button', { name: '로그인' })).toBeNull()
  })
})
