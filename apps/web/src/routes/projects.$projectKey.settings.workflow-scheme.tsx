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
import { useWorkflowSchemes } from '@/hooks/use-workflow-schemes'

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

/**
 * 프로젝트 워크플로우 스킴 할당 설정 페이지.
 *
 * - useGetAssignment(projectKey): 현재 할당된 스킴 조회. 404 → null (EC-1 정상 케이스).
 * - useWorkflowSchemes(): 전체 스킴 목록 (Select 옵션용).
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
  const { data: schemes, isLoading: schemesLoading } = useWorkflowSchemes()
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

  const schemeOptions = schemes ?? []

  function handleApply() {
    const target = selectedSchemeKey !== '' ? selectedSchemeKey : (assignment?.schemeKey ?? '')
    if (target === '') return
    updateAssignment.mutate({ schemeKey: target })
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">워크플로우 스킴 설정</h1>
        <p className="text-muted-foreground text-sm">
          이 프로젝트에 적용할 워크플로우 스킴을 지정합니다.
        </p>
      </header>

      {/* EC-1 — 할당 없음 안내 카드 */}
      {assignment === null || assignment === undefined ? (
        <UnassignedSchemeCard />
      ) : (
        /* 현재 할당된 스킴 카드 */
        <Card>
          <CardHeader>
            <CardTitle>현재 할당된 스킴</CardTitle>
            <CardDescription>현재 이 프로젝트에 적용 중인 워크플로우 스킴입니다.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-center gap-2">
              <span className="font-medium">{assignment.schemeName}</span>
              <span className="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded-full">
                현재 적용
              </span>
            </div>
          </CardContent>
        </Card>
      )}

      {/* 스킴 변경 / 신규 할당 섹션 */}
      <Card>
        <CardHeader>
          <CardTitle>
            {assignment === null || assignment === undefined ? '스킴 지정' : '스킴 변경'}
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
              <SelectTrigger className="w-64" aria-label="워크플로우 스킴 선택">
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
              적용
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
    <Card className="border-amber-500/40 bg-amber-50/30 dark:bg-amber-950/20">
      <CardHeader>
        <CardTitle className="text-amber-700 dark:text-amber-400">스킴 미할당</CardTitle>
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
