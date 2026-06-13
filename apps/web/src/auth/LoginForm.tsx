// 로그인 폼 컴포넌트 — identifier-first 2단계 + MFA 3단계 (이메일 → provider+pw → TOTP 코드)
import { useState, useEffect, useMemo } from 'react'
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
// 1단계 — 이메일 입력 화면
// ─────────────────────────────────────────────────────────────────────────────

interface Step1Props {
  /** "계속" 클릭 시 — 이메일 값을 받아 부모가 route 조회를 처리한다 */
  onContinue: (email: string) => void
  isPending: boolean
}

const emailSchema = z.object({ email: z.string() })
type EmailFormValues = z.infer<typeof emailSchema>

/**
 * 1단계: 이메일 입력 + "계속" 버튼만 렌더한다.
 * provider 드롭다운/username/password/SSO 버튼은 이 단계에서 미표시.
 */
const LoginStep1 = ({ onContinue, isPending }: Step1Props) => {
  const form = useForm<EmailFormValues>({
    resolver: zodResolver(emailSchema),
    defaultValues: { email: '' },
  })

  function onSubmit(values: EmailFormValues) {
    onContinue(values.email)
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate className="space-y-4">
        <FormField
          control={form.control}
          name="email"
          render={({ field }) => (
            <FormItem>
              <FormLabel htmlFor="login-email">{loginStrings.emailLabel}</FormLabel>
              <FormControl>
                <Input
                  id="login-email"
                  type="text"
                  autoComplete="email"
                  aria-required="true"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <Button type="submit" className="w-full" disabled={isPending}>
          {loginStrings.continueButton}
        </Button>
      </form>
    </Form>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 2단계 — provider + username + password 폼
// ─────────────────────────────────────────────────────────────────────────────

interface Step2Props {
  /** 1단계에서 입력한 이메일 — username 필드에 프리필 */
  prefillEmail: string
  onSuccess?: () => void
  /** login 응답이 mfa_required:true일 때 챌린지 토큰을 전달하며 MFA step으로 진입 */
  onMfaRequired: (challengeToken: string) => void
  providers: readonly ProviderEntry[]
  isProvidersLoading: boolean
  samlIdps: SamlIdp[]
  oidcProviders: OidcProvider[]
}

/**
 * 2단계: 기존 provider 드롭다운 + username + password + SAML/OIDC 버튼 폼.
 * prefillEmail이 username 필드의 초기값으로 설정된다 (사용자 수정 가능).
 *
 * key prop으로 재마운트되므로 prefillEmail이 바뀌어도 stale state 없음
 * (react-usestate-stale-key-prop 패턴).
 *
 * providers는 부모 LoginForm이 이미 계산해 prop으로 전달한다.
 * 마운트 시 providers[0].id가 이미 확정돼 있으면 defaultValues에서 직접 설정한다.
 * providers가 아직 빈 배열이면 useEffect에서 첫 항목 도착 시 setValue로 설정한다.
 */
const LoginStep2 = ({
  prefillEmail,
  onSuccess,
  onMfaRequired,
  providers,
  isProvidersLoading,
  samlIdps,
  oidcProviders,
}: Step2Props) => {
  const mutation = useLoginMutation()

  // providers[0]?.id가 이미 있으면 마운트 시 기본값으로 사용한다.
  // 없으면 '' — useEffect에서 채운다.
  const initialProvider = providers[0]?.id ?? ''

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: {
      provider: initialProvider,
      // 1단계에서 입력한 이메일을 username 초기값으로 설정한다
      username: prefillEmail,
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
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

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

        <Button type="submit" className="w-full" disabled={mutation.isPending}>
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
      const tokenData = await verifyMfa(challengeToken, code, mode)

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
        <p className="text-sm text-muted-foreground">{guideText}</p>

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
      const tokenData = await authenticateWithSecurityKey(challengeToken)

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
      <MfaCodeInput
        key={mode}
        mode={mode}
        challengeToken={challengeToken}
        onSuccess={onSuccess}
        onBackToLogin={onBackToLogin}
        onToggleMode={handleToggleMode}
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
 * identifier-first 로그인 폼. step 상태로 3단계를 오케스트레이션한다.
 *
 * 1단계: 이메일 입력 → "계속"
 * - 이메일에 @가 있으면 도메인으로 route 조회
 * - matched:true → SSO 리다이렉트 (window.location.assign)
 * - matched:false / 조회 에러 / @없음 → 2단계 폼으로 fall-through
 *
 * 2단계: provider 드롭다운 + username(이메일 프리필) + password
 * - login 200 mfa_required:true → 3단계 MFA 진입 (챌린지 토큰 컴포넌트 메모리 보관)
 *
 * 3단계: TOTP 코드 입력 → verify → 성공 시 기존 성공 경로 수렴
 */
export const LoginForm = ({ onSuccess }: LoginFormProps) => {
  // step: 'email' | 'form' | 'mfa'
  const [step, setStep] = useState<'email' | 'form' | 'mfa'>('email')
  const [prefillEmail, setPrefillEmail] = useState('')
  const [isRouting, setIsRouting] = useState(false)
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

  /**
   * 1단계 "계속" 핸들러.
   * @가 없으면 route 조회 없이 즉시 2단계로 진입한다.
   * 조회 실패 시에도 fail-safe로 2단계 진입한다(사용자 막지 않음).
   */
  async function handleEmailContinue(email: string) {
    setPrefillEmail(email)

    const atIndex = email.indexOf('@')
    // @가 없거나 도메인 부분이 비어 있으면 조회 없이 2단계로
    const domain = atIndex !== -1 ? email.slice(atIndex + 1) : ''
    if (domain === '') {
      setStep('form')
      return
    }

    setIsRouting(true)
    try {
      const result = await fetchRoute(domain)
      if (result.matched) {
        // SSO 매칭 — 브라우저를 IdP로 리다이렉트한다
        window.location.assign(ssoEntryUrl(result.type, result.registrationId))
        return
      }
    } catch (err) {
      // fetch 에러는 fail-safe: 2단계로 fall-through해 사용자가 폼 로그인 가능하게 한다.
      // 정상 운영(네트워크 일시 단절 등)에서도 발생할 수 있는 폴백 경로라 error가 아닌 warn으로 남긴다.
      console.warn('[LoginForm] route 조회 실패 — 2단계로 fall-through', err)
    } finally {
      setIsRouting(false)
    }

    // 미매칭 또는 에러 → 2단계
    setStep('form')
  }

  /** MFA 챌린지 수신 시 MFA step으로 전환한다. 챌린지 토큰은 메모리에만 보관 */
  function handleMfaRequired(challengeToken: string) {
    setMfaChallengeToken(challengeToken)
    setStep('mfa')
  }

  /** MFA step에서 "다시 로그인" 클릭 시 1단계로 복귀하고 챌린지 토큰을 초기화한다 */
  function handleBackToLogin() {
    setMfaChallengeToken(null)
    setStep('email')
  }

  if (step === 'email') {
    return <LoginStep1 onContinue={handleEmailContinue} isPending={isRouting} />
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
    <LoginStep2
      key={prefillEmail}
      prefillEmail={prefillEmail}
      onSuccess={onSuccess}
      onMfaRequired={handleMfaRequired}
      providers={effectiveProviders}
      isProvidersLoading={isProvidersLoading}
      samlIdps={samlIdps ?? []}
      oidcProviders={oidcProviders ?? []}
    />
  )
}
