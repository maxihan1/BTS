// 이슈 상세 본문의 「하위 작업」 섹션 — 목록 · 진행률 · 연속 입력 행 (JA-D-2 · JA-C-4)
import type { JSX, KeyboardEvent } from 'react'
import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { createIssue } from '@/api/issues'
import { fetchIssueTypes, SUBTASK_HIERARCHY_LEVEL } from '@/api/issue-types'
import { ISSUE_GRAPH_EDGE_TYPES, useIssueGraph } from '@/api/issue-graph'
import type { IssueGraphNode } from '@/api/issue-graph'
import { invalidateIssueViews } from '@/api/issue-view-invalidation'
import { useStatusMetaByKey } from '@/hooks/use-workflows'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { subtaskLabels } from '@/i18n/subtask-labels'

interface IssueSubtasksSectionProps {
  issueKey: string
  projectKey: string
  /** 이 이슈의 유형 — 하위 작업이면 섹션을 그리지 않는다(하위 작업 밑에 하위 작업 없음). */
  typeId: number
  canCreate: boolean
}

/**
 * 하위 작업 목록과 「하위 작업 추가」 인라인 행.
 *
 * 자식 목록 전용 API 가 없어 그래프(depth=1)의 PARENT 엣지(부모 → 자식)로 읽는다.
 * ponytail: 그래프는 노드 100개에서 잘린다 — 링크가 아주 많은 이슈면 자식이 빠질 수 있다. 자식 목록 API 가 생기면 그쪽으로.
 *
 * 입력 행은 Enter 로 만들고 **열린 채** 제목만 비운다(연속 입력). Esc 로 닫는다.
 */
export function IssueSubtasksSection({
  issueKey,
  projectKey,
  typeId,
  canCreate,
}: IssueSubtasksSectionProps): JSX.Element | null {
  const queryClient = useQueryClient()
  const { data: issueTypes = [] } = useQuery({ queryKey: ['issue-types'], queryFn: fetchIssueTypes })
  const { data: graph } = useIssueGraph(issueKey, 1, true)
  const statusMeta = useStatusMetaByKey()
  const [adding, setAdding] = useState(false)
  const [summary, setSummary] = useState('')

  const subtaskType = issueTypes.find((t) => t.hierarchyLevel === SUBTASK_HIERARCHY_LEVEL)
  const create = useMutation({
    mutationFn: (title: string) =>
      createIssue({ projectKey, summary: title, typeId: subtaskType?.id, parentKey: issueKey }),
    onSuccess: () => {
      setSummary('')
      void queryClient.invalidateQueries({ queryKey: ['issue-graph', issueKey] })
      void invalidateIssueViews(queryClient)
    },
    onError: () => toast.error(subtaskLabels.createFailed),
  })

  const isSubtask = issueTypes.find((t) => t.id === typeId)?.hierarchyLevel === SUBTASK_HIERARCHY_LEVEL
  const children = graph === undefined ? [] : childrenOf(issueKey, graph.edges, graph.nodes)
  const canAdd = canCreate && subtaskType !== undefined
  if (isSubtask || (children.length === 0 && !canAdd)) return null

  const doneCount = children.filter((c) => statusMeta.get(c.statusKey)?.category === 'DONE').length

  function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Escape') {
      e.stopPropagation()
      setAdding(false)
      setSummary('')
      return
    }
    if (e.key !== 'Enter' || e.nativeEvent.isComposing) return
    e.preventDefault()
    const title = summary.trim()
    if (title !== '' && !create.isPending) create.mutate(title)
  }

  return (
    <section aria-label={subtaskLabels.sectionTitle} className="mt-6">
      <div className="mb-3 flex items-center gap-3">
        <h2 className="text-sm font-semibold text-foreground">{subtaskLabels.sectionTitle}</h2>
        {children.length > 0 && (
          <>
            <progress
              className="h-1.5 flex-1 overflow-hidden rounded-full [&::-webkit-progress-bar]:bg-muted [&::-webkit-progress-value]:bg-primary"
              max={children.length}
              value={doneCount}
              aria-label={subtaskLabels.progress(doneCount, children.length)}
            />
            <span className="text-xs text-muted-foreground">
              {subtaskLabels.progress(doneCount, children.length)}
            </span>
          </>
        )}
      </div>

      {children.length > 0 && (
        <ul className="divide-y divide-border rounded-md border border-border">
          {children.map((child) => (
            <li key={child.key} className="flex items-center gap-3 px-3 py-2 text-sm">
              <Link to="/issues/$key" params={{ key: child.key }} className="shrink-0 text-primary hover:underline">
                {child.key}
              </Link>
              <span className="min-w-0 flex-1 truncate">{child.summary}</span>
              <span className="shrink-0 text-xs text-muted-foreground">
                {statusMeta.get(child.statusKey)?.name ?? child.statusKey}
              </span>
            </li>
          ))}
        </ul>
      )}

      {canAdd &&
        (adding ? (
          <Input
            autoFocus
            className="mt-2"
            aria-label={subtaskLabels.inputLabel}
            placeholder={subtaskLabels.inputPlaceholder}
            value={summary}
            // disabled 는 포커스를 빼앗아 연속 입력이 끊긴다 — readOnly 로 막는다.
            readOnly={create.isPending}
            onChange={(e) => setSummary(e.target.value)}
            onKeyDown={handleKeyDown}
          />
        ) : (
          <Button variant="ghost" size="sm" className="mt-2" onClick={() => setAdding(true)}>
            {subtaskLabels.addButton}
          </Button>
        ))}
    </section>
  )
}

/** PARENT 엣지(부모 → 자식) 중 이 이슈가 부모인 것의 자식 노드. */
function childrenOf(
  issueKey: string,
  edges: readonly { from: string; to: string; type: string }[],
  nodes: readonly IssueGraphNode[],
): IssueGraphNode[] {
  const childKeys = new Set(
    edges.filter((e) => e.type === ISSUE_GRAPH_EDGE_TYPES.PARENT && e.from === issueKey).map((e) => e.to),
  )
  return nodes.filter((n) => childKeys.has(n.key))
}
