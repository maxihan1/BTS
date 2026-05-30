// 비밀번호 변경 폼 컴포넌트 — 현재/새/확인 비밀번호 3 입력, 서버 에러 한글 매핑
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { useChangePassword } from '@/hooks/use-change-password'
import { PasswordChangeErrorCode, PasswordViolation } from '@/api/password'
import { ApiError } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 매핑 상수 테이블 — 대문자 스네이크 코드 → 한글 문구
// 백엔드 message 를 무시하고 프론트 상수에서 생성 (review CONCERN 1).
// 소문자 비교 금지(PR #41 BLOCKER 선례 — 대문자 스네이크 정본).
// ─────────────────────────────────────────────────────────────────────────────

/** violations 배열 항목별 한글 메시지 */
const VIOLATION_MESSAGES: Record<string, string> = {
  [PasswordViolation.MIN_LENGTH]: '비밀번호는 최소 12자 이상이어야 합니다.',
  [PasswordViolation.COMPLEXITY]: '영문 대문자·소문자·숫자·특수문자 중 3종 이상을 포함해야 합니다.',
}

/** 최상위 에러 코드별 한글 메시지 (POLICY_VIOLATION은 violations로 분기) */
const ERROR_CODE_MESSAGES: Record<string, string> = {
  [PasswordChangeErrorCode.CURRENT_PASSWORD_MISMATCH]: '현재 비밀번호가 일치하지 않습니다.',
  [PasswordChangeErrorCode.SAME_AS_CURRENT]: '새 비밀번호가 현재 비밀번호와 같습니다.',
}

/** 알 수 없는 에러 폴백 메시지 */
const FALLBACK_ERROR = '비밀번호 변경에 실패했습니다. 잠시 후 다시 시도해 주세요.'

// ─────────────────────────────────────────────────────────────────────────────
// mapError — ApiError.body 로부터 한글 메시지 배열을 생성한다
// ─────────────────────────────────────────────────────────────────────────────

interface ErrorBody {
  code?: string
  violations?: string[]
}

/**
 * ApiError.body 를 분석해 사용자에게 보여줄 한글 메시지 배열을 반환한다.
 *
 * - POLICY_VIOLATION: violations 항목별 메시지 나열 (body.message 무시)
 * - CURRENT_PASSWORD_MISMATCH / SAME_AS_CURRENT: 상수 테이블 메시지
 * - 그 외 / 알 수 없는 code: 폴백 메시지
 *
 * @param body ApiError 의 body (unknown → 방어적 타입 가드)
 */
function mapError(body: unknown): readonly string[] {
  if (body === null || typeof body !== 'object') {
    return [FALLBACK_ERROR]
  }

  const errorBody = body as ErrorBody
  const code = errorBody.code

  if (code === PasswordChangeErrorCode.POLICY_VIOLATION) {
    const violations = Array.isArray(errorBody.violations) ? errorBody.violations : []
    const messages = violations
      .map((v) => VIOLATION_MESSAGES[v])
      .filter((msg): msg is string => msg !== undefined)

    return messages.length > 0 ? messages : [FALLBACK_ERROR]
  }

  if (code !== undefined && code in ERROR_CODE_MESSAGES) {
    const msg = ERROR_CODE_MESSAGES[code]
    return msg !== undefined ? [msg] : [FALLBACK_ERROR]
  }

  return [FALLBACK_ERROR]
}

// ─────────────────────────────────────────────────────────────────────────────
// ChangePasswordForm — 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 비밀번호 변경 폼 컴포넌트.
 *
 * - 현재 비밀번호 / 새 비밀번호 / 새 비밀번호 확인 3 입력 필드
 * - 클라이언트 검증: required(빈 값 차단) + 새 비번 == 확인 비번 (D1 확정)
 *   정책(12자/3종)은 정적 안내문으로만 표시 — 클라이언트 검증 로직 금지 (drift 차단)
 * - 에러 매핑: `mapError` 로 한글 메시지 생성, `aria-live` 영역으로 표시
 * - 성공: 인라인 메시지 + "다른 기기 세션 로그아웃" 안내 + 필드 초기화
 * - 제출 중(isPending): 버튼 비활성, 중복 제출 차단 (FR-7)
 *
 * @returns 비밀번호 변경 폼 JSX
 */
export function ChangePasswordForm(): JSX.Element {
  const { mutate, isPending, isSuccess, error, reset } = useChangePassword()

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [clientError, setClientError] = useState<string | null>(null)

  /** 서버 에러 메시지 배열 — ApiError 에서 mapError 로 생성 */
  const serverErrors: readonly string[] =
    error instanceof ApiError ? mapError(error.body) : []

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    setClientError(null)
    reset()

    // 클라이언트 검증 1: 빈 필드 차단 (EC-1)
    if (currentPassword === '' || newPassword === '' || confirmPassword === '') {
      return
    }

    // 클라이언트 검증 2: 새 비번 == 확인 비번 (S6)
    if (newPassword !== confirmPassword) {
      setClientError('새 비밀번호가 일치하지 않습니다.')
      return
    }

    mutate(
      { currentPassword, newPassword },
      {
        onSuccess: () => {
          // 성공 후 3 필드 초기화 (S1)
          setCurrentPassword('')
          setNewPassword('')
          setConfirmPassword('')
        },
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      {/* 성공 메시지 영역 (S1, S8) */}
      {isSuccess && (
        <div role="status" className="mb-4 rounded-lg bg-primary/10 p-3 text-sm text-primary">
          <p className="font-medium">비밀번호가 변경되었습니다</p>
          <p className="mt-1 text-xs text-muted-foreground">다른 기기의 세션은 로그아웃되었습니다</p>
        </div>
      )}

      {/* 에러 메시지 영역 — aria-live로 스크린리더 전달 (NFR-3) */}
      {(clientError !== null || serverErrors.length > 0) && (
        <div
          role="alert"
          aria-live="polite"
          className="mb-4 rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
        >
          {clientError !== null && <p>{clientError}</p>}
          {serverErrors.map((msg) => (
            <p key={msg}>{msg}</p>
          ))}
        </div>
      )}

      <div className="space-y-4">
        {/* 현재 비밀번호 */}
        <div className="space-y-1.5">
          <Label htmlFor="current-password">현재 비밀번호</Label>
          <Input
            id="current-password"
            type="password"
            autoComplete="current-password"
            value={currentPassword}
            onChange={(e) => { setCurrentPassword(e.target.value) }}
            disabled={isPending}
          />
        </div>

        {/* 새 비밀번호 */}
        <div className="space-y-1.5">
          <Label htmlFor="new-password">새 비밀번호</Label>
          <Input
            id="new-password"
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => { setNewPassword(e.target.value) }}
            disabled={isPending}
          />
          {/* 정적 정책 안내문 — 항상 표시, 클라이언트 검증 금지 (FR-6, D1) */}
          <p className="text-xs text-muted-foreground">
            12자 이상, 영문 대문자·소문자·숫자·특수문자 중 3종 이상
          </p>
        </div>

        {/* 새 비밀번호 확인 */}
        <div className="space-y-1.5">
          <Label htmlFor="confirm-password">새 비밀번호 확인</Label>
          <Input
            id="confirm-password"
            type="password"
            autoComplete="new-password"
            value={confirmPassword}
            onChange={(e) => { setConfirmPassword(e.target.value) }}
            disabled={isPending}
          />
        </div>
      </div>

      <div className="mt-6">
        <Button type="submit" disabled={isPending} className="w-full sm:w-auto">
          {isPending ? '변경 중...' : '비밀번호 변경'}
        </Button>
      </div>
    </form>
  )
}
