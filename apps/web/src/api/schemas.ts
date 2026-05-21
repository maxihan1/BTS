// 백엔드 인증 API 요청/응답 Zod 스키마 정의
import { z } from 'zod'

export const LoginRequestSchema = z.object({
  provider: z.enum(['local', 'ldap-corp']),
  username: z.string().min(1),
  password: z.string().min(1),
})

export const TokenResponseSchema = z.object({
  access_token: z.string().min(1),
  token_type: z.literal('Bearer'),
  expires_in: z.number(),
})

export const WhoamiResponseSchema = z.object({
  username: z.string(),
  email: z.string(),
  authMethod: z.string(),
  userId: z.string(),
})

export const ApiErrorResponseSchema = z.object({
  error: z.string(),
})

export type LoginRequest = z.infer<typeof LoginRequestSchema>
export type TokenResponse = z.infer<typeof TokenResponseSchema>
export type WhoamiResponse = z.infer<typeof WhoamiResponseSchema>
export type ApiErrorResponse = z.infer<typeof ApiErrorResponseSchema>
