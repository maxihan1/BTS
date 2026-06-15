// useAttachments 훅 단위 테스트 — useAttachmentList/useUploadAttachment/useDeleteAttachment
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  useAttachmentList,
  useUploadAttachment,
  useDeleteAttachment,
  ATTACHMENTS_QUERY_KEY,
} from './useAttachments'

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const attachmentFixture = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  filename: 'report.pdf',
  contentType: 'application/pdf',
  sizeBytes: 102400,
  uploadedBy: '550e8400-e29b-41d4-a716-446655440001',
  createdAt: '2026-06-15T10:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTACHMENTS_QUERY_KEY 상수
// ─────────────────────────────────────────────────────────────────────────────

describe('ATTACHMENTS_QUERY_KEY', () => {
  it('T-UA-K-1: ATTACHMENTS_QUERY_KEY("ATLAS-1")는 ["attachments", "ATLAS-1"] 를 반환한다', () => {
    expect(ATTACHMENTS_QUERY_KEY('ATLAS-1')).toEqual(['attachments', 'ATLAS-1'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useAttachmentList — 목록 쿼리
// ─────────────────────────────────────────────────────────────────────────────

describe('useAttachmentList', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/attachments', () =>
        HttpResponse.json({ data: [attachmentFixture] }),
      ),
      http.get('/api/v1/issues/ATLAS-EMPTY/attachments', () =>
        HttpResponse.json({ data: [] }),
      ),
    )
  })

  it('T-UA-1-1: queryKey가 ["attachments", key] 형태다', async () => {
    const { queryClient, wrapper } = createWrapper()

    renderHook(() => useAttachmentList('ATLAS-1'), { wrapper })

    await waitFor(() =>
      expect(queryClient.getQueryState(['attachments', 'ATLAS-1'])).not.toBeUndefined(),
    )
  })

  it('T-UA-1-2: 성공 시 첨부 파일 목록을 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useAttachmentList('ATLAS-1'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]?.id).toBe(attachmentFixture.id)
  })

  it('T-UA-1-3: 빈 이슈는 빈 배열을 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useAttachmentList('ATLAS-EMPTY'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUploadAttachment — 업로드 mutation
// ─────────────────────────────────────────────────────────────────────────────

describe('useUploadAttachment', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/attachments', () =>
        HttpResponse.json({ data: [attachmentFixture] }),
      ),
    )
  })

  it('T-UA-2-1: 업로드 성공 시 attachments 쿼리를 invalidate한다 (refetch 트리거)', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/attachments', async () =>
        HttpResponse.json({ data: attachmentFixture }, { status: 201 }),
      ),
    )

    const { queryClient, wrapper } = createWrapper()

    // 먼저 목록 쿼리를 캐시에 올린다
    await queryClient.prefetchQuery({
      queryKey: ['attachments', 'ATLAS-1'],
      queryFn: () => Promise.resolve([attachmentFixture]),
    })

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUploadAttachment('ATLAS-1'), { wrapper })

    const file = new File(['content'], 'report.pdf', { type: 'application/pdf' })

    await act(async () => {
      result.current.mutate(file)
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    // invalidateQueries가 ['attachments', 'ATLAS-1'] 키로 호출됐는지 확인
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['attachments', 'ATLAS-1'] }),
    )
  })

  it('T-UA-2-2: 업로드 실패 시 sonner toast.error를 호출한다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/attachments', () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )

    const { toast } = await import('sonner')
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useUploadAttachment('ATLAS-1'), { wrapper })

    const file = new File(['x'], 'test.txt', { type: 'text/plain' })

    await act(async () => {
      result.current.mutate(file)
      await waitFor(() => expect(result.current.isError).toBe(true))
    })

    expect(toast.error).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteAttachment — 삭제 mutation
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteAttachment', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/attachments', () =>
        HttpResponse.json({ data: [attachmentFixture] }),
      ),
    )
  })

  it('T-UA-3-1: 삭제 성공 시 attachments 쿼리를 invalidate한다', async () => {
    server.use(
      http.delete(
        '/api/v1/issues/ATLAS-1/attachments/550e8400-e29b-41d4-a716-446655440000',
        () => new HttpResponse(null, { status: 204 }),
      ),
    )

    const { queryClient, wrapper } = createWrapper()

    await queryClient.prefetchQuery({
      queryKey: ['attachments', 'ATLAS-1'],
      queryFn: () => Promise.resolve([attachmentFixture]),
    })

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useDeleteAttachment('ATLAS-1'), { wrapper })

    await act(async () => {
      result.current.mutate('550e8400-e29b-41d4-a716-446655440000')
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['attachments', 'ATLAS-1'] }),
    )
  })

  it('T-UA-3-2: 삭제 실패 시 sonner toast.error를 호출한다', async () => {
    server.use(
      http.delete(
        '/api/v1/issues/ATLAS-1/attachments/550e8400-e29b-41d4-a716-446655440000',
        () => HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )

    const { toast } = await import('sonner')
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useDeleteAttachment('ATLAS-1'), { wrapper })

    await act(async () => {
      result.current.mutate('550e8400-e29b-41d4-a716-446655440000')
      await waitFor(() => expect(result.current.isError).toBe(true))
    })

    expect(toast.error).toHaveBeenCalled()
  })
})
