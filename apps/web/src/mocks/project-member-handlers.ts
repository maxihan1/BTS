// FR-PM-01 프로젝트 멤버 MSW 핸들러 — stateful CRUD + 마지막 admin 보호 (GET/POST/PATCH/DELETE)
import { http, HttpResponse } from 'msw'
import type { ProjectMember, ProjectRole } from '../api/project-members.types'
import {
  atlasInitialMembers,
  btsInitialMembers,
  KNOWN_PROJECT_KEYS,
  makeMember,
} from './project-member-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// Stateful 저장소 — 프로젝트 키별 멤버 목록 Map
// 각 테스트 시작 시 X-MSW-Reset-Members: true 헤더로 초기화한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트별 UUID 대조 Map — 프로젝트 키 → projectId */
const PROJECT_ID_MAP: Readonly<Record<string, string>> = {
  ATLAS: 'project-atlas-uuid',
  BTS: 'project-bts-uuid',
}

/** 멤버 상태를 초기 fixture로 리셋하는 헬퍼 */
function buildInitialState(): Map<string, ProjectMember[]> {
  return new Map<string, ProjectMember[]>([
    ['ATLAS', atlasInitialMembers.map((m) => ({ ...m }))],
    ['BTS', btsInitialMembers.map((m) => ({ ...m }))],
  ])
}

let memberStore: Map<string, ProjectMember[]> = buildInitialState()

/** 저장소를 fixture 초기 상태로 완전히 재구성한다 */
function resetMemberStore(): void {
  memberStore = buildInitialState()
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function projectNotFound(): HttpResponse<{ error: string }> {
  return HttpResponse.json({ error: 'project_not_found' }, { status: 404 })
}

function memberNotFound(): HttpResponse<{ error: string }> {
  return HttpResponse.json({ error: 'member_not_found' }, { status: 404 })
}

function lastAdminProtected(): HttpResponse<{ error: string }> {
  return HttpResponse.json({ error: 'last_admin_protected' }, { status: 409 })
}

/** admin이 1명 이하이고 대상 userId가 그 admin인지 확인한다 */
function isLastAdmin(members: ProjectMember[], targetUserId: string): boolean {
  const adminList = members.filter((m) => m.role === 'PROJECT_ADMIN')
  return adminList.length <= 1 && adminList.some((m) => m.userId === targetUserId)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/members
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 목록 조회.
 *
 * X-MSW-Reset-Members: true 헤더 포함 시 상태를 초기화한 뒤 목록을 반환한다.
 * 에러 분기 순서: 미존재 프로젝트 → 404.
 */
const listMembersHandler = http.get(
  '/api/v1/projects/:projectKey/members',
  ({ request, params }) => {
    if (request.headers.get('X-MSW-Reset-Members') === 'true') {
      resetMemberStore()
    }

    const projectKey = params['projectKey'] as string
    if (!KNOWN_PROJECT_KEYS.has(projectKey)) {
      return projectNotFound()
    }

    const members = memberStore.get(projectKey) ?? []
    return HttpResponse.json({ members })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/projects/:projectKey/members
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 멤버를 추가한다.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 미존재 프로젝트 → 404 project_not_found
 * 2. 이미 멤버 → 409 membership_already_exists
 * 성공 → 201 + 단건 ProjectMember
 */
const addMemberHandler = http.post(
  '/api/v1/projects/:projectKey/members',
  async ({ request, params }) => {
    const projectKey = params['projectKey'] as string
    if (!KNOWN_PROJECT_KEYS.has(projectKey)) {
      return projectNotFound()
    }

    const body = await request.json() as { userId: string; role: string }
    const members = memberStore.get(projectKey) ?? []

    if (members.some((m) => m.userId === body.userId)) {
      return HttpResponse.json({ error: 'membership_already_exists' }, { status: 409 })
    }

    const now = new Date().toISOString()
    const newMember = makeMember({
      projectId: PROJECT_ID_MAP[projectKey] ?? `project-${projectKey.toLowerCase()}-uuid`,
      userId: body.userId,
      role: body.role as ProjectRole,
      createdAt: now,
      updatedAt: now,
      displayName: null,
      username: null,
    })

    memberStore.set(projectKey, [...members, newMember])
    return HttpResponse.json(newMember, { status: 201 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectKey/members/:userId
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 멤버 역할을 변경한다.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 미존재 프로젝트 → 404 project_not_found
 * 2. 미존재 멤버 → 404 member_not_found
 * 3. 마지막 admin 강등 → 409 last_admin_protected
 * 성공 → 200 + 변경된 ProjectMember
 */
const changeRoleHandler = http.patch(
  '/api/v1/projects/:projectKey/members/:userId',
  async ({ request, params }) => {
    const projectKey = params['projectKey'] as string
    const userId = params['userId'] as string

    if (!KNOWN_PROJECT_KEYS.has(projectKey)) {
      return projectNotFound()
    }

    const members = memberStore.get(projectKey) ?? []
    const target = members.find((m) => m.userId === userId)
    if (target === undefined) {
      return memberNotFound()
    }

    const body = await request.json() as { role: string }
    const newRole = body.role as ProjectRole

    if (newRole !== 'PROJECT_ADMIN' && isLastAdmin(members, userId)) {
      return lastAdminProtected()
    }

    const updated: ProjectMember = {
      ...target,
      role: newRole,
      updatedAt: new Date().toISOString(),
    }

    memberStore.set(
      projectKey,
      members.map((m) => (m.userId === userId ? updated : m)),
    )

    return HttpResponse.json(updated)
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/projects/:projectKey/members/:userId
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에서 멤버를 제거한다.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 미존재 프로젝트 → 404 project_not_found
 * 2. 미존재 멤버 → 404 member_not_found
 * 3. 마지막 admin 삭제 → 409 last_admin_protected
 * 성공 → 204 No Content
 */
const removeMemberHandler = http.delete(
  '/api/v1/projects/:projectKey/members/:userId',
  ({ params }) => {
    const projectKey = params['projectKey'] as string
    const userId = params['userId'] as string

    if (!KNOWN_PROJECT_KEYS.has(projectKey)) {
      return projectNotFound()
    }

    const members = memberStore.get(projectKey) ?? []
    const target = members.find((m) => m.userId === userId)
    if (target === undefined) {
      return memberNotFound()
    }

    if (isLastAdmin(members, userId)) {
      return lastAdminProtected()
    }

    memberStore.set(
      projectKey,
      members.filter((m) => m.userId !== userId),
    )

    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 멤버 CRUD MSW 핸들러 배열 */
export const projectMemberHandlers = [
  listMembersHandler,
  addMemberHandler,
  changeRoleHandler,
  removeMemberHandler,
]
