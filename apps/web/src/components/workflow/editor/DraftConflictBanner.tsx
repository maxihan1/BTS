// 발행 충돌 배너 — 재시도가 무의미하다는 사실과 유일한 출구를 함께 준다
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'

interface DraftConflictBannerProps {
  /** 초안을 폐기하고 최신 정의로 다시 시작한다 */
  onDiscard: () => void
  discarding: boolean
}

/**
 * 낙관적 락 충돌을 알린다.
 *
 * ### 토스트가 아니라 배너인 이유
 * 이 상태는 **사라지지 않는다.** 서버 앵커는 write-once 라(`WorkflowDraftRepository.upsert` 의
 * `DO UPDATE` 가 그 컬럼을 뺀다) 다시 불러와도 저장된 초안의 앵커는 그대로이고, 발행할 때마다
 * 같은 409 가 반복된다. 사라지는 토스트로 알리면 관리자는 무한히 재시도하게 된다.
 *
 * 그래서 「다시 시도」를 권하지 않고 **유일한 출구인 폐기**만 준다.
 */
function DraftConflictBanner({ onDiscard, discarding }: DraftConflictBannerProps): React.JSX.Element {
  return (
    <div
      role="alert"
      aria-label={labels.conflict.banner}
      className="border-destructive/50 flex flex-wrap items-center justify-between gap-3 rounded-md border p-3"
    >
      <span className="text-destructive text-sm">{labels.conflict.message}</span>
      <Button variant="outline" size="sm" onClick={onDiscard} disabled={discarding}>
        {labels.conflict.action}
      </Button>
    </div>
  )
}

export { DraftConflictBanner }
export type { DraftConflictBannerProps }
