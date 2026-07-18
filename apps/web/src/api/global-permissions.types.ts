// 전역 권한 부여 BC Zod 스키마 + 추론 타입 정의 — backend GlobalPermissionGrantController DTO 1:1 대응 (FR-PM-10)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend GrantResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 부여 대상 종류 enum — backend GranteeType(USER|GROUP) 1:1 대응.
 */
export const granteeTypeEnum = z.enum(['USER', 'GROUP'])

/**
 * 전역 권한 부여 단건 응답 Zod 스키마.
 * backend GlobalPermissionGrantController GrantResponse DTO 1:1 대응.
 * permission은 z.string()으로 파싱한다 — 화이트리스트는 백엔드가 소유(NFR-5).
 */
export const grantResponseSchema = z.object({
  id: z.string().uuid(),
  permission: z.string(),
  granteeType: granteeTypeEnum,
  granteeId: z.string().uuid(),
  grantedBy: z.string().uuid(),
  createdAt: z.string(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 전역 권한 부여 대상 종류 타입 */
export type GranteeType = z.infer<typeof granteeTypeEnum>

/** 전역 권한 부여 단건 응답 타입 */
export type GrantResponse = z.infer<typeof grantResponseSchema>

/** 전역 권한 부여 생성 입력 타입 — permission·granteeType·granteeId 필수 */
export interface CreateGlobalPermissionInput {
  permission: string
  granteeType: GranteeType
  granteeId: string
}
