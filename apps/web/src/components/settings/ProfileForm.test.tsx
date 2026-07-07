// ProfileForm 컴포넌트 테스트 — 프리필·3-state 저장·아바타 업로드/삭제·에러/성공 표시 (FR-PR-01 D6 Task 7)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { profileHandlers, resetProfileStore } from '@/mocks/profile-handlers'
import { authHandlers } from '@/mocks/auth-handlers'
import { ALICE_PROFILE_FIXTURE, BOB_PROFILE_FIXTURE } from '@/mocks/profile-fixtures'
import { aliceUser, bobUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { profileLabels } from '@/i18n/profile-labels'
import type { ProfileResponse } from '@/api/profile'

// ─────────────────────────────────────────────────────────────────────────────
// Avatar 컴포넌트 mock — blob fetch/objectURL 내부 동작은 avatar.test.tsx가 이미 검증
// (jsdom URL.createObjectURL 미구현 회피 + ProfileForm 자체 로직에 테스트 집중)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/ui/avatar', () => ({
  Avatar: ({
    avatarUrl,
    displayName,
    cacheBust,
  }: {
    avatarUrl?: string | null
    displayName?: string | null
    cacheBust?: number
  }) => (
    <div
      data-testid="avatar-mock"
      data-avatar-url={avatarUrl ?? ''}
      data-cache-bust={cacheBust ?? ''}
    >
      {displayName}
    </div>
  ),
}))

// 대상 import — mock 이후 (vi.mock hoisting)
import { ProfileForm } from './ProfileForm'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

function renderForm() {
  const { wrapper } = createWrapper()
  return render(<ProfileForm />, { wrapper })
}

/**
 * ALICE_PROFILE_FIXTURE 기반 응답 병합 헬퍼 — avatarUrl은 avatarObjectKey null 상태이므로 null 고정.
 * displayNameSource/ldapLinked 기본값은 alice 픽스처(LDAP 연결 + 동기화 상태)와 동일(FR-PR-04).
 */
function buildAliceResponse(overrides: Partial<ProfileResponse> = {}): ProfileResponse {
  return {
    userId: ALICE_PROFILE_FIXTURE.userId,
    username: ALICE_PROFILE_FIXTURE.username,
    email: ALICE_PROFILE_FIXTURE.email,
    displayName: ALICE_PROFILE_FIXTURE.displayName,
    avatarUrl: null,
    timezone: ALICE_PROFILE_FIXTURE.timezone,
    department: ALICE_PROFILE_FIXTURE.department,
    displayNameSource: 'LDAP',
    ldapLinked: true,
    ...overrides,
  }
}

const ALICE_TOKEN = mockAccessToken('alice')
const BOB_TOKEN = mockAccessToken('bob')

