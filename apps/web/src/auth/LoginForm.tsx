// 로그인 폼 컴포넌트 — RHF + Zod 검증 + shadcn/ui Form + provider 드롭다운 + SAML IdP 버튼
import { useState } from 'react'
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
import { loginStrings } from '@/i18n/ko'
import { fetchSamlIdps } from '@/api/saml'
import type { SamlIdp } from '@/api/saml'

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

const loginFormSchema = z.object({
  provider: z.enum(['local', 'ldap-corp']),
  username: z.string().min(1, loginStrings.usernameRequired),
  password: z.string().min(1, loginStrings.passwordRequired),
})

type LoginFormValues = z.infer<typeof loginFormSchema>

interface LoginFormProps {
  onSuccess?: () => void
}

export const LoginForm = ({ onSuccess }: LoginFormProps) => {
  const [serverError, setServerError] = useState<string | null>(null)
  const mutation = useLoginMutation()

  const { data: samlIdps } = useQuery<SamlIdp[]>({
    queryKey: ['saml', 'idps'],
    queryFn: fetchSamlIdps,
    staleTime: 60_000,
  })

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: {
      provider: 'local',
      username: '',
      password: '',
    },
  })

  function onSubmit(values: LoginFormValues) {
    setServerError(null)
    mutation.mutate(values, {
      onSuccess: () => {
        onSuccess?.()
      },
      onError: (error: unknown) => {
        setServerError(resolveLoginErrorMessage(error))
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
                    <SelectItem value="local" role="option">
                      {loginStrings.providerLocal}
                    </SelectItem>
                    <SelectItem value="ldap-corp" role="option">
                      {loginStrings.providerLdapCorp}
                    </SelectItem>
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
      </form>
    </Form>
  )
}
