// 사용자 생성 폼 컴포넌트 — username/email/displayName 입력, 성공 시 임시 비밀번호 1회 표시
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { useCreateUser } from '@/hooks/use-create-user'
import { CreateUserErrorCode } from '@/api/users'
import type { CreateUserResponse } from '@/api/users'
import { ApiError } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → 한글 메시지 매핑
// ─────────────────────────────────────────────────────────────────────────────

/** 최상위 에러 코드 → 한글 메시지 (대문자 스네이크 정본, 소문자 비교 금지) */
const ERROR_CODE_MESSAGES: Record<string, string> = {
  [CreateUserErrorCode.USERNAME_TAKEN]: '이미 사용 중인 사용자 이름입니다.',
}

const FALLBACK_ERROR = '사용자 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.'

// ─────────────────────────────────────────────────────────────────────────────
// mapError — ApiError.body 에서 한글 메시지를 생성한다
// ─────────────────────────────────────────────────────────────────────────────

interface ErrorBody {
  code?: string
}

/**
 * ApiError.body 를 분석해 사용자에게 보여줄 한글 메시지를 반환한다.
 *
 * @param body ApiError 의 body (unknown → 방어적 타입 가드)
 */
function mapError(body: unknown): string {
  if (body === null || typeof body !== 'object') {
    return FALLBACK_ERROR
  }

  const errorBody = body as ErrorBody
  const code = errorBody.code

  if (code !== undefined && code in ERROR_CODE_MESSAGES) {
    const msg = ERROR_CODE_MESSAGES[code]
    return msg ?? FALLBACK_ERROR
  }

  return FALLBACK_ERROR
}

// ─────────────────────────────────────────────────────────────────────────────
// 결과 패널 — 임시 비밀번호 1회 표시
// ─────────────────────────────────────────────────────────────────────────────

interface ResultPanelProps {
  /** 생성된 사용자 정보 */
  result: CreateUserResponse
  /** "추가 생성" 버튼 핸들러 */
  onReset: () => void
}

/**
 * 사용자 생성 성공 결과 패널.
 *
 * 임시 비밀번호는 이 화면에서 1회만 표시된다 (§1.1, DEVELOPMENT.md).
 * localStorage·로그 저장 금지.
 */
function ResultPanel({ result, onReset }: ResultPanelProps): JSX.Element {
  return (
    <div role="status" className="space-y-4">
      <div className="rounded-lg bg-primary/10 p-4 text-sm">
        <p className="font-semibold text-primary">사용자가 생성되었습니다.</p>
      </div>

      <div className="space-y-3 rounded-lg border p-4">
        {/* 생성된 username */}
        <div className="space-y-1">
          <p className="text-xs text-muted-foreground">사용자 이름</p>
          <p className="font-mono text-sm font-medium" aria-label="생성된 사용자 이름">
            {result.username}
          </p>
        </div>

        {/* 임시 비밀번호 — 1회 표시, 복사 안내 */}
        <div className="space-y-1">
          <p className="text-xs text-muted-foreground">임시 비밀번호</p>
          <p
            className="font-mono text-sm font-medium"
            aria-label="임시 비밀번호"
            data-testid="temporary-password"
          >
            {result.temporaryPassword}
          </p>
          <p className="text-xs text-destructive">
            이 비밀번호는 다시 표시되지 않습니다. 지금 복사하여 사용자에게 전달하세요.
          </p>
        </div>
      </div>

      <Button type="button" variant="outline" onClick={onReset}>
        추가 생성
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CreateUserForm — 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 생성 폼 컴포넌트.
 *
 * - username / displayName(필수) + email(선택) 입력 필드
 * - 클라이언트 검증: required(빈 값 차단)
 * - 에러 매핑: `mapError`로 한글 메시지 생성, `aria-live` 영역으로 표시
 * - 성공: 임시 비밀번호 포함 결과 패널 + "추가 생성" 버튼 (폼 초기화)
 * - 제출 중(isPending): 버튼 비활성, 중복 제출 차단
 *
 * @returns 사용자 생성 폼 JSX
 */
export function CreateUserForm(): JSX.Element {
  const { mutate, isPending, isSuccess, data, error, reset } = useCreateUser()

  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [usernameError, setUsernameError] = useState<string | null>(null)
  const [displayNameError, setDisplayNameError] = useState<string | null>(null)

  /** 서버 에러 메시지 — ApiError에서 mapError로 생성 */
  const serverError: string | null =
    error instanceof ApiError ? mapError(error.body) : null

  /** "추가 생성" 클릭 시 모든 상태 초기화 */
  function handleReset(): void {
    setUsername('')
    setDisplayName('')
    setEmail('')
    setUsernameError(null)
    setDisplayNameError(null)
    reset()
  }

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    setUsernameError(null)
    setDisplayNameError(null)
    reset()

    // 클라이언트 검증 — username
    if (username.trim() === '') {
      setUsernameError('사용자 이름은 필수입니다.')
      return
    }

    // 클라이언트 검증 — displayName
    if (displayName.trim() === '') {
      setDisplayNameError('표시 이름은 필수입니다.')
      return
    }

    mutate({
      username: username.trim(),
      displayName: displayName.trim(),
      email: email.trim() !== '' ? email.trim() : undefined,
    })
  }

  // 성공 시 결과 패널로 교체
  if (isSuccess && data !== undefined) {
    return <ResultPanel result={data} onReset={handleReset} />
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      {/* 서버 에러 영역 — aria-live로 스크린리더 전달 */}
      {serverError !== null && (
        <div
          role="alert"
          aria-live="polite"
          className="mb-4 rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
        >
          <p>{serverError}</p>
        </div>
      )}

      <div className="space-y-4">
        {/* 사용자 이름 — 필수 */}
        <div className="space-y-1.5">
          <Label htmlFor="create-user-username">사용자 이름</Label>
          <Input
            id="create-user-username"
            type="text"
            autoComplete="username"
            value={username}
            onChange={(e) => { setUsername(e.target.value) }}
            disabled={isPending}
            aria-required="true"
            aria-invalid={usernameError !== null}
            aria-describedby={usernameError !== null ? 'create-user-username-error' : undefined}
          />
          {usernameError !== null && (
            <p
              id="create-user-username-error"
              role="alert"
              className="text-xs text-destructive"
            >
              {usernameError}
            </p>
          )}
        </div>

        {/* 표시 이름 — 필수 */}
        <div className="space-y-1.5">
          <Label htmlFor="create-user-display-name">표시 이름</Label>
          <Input
            id="create-user-display-name"
            type="text"
            autoComplete="name"
            value={displayName}
            onChange={(e) => { setDisplayName(e.target.value) }}
            disabled={isPending}
            aria-required="true"
            aria-invalid={displayNameError !== null}
            aria-describedby={displayNameError !== null ? 'create-user-display-name-error' : undefined}
          />
          {displayNameError !== null && (
            <p
              id="create-user-display-name-error"
              role="alert"
              className="text-xs text-destructive"
            >
              {displayNameError}
            </p>
          )}
        </div>

        {/* 이메일 — 선택 */}
        <div className="space-y-1.5">
          <Label htmlFor="create-user-email">이메일 (선택)</Label>
          <Input
            id="create-user-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => { setEmail(e.target.value) }}
            disabled={isPending}
          />
        </div>
      </div>

      <div className="mt-6">
        <Button type="submit" disabled={isPending} className="w-full sm:w-auto">
          {isPending ? '생성 중...' : '사용자 생성'}
        </Button>
      </div>
    </form>
  )
}
