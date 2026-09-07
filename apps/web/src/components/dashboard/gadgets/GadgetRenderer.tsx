// 가젯 타입별 컴포넌트 분기 렌더러 (FR-DB-02 D6/D7 Task-5)
import type { JSX } from 'react'
import type { DashboardTile } from '@/lib/dashboard-layout'
import type { GadgetConfig } from './gadget-types'
import type { LinkItem } from './LinkListGadget'
import { IssueListGadget } from './IssueListGadget'
import { IssueCountGadget } from './IssueCountGadget'
import { TextWidgetGadget } from './TextWidgetGadget'
import { LinkListGadget } from './LinkListGadget'
import { DistributionChartGadget } from './DistributionChartGadget'
import { SprintBurndownGadget } from './SprintBurndownGadget'
import { ActivityStreamGadget } from './ActivityStreamGadget'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** GadgetRenderer props */
export interface GadgetRendererProps {
  /** 렌더할 대시보드 타일 */
  tile: DashboardTile
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 타입에 따라 적절한 가젯 컴포넌트를 렌더링한다.
 *
 * 분기 규칙.
 * - gadgetType 없음 → null (legacy 타일 — 호출측이 처리)
 * - 지원 타입 → 해당 가젯 컴포넌트
 * - 미지원/unknown 타입 → "지원되지 않는 가젯" 안전 표시 (EC6 — 페이지 무손상)
 *
 * ★아래 `case` 목록은 백엔드 `GadgetType.enabled` 와 **정확히 같아야 한다.**
 * 한쪽만 늘면 사용자가 카탈로그에서 고를 수 있는 가젯이 「지원되지 않는 가젯입니다」로 뜨거나,
 * 반대로 도달 불가 코드가 조용히 남는다. 이 축은
 * `scripts/workflow/gadget-catalog-renderer-parity.test.ts` 가 양방향 차집합으로 지킨다 —
 * `case` 를 지우거나 `enabled` 를 켜기만 하면 그 판별식이 red 를 낸다.
 */
export function GadgetRenderer({ tile }: GadgetRendererProps): JSX.Element | null {
  const { gadgetType, config } = tile

  // legacy 타일 — gadgetType 없으면 null 반환, 호출측(DashboardTile.tsx)이 처리
  if (gadgetType === undefined) {
    return null
  }

  switch (gadgetType) {
    case 'assigned_to_me':
    case 'recently_created':
    case 'filter_result':
      return <IssueListGadget gadgetType={gadgetType} config={toGadgetConfig(config)} />

    case 'issue_count':
      return <IssueCountGadget config={toGadgetConfig(config)} />

    case 'text_widget':
      return <TextWidgetGadget markdown={extractMarkdown(config)} />

    case 'link_list':
      return <LinkListGadget links={extractLinks(config)} />

    // 분포 차트 2종 — 같은 데이터를 다른 마크로 그린다(M-4). 스코프는 프로젝트다(X-JD-1).
    case 'pie_chart':
      return <DistributionChartGadget variant="pie" config={toGadgetConfig(config)} />

    case 'bar_chart':
      return <DistributionChartGadget variant="bar" config={toGadgetConfig(config)} />

    // boardId 를 받아 활성 스프린트를 자동으로 따라간다(M-3).
    case 'sprint_burndown':
      return <SprintBurndownGadget config={toGadgetConfig(config)} />

    // 프로젝트 기준이다 — Jira 의 「your」 기준과 다르다(X-JD-5).
    case 'activity_stream':
      return <ActivityStreamGadget config={toGadgetConfig(config)} />

    default:
      // EC6 — 미지원 가젯 타입: 페이지를 망가뜨리지 않고 안내 메시지만 표시
      return (
        <div className="flex flex-1 items-center justify-center p-4">
          <p className="text-sm text-muted-foreground">지원되지 않는 가젯입니다</p>
        </div>
      )
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 변환 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타일 config(Record<string,unknown>)를 GadgetConfig로 안전하게 변환한다.
 * 각 필드를 타입 가드로 검사해 타입 안전성을 보장한다 (as 캐스팅 없음).
 *
 * @param config 타일 config 객체
 * @returns 타입 안전한 GadgetConfig
 */
function toGadgetConfig(config: Record<string, unknown> | undefined): GadgetConfig {
  if (config === undefined) return {}
  return {
    projectKey: typeof config['projectKey'] === 'string' ? config['projectKey'] : undefined,
    filterId: typeof config['filterId'] === 'string' ? config['filterId'] : undefined,
    maxItems: typeof config['maxItems'] === 'number' ? config['maxItems'] : undefined,
    // ★분포 차트·번다운이 쓰는 두 필드. 여기서 빠뜨리면 config 는 저장돼 있는데 가젯이
    //   못 읽어 「설정이 필요합니다」로만 뜬다 — 사용자에겐 저장이 안 된 것처럼 보인다.
    field: typeof config['field'] === 'string' ? config['field'] : undefined,
    boardId: typeof config['boardId'] === 'string' ? config['boardId'] : undefined,
  }
}

/**
 * config에서 markdown 문자열을 추출한다.
 *
 * @param config 타일 config 객체
 * @returns markdown 문자열 (없거나 string이 아니면 빈 문자열)
 */
function extractMarkdown(config: Record<string, unknown> | undefined): string {
  if (config === undefined) return ''
  return typeof config['markdown'] === 'string' ? config['markdown'] : ''
}

/**
 * config에서 LinkItem 배열을 추출한다.
 * http/https 스킴 필터링은 LinkListGadget이 담당한다.
 *
 * @param config 타일 config 객체
 * @returns LinkItem 배열 (없거나 형식 불일치면 빈 배열)
 */
function extractLinks(config: Record<string, unknown> | undefined): LinkItem[] {
  if (config === undefined) return []
  const rawLinks = config['links']
  if (!Array.isArray(rawLinks)) return []
  return rawLinks.filter((item): item is LinkItem => {
    if (typeof item !== 'object' || item === null) return false
    const obj = item as Record<string, unknown>
    return typeof obj['label'] === 'string' && typeof obj['url'] === 'string'
  })
}
