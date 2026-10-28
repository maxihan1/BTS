// 이슈 상세 헤더 ⚡ 「자동화」 메뉴 — 이 프로젝트의 수동(MANUAL) 규칙을 골라 이 이슈로 실행 (R9 · JAU-5)
import type { JSX } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Zap } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { extractAutomationRuleErrorCode, fetchManualRules, runManualRule } from '@/api/automation-rules'
import type { ManualRule } from '@/api/automation-rules'

// automation BC 관례 — 문구는 파일 안 고정 한국어(AutomationRuleFormDialog 선례).
const labels = {
  trigger: '자동화',
  heading: '규칙 실행',
  loading: '불러오는 중…',
  empty: '실행할 수 있는 수동 규칙이 없습니다.',
  loadFailed: '규칙 목록을 불러오지 못했습니다.',
  ran: (name: string) => `「${name}」 규칙을 실행했습니다. 결과는 실행 이력에서 볼 수 있습니다.`,
  notFound: '규칙이나 이슈를 찾을 수 없습니다. 목록을 다시 열어 주세요.',
  runFailed: '규칙을 실행하지 못했습니다. 다시 시도해 주세요.',
} as const

interface IssueAutomationMenuProps {
  readonly projectKey: string
  readonly issueKey: string
}

/**
 * Jira 이슈 화면의 ⚡ 자동화 버튼 자리. 목록은 메뉴를 열 때만 불러온다(Radix 가 닫힌 Content 를 마운트하지 않음).
 * 실행은 비동기(서버 202) — 이 메뉴는 「넣었다」만 알리고 결과는 규칙의 실행 이력에 남는다.
 */
export function IssueAutomationMenu({ projectKey, issueKey }: IssueAutomationMenuProps): JSX.Element {
  // 메뉴 항목을 고르면 Content 가 닫혀 언마운트되므로 mutation 은 늘 마운트된 여기에 둔다.
  const run = useMutation({
    mutationFn: (rule: ManualRule) => runManualRule(projectKey, rule.id, issueKey),
    onSuccess: (_, rule) => toast.success(labels.ran(rule.name)),
    onError: (error) => {
      const notFound = extractAutomationRuleErrorCode(error) === 'AUTOMATION_MANUAL_RUN_NOT_FOUND'
      toast.error(notFound ? labels.notFound : labels.runFailed)
    },
  })

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="ghost" size="sm" aria-label={labels.trigger}>
          <Zap className="size-4 mr-1" aria-hidden="true" />
          {labels.trigger}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel>{labels.heading}</DropdownMenuLabel>
        <ManualRuleItems projectKey={projectKey} onRun={run.mutate} />
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

interface ManualRuleItemsProps {
  readonly projectKey: string
  readonly onRun: (rule: ManualRule) => void
}

function ManualRuleItems({ projectKey, onRun }: ManualRuleItemsProps): JSX.Element {
  const rulesQuery = useQuery({
    queryKey: ['automation-manual-rules', projectKey],
    queryFn: () => fetchManualRules(projectKey),
  })

  if (rulesQuery.isPending) return <DropdownMenuItem disabled>{labels.loading}</DropdownMenuItem>
  if (rulesQuery.isError) return <DropdownMenuItem disabled>{labels.loadFailed}</DropdownMenuItem>
  if (rulesQuery.data.length === 0) return <DropdownMenuItem disabled>{labels.empty}</DropdownMenuItem>
  return (
    <>
      {rulesQuery.data.map((rule) => (
        <DropdownMenuItem key={rule.id} onSelect={() => onRun(rule)}>
          {rule.name}
        </DropdownMenuItem>
      ))}
    </>
  )
}
