// 첨부 파일(issue-tracking BC) REST API 클라이언트 — CRUD 4함수 + Zod 스키마
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 업로드 허용 최대 파일 크기 — 100MB (백엔드 MinIO 설정과 동일) */
export const MAX_ATTACHMENT_BYTES = 100 * 1024 * 1024

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 AttachmentResponse DTO와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 첨부 파일 응답 Zod 스키마.
 * 백엔드 IssueAttachmentController 응답 DTO와 1:1 대응.
 * - id: UUID (첨부 고유 식별자)
 * - filename: 원본 파일명 (UTF-8, 한글 포함)
 * - contentType: MIME 타입
 * - sizeBytes: 파일 크기 (바이트, Long → number)
 * - uploadedBy: 업로드한 사용자 UUID
 * - createdAt: 업로드 시각 (ISO 8601)
 */
export const attachmentResponseSchema = z.object({
  id: z.string().uuid(),
  filename: z.string().min(1),
  contentType: z.string().min(1),
  sizeBytes: z.number().int().nonnegative(),
  uploadedBy: z.string().uuid(),
  createdAt: z.string().min(1),
})

/** 첨부 파일 응답 타입 — Zod 스키마에서 추론 (interface 중복 정의 금지) */
export type AttachmentResponse = z.infer<typeof attachmentResponseSchema>

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈의 첨부 파일 목록을 조회한다.
 *
 * GET /api/v1/issues/{key}/attachments → 200 { data: AttachmentResponse[] }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns 첨부 파일 목록 — 첨부 없으면 빈 배열
 * @throws ApiError(403) 권한 없음
 * @throws ApiError(404) 이슈 없음
 */
export async function fetchAttachments(key: string): Promise<AttachmentResponse[]> {
  const res = await apiFetch(`/api/v1/issues/${key}/attachments`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(z.array(attachmentResponseSchema)).parse(raw).data
}

/**
 * 이슈에 파일을 첨부한다.
 *
 * POST /api/v1/issues/{key}/attachments
 * multipart/form-data, part명 `file` — 브라우저가 Content-Type boundary 자동 설정.
 * 응답 201 { data: AttachmentResponse }.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param file 업로드할 File 객체
 * @returns 업로드된 첨부 파일 정보
 * @throws ApiError(403) UPDATE 권한 없음
 * @throws ApiError(413) 파일 크기 초과 (100MB)
 */
export async function uploadAttachment(key: string, file: File): Promise<AttachmentResponse> {
  const formData = new FormData()
  // 백엔드 API 계약: part명은 반드시 'file'
  formData.append('file', file)

  const res = await apiFetch(`/api/v1/issues/${key}/attachments`, {
    method: 'POST',
    body: formData,
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(attachmentResponseSchema).parse(raw).data
}

/**
 * 첨부 파일 바이너리를 다운로드한다.
 *
 * GET /api/v1/issues/{key}/attachments/{id}
 * → 200 바이트스트림 (Content-Disposition: attachment)
 *
 * 다운로드 후 triggerBlobDownload(blob, filename)로 브라우저 다운로드 트리거.
 * GET 요청이므로 CSRF 토큰 불필요.
 *
 * @param key 이슈 식별 키
 * @param attachmentId 첨부 파일 UUID
 * @returns 파일 내용을 담은 Blob
 * @throws ApiError(403) VIEW 권한 없음
 * @throws ApiError(404) 첨부 파일 없음
 */
export async function downloadAttachment(key: string, attachmentId: string): Promise<Blob> {
  const res = await apiFetch(`/api/v1/issues/${key}/attachments/${attachmentId}`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return res.blob()
}

/**
 * 첨부 파일을 하드삭제한다 (복구 불가).
 *
 * DELETE /api/v1/issues/{key}/attachments/{id} → 204
 *
 * 하드삭제이므로 UI에서 호출 전 확인 단계 필수.
 *
 * @param key 이슈 식별 키
 * @param attachmentId 첨부 파일 UUID
 * @throws ApiError(403) UPDATE 권한 없음
 * @throws ApiError(404) 첨부 파일 없음
 */
export async function deleteAttachment(key: string, attachmentId: string): Promise<void> {
  const res = await apiFetch(`/api/v1/issues/${key}/attachments/${attachmentId}`, {
    method: 'DELETE',
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
