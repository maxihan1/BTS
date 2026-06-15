// 첨부 파일 API 클라이언트 단위 테스트 — fetchAttachments/uploadAttachment/downloadAttachment/deleteAttachment + Zod 파싱
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import {
  fetchAttachments,
  uploadAttachment,
  downloadAttachment,
  deleteAttachment,
  attachmentResponseSchema,
  MAX_ATTACHMENT_BYTES,
} from './attachments'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 백엔드 AttachmentResponse DTO와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

const attachmentFixture = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  filename: 'report.pdf',
  contentType: 'application/pdf',
  sizeBytes: 102400,
  uploadedBy: '550e8400-e29b-41d4-a716-446655440001',
  createdAt: '2026-06-15T10:00:00Z',
}

const attachmentFixture2 = {
  id: '550e8400-e29b-41d4-a716-446655440002',
  filename: '테스트파일.txt',
  contentType: 'text/plain',
  sizeBytes: 512,
  uploadedBy: '550e8400-e29b-41d4-a716-446655440001',
  createdAt: '2026-06-15T11:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// T-AT-S. attachmentResponseSchema 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('attachmentResponseSchema', () => {
  it('T-AT-S-1: 유효한 AttachmentResponse 픽스처 파싱 성공', () => {
    const result = attachmentResponseSchema.safeParse(attachmentFixture)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.id).toBe('550e8400-e29b-41d4-a716-446655440000')
      expect(result.data.filename).toBe('report.pdf')
      expect(result.data.sizeBytes).toBe(102400)
    }
  })

  it('T-AT-S-2: 필수 필드 누락 시 파싱 실패', () => {
    // filename 필드를 제거한 불완전한 객체 생성
    const withoutFilename: Record<string, unknown> = { ...attachmentFixture }
    delete withoutFilename['filename']
    const result = attachmentResponseSchema.safeParse(withoutFilename)
    expect(result.success).toBe(false)
  })

  it('T-AT-S-3: sizeBytes가 숫자가 아니면 파싱 실패', () => {
    const invalid = { ...attachmentFixture, sizeBytes: 'not-a-number' }
    const result = attachmentResponseSchema.safeParse(invalid)
    expect(result.success).toBe(false)
  })

  it('T-AT-S-4: id가 UUID 형식이 아니면 파싱 실패', () => {
    const invalid = { ...attachmentFixture, id: 'not-a-uuid' }
    const result = attachmentResponseSchema.safeParse(invalid)
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-AT-C. MAX_ATTACHMENT_BYTES 상수
// ─────────────────────────────────────────────────────────────────────────────

describe('MAX_ATTACHMENT_BYTES', () => {
  it('T-AT-C-1: 100MB(104857600 바이트)와 일치한다', () => {
    expect(MAX_ATTACHMENT_BYTES).toBe(100 * 1024 * 1024)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-AT-1. fetchAttachments — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchAttachments', () => {
  it('T-AT-1-1: GET /api/v1/issues/{key}/attachments 호출 후 data 배열 언랩 반환', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/attachments', () =>
        HttpResponse.json({ data: [attachmentFixture, attachmentFixture2] }),
      ),
    )

    const result = await fetchAttachments('ATLAS-1')
    expect(result).toHaveLength(2)
    expect(result[0]?.id).toBe(attachmentFixture.id)
    expect(result[1]?.filename).toBe('테스트파일.txt')
  })

  it('T-AT-1-2: 첨부 없는 이슈는 빈 배열 반환', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-2/attachments', () =>
        HttpResponse.json({ data: [] }),
      ),
    )

    const result = await fetchAttachments('ATLAS-2')
    expect(result).toHaveLength(0)
  })

  it('T-AT-1-3: 404 응답 → ApiError(404) throw', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-MISSING/attachments', () =>
        HttpResponse.json({ errorCode: 'ISSUE_NOT_FOUND' }, { status: 404 }),
      ),
    )

    await expect(fetchAttachments('ATLAS-MISSING')).rejects.toBeInstanceOf(ApiError)
    await expect(fetchAttachments('ATLAS-MISSING')).rejects.toMatchObject({ status: 404 })
  })

  it('T-AT-1-4: 403 응답 → ApiError(403) throw', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-FORBIDDEN/attachments', () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )

    await expect(fetchAttachments('ATLAS-FORBIDDEN')).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-AT-2. uploadAttachment — 파일 업로드
// ─────────────────────────────────────────────────────────────────────────────

describe('uploadAttachment', () => {
  it('T-AT-2-1: POST /api/v1/issues/{key}/attachments multipart, part명 "file" 전송 후 201 응답 파싱', async () => {
    let capturedFile: File | null = null

    server.use(
      http.post('/api/v1/issues/ATLAS-1/attachments', async ({ request }) => {
        const fd = await request.formData()
        // FormData.get() 반환 타입이 msw 환경에서 File | string | null
        capturedFile = fd.get('file') as File | null
        return HttpResponse.json({ data: attachmentFixture }, { status: 201 })
      }),
    )

    const file = new File(['content'], 'report.pdf', { type: 'application/pdf' })
    const result = await uploadAttachment('ATLAS-1', file)

    // FormData part명이 'file' 이어야 한다
    expect(capturedFile).not.toBeNull()
    // 응답이 AttachmentResponse 스키마로 파싱된다
    expect(result.id).toBe(attachmentFixture.id)
    expect(result.filename).toBe('report.pdf')
  })

  it('T-AT-2-2: Content-Type 헤더를 자동으로 application/json 으로 설정하지 않는다 (FormData 분기)', async () => {
    let capturedContentType: string | null = null

    server.use(
      http.post('/api/v1/issues/ATLAS-1/attachments', ({ request }) => {
        capturedContentType = request.headers.get('content-type')
        return HttpResponse.json({ data: attachmentFixture }, { status: 201 })
      }),
    )

    const file = new File(['content'], 'test.txt', { type: 'text/plain' })
    await uploadAttachment('ATLAS-1', file)

    expect(capturedContentType).not.toContain('application/json')
  })

  it('T-AT-2-3: 413 응답 → ApiError(413) throw', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/attachments', () =>
        HttpResponse.json({ errorCode: 'FILE_TOO_LARGE' }, { status: 413 }),
      ),
    )

    const largeFile = new File(['x'.repeat(10)], 'large.bin', { type: 'application/octet-stream' })
    await expect(uploadAttachment('ATLAS-1', largeFile)).rejects.toMatchObject({ status: 413 })
  })

  it('T-AT-2-4: 403 응답 → ApiError(403) throw', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/attachments', () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )

    const file = new File(['x'], 'test.txt', { type: 'text/plain' })
    await expect(uploadAttachment('ATLAS-1', file)).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-AT-3. downloadAttachment — blob 다운로드
// ─────────────────────────────────────────────────────────────────────────────

describe('downloadAttachment', () => {
  it('T-AT-3-1: GET /api/v1/issues/{key}/attachments/{id} 호출 후 Blob 반환', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/attachments/550e8400-e29b-41d4-a716-446655440000', () =>
        HttpResponse.arrayBuffer(new ArrayBuffer(8), {
          headers: {
            'Content-Type': 'application/pdf',
            'Content-Disposition': 'attachment; filename="report.pdf"',
          },
        }),
      ),
    )

    const blob = await downloadAttachment('ATLAS-1', '550e8400-e29b-41d4-a716-446655440000')

    // jsdom 환경에서 MSW Blob과 global Blob의 생성자 클래스가 다를 수 있으므로 duck-typing으로 검증
    expect(blob).toBeDefined()
    expect(blob.size).toBeGreaterThan(0)
    expect(typeof blob.arrayBuffer).toBe('function')
  })

  it('T-AT-3-2: 404 응답 → ApiError(404) throw', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/attachments/bad-id', () =>
        HttpResponse.json({ errorCode: 'ATTACHMENT_NOT_FOUND' }, { status: 404 }),
      ),
    )

    await expect(downloadAttachment('ATLAS-1', 'bad-id')).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-AT-4. deleteAttachment — 하드삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteAttachment', () => {
  it('T-AT-4-1: DELETE /api/v1/issues/{key}/attachments/{id} 호출 후 204 → undefined 반환', async () => {
    let called = false

    server.use(
      http.delete('/api/v1/issues/ATLAS-1/attachments/550e8400-e29b-41d4-a716-446655440000', () => {
        called = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const result = await deleteAttachment('ATLAS-1', '550e8400-e29b-41d4-a716-446655440000')

    expect(called).toBe(true)
    expect(result).toBeUndefined()
  })

  it('T-AT-4-2: 404 응답 → ApiError(404) throw', async () => {
    server.use(
      http.delete('/api/v1/issues/ATLAS-1/attachments/bad-id', () =>
        HttpResponse.json({ errorCode: 'ATTACHMENT_NOT_FOUND' }, { status: 404 }),
      ),
    )

    await expect(deleteAttachment('ATLAS-1', 'bad-id')).rejects.toMatchObject({ status: 404 })
  })

  it('T-AT-4-3: 403 응답 → ApiError(403) throw', async () => {
    server.use(
      http.delete('/api/v1/issues/ATLAS-1/attachments/550e8400-e29b-41d4-a716-446655440000', () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )

    await expect(
      deleteAttachment('ATLAS-1', '550e8400-e29b-41d4-a716-446655440000'),
    ).rejects.toMatchObject({ status: 403 })
  })
})
