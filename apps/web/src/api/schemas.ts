// 백엔드 인증 API 요청/응답 Zod 스키마 정의
import { z } from 'zod'

// provider는 GET /api/v1/auth/providers 응답의 id값 — 동적이므로 enum 대신 string.
// 구체 값 검증은 백엔드에서 수행한다.
export const LoginRequestSchema = z.object({
  provider: z.string().min(1),
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
  mustChangePassword: z.boolean(),
  isSystemAdmin: z.boolean(),
})

export const ApiErrorResponseSchema = z.object({
  error: z.string(),
})

export type LoginRequest = z.infer<typeof LoginRequestSchema>
export type TokenResponse = z.infer<typeof TokenResponseSchema>
export type WhoamiResponse = z.infer<typeof WhoamiResponseSchema>
export type ApiErrorResponse = z.infer<typeof ApiErrorResponseSchema>
