// WebAuthn 보안 키 설정 섹션 — 목록 조회·키 등록·키 삭제·FR-8 claimRefresh 담당
import type { JSX, FormEvent } from 'react'
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { browserSupportsWebAuthn } from '@simplewebauthn/browser'
import { registerSecurityKey, deleteWebauthnKey, listWebauthnKeys } from '@/api/webauthn'
import type { WebauthnKey } from '@/api/schemas'
import { ApiError, refreshSession } from '@/api/client'
import { extractErrorCode } from '@/lib/extract-error-code'
import { mfaStrings, mfaErrorMessage } from '@/i18n/ko'
import { useAuthUser } from '@/auth/authStore'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트 — 키 목록 항목
// ─────────────────────────────────────────────────────────────────────────────

interface KeyListItemProps {
  readonly securityKey: WebauthnKey
  /** 삭제 확인 중 여부 — true이면 삭제 버튼을 숨긴다 */
  readonly isConfirming: boolean
  readonly onDeleteClick: (id: string) => void
}

/**
 * 보안 키 목록의 단일 항목.
 * 이름·등록일·마지막 사용일과 삭제 버튼을 포함한다.
 */
function KeyListItem({ securityKey, isConfirming, onDeleteClick }: KeyListItemProps): JSX.Element {
  const displayName = securityKey.name ?? `키 (${securityKey.id.slice(0, 8)}…)`
  const lastUsed = securityKey.lastUsedAt !== null
    ? securityKey.lastUsedAt
    : mfaStrings.webauthnLastUsedNever

  return (
    <li className="flex items-center justify-between rounded-lg border p-3">
      <div className="space-y-0.5">
        <p className="text-sm font-medium">{displayName}</p>
        <p className="text-xs text-muted-foreground">
          {mfaStrings.webauthnLastUsedLabel}:{' '}
          <span>{lastUsed}</span>
        </p>
      </div>
      {/* 삭제 확인 박스가 열려 있으면 버튼을 숨겨 중복 "삭제" 버튼을 방지한다 */}
      {!isConfirming && (
        <Button
          variant="destructive"
          size="sm"
          onClick={() => { onDeleteClick(securityKey.id) }}
        >
          {mfaStrings.webauthnDeleteButton}
        </Button>
      )}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트 — 삭제 인라인 확인 박스
// ─────────────────────────────────────────────────────────────────────────────

interface DeleteConfirmBoxProps {
  readonly isPending: boolean
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/**
 * 보안 키 삭제 전 인라인 확인 박스.
 * BackupCodesSection의 RegenerateConfirmBox와 동형 구조.
 */
function DeleteConfirmBox({ isPending, onConfirm, onCancel }: DeleteConfirmBoxProps): JSX.Element {
  return (
    <div className="space-y-3 rounded-lg border border-destructive/20 bg-destructive/5 p-4">
      <p className="text-sm font-medium">{mfaStrings.webauthnDeleteConfirmTitle}</p>
      <p className="text-sm text-muted-foreground">{mfaStrings.webauthnDeleteConfirmBody}</p>
      <div className="flex gap-2">
        <Button
          variant="destructive"
          size="sm"
          disabled={isPending}
          onClick={onConfirm}
        >
          {mfaStrings.webauthnDeleteConfirmButton}
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={isPending}
          onClick={onCancel}
        >
          {mfaStrings.webauthnDeleteCancelButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트 — 등록 폼
// ─────────────────────────────────────────────────────────────────────────────

interface RegisterFormProps {
  readonly isPending: boolean
  readonly errorMessage: string | null
  readonly onSubmit: (name: string) => void
  readonly onCancel: () => void
}

/**
 * 보안 키 별칭 입력 및 등록 제출 폼.
 * 브라우저 안내 메시지와 별칭 입력 필드, 등록/취소 버튼을 포함한다.
 */
function RegisterForm({ isPending, errorMessage, onSubmit, onCancel }: RegisterFormProps): JSX.Element {
  const [name, setName] = useState('')

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    onSubmit(name.trim())
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-4 rounded-lg border p-4">
      <p className="text-sm text-muted-foreground">{mfaStrings.webauthnRegisteringGuide}</p>

      {errorMessage !== null && (
        <div
          role="alert"
          aria-live="polite"
          className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
        >
          {errorMessage}
        </div>
      )}

      <div className="space-y-1.5">
        <Label htmlFor="webauthn-key-name">{mfaStrings.webauthnNameLabel}</Label>
        <Input
          id="webauthn-key-name"
          type="text"
          placeholder={mfaStrings.webauthnNamePlaceholder}
          value={name}
          onChange={(e) => { setName(e.target.value) }}
          disabled={isPending}
        />
      </div>

      <div className="flex gap-2">
        <Button type="submit" disabled={isPending}>
          {mfaStrings.webauthnAddButton}
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={isPending}
          onClick={onCancel}
        >
          취소
        </Button>
      </div>
    </form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// WebauthnSection — 메인 내보내기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WebAuthn(FIDO2) 보안 키 설정 섹션 컴포넌트.
 *
 * - 목록 조회: useQuery(['webauthn','keys'], listWebauthnKeys).
 * - 등록: useMutation(registerSecurityKey). 성공 시 invalidateQueries(['webauthn','keys'])만 사용.
 *   register/finish 응답은 201 No Content이므로 setQueryData는 부분응답 플리커를 유발한다 — 금지.
 * - 삭제: useMutation(deleteWebauthnKey). 성공 시 invalidateQueries(['webauthn','keys']).
 * - FR-8(P0): 등록 성공 후 user.mfaEnrollmentRequired===true면 refreshSession() → /dashboard.
 *   이를 생략하면 클레임-read 게이트가 stale(true) 상태로 남아 가드가 영구 리다이렉트한다.
 * - 평문 credential은 React state/메모리에만 (localStorage/sessionStorage/authStore 영속 금지).
 * - 미지원 브라우저(browserSupportsWebAuthn()=false)일 때 추가 버튼 비활성 + 안내 문구.
 */
export function WebauthnSection(): JSX.Element {
  const queryClient = useQueryClient()
  const user = useAuthUser()
  const navigate = useNavigate()
  const isSupported = browserSupportsWebAuthn()

  // ── 목록 조회 ─────────────────────────────────────────────────────────────
  const {
    data: keysData,
    isLoading: keysLoading,
  } = useQuery({
    queryKey: ['webauthn', 'keys'],
    queryFn: listWebauthnKeys,
    staleTime: 30_000,
  })

  // ── UI 상태 ───────────────────────────────────────────────────────────────
  const [showRegisterForm, setShowRegisterForm] = useState(false)
  const [registerError, setRegisterError] = useState<string | null>(null)
  // 삭제 확인 중인 키 ID (null이면 확인 박스 닫힘)
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)

  // ── 등록 mutation ─────────────────────────────────────────────────────────
  const registerMutation = useMutation({
    mutationFn: (name: string) => registerSecurityKey(name),
    onSuccess: () => {
      setShowRegisterForm(false)
      setRegisterError(null)
      void queryClient.invalidateQueries({ queryKey: ['webauthn', 'keys'] })

      // FR-8(P0): 강제 모드였다면 토큰 refresh → 클레임 재계산 → 게이트 해제 → /dashboard
      if (user?.mfaEnrollmentRequired === true) {
        void refreshSession()
          .then(() => {
            void navigate({ to: '/dashboard' })
          })
          .catch(() => {
            // doRefresh 내부의 clearSession이 세션을 비워 이후 가드가 /login으로 유도한다.
            // 추가 처리 없이 미처리 rejection만 차단한다.
          })
      }
    },
    onError: (err) => {
      if (err instanceof ApiError) {
        const code = extractErrorCode(err.body)
        setRegisterError(mfaErrorMessage(code ?? ''))
      } else if (err instanceof Error && err.name === 'InvalidStateError') {
        // @simplewebauthn은 excludeCredentials에 매칭되는 인증기를 재등록하면 register/finish
        // 요청 전에 InvalidStateError를 던진다(백엔드 409 미트리거). already_registered로 매핑한다.
        setRegisterError(mfaErrorMessage('already_registered'))
      } else if (err instanceof Error && err.name === 'NotAllowedError') {
        // 사용자 취소 또는 타임아웃 — 일반 에러 메시지 표시
        setRegisterError(mfaErrorMessage(''))
      } else {
        setRegisterError(mfaErrorMessage(''))
      }
    },
  })

  // ── 삭제 mutation ─────────────────────────────────────────────────────────
  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteWebauthnKey(id),
    onSuccess: () => {
      setPendingDeleteId(null)
      void queryClient.invalidateQueries({ queryKey: ['webauthn', 'keys'] })
    },
    onError: () => {
      setPendingDeleteId(null)
    },
  })

  // ── 이벤트 핸들러 ─────────────────────────────────────────────────────────

  function handleAddClick(): void {
    setRegisterError(null)
    setShowRegisterForm(true)
  }

  function handleRegisterSubmit(name: string): void {
    setRegisterError(null)
    registerMutation.mutate(name)
  }

  function handleRegisterCancel(): void {
    setShowRegisterForm(false)
    setRegisterError(null)
  }

  function handleDeleteClick(id: string): void {
    setPendingDeleteId(id)
  }

  function handleDeleteConfirm(): void {
    if (pendingDeleteId !== null) {
      deleteMutation.mutate(pendingDeleteId)
    }
  }

  function handleDeleteCancel(): void {
    setPendingDeleteId(null)
  }

  // ── 렌더 ─────────────────────────────────────────────────────────────────

  const keys = keysData?.keys ?? []

  return (
    <div className="space-y-4 border-t pt-6">
      {/* 섹션 헤더 */}
      <div>
        <h3 className="text-sm font-semibold">{mfaStrings.webauthnSectionTitle}</h3>
        <p className="mt-1 text-sm text-muted-foreground">
          {mfaStrings.webauthnSectionDescription}
        </p>
      </div>

      {/* 미지원 브라우저 안내 */}
      {!isSupported && (
        <div
          role="alert"
          aria-live="polite"
          className="rounded-lg bg-muted p-3 text-sm text-muted-foreground"
        >
          {mfaStrings.webauthnUnsupportedBrowser}
        </div>
      )}

      {/* 목록 로딩 스켈레톤 */}
      {keysLoading && (
        <div className="space-y-2">
          <div className="h-12 w-full animate-pulse rounded-lg bg-muted" />
        </div>
      )}

      {/* 키 목록 */}
      {!keysLoading && (
        <>
          {keys.length === 0 ? (
            <p className="text-sm text-muted-foreground">{mfaStrings.webauthnEmptyState}</p>
          ) : (
            <ul className="space-y-2">
              {keys.map((key) => (
                <KeyListItem
                  key={key.id}
                  securityKey={key}
                  isConfirming={pendingDeleteId === key.id}
                  onDeleteClick={handleDeleteClick}
                />
              ))}
            </ul>
          )}
        </>
      )}

      {/* 삭제 인라인 확인 박스 */}
      {pendingDeleteId !== null && (
        <DeleteConfirmBox
          isPending={deleteMutation.isPending}
          onConfirm={handleDeleteConfirm}
          onCancel={handleDeleteCancel}
        />
      )}

      {/* 등록 폼 */}
      {showRegisterForm && isSupported && (
        <RegisterForm
          isPending={registerMutation.isPending}
          errorMessage={registerError}
          onSubmit={handleRegisterSubmit}
          onCancel={handleRegisterCancel}
        />
      )}

      {/* 추가 버튼 — 폼이 열려 있거나 미지원 브라우저면 숨김 */}
      {!showRegisterForm && (
        <Button
          onClick={handleAddClick}
          disabled={!isSupported}
          aria-label={mfaStrings.webauthnAddButton}
        >
          {mfaStrings.webauthnAddButton}
        </Button>
      )}
    </div>
  )
}
