// 비밀번호 변경 폼 컴포넌트 테스트 — MSW + QueryClientProvider wrapper
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
import { server } from '@/test/server'
import { passwordHandlers } from '@/mocks/password-handlers'
import { ChangePasswordForm } from './ChangePasswordForm'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처 상수
// ─────────────────────────────────────────────────────────────────────────────

/** MSW seed 현재 비밀번호 — password-handlers.ts SEED_CURRENT_PASSWORD와 동일 */
const SEED_CURRENT_PASSWORD = 'CurrentPass123!'

/** 정책을 만족하는 새 비밀번호(12자+, 대문자·소문자·숫자·특수 3종 이상) */
const VALID_NEW_PASSWORD = 'NewPass4567!@'

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient 래퍼 — 재시도 없이 에러를 즉시 노출
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — 폼 렌더 + 필드 접근
// ─────────────────────────────────────────────────────────────────────────────

function renderForm() {
  const Wrapper = createWrapper()
  render(<ChangePasswordForm />, { wrapper: Wrapper })
}

async function fillAndSubmit(
  currentPassword: string,
  newPassword: string,
  confirmPassword: string,
) {
  // delay:null — 긴 비번 타이핑 timeout 방지 (vitest-usertype-long-string-timeout 선례)
  // setup({ delay: null }) 방식 사용 — user.type 세 번째 인자 delay 는 타입 미지원
  const user = userEvent.setup({ delay: null })
  await user.type(screen.getByLabelText('현재 비밀번호'), currentPassword)
  await user.type(screen.getByLabelText('새 비밀번호'), newPassword)
  await user.type(screen.getByLabelText('새 비밀번호 확인'), confirmPassword)
  // exact 는 getByRole ByRoleOptions 에 없음 — 정확한 이름 문자열로 매칭
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 정상 변경 성공
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — S1 정상 변경', () => {
  it('성공 시 "비밀번호가 변경되었습니다" 메시지와 세션 안내를 표시한다', async () => {
    renderForm()

    await fillAndSubmit(SEED_CURRENT_PASSWORD, VALID_NEW_PASSWORD, VALID_NEW_PASSWORD)

    await waitFor(() => {
      expect(screen.getByText('비밀번호가 변경되었습니다')).toBeInTheDocument()
    })
    expect(screen.getByText('다른 기기의 세션은 로그아웃되었습니다')).toBeInTheDocument()
  })

  it('성공 후 3 입력 필드가 초기화된다', async () => {
    renderForm()

    await fillAndSubmit(SEED_CURRENT_PASSWORD, VALID_NEW_PASSWORD, VALID_NEW_PASSWORD)

    await waitFor(() => {
      expect(screen.getByText('비밀번호가 변경되었습니다')).toBeInTheDocument()
    })

    const currentInput = screen.getByLabelText('현재 비밀번호') as HTMLInputElement
    const newInput = screen.getByLabelText('새 비밀번호') as HTMLInputElement
    const confirmInput = screen.getByLabelText('새 비밀번호 확인') as HTMLInputElement

    expect(currentInput.value).toBe('')
    expect(newInput.value).toBe('')
    expect(confirmInput.value).toBe('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 현재 비밀번호 불일치
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — S2 현재 비번 불일치', () => {
  it('CURRENT_PASSWORD_MISMATCH 에러 시 한글 메시지를 표시한다', async () => {
    renderForm()

    await fillAndSubmit('wrongPassword123!', VALID_NEW_PASSWORD, VALID_NEW_PASSWORD)

    await waitFor(() => {
      expect(screen.getByText('현재 비밀번호가 일치하지 않습니다.')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — POLICY_VIOLATION + MIN_LENGTH
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — S3 정책 위반(길이)', () => {
  it('MIN_LENGTH violation 시 "최소 12자" 메시지를 표시한다', async () => {
    renderForm()

    // 새 비번 4자(길이 위반, 복잡도 위반) — MSW는 POLICY_VIOLATION violations=[MIN_LENGTH, COMPLEXITY] 반환
    await fillAndSubmit(SEED_CURRENT_PASSWORD, 'Ab1!', 'Ab1!')

    await waitFor(() => {
      expect(screen.getByText('비밀번호는 최소 12자 이상이어야 합니다.')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — POLICY_VIOLATION + COMPLEXITY
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — S4 정책 위반(복잡도)', () => {
  it('COMPLEXITY violation 시 "3종 이상" 메시지를 표시한다', async () => {
    // 길이는 만족(12자), 복잡도만 위반(소문자만)
    server.use(
      http.post('/api/v1/users/me/password', () =>
        HttpResponse.json(
          {
            code: 'POLICY_VIOLATION',
            message: '비밀번호가 정책을 위반합니다.',
            violations: ['COMPLEXITY'],
          },
          { status: 400 },
        ),
      ),
    )

    renderForm()

    await fillAndSubmit(SEED_CURRENT_PASSWORD, 'aaaaaaaaaaaa', 'aaaaaaaaaaaa')

    await waitFor(() => {
      expect(
        screen.getByText('영문 대문자·소문자·숫자·특수문자 중 3종 이상을 포함해야 합니다.'),
      ).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — SAME_AS_CURRENT
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — S5 현재와 동일한 비밀번호', () => {
  it('SAME_AS_CURRENT 에러 시 한글 메시지를 표시한다', async () => {
    renderForm()

    // 새 비번 = 현재 비번 = seed → MSW SAME_AS_CURRENT 반환
    await fillAndSubmit(SEED_CURRENT_PASSWORD, SEED_CURRENT_PASSWORD, SEED_CURRENT_PASSWORD)

    await waitFor(() => {
      expect(screen.getByText('새 비밀번호가 현재 비밀번호와 같습니다.')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — 클라이언트 차단: 새 비번 ≠ 확인 비번
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — S6 새 비번 ≠ 확인 비번', () => {
  it('불일치 시 "새 비밀번호가 일치하지 않습니다." 메시지를 표시한다', async () => {
    // API 호출 여부 추적 — 클라이언트 차단이면 호출 안 됨
    let apiCalled = false
    server.use(
      http.post('/api/v1/users/me/password', () => {
        apiCalled = true
        return HttpResponse.json({ changed: true })
      }),
    )

    renderForm()

    await fillAndSubmit(SEED_CURRENT_PASSWORD, VALID_NEW_PASSWORD, 'DifferentPass9#')

    await waitFor(() => {
      expect(screen.getByText('새 비밀번호가 일치하지 않습니다.')).toBeInTheDocument()
    })

    // API가 호출되지 않았음을 검증
    expect(apiCalled).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// EC-1 — 빈 필드 제출
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — EC-1 빈 필드 제출', () => {
  it('빈 필드 제출 시 API가 호출되지 않는다', async () => {
    let apiCalled = false
    server.use(
      http.post('/api/v1/users/me/password', () => {
        apiCalled = true
        return HttpResponse.json({ changed: true })
      }),
    )

    renderForm()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))

    // 잠깐 기다린 후 API 미호출 확인
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(apiCalled).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// EC-7 — isPending 중 버튼 비활성
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — EC-7 isPending 중 버튼 비활성', () => {
  it('제출 진행 중에 버튼이 disabled 상태이다', async () => {
    // 응답을 지연시켜 isPending 상태를 관찰
    server.use(
      http.post('/api/v1/users/me/password', async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ changed: true })
      }),
    )

    renderForm()

    const user = userEvent.setup({ delay: null })
    await user.type(screen.getByLabelText('현재 비밀번호'), SEED_CURRENT_PASSWORD)
    await user.type(screen.getByLabelText('새 비밀번호'), VALID_NEW_PASSWORD)
    await user.type(screen.getByLabelText('새 비밀번호 확인'), VALID_NEW_PASSWORD)
    await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))

    // 즉시 확인 — 응답 지연 중이므로 버튼이 disabled여야 함
    // isPending 시 버튼 텍스트가 "변경 중..."으로 바뀌므로 type=submit 으로 조회
    const submitButton = document.querySelector('button[type="submit"]')
    expect(submitButton).toBeDisabled()

    await settlePendingMutations()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 정적 정책 안내문 항상 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('ChangePasswordForm — 정적 정책 안내문', () => {
  it('폼 렌더 시 정책 안내문이 항상 표시된다', () => {
    renderForm()

    expect(screen.getByText(/12자 이상/)).toBeInTheDocument()
    expect(screen.getByText(/3종 이상/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 등록 및 정리
// test/server.ts 는 test/handlers.ts (빈 배열)를 초기 핸들러로 사용하므로
// passwordHandlers 를 각 테스트 전에 server.use() 로 명시 등록한다.
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  // 기본 password 핸들러 등록 — S4 등에서 server.use() 오버라이드 전 기본값
  server.use(...passwordHandlers)
})

afterEach(() => {
  server.resetHandlers()
})
