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
import { useDateFormat } from '@/hooks/use-date-format'
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
  /** 변경 항목 데이터 */
  readonly item: ChangeItem
  /** 값 표시명 해석용 참조 데이터 */
  readonly refs: ChangelogRefs
}

/**
 * 단건 변경 항목 렌더.
 * lifecycle 필드는 "이슈를 생성/삭제했습니다"로 특수 렌더하고,
 * 나머지는 "필드명: from → to" 형태로 표시한다.
 */
export function ChangeItemRow({ item, refs }: ChangeItemRowProps): JSX.Element {
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
  /** 변경 그룹 데이터 */
  readonly group: ChangeGroup
  /** 값 표시명 해석용 참조 데이터 */
  readonly refs: ChangelogRefs
}

/**
 * 단건 변경 그룹 렌더.
 * "actorName · 상대시각" 헤더 + 그룹 내 변경 항목 목록을 표시한다.
 * actorName=null이면 "시스템"을 표시한다.
 * createdAt이 파싱 불가한 값이면 원문을 그대로 표시한다 (Invalid Date 방어).
 */
export function ChangeGroupRow({ group, refs }: ChangeGroupRowProps): JSX.Element {
  const { formatDateTime } = useDateFormat()
  const actorDisplay = group.actorName ?? issueDetailStrings.changelogSystemActor
  const timeDisplay = isNaN(new Date(group.createdAt).getTime())
    ? group.createdAt
    : formatDateTime(group.createdAt)
  const groupLabel = `${actorDisplay} · ${timeDisplay}`

  return (
    <div
      role="group"
      aria-label={groupLabel}
      className="border-l-2 border-border pl-4 py-2"
    >
      <p className="text-xs text-muted-foreground mb-1 font-medium">
        <span>{actorDisplay}</span>
        <span className="mx-1" aria-hidden="true">·</span>
        <time dateTime={group.createdAt}>{timeDisplay}</time>
      </p>
      <ul
        aria-label={`${actorDisplay}의 변경 항목`}
        className="space-y-0.5"
      >
        {group.items.map((item, idx) => (
          <ChangeItemRow
            key={`${item.field}-${idx}`}
            item={item}
            refs={refs}
          />
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
 * role=status으로 스크린 리더에 로딩 상태를 알린다.
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
// ChangelogPage — 단일 페이지 fetch + 렌더
// ─────────────────────────────────────────────────────────────────────────────

interface ChangelogPageProps {
  /** 이슈 식별 키 */
  readonly issueKey: string
  /** 값 표시명 해석용 참조 데이터 */
  readonly refs: ChangelogRefs
  /** 0-base 페이지 번호 */
  readonly page: number
  /**
   * 현재 컴포넌트가 마지막으로 요청된 페이지인지 여부.
   * true + data.last=false 조합에서 "더 보기" 버튼을 표시한다.
   */
  readonly isLastRequested: boolean
  /** "더 보기" 클릭 핸들러 */
  readonly onLoadMore: () => void
}

/**
 * 단일 페이지 변경 이력을 fetch하고 ChangeGroupRow 목록을 렌더한다.
 *
 * - 첫 페이지(page=0)에서 로딩/에러/빈 상태를 처리한다.
 * - isLastRequested=true이고 data.last=false이면 "더 보기" 버튼을 표시한다.
 * - 비-0 페이지 에러 시 "더 보기" 버튼을 유지해 사용자가 재시도할 수 있도록 한다.
 */
function ChangelogPage({
  issueKey,
  refs,
  page,
  isLastRequested,
  onLoadMore,
}: ChangelogPageProps): JSX.Element {
  const { data, isLoading, isError, refetch } = useIssueChangelog(issueKey, page)

  if (isLoading) {
    return page === 0
      ? <ChangelogSkeleton />
      : (
        <div
          role="status"
          aria-label={issueDetailStrings.changelogLoading}
          className="py-2"
        >
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
    // 비-0 페이지 에러: 재시도 가능하도록 "더 보기" 버튼을 유지한다.
    // 클릭 시 refetch()로 같은 페이지를 재요청한다.
    return (
      <div className="pt-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => { void refetch() }}
        >
          {issueDetailStrings.changelogLoadMore}
        </Button>
      </div>
    )
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
// IssueChangelog — 공개 컴포넌트
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
  // 현재까지 로드한 최대 페이지 번호 (0-base). "더 보기" 클릭마다 1씩 증가.
  const [maxPage, setMaxPage] = useState(0)
  const pages = Array.from({ length: maxPage + 1 }, (_, i) => i)

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
        aria-controls="changelog-content"
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
        <div id="changelog-content" className="space-y-2">
          {pages.map((page) => (
            <ChangelogPage
              key={page}
              issueKey={issueKey}
              refs={refs}
              page={page}
              isLastRequested={page === pages[pages.length - 1]}
              onLoadMore={() => setMaxPage((prev) => prev + 1)}
            />
          ))}
        </div>
      )}
    </section>
  )
}
