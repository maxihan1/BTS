// 이슈 변경 이력 타임라인 컴포넌트 — 그룹별 from→to 렌더 + 더 보기 누적 페이징 (FR-HS-02)
import type { JSX } from 'react'
import { useState } from 'react'
import { useIssueChangelog } from '@/hooks/use-issue-changelog'
import {
  resolveFieldLabel,
  resolveValueLabel,
} from '@/lib/changelog-labels'
import type { ChangelogRefs, ChangeItem as LabelsChangeItem } from '@/lib/changelog-labels'
import type { ChangeGroup, ChangeItem } from '@/api/changelog'
import { formatDateTime } from '@/lib/datetime'
import { Button } from '@/components/ui/button'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueChangelog 컴포넌트 props */
export interface IssueChangelogProps {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  readonly issueKey: string
  /**
   * 값 표시명 해석에 사용할 참조 데이터.
   * F5가 페이지 로드 시 수집해 주입한다.
   */
  readonly refs: ChangelogRefs
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * API ChangeItem(Zod nullish — undefined 가능)을 라벨 유틸이 기대하는
 * LabelsChangeItem(null만 허용) 형태로 정규화한다.
 * undefined → null 변환만 수행 (값은 그대로).
 */
function normalizeItem(item: ChangeItem): LabelsChangeItem {
  return {
    field: item.field,
    fromValue: item.fromValue ?? null,
    toValue: item.toValue ?? null,
    fromLabel: item.fromLabel ?? null,
    toLabel: item.toLabel ?? null,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// ChangeItemRow — 단건 변경 항목 행
// ─────────────────────────────────────────────────────────────────────────────

interface ChangeItemRowProps {
  readonly item: ChangeItem
  readonly refs: ChangelogRefs
}

/**
 * 단건 변경 항목 렌더.
 * lifecycle 필드는 "이슈를 생성/삭제했습니다"로 특수 렌더하고,
 * 나머지는 "필드명: from → to" 형태로 표시한다.
 */
function ChangeItemRow({ item, refs }: ChangeItemRowProps): JSX.Element {
  const normalized = normalizeItem(item)
  const fieldLabel = resolveFieldLabel(normalized.field, refs)

  // lifecycle 특수 렌더 — from→to 형식 아님
  if (normalized.field === 'lifecycle') {
    const lifecycleText = resolveValueLabel(normalized, 'to', refs)
    return (
      <li className="text-sm text-foreground py-0.5">
        <span className="font-medium">{fieldLabel}</span>
        {': '}
        <span>{lifecycleText}</span>
      </li>
    )
  }

  const fromText = resolveValueLabel(normalized, 'from', refs)
  const toText = resolveValueLabel(normalized, 'to', refs)

  return (
    <li className="text-sm text-foreground py-0.5">
      <span className="font-medium">{fieldLabel}</span>
      {': '}
      <span className="text-muted-foreground">{fromText}</span>
      <span className="mx-1 text-muted-foreground" aria-hidden="true">→</span>
      <span>{toText}</span>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ChangeGroupRow — 변경 그룹 행 (헤더 + 항목 목록)
// ─────────────────────────────────────────────────────────────────────────────

interface ChangeGroupRowProps {
  readonly group: ChangeGroup
  readonly refs: ChangelogRefs
}

/**
 * 단건 변경 그룹 렌더.
 * "actorName · 상대시각" 헤더 + 그룹 내 변경 항목 목록을 표시한다.
 * actorName=null이면 "시스템"을 표시한다.
 */
function ChangeGroupRow({ group, refs }: ChangeGroupRowProps): JSX.Element {
  const actorDisplay = group.actorName ?? issueDetailStrings.changelogSystemActor
  const timeDisplay = formatDateTime(group.createdAt)
  const groupLabel = `${actorDisplay} · ${timeDisplay}`

  return (
    <div
      role="group"
      aria-label={groupLabel}
      className="border-l-2 border-border pl-4 py-2"
    >
      <p className="text-xs text-muted-foreground mb-1 font-medium">
        {actorDisplay}
        <span className="mx-1" aria-hidden="true">·</span>
        <time dateTime={group.createdAt}>{timeDisplay}</time>
      </p>
      <ul className="space-y-0.5">
        {group.items.map((item, idx) => (
          <ChangeItemRow key={`${item.field}-${idx}`} item={item} refs={refs} />
        ))}
      </ul>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ChangelogSkeleton — 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 변경 이력 로딩 중 스켈레톤 UI.
 */
function ChangelogSkeleton(): JSX.Element {
  return (
    <div
      role="status"
      aria-label={issueDetailStrings.changelogLoading}
      className="space-y-4"
    >
      {[1, 2, 3].map((i) => (
        <div key={i} className="border-l-2 border-border pl-4 py-2 space-y-2">
          <div className="h-3 w-32 rounded bg-muted animate-pulse" />
          <div className="h-3 w-48 rounded bg-muted animate-pulse" />
        </div>
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ChangelogContent — 페이지 데이터를 받아 목록+더보기 렌더
// ─────────────────────────────────────────────────────────────────────────────

interface ChangelogContentProps {
  readonly issueKey: string
  readonly refs: ChangelogRefs
}

/**
 * 변경 이력 목록 + "더 보기" 누적 렌더.
 * page 0부터 시작해 "더 보기" 클릭마다 다음 페이지를 추가 fetch하고 누적 표시한다.
 */
function ChangelogContent({ issueKey, refs }: ChangelogContentProps): JSX.Element {
  // 현재까지 로드한 최대 페이지 번호 (0-base)
  const [maxPage, setMaxPage] = useState(0)

  // 페이지별 결과를 개별 훅으로 조회한다. "더 보기" 시 maxPage를 증가시켜 새 훅을 마운트한다.
  const pages = Array.from({ length: maxPage + 1 }, (_, i) => i)

  return (
    <ChangelogPages
      issueKey={issueKey}
      refs={refs}
      pages={pages}
      onLoadMore={() => setMaxPage((prev) => prev + 1)}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ChangelogPages — 여러 페이지를 순서대로 렌더
// ─────────────────────────────────────────────────────────────────────────────

interface ChangelogPagesProps {
  readonly issueKey: string
  readonly refs: ChangelogRefs
  readonly pages: number[]
  readonly onLoadMore: () => void
}

/**
 * pages 배열에 해당하는 각 페이지 데이터를 useIssueChangelog로 fetch하고 순서대로 누적 렌더한다.
 * 마지막 페이지가 `last=true`이면 "더 보기" 버튼을 숨긴다.
 */
function ChangelogPages({
  issueKey,
  refs,
  pages,
  onLoadMore,
}: ChangelogPagesProps): JSX.Element {
  return (
    <>
      {pages.map((page) => (
        <ChangelogPageSection
          key={page}
          issueKey={issueKey}
          refs={refs}
          page={page}
          isLastRequested={page === pages[pages.length - 1]}
          onLoadMore={onLoadMore}
        />
      ))}
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ChangelogPageSection — 단일 페이지 fetch + 렌더
// ─────────────────────────────────────────────────────────────────────────────

interface ChangelogPageSectionProps {
  readonly issueKey: string
  readonly refs: ChangelogRefs
  readonly page: number
  /** 현재 컴포넌트가 마지막으로 요청된 페이지인지 여부 — "더 보기" 버튼 표시 결정에 사용 */
  readonly isLastRequested: boolean
  readonly onLoadMore: () => void
}

/**
 * 단일 페이지를 fetch하고 그룹 목록을 렌더한다.
 * isLastRequested=true이고 last=false이면 "더 보기" 버튼을 표시한다.
 * 첫 페이지에서 에러/빈 상태를 처리한다 (이후 페이지는 부분 에러이므로 조용히 처리).
 */
function ChangelogPageSection({
  issueKey,
  refs,
  page,
  isLastRequested,
  onLoadMore,
}: ChangelogPageSectionProps): JSX.Element {
  const { data, isLoading, isError } = useIssueChangelog(issueKey, page)

  if (isLoading) {
    // 첫 페이지만 스켈레톤 표시, 이후 페이지는 로딩 중 표시 최소화
    return page === 0 ? <ChangelogSkeleton /> : (
      <div role="status" aria-label={issueDetailStrings.changelogLoading} className="py-2">
        <div className="h-3 w-32 rounded bg-muted animate-pulse" />
      </div>
    )
  }

  if (isError || data === undefined) {
    if (page === 0) {
      return (
        <div role="alert" className="text-sm text-destructive py-2">
          {issueDetailStrings.changelogError}
        </div>
      )
    }
    return <></>
  }

  if (page === 0 && data.empty) {
    return (
      <p className="text-sm text-muted-foreground py-2">
        {issueDetailStrings.changelogEmpty}
      </p>
    )
  }

  return (
    <>
      <div className="space-y-4">
        {data.content.map((group, idx) => (
          <ChangeGroupRow
            key={`${page}-${idx}`}
            group={group}
            refs={refs}
          />
        ))}
      </div>
      {isLastRequested && !data.last && (
        <div className="pt-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={onLoadMore}
          >
            {issueDetailStrings.changelogLoadMore}
          </Button>
        </div>
      )}
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueChangelog — 공개 컴포넌트 (섹션 래퍼 + 접기/펼치기)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 변경 이력 타임라인 섹션 컴포넌트.
 *
 * - 이슈 상세 페이지 하단 전체폭 "변경 이력" 섹션에 마운트된다.
 * - useIssueChangelog 훅으로 데이터를 조회하고, ChangeGroupRow로 타임라인을 렌더한다.
 * - "더 보기" 버튼으로 다음 페이지를 누적 로드한다.
 * - actorName=null이면 "시스템", lifecycle 항목은 특수 렌더.
 *
 * @param issueKey - 이슈 식별 키
 * @param refs - 값 표시명 해석에 사용할 참조 데이터
 */
export function IssueChangelog({ issueKey, refs }: IssueChangelogProps): JSX.Element {
  const [isOpen, setIsOpen] = useState(true)

  return (
    <section
      aria-label={issueDetailStrings.changelogSectionTitle}
      role="region"
      className="w-full"
    >
      <button
        type="button"
        className="flex items-center gap-2 w-full text-left mb-3"
        aria-expanded={isOpen}
        onClick={() => setIsOpen((prev) => !prev)}
      >
        <h2 className="text-base font-semibold">
          {issueDetailStrings.changelogSectionTitle}
        </h2>
        <span className="text-muted-foreground text-xs" aria-hidden="true">
          {isOpen ? '▲' : '▼'}
        </span>
      </button>

      {isOpen && (
        <div className="space-y-2">
          <ChangelogContent issueKey={issueKey} refs={refs} />
        </div>
      )}
    </section>
  )
}
