// 로그인 폼 컴포넌트 — 단일 화면 자격 증명 + MFA 2단계 (provider+id+pw → TOTP 코드)
import { useState, useEffect, useMemo, useRef } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useMutation } from '@tanstack/react-query'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useLoginMutation } from './useLoginMutation'
import { SamlIdpButtons } from './SamlIdpButtons'
import { OidcIdpButtons } from './OidcIdpButtons'
import { ssoEntryUrl } from './ssoEntryUrl'
import { loginStrings, mfaStrings, mfaErrorMessage } from '@/i18n/ko'
import { verifyMfa } from '@/api/mfa'
import { ApiError, apiGet } from '@/api/client'
import { ApiErrorResponseSchema, WhoamiResponseSchema } from '@/api/schemas'
import { useAuthStore } from './authStore'
import { fetchSamlIdps } from '@/api/saml'
import { fetchOidcProviders } from '@/api/oidc'
import { fetchProviders } from '@/api/providers'
import { fetchRoute } from '@/api/route'
import { authenticateWithSecurityKey } from '@/api/webauthn'
import { browserSupportsWebAuthn } from '@simplewebauthn/browser'
import type { SamlIdp } from '@/api/saml'
import type { OidcProvider } from '@/api/oidc'
import type { ProviderEntry } from '@/api/providers'
import type { RouteMatch } from '@/api/route'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 / 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * provider id → 한국어 라벨 매핑.
 * 키가 없으면 응답의 displayName을 그대로 사용한다(fallback).
 */
const PROVIDER_LABEL_MAP: Readonly<Record<string, string>> = {
  local: loginStrings.providerLocal,
  ldap: loginStrings.providerLdapCorp,
}

/** ProviderEntry의 id에 대응하는 표시 라벨을 반환한다. */
function resolveProviderLabel(provider: ProviderEntry): string {
  return PROVIDER_LABEL_MAP[provider.id] ?? provider.displayName
}

/**
 * onError 콜백에서 받은 에러를 사용자 노출 한국어 메시지로 변환한다.
 */
function resolveLoginErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return loginStrings.errorDefault
}

/**
 * providers API 실패 또는 빈 배열 응답 시 사용하는 LOCAL 안전 기본값.
 * spec EC-06-06: "LOCAL fallback — 최소 Local 로그인은 항상 가능".
 */
const LOCAL_FALLBACK: readonly ProviderEntry[] = [
  { id: 'local', type: 'LOCAL', displayName: 'Local', priority: 0, available: true },
]

// provider 값은 동적이므로 enum 대신 z.string().min(1) 사용.
const loginFormSchema = z.object({
  provider: z.string().min(1),
  username: z.string().min(1, loginStrings.usernameRequired),
  password: z.string().min(1, loginStrings.passwordRequired),
})

type LoginFormValues = z.infer<typeof loginFormSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 자격 증명 폼 — provider + username + password + SSO (단일 화면)
// ─────────────────────────────────────────────────────────────────────────────

interface CredentialsFormProps {
  onSuccess?: () => void
  /** login 응답이 mfa_required:true일 때 챌린지 토큰을 전달하며 MFA step으로 진입 */
  onMfaRequired: (challengeToken: string) => void
  providers: readonly ProviderEntry[]
  isProvidersLoading: boolean
  samlIdps: SamlIdp[]
  oidcProviders: OidcProvider[]
}

/** 도메인 조회 디바운스 — 부분 도메인마다 요청이 나가는 것을 막는다 */
const ROUTE_LOOKUP_DEBOUNCE_MS = 500

