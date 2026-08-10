// 프로젝트 Import(CSV/JSON) 설정 라우트 페이지 — RouteAdapter(useParams) + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ImportForm } from '@/components/import/ImportForm'
import { ImportMappingWizard } from '@/components/import/mapping/ImportMappingWizard'
import { useIssueCreatePermissionGate } from '@/components/issue/create/use-issue-create-permission-gate'
import { importFailureMessage } from '@/api/imports'
import { importLabels } from '@/i18n/import-labels'
import { useProject } from '@/hooks/use-project'
import { ApiError } from '@/api/client'
import { ProjectNotFoundCard } from '@/routes/projects.$projectKey.settings.workflow-scheme'

// ─────────────────────────────────────────────────────────────────────────────
// 모드 토글 — "바로 가져오기"(ImportForm) / "매핑하며 가져오기"(ImportMappingWizard)
// ─────────────────────────────────────────────────────────────────────────────

type ImportPageMode = 'simple' | 'mapping'

/** 토글 렌더 순서 — Record 키 순회 시 필요한 `as` 캐스트를 피하기 위한 명시적 배열 */
const IMPORT_MODE_ORDER: readonly ImportPageMode[] = ['simple', 'mapping']

const IMPORT_MODE_LABELS: Record<ImportPageMode, string> = {
  simple: '바로 가져오기',
  mapping: '매핑하며 가져오기',
}

const IMPORT_MODE_HELP_TEXT =
  '바로 가져오기 = canonical 컬럼·JSON 첨부 zip / 매핑하며 가져오기 = 임의 CSV 컬럼·작성자·값 매핑'

interface ImportModeToggleProps {
  readonly mode: ImportPageMode
  readonly onModeChange: (mode: ImportPageMode) => void
}

/**
 * Import 모드 세그먼트 토글(DR-3) — 활성 모드는 `variant="default"` + `aria-pressed=true`로 표시하고,
 * 하단에 각 모드가 언제 적합한지 안내하는 도움말 한 줄을 함께 렌더한다.
 * shadcn tabs 컴포넌트가 없어 `Button` variant 토글로 구현한다(TimelineZoomControl 세그먼트 버튼 패턴 미러).
 */
