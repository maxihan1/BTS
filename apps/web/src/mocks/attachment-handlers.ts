// 첨부 파일 BC MSW 핸들러 — stateful in-memory store (FR-AC-01 D6/D7, FR-AC-02 미리보기 바이트)
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
// GET /api/v1/issues/:key/attachments — 목록 조회 (+ E2E 시드/리셋 지원)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 첨부 파일 목록 조회 핸들러.
 * 성공 → 200 { data: AttachmentResponse[] }
 * 이슈 없거나 첨부 없으면 빈 배열 반환 (VIEW 권한 검증은 생략).
 *
 * E2E 전용 헤더 지원 (msw-derived-behavior-shared-store-e2e 교훈 반영).
 *
 * X-MSW-Reset-Attachments: true
 *   → 이슈 키의 첨부 목록을 초기화하고 빈 배열 반환.
 *   → 이슈 키를 생략하면 전체 store 초기화.
 *
 * X-MSW-Seed-Attachment: true (+ 부가 헤더)
 *   → 헤더값으로 첨부 파일 하나를 store에 추가한다.
 *   X-MSW-Seed-Id:          UUID (필수)
 *   X-MSW-Seed-Filename:    파일명 (필수)
 *   X-MSW-Seed-ContentType: MIME 타입 (기본 application/octet-stream)
 *   X-MSW-Seed-SizeBytes:   바이트 수 (기본 0)
 *   X-MSW-Seed-UploadedBy:  업로더 UUID (기본 mock UUID)
 *   X-MSW-Seed-CreatedAt:   ISO 8601 (기본 현재 시각)
 */
const listAttachmentsHandler = http.get(
  '/api/v1/issues/:key/attachments',
  ({ params, request }) => {
    const issueKey = params['key'] as string

    // E2E 시드 경로 — X-MSW-Seed-Attachment: true
    if (request.headers.get('X-MSW-Seed-Attachment') === 'true') {
      const id = request.headers.get('X-MSW-Seed-Id') ?? generateUuidV4()
      const filename = request.headers.get('X-MSW-Seed-Filename') ?? 'seeded-file'
      const contentType =
        request.headers.get('X-MSW-Seed-ContentType') ?? 'application/octet-stream'
      const sizeBytes = Number(request.headers.get('X-MSW-Seed-SizeBytes') ?? '0')
      const uploadedBy =
        request.headers.get('X-MSW-Seed-UploadedBy') ??
        '00000000-0000-4000-a000-000000000001'
      const createdAt =
        request.headers.get('X-MSW-Seed-CreatedAt') ?? new Date().toISOString()

      const seeded: AttachmentResponse = {
        id,
        filename,
        contentType,
        sizeBytes,
        uploadedBy,
        createdAt,
      }
      const existing = attachmentStore.get(issueKey) ?? []
      attachmentStore.set(issueKey, [...existing, seeded])
      return HttpResponse.json({ data: attachmentStore.get(issueKey) })
    }

    // E2E 리셋 경로 — X-MSW-Reset-Attachments: true
    if (request.headers.get('X-MSW-Reset-Attachments') === 'true') {
      attachmentStore.set(issueKey, [])
      return HttpResponse.json({ data: [] })
    }

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
// 미리보기용 최소 유효 바이트 생성 헬퍼 (FR-AC-02 C4)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 1×1 투명 PNG 최소 바이트 (base64 → Uint8Array).
 * <img> 태그가 실제로 렌더하려면 유효한 이미지 바이트가 필요하다.
 */
const MINIMAL_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='

function minimalPngBytes(): Uint8Array {
  const binary = atob(MINIMAL_PNG_BASE64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

/**
 * 최소 유효 PDF 바이트 — 1페이지 PDF.
 * <iframe> 또는 브라우저 PDF 뷰어가 로드할 수 있는 최소 구조.
 */
function minimalPdfBytes(): Uint8Array {
  const pdfStr = [
    '%PDF-1.4',
    '1 0 obj<</Type /Catalog /Pages 2 0 R>>endobj',
    '2 0 obj<</Type /Pages /Kids[3 0 R] /Count 1>>endobj',
    '3 0 obj<</Type /Page /Parent 2 0 R /MediaBox[0 0 3 3]>>endobj',
    'xref',
    '0 4',
    '0000000000 65535 f ',
    '0000000009 00000 n ',
    '0000000058 00000 n ',
    '0000000115 00000 n ',
    'trailer<</Size 4 /Root 1 0 R>>',
    'startxref',
    '190',
    '%%EOF',
  ].join('\n')
  return new TextEncoder().encode(pdfStr)
}

/**
 * 최소 MP4 ftyp 박스 바이트.
 * <video> 태그가 src를 로드 시도할 수 있을 정도의 최소 구조.
 * 실제 재생은 불필요 — src 설정 + 로드 시도만 되면 된다.
 */
function minimalMp4Bytes(): Uint8Array {
  // ftyp 박스: size(4) + 'ftyp'(4) + 'isom'(4) + version(4) + 'isom'(4)
  const box = new Uint8Array(20)
  // size = 20 (빅엔디언)
  box[0] = 0x00; box[1] = 0x00; box[2] = 0x00; box[3] = 0x14
  // 'ftyp'
  box[4] = 0x66; box[5] = 0x74; box[6] = 0x79; box[7] = 0x70
  // 'isom'
  box[8] = 0x69; box[9] = 0x73; box[10] = 0x6f; box[11] = 0x6d
  // version = 0
  box[12] = 0x00; box[13] = 0x00; box[14] = 0x00; box[15] = 0x00
  // compatible brand 'isom'
  box[16] = 0x69; box[17] = 0x73; box[18] = 0x6f; box[19] = 0x6d
  return box
}

/**
 * MIME 타입에 따라 미리보기에 적합한 최소 바이트 Blob을 생성한다.
 *
 * - image/*      → 1×1 투명 PNG 유효 바이트
 * - application/pdf → 최소 유효 PDF
 * - video/mp4    → 최소 ftyp 박스
 * - 그 외        → 기존 'mock-content' 텍스트 (비미리보기 타입, 불변 유지)
 */
function buildResponseBlob(contentType: string): Blob {
  if (contentType.startsWith('image/')) {
    return new Blob([minimalPngBytes()], { type: contentType })
  }
  if (contentType === 'application/pdf') {
    return new Blob([minimalPdfBytes()], { type: contentType })
  }
  if (contentType === 'video/mp4') {
    return new Blob([minimalMp4Bytes()], { type: contentType })
  }
  // 그 외 (zip, text/plain, octet-stream 등) — 기존 동작 유지 (회귀 0)
  return new Blob(['mock-content'], { type: contentType })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/issues/:key/attachments/:attachmentId — 다운로드
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 첨부 파일 다운로드 핸들러.
 * 성공 → 200 Blob (미리보기 가능 타입은 유효 최소 바이트, 그 외 mock-content)
 * 미존재 → 404
 *
 * FR-AC-02 C4: image/png·jpeg·gif·webp → 1×1 PNG, application/pdf → 최소 PDF,
 * video/mp4 → ftyp 박스. 비미리보기 타입 경로는 기존과 동일(회귀 0).
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

    return new HttpResponse(buildResponseBlob(att.contentType), {
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
