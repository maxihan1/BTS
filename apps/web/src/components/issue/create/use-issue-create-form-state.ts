// 이슈 생성 폼의 필드 상태 한 덩어리 — 세 필드 그룹이 공유하는 값·프로젝트 종속 조회·초기화 (부채 매핑 8)
import { useState, useEffect, useRef } from 'react'
import { useForm } from 'react-hook-form'
import type { UseFormReturn } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useAssigneePicker } from '@/components/issue/create/use-assignee-picker'
import type { AssigneePicker } from '@/components/issue/create/use-assignee-picker'
import {
  useDefaultProjectSelection,
  useIssueTypeSelection,
} from '@/components/issue/create/use-issue-create-defaults'
import {
  DEFAULT_PRIORITY,
  issueCreateSchema,
  sanitizeInitialSummary,
} from '@/components/issue/create/issue-create-schema'
import type { IssueCreateFormValues } from '@/components/issue/create/issue-create-schema'
import { useComponents } from '@/hooks/use-components'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { useProjects } from '@/hooks/use-projects'
import type { CustomFieldValues } from '@/api/issues'
import type { CustomField } from '@/api/custom-fields.types'
import type { Component } from '@/api/components'
import type { Project } from '@/api/projects'
import type { IssueTypeResponse } from '@/api/issue-types'

/** {@link useIssueCreateFormState} 입력 — 폼이 호출자에게서 받는 기본값 2종. */
export interface IssueCreateFormStateOptions {
  /** 제목 필드 기본값 (FR-UX-04 FR7 명령 팔레트 프리필) */
  initialSummary?: string
  /** 프로젝트 기본값 **명시 전달** (FR-UX-09 F3 · 스펙 §8 D-A). 미전달이면 활성 프로젝트 경로가 산다 */
  initialProjectKey?: string
}

/**
 * 이슈 생성 폼이 쥐는 상태 전량.
 *
 * 필드 3그룹(기본 · 배정 · 추가) 순서 그대로 묶었다 — 그리는 컴포넌트와 같은 단위로 읽힌다.
 */
export interface IssueCreateFormState {
  /** react-hook-form 인스턴스. 제목·본문·프로젝트키는 여기가 쥔다 */
  form: UseFormReturn<IssueCreateFormValues>
  /** 현재 선택된 프로젝트 키 — 프로젝트 종속 조회와 CREATE 게이트의 입력 */
  projectKey: string
  isProjectKeyFilled: boolean

  // ── 「기본」 그룹 ──────────────────────────────────────────────────────────
  projects: Project[]
  isProjectsLoading: boolean
  issueTypes: IssueTypeResponse[]
  isTypesLoading: boolean
  selectedTypeId: number | null
  setSelectedTypeId: (typeId: number) => void

  // ── 「배정」 그룹 ──────────────────────────────────────────────────────────
  assignee: AssigneePicker
  priority: number
  setPriority: (priority: number) => void
  labels: string[]
  setLabels: (labels: string[]) => void

  // ── 「추가」 그룹 (셋 다 프로젝트마다 정의가 다르다) ────────────────────────
  componentOptions: Component[]
  selectedComponentIds: string[]
  setSelectedComponentIds: (ids: string[]) => void
  securityLevelId: string | null
  setSecurityLevelId: (id: string | null) => void
  customFieldDefs: CustomField[]
  customFieldValues: CustomFieldValues
  /** 값이 들어오면 required 경고를 즉시 거둔다 */
  handleCustomFieldChange: (key: string, value: unknown) => void

  // ── 화면 알림 2종 (프로젝트를 바꾸면 함께 버린다) ──────────────────────────
  serverError: string | null
  setServerError: (message: string | null) => void
  customFieldRequiredError: boolean
  setCustomFieldRequiredError: (on: boolean) => void
}

/**
 * 이슈 생성 폼의 상태·프로젝트 종속 조회·초기화를 한 곳에 모은 훅.
 *
 * ★**CREATE 게이트는 여기 없다.** 게이트 훅은 화면(`IssueCreateForm.tsx`)이 직접 import 한다 —
 *   「로딩 프레임 계약」 레지스트리(부채 매핑 15)가 **import 문**으로 소비처를 탐지하므로,
 *   훅을 이 파일로 옮기면 화면의 선언이 「썩은 선언」이 되고 이 파일이 「선언 없는 소비처」가 된다.
 *   계약은 화면의 것이지 상태 묶음의 것이 아니다.
 */
