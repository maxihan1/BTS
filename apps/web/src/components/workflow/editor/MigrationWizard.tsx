// 발행으로 사라지는 상태에 남은 이슈를 어디로 옮길지 고르는 마법사 — 발행 다이얼로그의 한 단계
// 순수 표현 컴포넌트다. 폴링·API 호출을 하지 않고 필요한 값을 전부 props 로 받는다(배선은 T5·T6 소관).
import type { JSX } from 'react'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import { bulkOperationLabels, failureReasonLabels, getFailureReasonLabel, statusLabels } from '@/i18n/bulk-operation-labels'
import type { BulkOperationResponse } from '@/api/bulk-operations'
import type { DraftDefinition, StatusMappingInput } from '@/api/workflows-draft.types'
import type { EditableDraft } from '@/lib/workflow-draft'
import { migrationTargets, removedStatusKeys, stateNameMap } from '@/lib/workflow-draft'
import { canStartMigration, toMigrationRequest, type MigrationSelection } from '@/lib/workflow-migration'

/** MigrationWizard Props */
export interface MigrationWizardProps {
  /** 지금 편집 중인 초안. 사라지는 상태·도착지 후보 산출에 쓰인다 */
  readonly draft: EditableDraft
  /** 지금 운영 중인 발행본. 초안과 비교해 사라지는 상태를 가른다 */
  readonly published: DraftDefinition
  /** 상태별 잔여 이슈 건수 — `POST /publish/preview` 응답(J3) */
  readonly pendingIssueCounts: Record<string, number>
  /** 도착지 선택 — 사라지는 상태 키 → 옮겨 갈 상태 키. 부모가 소유하는 controlled 값 */
  readonly selection: MigrationSelection
  /** 셀렉트 값이 바뀔 때 호출된다 */
  readonly onSelectionChange: (removedKey: string, targetKey: string) => void
  /** 이관 시작 버튼 클릭 — 서버 계약 형태로 변환된 매핑을 함께 넘긴다(F6) */
  readonly onStartMigration: (mappings: StatusMappingInput[]) => void
  /** 이관 시작 요청 전송 중 — 중복 클릭 방지 */
  readonly starting: boolean
  /** 접수된 이관 작업의 폴링 결과. null 이면 아직 시작 전(선택 단계) */
  readonly operation: BulkOperationResponse | null
  /**
   * 진행률 0~1. 아직 셀 수 없으면(총 건수 0) null.
   *
   * ★ **여기서 다시 계산하지 않는다.** 폴링 훅(`useBulkOperationPolling.progressRatio`)이 이미
   * 같은 두 숫자로 뽑아 둔 값을 받는다 — 같은 계산을 화면마다 복제하면 0 나눗셈·반올림 처리가
   * 서로 어긋난다.
   */
  readonly progressRatio: number | null
}

/** 0~1 진행률을 정수 퍼센트로. 셀 수 없으면(null) 0 으로 그린다. */
function toPercent(progressRatio: number | null): number {
  return progressRatio === null ? 0 : Math.round(progressRatio * 100)
}

/** 의존성 없는 자체 진행률 바(D4) — `role="progressbar"` + `aria-valuenow` 로 ADS A1 준용. */
function ProgressBar({
  operation,
  progressRatio,
}: {
  readonly operation: BulkOperationResponse
  readonly progressRatio: number | null
}): JSX.Element {
  const { status, processedCount, totalCount } = operation
  const percent = toPercent(progressRatio)
  const fillColor: 'bg-danger' | 'bg-success' | 'bg-info' =
    status === 'FAILED' ? 'bg-danger' : status === 'COMPLETED' ? 'bg-success' : 'bg-info'
  const badgeVariant: 'red' | 'green' | 'blue' = status === 'FAILED' ? 'red' : status === 'COMPLETED' ? 'green' : 'blue'

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <Badge variant={badgeVariant}>{statusLabels[status]}</Badge>
        <span>
          {processedCount} / {totalCount} ({percent}%)
        </span>
      </div>
      <div
        role="progressbar"
        aria-label={labels.migration.progressLabel}
        aria-valuenow={processedCount}
        aria-valuemin={0}
        aria-valuemax={totalCount}
        className="h-2 w-full overflow-hidden rounded-full bg-(--bg-neutral)"
      >
        <div className={`h-full rounded-full transition-all ${fillColor}`} style={{ width: `${String(percent)}%` }} />
      </div>
      {status === 'COMPLETED' ? <p className="text-success-text text-sm">{labels.migration.completed}</p> : null}
    </div>
  )
}

