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
import {
  useAssignableWorkflowSchemes,
  useCreateWorkflowScheme,
  useManagedWorkflowSchemes,
  useWorkflowSchemeDetail,
} from '@/hooks/use-workflow-schemes'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { WorkflowSchemeSidebar } from '@/components/admin/WorkflowSchemeSidebar'
import { MappingTable } from '@/components/admin/MappingTable'
import { SchemeMetaPanel } from '@/components/admin/SchemeMetaPanel'
import type { SchemeDetail, SchemeListItem } from '@/api/workflow-schemes'
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
      <h2 className="text-xl font-semibold">{workflowSchemeLabels.assignment.pageHeading}</h2>
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

      {/* 스킴 관리 — 생성·매핑·메타 편집 (FR-WF-08 PR ⑤) */}
      <ProjectSchemeManagement projectKey={projectKey} />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ProjectSchemeManagement — 사이드바 + 매핑표 + 메타패널 (FR-WF-08 PR ⑤)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 설정에서 스킴을 만들고 고치는 구역.
 *
 * ## 세 컴포넌트를 그대로 재사용한다
 * `WorkflowSchemeSidebar` · `MappingTable` · `SchemeMetaPanel` 은 관리자 화면의 것이고
 * **내부를 고치지 않았다.** 사이드바만 목록을 props 로 받도록 열었다 — 컴포넌트가 「어느 화면에서
 * 열렸는지」를 알게 되면 두 화면의 동작이 서로를 검사하지 않은 채 갈리기 시작한다.
 *
 * ## 목록은 프로젝트 스코프 창구를 탄다
 * `useWorkflowSchemes`(전역 관리자 목록)는 `MANAGE_SCHEME` + `Global` 게이트라 프로젝트
 * 관리자에게 403 이고, 통과하더라도 남의 프로젝트 전용 스킴이 함께 온다.
 */
function ProjectSchemeManagement({ projectKey }: { projectKey: string }): JSX.Element {
  const { data: schemes, isPending, error } = useManagedWorkflowSchemes(projectKey)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const { data: detail } = useWorkflowSchemeDetail(selectedKey)

  if (error != null) {
    return error instanceof ApiError && error.status === 403 ? <ForbiddenSchemeCard /> : <SchemeLoadErrorCard />
  }

  const list = schemes ?? []
  const selected = list.find((scheme) => scheme.key === selectedKey) ?? null

  return (
    <section className="flex min-h-96 rounded-md border border-border">
      <WorkflowSchemeSidebar
        selectedSchemeKey={selectedKey ?? undefined}
        onSelect={(key) => {
          setCreating(false)
          setSelectedKey(key)
        }}
        onAddNew={() => {
          setSelectedKey(null)
          setCreating(true)
        }}
        schemes={list}
        isPending={isPending}
      />

      <div className="flex min-w-0 flex-1 flex-col">
        {creating && (
          <ProjectSchemeCreateForm
            projectKey={projectKey}
            onCreated={(key) => {
              setCreating(false)
              setSelectedKey(key)
            }}
          />
        )}
        {!creating && selected === null && (
          <p className="p-6 text-sm text-muted-foreground">
            {workflowSchemeLabels.projectManagement.pickPrompt}
          </p>
        )}
        {!creating && selected !== null && <ProjectSchemePane scheme={selected} detail={detail} />}
      </div>
    </section>
  )
}

/**
 * 프로젝트 전용 스킴 생성 폼.
 *
 * ★**`projectKey` 를 반드시 싣는다.** 빠뜨리면 전역 공유 템플릿이 만들어지고, 그 스킴은
 * 만든 사람조차 못 고친다(전역 편집은 SYSTEM_ADMIN 소관). 이 화면이 존재하는 이유가
 * 그 자리에서 사라진다 — 그래서 폼에 「전역으로 만들기」 선택지를 두지 않는다.
 */
