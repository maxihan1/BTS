// 이슈 프로젝트 간 이동 API 클라이언트 + Zod 스키마 + TanStack Query 훅 — FR-MV-01 Task 3
import { z } from 'zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/api/client'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import { componentResponseSchema } from '@/api/components.types'
import { versionResponseSchema } from '@/api/versions.types'
import { customFieldResponseSchema } from '@/api/custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * backend 공통 응답 래퍼 `{ data: T }` 파싱 스키마.
 * issue-links.ts·issue-versions.ts 동형 로컬 재정의 패턴.
 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend DTO 1:1 미러
// MovePreviewService.kt / MoveDtos.kt / WorkflowStateView.kt 실측 기준
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 상태 단건 뷰 스키마.
 * backend WorkflowStateView(key, name, isDone) 1:1 대응.
 * isDone: 기본값 false — WorkflowStateView.kt 실측.
 */
const workflowStateViewSchema = z.object({
  /** 상태 키. 예: "open", "in-progress" */
  key: z.string().min(1),
  /** 사용자 노출 상태 이름 */
  name: z.string().min(1),
  /**
   * DONE 카테고리 여부.
   * 이동 마법사에서 targetStateIsDone 채우는 데 사용된다 (G2).
   */
  isDone: z.boolean().default(false),
})

/**
 * 워크플로우 상태 호환성 섹션 스키마.
 * backend WorkflowPreviewSection 1:1 대응.
 * suggestedStateKey: null 가능 — 대상 상태 목록 비었을 때.
 */
const workflowPreviewSectionSchema = z.object({
  /** 현재 상태가 대상 워크플로우에 존재하면 true */
  compatible: z.boolean(),
  /** 대상 프로젝트 전체 워크플로우 상태 목록 */
  targetStates: z.array(workflowStateViewSchema),
  /**
   * 권장 대상 상태 키.
   * compatible=true → 현재 상태 키, false → 첫 번째 상태 키, 없으면 null.
   */
  suggestedStateKey: z.string().nullable(),
})

/**
 * 컴포넌트 자동매핑 섹션 스키마.
 * backend ResourceMappingSection 1:1 대응.
 * autoMapping value: UUID | null — 이름 불일치 시 null.
 */
const resourceMappingSectionSchema = z.object({
  /** 현재 이슈에 연결된 컴포넌트 목록 */
  current: z.array(componentResponseSchema),
  /** 대상 프로젝트 전체 활성 컴포넌트 목록 */
  target: z.array(componentResponseSchema),
  /**
   * 원본 컴포넌트 id → 대상 컴포넌트 id 자동매핑.
   * value가 null이면 대상에 동명 컴포넌트 없음(미매핑).
   */
  autoMapping: z.record(z.string().uuid(), z.string().uuid().nullable()),
})

/**
 * 버전 자동매핑 섹션 스키마.
 * backend VersionMappingSection 1:1 대응 — ResourceMappingSection과 동형.
 */
const versionMappingSectionSchema = z.object({
  /** 현재 이슈에 연결된 버전 목록 */
  current: z.array(versionResponseSchema),
  /** 대상 프로젝트 전체 활성 버전 목록 */
  target: z.array(versionResponseSchema),
  /**
   * 원본 버전 id → 대상 버전 id 자동매핑.
   * value가 null이면 대상에 동명 버전 없음(미매핑).
   */
  autoMapping: z.record(z.string().uuid(), z.string().uuid().nullable()),
})

/**
 * 커스텀필드 호환성 섹션 스키마.
 * backend CustomFieldPreviewSection 1:1 대응.
 */
const customFieldPreviewSectionSchema = z.object({
  /** 현재 이슈에 값이 있지만 대상 프로젝트에 없는 필드 목록 */
  removed: z.array(customFieldResponseSchema),
  /** 대상 프로젝트에서 필수이지만 현재 이슈에 값이 없는 필드 목록 */
  requiredMissing: z.array(customFieldResponseSchema),
})

/**
 * 서브태스크 노드별 preview 섹션 스키마.
 * backend SubtaskPreviewNode 1:1 대응.
 * issueTypeKey: null 가능 — 타입 조회 실패 시.
 * version: 자식 이슈 OCC 버전 (G1 추가).
 */
const subtaskPreviewNodeSchema = z.object({
  /** 자식 이슈 키 (이동 전 원본 키) */
  issueKey: z.string().min(1),
  /**
   * 자식 이슈 타입 키.
   * null이면 타입 조회 실패 — backend SubtaskPreviewNode.issueTypeKey: String? 실측.
   */
  issueTypeKey: z.string().nullable(),
  /**
   * 자식 이슈 OCC 버전.
   * move 요청의 자식 expectedVersion 채우는 데 사용된다 (G1).
   */
  version: z.number().int(),
  /** 워크플로우 상태 호환성 섹션 */
  workflow: workflowPreviewSectionSchema,
  /** 컴포넌트 자동매핑 섹션 */
  components: resourceMappingSectionSchema,
  /** affectsVersions 자동매핑 섹션 */
  affectsVersions: versionMappingSectionSchema,
  /** fixVersions 자동매핑 섹션 */
  fixVersions: versionMappingSectionSchema,
  /** 커스텀필드 호환성 섹션 */
  customFields: customFieldPreviewSectionSchema,
})

