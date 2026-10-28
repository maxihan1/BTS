// 스킴 게시 · 교체 시 업무 유형별 상태 이관 마법사 — 1/2 매핑 · 2/2 진행률 (R11 · 갭 JCW-1)
import type { JSX } from 'react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { useStatusMetaByKey, useWorkflows } from '@/hooks/use-workflows'
import { useBulkOperationPolling } from '@/hooks/use-bulk-operation'
import type { StatusMappingInput, StrandedIssueType } from '@/api/workflow-schemes'
import { statusLabels } from '@/i18n/bulk-operation-labels'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'

const labels = workflowSchemeLabels.migration

export interface SchemeMigrationWizardProps {
  /** 1/2 단계 입력 — 서버 409 `strandedByType`. 비어 있고 [bulkOperationIds] 가 있으면 2/2 단계 */
  stranded: StrandedIssueType[]
  /** 2/2 단계 — 큐잉된 이관 작업 id. null 이면 1/2 단계 */
  bulkOperationIds: string[] | null
  isSubmitting: boolean
  onSubmit: (statusMappings: StatusMappingInput[]) => void
  onClose: () => void
}

const slot = (typeId: number, from: string) => `${String(typeId)}:${from}`

/**
 * 스킴 변경 이관 마법사.
 *
 * 1/2 — 서버가 돌려준 (유형, 남는 상태)마다 새 워크플로우의 상태를 고른다. 전부 골라야 「다음」.
 * 2/2 — 유형마다 이관 작업 1건이라 작업마다 진행률 한 줄을 그린다.
 */
export function SchemeMigrationWizard({
  stranded,
  bulkOperationIds,
  isSubmitting,
  onSubmit,
  onClose,
}: SchemeMigrationWizardProps): JSX.Element {
  const [picked, setPicked] = useState<Record<string, string>>({})
  const { data: issueTypes = [] } = useIssueTypes()
  const { data: workflows = [] } = useWorkflows()
  const statusMeta = useStatusMetaByKey()

  const typeName = (id: number) => issueTypes.find((t) => t.id === id)?.name ?? String(id)
  const statusName = (key: string) => statusMeta.get(key)?.name ?? key

  const rows = stranded.flatMap((t) => Object.keys(t.statuses).map((from) => ({ t, from })))
  const complete = rows.every(({ t, from }) => (picked[slot(t.issueTypeId, from)] ?? '') !== '')

  const submit = () => {
    onSubmit(
      rows.map(({ t, from }) => ({
        issueTypeId: t.issueTypeId,
        fromStatusKey: from,
        toStatusKey: picked[slot(t.issueTypeId, from)] ?? '',
      })),
    )
  }

  const inProgress = bulkOperationIds !== null

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{inProgress ? labels.step2Title : labels.step1Title}</DialogTitle>
          <DialogDescription>{inProgress ? labels.step2Description : labels.step1Description}</DialogDescription>
        </DialogHeader>

        {inProgress ? (
          <ul className="flex flex-col gap-2 text-sm">
            {bulkOperationIds.map((id) => <MigrationProgressRow key={id} id={id} />)}
          </ul>
        ) : (
          <div className="flex max-h-96 flex-col gap-4 overflow-auto">
            {stranded.map((t) => {
              const targets = workflows.find((w) => w.key === t.workflowKey)?.states ?? []
              return (
                <section key={t.issueTypeId} className="flex flex-col gap-2">
                  <h3 className="text-sm font-semibold">{labels.typeHeading(typeName(t.issueTypeId), t.workflowKey)}</h3>
                  {Object.entries(t.statuses).map(([from, count]) => (
                    <label key={from} className="flex items-center gap-3 text-sm">
                      <span className="w-40 truncate">{statusName(from)}</span>
                      <span className="w-12 text-muted-foreground">{labels.issueCount(count)}</span>
                      <select
                        value={picked[slot(t.issueTypeId, from)] ?? ''}
                        onChange={(e) => setPicked((p) => ({ ...p, [slot(t.issueTypeId, from)]: e.target.value }))}
                        aria-label={labels.targetAriaLabel(typeName(t.issueTypeId), statusName(from))}
                        className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-sm"
                      >
                        <option value="">{labels.targetPlaceholder}</option>
                        {targets.map((s) => (
                          <option key={s.key} value={s.key}>{s.name}</option>
                        ))}
                      </select>
                    </label>
                  ))}
                </section>
              )
            })}
          </div>
        )}

        <DialogFooter>
          {inProgress ? (
            <Button onClick={onClose}>{labels.close}</Button>
          ) : (
            <>
              <Button variant="ghost" onClick={onClose}>{labels.cancel}</Button>
              <Button onClick={submit} disabled={!complete || isSubmitting}>{labels.next}</Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** 이관 작업 하나의 진행률 — 종료되면 폴링 훅이 이슈 화면을 무효화한다 */
function MigrationProgressRow({ id }: { id: string }): JSX.Element {
  const { data } = useBulkOperationPolling(id, true)
  return (
    <li className="flex items-center justify-between rounded-md border border-border px-3 py-2">
      <span>{data === undefined ? '…' : statusLabels[data.status]}</span>
      <span className="text-muted-foreground">
        {data === undefined ? '' : labels.progress(data.processedCount, data.totalCount)}
      </span>
    </li>
  )
}