function ImportModeToggle({ mode, onModeChange }: ImportModeToggleProps): JSX.Element {
  return (
    <div className="space-y-1.5">
      <div role="group" aria-label="Import 방식" className="flex gap-2">
        {IMPORT_MODE_ORDER.map((candidate) => (
          <Button
            key={candidate}
            type="button"
            variant={mode === candidate ? 'default' : 'outline'}
            size="sm"
            aria-pressed={mode === candidate}
            onClick={() => {
              onModeChange(candidate)
            }}
          >
            {IMPORT_MODE_LABELS[candidate]}
          </Button>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">{IMPORT_MODE_HELP_TEXT}</p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CREATE 권한 거부 안내
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 거부 안내 카드 본문.
 *
 * 서버가 403 으로 돌려줄 문장을 그대로 쓴다(`api/imports.ts` 의 `IMPORT_ACCESS_DENIED`).
 * 사전 신호와 사후 에러가 **같은 문장**이어야 사용자가 두 화면을 같은 사건으로 읽는다.
 * 여기서 문자열을 새로 쓰면 사본 drift 가 생긴다.
 */
const IMPORT_DENIED_MESSAGE = importFailureMessage('IMPORT_ACCESS_DENIED')

/**
 * 대상 프로젝트에 이슈 생성(CREATE) 권한이 명시적으로 없을 때 폼 대신 표시하는 안내 카드.
 *
 * 같은 라우트 계열(`projects.$projectKey.settings.workflow-scheme.tsx` 의 `ForbiddenSchemeCard`)의
 * destructive Card 형태를 그대로 따른다. 제목 계층도 그 선례와 같이 `CardTitle`(`<div>`) 이다 —
 * 여기만 `<h2>` 로 올리면 같은 페이지에 함께 쓰는 `ProjectNotFoundCard` 와 계층이 갈린다.
 * 승격은 `CardTitle` 자체를 바꾸는 별건이다.
 */
function ImportCreateDeniedCard(): JSX.Element {
  return (
    <Card
      className="border-destructive/40 bg-destructive/10"
      data-testid="import-create-denied"
    >
      <CardHeader>
        <CardTitle className="text-destructive">{importLabels.createDeniedTitle}</CardTitle>
      </CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        <p>{IMPORT_DENIED_MESSAGE}</p>
      </CardContent>
    </Card>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectImportSettingsPage에 전달한다.
 */
export function ProjectImportSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectImportSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectImportSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 Import(CSV/JSON) 설정 페이지.
 *
 * - 페이지 헤더(h1 "가져오기(Import)") + 모드 토글("바로 가져오기"/"매핑하며 가져오기") + 선택된 모드의 화면을 렌더한다.
 * - "바로 가져오기"(기본)는 ImportForm(파일 선택 / 접수 / 진행률 폴링 / 완료), "매핑하며 가져오기"는
 *   ImportMappingWizard(analyze→필드/사용자/값 매핑→확정→폴링)를 각각 그대로 위임한다(FR-IM-02 D6/D7 Task-7).
 * - 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 * - `projects.$projectKey.reports.cfd.tsx` (CfdReportPage) 어댑터/페이지 분리 패턴 미러 (FR-IM-01 D6/D7 Task-4).
 *
 * @param projectKey 프로젝트 키
 */
export function ProjectImportSettingsPage({
  projectKey,
}: ProjectImportSettingsPageProps): JSX.Element {
  const [mode, setMode] = useState<ImportPageMode>('simple')

  /**
   * 이 프로젝트에 이슈를 만들 권한이 **명시적으로** 없는가.
   *
   * 임포트가 요구하는 권한은 대상 프로젝트의 CREATE 다 — 서버도 접수 즉시 같은 판정을 한다
   * (`ImportJobService.kt:36-38,381-392` fail-fast → 403). 이 게이트는 그 403 의 **사전 신호**이지
   * 유일 방어가 아니다.
   *
   * ★게이트를 **페이지 1곳**에 둔 이유. 두 모드(`ImportForm`/`ImportMappingWizard`)가 아래에서
   *   분기하므로 여기서 막으면 둘을 한 번에 덮는다. 폼 안에 각각 넣으면 같은 판정이 2벌이 된다.
   *
   * ★판정식은 `CREATE === false`(명시 거부만). 근거 정본은
   *   `components/issue/create/use-issue-create-permission-gate.ts` KDoc — 미지(로딩·조회실패)를
   *   거부로 읽으면 CREATE 를 실제로 가진 사용자를 영구 차단한다.
   *
   * ★사이드바 네비 링크(`layout/ProjectTree.tsx:107` `SETTINGS_LINKS`)는 **일부러 안 건드린다.**
   *   링크를 숨기면 「가져오기 메뉴가 왜 없지」가 되고, 들어와서 사유를 읽는 편이 낫다.
   *   그 배열은 정적이라 프로젝트별 권한을 알지도 못한다.
   */
  const isCreateExplicitlyDenied = useIssueCreatePermissionGate(projectKey)

  /**
   * 이 프로젝트가 **아예 없는가**.
   *
   * 권한 API 는 미존재 projectKey 에도 200 + `CREATE:false` 를 준다. 그래서 CREATE 게이트만
   * 두면 `/projects/BOGUS/settings/import` 로 들어온 사용자가 「생성 권한이 없습니다」를 본다 —
   * 프로젝트가 없는데 권한 탓을 하는 **틀린 안내**다. 형제 페이지가 같은 결함을 이미 고쳤다
   * (`projects.$projectKey.settings.workflow-scheme.tsx` KDoc 「배정 조회 404 는 …프로젝트 없음」).
   *
   * ★존재 신호로 `GET /api/v1/projects/{key}` 의 404 를 쓰는 근거. `ProjectQueryService.getOne`
   *   은 **존재 → 권한** 순서라 미존재는 404, BROWSE 없음은 403 이다. 즉 404 가 「없음」 하나를
   *   뜻한다. (이동 preview 의 404 와 대비 — 그쪽은 권한이 먼저라 404 가 prod 에 안 나온다.)
   *
   * ★404 **만** 본다. 500·네트워크 단절은 「없다」가 아니라 「모른다」이므로 막지 않는다 —
   *   CREATE 게이트가 미지를 거부로 읽지 않는 것과 같은 규칙이다.
   */
  const { error: projectError } = useProject(projectKey)
  const isProjectMissing = projectError instanceof ApiError && projectError.status === 404

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">가져오기(Import)</h1>
      </header>
      {isProjectMissing ? (
        // 부재가 권한보다 앞선다 — 없는 프로젝트의 권한을 논하는 것은 뜻이 없다.
        <ProjectNotFoundCard />
      ) : isCreateExplicitlyDenied ? (
        <ImportCreateDeniedCard />
      ) : (
        <>
          <ImportModeToggle mode={mode} onModeChange={setMode} />
          {/* key={projectKey}: projectKey 변경 시 remount 강제 — 아니면 이전 project의
              진행 중 phase/file/jobId/submitError state가 다음 project 화면에 leak된다
              (react-usestate-stale-key-prop). 두 모드 모두 동일 규칙을 적용한다(EC8). */}
          {mode === 'simple' ? (
            <ImportForm key={projectKey} projectKey={projectKey} />
          ) : (
            <ImportMappingWizard key={projectKey} projectKey={projectKey} />
          )}
        </>
      )}
    </div>
  )
}