/**
 * 이슈 이동 preview 응답 스키마.
 * backend MovePreview 1:1 대응.
 * version: 루트 이슈 OCC 버전 (G1 추가).
 * subtasks: non-null array — backend emptyList() 기본값.
 */
export const movePreviewSchema = z.object({
  /**
   * 루트 이슈 OCC 버전.
   * move 요청의 expectedVersion 채우는 데 사용된다 (G1).
   */
  version: z.number().int(),
  /** 워크플로우 상태 호환성 섹션 */
  workflow: workflowPreviewSectionSchema,
  /** 컴포넌트 자동매핑 섹션 */
  components: resourceMappingSectionSchema,
  /** affectsVersions 자동매핑 섹션 */
  affectsVersions: versionMappingSectionSchema,
  /** fixVersions 자동매핑 섹션 */
  fixVersions: versionMappingSectionSchema,
  /** 커스텀필드 호환성 섹션 */
  customFields: customFieldPreviewSectionSchema,
  /**
   * 직접 자식 이슈 노드별 매핑 섹션.
   * 서브태스크 없으면 빈 배열 — non-null.
   */
  subtasks: z.array(subtaskPreviewNodeSchema).default([]),
})

/**
 * 이슈 이동 후 자식 이슈 이동 결과 스키마.
 * backend MovedSubtask 1:1 대응.
 */
const movedSubtaskSchema = z.object({
  /** 이동 전 원본 자식 이슈 키 */
  previousKey: z.string().min(1),
  /** 이동 후 새 자식 이슈 키 */
  issueKey: z.string().min(1),
})

/**
 * 이슈 이동 응답 스키마.
 * backend MoveResponse 1:1 대응.
 * movedSubtasks: non-null array — 단건이면 빈 배열.
 */