export function useIssueCreateFormState(
  options: IssueCreateFormStateOptions = {},
): IssueCreateFormState {
  const { initialSummary, initialProjectKey } = options

  const [serverError, setServerError] = useState<string | null>(null)
  const [selectedComponentIds, setSelectedComponentIds] = useState<string[]>([])
  const [securityLevelId, setSecurityLevelId] = useState<string | null>(null)
  const [customFieldValues, setCustomFieldValues] = useState<CustomFieldValues>({})
  const [customFieldRequiredError, setCustomFieldRequiredError] = useState(false)
  const [priority, setPriority] = useState(DEFAULT_PRIORITY)
  const [labels, setLabels] = useState<string[]>([])

  const assignee = useAssigneePicker()

  const form = useForm<IssueCreateFormValues>({
    resolver: zodResolver(issueCreateSchema),
    defaultValues: {
      projectKey: '',
      summary: sanitizeInitialSummary(initialSummary),
      // 빈 HTML 이 아니라 **빈 문자열**로 시작한다 — TipTap 이 빈 문단으로 정규화하고,
      // `isBlankHtml` 이 어느 쪽이든 빈 본문으로 읽는다.
      descriptionHtml: '',
    },
  })

  const projectKey = form.watch('projectKey')
  const isProjectKeyFilled = projectKey.trim() !== ''

  // ── FR-3 프로젝트 셀렉터 / FR-4 이슈 유형 — 기본값은 훅이 채운다 ──────────
  const { data: projects = [], isLoading: isProjectsLoading } = useProjects()
  useDefaultProjectSelection(form, projects, initialProjectKey)
  const {
    issueTypes,
    isLoading: isTypesLoading,
    selectedTypeId,
    setSelectedTypeId,
  } = useIssueTypeSelection()

  // ── E1/B-4 프로젝트를 바꾸면 프로젝트 종속 값을 버린다 ────────────────────
  // 컴포넌트·커스텀 필드는 **프로젝트마다 정의가 다르다**. 안 비우면 이전 프로젝트의 id 가
  // 그대로 실려 서버가 거부하거나(422) 엉뚱한 값이 저장된다.
  const prevProjectKeyRef = useRef(projectKey)
  useEffect(() => {
    if (prevProjectKeyRef.current !== projectKey) {
      prevProjectKeyRef.current = projectKey
      setSelectedComponentIds([])
      setCustomFieldValues({})
      setSecurityLevelId(null)
      // ★프로젝트에 종속된 **에러 표시**도 함께 버린다 (게이트2 리뷰 적발).
      // 「이 프로젝트에 이슈를 만들 권한이 없습니다」는 프로젝트 A 에 대한 주장이라
      // B 로 바꾸면 즉시 거짓이 된다. 안 비우면 권한이 **있는** 프로젝트를 고른 뒤에도
      // 빨간 alert 이 그대로 남아 다음 제출까지 거짓말을 계속한다.
      // 필수 커스텀 필드 경고도 같은 이유다 — 정의 자체가 프로젝트마다 다르다.
      setServerError(null)
      setCustomFieldRequiredError(false)
    }
  }, [projectKey])

  const { data: componentData } = useComponents(projectKey, { enabled: isProjectKeyFilled })
  const { data: customFieldDefs = [] } = useCustomFields(projectKey, {
    enabled: isProjectKeyFilled,
  })

  /** 커스텀 필드 값 변경 — 값이 들어오면 required 경고를 즉시 거둔다. */
  function handleCustomFieldChange(key: string, value: unknown): void {
    setCustomFieldValues((prev) => ({ ...prev, [key]: value }))
    if (customFieldRequiredError) setCustomFieldRequiredError(false)
  }

  return {
    form,
    projectKey,
    isProjectKeyFilled,
    projects,
    isProjectsLoading,
    issueTypes,
    isTypesLoading,
    selectedTypeId,
    setSelectedTypeId,
    assignee,
    priority,
    setPriority,
    labels,
    setLabels,
    componentOptions: componentData ?? [],
    selectedComponentIds,
    setSelectedComponentIds,
    securityLevelId,
    setSecurityLevelId,
    customFieldDefs,
    customFieldValues,
    handleCustomFieldChange,
    serverError,
    setServerError,
    customFieldRequiredError,
    setCustomFieldRequiredError,
  }
}