/**
 * 로그인 자격 증명 폼. provider 드롭다운 + username + password + SSO 버튼을 **한 화면**에 렌더한다.
 *
 * 이메일 선입력 1단계는 폐기됐다(FR-AU-07 deviation). 도메인 기반 SSO 라우팅은 사라지지 않고
 * username 입력의 배경 조회로 옮겨왔다 — blur 또는 500ms 디바운스에 `GET /auth/route` 를 부르고,
 * 매칭되면 SSO 버튼을 비밀번호 **위**에 노출한다.
 *
 * 🛑 자동 리다이렉트는 하지 않는다. 기존 2단계에서 `window.location.assign` 이 안전했던 것은
 * 트리거가 "계속" 클릭이라는 **명시적 행위**였기 때문이다. 여기서 트리거는 blur/디바운스로
 * **수동적**이라, 그 상태로 풀 네비게이션을 걸면 타이핑 중이던 비밀번호와 함께 화면이 통째로
 * 사라지고 되돌릴 수 없다. 매칭 도메인에 LOCAL/LDAP 계정이 공존할 수도 있다(FR-AU-06).
 *
 * providers는 부모 LoginForm이 이미 계산해 prop으로 전달한다.
 * 마운트 시 providers[0].id가 이미 확정돼 있으면 defaultValues에서 직접 설정한다.
 * providers가 아직 빈 배열이면 useEffect에서 첫 항목 도착 시 setValue로 설정한다.
 */
