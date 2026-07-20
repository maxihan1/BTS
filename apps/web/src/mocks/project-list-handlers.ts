// 프로젝트 목록 MSW 핸들러 — GET /api/v1/projects bare list, name 오름차순 반환 (FR-UX-06 PR12 Task 1)
import { http, HttpResponse } from 'msw'

/** 프로젝트 목록 fixture 항목 형태 — backend ProjectResponse 3필드(id/key/name)와 1:1 */
interface ProjectListFixture {
  id: string
  key: string
  name: string
}

/**
 * 프로젝트 목록 fixture 3건 — name 순서를 일부러 뒤섞어 정의한다.
 * 실 백엔드(ProjectQueryController)가 name 오름차순으로 정렬해 응답하므로,
 * 아래 핸들러도 동일하게 정렬해 반환해 그 계약을 재현한다.
 */
export const projectListFixtures: ProjectListFixture[] = [
  { id: 'b2c3d4e5-f6a7-4901-bcde-f12345678901', key: 'ZETA', name: 'Zeta 프로젝트' },
  { id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890', key: 'ATLAS', name: 'Atlas 프로젝트' },
  { id: 'c3d4e5f6-a7b8-4012-9def-123456789012', key: 'MIDDLE', name: 'Middle 프로젝트' },
]

/**
 * GET /api/v1/projects — 로그인 사용자가 접근 가능한 프로젝트 목록.
 *
 * `projectListFixtures`를 name 오름차순으로 정렬해 `{ data: [...] }` 형태로 반환한다.
 * 하위리소스 경로(`/api/v1/projects/:projectIdOrKey/...`)와 겹치지 않는 정확한 bare 경로만 매칭한다.
 */
const listProjectsHandler = http.get('/api/v1/projects', () => {
  const sorted = [...projectListFixtures].sort((a, b) => a.name.localeCompare(b.name))
  return HttpResponse.json({ data: sorted })
})

/** 프로젝트 목록 BC MSW 핸들러 배열 */
export const projectListHandlers = [listProjectsHandler]
