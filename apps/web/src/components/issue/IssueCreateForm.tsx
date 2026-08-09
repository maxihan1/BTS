// 이슈 생성 폼 — 라우터 비의존 제어 컴포넌트 (FR-UX-09 F2 로 모달·라우트가 공유)
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { createIssue } from '@/api/issues'
import { ApiError } from '@/api/client'
import { Form } from '@/components/ui/form'
import { EmptyState } from '@/components/ui/empty-state'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { IssueCreateBasicFields } from '@/components/issue/create/IssueCreateBasicFields'
import { IssueCreateAssignmentFields } from '@/components/issue/create/IssueCreateAssignmentFields'
import { IssueCreateExtraFields } from '@/components/issue/create/IssueCreateExtraFields'
import { useAssigneePicker } from '@/components/issue/create/use-assignee-picker'
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
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { useAuthUser } from '@/auth/authStore'
import { issueCreateStrings, issueDetailStrings } from '@/i18n/ko'
import type { CustomFieldValues } from '@/api/issues'
import type { CustomField } from '@/api/custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// isRequiredFieldEmpty — required 필드 빈값 판정 헬퍼 (스펙 E-3)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * required 커스텀 필드의 현재 값이 비어 있는지 판정한다 (스펙 E-3 기준).
 *
 * ★판정을 **열거가 아니라 선판정**으로 짠다. 「값이 아예 없다」(`undefined`/`null`)는
 * 필드 유형과 무관하게 빈값이므로 switch 앞에서 한 번에 거른다. 분기마다 `undefined` 를
 * 다시 적는 방식은 한 분기만 빠뜨려도 조용히 뚫리고, 새 `fieldType` 이 생길 때
 * 같은 구멍이 재발한다 — 실제로 MULTI_SELECT 와 CHECKBOX 두 분기가 그렇게 뚫려 있었다
 * (TODOS 「required MULTI_SELECT 가 클라이언트 검증을 그냥 통과한다」, 2026-08-09 봉합).
 *
 * 선판정 이후 각 분기는 **그 유형 고유의 빈값**만 본다.
 * - 텍스트류(SHORT_TEXT/LONG_TEXT/URL/DATE/DATETIME/SINGLE_SELECT/RADIO): `''`
 * - NUMBER: `NaN`. **0 은 유효값**이므로 falsy 검사를 쓰면 안 된다.
 * - MULTI_SELECT: 빈 배열
 * - CHECKBOX: 체크되지 않음(`raw !== true`)
 *
 * ★CHECKBOX 가 **백엔드보다 엄격**한 것은 의도된 것이다(2026-08-09 Maxi 확정).
 * 백엔드 `CustomFieldValueValidator` 는 `value == null` 만 거부해 `false` 를 충족으로 본다.
 * 프론트는 「필수 체크박스는 체크해야 제출 가능」으로 둔다 — 필수 체크박스의 실제 용도가
 * 약관 동의류라 「해제된 채 통과」가 의미를 잃기 때문이다.
 * 판정을 `undefined → 빈값 / false → 유효` 로 두는 절충안은 **택하지 않았다**. 그러면
 * 화면상 똑같이 해제된 두 상태(첫 방문 / 토글 왕복 후)가 서로 다르게 판정돼
 * 사용자가 원인을 알 수 없는 데드락이 된다.
 */
function isRequiredFieldEmpty(
  fieldType: CustomField['fieldType'],
  raw: unknown,
): boolean {
  // ★선판정 — 값이 아예 없으면 유형과 무관하게 빈값이다.
  if (raw === undefined || raw === null) return true

  switch (fieldType) {
    case 'SHORT_TEXT':
    case 'LONG_TEXT':
    case 'URL':
    case 'DATE':
    case 'DATETIME':
    case 'SINGLE_SELECT':
    case 'RADIO':
      return raw === ''
    case 'NUMBER':
      return typeof raw === 'number' && isNaN(raw)
    case 'MULTI_SELECT':
      return Array.isArray(raw) && raw.length === 0
    case 'CHECKBOX':
      return raw !== true
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → 사용자 메시지 매핑
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ApiError body에서 errorCode 를 추출해 사용자 노출 메시지로 변환한다.
 *
 * @param err 임의 에러 — ApiError 가 아니면 기본 메시지 반환
 * @returns 사용자 노출 한국어 에러 메시지
 */
function resolveCreateErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    // 403 — 권한 부족. 상태 코드를 먼저 본다. errorCode 가 무엇이든 「잠시 후 다시
    // 시도해 주세요」는 거짓말이다 (재시도해도 안 되는데 재시도를 권한다).
    if (err.status === 403) {
      return issueCreateStrings.errorCreateForbidden
    }
    const body = err.body
    if (typeof body === 'object' && body !== null && 'errorCode' in body) {
      const { errorCode } = body as { errorCode: unknown }
      if (errorCode === 'PROJECT_NOT_FOUND') {
        return issueCreateStrings.errorProjectNotFound
      }
      // FR-UX-09 F2 — 지정한 담당자가 존재하지 않을 때 백엔드가 내는 422.
      if (errorCode === 'ASSIGNEE_NOT_FOUND') {
        return issueCreateStrings.errorAssigneeNotFound
      }
    }
  }
  return issueCreateStrings.errorDefault
}

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

  /**
   * 선택된 프로젝트에 이슈 생성 권한이 **명시적으로 없는가**.
   *
   * ★판정을 `!isLoading && CREATE === true`(미지 = 거부)로 쓰면 안 된다.
   * 그 형태는 `issues.index.tsx:526` 처럼 **버튼을 회색으로 만드는** 용도라 아무 사실도
   * 주장하지 않지만, 여기서는 제출 차단 + 「권한이 없습니다」 문구 노출이라 **사실 주장**이다.
   * 미지를 거부로 읽으면 두 가지가 깨진다.
   * - 권한 조회가 아직 안 끝난 구간(프로젝트를 바꿀 때마다 queryKey 가 바뀌어 재발한다)에
   *   Enter 를 치면 거짓 문구를 본다.
   * - `use-project-permissions.ts` 에 `retry:false` 도 에러 폴백도 없어, 500/네트워크 단절이면
   *   `data === undefined` 로 안착해 **CREATE 를 실제로 가진 사용자를 영구 차단**한다.
   *
   * 그래서 **명시적 거부만** 막고 나머지는 서버가 최종 판정하게 둔다(403 은 아래
   * `resolveCreateErrorMessage` 가 권한 문구로 받는다). `use-projects.ts` KDoc 이 적어 둔
   * 「API 에러는 조용한 fail-safe」 관례와도 같은 방향이다.
   */
  const { data: projectPermissions } = useProjectPermissions(projectKey)
  const isCreateExplicitlyDenied = projectPermissions?.permissions.CREATE === false

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
