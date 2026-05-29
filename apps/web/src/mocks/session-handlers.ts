// FR-AU-09 MSW 세션 관리 mock 핸들러 — GET /sessions + DELETE /sessions/:sid (stateful)
import { http, HttpResponse } from 'msw'
import type { Session } from '../api/sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture 데이터 — alice 세션 2개 (현재 + 다른 기기)
// UUID 값은 e2e/fixtures/session-fixtures.ts 의 값과 동일해야 한다 (단일 진실 출처 불가 —
// src→e2e 역방향 import 는 tsconfig.app.json include:"src" 위반이므로 여기서 정의).
// ─────────────────────────────────────────────────────────────────────────────

// Zod uuid() 검증 통과 조건: M 자리 [1-8], N 자리 [89abAB].
// e2e/fixtures/session-fixtures.ts 와 동일한 값 — 단일 진실 출처 불가
// (src→e2e 역방향 import 는 tsconfig.app.json include:"src" 위반이므로 여기서 별도 정의).
const ALICE_CURRENT_SID = '11111111-1111-1111-8111-111111111111'
const ALICE_OTHER_SID = '22222222-2222-2222-8222-222222222222'

const aliceCurrentSession: Session = {
  sid: ALICE_CURRENT_SID,
  providerId: 'local',
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/124',
  ipAddress: '127.0.0.1',
  lastSeenAt: '2026-05-29T10:00:00Z',
  createdAt: '2026-05-29T09:00:00Z',
  current: true,
}

const aliceOtherSession: Session = {
  sid: ALICE_OTHER_SID,
  providerId: 'local',
  userAgent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/123',
  ipAddress: '192.168.1.100',
  lastSeenAt: '2026-05-28T18:00:00Z',
  createdAt: '2026-05-28T17:00:00Z',
  current: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// Stateful 상태 — 강제 종료된 sid 집합
// 각 E2E 테스트 시작 시 X-MSW-Reset-Sessions: true 헤더를 포함한 요청으로 초기화한다.
// ─────────────────────────────────────────────────────────────────────────────

const revokedSids = new Set<string>()

/**
 * GET /api/v1/auth/sessions
 *
 * 응답: `{ sessions: [...] }` — revokedSids 에 없는 세션만 반환.
 * X-MSW-Reset-Sessions: true 헤더 포함 시 상태 초기화 후 전체 목록 반환.
 */
const listSessionsHandler = http.get('/api/v1/auth/sessions', ({ request }) => {
  if (request.headers.get('X-MSW-Reset-Sessions') === 'true') {
    revokedSids.clear()
  }

  const allSessions = [aliceCurrentSession, aliceOtherSession]
  const activeSessions = allSessions.filter((s) => !revokedSids.has(s.sid))

  return HttpResponse.json({ sessions: activeSessions })
})

/**
 * DELETE /api/v1/auth/sessions/:sid
 *
 * 백엔드 AuthController.revokeSession 과 동일한 분기 순서 (정보 비노출 시맨틱 일치):
 * - 본인 소유 아님(타인/미존재) → 404 IDOR 방어 (먼저)
 * - 본인 현재 세션 → 409 `cannot_revoke_current_session`
 * - 본인 다른 세션 → 204 + revokedSids 에 등록
 */
const revokeSessionHandler = http.delete('/api/v1/auth/sessions/:sid', ({ params }) => {
  const sid = params['sid'] as string
  const ownSids = [ALICE_CURRENT_SID, ALICE_OTHER_SID]

  // 본인 소유가 아니면 항상 404 먼저 — 타인 세션 존재 여부 비노출 (백엔드와 동일 순서)
  if (!ownSids.includes(sid)) {
    return HttpResponse.json({ error: 'not_found' }, { status: 404 })
  }

  if (sid === ALICE_CURRENT_SID) {
    return HttpResponse.json(
      { error: 'cannot_revoke_current_session' },
      { status: 409 },
    )
  }

  // ALICE_OTHER_SID — 본인 다른 세션
  revokedSids.add(sid)
  return new HttpResponse(null, { status: 204 })
})

export const sessionHandlers = [listSessionsHandler, revokeSessionHandler]
