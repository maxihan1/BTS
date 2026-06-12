// 이슈 템플릿 BC TanStack Query 훅 — CRUD invalidate-only + 에러 토스트 (FR-TM-01)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchIssueTemplates,
  createIssueTemplate,
  updateIssueTemplate,
  deleteIssueTemplate,
  extractIssueTemplateErrorCode,
} from '@/api/issue-templates'
import { issueTemplateErrorMessage } from '@/i18n/issue-template-labels'
import type {
  IssueTemplate,
  CreateIssueTemplateInput,
  UpdateIssueTemplateInput,
} from '@/api/issue-templates'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 템플릿 BC queryKey 팩토리 */
export const ISSUE_TEMPLATE_KEYS = {
  /** 프로젝트별 이슈 템플릿 목록 queryKey */
  list: (projectKey: string) => ['issue-templates', projectKey] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 토스트 헬퍼 — 훅 레이어에서 단일 발사
// silent:true 경로(Dialog 인라인)에서는 호출하지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

function notifyIssueTemplateError(error: unknown): void {
  const code = extractIssueTemplateErrorCode(error)
  toast.error(issueTemplateErrorMessage(code))
}

// ─────────────────────────────────────────────────────────────────────────────
// useIssueTemplates — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/** useIssueTemplates 옵션 타입 */
export interface UseIssueTemplatesOptions {
  /** false이면 쿼리를 idle 상태로 유지해 fetch를 지연한다. 기본값 true. */
  enabled?: boolean
}

/**
 * 프로젝트 이슈 템플릿 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/issue-templates → IssueTemplate[]
 * staleTime 30초 — 빈번한 목록 재조회를 방지한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param options 쿼리 옵션 — enabled: false이면 즉시 fetch하지 않음 (lazy 로드)
 */
export function useIssueTemplates(projectKey: string, options?: UseIssueTemplatesOptions) {
  return useQuery({
    queryKey: ISSUE_TEMPLATE_KEYS.list(projectKey),
    queryFn: () => fetchIssueTemplates(projectKey),
    staleTime: 30_000,
    enabled: options?.enabled ?? true,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 mutation 옵션 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useCreateIssueTemplate / useUpdateIssueTemplate 공통 옵션 */
export interface UseIssueTemplateMutationOptions {
  /**
   * true이면 onError에서 toast를 발사하지 않는다.
   * Dialog 경로처럼 인라인 submitError만 표시하는 경우에 사용한다.
   * 삭제(useDeleteIssueTemplate)는 Dialog가 없으므로 이 옵션을 지원하지 않는다.
   */
  silent?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// useCreateIssueTemplate — 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿을 생성한다.
 *
 * POST /api/v1/projects/{projectKey}/issue-templates → 201 IssueTemplate
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지)
 * onError   → silent:false(기본)이면 toast.error 발사, silent:true이면 억제.
 *             Dialog 경로는 silent:true + per-call onError로 인라인 submitError만 표시한다.
 *
 * @param projectKey 템플릿을 추가할 프로젝트 키
 * @param options 뮤테이션 옵션 — silent:true이면 onError toast 억제
 */
export function useCreateIssueTemplate(
  projectKey: string,
  options?: UseIssueTemplateMutationOptions,
) {
  const queryClient = useQueryClient()
  const listKey = ISSUE_TEMPLATE_KEYS.list(projectKey)
  const silent = options?.silent ?? false

  return useMutation<IssueTemplate, unknown, CreateIssueTemplateInput>({
    mutationFn: (input) => createIssueTemplate(projectKey, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      if (!silent) {
        notifyIssueTemplateError(error)
      }
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateIssueTemplate — 수정
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 템플릿 수정 mutation 입력 타입 */
export interface UpdateIssueTemplateMutationInput {
  templateId: string
  input: UpdateIssueTemplateInput
}

/**
 * 이슈 템플릿을 수정한다 (issueTypeId는 불변).
 *
 * PATCH /api/v1/projects/{projectKey}/issue-templates/{templateId} → 200 IssueTemplate
 *
 * onSuccess → invalidateQueries
 * onError   → silent:false(기본)이면 toast.error 발사, silent:true이면 억제.
 *             Dialog 경로는 silent:true + per-call onError로 인라인 submitError만 표시한다.
 *
 * @param projectKey 프로젝트 키
 * @param options 뮤테이션 옵션 — silent:true이면 onError toast 억제
 */
export function useUpdateIssueTemplate(
  projectKey: string,
  options?: UseIssueTemplateMutationOptions,
) {
  const queryClient = useQueryClient()
  const listKey = ISSUE_TEMPLATE_KEYS.list(projectKey)
  const silent = options?.silent ?? false

  return useMutation<IssueTemplate, unknown, UpdateIssueTemplateMutationInput>({
    mutationFn: ({ templateId, input }) => updateIssueTemplate(projectKey, templateId, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      if (!silent) {
        notifyIssueTemplateError(error)
      }
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteIssueTemplate — 삭제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿을 삭제한다.
 *
 * DELETE /api/v1/projects/{projectKey}/issue-templates/{templateId} → 204 No Content
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useDeleteIssueTemplate(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = ISSUE_TEMPLATE_KEYS.list(projectKey)

  return useMutation<void, unknown, string>({
    mutationFn: (templateId) => deleteIssueTemplate(projectKey, templateId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyIssueTemplateError(error)
    },
  })
}
