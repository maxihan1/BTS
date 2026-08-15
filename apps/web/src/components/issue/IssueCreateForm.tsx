// 이슈 생성 폼 — 라우터 비의존 제어 컴포넌트 (FR-UX-09 F2 로 모달·라우트가 공유)
import type { JSX } from 'react'
import { Form } from '@/components/ui/form'
import { EmptyState } from '@/components/ui/empty-state'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { IssueCreateBasicFields } from '@/components/issue/create/IssueCreateBasicFields'
import { IssueCreateAssignmentFields } from '@/components/issue/create/IssueCreateAssignmentFields'
import { IssueCreateExtraFields } from '@/components/issue/create/IssueCreateExtraFields'
import { useIssueCreatePermissionGate } from '@/components/issue/create/use-issue-create-permission-gate'
import { useIssueCreateFormState } from '@/components/issue/create/use-issue-create-form-state'
import { useIssueCreateSubmit } from '@/components/issue/create/use-issue-create-submit'
import { useAuthUser } from '@/auth/authStore'
import { issueCreateStrings, issueDetailStrings } from '@/i18n/ko'

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
 * 상태는 [useIssueCreateFormState], 제출은 [useIssueCreateSubmit] 이 쥔다 (부채 매핑 8).
 * 이 컴포넌트에 남는 것은 **게이트 판정과 화면 조립**뿐이다.
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
  const state = useIssueCreateFormState({ initialSummary, initialProjectKey })

  // 선택된 프로젝트의 CREATE 게이트. 판정식의 근거(미지 ≠ 거부)는 훅 KDoc 에 있다.
  // ★이 import 는 이 파일에 남아 있어야 한다 — 「로딩 프레임 계약」 레지스트리(부채 매핑 15)가
  //   import 문으로 소비처를 탐지하고, 이 화면의 계약은 `open-while-loading` 로 선언돼 있다.
  const isCreateExplicitlyDenied = useIssueCreatePermissionGate(state.projectKey)

  const submit = useIssueCreateSubmit(state, {
    isCreateExplicitlyDenied,
    onSuccess,
    onPendingChange,
  })

  const authUser = useAuthUser()
  const canCreateProject = authUser?.canCreateProject === true

  // FR-17 — 접근 가능한 프로젝트가 0개면 채울 수 없는 폼 대신 이유와 다음 행동을 보여준다.
  // 목록 로딩이 끝난 뒤에만 판정한다 — 로딩 중 빈 배열을 0개로 오인하면 화면이 깜빡인다.
  if (!state.isProjectsLoading && state.projects.length === 0) {
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
    <Form {...state.form}>
      <form
        id={formId}
        onSubmit={state.form.handleSubmit(submit.submit)}
        noValidate
        className="space-y-4"
      >
        {/* 서버 에러 알림 영역 */}
        {state.serverError !== null && (
          <p role="alert" className="text-sm text-destructive">
            {state.serverError}
          </p>
        )}

        <IssueCreateBasicFields
          control={state.form.control}
          projects={state.projects}
          isProjectsLoading={state.isProjectsLoading}
          issueTypes={state.issueTypes}
          isTypesLoading={state.isTypesLoading}
          selectedTypeId={state.selectedTypeId}
          onTypeChange={state.setSelectedTypeId}
        />

        <Separator />

        <IssueCreateAssignmentFields
          assignee={state.assignee}
          priority={state.priority}
          onPriorityChange={state.setPriority}
          labels={state.labels}
          onLabelsChange={state.setLabels}
        />

        <Separator />

        <IssueCreateExtraFields
          projectKey={state.projectKey}
          isProjectKeyFilled={state.isProjectKeyFilled}
          componentOptions={state.componentOptions}
          selectedComponentIds={state.selectedComponentIds}
          onComponentsChange={state.setSelectedComponentIds}
          securityLevelId={state.securityLevelId}
          onSecurityLevelChange={state.setSecurityLevelId}
          customFieldDefs={state.customFieldDefs}
          customFieldValues={state.customFieldValues}
          onCustomFieldChange={state.handleCustomFieldChange}
        />

        {/* required 커스텀 필드 빈값 경고 — 스펙 E-3 1차 클라 경고 */}
        {state.customFieldRequiredError && (
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
            disabled={submit.isPending || isCreateExplicitlyDenied}
            className="w-full sm:w-auto"
          >
            {submit.isPending
              ? issueCreateStrings.submitButtonPending
              : issueCreateStrings.submitButton}
          </Button>
        )}
      </form>
    </Form>
  )
}
