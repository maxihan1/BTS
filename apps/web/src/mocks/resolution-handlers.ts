// 결의안 MSW 핸들러 — GET /api/v1/resolutions (5종 표준 seed 반환)
import { http, HttpResponse } from 'msw'

/**
 * 표준 결의안 5종 fixture.
 *
 * UUID는 백엔드 seed와 완전히 동일 (E2E + 실 백엔드 정합).
 * Zod v4 UUID 형식: 3번째 그룹이 4로 시작, 4번째 그룹이 8로 시작.
 */
const standardResolutionFixtures = [
  {
    id: '00000000-0000-4000-8000-000000000001',
    key: 'fixed',
    name: 'Fixed',
    description: null,
    displayOrder: 1,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000002',
    key: 'wontfix',
    name: "Won't Fix",
    description: null,
    displayOrder: 2,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000003',
    key: 'duplicate',
    name: 'Duplicate',
    description: null,
    displayOrder: 3,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000004',
    key: 'cannotreproduce',
    name: 'Cannot Reproduce',
    description: null,
    displayOrder: 4,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000005',
    key: 'done',
    name: 'Done',
    description: null,
    displayOrder: 5,
    isStandard: true,
  },
]

/**
 * 결의안 MSW 핸들러 목록.
 *
 * - GET /api/v1/resolutions — 5종 표준 결의안 반환 (`{ data: [...] }`)
 *
 * 응답 형식: backend의 `DataResponse<T>` 래퍼 (`{ data: T }`)와 일치.
 */
export const resolutionHandlers = [
  /** GET /api/v1/resolutions — 표준 결의안 목록 */
  http.get('/api/v1/resolutions', () => {
    return HttpResponse.json({ data: standardResolutionFixtures })
  }),
]
