// 계정 연결 설정 페이지 — /settings/account-links, step-up 오케스트레이션 + 콜백 쿼리 처리 (FR-AU-08/08b D6)
import { type JSX, useState, useEffect, useCallback } from 'react'
import { useSearch, useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { AccountLinkList } from '@/components/auth/AccountLinkList'
import { ReauthDialog } from '@/components/auth/ReauthDialog'
import { AddAccountDialog } from '@/components/auth/AddAccountDialog'
import { useAccountLinksQuery } from '@/auth/useAccountLinksQuery'
import { useAccountLinkMutations } from '@/auth/useAccountLinkMutations'
import { accountLinkLabels, accountLinkErrorMessage } from '@/i18n/account-link-labels'
import { ApiError } from '@/api/client'
import { extractErrorCode } from '@/lib/extract-error-code'

// ─────────────────────────────────────────────────────────────────────────────
// 콜백 status → 메시지/토스트 타입 매핑 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

type ToastVariant = 'success' | 'error' | 'info'

interface CallbackMessage {
  readonly message: string
  readonly variant: ToastVariant
}

/**
 * link 콜백 status를 사용자 노출 메시지와 토스트 종류로 변환한다.
 * 새로고침 재표시 방지를 위해 표시 즉시 쿼리 파라미터를 제거한다(EC7).
 */
function resolveLinkCallbackMessage(status: string): CallbackMessage {
  switch (status) {
    case 'success':
      return { message: accountLinkLabels.callback.link.success, variant: 'success' }
    case 'already_linked':
      return { message: accountLinkLabels.callback.link.alreadyLinked, variant: 'info' }
    case 'conflict':
      return { message: accountLinkLabels.callback.link.conflict, variant: 'error' }
    default:
      return { message: accountLinkLabels.callback.link.error, variant: 'error' }
  }
}

/**
 * reauth 콜백 status를 사용자 노출 메시지와 토스트 종류로 변환한다.
 */
function resolveReauthCallbackMessage(status: string): CallbackMessage {
  switch (status) {
    case 'success':
      return { message: accountLinkLabels.callback.reauth.success, variant: 'success' }
    default:
      return { message: accountLinkLabels.callback.reauth.failed, variant: 'error' }
  }
}

/** ToastVariant에 따라 sonner toast를 호출한다 */
function showToast(msg: CallbackMessage): void {
  if (msg.variant === 'success') {
    toast.success(msg.message)
  } else if (msg.variant === 'info') {
    toast.info(msg.message)
  } else {
    toast.error(msg.message)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 대기 동작 타입 — step-up 재인증 완료 후 재개할 동작을 보관
// ─────────────────────────────────────────────────────────────────────────────

type PendingAction =
  | { readonly kind: 'unlink'; readonly id: string }
  | { readonly kind: 'link'; readonly providerId: string; readonly username: string; readonly password: string }
  | { readonly kind: 'ssoStart'; readonly registrationId: string; readonly providerType: 'SAML' | 'OIDC' }

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AccountLinksSettingsPage props.
 *
 * 콜백 쿼리 파라미터와 navigate 의존성을 props로 주입받아
 * TanStack Router 없이 단위 테스트가 가능하도록 설계한다.
 */
export interface AccountLinksSettingsPageProps {
  /**
   * `?link=` 콜백 status 값.
   * SSO 연결 완료 후 IdP가 이 페이지로 리디렉트할 때 포함된다.
   * null이면 콜백 처리를 하지 않는다.
   */
  readonly linkCallbackStatus: string | null
  /**
   * `?reauth=` 콜백 status 값.
   * SSO step-up 재인증 완료 후 리디렉트 시 포함된다.
   * null이면 콜백 처리를 하지 않는다.
   */
  readonly reauthCallbackStatus: string | null
  /**
   * window.location.assign 대체 함수 — SSO 리디렉트 시 사용.
   * 테스트에서 주입해 실제 리디렉트 없이 URL을 검증한다.
   * 기본값은 RouteAdapter에서 `(url) => window.location.assign(url)` 으로 설정한다.
   */
  readonly assignLocation: (url: string) => void
  /**
   * 콜백 쿼리 파라미터를 URL에서 제거하는 콜백.
   * 표시 완료 후 새로고침 재표시 방지(EC7)를 위해 즉시 호출한다.
   * 라우터 인스턴스가 있는 RouteAdapter에서 `navigate({ search: {} })` 로 구현한다.
   */
  readonly onClearCallbackSearch: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// AccountLinksSettingsPage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 계정 연결 설정 페이지.
 *
 * ### 주요 동작
 * - `useAccountLinksQuery`로 연결 목록을 조회해 `AccountLinkList`에 렌더한다.
 * - "계정 추가" 버튼 → `AddAccountDialog` 열림 (linkable providers 조회).
 * - 연결/해제 동작 시 step-up 게이팅 수행.
 *   - `stepUpExpiresAt` 상태가 미래면 즉시 진행 (클라이언트 시각은 보조 최적화).
 *   - 아니면 `ReauthDialog` 오픈 → step-up 완료 후 대기 동작 자동 재개.
 *   - **서버 403 `step_up_required` 응답이 오면 클라 시각과 무관하게 ReauthDialog를 트리거한다** (EC3 — 서버 응답이 진실 출처).
 * - `?link=`/`?reauth=` 콜백 쿼리를 마운트 시 읽어 sonner toast 표시 후 파라미터를 제거한다.
 */
export function AccountLinksSettingsPage({
  linkCallbackStatus,
  reauthCallbackStatus,
  assignLocation,
  onClearCallbackSearch,
}: AccountLinksSettingsPageProps): JSX.Element {
  // ─── 계정 연결 목록 쿼리 ───────────────────────────────────────────────────
  const { data: linksData, isLoading } = useAccountLinksQuery()
  const links = linksData?.links ?? []
  const hasLocalPassword = linksData?.hasLocalPassword ?? true

  // ─── mutations ────────────────────────────────────────────────────────────
  const { link, unlink, ssoLinkStart } = useAccountLinkMutations()

  // ─── UI 상태 ──────────────────────────────────────────────────────────────
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [reauthDialogOpen, setReauthDialogOpen] = useState(false)
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)

  /**
   * step-up 세션 만료 시각 (ISO 8601).
   *
   * 클라이언트 시각은 서버 판단의 보조 최적화 역할만 한다.
   * 실제 권한 판단은 서버 403 응답이 진실 출처다 (EC3).
   */
  const [stepUpExpiresAt, setStepUpExpiresAt] = useState<string | null>(null)

  // ─── 콜백 쿼리 처리 ───────────────────────────────────────────────────────
  /**
   * 마운트 시 ?link= / ?reauth= 쿼리를 처리한다.
   *
   * 처리 후 onClearCallbackSearch를 즉시 호출해 URL에서 파라미터를 제거한다.
   * 새로고침 재표시 방지(EC7).
   *
   * 의존성 배열에 linkCallbackStatus/reauthCallbackStatus만 포함 — 마운트 시 1회 실행.
   */
  useEffect(() => {
    if (linkCallbackStatus !== null) {
      const msg = resolveLinkCallbackMessage(linkCallbackStatus)
      showToast(msg)
      onClearCallbackSearch()
      return
    }
    if (reauthCallbackStatus !== null) {
      const msg = resolveReauthCallbackMessage(reauthCallbackStatus)
      showToast(msg)
      onClearCallbackSearch()
    }
    // onClearCallbackSearch는 stable ref이므로 의존성에 포함하지 않는다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkCallbackStatus, reauthCallbackStatus])

  // ─── step-up 유효성 체크 ──────────────────────────────────────────────────
  /** 클라이언트 시각 기준으로 step-up 윈도우가 아직 유효한지 확인한다 (보조 최적화) */
  function isStepUpValid(): boolean {
    if (stepUpExpiresAt === null) return false
    return new Date(stepUpExpiresAt).getTime() > Date.now()
  }

  // ─── step-up 오케스트레이션 ───────────────────────────────────────────────
  /** step-up이 필요할 때 ReauthDialog를 열고 대기 동작을 보관한다 */
  function requireStepUp(action: PendingAction): void {
    setPendingAction(action)
    setReauthDialogOpen(true)
  }

  /**
   * ReauthDialog의 onStepUpGranted 콜백.
   * 서버가 발급한 만료 시각을 상태에 저장하고 대기 중인 동작을 재개한다.
   */
  const handleStepUpGranted = useCallback(
    (expiresAt: string) => {
      setStepUpExpiresAt(expiresAt)
      setReauthDialogOpen(false)
      if (pendingAction === null) return

      const action = pendingAction
      setPendingAction(null)

      if (action.kind === 'unlink') {
        unlink.mutate(action.id, {
          onSuccess: () => { toast.success(accountLinkLabels.toast.unlinkSuccess) },
          onError: (err) => {
            const code = err instanceof ApiError ? extractErrorCode(err.body) : null
            toast.error(accountLinkErrorMessage(code))
          },
        })
      } else if (action.kind === 'link') {
        link.mutate(
          { providerId: action.providerId, username: action.username, password: action.password },
          {
            onSuccess: () => {
              toast.success(accountLinkLabels.toast.linkSuccess)
              setAddDialogOpen(false)
            },
            onError: (err) => {
              const code = err instanceof ApiError ? extractErrorCode(err.body) : null
              toast.error(accountLinkErrorMessage(code))
            },
          },
        )
      } else {
        // ssoStart
        ssoLinkStart.mutate(
          { registrationId: action.registrationId, providerType: action.providerType },
          {
            onSuccess: ({ authorizeUrl }) => { assignLocation(authorizeUrl) },
            onError: (err) => {
              const code = err instanceof ApiError ? extractErrorCode(err.body) : null
              toast.error(accountLinkErrorMessage(code))
            },
          },
        )
      }
    },
    [pendingAction, unlink, link, ssoLinkStart, assignLocation],
  )

  // ─── mutation 에러 → step_up_required 감지 ───────────────────────────────
  /**
   * 403 step_up_required 응답 시 ReauthDialog를 트리거하는 공통 에러 핸들러.
   *
   * 서버 403이 진실 출처다(EC3). 클라이언트 시각이 미래여도
   * 서버가 403을 반환하면 재인증을 요구한다.
   */
  function handleMutationError(err: unknown, action: PendingAction): void {
    if (err instanceof ApiError) {
      const code = extractErrorCode(err.body)
      if (err.status === 403 && code === 'step_up_required') {
        // 서버 403 step_up_required → 클라 시각과 무관하게 ReauthDialog 트리거 (EC3)
        requireStepUp(action)
        return
      }
      toast.error(accountLinkErrorMessage(code))
    } else {
      toast.error(accountLinkErrorMessage(null))
    }
  }

  // ─── onUnlink ────────────────────────────────────────────────────────────
  function handleUnlink(id: string): void {
    const action: PendingAction = { kind: 'unlink', id }
    if (!isStepUpValid()) {
      requireStepUp(action)
      return
    }
    unlink.mutate(id, {
      onSuccess: () => { toast.success(accountLinkLabels.toast.unlinkSuccess) },
      onError: (err) => { handleMutationError(err, action) },
    })
  }

  // ─── onLink (LDAP) ───────────────────────────────────────────────────────
  function handleLink(req: { providerId: string; username: string; password: string }): void {
    const action: PendingAction = { kind: 'link', ...req }
    if (!isStepUpValid()) {
      requireStepUp(action)
      return
    }
    link.mutate(req, {
      onSuccess: () => {
        toast.success(accountLinkLabels.toast.linkSuccess)
        setAddDialogOpen(false)
      },
      onError: (err) => { handleMutationError(err, action) },
    })
  }

  // ─── onSsoStart ──────────────────────────────────────────────────────────
  function handleSsoStart(req: { registrationId: string; providerType: 'SAML' | 'OIDC' }): void {
    const action: PendingAction = { kind: 'ssoStart', ...req }
    if (!isStepUpValid()) {
      requireStepUp(action)
      return
    }
    ssoLinkStart.mutate(req, {
      onSuccess: ({ authorizeUrl }) => { assignLocation(authorizeUrl) },
      onError: (err) => { handleMutationError(err, action) },
    })
  }

  // ─── ReauthDialog props ───────────────────────────────────────────────────
  /** LDAP 연결 중 해제 중인 링크 — 재인증 수단 결정에 사용 */
  const ldapLink = links.find((l) => l.providerType === 'LDAP')
  const ssoLink = links.find((l) => l.providerType === 'SAML' || l.providerType === 'OIDC')

  // ─── 현재 해제 진행 중인 id ───────────────────────────────────────────────
  const unlinkingId =
    unlink.isPending && pendingAction?.kind === 'unlink' ? pendingAction.id : undefined

  // ─────────────────────────────────────────────────────────────────────────
  // 렌더
  // ─────────────────────────────────────────────────────────────────────────

  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      {/* 페이지 헤더 */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">{accountLinkLabels.page.heading}</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {accountLinkLabels.page.description}
          </p>
        </div>
        {/* 연결된 계정이 있을 때만 "계정 추가" 버튼 상단에 표시 */}
        {links.length > 0 && (
          <Button variant="outline" size="sm" onClick={() => { setAddDialogOpen(true) }}>
            {accountLinkLabels.add.addButton}
          </Button>
        )}
      </div>

      {/* 로딩 상태 */}
      {isLoading ? (
        <p
          role="status"
          aria-label={accountLinkLabels.page.loadingStatus}
          className="text-sm text-muted-foreground animate-pulse"
        >
          {accountLinkLabels.page.loadingStatus}
        </p>
      ) : (
        <AccountLinkList
          links={links}
          onUnlink={handleUnlink}
          onAddLink={() => { setAddDialogOpen(true) }}
          unlinkingId={unlinkingId}
        />
      )}

      {/* 계정 추가 다이얼로그 */}
      <AddAccountDialog
        open={addDialogOpen}
        onOpenChange={setAddDialogOpen}
        onLink={handleLink}
        onSsoStart={handleSsoStart}
        isSubmitting={link.isPending || ssoLinkStart.isPending}
      />

      {/* step-up 재인증 모달 */}
      <ReauthDialog
        open={reauthDialogOpen}
        onOpenChange={setReauthDialogOpen}
        hasLocalPassword={hasLocalPassword}
        ldapProviderId={ldapLink?.providerId}
        ldapUsername={ldapLink?.externalSubjectMasked}
        ssoReauthProvider={
          ssoLink !== undefined && (ssoLink.providerType === 'SAML' || ssoLink.providerType === 'OIDC')
            ? { registrationId: ssoLink.providerId, providerType: ssoLink.providerType }
            : undefined
        }
        onStepUpGranted={handleStepUpGranted}
        assignLocation={assignLocation}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 *
 * - `useSearch` / `useNavigate`로 TanStack Router 컨텍스트를 읽어 페이지에 props로 전달한다.
 * - `validateSearch`로 타입이 선언된 쿼리 파라미터를 사용한다.
 * - `window.location.search` 직접 파싱 금지 (issuesIndexRoute 선례).
 *
 * createRoute 등록 예 (router.ts 참조).
 * ```ts
 * import { AccountLinksSettingsRouteAdapter } from './routes/settings.account-links'
 * const settingsAccountLinksRoute = createRoute({
 *   getParentRoute: () => rootRoute,
 *   path: '/settings/account-links',
 *   component: AccountLinksSettingsRouteAdapter,
 *   staticData: { requireAuth: true },
 *   beforeLoad: requireAuthAndPasswordChanged,
 *   validateSearch: (search) => ({ link: ..., reauth: ... }), // router.ts에 인라인 정의
 * })
 * ```
 */
export function AccountLinksSettingsRouteAdapter(): JSX.Element {
  const search = useSearch({ from: '/settings/account-links' }) as AccountLinksSearch
  const navigate = useNavigate()

  function handleClearCallbackSearch(): void {
    void navigate({ to: '/settings/account-links', search: {}, replace: true })
  }

  return (
    <AccountLinksSettingsPage
      linkCallbackStatus={search.link ?? null}
      reauthCallbackStatus={search.reauth ?? null}
      assignLocation={(url) => { window.location.assign(url) }}
      onClearCallbackSearch={handleClearCallbackSearch}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// validateSearch — TanStack Router validateSearch 함수
// ─────────────────────────────────────────────────────────────────────────────

/** 계정 연결 페이지 쿼리 파라미터 타입 */
interface AccountLinksSearch {
  /** SSO 연결 콜백 status — 'success' | 'already_linked' | 'conflict' | 'error' */
  readonly link?: string | undefined
  /** SSO reauth 콜백 status — 'success' | 'failed' */
  readonly reauth?: string | undefined
}

// validateAccountLinksSearch는 router.ts에서 인라인으로 선언한다.
// (react-refresh/only-export-components 규칙 — 컴포넌트 파일에서 유틸 함수 export 금지)
