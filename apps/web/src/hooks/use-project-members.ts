// 프로젝트 멤버 CRUD TanStack Query 훅 — 낙관적 업데이트 + 롤백 + invalidate (FR-PM-01)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchProjectMembers,
  addMember,
  changeRole,
  removeMember,
} from '@/api/project-members'
import type { ProjectMember, AddMemberInput, ProjectRole } from '@/api/project-members'
import { notifyMemberError } from './project-member-error'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** queryKey 상수 — 매직 문자열 방지 */
export const PROJECT_MEMBER_KEYS = {
  /** 프로젝트 멤버 목록 queryKey */
  list: (projectKey: string) => ['project-members', projectKey] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/members → ProjectMember[]
 * staleTime 30초 — 빈번한 목록 재조회를 방지한다.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function useProjectMembers(projectKey: string) {
  return useQuery({
    queryKey: PROJECT_MEMBER_KEYS.list(projectKey),
    queryFn: () => fetchProjectMembers(projectKey),
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation 훅 — 낙관적 업데이트 컨텍스트 타입
// ─────────────────────────────────────────────────────────────────────────────

interface MemberListContext {
  prevList: ProjectMember[] | undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// useAddMember
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 멤버를 추가한다.
 *
 * POST /api/v1/projects/{projectKey}/members → 201 ProjectMember
 *
 * 낙관적 업데이트: onMutate에서 캐시에 임시 멤버를 선반영한다.
 * 실패 시 onError에서 이전 캐시를 복원(롤백)한다.
 * onSettled에서 서버 최신 상태로 invalidate한다.
 *
 * 주의: mutation-setquerydata-partial-response-flicker 교훈에 따라
 * 성공 응답을 setQueryData로 직접 덮지 않고 invalidate-only 전략을 사용한다.
 *
 * @param projectKey 멤버를 추가할 프로젝트 키
 */
export function useAddMember(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = PROJECT_MEMBER_KEYS.list(projectKey)

  return useMutation<ProjectMember, unknown, AddMemberInput, MemberListContext>({
    mutationFn: (input) => addMember(projectKey, input),
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: listKey })

      const prevList = queryClient.getQueryData<ProjectMember[]>(listKey)

      if (prevList !== undefined) {
        const optimistic: ProjectMember = {
          projectId: '',
          userId: input.userId,
          role: input.role,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          displayName: null,
          username: null,
        }
        queryClient.setQueryData<ProjectMember[]>(listKey, [...prevList, optimistic])
      }

      return { prevList }
    },
    onError: (error, _input, context) => {
      if (context?.prevList !== undefined) {
        queryClient.setQueryData<ProjectMember[]>(listKey, context.prevList)
      }
      notifyMemberError(error)
    },
    onSuccess: () => {
      toast.success('멤버를 추가했습니다')
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeRole
// ─────────────────────────────────────────────────────────────────────────────

/** 역할 변경 mutation 입력 타입 */
export interface ChangeRoleMutationInput {
  userId: string
  role: ProjectRole
}

/**
 * 멤버의 역할을 변경한다.
 *
 * PATCH /api/v1/projects/{projectKey}/members/{userId} → 200 ProjectMember
 *
 * 낙관적 업데이트: onMutate에서 캐시의 해당 멤버 역할을 즉시 변경한다.
 * 실패 시 onError에서 이전 캐시를 복원한다.
 * onSettled에서 서버 최신 상태로 invalidate한다.
 *
 * @param projectKey 프로젝트 키
 */
export function useChangeRole(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = PROJECT_MEMBER_KEYS.list(projectKey)

  return useMutation<ProjectMember, unknown, ChangeRoleMutationInput, MemberListContext>({
    mutationFn: ({ userId, role }) => changeRole(projectKey, userId, role),
    onMutate: async ({ userId, role }) => {
      await queryClient.cancelQueries({ queryKey: listKey })

      const prevList = queryClient.getQueryData<ProjectMember[]>(listKey)

      if (prevList !== undefined) {
        queryClient.setQueryData<ProjectMember[]>(
          listKey,
          prevList.map((m) =>
            m.userId === userId
              ? { ...m, role, updatedAt: new Date().toISOString() }
              : m,
          ),
        )
      }

      return { prevList }
    },
    onError: (error, _input, context) => {
      if (context?.prevList !== undefined) {
        queryClient.setQueryData<ProjectMember[]>(listKey, context.prevList)
      }
      notifyMemberError(error)
    },
    onSuccess: () => {
      toast.success('역할을 변경했습니다')
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useRemoveMember
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에서 멤버를 제거한다.
 *
 * DELETE /api/v1/projects/{projectKey}/members/{userId} → 204 No Content
 *
 * 낙관적 업데이트: onMutate에서 캐시에서 해당 멤버를 즉시 제거한다.
 * 실패 시 onError에서 이전 캐시를 복원한다.
 * onSettled에서 서버 최신 상태로 invalidate한다.
 *
 * @param projectKey 프로젝트 키
 */
export function useRemoveMember(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = PROJECT_MEMBER_KEYS.list(projectKey)

  return useMutation<void, unknown, string, MemberListContext>({
    mutationFn: (userId) => removeMember(projectKey, userId),
    onMutate: async (userId) => {
      await queryClient.cancelQueries({ queryKey: listKey })

      const prevList = queryClient.getQueryData<ProjectMember[]>(listKey)

      if (prevList !== undefined) {
        queryClient.setQueryData<ProjectMember[]>(
          listKey,
          prevList.filter((m) => m.userId !== userId),
        )
      }

      return { prevList }
    },
    onError: (error, _userId, context) => {
      if (context?.prevList !== undefined) {
        queryClient.setQueryData<ProjectMember[]>(listKey, context.prevList)
      }
      notifyMemberError(error)
    },
    onSuccess: () => {
      toast.success('멤버를 제거했습니다')
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
  })
}
