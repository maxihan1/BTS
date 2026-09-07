// 활동 스트림 가젯 — 프로젝트 변경 이력을 최신순으로 (편차 X-JD-5)
import type { JSX } from 'react'
import { ActivityEntryList } from '@/components/project/summary/ActivityEntryList'
import { EmptyState } from '@/components/ui/empty-state'
import { useProjectActivity } from '@/hooks/use-project-summary'
import { gadgetStateLabels } from '@/i18n/dashboard-labels'
import { clampMaxItems } from './useGadgetData'
import type { GadgetConfig } from './gadget-types'

interface ActivityStreamGadgetProps {
  /** 가젯 config — `projectKey` · `maxItems` */
  readonly config: GadgetConfig
}

/**
 * 프로젝트의 최근 변경 이력을 최신순으로 나열한다.
 *
 * ★**프로젝트 기준**이다(편차 X-JD-5). Jira 의 Activity Stream 은 원문이
 * "a summary of **your** recent activity" 로 **보는 사람** 기준인데, BTS 가 재사용하는
 * `GET /projects/{key}/activity` 는 프로젝트 기준이다. 사용자 기준 스트림 API 가 없고,
 * 만들면 이 PR 의 「백엔드 신규 0」 전제가 깨진다. 다른 것을 같다고 부르지 않는다.
 *
 * 목록 본문은 프로젝트 요약 화면과 `ActivityEntryList` 를 공유한다 — 요약 문장·시각 표기·
 * 링크 동작이 갈리면 같은 활동이 두 화면에서 다르게 읽힌다.
 */
export function ActivityStreamGadget({ config }: ActivityStreamGadgetProps): JSX.Element {
  const projectKey = config.projectKey ?? ''
  // 다른 가젯과 같은 클램프를 쓴다 — 규칙이 갈리면 같은 설정이 가젯마다 다르게 동작한다.
  const limit = clampMaxItems(config.maxItems)
  const { data, isPending, isError } = useProjectActivity(projectKey, limit)

  if (projectKey === '') {
    return <EmptyState title={gadgetStateLabels.notConfigured} />
  }

  if (isPending) {
    return (
      <div className="text-muted-foreground flex flex-1 items-center justify-center p-4 text-sm">
        {gadgetStateLabels.loading}
      </div>
    )
  }

  if (isError || data === undefined) {
    return <EmptyState title={gadgetStateLabels.loadFailed} />
  }

  // 변경 항목이 0개인 그룹은 화면에 쓸 문장이 없다 — 요약 화면과 같은 규칙으로 거른다.
  const visible = data.entries.filter((entry) => entry.items.length > 0)
  if (visible.length === 0) {
    return <EmptyState title={gadgetStateLabels.noActivity} />
  }

  return (
    <div className="flex-1 overflow-auto p-1" data-testid="activity-stream-gadget">
      <ActivityEntryList entries={visible} />
    </div>
  )
}
