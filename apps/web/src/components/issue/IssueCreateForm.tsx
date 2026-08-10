// 이슈 생성 폼 — 라우터 비의존 제어 컴포넌트 (FR-UX-09 F2 로 모달·라우트가 공유)
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { createIssue } from '@/api/issues'
import { Form } from '@/components/ui/form'
import { EmptyState } from '@/components/ui/empty-state'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { IssueCreateBasicFields } from '@/components/issue/create/IssueCreateBasicFields'
import { IssueCreateAssignmentFields } from '@/components/issue/create/IssueCreateAssignmentFields'
import { IssueCreateExtraFields } from '@/components/issue/create/IssueCreateExtraFields'
import { useAssigneePicker } from '@/components/issue/create/use-assignee-picker'
import {
  isRequiredFieldEmpty,
  resolveCreateErrorMessage,
} from '@/components/issue/create/issue-create-validation'
import { useIssueCreatePermissionGate } from '@/components/issue/create/use-issue-create-permission-gate'
import {
  useDefaultProjectSelection,
  useIssueTypeSelection,
} from '@/components/issue/create/use-issue-create-defaults'
import {
  DEFAULT_PRIORITY,
  buildCreateIssuePayload,
  issueCreateSchema,
  sanitizeInitialSummary,
} from '@/components/issue/create/issue-create-schema'
import type { IssueCreateFormValues } from '@/components/issue/create/issue-create-schema'
import { useComponents } from '@/hooks/use-components'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { useProjects } from '@/hooks/use-projects'
import { useAuthUser } from '@/auth/authStore'
import { issueCreateStrings, issueDetailStrings } from '@/i18n/ko'
import type { CustomFieldValues } from '@/api/issues'

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface IssueCreateFormProps {
  /** 이슈 생성 성공 후 호출되는 콜백 — 생성된 이슈 key를 전달 */
  onSuccess?: (key: string) => void
  /** 제목 필드 기본값 — 명령 팔레트 `/issue <제목>`(FR-UX-04 FR7)의 URL summary 프리필용 */
  initialSummary?: string
  /**
   * FR-UX-09 F2 — 접근 가능한 프로젝트가 0개일 때 빈 상태의 「프로젝트 만들기」 콜백.
   * 폼은 라우터 비의존이라 이동을 스스로 하지 않고 호출자에게 맡긴다.
   * 미전달이면 버튼을 노출하지 않는다.
   */
  onCreateProject?: () => void
  /**
   * FR-UX-09 F2 — 폼 요소의 `id`. 모달이 **스크롤 영역 밖 푸터**에 제출 버튼을 두려면
   * `<button form={formId} type="submit">` 로 폼 밖에서 제출을 걸어야 한다 (NFR-2).
   *
   * 전달하면 폼은 **내부 제출 버튼을 렌더하지 않는다** — 호출자가 푸터에 두기 때문이다.
   * 미전달(라우트 페이지 등)이면 기존처럼 폼 안에 제출 버튼을 그린다.
   */
  formId?: string
  /**
   * 제출 진행 상태 변경 콜백 (게이트 2 C-2).
   *
   * `formId` 를 준 호출자는 제출 버튼을 **폼 밖**에 두므로 `mutation.isPending` 을 볼 수 없다.
   * 그대로 두면 모달 버튼만 눌러도 아무 반응이 없어 사용자가 다시 누른다.
   * setState 함수를 그대로 넘기면 참조가 안정적이라 재발화가 없다.
   */
  onPendingChange?: (pending: boolean) => void
  /**
   * FR-UX-09 F3 — 프로젝트 기본값 **명시 전달** (스펙 §8 D-A).
   *
   * 보드·백로그 진입점은 자기 프로젝트를 이미 알고 있으므로 전역 활성 프로젝트를
   * 경유하지 않는다. **미전달이면 기존 활성 프로젝트 기본값 경로가 그대로 산다** —
   * 상단바·딥링크 2경로는 이 값을 넘기지 않으므로 동작이 바뀌지 않는다.
   */
  initialProjectKey?: string
}

