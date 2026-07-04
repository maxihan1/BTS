// 프로젝트 Import(CSV/JSON) 설정 라우트 페이지 — RouteAdapter(useParams) + props 기반 Page (라우터 비의존 단위 테스트 가능)
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { ImportForm } from '@/components/import/ImportForm'
import { ImportMappingWizard } from '@/components/import/mapping/ImportMappingWizard'

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

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">가져오기(Import)</h1>
      </header>
      <ImportModeToggle mode={mode} onModeChange={setMode} />
      {/* key={projectKey}: projectKey 변경 시 remount 강제 — 아니면 이전 project의
          진행 중 phase/file/jobId/submitError state가 다음 project 화면에 leak된다
          (react-usestate-stale-key-prop). 두 모드 모두 동일 규칙을 적용한다(EC8). */}
      {mode === 'simple' ? (
        <ImportForm key={projectKey} projectKey={projectKey} />
      ) : (
        <ImportMappingWizard key={projectKey} projectKey={projectKey} />
      )}
    </div>
  )
}
