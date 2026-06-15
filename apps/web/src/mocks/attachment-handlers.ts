// 첨부 파일 BC MSW 핸들러 — stateful in-memory store (FR-AC-01 D6/D7)
// 업로드/삭제가 목록 조회에 즉시 반영되도록 모듈 스코프 Map으로 관리한다.
import { http, HttpResponse } from 'msw'
import type { AttachmentResponse } from '@/api/attachments'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (component-handlers 패턴 동일)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 */
function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 상태 — 이슈 키별 첨부 파일 Map
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 키 → 첨부 파일 목록 Map */
let attachmentStore: Map<string, AttachmentResponse[]> = new Map()

/**
 * 첨부 파일 store를 초기화한다 (테스트 격리용).
 * 각 테스트의 beforeEach에서 호출해 이전 테스트 상태가 남지 않도록 한다.
 */
export function resetAttachmentStore(): void {
  attachmentStore = new Map()
}

/**
 * 지정한 이슈에 시드 첨부 파일을 추가한다.
 * E2E 테스트에서 초기 첨부 목록을 세팅할 때 사용한다.
 *
 * @param issueKey 이슈 키 (예: "ATLAS-1")
 * @param attachments 추가할 첨부 파일 목록
 */
export function seedAttachments(issueKey: string, attachments: AttachmentResponse[]): void {
  const existing = attachmentStore.get(issueKey) ?? []
  attachmentStore.set(issueKey, [...existing, ...attachments])
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/issues/:key/attachments — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 첨부 파일 목록 조회 핸들러.
 * 성공 → 200 { data: AttachmentResponse[] }
 * 이슈 없거나 첨부 없으면 빈 배열 반환 (VIEW 권한 검증은 생략).
 */
const listAttachmentsHandler = http.get(
  '/api/v1/issues/:key/attachments',
  ({ params }) => {
    const issueKey = params['key'] as string
    const list = attachmentStore.get(issueKey) ?? []
    return HttpResponse.json({ data: list })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/issues/:key/attachments — 업로드
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 첨부 파일 업로드 핸들러.
 * multipart/form-data 요청 수신 → in-memory store에 추가.
 * 성공 → 201 { data: AttachmentResponse }
 *
 * 업로드된 파일의 size/contentType은 FormData에서 추출한다.
 * MSW 환경에서 FormData 파싱이 제한적이므로 filename/size는 안전하게 폴백한다.
 */
const uploadAttachmentHandler = http.post(
  '/api/v1/issues/:key/attachments',
  async ({ params, request }) => {
    const issueKey = params['key'] as string

    // FormData 파싱 — file part 추출
    let filename = 'uploaded-file'
    let contentType = 'application/octet-stream'
    let sizeBytes = 0

    try {
      const formData = await request.formData()
      const file = formData.get('file')
      if (file instanceof File) {
        filename = file.name
        contentType = file.type !== '' ? file.type : 'application/octet-stream'
        sizeBytes = file.size
      }
    } catch {
      // FormData 파싱 실패 시 폴백값 유지
    }

    const newAttachment: AttachmentResponse = {
      id: generateUuidV4(),
      filename,
      contentType,
      sizeBytes,
      uploadedBy: '00000000-0000-4000-a000-000000000001', // mock 업로더 UUID
      createdAt: new Date().toISOString(),
    }

    const existing = attachmentStore.get(issueKey) ?? []
    attachmentStore.set(issueKey, [...existing, newAttachment])

    return HttpResponse.json({ data: newAttachment }, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/issues/:key/attachments/:attachmentId — 다운로드
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 첨부 파일 다운로드 핸들러.
 * 성공 → 200 Blob (최소 1바이트, Content-Disposition: attachment)
 * 미존재 → 404
 */
const downloadAttachmentHandler = http.get(
  '/api/v1/issues/:key/attachments/:attachmentId',
  ({ params }) => {
    const issueKey = params['key'] as string
    const attachmentId = params['attachmentId'] as string

    const list = attachmentStore.get(issueKey) ?? []
    const att = list.find((a) => a.id === attachmentId)

    if (att === undefined) {
      return HttpResponse.json(
        { errorCode: 'ATTACHMENT_NOT_FOUND', detail: '첨부 파일을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }

    return new HttpResponse(new Blob(['mock-content'], { type: att.contentType }), {
      status: 200,
      headers: {
        'Content-Disposition': `attachment; filename="${att.filename}"`,
        'Content-Type': att.contentType,
      },
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/issues/:key/attachments/:attachmentId — 하드삭제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 첨부 파일 삭제 핸들러.
 * 성공 → 204 No Content (store에서 제거)
 * 미존재 → 404
 */
const deleteAttachmentHandler = http.delete(
  '/api/v1/issues/:key/attachments/:attachmentId',
  ({ params }) => {
    const issueKey = params['key'] as string
    const attachmentId = params['attachmentId'] as string

    const list = attachmentStore.get(issueKey) ?? []
    const idx = list.findIndex((a) => a.id === attachmentId)

    if (idx === -1) {
      return HttpResponse.json(
        { errorCode: 'ATTACHMENT_NOT_FOUND', detail: '첨부 파일을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }

    const newList = [...list.slice(0, idx), ...list.slice(idx + 1)]
    attachmentStore.set(issueKey, newList)

    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 첨부 파일 BC MSW 핸들러 배열 */
export const attachmentHandlers = [
  listAttachmentsHandler,
  uploadAttachmentHandler,
  downloadAttachmentHandler,
  deleteAttachmentHandler,
]