beforeEach(() => {
  resetProfileStore()
  server.use(...profileHandlers, ...authHandlers)
  useAuthStore.getState().setSession({ accessToken: ALICE_TOKEN, user: aliceUser })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────────────────────────────────────
// 로딩/에러
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileForm — 로딩/에러', () => {
  it('로딩 중에는 로딩 텍스트를 표시한다', () => {
    renderForm()
    expect(screen.getByText(profileLabels.status.loading)).toBeInTheDocument()
  })

  it('조회가 실패하면 role=alert로 에러 텍스트를 표시한다', async () => {
    server.use(
      http.get('/api/v1/users/me/profile', () => new HttpResponse(null, { status: 500 })),
    )
    renderForm()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(profileLabels.status.loadError)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 프리필
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileForm — S1 프리필', () => {
  it('조회 성공 시 username/email(읽기전용)·displayName·timezone·department가 프리필된다', async () => {
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.displayNameLabel)).toHaveValue(
        ALICE_PROFILE_FIXTURE.displayName,
      )
    })

    const usernameInput = screen.getByLabelText(profileLabels.form.usernameLabel)
    expect(usernameInput).toHaveValue(ALICE_PROFILE_FIXTURE.username)
    expect(usernameInput).toHaveAttribute('readonly')

    expect(screen.getByLabelText(profileLabels.form.emailLabel)).toHaveValue(
      ALICE_PROFILE_FIXTURE.email,
    )
    expect(screen.getByLabelText(profileLabels.form.timezoneLabel)).toHaveValue(
      ALICE_PROFILE_FIXTURE.timezone,
    )
    expect(screen.getByLabelText(profileLabels.form.departmentLabel)).toHaveValue(
      ALICE_PROFILE_FIXTURE.department,
    )
  })

  it('timezone select 옵션에 Intl.supportedValuesOf 목록이 포함된다', async () => {
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.timezoneLabel)).toBeInTheDocument()
    })
    const select = screen.getByLabelText(profileLabels.form.timezoneLabel) as HTMLSelectElement
    const values = Array.from(select.options).map((o) => o.value)
    expect(values).toContain('America/New_York')
    expect(values.length).toBeGreaterThan(400)
  })

  it('EC3 — 서버 timezone 값이 Intl 목록에 없으면 옵션으로 추가된다(bob=UTC)', async () => {
    useAuthStore.getState().setSession({ accessToken: BOB_TOKEN, user: bobUser })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.timezoneLabel)).toHaveValue(
        BOB_PROFILE_FIXTURE.timezone,
      )
    })
    const select = screen.getByLabelText(profileLabels.form.timezoneLabel) as HTMLSelectElement
    const values = Array.from(select.options).map((o) => o.value)
    expect(values.filter((v) => v === 'UTC')).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2/F4/EC2 — 3-state 저장
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileForm — S2 저장(변경 필드만 PATCH)', () => {
  it('displayName/timezone만 변경하면 두 필드만 body에 담겨 전송되고 성공 메시지가 표시된다', async () => {
    let capturedBody: Record<string, unknown> | undefined
    server.use(
      http.patch('/api/v1/users/me/profile', async ({ request }) => {
        capturedBody = (await request.clone().json()) as Record<string, unknown>
        return HttpResponse.json(
          buildAliceResponse({
            displayName: capturedBody['displayName'] as string,
            timezone: capturedBody['timezone'] as string,
          }),
        )
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.displayNameLabel)).toHaveValue(
        ALICE_PROFILE_FIXTURE.displayName,
      )
    })

    const displayNameInput = screen.getByLabelText(profileLabels.form.displayNameLabel)
    await user.clear(displayNameInput)
    await user.type(displayNameInput, '김맥시')
    await user.selectOptions(
      screen.getByLabelText(profileLabels.form.timezoneLabel),
      'America/New_York',
    )
    await user.click(screen.getByRole('button', { name: profileLabels.form.saveButton }))

    await waitFor(() => {
      expect(capturedBody).toBeDefined()
    })
    expect(capturedBody).toEqual({ displayName: '김맥시', timezone: 'America/New_York' })

    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(profileLabels.status.saveSuccess)
    })
  })

  it('EC2/S3 — department를 비우고 저장하면 명시적으로 null이 전송된다', async () => {
    let capturedBody: Record<string, unknown> | undefined
    server.use(
      http.patch('/api/v1/users/me/profile', async ({ request }) => {
        capturedBody = (await request.clone().json()) as Record<string, unknown>
        return HttpResponse.json(buildAliceResponse({ department: null }))
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.departmentLabel)).toHaveValue(
        ALICE_PROFILE_FIXTURE.department,
      )
    })

    await user.clear(screen.getByLabelText(profileLabels.form.departmentLabel))
    await user.click(screen.getByRole('button', { name: profileLabels.form.saveButton }))

    await waitFor(() => {
      expect(capturedBody).toBeDefined()
    })
    expect(capturedBody).toEqual({ department: null })

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.departmentLabel)).toHaveValue('')
    })
  })

  it('아무 필드도 변경하지 않으면 빈 body로 전송된다(미변경 키 생략)', async () => {
    let capturedBody: Record<string, unknown> | undefined
    server.use(
      http.patch('/api/v1/users/me/profile', async ({ request }) => {
        capturedBody = (await request.clone().json()) as Record<string, unknown>
        return HttpResponse.json(buildAliceResponse())
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.displayNameLabel)).toHaveValue(
        ALICE_PROFILE_FIXTURE.displayName,
      )
    })
    await user.click(screen.getByRole('button', { name: profileLabels.form.saveButton }))

    await waitFor(() => {
      expect(capturedBody).toBeDefined()
    })
    expect(capturedBody).toEqual({})
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// F6 — 클라이언트 검증 최소화(빈 displayName)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileForm — F6 클라이언트 검증', () => {
  it('displayName이 빈 값이면 저장 버튼이 비활성화된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.displayNameLabel)).toHaveValue(
        ALICE_PROFILE_FIXTURE.displayName,
      )
    })
    await user.clear(screen.getByLabelText(profileLabels.form.displayNameLabel))

    expect(screen.getByRole('button', { name: profileLabels.form.saveButton })).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6/F7 — 저장 검증 실패
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileForm — S6 검증 실패', () => {
  it('PATCH가 400 PROFILE_VALIDATION_FAILED를 반환하면 role=alert 한글 메시지를 표시한다', async () => {
    server.use(
      http.patch('/api/v1/users/me/profile', () =>
        HttpResponse.json(
          { code: 'PROFILE_VALIDATION_FAILED', message: 'ignored' },
          { status: 400 },
        ),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.displayNameLabel)).toHaveValue(
        ALICE_PROFILE_FIXTURE.displayName,
      )
    })
    await user.click(screen.getByRole('button', { name: profileLabels.form.saveButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('표시 이름을 입력해 주세요.')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// F8 — isPending 저장 버튼 비활성
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileForm — F8 저장 isPending', () => {
  it('제출 진행 중에 저장 버튼이 disabled 상태이다', async () => {
    server.use(
      http.patch('/api/v1/users/me/profile', async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json(buildAliceResponse())
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.displayNameLabel)).toHaveValue(
        ALICE_PROFILE_FIXTURE.displayName,
      )
    })
    await user.click(screen.getByRole('button', { name: profileLabels.form.saveButton }))

    const submitButton = document.querySelector('button[type="submit"]')
    expect(submitButton).toBeDisabled()
    expect(submitButton).toHaveTextContent(profileLabels.form.savingButton)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4/S5/EC4/EC9 — 아바타 업로드/삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileForm — 아바타 업로드/삭제', () => {
  it('아바타 업로드 성공 후 Avatar에 전달되는 cacheBust(authStore.avatarVersion)가 1 증가한다 (회귀 방지)', async () => {
    useAuthStore.setState({ avatarVersion: 0 })
    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.avatar.fileInputLabel)).toBeInTheDocument()
    })
    expect(screen.getByTestId('avatar-mock')).toHaveAttribute('data-cache-bust', '0')

    const fileInput = screen.getByLabelText(
      profileLabels.avatar.fileInputLabel,
    ) as HTMLInputElement
    await user.upload(fileInput, new File(['x'], 'avatar.png', { type: 'image/png' }))

    await waitFor(() => {
      expect(screen.getByTestId('avatar-mock')).toHaveAttribute('data-cache-bust', '1')
    })
  })

  it('S4/EC9 — 파일 선택 시 즉시 업로드되고, 성공 후 Avatar에 새 avatarUrl이 반영되며 input이 초기화된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.avatar.fileInputLabel)).toBeInTheDocument()
    })

    const fileInput = screen.getByLabelText(
      profileLabels.avatar.fileInputLabel,
    ) as HTMLInputElement
    const file = new File(['x'], 'avatar.png', { type: 'image/png' })
    await user.upload(fileInput, file)

    expect(fileInput.value).toBe('')

    await waitFor(() => {
      expect(screen.getByTestId('avatar-mock')).toHaveAttribute(
        'data-avatar-url',
        `/api/v1/users/${ALICE_PROFILE_FIXTURE.userId}/avatar`,
      )
    })
  })

  it('S5 — 업로드된 아바타를 삭제하면 Avatar의 avatarUrl이 다시 비워진다', async () => {
    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.avatar.fileInputLabel)).toBeInTheDocument()
    })

    const fileInput = screen.getByLabelText(
      profileLabels.avatar.fileInputLabel,
    ) as HTMLInputElement
    await user.upload(fileInput, new File(['x'], 'avatar.png', { type: 'image/png' }))

    await waitFor(() => {
      expect(screen.getByTestId('avatar-mock')).toHaveAttribute(
        'data-avatar-url',
        `/api/v1/users/${ALICE_PROFILE_FIXTURE.userId}/avatar`,
      )
    })

    await user.click(screen.getByRole('button', { name: profileLabels.avatar.deleteButton }))

    await waitFor(() => {
      expect(screen.getByTestId('avatar-mock')).toHaveAttribute('data-avatar-url', '')
    })
  })

  it('EC4 — 업로드 진행 중에는 파일 input과 삭제 버튼이 비활성화된다', async () => {
    server.use(
      http.post('/api/v1/users/me/profile/avatar', async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({
          avatarUrl: `/api/v1/users/${ALICE_PROFILE_FIXTURE.userId}/avatar`,
        })
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.avatar.fileInputLabel)).toBeInTheDocument()
    })

    const fileInput = screen.getByLabelText(
      profileLabels.avatar.fileInputLabel,
    ) as HTMLInputElement
    await user.upload(fileInput, new File(['x'], 'avatar.png', { type: 'image/png' }))

    expect(fileInput).toBeDisabled()
    expect(screen.getByRole('button', { name: profileLabels.avatar.deleteButton })).toBeDisabled()
  })

  it('S7 — 아바타 업로드가 400 AVATAR_VALIDATION_FAILED를 반환하면 role=alert 한글 메시지를 표시한다', async () => {
    server.use(
      http.post('/api/v1/users/me/profile/avatar', () =>
        HttpResponse.json(
          { code: 'AVATAR_VALIDATION_FAILED', message: 'ignored' },
          { status: 400 },
        ),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.avatar.fileInputLabel)).toBeInTheDocument()
    })

    const fileInput = screen.getByLabelText(
      profileLabels.avatar.fileInputLabel,
    ) as HTMLInputElement
    await user.upload(fileInput, new File(['x'], 'avatar.png', { type: 'image/png' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('5MB')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-PR-04 — LDAP 출처 배지 + "LDAP 값으로 재설정"
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileForm — FR-PR-04 LDAP 출처 배지 + 재설정', () => {
  it('ldapLinked=true && source=LDAP이면 동기화 배지·힌트가 보이고 재설정 버튼은 없다', async () => {
    renderForm()

    await waitFor(() => {
      expect(screen.getByText(profileLabels.ldapSource.syncedBadge)).toBeInTheDocument()
    })
    expect(screen.getByText(profileLabels.ldapSource.syncedHint)).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: profileLabels.ldapSource.resyncButton }),
    ).not.toBeInTheDocument()
  })

  it('source=USER면 직접편집 배지·재설정 버튼이 보이고, 클릭 시 resync 호출 후 배지가 LDAP로 갱신된다', async () => {
    let currentSource: 'LDAP' | 'USER' = 'USER'
    server.use(
      http.get('/api/v1/users/me/profile', () =>
        HttpResponse.json(buildAliceResponse({ displayNameSource: currentSource })),
      ),
      http.post('/api/v1/users/me/profile/display-name/resync', () => {
        currentSource = 'LDAP'
        return HttpResponse.json(buildAliceResponse({ displayNameSource: 'LDAP' }))
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(screen.getByText(profileLabels.ldapSource.overriddenBadge)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: profileLabels.ldapSource.resyncButton }))

    await waitFor(() => {
      expect(screen.getByText(profileLabels.ldapSource.syncedBadge)).toBeInTheDocument()
    })
    expect(screen.queryByText(profileLabels.ldapSource.overriddenBadge)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: profileLabels.ldapSource.resyncButton }),
    ).not.toBeInTheDocument()
  })

  it('resync가 409 DISPLAY_NAME_NOT_LDAP_LINKED를 반환하면 role=alert 한글 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/users/me/profile', () =>
        HttpResponse.json(buildAliceResponse({ displayNameSource: 'USER' })),
      ),
      http.post('/api/v1/users/me/profile/display-name/resync', () =>
        HttpResponse.json(
          { code: 'DISPLAY_NAME_NOT_LDAP_LINKED', message: 'ignored' },
          { status: 409 },
        ),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderForm()

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: profileLabels.ldapSource.resyncButton }),
      ).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: profileLabels.ldapSource.resyncButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('디렉터리')
    })
  })

  it('ldapLinked=false(로컬 사용자, bob)면 배지·재설정 버튼이 모두 미노출된다', async () => {
    useAuthStore.getState().setSession({ accessToken: BOB_TOKEN, user: bobUser })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.displayNameLabel)).toHaveValue(
        BOB_PROFILE_FIXTURE.displayName,
      )
    })
    expect(screen.queryByText(profileLabels.ldapSource.syncedBadge)).not.toBeInTheDocument()
    expect(screen.queryByText(profileLabels.ldapSource.overriddenBadge)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: profileLabels.ldapSource.resyncButton }),
    ).not.toBeInTheDocument()
  })
})
