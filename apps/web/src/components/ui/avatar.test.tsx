// Avatar 컴포넌트 단위 테스트 — blob fetch 렌더/이니셜 폴백/revoke 검증
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { Avatar } from './avatar'

// ─────────────────────────────────────────────────────────────────────────────
// fetchAvatarBlob mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/profile', () => ({
  fetchAvatarBlob: vi.fn(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// URL.createObjectURL / revokeObjectURL mock
// URL 전체 교체 시 URL.parse 등이 소실되므로 두 메서드만 교체한다
// (AttachmentPreviewModal.test.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

let createSpy: ReturnType<typeof vi.fn>
let revokeSpy: ReturnType<typeof vi.fn>

beforeEach(async () => {
  createSpy = vi.fn().mockReturnValue('blob:mock')
  revokeSpy = vi.fn()
  vi.stubGlobal('URL', { ...URL, createObjectURL: createSpy, revokeObjectURL: revokeSpy })

  const { fetchAvatarBlob } = await import('@/api/profile')
  vi.mocked(fetchAvatarBlob).mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: avatarUrl 존재 → fetch 후 <img> 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-1: avatarUrl 존재', () => {
  it('fetch 성공 시 <img> 렌더 (alt=displayName, src=blob:mock)', async () => {
    const { fetchAvatarBlob } = await import('@/api/profile')
    vi.mocked(fetchAvatarBlob).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))

    render(<Avatar avatarUrl="/api/v1/users/u1/avatar" displayName="홍길동" username="hong" />)

    const img = await screen.findByRole('img', { name: '홍길동' })
    expect(img.tagName).toBe('IMG')
    expect(img).toHaveAttribute('src', 'blob:mock')
    expect(createSpy).toHaveBeenCalledOnce()
    expect(fetchAvatarBlob).toHaveBeenCalledWith('/api/v1/users/u1/avatar')
  })

  it('displayName 없으면 username을 alt로 사용한다', async () => {
    const { fetchAvatarBlob } = await import('@/api/profile')
    vi.mocked(fetchAvatarBlob).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))

    render(<Avatar avatarUrl="/api/v1/users/u1/avatar" displayName={null} username="hong" />)

    expect(await screen.findByRole('img', { name: 'hong' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-2: avatarUrl null/undefined → 이니셜 폴백
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-2: avatarUrl 부재 → 이니셜 폴백', () => {
  it('avatarUrl이 null이면 displayName 첫 글자를 폴백으로 렌더한다', () => {
    render(<Avatar avatarUrl={null} displayName="홍길동" username="hong" />)

    const fallback = screen.getByRole('img', { name: '홍길동' })
    expect(fallback.tagName).toBe('DIV')
    expect(fallback).toHaveTextContent('홍')
  })

  it('avatarUrl이 undefined이고 displayName도 없으면 username 첫 글자를 폴백으로 렌더한다', () => {
    render(<Avatar avatarUrl={undefined} displayName={null} username="hong" />)

    const fallback = screen.getByRole('img', { name: 'hong' })
    expect(fallback).toHaveTextContent('h')
  })

  it('displayName/username 모두 없으면 "?"와 기본 aria-label을 렌더한다', () => {
    render(<Avatar avatarUrl={null} />)

    const fallback = screen.getByRole('img', { name: '아바타' })
    expect(fallback).toHaveTextContent('?')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-3: fetch 실패(404 AVATAR_NOT_FOUND 포함, EC8) → 이니셜 폴백
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-3: fetch 실패 → 이니셜 폴백', () => {
  it('fetchAvatarBlob이 404 ApiError로 reject되면 이니셜 폴백을 렌더한다', async () => {
    const { fetchAvatarBlob } = await import('@/api/profile')
    vi.mocked(fetchAvatarBlob).mockRejectedValue(
      new ApiError(404, { code: 'AVATAR_NOT_FOUND' }),
    )

    render(<Avatar avatarUrl="/api/v1/users/u1/avatar" displayName="홍길동" username="hong" />)

    const fallback = await screen.findByRole('img', { name: '홍길동' })
    expect(fallback.tagName).toBe('DIV')
    expect(fallback).toHaveTextContent('홍')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-4: 언마운트 시 revokeObjectURL 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-4: 언마운트 cleanup', () => {
  it('언마운트 시 revokeObjectURL이 호출된다', async () => {
    const { fetchAvatarBlob } = await import('@/api/profile')
    vi.mocked(fetchAvatarBlob).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))

    const { unmount } = render(
      <Avatar avatarUrl="/api/v1/users/u1/avatar" displayName="홍길동" username="hong" />,
    )

    await screen.findByRole('img', { name: '홍길동' })
    expect(createSpy).toHaveBeenCalledOnce()

    unmount()

    expect(revokeSpy).toHaveBeenCalledWith('blob:mock')
  })

  it('avatarUrl이 다른 값으로 교체되면 이전 objectURL을 revoke하고 새로 fetch한다', async () => {
    const { fetchAvatarBlob } = await import('@/api/profile')
    vi.mocked(fetchAvatarBlob).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))

    const { rerender } = render(
      <Avatar avatarUrl="/api/v1/users/u1/avatar" displayName="홍길동" username="hong" />,
    )
    await screen.findByRole('img', { name: '홍길동' })
    expect(createSpy).toHaveBeenCalledTimes(1)

    rerender(<Avatar avatarUrl="/api/v1/users/u2/avatar" displayName="김철수" username="kim" />)
    await screen.findByRole('img', { name: '김철수' })

    expect(revokeSpy).toHaveBeenCalledWith('blob:mock')
    expect(createSpy).toHaveBeenCalledTimes(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-5: cacheBust — 아바타 교체 시 회귀 방지(핵심)
// avatarUrl 문자열 자체는 userId에서만 파생되는 고정 경로라 아바타를 교체해도 바뀌지
// 않는다. cacheBust(authStore.avatarVersion)가 바뀌면 avatarUrl이 그대로여도
// 강제로 재fetch되어야 한다 — 아니면 옛 아바타가 리로드 전까지 표시된다.
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-5: cacheBust — avatarUrl 고정 + cacheBust 변경 시 재fetch', () => {
  it('avatarUrl이 같아도 cacheBust가 바뀌면 재fetch하고 이전 objectURL을 revoke한다', async () => {
    const { fetchAvatarBlob } = await import('@/api/profile')
    vi.mocked(fetchAvatarBlob).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))

    const { rerender } = render(
      <Avatar avatarUrl="/api/v1/users/u1/avatar" displayName="홍길동" username="hong" cacheBust={0} />,
    )
    await screen.findByRole('img', { name: '홍길동' })
    expect(fetchAvatarBlob).toHaveBeenCalledTimes(1)
    expect(fetchAvatarBlob).toHaveBeenLastCalledWith('/api/v1/users/u1/avatar?v=0')

    rerender(
      <Avatar avatarUrl="/api/v1/users/u1/avatar" displayName="홍길동" username="hong" cacheBust={1} />,
    )

    await waitFor(() => expect(fetchAvatarBlob).toHaveBeenCalledTimes(2))
    expect(fetchAvatarBlob).toHaveBeenLastCalledWith('/api/v1/users/u1/avatar?v=1')
    expect(revokeSpy).toHaveBeenCalledWith('blob:mock')
    expect(createSpy).toHaveBeenCalledTimes(2)
  })

  it('cacheBust prop이 없으면 기존 동작대로 원본 avatarUrl 그대로 fetch한다(하위 호환)', async () => {
    const { fetchAvatarBlob } = await import('@/api/profile')
    vi.mocked(fetchAvatarBlob).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))

    render(<Avatar avatarUrl="/api/v1/users/u1/avatar" displayName="홍길동" username="hong" />)

    await screen.findByRole('img', { name: '홍길동' })
    expect(fetchAvatarBlob).toHaveBeenCalledWith('/api/v1/users/u1/avatar')
  })
})
