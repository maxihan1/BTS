// 익명(비로그인) 대시보드 공유 뷰 전용 정적 화이트리스트 가젯 렌더러 — FR-DB-03 D6/D7 Task-7
import type { JSX } from 'react'
import { Lock } from 'lucide-react'
import type { DashboardTile } from '@/lib/dashboard-layout'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import type { LinkItem } from './LinkListGadget'
import { TextWidgetGadget } from './TextWidgetGadget'
import { LinkListGadget } from './LinkListGadget'

// ─────────────────────────────────────────────────────────────────────────────
// 타입 · 상수
// ─────────────────────────────────────────────────────────────────────────────

/** PublicGadgetRenderer props */
export interface PublicGadgetRendererProps {
  /** 렌더할 대시보드 타일 (익명 공개 뷰) */
  tile: DashboardTile
}

/**
 * 익명 뷰에서 렌더를 허용하는 정적 가젯 타입 화이트리스트.
 *
 * fail-closed(EC-2) — 이 목록에 없는 gadgetType(데이터 가젯 4종 + 향후 신규 타입 포함)은
 * 전부 로그인 필요 플레이스홀더로 대체된다. 새 가젯 타입이 추가돼도 여기 명시하지 않으면
 * 자동으로 차단된다.
 */
const PUBLIC_GADGET_WHITELIST = ['text_widget', 'link_list'] as const

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 익명(비로그인) 대시보드 공유 뷰 전용 가젯 렌더러.
 *
 * 화이트리스트 fail-closed(EC-2) — text_widget/link_list 두 정적 가젯만 렌더링한다.
 * 그 외 모든 gadgetType(데이터 가젯 assigned_to_me/recently_created/filter_result/issue_count 및
 * 미지 타입, gadgetType 없는 legacy 타일 포함)은 "로그인이 필요한 가젯입니다" 플레이스홀더로 대체한다.
 *
 * ⚠️ useGadgetData 등 네트워크 훅을 절대 호출하지 않는다 — IssueListGadget/IssueCountGadget을
 * import하지 않는다. 익명 경로는 인증 헤더가 없어 데이터 가젯 API를 호출하면 401/정보 누출
 * 위험이 있다(spec FR-7 — 정적 가젯만 렌더).
 */
export function PublicGadgetRenderer({ tile }: PublicGadgetRendererProps): JSX.Element {
  const { gadgetType, config } = tile

  if (isWhitelistedGadgetType(gadgetType)) {
    if (gadgetType === 'text_widget') {
      return <TextWidgetGadget markdown={extractMarkdown(config)} />
    }
    return <LinkListGadget links={extractLinks(config)} />
  }

  return <LoginRequiredPlaceholder />
}

/**
 * gadgetType이 익명 뷰 화이트리스트에 포함되는지 검사한다.
 *
 * @param gadgetType 타일의 가젯 종류 식별자 (없을 수 있음)
 * @returns 화이트리스트에 정확히 일치하면 true
 */
function isWhitelistedGadgetType(
  gadgetType: string | undefined,
): gadgetType is (typeof PUBLIC_GADGET_WHITELIST)[number] {
  if (gadgetType === undefined) return false
  return PUBLIC_GADGET_WHITELIST.includes(gadgetType as (typeof PUBLIC_GADGET_WHITELIST)[number])
}

/**
 * 로그인 필요 플레이스홀더.
 *
 * "고장이 아니라 의도적 비공개"임을 전달한다 — muted 중앙 정렬 + Lock 아이콘.
 * destructive/에러 스타일은 사용하지 않는다(디자인 리뷰 보강 5).
 */
function LoginRequiredPlaceholder(): JSX.Element {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 p-4 text-center">
      <Lock className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
      <p className="text-sm text-muted-foreground">{dashboardLabels.share.authRequiredGadget}</p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 변환 헬퍼 (GadgetRenderer.tsx 로직 재사용)
// ─────────────────────────────────────────────────────────────────────────────

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
