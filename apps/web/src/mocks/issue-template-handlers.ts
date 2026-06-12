// 이슈 본문 템플릿 BC MSW 핸들러 — stateful CRUD + resolve + RFC 7807 ProblemDetail + 시나리오 토글 (FR-TM-01)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 타입
// ─────────────────────────────────────────────────────────────────────────────

interface StoredIssueTemplate {
  /** 템플릿 UUID */
  id: string
  /** 프로젝트 UUID */
  projectId: string
  /** 프로젝트 식별자(id 또는 key) — 저장소 격리 키 */
  projectIdOrKey: string
  /** 이슈 타입 ID (Long → number) */
  issueTypeId: number
  /** 템플릿 이름 */
  name: string
  /** Markdown 본문 */
  content: string
  /** 생성 시각 (ISO 8601) */
  createdAt: string
  /** 수정 시각 (ISO 8601) */
  updatedAt: string
  /** 소프트 삭제 플래그 */
  deleted: boolean
}

/** E2E에서 공유되는 응답 타입 (저장소에서 노출되는 형태) */
export interface IssueTemplateData {
  id: string
  projectId: string
  issueTypeId: number
  name: string
  content: string
  createdAt: string
  updatedAt: string
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키
// ─────────────────────────────────────────────────────────────────────────────

/** 403 강제 플래그 키 — 'true' 세팅 시 모든 변이(POST/PATCH/DELETE) 요청이 403을 반환한다 */
export const LS_KEY_ISSUE_TEMPLATE_403 = 'msw-issue-template-403'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼
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
// 모듈 상태 — resetIssueTemplateStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

let issueTemplateStore: Map<string, StoredIssueTemplate> = new Map()

/** 저장소를 빈 상태로 초기화한다 (테스트 격리용) */
export function resetIssueTemplateStore(): void {
  issueTemplateStore = new Map()
}

/**
 * 이슈 템플릿 저장소에서 projectIdOrKey에 해당하는 활성 템플릿을 반환한다.
 * E2E 파생 동작에서 공유 store 읽기 헬퍼.
 *
 * @param projectIdOrKey 프로젝트 식별자
 * @returns 활성 IssueTemplateData 배열 — createdAt 오름차순
 */
export function getActiveIssueTemplates(projectIdOrKey: string): IssueTemplateData[] {
  return Array.from(issueTemplateStore.values())
    .filter((t) => t.projectIdOrKey === projectIdOrKey && !t.deleted)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    .map(toIssueTemplateData)
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼 — RFC 7807 ProblemDetail 형태
// `message` 필드 절대 금지 — 백엔드는 `detail` 필드를 사용한다
// ─────────────────────────────────────────────────────────────────────────────

interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  errorCode: string
  timestamp: string
}

function problemDetail(
  status: number,
  type: string,
  title: string,
  errorCode: string,
  detail: string,
): HttpResponse<ProblemDetail> {
  return HttpResponse.json<ProblemDetail>(
    {
      type: `https://bts.example.com/problems/${type}`,
      title,
      status,
      detail,
      errorCode,
      timestamp: new Date().toISOString(),
    },
    { status },
  )
}

function issueTemplateNotFound(id: string): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'issue-template-not-found',
    'Issue Template Not Found',
    'ISSUE_TEMPLATE_NOT_FOUND',
    `이슈 템플릿을 찾을 수 없습니다: ${id}`,
  )
}

