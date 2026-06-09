// 로그인 폼 컴포넌트 — identifier-first 2단계 (1단계: 이메일, 2단계: provider+username+password)
import { useState, useEffect, useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
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
import { loginStrings } from '@/i18n/ko'
import { fetchSamlIdps } from '@/api/saml'
import { fetchOidcProviders } from '@/api/oidc'
import { fetchProviders } from '@/api/providers'
import { fetchRoute } from '@/api/route'
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
      onSuccess: () => {
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
// LoginForm — 오케스트레이터 (단계 전환 + route 조회 담당)
// ─────────────────────────────────────────────────────────────────────────────

interface LoginFormProps {
  onSuccess?: () => void
}

/**
 * identifier-first 2단계 로그인 폼.
 *
 * 1단계: 이메일 입력 → "계속"
 * - 이메일에 @가 있으면 도메인으로 route 조회
 * - matched:true → SSO 리다이렉트 (window.location.assign)
 * - matched:false / 조회 에러 / @없음 → 2단계 폼으로 fall-through
 *
 * 2단계: provider 드롭다운 + username(이메일 프리필) + password
 */
export const LoginForm = ({ onSuccess }: LoginFormProps) => {
  // step: 'email' | 'form'
  const [step, setStep] = useState<'email' | 'form'>('email')
  const [prefillEmail, setPrefillEmail] = useState('')
  const [isRouting, setIsRouting] = useState(false)

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
    } catch {
      // fetch 에러는 fail-safe: 2단계로 fall-through해 사용자가 폼 로그인 가능하게 한다
    } finally {
      setIsRouting(false)
    }

    // 미매칭 또는 에러 → 2단계
    setStep('form')
  }

  if (step === 'email') {
    return <LoginStep1 onContinue={handleEmailContinue} isPending={isRouting} />
  }

  return (
    <LoginStep2
      key={prefillEmail}
      prefillEmail={prefillEmail}
      onSuccess={onSuccess}
      providers={effectiveProviders}
      isProvidersLoading={isProvidersLoading}
      samlIdps={samlIdps ?? []}
      oidcProviders={oidcProviders ?? []}
    />
  )
}
