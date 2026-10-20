// restoreSession 단위 테스트 — token 이 없을 때만 refresh 를 한 번 부르고, 실패는 삼킨다 (SSO 착지)
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from './authStore'

const refreshSession = vi.fn()
vi.mock('@/api/client', () => ({ refreshSession: () => refreshSession() }))

const { restoreSession } = await import('./restoreSession')

describe('restoreSession', () => {
  beforeEach(() => {
    refreshSession.mockReset()
    useAuthStore.setState({ accessToken: null, user: null })
  })

  it('access token 이 없으면 refreshSession 을 한 번 부른다 (SSO 콜백은 refresh cookie 만 굽는다)', async () => {
    refreshSession.mockResolvedValue({})

    await restoreSession()

    expect(refreshSession).toHaveBeenCalledTimes(1)
  })

  it('refresh 가 실패해도 throw 하지 않는다 — 로그인 안 된 상태로 앱을 띄운다', async () => {
    refreshSession.mockRejectedValue(new Error('401'))

    await expect(restoreSession()).resolves.toBeUndefined()
  })

  it('refresh 는 됐는데 whoami 가 실패하면 token 만 남은 반쪽 세션을 지운다', async () => {
    refreshSession.mockImplementation(async () => {
      useAuthStore.setState({ accessToken: 'fresh-token' })
      throw new Error('whoami 503')
    })

    await restoreSession()

    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('access token 이 있으면 부르지 않는다', async () => {
    useAuthStore.setState({ accessToken: 'valid-token' })

    await restoreSession()

    expect(refreshSession).not.toHaveBeenCalled()
  })
})
