// 이슈 생성 폼 — 라우터 비의존 제어 컴포넌트 (FR-UX-09 F2 로 모달·라우트가 공유)
import type { JSX } from 'react'
import { useState, useEffect, useMemo, useRef } from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { createIssue } from '@/api/issues'
import { ApiError } from '@/api/client'
import {
  Form,
  FormField,
  FormItem,
  FormLabel,
  FormControl,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { EmptyState } from '@/components/ui/empty-state'
import { Button } from '@/components/ui/button'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { IssueSecurityLevelSelect } from '@/components/issue/IssueSecurityLevelSelect'
import { CustomFieldInput } from '@/components/custom-fields/CustomFieldInput'
import { IssueTypeSelect } from '@/components/issue/meta/IssueTypeSelect'
import { useComponents } from '@/hooks/use-components'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { useProjects } from '@/hooks/use-projects'
import { useActiveProject } from '@/hooks/use-active-project'
import { useAuthUser } from '@/auth/authStore'
import { fetchIssueTypes } from '@/api/issue-types'
import { issueCreateStrings, issueDetailStrings } from '@/i18n/ko'
import type { CustomFieldValues } from '@/api/issues'
import type { CustomField } from '@/api/custom-fields.types'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 폼 스키마 — interface 중복 정의 금지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 제목(summary) 최대 길이 — zod 검증과 URL summary 프리필 clamp(FR-UX-04 FR7)가 공유.
 *
 * ★백엔드 `CreateIssueRequest.summary` 가 `@Size(max = 200)` 다. 프론트가 500 을 허용하던 동안
 * 201~500자 제목은 **프론트 검증을 통과한 뒤 백엔드 400** 을 맞았다(선재 결함).
 * FR-UX-09 F2 에서 200 으로 정렬한다.
 */
const SUMMARY_MAX_LENGTH = 200

/** 이슈 생성 폼 입력 Zod 스키마 */
const issueCreateSchema = z.object({
  projectKey: z.string().min(1, issueCreateStrings.projectKeyRequired),
  summary: z
    .string()
    .min(1, issueCreateStrings.summaryRequired)
    .max(SUMMARY_MAX_LENGTH, issueCreateStrings.summaryTooLong),
  /** FR-UX-09 F2 — 본문. 비우면 서버가 프로젝트 템플릿으로 채운다(FR-TM-01)라 필수가 아니다. */
  description: z.string(),
})

/** Zod 스키마에서 추론한 폼 값 타입 */
type IssueCreateFormValues = z.infer<typeof issueCreateSchema>

/**
 * 명령 팔레트 `/issue <제목>`(FR-UX-04)가 넘긴 URL summary를 폼 기본값으로 쓸 수 있게 정제한다.
 *
 * - undefined → 빈 문자열(기존 동작 유지, 회귀 방지)
 * - 앞뒤 공백 trim
 * - zod max(SUMMARY_MAX_LENGTH)를 넘으면 제출 시 검증 에러가 바로 뜨는 것을 막기 위해 clamp
 *
 * @param rawSummary URL search param에서 읽은 summary(선택)
 * @returns 폼 defaultValues.summary에 바로 쓸 수 있는 문자열
 */
function sanitizeInitialSummary(rawSummary: string | undefined): string {
  if (rawSummary === undefined) return ''
  return rawSummary.trim().slice(0, SUMMARY_MAX_LENGTH)
}

// ─────────────────────────────────────────────────────────────────────────────
// isRequiredFieldEmpty — required 필드 빈값 판정 헬퍼 (스펙 E-3)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * required 커스텀 필드의 현재 값이 비어 있는지 판정한다 (스펙 E-3 기준).
 *
 * - SHORT_TEXT/LONG_TEXT/URL/DATE/DATETIME/SINGLE_SELECT/RADIO: '' | undefined | null → 빈값
 * - NUMBER: undefined | null | NaN → 빈값. 0은 유효값.
 * - MULTI_SELECT: 빈 배열 → 빈값.
 * - CHECKBOX: 항상 값 보유 → 빈값 아님.
 */
function isRequiredFieldEmpty(
  fieldType: CustomField['fieldType'],
  raw: unknown,
): boolean {
  switch (fieldType) {
    case 'SHORT_TEXT':
    case 'LONG_TEXT':
    case 'URL':
    case 'DATE':
    case 'DATETIME':
    case 'SINGLE_SELECT':
    case 'RADIO':
      return raw === '' || raw === undefined || raw === null
    case 'NUMBER':
      return raw === undefined || raw === null || (typeof raw === 'number' && isNaN(raw))
    case 'MULTI_SELECT':
      return Array.isArray(raw) && raw.length === 0
    case 'CHECKBOX':
      return false
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
    const body = err.body
    if (typeof body === 'object' && body !== null && 'errorCode' in body) {
      const { errorCode } = body as { errorCode: unknown }
      if (errorCode === 'PROJECT_NOT_FOUND') {
        return issueCreateStrings.errorProjectNotFound
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
}

/**
 * 이슈 생성 폼 컴포넌트.
 *
 * - projectKey + summary + 컴포넌트 선택 입력 필드
 * - 클라이언트 Zod 검증: 빈 값 제출 차단
 * - 제출 성공 시 onSuccess(key) 콜백 호출
 * - PROJECT_NOT_FOUND(404) 시 role="alert" 에러 메시지 노출
 * - projectKey 비어있으면 ComponentMultiSelect disabled (lazy 로드)
 * - initialSummary가 있으면 제목 필드 기본값으로 사용(FR-UX-04 FR7)
 *
 * 라우터 의존 없이 props로 onSuccess를 받아 단위 테스트가 가능하다.
 */
export function IssueCreateForm({
  onSuccess,
  initialSummary,
  onCreateProject,
}: IssueCreateFormProps = {}): JSX.Element {
  const [serverError, setServerError] = useState<string | null>(null)
  const [selectedComponentIds, setSelectedComponentIds] = useState<string[]>([])
  const [selectedSecurityLevelId, setSelectedSecurityLevelId] = useState<string | null>(null)
  const [customFieldValues, setCustomFieldValues] = useState<CustomFieldValues>({})
  const [customFieldRequiredError, setCustomFieldRequiredError] = useState(false)

  // FR-UX-09 F2 — 이슈 유형. zod 가 아니라 로컬 상태로 둔다(셀렉터라 텍스트 검증 대상이 아니다).
  const [selectedTypeId, setSelectedTypeId] = useState<number | null>(null)

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

  // ── FR-3 프로젝트 셀렉터 ──────────────────────────────────────────────────
  const { data: projects = [], isLoading: isProjectsLoading } = useProjects()
  const activeProjectKey = useActiveProject((s) => s.activeProjectKey)
  const authUser = useAuthUser()
  const canCreateProject = authUser?.canCreateProject === true

  /**
   * 기본 선택 프로젝트 — 활성 프로젝트(FR-UX-07)가 목록에 있으면 그것, 없으면 목록 첫 번째.
   * 활성 프로젝트가 아카이브되었거나 권한을 잃은 경우를 목록 대조로 걸러낸다.
   */
  const defaultProjectKey = useMemo(() => {
    if (projects.length === 0) return ''
    if (activeProjectKey !== null && projects.some((p) => p.key === activeProjectKey)) {
      return activeProjectKey
    }
    return projects[0]?.key ?? ''
  }, [projects, activeProjectKey])

  // 목록은 비동기로 도착하는데 react-hook-form 의 defaultValues 는 mount 시 1회만 적용된다.
  // 아직 사용자가 고르지 않았을 때만 채운다 — 이미 고른 값을 덮으면 조작이 되돌려진다.
  useEffect(() => {
    if (defaultProjectKey !== '' && form.getValues('projectKey') === '') {
      form.setValue('projectKey', defaultProjectKey)
    }
  }, [defaultProjectKey, form])

  // ── FR-4 이슈 유형 ────────────────────────────────────────────────────────
  const { data: issueTypes = [], isLoading: isTypesLoading } = useQuery({
    queryKey: ['issue-types'],
    queryFn: fetchIssueTypes,
  })

  // 기본 유형은 `task` — 백엔드가 typeId 미전달 시 적용하는 fallback 과 같은 값이다.
  useEffect(() => {
    if (selectedTypeId === null && issueTypes.length > 0) {
      const task = issueTypes.find((t) => t.key === 'task') ?? issueTypes[0]
      if (task !== undefined) setSelectedTypeId(task.id)
    }
  }, [issueTypes, selectedTypeId])

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

  const { data: componentData } = useComponents(projectKey, {
    enabled: isProjectKeyFilled,
  })
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

  function handleSubmit(values: IssueCreateFormValues): void {
    // 스펙 E-3: required 커스텀 필드 빈값 1차 검사 — mutation 전 차단
    const hasRequiredEmpty = customFieldDefs.some(
      (field) => field.required && isRequiredFieldEmpty(field.fieldType, customFieldValues[field.key]),
    )
    if (hasRequiredEmpty) {
      setCustomFieldRequiredError(true)
      return
    }
    setCustomFieldRequiredError(false)
    setServerError(null)
    const { description, ...rest } = values
    mutation.mutate({
      ...rest,
      // FR-UX-09 F2 — 유형은 선택 전(목록 로딩 중)이면 키를 빼 서버 fallback(task)에 맡긴다.
      ...(selectedTypeId !== null ? { typeId: selectedTypeId } : {}),
      // 본문이 비면 키를 빼 서버가 프로젝트 템플릿으로 채우게 한다 (FR-TM-01).
      ...(description.trim() !== '' ? { description } : {}),
      componentIds: selectedComponentIds,
      // securityLevelId null은 명시적으로 전달 — 미선택(null)이면 body에 포함해 서버가 무등급으로 처리
      securityLevelId: selectedSecurityLevelId,
      // customFields: 값이 있으면 포함, 빈 맵이면 미전달 (서버 기본값 사용)
      ...(Object.keys(customFieldValues).length > 0 ? { customFields: customFieldValues } : {}),
    })
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
      <form
        onSubmit={form.handleSubmit(handleSubmit)}
        noValidate
        className="space-y-4"
      >
        {/* 서버 에러 알림 영역 */}
        {serverError !== null && (
          <p role="alert" className="text-sm text-destructive">
            {serverError}
          </p>
        )}

        {/* 프로젝트 선택 — 자유 텍스트가 아니라 접근 가능한 프로젝트 목록에서 고른다 (FR-3) */}
        <FormField
          control={form.control}
          name="projectKey"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{issueCreateStrings.projectKeyLabel}</FormLabel>
              <FormControl>
                {/* aria-label — FormLabel.htmlFor 가 wrapper div 를 가리키므로 select 자체에 aria-label 로 WCAG AA 보장 */}
                <select
                  className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
                  aria-label={issueCreateStrings.projectKeyLabel}
                  disabled={isProjectsLoading}
                  {...field}
                >
                  <option value="">{issueCreateStrings.projectPlaceholder}</option>
                  {projects.map((project) => (
                    <option key={project.key} value={project.key}>
                      {project.name}
                    </option>
                  ))}
                </select>
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {/* 이슈 유형 선택 — meta/IssueTypeSelect 재사용 (FR-4) */}
        <div className="flex flex-col gap-1.5">
          {/* 눈에 보이는 라벨과 접근성 이름을 같게 둔다 — IssueTypeSelect 가 자체 aria-label
              (issueDetailStrings.typeSelectLabel)을 갖고 있어, 다른 문구를 쓰면
              WCAG 2.5.3 Label in Name 이 어긋난다. */}
          <span className="text-sm font-medium leading-none">
            {issueDetailStrings.typeSelectLabel}
          </span>
          {isTypesLoading || selectedTypeId === null ? (
            // 유형 목록이 도착하기 전에는 셀렉터를 비활성으로 둔다 — IssueTypeSelect 는
            // value:number 필수라 빈 목록으로 렌더하면 선택 없는 빈 셀렉터가 된다 (design 리뷰 B-3).
            <select
              className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px]"
              aria-label={issueDetailStrings.typeSelectLabel}
              disabled
            />
          ) : (
            <IssueTypeSelect
              value={selectedTypeId}
              availableTypes={issueTypes}
              currentTypeId={selectedTypeId}
              onTypeChange={setSelectedTypeId}
            />
          )}
        </div>

        {/* 이슈 제목 입력 */}
        <FormField
          control={form.control}
          name="summary"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{issueCreateStrings.summaryLabel}</FormLabel>
              <FormControl>
                <Input
                  placeholder="이슈 제목을 입력하세요"
                  aria-label={issueCreateStrings.summaryLabel}
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {/* 본문 입력 — 비우면 서버가 프로젝트 템플릿으로 채운다 (FR-5, FR-TM-01) */}
        <FormField
          control={form.control}
          name="description"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{issueCreateStrings.descriptionLabel}</FormLabel>
              <FormControl>
                <Textarea
                  rows={4}
                  placeholder={issueCreateStrings.descriptionPlaceholder}
                  aria-label={issueCreateStrings.descriptionLabel}
                  {...field}
                />
              </FormControl>
              <p className="text-xs text-muted-foreground">
                {issueCreateStrings.descriptionTemplateHint}
              </p>
              <FormMessage />
            </FormItem>
          )}
        />

        {/* 컴포넌트 선택 — projectKey 미입력 시 disabled */}
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
            {issueDetailStrings.componentsLabel}
          </span>
          <ComponentMultiSelect
            value={selectedComponentIds}
            options={componentOptions}
            onChange={setSelectedComponentIds}
            disabled={!isProjectKeyFilled}
          />
        </div>

        {/* 보안등급 선택 — projectKey 입력 시 등급 목록 로드 (FR-PM-06 PR-B) */}
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
            {issueDetailStrings.securityLevelCreateLabel}
          </span>
          <IssueSecurityLevelSelect
            projectKey={projectKey}
            value={selectedSecurityLevelId}
            onChange={setSelectedSecurityLevelId}
            disabled={!isProjectKeyFilled}
          />
        </div>

        {/* 커스텀 필드 섹션 — projectKey 입력 + 활성 정의가 있을 때만 렌더 (FR-IS-10) */}
        {isProjectKeyFilled && customFieldDefs.length > 0 && (
          <div data-testid="custom-fields-section" className="flex flex-col gap-3">
            {customFieldDefs.map((field) => (
              <div key={field.id} className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none">
                  {field.name}
                  {field.required && <span className="text-destructive ml-0.5">*</span>}
                </span>
                <CustomFieldInput
                  field={field}
                  value={customFieldValues[field.key]}
                  onChange={(v) => {
                    setCustomFieldValues((prev) => ({ ...prev, [field.key]: v }))
                    if (customFieldRequiredError) setCustomFieldRequiredError(false)
                  }}
                  disabled={!isProjectKeyFilled}
                />
              </div>
            ))}
          </div>
        )}

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

        <Button
          type="submit"
          disabled={mutation.isPending}
          className="w-full sm:w-auto"
        >
          {issueCreateStrings.submitButton}
        </Button>
      </form>
    </Form>
  )
}