const LoginCredentialsForm = ({
  onSuccess,
  onMfaRequired,
  providers,
  isProvidersLoading,
  samlIdps,
  oidcProviders,
}: CredentialsFormProps) => {
  const mutation = useLoginMutation()

  /** 도메인 매칭 결과 — null 이면 로컬/LDAP 폼만 보인다 */
  const [matchedRoute, setMatchedRoute] = useState<RouteMatch | null>(null)
  /** 같은 도메인을 두 번 조회하지 않기 위한 dedupe 키 */
  const lastQueriedDomainRef = useRef('')
  /** 늦게 도착한 응답이 최신 결과를 덮어쓰지 않게 하는 순번 */
  const seqRef = useRef(0)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 언마운트 시 대기 중인 디바운스를 취소한다 — 사라진 컴포넌트의 setState 방지
  useEffect(
    () => () => {
      if (debounceRef.current !== null) clearTimeout(debounceRef.current)
    },
    [],
  )

  /**
   * 식별자에서 도메인을 뽑아 route 를 조회한다.
   *
   * `@` 가 없거나 도메인이 비면 조회하지 않는다 — LDAP 사용자명(`alice`)이 그 경우다.
   * 조회 실패는 사용자를 막지 않고 `console.warn` 만 남긴다(FR-07 S4 fail-safe).
   * 정상 운영(네트워크 일시 단절 등)에서도 발생할 수 있는 폴백 경로라 error 가 아닌 warn 이다.
   */
  function runRouteLookup(identifier: string) {
    const atIndex = identifier.indexOf('@')
    const domain = atIndex !== -1 ? identifier.slice(atIndex + 1) : ''

    // `@` 를 지웠거나 도메인이 비면 조회하지 않는다 — LDAP 사용자명(`alice`)이 그 경우다.
    // 🛑 이때 이전 매칭을 **반드시 지운다**. 안 지우면 식별자를 사용자명으로 바꿨는데
    //    이전 도메인의 SSO 버튼이 남는다(stale 매칭).
    if (domain === '') {
      lastQueriedDomainRef.current = ''
      setMatchedRoute(null)
      return
    }
    if (domain === lastQueriedDomainRef.current) return

    lastQueriedDomainRef.current = domain
    const seq = ++seqRef.current
    fetchRoute(domain)
      .then((result) => {
        if (seq !== seqRef.current) return
        setMatchedRoute(result.matched ? result : null)
      })
      .catch((err: unknown) => {
        // 🛑 dedupe 키를 되돌린다. 실패한 도메인이 키에 남으면 재조회가 **영구 차단**되고,
        //    일시적 네트워크 장애 뒤 SSO 버튼이 영영 뜨지 않는다.
        //    최신 조회일 때만 되돌린다 — 뒤늦게 실패한 옛 요청이 새 키를 지우면 안 된다.
        if (seq === seqRef.current) {
          lastQueriedDomainRef.current = ''
        }
        console.warn('[LoginForm] route 조회 실패 — 로컬 로그인으로 진행', err)
      })
  }

  /** 타이핑 중 조회 — 부분 도메인 요청을 줄이기 위해 디바운스한다 */
  function scheduleRouteLookup(identifier: string) {
    if (debounceRef.current !== null) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      runRouteLookup(identifier)
    }, ROUTE_LOOKUP_DEBOUNCE_MS)
  }

  /**
   * 입력 종료 신호. 디바운스만으로는 필드를 떠나지 않고 Enter 로 제출하는 사용자를 놓치는데,
   * 그게 정확히 NFR-A11Y-04(키보드만으로 로그인)가 보호하는 경로다.
   */
  function flushRouteLookup(identifier: string) {
    if (debounceRef.current !== null) {
      clearTimeout(debounceRef.current)
      debounceRef.current = null
    }
    runRouteLookup(identifier)
  }

  // providers[0]?.id가 이미 있으면 마운트 시 기본값으로 사용한다.
  // 없으면 '' — useEffect에서 채운다.
  const initialProvider = providers[0]?.id ?? ''

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: {
      provider: initialProvider,
      username: '',
      password: '',
    },
  })

  // providers가 비동기로 늦게 도착하는 경우(마운트 시 빈 배열) 첫 항목을 설정한다.
  // 이미 provider 값이 있으면(마운트 시 defaultValues로 설정됨) 덮어쓰지 않는다.
  useEffect(() => {
    const firstProvider = providers[0]
    if (firstProvider === undefined) return
    if (form.getValues('provider') === '') {
      form.setValue('provider', firstProvider.id)
    }
  }, [providers, form])

  const serverError = form.formState.errors.root?.message ?? null

  function onSubmit(values: LoginFormValues) {
    form.clearErrors('root')
    mutation.mutate(values, {
      onSuccess: (data) => {
        if (data.kind === 'mfa_required') {
          // MFA 챌린지 진입 — 챌린지 토큰을 부모에게 전달하고 MFA step으로 전환
          onMfaRequired(data.challengeToken)
          return
        }
        // 정상 로그인 완료 — 기존 성공 경로
        onSuccess?.()
      },
      onError: (error: unknown) => {
        form.setError('root', { message: resolveLoginErrorMessage(error) })
      },
    })
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate className="space-y-4">
        <FormField
          control={form.control}
          name="provider"
          render={({ field }) => (
            <FormItem>
              <FormLabel id="provider-label">{loginStrings.providerLabel}</FormLabel>
              <FormControl>
                <Select
                  value={field.value}
                  onValueChange={field.onChange}
                  disabled={isProvidersLoading}
                >
                  <SelectTrigger
                    aria-labelledby="provider-label"
                    aria-label={loginStrings.providerLabel}
                    role="combobox"
                    className="w-full"
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {providers.map((provider) => (
                      <SelectItem key={provider.id} value={provider.id} role="option">
                        {resolveProviderLabel(provider)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="username"
          render={({ field }) => (
            <FormItem>
              <FormLabel htmlFor="login-username">{loginStrings.usernameLabel}</FormLabel>
              <FormControl>
                <Input
                  id="login-username"
                  type="text"
                  autoComplete="username"
                  aria-required="true"
                  {...field}
                  onChange={(e) => {
                    field.onChange(e)
                    scheduleRouteLookup(e.target.value)
                  }}
                  onBlur={(e) => {
                    field.onBlur()
                    flushRouteLookup(e.target.value)
                  }}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {/* SSO 버튼은 비밀번호 **위**에 둔다 — 사용자가 비밀번호를 치기 전에 보게 하기 위해서다 */}
        {matchedRoute !== null && (
          <div className="space-y-2">
            <Button
              type="button"
              className="h-10 w-full"
              onClick={() => {
                window.location.assign(
                  ssoEntryUrl(matchedRoute.type, matchedRoute.registrationId),
                )
              }}
            >
              {matchedRoute.type === 'SAML'
                ? loginStrings.samlLoginButtonLabel(matchedRoute.displayName)
                : loginStrings.oidcLoginButtonLabel(matchedRoute.displayName)}
            </Button>
            <p className="text-sm text-muted-foreground">{loginStrings.ssoRoutedHint}</p>
            <div className="flex items-center gap-2">
              <span className="h-px flex-1 bg-border" />
              <span className="text-xs text-muted-foreground">{loginStrings.samlDividerText}</span>
              <span className="h-px flex-1 bg-border" />
            </div>
          </div>
        )}

        <FormField
          control={form.control}
          name="password"
          render={({ field }) => (
            <FormItem>
              <FormLabel htmlFor="login-password">{loginStrings.passwordLabel}</FormLabel>
              <FormControl>
                <Input
                  id="login-password"
                  type="password"
                  autoComplete="current-password"
                  aria-required="true"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {serverError !== null && (
          <p role="alert" className="text-destructive text-sm">
            {serverError}
          </p>
        )}

        {/* SSO 로 라우팅된 도메인이어도 로컬 제출은 살려둔다 — 강등만 한다.
            FR-07 S4 "끊긴 라우트가 사용자를 막지 않는다" 를 그대로 지키는 자리다. */}
        <Button
          type="submit"
          variant={matchedRoute !== null ? 'outline' : 'default'}
          className="h-10 w-full"
          disabled={mutation.isPending}
        >
          {loginStrings.submitButton}
        </Button>

        <SamlIdpButtons idps={samlIdps} />
        <OidcIdpButtons providers={oidcProviders} />
      </form>
    </Form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// MFA step — TOTP / 백업 코드 입력 화면 (3단계)
// ─────────────────────────────────────────────────────────────────────────────

/** MFA 인증 방식. 'totp'는 Authenticator 앱 6자리 코드, 'backup_code'는 비상 백업 코드 */
type MfaMode = 'totp' | 'backup_code'

interface MfaStepProps {
  /** login 200 응답에서 받은 5분 단명 챌린지 토큰 */
  challengeToken: string
  /** verify 성공 시 기존 로그인 성공 핸들러와 동일 경로 수렴 */
  onSuccess?: () => void
  /** 1단계(이메일)로 복귀하는 콜백 */
  onBackToLogin: () => void
}

/** TOTP 모드 Zod 스키마 — 6자리 숫자 문자열 */
const totpSchema = z.object({
  code: z.string().length(6, '6자리 코드를 입력하세요.'),
})

/** 백업 코드 모드 Zod 스키마 — 비어 있지 않으면 허용 (형식 검증은 백엔드 위임) */
const backupCodeSchema = z.object({
  code: z.string().trim().min(1, mfaStrings.loginBackupCodeRequired),
})

type MfaCodeFormValues = { code: string }

/**
 * resolveVerifyErrorMessage는 ApiError body의 error 코드를 mfaErrorMessage로 변환한다.
 * 알 수 없는 에러면 mfaErrorMessage default 케이스 메시지를 반환한다.
 */
function resolveVerifyErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const parsed = ApiErrorResponseSchema.safeParse(error.body)
    if (parsed.success) {
      return mfaErrorMessage(parsed.data.error)
    }
  }
  return mfaErrorMessage('')
}

/**
 * mode에 대응하는 Zod 스키마를 반환한다.
 * CONCERN-2: zodResolver는 useForm 첫 렌더에 고정되므로 key={mode}로 재마운트해
 * 스키마를 갱신한다. 이 함수는 재마운트된 컴포넌트 내에서 호출된다.
 */
function resolveSchema(mode: MfaMode) {
  return mode === 'totp' ? totpSchema : backupCodeSchema
}

/** mode에 대응하는 UI 문자열(가이드·라벨·토글 텍스트·inputMode·maxLength)을 반환한다. */
function resolveMfaUiConfig(mode: MfaMode) {
  if (mode === 'totp') {
    return {
      guideText: mfaStrings.loginStepGuide,
      codeLabel: mfaStrings.loginCodeLabel,
      toggleText: mfaStrings.loginUseBackupCode,
      inputMode: 'numeric' as const,
      maxLength: 6,
      autoComplete: 'one-time-code',
    }
  }
  return {
    guideText: mfaStrings.loginBackupStepGuide,
    codeLabel: mfaStrings.loginBackupCodeLabel,
    toggleText: mfaStrings.loginUseTotp,
    inputMode: 'text' as const,
    maxLength: undefined,
    autoComplete: 'off',
  }
}

interface MfaCodeInputProps {
  /** 현재 MFA 인증 방식 */
  mode: MfaMode
  challengeToken: string
  onSuccess?: () => void
  onBackToLogin: () => void
  onToggleMode: () => void
  /** 이 디바이스를 30일간 신뢰할지 여부 — 부모(LoginMfaStep)에서 관리한다 */
  trustDevice: boolean
}

/**
 * MFA 코드 입력 폼.
 * mode별로 다른 zodResolver가 필요하므로 부모(LoginMfaStep)에서 key={mode}로 재마운트된다.
 * 재마운트 시 useForm + zodResolver가 새로 초기화되어 스키마가 올바르게 적용된다.
 */
const MfaCodeInput = ({
  mode,
  challengeToken,
  onSuccess,
  onBackToLogin,
  onToggleMode,
  trustDevice,
}: MfaCodeInputProps) => {
  const setAccessToken = useAuthStore((s) => s.setAccessToken)
  const setSession = useAuthStore((s) => s.setSession)
  const clearSession = useAuthStore((s) => s.clearSession)

  const form = useForm<MfaCodeFormValues>({
    resolver: zodResolver(resolveSchema(mode)),
    defaultValues: { code: '' },
  })

  const verifyMutation = useMutation({
    mutationFn: async (code: string) => {
      const tokenData = await verifyMfa(challengeToken, code, mode, trustDevice)

      // verify 성공 — 기존 로그인 성공 경로와 동일하게 세션 저장
      setAccessToken(tokenData.access_token)

      const user = await apiGet('/api/v1/users/me/whoami', WhoamiResponseSchema).catch(
        (err: unknown) => {
          clearSession()
          throw err
        },
      )

      setSession({ accessToken: tokenData.access_token, user })
      return { accessToken: tokenData.access_token, user }
    },
    onSuccess: () => {
      onSuccess?.()
    },
    onError: (error: unknown) => {
      form.setError('root', { message: resolveVerifyErrorMessage(error) })
    },
  })

  const serverError = form.formState.errors.root?.message ?? null
  const { guideText, codeLabel, toggleText, inputMode, maxLength, autoComplete } =
    resolveMfaUiConfig(mode)

  function onSubmit(values: MfaCodeFormValues) {
    form.clearErrors('root')
    verifyMutation.mutate(values.code)
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate className="space-y-4">
        {/* 모달 안에서 단계가 바뀌므로 전환을 스크린리더에 알린다 — 페이지 이동이 없어
            보조기술이 맥락 변화를 스스로 눈치챌 수 없다. 에러는 별도로 role="alert" 가 맡는다. */}
        <p role="status" className="text-sm text-muted-foreground">
          {guideText}
        </p>

        <FormField
          control={form.control}
          name="code"
          render={({ field }) => (
            <FormItem>
              <FormLabel htmlFor="mfa-code">{codeLabel}</FormLabel>
              <FormControl>
                <Input
                  id="mfa-code"
                  type="text"
                  inputMode={inputMode}
                  maxLength={maxLength}
                  autoComplete={autoComplete}
                  aria-required="true"
                  // Radix 는 다이얼로그가 **열릴 때만** autofocus 하고 콘텐츠 교체는 모른다.
                  // MfaCodeInput 은 key={mode} 로 재마운트되므로 여기서 발화한다.
                  autoFocus
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {serverError !== null && (
          <p role="alert" className="text-destructive text-sm">
            {serverError}
          </p>
        )}

        <Button type="submit" className="w-full" disabled={verifyMutation.isPending}>
          {mfaStrings.loginVerifyButton}
        </Button>

        <Button
          type="button"
          variant="ghost"
          className="w-full"
          onClick={onToggleMode}
          disabled={verifyMutation.isPending}
        >
          {toggleText}
        </Button>

        <Button
          type="button"
          variant="ghost"
          className="w-full"
          onClick={onBackToLogin}
          disabled={verifyMutation.isPending}
        >
          {mfaStrings.loginBackToLogin}
        </Button>
      </form>
    </Form>
  )
}

/**
 * MFA step (3단계): mode state를 들고 MfaCodeInput을 key={mode}로 분기 렌더한다.
 *
 * CONCERN-2 대응: zodResolver는 useForm 첫 렌더에 고정되므로,
 * mode가 바뀔 때 key={mode}로 MfaCodeInput을 재마운트해 resolver를 갱신한다.
 *
 * 챌린지 토큰은 prop으로 받아 컴포넌트 메모리에만 보관한다(authStore/sessionStorage 영속 금지 — NFR-1).
 *
 * "보안 키로 인증" 버튼은 MfaCodeInput의 세 번째 mode가 아니라 독립 액션 버튼으로 배치한다.
 * 코드 입력 없이 webauthn 오케스트레이션만 수행하므로 zodResolver/useForm 불요.
 */
const LoginMfaStep = ({ challengeToken, onSuccess, onBackToLogin }: MfaStepProps) => {
  const [mode, setMode] = useState<MfaMode>('totp')
  const [webauthnError, setWebauthnError] = useState<string | null>(null)
  const [isWebauthnPending, setIsWebauthnPending] = useState(false)
  // E6 핵심: trustDevice는 부모(LoginMfaStep)에 보관한다.
  // MfaCodeInput은 mode가 바뀔 때 key={mode}로 재마운트되므로
  // 자식 안에 두면 체크 상태가 리셋된다 (react-usestate-stale-key-prop 역패턴).
  const [trustDevice, setTrustDevice] = useState(false)

  const setAccessToken = useAuthStore((s) => s.setAccessToken)
  const setSession = useAuthStore((s) => s.setSession)
  const clearSession = useAuthStore((s) => s.clearSession)

  const isWebauthnSupported = browserSupportsWebAuthn()

  function handleToggleMode() {
    setMode((prev) => (prev === 'totp' ? 'backup_code' : 'totp'))
  }

  /**
   * 보안 키로 MFA 인증을 수행한다.
   *
   * B-5 토큰 생명주기 규칙.
   * - EC-1 NotAllowedError: 인라인 에러 + 화면 유지 + challengeToken 보존 (재시도 가능)
   * - EC-4 401 invalid_code: 인라인 에러 + 화면 유지 + challengeToken 보존 (재시도 가능)
   * - EC-5 401 mfa_challenge_expired: 토큰 실제 만료 → onBackToLogin으로 1단계 복귀
   */
  async function handleWebauthnVerify() {
    setWebauthnError(null)
    setIsWebauthnPending(true)
    try {
      const tokenData = await authenticateWithSecurityKey(challengeToken, trustDevice)

      // 성공 경로 — MfaCodeInput 성공 경로와 동형
      setAccessToken(tokenData.access_token)

      const user = await apiGet('/api/v1/users/me/whoami', WhoamiResponseSchema).catch(
        (err: unknown) => {
          clearSession()
          throw err
        },
      )

      setSession({ accessToken: tokenData.access_token, user })
      onSuccess?.()
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        const parsed = ApiErrorResponseSchema.safeParse(err.body)
        const errorCode = parsed.success ? parsed.data.error : ''

        if (errorCode === 'mfa_challenge_expired') {
          // EC-5: 토큰 만료 → 1단계 복귀 (challengeToken 폐기는 부모의 handleBackToLogin이 담당)
          onBackToLogin()
          return
        }

        // EC-4: verify 실패. 코드 입력이 아닌 보안 키 흐름이라 webauthn 전용 문구를 쓴다
        // ("코드가 올바르지 않습니다"는 부적합). rate-limit은 의미가 분명하므로 그대로 노출한다.
        setWebauthnError(
          errorCode === 'too_many_attempts'
            ? mfaErrorMessage('too_many_attempts')
            : mfaStrings.webauthnVerifyFailed,
        )
        return
      }

      // EC-1: NotAllowedError(사용자 취소) 등 브라우저 의식 예외 → 보안 키 전용 문구 + 화면 유지
      setWebauthnError(mfaStrings.webauthnVerifyFailed)
    } finally {
      setIsWebauthnPending(false)
    }
  }

  return (
    <>
      {/* 체크박스는 MfaCodeInput 바깥에 배치 — key={mode} 재마운트에 영향받지 않도록 한다 (E6) */}
      <div className="flex items-center gap-2 py-1">
        <input
          id="trust-device-checkbox"
          type="checkbox"
          checked={trustDevice}
          onChange={(e) => { setTrustDevice(e.target.checked) }}
          className="h-4 w-4 cursor-pointer"
        />
        <label htmlFor="trust-device-checkbox" className="text-sm cursor-pointer select-none">
          {mfaStrings.trustedDevicesLoginCheckboxLabel}
        </label>
      </div>
      <MfaCodeInput
        key={mode}
        mode={mode}
        challengeToken={challengeToken}
        onSuccess={onSuccess}
        onBackToLogin={onBackToLogin}
        onToggleMode={handleToggleMode}
        trustDevice={trustDevice}
      />
      {isWebauthnSupported && (
        <div className="mt-2 space-y-2">
          {webauthnError !== null && (
            <p role="alert" className="text-destructive text-sm">
              {webauthnError}
            </p>
          )}
          <Button
            type="button"
            variant="outline"
            className="w-full"
            onClick={() => void handleWebauthnVerify()}
            disabled={isWebauthnPending}
          >
            {mfaStrings.webauthnVerifyButton}
          </Button>
        </div>
      )}
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// LoginForm — 오케스트레이터 (단계 전환 + route 조회 담당)
// ─────────────────────────────────────────────────────────────────────────────

interface LoginFormProps {
  onSuccess?: () => void
}

/**
 * 로그인 폼. step 상태로 2단계를 오케스트레이션한다.
 *
 * 1단계(form): provider 드롭다운 + username + password + SSO 를 **한 화면**에.
 * - username 의 도메인은 blur/디바운스로 배경 조회해 매칭 시 SSO 버튼을 띄운다
 * - login 200 mfa_required:true → MFA 단계 진입 (챌린지 토큰은 컴포넌트 메모리 보관)
 *
 * 2단계(mfa): TOTP / 백업 코드 / 보안 키 → verify → 성공 시 기존 성공 경로 수렴
 *
 * 이메일 선입력 단계는 폐기됐다(FR-AU-07 deviation). 도메인 라우팅 기능 자체는
 * {@link LoginCredentialsForm} 의 배경 조회로 보존된다.
 */
export const LoginForm = ({ onSuccess }: LoginFormProps) => {
  // step: 'form' | 'mfa'
  const [step, setStep] = useState<'form' | 'mfa'>('form')
  // 챌린지 토큰은 컴포넌트 메모리에만 보관한다(authStore/sessionStorage 영속 금지 — NFR-1)
  const [mfaChallengeToken, setMfaChallengeToken] = useState<string | null>(null)

  const {
    data: providers,
    isLoading: isProvidersLoading,
    isError: isProvidersError,
  } = useQuery<ProviderEntry[]>({
    queryKey: ['auth', 'providers'],
    queryFn: fetchProviders,
    staleTime: 60_000,
  })

  const effectiveProviders = useMemo<readonly ProviderEntry[]>(
    () =>
      isProvidersError || (providers !== undefined && providers.length === 0)
        ? LOCAL_FALLBACK
        : (providers ?? []),
    [isProvidersError, providers],
  )

  const { data: samlIdps } = useQuery<SamlIdp[]>({
    queryKey: ['saml', 'idps'],
    queryFn: fetchSamlIdps,
    staleTime: 60_000,
  })

  const { data: oidcProviders } = useQuery<OidcProvider[]>({
    queryKey: ['oidc', 'providers'],
    queryFn: fetchOidcProviders,
    staleTime: 60_000,
  })

  /** MFA 챌린지 수신 시 MFA step으로 전환한다. 챌린지 토큰은 메모리에만 보관 */
  function handleMfaRequired(challengeToken: string) {
    setMfaChallengeToken(challengeToken)
    setStep('mfa')
  }

  /** MFA step에서 "다시 로그인" 클릭 시 자격 증명 폼으로 복귀하고 챌린지 토큰을 폐기한다 */
  function handleBackToLogin() {
    setMfaChallengeToken(null)
    setStep('form')
  }

  if (step === 'mfa' && mfaChallengeToken !== null) {
    return (
      <LoginMfaStep
        challengeToken={mfaChallengeToken}
        onSuccess={onSuccess}
        onBackToLogin={handleBackToLogin}
      />
    )
  }

  return (
    <LoginCredentialsForm
      onSuccess={onSuccess}
      onMfaRequired={handleMfaRequired}
      providers={effectiveProviders}
      isProvidersLoading={isProvidersLoading}
      samlIdps={samlIdps ?? []}
      oidcProviders={oidcProviders ?? []}
    />
  )
}
