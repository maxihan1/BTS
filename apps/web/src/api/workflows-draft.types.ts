// 초안·발행 API 의 Zod 스키마·입력 타입 — 백엔드 WorkflowDraftDtos 와 1:1
import { z } from 'zod'
import { stateCategorySchema, transitionKindSchema } from './workflows'

/**
 * 초안 전환의 규칙 항목 (validator · post-action 공용).
 *
 * ★ **`config` 를 `unknown` 으로 열어 둔다.** 백엔드 `DraftRuleDto.config` 가
 * `Map<String, Any?>` 이고 타입별 파라미터가 제각각이다. 화면은 이 값을 해석하지 않고
 * **그대로 되돌려 보내는 것이 임무**라, 여기서 형태를 좁히면 모르는 타입의 규칙이 저장에서
 * 조용히 깎여 나간다.
 */
export const draftRuleSchema = z
  .object({
    type: z.string().min(1),
    config: z.record(z.string(), z.unknown()),
  })
  .strict()

/**
 * 초안의 상태 항목. 백엔드 `DraftStateDto` 4필드와 1:1.
 *
 * `name`·`category` 의 정본은 전역 카탈로그다 — 초안이 다른 값을 담으면 저장이 400 이다
 * (`DraftIdentityGuard.requireStatesMatchCatalog`). 화면이 임의로 채우지 말고 카탈로그 값을 싣는다.
 */
export const draftStateSchema = z
  .object({
    key: z.string().min(1),
    name: z.string().min(1),
    category: stateCategorySchema,
    displayOrder: z.number().int(),
  })
  .strict()

/**
 * 초안의 전환 항목. 백엔드 `DraftTransitionDto` 6필드와 1:1.
 *
 * @property from 출발 상태 키. `GLOBAL`·`INITIAL` 은 null 이어야 한다 — 출발지가 없다는 것이
 *   그 두 종류의 정의다. **생략이 아니라 null 로 실린다.**
 *
 * ★ 초안 전환에는 `id` 가 없다. 초안은 아직 DB 행이 아니라 전환 identity 가 없고, 발행
 * 시점에 DB 가 실제 id 를 정한다. 화면의 목록 key 는 로컬에서 따로 붙인다.
 */
export const draftTransitionSchema = z
  .object({
    from: z.string().min(1).nullable(),
    to: z.string().min(1),
    name: z.string(),
    kind: transitionKindSchema,
    validators: z.array(draftRuleSchema),
    postActions: z.array(draftRuleSchema),
  })
  .strict()

/** 초안 정의 전체. `workflow_drafts.definition` JSONB 의 형태이자 발행의 입력. */
export const draftDefinitionSchema = z
  .object({
    key: z.string().min(1),
    name: z.string(),
    description: z.string().nullable(),
    states: z.array(draftStateSchema),
    transitions: z.array(draftTransitionSchema),
  })
  .strict()

/**
 * `GET /draft` · `POST /reset-to-default` 응답.
 *
 * @property baseVersion 저장·발행·이관·복원에 **그대로 되실어 보낼** 앵커. 편집기가 이 값을
 *   들고 있다가 네 요청 모두에 싣는다. `previewPublish` 의 `currentVersion` 을 여기에 대입하면
 *   낙관적 락이 풀린다 — 백엔드 `PublishRequest` KDoc 이 지목한 실패다.
 * @property exists 저장된 초안이 실제로 있었는지. false 면 아직 DB 행이 없다.
 * @property canResetToDefault 「기본값으로 복원」을 띄울지. 서버가 origin 과 YAML 실재를 함께
 *   보고 낸 판정이라 화면이 다시 계산하지 않는다.
 */
export const draftResponseSchema = z
  .object({
    definition: draftDefinitionSchema,
    baseVersion: z.number().int().nonnegative(),
    exists: z.boolean(),
    canResetToDefault: z.boolean(),
  })
  .strict()

/**
 * `POST /publish/preview` 응답.
 *
 * @property currentVersion 지금 DB 의 버전. `baseVersion` 과 다르면 남이 먼저 발행한 것이다.
 *   ★ **앵커로 쓰지 마라.** 이 값을 되실어 보내면 락이 풀린다.
 * @property removedStatusKeys 발행하면 이 워크플로우에서 빠지는 상태 키.
 * @property pendingIssueCounts 그중 이슈가 남은 상태와 건수. 비어 있지 않으면 발행이 막힌다.
 */
export const publishPreviewSchema = z
  .object({
    baseVersion: z.number().int().nonnegative(),
    currentVersion: z.number().int().nonnegative(),
    removedStatusKeys: z.array(z.string()),
    pendingIssueCounts: z.record(z.string(), z.number()),
  })
  .strict()

/** `POST /publish` 응답. `versionNo` 는 이 워크플로우 안에서 1 부터 증가하는 발행 회차. */
export const publishResponseSchema = z.object({ versionNo: z.number().int().positive() }).strict()

/** `POST /publish/migrate` 202 응답. 이 id 로 `GET /api/v1/bulk-operations/{id}` 를 폴링한다. */
export const migrateResponseSchema = z.object({ bulkOperationId: z.string().uuid() }).strict()

// ─────────────────────────────────────────────────────────────────────────────
// 추론 타입
// ─────────────────────────────────────────────────────────────────────────────

export type DraftRule = z.infer<typeof draftRuleSchema>
export type DraftState = z.infer<typeof draftStateSchema>
export type DraftTransition = z.infer<typeof draftTransitionSchema>
export type DraftDefinition = z.infer<typeof draftDefinitionSchema>
export type DraftResponse = z.infer<typeof draftResponseSchema>
export type PublishPreview = z.infer<typeof publishPreviewSchema>
export type PublishResult = z.infer<typeof publishResponseSchema>
export type MigrateAccepted = z.infer<typeof migrateResponseSchema>

/**
 * 상태 하나의 이관 대상 매핑.
 *
 * @property fromStatusKey 이번 발행에서 사라지는 상태 키.
 * @property toStatusKey 옮겨 갈 상태 키. **초안에 남아 있고 지금 발행본에도 있는** 상태여야
 *   한다(백엔드 F8·F16). 둘 중 하나만 만족하면 400 이다.
 */
export interface StatusMappingInput {
  fromStatusKey: string
  toStatusKey: string
}
