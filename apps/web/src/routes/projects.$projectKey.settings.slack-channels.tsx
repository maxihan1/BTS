// 프로젝트 Slack 채널 매핑 설정 라우트 페이지 — RouteAdapter + props 기반 Page (라우터 비의존 단위 테스트 가능) (FR-SL-06 D6 Task 5)
import type { JSX } from 'react'
import { useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { SlackChannelMappingList } from '@/components/settings/SlackChannelMappingList'
import { SlackChannelMappingFormDialog } from '@/components/settings/SlackChannelMappingFormDialog'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'
import type { ChannelMapping } from '@/api/slack'

// ─────────────────────────────────────────────────────────────────────────────
// Router adapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 $projectKey param을 추출하여 ProjectSlackChannelSettingsPage에 전달한다.
 */
export function ProjectSlackChannelSettingsRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  return <ProjectSlackChannelSettingsPage projectKey={projectKey ?? ''} />
}

// ─────────────────────────────────────────────────────────────────────────────
// Page component
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectSlackChannelSettingsPageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  readonly projectKey: string
}

/**
 * 프로젝트 Slack 채널 매핑 설정 페이지.
 *
 * - 헤더 + SlackChannelMappingList + SlackChannelMappingFormDialog 조립
 *   (`settings.automation.tsx` 동형 — WebhookTokenModal은 이 BC에 해당 없어 제외).
 * - projectKey가 없거나 빈 문자열이면 ProjectNotFoundScreen을 렌더한다.
 * - `dialogOpen`/`editingMapping` state를 이 컴포넌트가 보유한다.
 *   SlackChannelMappingList의 onAdd(신규)·onEdit(수정) 콜백이 갱신하고,
 *   SlackChannelMappingFormDialog에 그대로 전달한다.
 * - 목록 조회 403/404 등 에러 상태는 SlackChannelMappingList가 자체적으로 처리한다.
 *
 * 라우터 의존 없이 props로 projectKey를 받아 단위 테스트가 가능하다.
 */
export function ProjectSlackChannelSettingsPage({
  projectKey,
}: ProjectSlackChannelSettingsPageProps): JSX.Element {
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingMapping, setEditingMapping] = useState<ChannelMapping | null>(null)

  if (!projectKey) {
    return <ProjectNotFoundScreen />
  }

  function handleAdd(): void {
    setEditingMapping(null)
    setDialogOpen(true)
  }

  function handleEdit(mapping: ChannelMapping): void {
    setEditingMapping(mapping)
    setDialogOpen(true)
  }

  return (
    <div className="p-8 space-y-6 max-w-2xl">
      <header className="space-y-1">
        <h2 className="text-xl font-semibold">Slack 채널</h2>
        <p className="text-muted-foreground text-sm">
          이 프로젝트의 활동을 브로드캐스트할 Slack 채널을 관리합니다.
        </p>
      </header>

      <SlackChannelMappingList projectKey={projectKey} onAdd={handleAdd} onEdit={handleEdit} />

      <SlackChannelMappingFormDialog
        projectKey={projectKey}
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        editingMapping={editingMapping}
      />
    </div>
  )
}
