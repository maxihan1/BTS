// 로그인 폼 컴포넌트 — RHF + Zod 검증 + shadcn/ui Form + provider 드롭다운(동적) + SAML IdP 버튼 + OIDC provider 버튼
import { useEffect } from 'react'
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
import { loginStrings } from '@/i18n/ko'
import { fetchSamlIdps } from '@/api/saml'
import { fetchOidcProviders } from '@/api/oidc'
import { fetchProviders } from '@/api/providers'
import type { SamlIdp } from '@/api/saml'
import type { OidcProvider } from '@/api/oidc'
import type { ProviderEntry } from '@/api/providers'

/**
 * provider id → 한국어 라벨 매핑.
 * 키가 없으면 응답의 displayName을 그대로 사용한다(fallback).
 */
const PROVIDER_LABEL_MAP: Readonly<Record<string, string>> = {
  local: loginStrings.providerLocal,
  ldap: loginStrings.providerLdapCorp,
}

/**
 * ProviderEntry의 id에 대응하는 표시 라벨을 반환한다.
 * 알려진 id(local/ldap)이면 i18n 매핑값, 없으면 displayName을 fallback으로 사용한다.
 */
function resolveProviderLabel(provider: ProviderEntry): string {
  return PROVIDER_LABEL_MAP[provider.id] ?? provider.displayName
}

/**
 * onError 콜백에서 받은 에러를 사용자 노출 한국어 메시지로 변환한다.
 * useLoginMutation은 이미 한국어 메시지를 Error.message에 담아 throw하므로
 * 그 값을 그대로 사용하고, Error가 아닌 경우에만 기본 메시지를 반환한다.
 */
function resolveLoginErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return loginStrings.errorDefault
}

// provider 값은 동적이므로 enum 대신 z.string().min(1) 사용.
// 구체 값 검증은 useLoginMutation → backend 응답에서 수행한다.
const loginFormSchema = z.object({
  provider: z.string().min(1),
  username: z.string().min(1, loginStrings.usernameRequired),
  password: z.string().min(1, loginStrings.passwordRequired),
})

type LoginFormValues = z.infer<typeof loginFormSchema>

interface LoginFormProps {
  onSuccess?: () => void
}

export const LoginForm = ({ onSuccess }: LoginFormProps) => {
  const mutation = useLoginMutation()

  const { data: providers, isLoading: isProvidersLoading } = useQuery<ProviderEntry[]>({
    queryKey: ['auth', 'providers'],
    queryFn: fetchProviders,
    staleTime: 60_000,
  })

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

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: {
      provider: '',
      username: '',
      password: '',
    },
  })

  // providers 응답이 도착하면 첫 항목을 기본 선택으로 설정한다.
  // react-usestate-stale-key-prop 패턴 주의: form.setValue로 명시 설정해야 한다.
  // providers가 이미 있으면 form 값이 비어있을 때만 초기값을 설정한다.
  useEffect(() => {
    const firstProvider = providers?.[0]
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
                    {(providers ?? []).map((provider) => (
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

        <SamlIdpButtons idps={samlIdps ?? []} />
        <OidcIdpButtons providers={oidcProviders ?? []} />
      </form>
    </Form>
  )
}
