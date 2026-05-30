// 비밀번호 변경 MSW 가짜 핸들러 — POST /api/v1/users/me/password (백엔드 EC-7 분기순서 일치)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// Seed 상수 — 테스트 격리 보장을 위해 무상태 판정(요청 body만으로 분기)
// 변경 성공 후에도 다음 요청은 항상 이 상수와 비교한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 현재 비밀번호 seed — PasswordController.kt 테스트용 기본값 */
const SEED_CURRENT_PASSWORD = 'CurrentPass123!'

/** 비밀번호 최소 길이 — PasswordPolicy.kt MIN_LENGTH = 12 */
const MIN_PASSWORD_LENGTH = 12

/** 비밀번호 복잡도 최소 문자 종류 수 — PasswordPolicy.kt MIN_CHARACTER_CLASSES = 3 */
const MIN_CHARACTER_CLASSES = 3

// ─────────────────────────────────────────────────────────────────────────────
// 정책 판정 헬퍼 — PasswordPolicy.kt 의 간이 미러 (테스트 결정성 전용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 비밀번호 정책 위반 종류를 반환한다.
 *
 * - MIN_LENGTH: 12자 미만
 * - COMPLEXITY: 문자 종류(대문자·소문자·숫자·특수) 중 3종 미만
 *
 * 위반 없으면 빈 배열 반환.
 */
function checkPasswordPolicy(password: string): string[] {
  const violations: string[] = []

  if (password.length < MIN_PASSWORD_LENGTH) {
    violations.push('MIN_LENGTH')
  }

  let classCount = 0
  if (/[A-Z]/.test(password)) classCount++
  if (/[a-z]/.test(password)) classCount++
  if (/[0-9]/.test(password)) classCount++
  if (/[^A-Za-z0-9]/.test(password)) classCount++

  if (classCount < MIN_CHARACTER_CLASSES) {
    violations.push('COMPLEXITY')
  }

  return violations
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — EC-7 분기순서 (백엔드 PasswordController.kt 와 일치)
// EC-7 분기:
//   1. Bean Validation(@NotBlank) — 프론트 클라이언트단에서 차단, 핸들러 생략
//   2. 새 비밀번호 정책 위반 → 400 POLICY_VIOLATION
//   3. 새 비밀번호 == 현재 비밀번호 → 400 SAME_AS_CURRENT
//   4. 현재 비밀번호 불일치 → 400 CURRENT_PASSWORD_MISMATCH
//   5. 정상 → 200 { changed: true }
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/users/me/password
 *
 * 백엔드 PasswordController.changePassword 와 동일한 분기 순서(EC-7).
 * 무상태 판정 — 요청 body 만으로 분기하여 테스트 격리를 보장한다(review CONCERN 2).
 * seed currentPassword = "CurrentPass123!" 고정 비교.
 */
const changePasswordHandler = http.post('/api/v1/users/me/password', async ({ request }) => {
  const body = await request.json() as { currentPassword?: string; newPassword?: string }
  const currentPassword = body.currentPassword ?? ''
  const newPassword = body.newPassword ?? ''

  // EC-7 분기 2: 새 비밀번호 정책 위반 — 정책 판정이 다른 분기보다 먼저 실행됨
  const violations = checkPasswordPolicy(newPassword)
  if (violations.length > 0) {
    return HttpResponse.json(
      {
        code: 'POLICY_VIOLATION',
        message: '비밀번호가 정책을 위반합니다.',
        violations,
      },
      { status: 400 },
    )
  }

  // EC-7 분기 3: 새 비밀번호 == 현재 비밀번호 (seed 기준)
  if (newPassword === SEED_CURRENT_PASSWORD) {
    return HttpResponse.json(
      {
        code: 'SAME_AS_CURRENT',
        message: '새 비밀번호가 현재 비밀번호와 같습니다.',
      },
      { status: 400 },
    )
  }

  // EC-7 분기 4: 현재 비밀번호 불일치
  if (currentPassword !== SEED_CURRENT_PASSWORD) {
    return HttpResponse.json(
      {
        code: 'CURRENT_PASSWORD_MISMATCH',
        message: '현재 비밀번호가 일치하지 않습니다.',
      },
      { status: 400 },
    )
  }

  // EC-7 분기 5: 정상
  return HttpResponse.json({ changed: true })
})

export const passwordHandlers = [changePasswordHandler]
