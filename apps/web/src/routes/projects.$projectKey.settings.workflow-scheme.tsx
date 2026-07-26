// 프로젝트 워크플로우 스킴 할당 페이지 — EC-1 (할당 없음) 정상 처리 + UPSERT
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
 * - useGetAssignment(projectKey): 현재 할당된 스킴 조회. 404 → null (EC-1 정상 케이스).
 * - useAssignableWorkflowSchemes(projectKey): 이 프로젝트에 배정 가능한 스킴 목록 (Select 옵션용).
 *   조회 실패 시 빈 Select 방치 금지 — 403(MANAGE_WORKFLOW 권한 없음)은 ForbiddenSchemeCard,
 *   그 외(404/500/네트워크 단절 등)는 SchemeLoadErrorCard로 대체.
 * - assignment null 분기 → UnassignedSchemeCard + 신규 할당 선택 UI.
 * - assignment 있음 → 현재 스킴 카드 + 「현재 적용」 indicator + 변경 select.
 * - 적용 버튼 → useUpdateAssignment.mutate({ schemeKey }) → 낙관적 업데이트 + 토스트.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectWorkflowSchemeSettingsPage({
  projectKey,
}: ProjectWorkflowSchemeSettingsPageProps): JSX.Element {
  const { data: assignment, isLoading: assignmentLoading } = useGetAssignment(projectKey)
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

  // 스킴 목록 조회 실패 — 스킴 0건(200 + [])과는 원인이 다르므로 빈 Select로 방치하지 않는다.
  // 403(MANAGE_WORKFLOW 권한 없음)은 전용 안내를, 그 외(404/500/네트워크 단절 등)는 별도 안내를 보여준다.
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
    const target = selectedSchemeKey !== '' ? selectedSchemeKey : (assignment?.schemeKey ?? '')
    if (target === '') return
    updateAssignment.mutate({ schemeKey: target })
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <PageHeader />

      {/* EC-1 — 할당 없음 안내 카드 */}
      {assignment === null || assignment === undefined ? (
        <UnassignedSchemeCard />
      ) : (
        /* 현재 할당된 스킴 카드 */
        <Card>
          <CardHeader>
            <CardTitle>{workflowSchemeLabels.assignment.currentSchemeTitle}</CardTitle>
            <CardDescription>현재 이 프로젝트에 적용 중인 워크플로우 스킴입니다.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-center gap-2">
              <span className="font-medium">{assignment.schemeName}</span>
              <span className="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded-full">
                {workflowSchemeLabels.assignment.currentSchemeBadge}
              </span>
            </div>
          </CardContent>
        </Card>
      )}

      {/* 스킴 변경 / 신규 할당 섹션 */}
      <Card>
        <CardHeader>
          <CardTitle>
            {assignment === null || assignment === undefined ? workflowSchemeLabels.assignment.assignTitle : workflowSchemeLabels.assignment.changeTitle}
          </CardTitle>
          <CardDescription>
            {assignment === null || assignment === undefined
              ? '프로젝트에 적용할 스킴을 선택하세요.'
              : '변경할 스킴을 선택하고 적용 버튼을 누르세요.'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-3">
            <Select
              value={selectedSchemeKey !== '' ? selectedSchemeKey : (assignment?.schemeKey ?? '')}
              onValueChange={setSelectedSchemeKey}
            >
              <SelectTrigger className="w-64" aria-label={workflowSchemeLabels.assignment.schemeSelectAriaLabel}>
                <SelectValue placeholder="스킴 선택..." />
              </SelectTrigger>
              <SelectContent>
                {schemeOptions.map((scheme) => (
                  <SelectItem key={scheme.schemeKey} value={scheme.schemeKey}>
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
// UnassignedSchemeCard — EC-1 안내 카드 (독립 named export — 재사용 가능)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * EC-1 — 프로젝트에 워크플로우 스킴이 할당되지 않은 경우 표시되는 안내 카드.
 * 자동 할당 동작과 미리 지정 가능 여부를 안내한다.
 * named export로 다른 페이지에서 재사용 가능하다.
 */
export function UnassignedSchemeCard(): JSX.Element {
  return (
    <Card className="border-warning/40 bg-warning/10">
      <CardHeader>
        <CardTitle className="text-warning-text">{workflowSchemeLabels.assignment.unassignedTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm text-muted-foreground">
        <p>이 프로젝트는 아직 워크플로우 스킴이 할당되지 않았습니다.</p>
        <p>
          첫 이슈 전이 시{' '}
          <code className="font-mono bg-muted px-1 rounded text-foreground">
            software-default-scheme
          </code>
          {' '}가 자동 할당됩니다.
        </p>
        <p>원하는 스킴을 미리 지정할 수도 있습니다.</p>
      </CardContent>
    </Card>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ForbiddenSchemeCard — 403 안내 카드 (독립 named export — 재사용 가능)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 배정 가능한 워크플로우 스킴 목록 조회가 403(MANAGE_WORKFLOW 권한 없음)으로
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
