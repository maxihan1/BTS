// 이슈 생성 폼이 열릴 때 스스로 채우는 기본값 두 갈래 — 프로젝트 · 이슈 유형 (FR-UX-09 F2)
import { useEffect, useMemo, useState } from 'react'
import type { UseFormReturn } from 'react-hook-form'
import { useQuery } from '@tanstack/react-query'
import { useActiveProject } from '@/hooks/use-active-project'
import { fetchIssueTypes } from '@/api/issue-types'
import type { Project } from '@/api/projects'
import type { IssueTypeResponse } from '@/api/issue-types'
import type { IssueCreateFormValues } from '@/components/issue/create/issue-create-schema'

/**
 * 폼의 `projectKey` 를 기본 프로젝트로 한 번 채운다 (FR-3, FR-UX-07 연계). 반환값 없음.
 *
 * 기본값은 **활성 프로젝트가 목록에 있으면 그것, 없으면 목록 첫 번째**다.
 * 활성 프로젝트가 아카이브되었거나 권한을 잃은 경우를 목록 대조로 걸러낸다.
 *
 * ★목록은 비동기로 도착하는데 react-hook-form 의 `defaultValues` 는 mount 시 1회만 적용된다.
 * 그래서 effect 로 채우되 **아직 사용자가 고르지 않았을 때만** 넣는다 — 이미 고른 값을
 * 덮으면 사용자의 조작이 되돌려진다.
 */
export function useDefaultProjectSelection(
  form: UseFormReturn<IssueCreateFormValues>,
  projects: Project[],
): void {
  const activeProjectKey = useActiveProject((s) => s.activeProjectKey)

  const defaultProjectKey = useMemo(() => {
    if (projects.length === 0) return ''
    if (activeProjectKey !== null && projects.some((p) => p.key === activeProjectKey)) {
      return activeProjectKey
    }
    return projects[0]?.key ?? ''
  }, [projects, activeProjectKey])

  useEffect(() => {
    if (defaultProjectKey !== '' && form.getValues('projectKey') === '') {
      form.setValue('projectKey', defaultProjectKey)
    }
  }, [defaultProjectKey, form])
}

/** `useIssueTypeSelection` 반환값 */
export interface IssueTypeSelection {
  issueTypes: IssueTypeResponse[]
  isLoading: boolean
  /** 선택된 유형 id — 목록 도착 전에는 null 이라 셀렉터를 비활성으로 둔다 */
  selectedTypeId: number | null
  setSelectedTypeId: (typeId: number) => void
}

/**
 * 이슈 유형 목록과 선택 상태 (FR-4).
 *
 * 기본 유형은 `task` — **백엔드가 `typeId` 미전달 시 적용하는 fallback 과 같은 값**이라
 * 화면에 보이는 것과 서버가 저장하는 것이 어긋나지 않는다.
 */
export function useIssueTypeSelection(): IssueTypeSelection {
  const [selectedTypeId, setSelectedTypeId] = useState<number | null>(null)
  const { data: issueTypes = [], isLoading } = useQuery({
    queryKey: ['issue-types'],
    queryFn: fetchIssueTypes,
  })

  useEffect(() => {
    if (selectedTypeId === null && issueTypes.length > 0) {
      const task = issueTypes.find((t) => t.key === 'task') ?? issueTypes[0]
      if (task !== undefined) setSelectedTypeId(task.id)
    }
  }, [issueTypes, selectedTypeId])

  return { issueTypes, isLoading, selectedTypeId, setSelectedTypeId }
}