function accessDenied(): HttpResponse<ProblemDetail> {
  return problemDetail(
    403,
    'issue-template-access-denied',
    'Issue Template Access Denied',
    'ISSUE_TEMPLATE_ACCESS_DENIED',
    '이슈 템플릿을 관리할 권한이 없습니다.',
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// StoredIssueTemplate → IssueTemplateData 변환 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function toIssueTemplateData(stored: StoredIssueTemplate): IssueTemplateData {
  return {
    id: stored.id,
    projectId: stored.projectId,
    issueTypeId: stored.issueTypeId,
    name: stored.name,
    content: stored.content,
    createdAt: stored.createdAt,
    updatedAt: stored.updatedAt,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/issue-templates
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성 이슈 템플릿 목록 조회 — createdAt 오름차순 정렬.
 * 성공 → 200 { data: IssueTemplateData[] }
 *
 * E2E 시나리오 토글 — X-MSW-Seed-IssueTemplates 헤더(encodeURIComponent JSON 배열)가 있으면
 * 해당 프로젝트의 issueTemplateStore를 시드 데이터로 초기화한다.
 * 브라우저 시드 헤더는 테스트 전용이며 프로덕션 API에는 존재하지 않는다.
 */
const listIssueTemplatesHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/issue-templates',
  ({ params, request }) => {
    const projectIdOrKey = params['projectIdOrKey'] as string

    // E2E seed 헤더 처리 — X-MSW-Seed-IssueTemplates: encodeURIComponent(JSON 배열)
    const seedHeader = request.headers.get('X-MSW-Seed-IssueTemplates')
    if (seedHeader !== null) {
      try {
        const seeds = JSON.parse(decodeURIComponent(seedHeader)) as Array<{
          id: string
          projectId?: string
          issueTypeId: number
          name: string
          content: string
          createdAt?: string
          updatedAt?: string
        }>
        // 기존 프로젝트 데이터 제거 후 seed 데이터로 초기화
        for (const [storeKey, template] of issueTemplateStore.entries()) {
          if (template.projectIdOrKey === projectIdOrKey) {
            issueTemplateStore.delete(storeKey)
          }
        }
        const now = new Date().toISOString()
        for (const seed of seeds) {
          const stored: StoredIssueTemplate = {
            id: seed.id,
            projectId: seed.projectId ?? generateUuidV4(),
            projectIdOrKey,
            issueTypeId: seed.issueTypeId,
            name: seed.name,
            content: seed.content,
            createdAt: seed.createdAt ?? now,
            updatedAt: seed.updatedAt ?? now,
            deleted: false,
          }
          issueTemplateStore.set(stored.id, stored)
        }
      } catch {
        // seed 파싱 실패 시 무시하고 기존 데이터 반환
        console.error('[MSW] X-MSW-Seed-IssueTemplates 파싱 실패 — 기존 데이터 유지')
      }
    }

    const items = Array.from(issueTemplateStore.values())
      .filter((t) => t.projectIdOrKey === projectIdOrKey && !t.deleted)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      .map(toIssueTemplateData)

    return HttpResponse.json({ data: items })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/issue-templates/:templateId
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿 단건 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 미존재 또는 소프트 삭제 → 404 ISSUE_TEMPLATE_NOT_FOUND
 * 성공 → 200 { data: IssueTemplateData }
 */
const getIssueTemplateHandler = http.get(
  '/api/v1/projects/:projectIdOrKey/issue-templates/:templateId',
  ({ params }) => {
    const templateId = params['templateId'] as string
    const stored = issueTemplateStore.get(templateId)
    if (stored === undefined || stored.deleted) {
      return issueTemplateNotFound(templateId)
    }
    return HttpResponse.json({ data: toIssueTemplateData(stored) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:projectIdOrKey/issue-templates
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿 생성.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 0. localStorage 플래그(msw-issue-template-403) → 403 ISSUE_TEMPLATE_ACCESS_DENIED (E2E 권한 토글)
 * 1. name 또는 content가 공백 → 422 ISSUE_TEMPLATE_INVALID (@NotBlank 정합)
 * 2. 같은 (project, issueTypeId) 조합 중복 → 409 ISSUE_TEMPLATE_DUPLICATE
 * 성공 → 201 { data: IssueTemplateData }
 */
const createIssueTemplateHandler = http.post(
  '/api/v1/projects/:projectIdOrKey/issue-templates',
  async ({ request, params }) => {
    // (0) 권한 토글 — localStorage 플래그
    const accessFlag = globalThis.localStorage?.getItem(LS_KEY_ISSUE_TEMPLATE_403)
    if (accessFlag === 'true') {
      return accessDenied()
    }

    const projectIdOrKey = params['projectIdOrKey'] as string
    const body = (await request.json()) as {
      issueTypeId: number
      name: string
      content: string
    }

    // (1) 공백 name/content 검증 — @NotBlank 정합
    if (body.name.trim() === '') {
      return problemDetail(
        422,
        'issue-template-invalid',
        'Issue Template Invalid',
        'ISSUE_TEMPLATE_INVALID',
        '템플릿 이름은 공백일 수 없습니다.',
      )
    }
    if (body.content.trim() === '') {
      return problemDetail(
        422,
        'issue-template-invalid',
        'Issue Template Invalid',
        'ISSUE_TEMPLATE_INVALID',
        '템플릿 본문은 공백일 수 없습니다.',
      )
    }

    // (2) 같은 (project, issueTypeId) 중복 확인
    const duplicate = Array.from(issueTemplateStore.values()).find(
      (t) =>
        t.projectIdOrKey === projectIdOrKey &&
        t.issueTypeId === body.issueTypeId &&
        !t.deleted,
    )
    if (duplicate !== undefined) {
      return problemDetail(
        409,
        'issue-template-duplicate',
        'Issue Template Duplicate',
        'ISSUE_TEMPLATE_DUPLICATE',
        `같은 프로젝트·이슈 타입 조합의 템플릿이 이미 존재합니다: issueTypeId=${body.issueTypeId}`,
      )
    }

    const now = new Date().toISOString()
    const newTemplate: StoredIssueTemplate = {
      id: generateUuidV4(),
      projectId: generateUuidV4(),
      projectIdOrKey,
      issueTypeId: body.issueTypeId,
      name: body.name,
      content: body.content,
      createdAt: now,
      updatedAt: now,
      deleted: false,
    }

    issueTemplateStore.set(newTemplate.id, newTemplate)

    return HttpResponse.json({ data: toIssueTemplateData(newTemplate) }, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/issue-templates/:templateId
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿 수정 — name/content 변경 가능.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 0. localStorage 플래그 → 403 ISSUE_TEMPLATE_ACCESS_DENIED
 * 1. 미존재 또는 소프트 삭제 → 404 ISSUE_TEMPLATE_NOT_FOUND
 * 2. name 또는 content가 공백 → 422 ISSUE_TEMPLATE_INVALID
 * 성공 → 200 { data: IssueTemplateData }
 */
const updateIssueTemplateHandler = http.patch(
  '/api/v1/projects/:projectIdOrKey/issue-templates/:templateId',
  async ({ request, params }) => {
    // (0) 권한 토글
    const accessFlag = globalThis.localStorage?.getItem(LS_KEY_ISSUE_TEMPLATE_403)
    if (accessFlag === 'true') {
      return accessDenied()
    }

    const templateId = params['templateId'] as string
    const stored = issueTemplateStore.get(templateId)
    if (stored === undefined || stored.deleted) {
      return issueTemplateNotFound(templateId)
    }

    const body = (await request.json()) as {
      name?: string
      content?: string
    }

    // (2) 공백 name/content 검증
    if (body.name !== undefined && body.name.trim() === '') {
      return problemDetail(
        422,
        'issue-template-invalid',
        'Issue Template Invalid',
        'ISSUE_TEMPLATE_INVALID',
        '템플릿 이름은 공백일 수 없습니다.',
      )
    }
    if (body.content !== undefined && body.content.trim() === '') {
      return problemDetail(
        422,
        'issue-template-invalid',
        'Issue Template Invalid',
        'ISSUE_TEMPLATE_INVALID',
        '템플릿 본문은 공백일 수 없습니다.',
      )
    }

    const updated: StoredIssueTemplate = {
      ...stored,
      name: body.name ?? stored.name,
      content: body.content ?? stored.content,
      updatedAt: new Date().toISOString(),
    }
    issueTemplateStore.set(templateId, updated)

    return HttpResponse.json({ data: toIssueTemplateData(updated) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/projects/:projectIdOrKey/issue-templates/:templateId
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿 소프트 삭제 (deleted=true 마킹).
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 0. localStorage 플래그 → 403 ISSUE_TEMPLATE_ACCESS_DENIED
 * 1. 미존재 또는 이미 소프트 삭제됨 → 404 ISSUE_TEMPLATE_NOT_FOUND
 * 성공 → 204 No Content
 */
const deleteIssueTemplateHandler = http.delete(
  '/api/v1/projects/:projectIdOrKey/issue-templates/:templateId',
  ({ params }) => {
    // (0) 권한 토글
    const accessFlag = globalThis.localStorage?.getItem(LS_KEY_ISSUE_TEMPLATE_403)
    if (accessFlag === 'true') {
      return accessDenied()
    }

    const templateId = params['templateId'] as string
    const stored = issueTemplateStore.get(templateId)
    if (stored === undefined || stored.deleted) {
      return issueTemplateNotFound(templateId)
    }
    issueTemplateStore.set(templateId, { ...stored, deleted: true })
    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 템플릿 BC MSW 핸들러 배열 */
export const issueTemplateHandlers = [
  listIssueTemplatesHandler,
  getIssueTemplateHandler,
  createIssueTemplateHandler,
  updateIssueTemplateHandler,
  deleteIssueTemplateHandler,
]
