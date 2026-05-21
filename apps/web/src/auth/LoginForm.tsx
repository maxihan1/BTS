// 로그인 폼 컴포넌트 — RHF + Zod 검증 + shadcn/ui Form + provider 드롭다운
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
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
import { loginStrings } from '@/i18n/ko'
import { ApiErrorResponseSchema } from '@/api/schemas'

/** 백엔드 에러 코드 → 한국어 메시지 매핑 */
function resolveLoginErrorMessage(error: unknown): string {
  if (!(error instanceof Error)) {
    return loginStrings.errorDefault
  }

  // useLoginMutation이 이미 한국어 메시지를 Error.message에 담아 throw한다
  const message = error.message

  // 알려진 한국어 메시지면 그대로 반환
  if (
    message === loginStrings.errorInvalidCredentials ||
    message === loginStrings.errorMfaRequired
  ) {
    return message
  }

  // ApiErrorResponseSchema 파싱 시도 (직접 ApiError가 올 경우 대비)
  const bodyMatch = ApiErrorResponseSchema.safeParse(
    (() => {
      try {
        return JSON.parse(message)
      } catch {
        return null
      }
    })(),
  )

  if (bodyMatch.success) {
    const code = bodyMatch.data.error
    if (code === 'invalid_credentials') return loginStrings.errorInvalidCredentials
    if (code === 'mfa_required') return loginStrings.errorMfaRequired
  }

  return message || loginStrings.errorDefault
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
      </form>
    </Form>
  )
}
