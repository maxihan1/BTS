// useVersions 판별식 — 프로젝트 키가 없으면 요청 자체를 내지 않는다 (빈 경로 세그먼트 차단)
//
// ─────────────────────────────────────────────────────────────────────────────
// 이 파일이 있는 이유
// ─────────────────────────────────────────────────────────────────────────────
// `issues.$key.tsx` 가 이슈 로드 **전에** `useVersions(issue?.projectKey ?? '')` 를 불러
// `GET /api/v1/projects//versions` 가 프로덕션에서 실제로 나갔다(2026-09-07 콘솔 실측).
//
// ★빈 세그먼트(`//`)는 단순한 404 가 아니다. Spring Security 의 경로 매처가 그 URL 을
//   원래 규칙에 매칭시키지 못해 `SecurityConfig.kt` 의 포괄 규칙 `/api/**` → authenticated
//   로 떨어진다. 프로덕션 실측으로 증명했다 — 같은 **공개** 엔드포인트가
//   `/api/v1/auth/oidc/providers` 는 200, `/api/v1/auth//oidc/providers` 는 401 이다.
//   즉 이 요청은 **로그인 여부와 무관하게 성공할 수 없다.**
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { useVersions } from './use-versions'

const { fetchVersionsSpy } = vi.hoisted(() => ({ fetchVersionsSpy: vi.fn() }))

vi.mock('@/api/versions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/versions')>()
  return {
    ...actual,
    fetchVersions: (projectKey: string) => {
      fetchVersionsSpy(projectKey)
      return Promise.resolve([])
    },
  }
})

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { readonly children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children)
  return wrapper
}

beforeEach(() => {
  fetchVersionsSpy.mockClear()
})

describe('useVersions — 빈 프로젝트 키 차단', () => {
  it.each([
    ['빈 문자열', ''],
    ['undefined', undefined],
  ])('T-1: 프로젝트 키가 %s 면 요청을 내지 않는다', async (_label, projectKey) => {
    renderHook(() => useVersions(projectKey), { wrapper: createWrapper() })

    // 마이크로태스크 몇 번을 흘려도 호출이 없어야 한다 — 즉시 단정만 하면
    // "아직 안 나갔을 뿐" 과 구분되지 않는다.
    await Promise.resolve()
    await Promise.resolve()

    expect(fetchVersionsSpy).not.toHaveBeenCalled()
  })

  it('T-2: 프로젝트 키가 있으면 그 키로 요청한다 (비-공허 짝)', async () => {
    // ★이 짝이 없으면 위 단정은 "훅이 아무 요청도 안 한다"와 구분되지 않아 공허해진다.
    renderHook(() => useVersions('ATLAS'), { wrapper: createWrapper() })

    await waitFor(() => expect(fetchVersionsSpy).toHaveBeenCalledWith('ATLAS'))
  })

  it('T-3: 키가 비었다가 채워지면 그때 요청한다 — 영영 막히면 안 된다', async () => {
    const { rerender } = renderHook(({ key }: { key: string | undefined }) => useVersions(key), {
      wrapper: createWrapper(),
      initialProps: { key: undefined as string | undefined },
    })
    expect(fetchVersionsSpy).not.toHaveBeenCalled()

    rerender({ key: 'ATLAS' })

    await waitFor(() => expect(fetchVersionsSpy).toHaveBeenCalledWith('ATLAS'))
  })
})
