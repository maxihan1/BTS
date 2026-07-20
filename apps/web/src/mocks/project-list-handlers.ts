// 프로젝트 목록 MSW 핸들러 — 하위호환 shim (FR-UX-06 PR12 Task 1에서 신설, FR-PJ PR-5 후속에서
// project-handlers.ts로 정본 통합됨. project-tree e2e shadow 회귀 수정)
//
// `GET /api/v1/projects`의 실제 응답은 이제 `project-handlers.ts`의 stateful 핸들러가 전담한다
// (활성 시드 ATLAS·MIDDLE·ZETA + 아카이브 시드 NOVA, `archived` 쿼리 파라미터 필터링 지원).
// 이 파일은 기존 vitest 4파일(use-projects.test.tsx·Sidebar.test.tsx·ProjectTree.test.tsx·
// navigation-contract.test.tsx)이 import하는 `projectListHandlers`/`projectListFixtures` 심볼이
// 깨지지 않도록 정본을 재노출(re-export)하는 얇은 shim이다. 신규 코드는 project-handlers.ts를
// 직접 사용할 것 — 이 파일에 신규 로직을 추가하지 말 것.
import { listProjectsHandler } from './project-handlers'

/** 프로젝트 목록 fixture 항목 형태 — 하위호환용 id/key/name 3필드(archived 제외) */
interface ProjectListFixture {
  id: string
  key: string
  name: string
}

/**
 * project-handlers.ts 활성 시드(ATLAS·MIDDLE·ZETA) 재노출 — `GET /api/v1/projects`
 * 기본 응답(archived 생략 시 활성만)과 동일 구성이다. id/key/name은 정본과 1:1 동일하게 유지한다.
 */
export const projectListFixtures: ProjectListFixture[] = [
  { id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890', key: 'ATLAS', name: 'Atlas 프로젝트' },
  { id: 'c3d4e5f6-a7b8-4012-9def-123456789012', key: 'MIDDLE', name: 'Middle 프로젝트' },
  { id: 'b2c3d4e5-f6a7-4901-bcde-f12345678901', key: 'ZETA', name: 'Zeta 프로젝트' },
]

/** `GET /api/v1/projects` 정본 핸들러 재노출 — project-handlers.ts와 동일 stateful 동작 */
export const projectListHandlers = [listProjectsHandler]
