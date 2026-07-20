// 프로젝트 생성/이름변경/아카이브/아카이브해제 TanStack Query mutation 훅 — invalidate-only (FR-PJ PR-5 Task 3)
import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  createProject,
  updateProjectName,
  archiveProject,
  unarchiveProject,
} from '@/api/projects'
import type { Project, ProjectArchiveResult } from '@/api/projects'
import { PROJECT_KEYS } from '@/hooks/use-project'

/** 프로젝트 생성 mutation 입력 타입 */
export interface CreateProjectMutationInput {
  key: string
  name: string
}

/** 프로젝트 이름 변경 mutation 입력 타입 */
export interface UpdateProjectNameMutationInput {
  idOrKey: string
  name: string
}

/**
 * 목록(PROJECT_KEYS.list())·단건(PROJECT_KEYS.detail(idOrKey)) 쿼리를 무효화한다.
 * setQueryData로 캐시를 부분 응답으로 덮지 않는다(mutation-setquerydata-partial-response-flicker 재발
 * 방지 — PATCH는 204라 바디가 아예 없다).
 */
async function invalidateProjectQueries(queryClient: QueryClient, idOrKey?: string): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: PROJECT_KEYS.list() })
  if (idOrKey !== undefined) {
    await queryClient.invalidateQueries({ queryKey: PROJECT_KEYS.detail(idOrKey) })
  }
}

/**
 * 새 프로젝트를 생성한다.
 *
 * POST /api/v1/projects → 201 Project
 * onSuccess → invalidateQueries(['projects'] · ['project', 생성된 key])
 */
export function useCreateProject() {
  const queryClient = useQueryClient()

  return useMutation<Project, unknown, CreateProjectMutationInput>({
    mutationFn: ({ key, name }) => createProject(key, name),
    onSuccess: async (project) => {
      await invalidateProjectQueries(queryClient, project.key)
    },
  })
}

/**
 * 프로젝트 이름을 변경한다.
 *
 * PATCH /api/v1/projects/{idOrKey} → 204 No Content
 * onSuccess → invalidateQueries(['projects'] · ['project', idOrKey])
 */
export function useUpdateProjectName() {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, UpdateProjectNameMutationInput>({
    mutationFn: ({ idOrKey, name }) => updateProjectName(idOrKey, name),
    onSuccess: async (_data, { idOrKey }) => {
      await invalidateProjectQueries(queryClient, idOrKey)
    },
  })
}

/**
 * 프로젝트를 아카이브한다.
 *
 * POST /api/v1/projects/{idOrKey}/archive → 200 ProjectArchiveResult
 * onSuccess → invalidateQueries(['projects'] · ['project', idOrKey])
 */
export function useArchiveProject() {
  const queryClient = useQueryClient()

  return useMutation<ProjectArchiveResult, unknown, string>({
    mutationFn: (idOrKey) => archiveProject(idOrKey),
    onSuccess: async (_data, idOrKey) => {
      await invalidateProjectQueries(queryClient, idOrKey)
    },
  })
}

/**
 * 프로젝트 아카이브를 해제한다.
 *
 * POST /api/v1/projects/{idOrKey}/unarchive → 200 ProjectArchiveResult
 * onSuccess → invalidateQueries(['projects'] · ['project', idOrKey])
 */
export function useUnarchiveProject() {
  const queryClient = useQueryClient()

  return useMutation<ProjectArchiveResult, unknown, string>({
    mutationFn: (idOrKey) => unarchiveProject(idOrKey),
    onSuccess: async (_data, idOrKey) => {
      await invalidateProjectQueries(queryClient, idOrKey)
    },
  })
}