export const moveResponseSchema = z.object({
  /** 이동 후 새 이슈 키 */
  issueKey: z.string().min(1),
  /** 이동 전 원본 이슈 키 */
  previousKey: z.string().min(1),
  /**
   * 동반 이동된 자식 이슈 목록.
   * 단건 경로이면 빈 배열 — backend emptyList() 기본값.
   */
  movedSubtasks: z.array(movedSubtaskSchema).default([]),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 도출
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 이동 preview 응답 타입 */
export type MovePreview = z.infer<typeof movePreviewSchema>

/** 서브태스크 노드별 preview 섹션 타입 */
export type SubtaskPreviewNode = z.infer<typeof subtaskPreviewNodeSchema>

/** 워크플로우 상태 뷰 타입 */
export type WorkflowStateView = z.infer<typeof workflowStateViewSchema>

/** 이슈 이동 응답 타입 */
export type MoveResponse = z.infer<typeof moveResponseSchema>

/** 이동된 자식 이슈 타입 */
export type MovedSubtask = z.infer<typeof movedSubtaskSchema>

/**
 * 서브태스크 이동 매핑 요청 타입.
 * backend SubtaskMoveMapping DTO 1:1 대응.
 */
export interface SubtaskMoveMappingInput {
  /** 자식 이슈 키 (이동 전 원본 키) */
  issueKey: string
  /** 자식 이슈 OCC 낙관락 버전 */
  expectedVersion: number
  /** 대상 상태 키. null이면 소스 상태 키 그대로 사용 시도 */
  targetStateKey: string | null
  /** 대상 상태가 DONE 카테고리인지 여부 */
  targetStateIsDone: boolean
  /** 원본 컴포넌트 UUID → 대상 컴포넌트 UUID 매핑. value null이면 미매핑(제거) */
  componentMapping: Record<string, string | null>
  /** 원본 affects-version UUID → 대상 버전 UUID 매핑 */
  affectsVersionMapping: Record<string, string | null>
  /** 원본 fix-version UUID → 대상 버전 UUID 매핑 */
  fixVersionMapping: Record<string, string | null>
  /** 추가 커스텀 필드 값 */
  customFieldValues: Record<string, unknown>
}

/**
 * 이슈 이동 요청 페이로드 타입.
 * backend MoveRequest DTO 1:1 대응.
 */
export interface MoveIssueInput {
  /** 이동 대상 프로젝트 키 */
  targetProjectKey: string
  /** OCC 낙관락 버전 */
  expectedVersion: number
  /** 대상 상태 키. null이면 소스 상태 키 그대로 사용 시도 */
  targetStateKey: string | null
  /** 대상 상태가 DONE 카테고리인지 여부 */
  targetStateIsDone: boolean
  /** 원본 컴포넌트 UUID → 대상 컴포넌트 UUID 매핑. value null이면 미매핑(제거) */
  componentMapping: Record<string, string | null>
  /** 원본 affects-version UUID → 대상 버전 UUID 매핑 */
  affectsVersionMapping: Record<string, string | null>
  /** 원본 fix-version UUID → 대상 버전 UUID 매핑 */
  fixVersionMapping: Record<string, string | null>
  /** 추가 커스텀 필드 값 */
  customFieldValues: Record<string, unknown>
  /**
   * 동반 이동할 직접 자식 노드별 매핑.
   * 단건이면 빈 배열.
   */
  subtasks: SubtaskMoveMappingInput[]
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 이동 preview를 조회한다.
 * POST /api/v1/issues/{key}/move/preview → `{ data: MovePreview }` 언랩
 *
 * @param key 이동할 이슈 키
 * @param targetProjectKey 이동 대상 프로젝트 키
 * @throws ApiError — **403 · 404 · 400 뿐이다.** `MovePreviewService.preview` 가 던지는 예외는
 *   `IssueAccessDenied`(403) · `IssueNotFound`(404) · `IssueProjectNotFound`(404) **셋뿐**이고,
 *   여기에 `@Valid`(`MovePreviewRequest` 는 `@NotBlank` 만) 400 이 더해진다.
 *   - 403 — 권한 없음. **키 오타/미존재도 여기로 온다**(권한 assert 가 존재 확인보다 앞선다).
 *   - 404 — **이슈 미존재만** 운영에서 난다(권한이 프로젝트 스코프라 원본 프로젝트 권한이
 *     있으면 없는 이슈 키에서 404 가 뜬다). **대상 프로젝트 미존재 404 는 비-prod 전용**이다.
 *
 *   ★**422 는 preview 에서 나오지 않는다.** 이전 서술의 「422(동일 프로젝트 등)」은 거짓이었다 —
 *   preview 에는 동일 프로젝트 검사가 없다(그 검사는 실행 경로인 [moveIssue] 에만 있다).
 */
export async function previewMove(key: string, targetProjectKey: string): Promise<MovePreview> {
  const res = await apiFetch(`/api/v1/issues/${key}/move/preview`, {
    method: 'POST',
    body: { targetProjectKey },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(movePreviewSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈를 대상 프로젝트로 이동한다.
 * POST /api/v1/issues/{key}/move → `{ data: MoveResponse }` 언랩
 *
 * @param key 이동할 이슈 키
 * @param payload 이동 요청 페이로드 (매핑 정보 포함)
 * @throws ApiError — 403, 404, 409(OCC 충돌), 422(상태 불일치·다단계 자식 등).
 *   404 의 두 갈래는 [previewMove] 와 같다 — 이슈 미존재는 운영에서도, **대상 프로젝트
 *   미존재는 비-prod 에서만** 난다(`IssueMoveService:189-190` 이 `:210` 보다 앞선다).
 */
export async function moveIssue(key: string, payload: MoveIssueInput): Promise<MoveResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/move`, {
    method: 'POST',
    body: payload,
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(moveResponseSchema).parse(raw)
  return wrapped.data
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — backend MoveErrorCode 열거 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 이동 BC errorCode 상수.
 * 호출 측(MoveIssueDialog 등)이 switch/if 분기에서 사용한다.
 * (error-key drift 방지 — PR #106 교훈, 공유 util 경유)
 */
export const MOVE_ERROR_CODES = {
  /** 이슈를 찾을 수 없음 */
  ISSUE_NOT_FOUND: 'ISSUE_NOT_FOUND',
  /** 대상 프로젝트를 찾을 수 없음 */
  PROJECT_NOT_FOUND: 'PROJECT_NOT_FOUND',
  /** 같은 프로젝트로 이동 시도 */
  MOVE_SAME_PROJECT: 'MOVE_SAME_PROJECT',
  /** OCC 낙관락 충돌 */
  VERSION_CONFLICT: 'VERSION_CONFLICT',
  /** 비호환 상태 미선택 */
  INVALID_TARGET_STATE: 'INVALID_TARGET_STATE',
  /** 다단계 자식(자식의 자식) 이동 시도 */
  SUBTASK_HAS_OWN_SUBTASKS: 'SUBTASK_HAS_OWN_SUBTASKS',
  /** 유효성 검사 실패 */
  VALIDATION_FAILED: 'VALIDATION_FAILED',
} as const

/** 이슈 이동 BC errorCode 유니온 타입 */
export type MoveErrorCode = (typeof MOVE_ERROR_CODES)[keyof typeof MOVE_ERROR_CODES]

/**
 * 에러에서 이슈 이동 BC errorCode를 추출한다.
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractMoveErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 이동 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: POST /api/v1/issues/{key}/move 호출
 * 2. onSuccess: issueQueryKey(key) invalidate → 이슈 상세 refetch
 *
 * 에러 처리는 호출 측(MoveIssueDialog)이 담당한다 (api 레이어 토스트 금지).
 *
 * @param key 이동할 이슈 키
 */
export function useMoveIssue(key: string) {
  const queryClient = useQueryClient()
  return useMutation<MoveResponse, ApiError, MoveIssueInput>({
    mutationFn: (payload: MoveIssueInput) => moveIssue(key, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(key) })
    },
  })
}
