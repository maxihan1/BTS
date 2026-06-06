// 이슈 보안등급 MSW mock 핸들러 — GET 등급목록 (FR-PM-06 PR-B)
import { http, HttpResponse } from 'msw'
import type { SecurityLevel } from '@/api/security-levels'

// ─────────────────────────────────────────────────────────────────────────────
// 보안등급 fixture 데이터 (Zod v4 UUID 형식 — 3그룹 4 시작, 4그룹 8~b)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ATLAS 프로젝트 보안등급 fixture 목록.
 * UUID는 Zod v4 RFC4122 v4 형식 준수:
 *   - 3번째 그룹 앞 자리: 4 (version)
 *   - 4번째 그룹 앞 자리: 8~b (variant)
 */
export const atlasSecurityLevels: SecurityLevel[] = [
  {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Public',
    description: '전체 공개 — 인증되지 않은 사용자도 볼 수 있음',
    isDefault: true,
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    name: 'Internal',
    description: '내부 구성원만 열람 가능',
    isDefault: false,
  },
  {
    id: '33333333-3333-4333-8333-333333333333',
    name: 'Restricted',
    description: '지정된 그룹만 열람 가능',
    isDefault: false,
  },
]

/** 편의 접근자 — spec에서 fixture ID를 하드코딩하지 않도록 */
export const securityLevelFixtures = {
  public: atlasSecurityLevels[0]!,
  internal: atlasSecurityLevels[1]!,
  restricted: atlasSecurityLevels[2]!,
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/projects/:projectKey/issue-security-scheme/levels
 * 프로젝트 보안등급 목록 반환.
 * ATLAS 프로젝트: 3개 fixture 목록.
 * 그 외 프로젝트: 빈 배열 (스킴 미적용 시뮬).
 */
const getSecurityLevelsHandler = http.get(
  '/api/v1/projects/:projectKey/issue-security-scheme/levels',
  ({ params }) => {
    const projectKey = params['projectKey'] as string
    if (projectKey === 'ATLAS') {
      return HttpResponse.json({ levels: atlasSecurityLevels })
    }
    return HttpResponse.json({ levels: [] })
  },
)

export const securityLevelHandlers = [getSecurityLevelsHandler]
