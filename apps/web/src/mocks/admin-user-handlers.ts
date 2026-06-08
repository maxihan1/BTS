// 사용자 생성 MSW mock 핸들러 — POST /api/v1/users (SYSTEM_ADMIN 전용, Task 8 최종 소유)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// stateful 사용자 저장소 — username 중복 검사에 사용
// Task 8 소유. E2E Task 9는 시나리오 토글만 추가한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 이미 존재하는 username 집합 (seed 값 포함) */
const existingUsernames = new Set<string>(['alice', 'bob', 'carol', 'dave', 'eve'])

/** 생성된 사용자 카운터 — UUID 대신 단순 시퀀스 (테스트 결정성) */
let userCounter = 100

/**
 * 핸들러 상태 초기화 — 테스트 격리용.
 * `beforeEach` 또는 테스트 setup에서 호출.
 */
export function resetAdminUserHandlerState(): void {
  existingUsernames.clear()
  for (const u of ['alice', 'bob', 'carol', 'dave', 'eve']) {
    existingUsernames.add(u)
  }
  userCounter = 100
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/users — 사용자 생성
// 백엔드 UsersController.createUser 와 동일한 분기 순서.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/users
 *
 * 분기 순서 (백엔드 UsersController 일치).
 * 1. username 누락(@NotBlank) → 400
 * 2. displayName 누락(@NotBlank) → 400
 * 3. username 중복 → 409 USERNAME_TAKEN
 * 4. 정상 → 201 { id, username, temporaryPassword }
 *
 * temporaryPassword: "Tmp{username}01!" 고정 패턴 (테스트 결정성).
 */
const createUserHandler = http.post('/api/v1/users', async ({ request }) => {
  const body = await request.json() as {
    username?: string
    displayName?: string
    email?: string
  }

  const username = (body.username ?? '').trim()
  const displayName = (body.displayName ?? '').trim()

  // 분기 1: username 빈 값
  if (username === '') {
    return HttpResponse.json(
      { code: 'VALIDATION_ERROR', message: 'username은 필수입니다.' },
      { status: 400 },
    )
  }

  // 분기 2: displayName 빈 값
  if (displayName === '') {
    return HttpResponse.json(
      { code: 'VALIDATION_ERROR', message: 'displayName은 필수입니다.' },
      { status: 400 },
    )
  }

  // 분기 3: username 중복
  if (existingUsernames.has(username)) {
    return HttpResponse.json(
      { code: 'USERNAME_TAKEN', message: '이미 사용 중인 사용자 이름입니다.' },
      { status: 409 },
    )
  }

  // 분기 4: 정상 — 사용자 생성
  existingUsernames.add(username)
  userCounter++
  // RFC4122 v4 형식 UUID 생성 (Zod v4 uuid 엄격 검증 통과)
  // 형식: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx (y는 8,9,a,b 중 하나)
  const hex = userCounter.toString(16).padStart(8, '0')
  const id = `${hex}-0000-4000-a000-${String(userCounter).padStart(12, '0')}`
  const temporaryPassword = `Tmp${username}01!`

  return HttpResponse.json(
    { id, username, temporaryPassword },
    { status: 201 },
  )
})

export const adminUserHandlers = [createUserHandler]