function ProjectSchemeCreateForm({
  projectKey,
  onCreated,
}: {
  projectKey: string
  onCreated: (schemeKey: string) => void
}): JSX.Element {
  const labels = workflowSchemeLabels.projectManagement
  const createScheme = useCreateWorkflowScheme()
  const [key, setKey] = useState('')
  const [name, setName] = useState('')

  const canSubmit = key.trim() !== '' && name.trim() !== '' && !createScheme.isPending

  return (
    <form
      className="flex flex-col gap-3 p-6"
      onSubmit={(event) => {
        event.preventDefault()
        if (!canSubmit) return
        createScheme.mutate(
          { key: key.trim(), name: name.trim(), projectKey },
          { onSuccess: (created) => onCreated(created.key) },
        )
      }}
    >
      <h3 className="text-base font-semibold">{labels.createHeading}</h3>
      <label className="flex flex-col gap-1 text-sm">
        {labels.createKeyLabel}
        <Input value={key} onChange={(event) => setKey(event.target.value)} />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        {labels.createNameLabel}
        <Input value={name} onChange={(event) => setName(event.target.value)} />
      </label>
      <Button type="submit" className="self-start" disabled={!canSubmit}>
        {labels.createSubmit}
      </Button>
    </form>
  )
}

/**
 * 선택된 스킴 한 장의 편집 구역.
 *
 * 전역 템플릿과 이 프로젝트 전용을 **다르게 다뤄야 한다** — 전역 스킴 편집은 SYSTEM_ADMIN
 * 소관이라(스펙 §4 D6) 프로젝트 관리자가 저장을 누르면 403 이다.
 */
function ProjectSchemePane({
  scheme,
  detail,
}: {
  scheme: SchemeListItem
  detail: SchemeDetail | undefined
}): JSX.Element {
  // 전역 공유 템플릿은 SYSTEM_ADMIN 소관이다(스펙 §4 D6). 편집 컨트롤을 띄우면 눌러 보고
  // 403 을 받는 자리가 남으므로, 내용만 읽기 전용으로 보여준다.
  //
  // ★`MappingTable` 을 그대로 쓰지 않는 이유. 그 컴포넌트는 추가·삭제 뮤테이션을 살아 있는
  // 버튼으로 갖는다(`useAddMapping` · 삭제 확인). 「보여주기」와 「고치기」는 다른 관심사라
  // 여기서 갈라야 하고, 컴포넌트 안에 읽기 전용 모드를 넣는 것은 계획의 금지 조항이다
  // (컴포넌트가 어느 화면에서 열렸는지 알기 시작한다).
  if (scheme.projectId === null) {
    return <GlobalSchemeReadOnlyPane scheme={scheme} detail={detail} />
  }

  return (
    <div className="flex min-w-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col">
        {detail !== undefined && <MappingTable schemeKey={scheme.key} mappings={detail.mappings} />}
      </div>
      {detail !== undefined && <SchemeMetaPanel key={detail.key} scheme={detail} />}
    </div>
  )
}

/**
 * 전역 공유 템플릿의 **읽기 전용** 표시.
 *
 * 이 프로젝트가 지금 배정받아 쓰고 있는 스킴이 대개 전역이라(EC-1 D10 자동 배정), 목록에서
 * 빼면 자기 프로젝트가 쓰는 스킴을 관리 화면에서 못 본다. 그래서 목록에는 두고 여기서
 * **내용만** 보여준다 — 「목록에서 숨기기」가 아니라 「목록에 두되 액션을 가르기」다.
 *
 * 복제는 아직 없다 — 스킴에는 워크플로우의 `duplicate` 에 해당하는 API 가 없고,
 * 프론트에서 생성 + 매핑 N회로 흉내 내면 부분 실패가 조용히 남는다. 별건이다.
 */
function GlobalSchemeReadOnlyPane({
  scheme,
  detail,
}: {
  scheme: SchemeListItem
  detail: SchemeDetail | undefined
}): JSX.Element {
  const labels = workflowSchemeLabels.projectManagement
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-4 p-6">
      <header className="space-y-1">
        <h3 className="text-base font-semibold">{scheme.name}</h3>
        <p className="text-sm text-muted-foreground">{labels.globalReadOnlyNotice}</p>
      </header>

      {detail === undefined ? (
        <Skeleton className="h-32 w-full" />
      ) : (
        <ul className="flex flex-col gap-1 text-sm">
          {detail.mappings.map((mapping) => (
            <li key={mapping.id} className="flex gap-2">
              <span className="text-muted-foreground">
                {mapping.issueTypeName ?? labels.defaultMappingLabel}
              </span>
              <span aria-hidden="true">→</span>
              <span className="font-medium">{mapping.workflowName}</span>
            </li>
          ))}
        </ul>
      )}
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