/**
 * 이슈 생성 폼 컴포넌트.
 *
 * 필드는 세 덩어리로 나눠 하위 컴포넌트가 그린다 (design 리뷰 D7) —
 * [IssueCreateBasicFields] · [IssueCreateAssignmentFields] · [IssueCreateExtraFields].
 * 이 컴포넌트는 **상태와 제출**만 쥔다.
 *
 * - 클라이언트 Zod 검증: 빈 값 제출 차단
 * - 제출 성공 시 onSuccess(key) 콜백 호출
 * - PROJECT_NOT_FOUND(404) / ASSIGNEE_NOT_FOUND(422) 시 role="alert" 에러 메시지 노출
 * - initialSummary가 있으면 제목 필드 기본값으로 사용(FR-UX-04 FR7)
 *
 * 라우터 의존 없이 props로 onSuccess를 받아 단위 테스트가 가능하다.
 */
export function IssueCreateForm({
  onSuccess,
  initialSummary,
  onCreateProject,
  formId,
  onPendingChange,
  initialProjectKey,
}: IssueCreateFormProps = {}): JSX.Element {
  const [serverError, setServerError] = useState<string | null>(null)
  const [selectedComponentIds, setSelectedComponentIds] = useState<string[]>([])
  const [selectedSecurityLevelId, setSelectedSecurityLevelId] = useState<string | null>(null)
  const [customFieldValues, setCustomFieldValues] = useState<CustomFieldValues>({})
  const [customFieldRequiredError, setCustomFieldRequiredError] = useState(false)
  const [selectedPriority, setSelectedPriority] = useState(DEFAULT_PRIORITY)
  const [selectedLabels, setSelectedLabels] = useState<string[]>([])

  const assignee = useAssigneePicker()

  const form = useForm<IssueCreateFormValues>({
    resolver: zodResolver(issueCreateSchema),
    defaultValues: {
      projectKey: '',
      summary: sanitizeInitialSummary(initialSummary),
      description: '',
    },
  })

  const projectKey = form.watch('projectKey')
  const isProjectKeyFilled = projectKey.trim() !== ''

  // 선택된 프로젝트의 CREATE 게이트. 판정식의 근거(미지 ≠ 거부)는 훅 KDoc 에 있다.
  const isCreateExplicitlyDenied = useIssueCreatePermissionGate(projectKey)

  // ── FR-3 프로젝트 셀렉터 / FR-4 이슈 유형 — 기본값은 훅이 채운다 ──────────
  const { data: projects = [], isLoading: isProjectsLoading } = useProjects()
  const authUser = useAuthUser()
  const canCreateProject = authUser?.canCreateProject === true
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
      setSelectedSecurityLevelId(null)
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
  const componentOptions = componentData ?? []

  const { data: customFieldDefs = [] } = useCustomFields(projectKey, {
    enabled: isProjectKeyFilled,
  })

  const mutation = useMutation({
    mutationFn: createIssue,
    onSuccess: (data) => {
      setServerError(null)
      onSuccess?.(data.key)
    },
    onError: (err: unknown) => {
      setServerError(resolveCreateErrorMessage(err))
    },
  })

  // 제출 상태를 폼 밖(모달 푸터)으로 흘려보낸다 (게이트 2 C-2).
  useEffect(() => {
    onPendingChange?.(mutation.isPending)
  }, [mutation.isPending, onPendingChange])

  /** 커스텀 필드 값 변경 — 값이 들어오면 required 경고를 즉시 거둔다. */
  function handleCustomFieldChange(key: string, value: unknown): void {
    setCustomFieldValues((prev) => ({ ...prev, [key]: value }))
    if (customFieldRequiredError) setCustomFieldRequiredError(false)
  }

  function handleSubmit(values: IssueCreateFormValues): void {
    // E7 — 제출 중 재클릭 차단. 푸터 버튼이 폼 밖에 있어 버튼 disabled 만으로는 부족하다.
    if (mutation.isPending) return
    // 선택된 프로젝트의 CREATE 게이트 (2026-08-09 Maxi 확정).
    // 이슈 생성 진입 경로 4개(상단바 · /issues/new 딥링크 · `c` 단축키 · 명령 팔레트)가
    // 전부 이 폼 하나를 지나므로 여기 한 곳이 무게이트 경로를 동시에 닫고,
    // 「게이트된 버튼으로 열어도 폼 안에서 무권한 프로젝트로 갈아타기」까지 덮는다.
    if (isCreateExplicitlyDenied) {
      setServerError(issueCreateStrings.errorCreateForbidden)
      return
    }
    // 스펙 E-3: required 커스텀 필드 빈값 1차 검사 — mutation 전 차단
    const hasRequiredEmpty = customFieldDefs.some(
      (field) =>
        field.required && isRequiredFieldEmpty(field.fieldType, customFieldValues[field.key]),
    )
    if (hasRequiredEmpty) {
      setCustomFieldRequiredError(true)
      return
    }
    setCustomFieldRequiredError(false)
    setServerError(null)
    mutation.mutate(
      buildCreateIssuePayload(values, {
        typeId: selectedTypeId,
        assigneeIntent: assignee.intent,
        priority: selectedPriority,
        labels: selectedLabels,
        componentIds: selectedComponentIds,
        securityLevelId: selectedSecurityLevelId,
        customFieldValues,
      }),
    )
  }

  // FR-17 — 접근 가능한 프로젝트가 0개면 채울 수 없는 폼 대신 이유와 다음 행동을 보여준다.
  // 목록 로딩이 끝난 뒤에만 판정한다 — 로딩 중 빈 배열을 0개로 오인하면 화면이 깜빡인다.
  if (!isProjectsLoading && projects.length === 0) {
    return (
      <EmptyState
        title={issueCreateStrings.noProjectsTitle}
        description={issueCreateStrings.noProjectsDescription}
        action={
          canCreateProject && onCreateProject !== undefined ? (
            <Button type="button" variant="outline" onClick={onCreateProject}>
              {issueCreateStrings.createProjectCta}
            </Button>
          ) : undefined
        }
      />
    )
  }

  return (
    <Form {...form}>
      <form id={formId} onSubmit={form.handleSubmit(handleSubmit)} noValidate className="space-y-4">
        {/* 서버 에러 알림 영역 */}
        {serverError !== null && (
          <p role="alert" className="text-sm text-destructive">
            {serverError}
          </p>
        )}

        <IssueCreateBasicFields
          control={form.control}
          projects={projects}
          isProjectsLoading={isProjectsLoading}
          issueTypes={issueTypes}
          isTypesLoading={isTypesLoading}
          selectedTypeId={selectedTypeId}
          onTypeChange={setSelectedTypeId}
        />

        <Separator />

        <IssueCreateAssignmentFields
          assignee={assignee}
          priority={selectedPriority}
          onPriorityChange={setSelectedPriority}
          labels={selectedLabels}
          onLabelsChange={setSelectedLabels}
        />

        <Separator />

        <IssueCreateExtraFields
          projectKey={projectKey}
          isProjectKeyFilled={isProjectKeyFilled}
          componentOptions={componentOptions}
          selectedComponentIds={selectedComponentIds}
          onComponentsChange={setSelectedComponentIds}
          securityLevelId={selectedSecurityLevelId}
          onSecurityLevelChange={setSelectedSecurityLevelId}
          customFieldDefs={customFieldDefs}
          customFieldValues={customFieldValues}
          onCustomFieldChange={handleCustomFieldChange}
        />

        {/* required 커스텀 필드 빈값 경고 — 스펙 E-3 1차 클라 경고 */}
        {customFieldRequiredError && (
          <p
            role="alert"
            className="text-sm text-destructive"
            data-testid="custom-fields-required-error"
          >
            {issueDetailStrings.customFieldRequiredEmpty}
          </p>
        )}

        {/* 제출 버튼 — formId 를 받은 경우(모달)는 호출자가 푸터에 그린다 (NFR-2) */}
        {/* 권한이 **명시적으로** 없을 때만 미리 알린다 — 폼을 다 채우고 제출에서야
            거부당하는 헛수고를 없앤다. 미지(로딩·조회 실패)에서는 아무 말도 하지 않는다. */}
        {isCreateExplicitlyDenied && (
          <p role="status" data-testid="create-permission-denied" className="text-sm text-destructive">
            {issueCreateStrings.errorCreateForbidden}
          </p>
        )}

        {formId === undefined && (
          <Button
            type="submit"
            disabled={mutation.isPending || isCreateExplicitlyDenied}
            className="w-full sm:w-auto"
          >
            {mutation.isPending
              ? issueCreateStrings.submitButtonPending
              : issueCreateStrings.submitButton}
          </Button>
        )}
      </form>
    </Form>
  )
}