/** 이슈 키 + 실패 사유 목록. 자체 스크롤 영역 — 다이얼로그 전체가 늘어나 버튼이 밀리지 않게 한다. */
function ItemList({
  heading,
  items,
  tone,
}: {
  readonly heading: string
  readonly items: BulkOperationResponse['items']
  readonly tone: 'danger' | 'muted'
}): JSX.Element {
  return (
    <section aria-label={heading} className="flex flex-col gap-2">
      <h3 className="text-sm font-medium">{heading}</h3>
      <ul className="flex max-h-40 flex-col gap-1 overflow-y-auto text-sm">
        {items.map((item) => (
          <li
            key={item.issueKey}
            className={`flex items-center justify-between gap-4 rounded-md px-2 py-1 ${tone === 'danger' ? 'bg-danger/10' : 'bg-(--bg-neutral)'}`}
          >
            <span className="font-mono">{item.issueKey}</span>
            <span className={`text-xs ${tone === 'danger' ? 'text-danger-text' : 'text-muted-foreground'}`}>
              {getFailureReasonLabel(item.failureReasonCode ?? 'UNKNOWN')}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * 상태 이관 마법사. 발행 다이얼로그의 한 단계로 들어간다(별도 `Dialog` 를 만들지 않는다).
 * `operation` 이 null 이면 도착지 선택 단계, 값이 있으면 진행률·결과 단계를 그린다.
 *
 * `PROJECT_ARCHIVED`(E10)는 실패가 아니다 — 아카이브 프로젝트 이슈는 이관 범위에 의도적으로
 * 포함되고 옮겨지지 않는다. 실패 목록과 섞어 「오류」로 보이게 하면 오해라 별도 목록으로 분리한다.
 */
export function MigrationWizard({
  draft,
  published,
  pendingIssueCounts,
  selection,
  onSelectionChange,
  onStartMigration,
  starting,
  operation,
  progressRatio,
}: MigrationWizardProps): JSX.Element {
  const removed = removedStatusKeys(draft, published)
  const targets = migrationTargets(draft, published)
  const nameMap = { ...stateNameMap(published), ...stateNameMap(draft) }
  const nameOf = (key: string): string => nameMap[key] ?? key
  const canStart = canStartMigration(selection, draft, published)

  const failedItems = operation?.items.filter((item) => item.status === 'FAILED' && item.failureReasonCode !== 'PROJECT_ARCHIVED') ?? []
  const excludedItems = operation?.items.filter((item) => item.failureReasonCode === 'PROJECT_ARCHIVED') ?? []

  return (
    <div className="flex flex-col gap-4">
      {/* J8 — 규칙 미발화 고지, 상시 표시 */}
      <p role="status" className="text-sm text-muted-foreground">{labels.migration.rulesNotTriggeredNotice}</p>
      {/* X4 — 보드 컬럼 고지, 사라지는 상태가 있으면 무조건 */}
      {removed.length > 0 ? (
        <p role="status" className="text-sm text-muted-foreground">{labels.migration.boardColumnsNotice}</p>
      ) : null}

      {operation === null ? (
        <>
          <section aria-label={labels.migration.intro} className="flex flex-col gap-3">
            <p className="text-sm text-muted-foreground">{labels.migration.intro}</p>
            <ul className="flex flex-col gap-3">
              {removed.map((key) => {
                const name = nameOf(key)
                const count = pendingIssueCounts[key] ?? 0
                return (
                  <li key={key} className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                    <div className="flex items-center gap-2 text-sm">
                      {/* 값이 채워져도 사라지지 않는 라벨 — placeholder 를 유일한 라벨로 쓰지 않는다 */}
                      <span className="font-medium">{name}</span>
                      <span className="text-muted-foreground">
                        {count > 0 ? `${String(count)}${labels.publish.issueCountSuffix}` : labels.publish.noIssues}
                      </span>
                    </div>
                    <Select value={selection[key] ?? ''} onValueChange={(next) => onSelectionChange(key, next)}>
                      <SelectTrigger aria-label={name} className="min-h-11 w-full sm:w-48">
                        <SelectValue placeholder={bulkOperationLabels.statusSelectPlaceholder} />
                      </SelectTrigger>
                      <SelectContent>
                        {targets.map((state) => (
                          <SelectItem key={state.key} value={state.key}>
                            {state.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </li>
                )
              })}
            </ul>
          </section>
          <div className="flex justify-end">
            <Button disabled={!canStart || starting} onClick={() => onStartMigration(toMigrationRequest(selection))}>
              {labels.migration.start}
            </Button>
          </div>
        </>
      ) : (
        <div className="flex flex-col gap-4">
          <ProgressBar operation={operation} progressRatio={progressRatio} />
          {failedItems.length > 0 ? <ItemList heading={labels.migration.failuresHeading} items={failedItems} tone="danger" /> : null}
          {excludedItems.length > 0 ? (
            <ItemList heading={failureReasonLabels.PROJECT_ARCHIVED} items={excludedItems} tone="muted" />
          ) : null}
        </div>
      )}
    </div>
  )
}
