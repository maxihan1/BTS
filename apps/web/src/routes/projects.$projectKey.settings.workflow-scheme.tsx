// 프로젝트 워크플로우 스킴 할당 페이지 — 배정 조회/변경(UPSERT) + 조회 실패 분기
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useGetAssignment, useUpdateAssignment } from '@/hooks/use-workflow-scheme-assignment'
import { useAssignableWorkflowSchemes } from '@/hooks/use-workflow-schemes'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'
import { ApiError } from '@/api/client'
import { WorkflowSchemeApiError } from '@/api/workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectWorkflowSchemeSettingsPage에 전달한다.
 */
export function ProjectWorkflowSchemeSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectWorkflowSchemeSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectWorkflowSchemeSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  projectKey: string
}

/** 페이지 상단 헤더 — 정상/403 분기가 공통으로 사용 */
function PageHeader(): JSX.Element {
  return (
    <header className="space-y-1">
      <h1 className="text-2xl font-semibold">{workflowSchemeLabels.assignment.pageHeading}</h1>
      <p className="text-muted-foreground text-sm">
        {workflowSchemeLabels.assignment.pageDescription}
      </p>
    </header>
  )
}

/**
 * 프로젝트 워크플로우 스킴 할당 설정 페이지.
 *
 * - useGetAssignment(projectKey): 현재 할당된 스킴 조회.
 * - useAssignableWorkflowSchemes(projectKey): 이 프로젝트에 배정 가능한 스킴 목록 (Select 옵션용).
 *   조회 실패 시 빈 Select 방치 금지 — 403(ASSIGN_SCHEME 권한 없음)은 ForbiddenSchemeCard,
 *   그 외(404/500/네트워크 단절 등)는 SchemeLoadErrorCard로 대체.
 * - assignment 조회 성공 → 현재 스킴 카드 + 「현재 적용」 indicator + 변경 select.
 * - 적용 버튼 → useUpdateAssignment.mutate({ schemeKey }) → 낙관적 업데이트 + 토스트.
 *
 * ## 배정 조회 404 는 「미할당」이 아니라 「프로젝트 없음」이다
 * 백엔드는 배정 없는 프로젝트에 software-scheme 을 자동 배정해 200 으로 돌려준다(EC-1 D10).
 * 그래서 이 화면에 「스킴 미할당」 상태는 존재하지 않는다 — 예전에는 404 를 미할당으로 읽어,
 * 존재하지 않는 프로젝트 URL 로 들어가면 틀린 안내 카드가 떴다. 지금은 `ProjectNotFoundCard` 다.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectWorkflowSchemeSettingsPage({
  projectKey,
}: ProjectWorkflowSchemeSettingsPageProps): JSX.Element {
  const {
    data: assignment,
    isLoading: assignmentLoading,
    error: assignmentError,
  } = useGetAssignment(projectKey)
  const { data: schemes, isLoading: schemesLoading, error: schemesError } = useAssignableWorkflowSchemes(projectKey)
  const updateAssignment = useUpdateAssignment(projectKey)

  const [selectedSchemeKey, setSelectedSchemeKey] = useState<string>('')

  const isLoading = assignmentLoading || schemesLoading

  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">
        로딩 중...
      </div>
    )
  }

  // 배정 조회 실패 — 404 는 이 엔드포인트에서 「프로젝트 없음」 하나뿐이므로 전용 안내를 낸다.
  // 나머지(권한/서버 오류)는 아래 목록 조회 실패와 같은 문구로 묶는다.
  if (assignmentError != null) {
    return (
      <div className="p-8 space-y-6 max-w-2xl">
        <PageHeader />
        {assignmentError instanceof WorkflowSchemeApiError && assignmentError.status === 404 ? (
          <ProjectNotFoundCard />
        ) : (
          <SchemeLoadErrorCard />
        )}
      </div>
    )
  }

  // 스킴 목록 조회 실패 — 스킴 0건(200 + [])과는 원인이 다르므로 빈 Select로 방치하지 않는다.
  // 403(ASSIGN_SCHEME 권한 없음)은 전용 안내를, 그 외(404/500/네트워크 단절 등)는 별도 안내를 보여준다.
  if (schemesError != null) {
    return (
      <div className="p-8 space-y-6 max-w-2xl">
        <PageHeader />
        {schemesError instanceof ApiError && schemesError.status === 403 ? (
          <ForbiddenSchemeCard />
        ) : (
          <SchemeLoadErrorCard />
        )}
      </div>
    )
  }

  const schemeOptions = schemes ?? []

  function handleApply() {
    const target = selectedSchemeKey !== '' ? selectedSchemeKey : (assignment?.key ?? '')
    if (target === '') return
    updateAssignment.mutate({ schemeKey: target })
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <PageHeader />

      {/* 현재 할당된 스킴 카드 — 조회가 성공하면 배정은 항상 존재한다 (백엔드 자동 배정) */}
      {assignment !== undefined && (
        <Card>
          <CardHeader>
            <CardTitle>{workflowSchemeLabels.assignment.currentSchemeTitle}</CardTitle>
            <CardDescription>현재 이 프로젝트에 적용 중인 워크플로우 스킴입니다.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-center gap-2">
              <span className="font-medium">{assignment.name}</span>
              <span className="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded-full">
                {workflowSchemeLabels.assignment.currentSchemeBadge}
              </span>
            </div>
          </CardContent>
        </Card>
      )}

      {/* 스킴 변경 섹션 */}
      <Card>
        <CardHeader>
          <CardTitle>{workflowSchemeLabels.assignment.changeTitle}</CardTitle>
          <CardDescription>변경할 스킴을 선택하고 적용 버튼을 누르세요.</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-3">
            <Select
              value={selectedSchemeKey !== '' ? selectedSchemeKey : (assignment?.key ?? '')}
              onValueChange={setSelectedSchemeKey}
            >
              <SelectTrigger className="w-64" aria-label={workflowSchemeLabels.assignment.schemeSelectAriaLabel}>
                <SelectValue placeholder={workflowSchemeLabels.schemeSelectPlaceholder} />
              </SelectTrigger>
              <SelectContent>
                {schemeOptions.map((scheme) => (
                  <SelectItem key={scheme.key} value={scheme.key}>
                    {scheme.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            <Button
              onClick={handleApply}
              disabled={updateAssignment.isPending}
            >
              {workflowSchemeLabels.assignment.applyButton}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ProjectNotFoundCard — 배정 조회 404 안내 카드 (독립 named export — 재사용 가능)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 배정 조회가 404 로 실패한 경우 표시되는 안내 카드.
 *
 * 이 엔드포인트의 404 는 「프로젝트를 찾을 수 없음」 하나뿐이다 — 배정이 없는 프로젝트에는
 * 백엔드가 `software-scheme` 을 자동 배정해 200 을 돌려주기 때문이다
 * (`WorkflowSchemeApplicationService.findAssignedScheme`, EC-1 D10).
 *
 * 이 카드가 대체한 것은 예전의 `UnassignedSchemeCard` 다. 그 카드는 404 를 「스킴 미할당」으로
 * 읽은 결과였고, 오타 난 프로젝트 키로 들어온 사용자에게 "곧 자동 할당됩니다" 라는 **틀린 안내**를
 * 보여줬다. 관측되지 않는 상태를 위한 UI 였으므로 되살리지 말 것.
 */
export function ProjectNotFoundCard(): JSX.Element {
  return (
    <Card className="border-destructive/40 bg-destructive/10">
      <CardHeader>
        <CardTitle className="text-destructive">
          {workflowSchemeLabels.assignment.projectNotFoundTitle}
        </CardTitle>
      </CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        <p>{workflowSchemeLabels.assignment.projectNotFoundMessage}</p>
      </CardContent>
    </Card>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ForbiddenSchemeCard — 403 안내 카드 (독립 named export — 재사용 가능)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 배정 가능한 워크플로우 스킴 목록 조회가 403(ASSIGN_SCHEME 권한 없음)으로
 * 실패한 경우 표시되는 안내 카드. 스킴 0건(200 + `[]`)인 정상 상태와는 원인이 다르므로
 * 빈 Select로 방치하지 않고 이 카드로 대체한다.
 * named export로 다른 페이지에서 재사용 가능하다.
 */
export function ForbiddenSchemeCard(): JSX.Element {
  return (
    <Card className="border-destructive/40 bg-destructive/10">
      <CardHeader>
        <CardTitle className="text-destructive">
          {workflowSchemeLabels.assignment.forbiddenTitle}
        </CardTitle>
      </CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        <p>{workflowSchemeLabels.assignment.forbiddenMessage}</p>
      </CardContent>
    </Card>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SchemeLoadErrorCard — 403 이외 조회 실패 안내 카드 (독립 named export — 재사용 가능)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 배정 가능한 워크플로우 스킴 목록 조회가 403 이외의 사유(404/500/네트워크 단절 등)로
 * 실패한 경우 표시되는 안내 카드. 오타 프로젝트 키로 인한 404, 서버 오류로 인한 500 등도
 * 빈 Select로 방치하지 않고 이 카드로 대체한다. 403 전용 안내(ForbiddenSchemeCard)와는
 * 원인이 다르므로 별도 문구를 사용한다.
 * named export로 다른 페이지에서 재사용 가능하다.
 */
export function SchemeLoadErrorCard(): JSX.Element {
  return (
    <Card className="border-destructive/40 bg-destructive/10">
      <CardHeader>
        <CardTitle className="text-destructive">
          {workflowSchemeLabels.assignment.loadErrorTitle}
        </CardTitle>
      </CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        <p>{workflowSchemeLabels.assignment.loadErrorMessage}</p>
      </CardContent>
    </Card>
  )
}
