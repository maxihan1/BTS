// Git 웹훅 등록/목록 REST 응답의 Zod 계약
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend CreateGitWebhookResponse/GitWebhookSummaryResponse DTO
// (com.bts.automation.adapter.web.dto.GitWebhookDtos.kt) 직렬화 형태와 1:1 대응.
// automation 응답은 `{data}` 봉투 없이 bare DTO를 직접 반환한다(automation-rules.types.ts 선례와 동일).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Git 호스팅 제공자 enum — backend `GitProvider` 2종(GITHUB/GITLAB) 1:1 대응(FR-AT-07 PR-C).
 * 대문자 원문 그대로 전송하는 계약이다(`GitProvider.kt:16-17` 서명 검증기 상수와 이름이 정확히
 * 같아야 하는 이유가 적혀 있다) — 소문자/BITBUCKET 등 화이트리스트 밖 값은 거부한다.
 */
export const gitProviderSchema = z.enum(['GITHUB', 'GITLAB'])

/**
 * Git 웹훅 등록 응답(201) Zod 스키마 — backend `CreateGitWebhookResponse` DTO 1:1 대응.
 * `token`은 **원문 토큰을 노출하는 유일한 지점**(1회성) — 목록 응답({@link gitWebhookSummarySchema})
 * 에는 이 필드가 없다.
 *
 * ## ★ webhookUrl 에 `.url()` 을 붙이지 않는다 (NFR4)
 * backend가 내려주는 `webhookUrl`은 **origin 없는 절대 경로**다(`/api/v1/webhooks/git/<token>`,
 * `GitWebhookDtos.kt:66-69` — automation 모듈이 배포 origin(스킴/호스트)을 모르므로, 호출하는
 * 화면이 자신의 origin을 앞에 붙여 완전한 URL을 만들도록 명시 위임한다). Zod `.url()`은 스킴+호스트가
 * 포함된 완전한 URL만 통과시키므로, 여기 붙이면 **정상 응답이 파싱 단계에서 거부된다** —
 * "URL이니까 `.url()`이 맞다"는 직관이 정확히 이 회귀를 만든다. 후속 세션에서 "강화" 목적으로
 * `.url()`을 추가하지 말 것 — `z.string()`이 의도된 최종 형태다.
 */
export const createGitWebhookResponseSchema = z.object({
  id: z.string().uuid(),
  provider: gitProviderSchema,
  webhookUrl: z.string(),
  token: z.string(),
})

/**
 * Git 웹훅 목록 조회(200) 항목 1건의 Zod 스키마 — backend `GitWebhookSummaryResponse` DTO 1:1 대응.
 * **token·secret 관련 필드를 하나도 두지 않는다** — backend가 애초에 이 DTO에 그런 필드를
 * 만들지 않아(`GitWebhookDtos.kt:84-88`) 타입 상 새어 나갈 수 없다(`AutomationRuleResponse`
 * 선례와 동형 방어). `createdAt`은 backend `Instant`가 ISO-8601 문자열로 직렬화된 값이다
 * (epoch 숫자 배열이 아니다, NFR5).
 */
export const gitWebhookSummarySchema = z.object({
  id: z.string().uuid(),
  provider: gitProviderSchema,
  createdAt: z.string().datetime(),
  createdBy: z.string().uuid(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** Git 호스팅 제공자 */
export type GitProvider = z.infer<typeof gitProviderSchema>

/** Git 웹훅 등록 응답 타입(1회성 원문 토큰 포함) */
export type CreateGitWebhookResponse = z.infer<typeof createGitWebhookResponseSchema>

/** Git 웹훅 목록 항목 타입(token·secret 미포함) */
export type GitWebhookSummary = z.infer<typeof gitWebhookSummarySchema>

/**
 * Git 웹훅 등록 요청 입력 타입 — backend `CreateGitWebhookRequest` DTO 1:1 대응.
 * `secret`은 provider 서명(HMAC) 검증용 공유 비밀 원문이다. 서버가 저장 시 AES-256-GCM
 * 암호문으로만 남기므로, 이 타입이 표현하는 요청 바디를 벗어나 다시 노출되지 않는다.
 */
export interface CreateGitWebhookInput {
  provider: GitProvider
  secret: string
}
