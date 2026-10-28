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
    /**
     * 다이어그램 노드 좌표 (FR-WF-07 D8). `null` 이면 자동 배치다.
     *
     * ★ **`.optional()` 을 쓰지 않는다.** 서버 `DraftStateDto` 가 기본값 `null` 로 항상 실어
     * 보내므로, 필수-nullable 로 두면 「서버가 안 보냄」과 「좌표가 없음」이 구분된다. 선택 필드로
     * 열면 백엔드가 필드를 빠뜨려도 화면이 조용히 자동 배치로 떨어져 아무도 모른다.
     *
     * 발행 시 `workflow_statuses.layout_x/layout_y` 로 내려가고, 초안을 새로 뜰 때
     * `CurrentDefinitionReader` 가 그것을 되읽어 여기에 싣는다 — 그 왕복이 끊기면 배치가 사라진다.
     */
    layoutX: z.number().nullable(),
    layoutY: z.number().nullable(),
  })
  .strict()

/** 전환 화면에 올릴 수 있는 필드 — 백엔드 `DraftScreenFieldDto.FIELDS` · V212 CHECK 와 같은 목록 · 같은 순서. */
export const SCREEN_FIELDS = ['resolution', 'assignee', 'comment'] as const

/** 전환 화면의 필드 한 칸 (FR-WF-01 D10). 백엔드 `DraftScreenFieldDto` 와 1:1. 필수 여부의 정본이다. */
export const draftScreenFieldSchema = z
  .object({
    field: z.enum(SCREEN_FIELDS),
    required: z.boolean(),
  })
  .strict()

/**
 * 초안의 전환 항목. 백엔드 `DraftTransitionDto` 7필드와 1:1.
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
    /** 전환 화면 (FR-WF-01 D10). 비면 화면 없이 바로 전환한다. 서버가 늘 실어 보낸다. */
    screen: z.array(draftScreenFieldSchema),
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
 * @property scopes 이 워크플로우를 쓰는 프로젝트와 거기서 맡는 업무 유형 — 이관 예외 선택지(R11C).
 *   `exceptListed` 가 참이면 `issueTypeIds` **밖의** 유형 전부다(스킴 기본 매핑).
 */
export const publishPreviewSchema = z
  .object({
    baseVersion: z.number().int().nonnegative(),
    currentVersion: z.number().int().nonnegative(),
    removedStatusKeys: z.array(z.string()),
    pendingIssueCounts: z.record(z.string(), z.number()),
    scopes: z.array(
      z
        .object({ projectKey: z.string(), issueTypeIds: z.array(z.number().int()), exceptListed: z.boolean() })
        .strict(),
    ),
  })
  .strict()

/** `POST /publish` 응답. `versionNo` 는 이 워크플로우 안에서 1 부터 증가하는 발행 회차. */
export const publishResponseSchema = z.object({ versionNo: z.number().int().positive() }).strict()

/**
 * `POST /publish/migrate` 202 응답. 이 id 들로 `GET /api/v1/bulk-operations/{id}` 를 폴링한다.
 * 예외가 있으면 (프로젝트, 유형)마다 작업이 하나씩 늘어난다(R11C). `bulkOperationId` 는 첫 id 다.
 */
export const migrateResponseSchema = z
  .object({ bulkOperationId: z.string().uuid(), bulkOperationIds: z.array(z.string().uuid()).min(1) })
  .strict()

// ─────────────────────────────────────────────────────────────────────────────
// 추론 타입
// ─────────────────────────────────────────────────────────────────────────────

export type DraftRule = z.infer<typeof draftRuleSchema>
export type DraftState = z.infer<typeof draftStateSchema>
export type DraftScreenField = z.infer<typeof draftScreenFieldSchema>
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

/** 이관 예외 — 이 (프로젝트, 업무 유형)의 `fromStatusKey` 이슈는 기본 대상 대신 `toStatusKey` 로 간다(R11C). */
export interface StatusMappingExceptionInput extends StatusMappingInput {
  projectKey: string
  issueTypeId: number
}
