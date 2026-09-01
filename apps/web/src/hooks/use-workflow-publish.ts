// 발행·미리보기·이관 큐잉·기본값 복원·초안 폐기 뮤테이션 — 앵커는 호출부가 넘긴다
import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  previewPublish,
  publishDraft,
  migrateStatuses,
  resetToDefault as resetToDefaultApi,
  discardDraft,
} from '@/api/workflows-draft'
import type {
  DraftResponse,
  PublishPreview,
  PublishResult,
  MigrateAccepted,
  StatusMappingInput,
} from '@/api/workflows-draft.types'
import { WORKFLOW_ADMIN_KEYS } from './use-workflows-admin'
import { WORKFLOW_DRAFT_KEY } from './use-workflow-draft'

export interface UseWorkflowPublishResult {
  /**
   * 발행하면 무엇이 바뀌는지 계산한다. **매번 서버에 새로 묻는다.**
   *
   * 캐시에 얹지 않는 이유 — 초안이 바뀌면 옛 결과는 거짓이다. 상태를 뺀 직후에 캐시된
   * 「바뀐 것 없음」이 뜨면 마법사가 열리지 않고 발행이 곧장 409 로 막힌다.
   *
   * ★ 응답의 `currentVersion` 을 **앵커로 쓰지 마라.** 그것은 「지금 DB 가 몇 버전인가」이지
   * 「편집기가 무엇을 보고 있었는가」가 아니다. 되실어 보내면 낙관적 락이 풀린다.
   */
  preview: () => Promise<PublishPreview>
  /** 초안을 발행한다. `baseVersion` 은 `useWorkflowDraft` 가 들고 있는 앵커 그대로다. */
  publish: (baseVersion: number) => Promise<PublishResult>
  /** 빠지는 상태의 이슈 이관을 큐잉한다. 202 이고 **발행은 따로** 불러야 한다. */
  migrate: (baseVersion: number, mappings: StatusMappingInput[]) => Promise<MigrateAccepted>
  /** YAML 기본값을 초안으로 불러온다. 응답이 새 앵커를 준다. */
  resetToDefault: (baseVersion: number) => Promise<DraftResponse>
  /** 초안을 폐기한다. 앵커가 죽어 영구 409 가 된 초안의 유일한 출구다. */
  discard: () => Promise<void>
}

/**
 * 발행 경로의 쓰기 묶음.
 *
 * ### 왜 `useMutation` 이 아니라 평범한 함수인가
 * 이 호출들은 전부 **다이얼로그 흐름 안에서 순서대로** 일어난다 — 저장 flush → preview →
 * (이관 → 폴링) → publish. `mutateAsync` 로 감싸도 결국 await 로 이어 붙이게 되고, 대신
 * 훅 하나에 mutation 다섯 개의 `isPending` 이 생겨 어느 것이 도는지 화면이 헷갈린다.
 * 진행 표시는 다이얼로그가 자기 단계로 들고 있는 편이 정확하다.
 *
 * ### 앵커를 훅이 보관하지 않는다
 * 인자로 받는다. 훅이 들고 있으면 preview 응답으로 갱신하고 싶은 유혹이 생기고, 그 한 줄이
 * 낙관적 락을 푼다. 앵커의 소유자는 `useWorkflowDraft` 하나다.
 */
export function useWorkflowPublish(key: string): UseWorkflowPublishResult {
  const client = useQueryClient()

  /** 발행 뒤에는 목록·상세가 실제로 바뀐다 — 무효화하지 않으면 화면이 옛 정의를 계속 보여준다. */
  const invalidatePublished = React.useCallback(async () => {
    await client.invalidateQueries({ queryKey: WORKFLOW_ADMIN_KEYS.list })
    await client.invalidateQueries({ queryKey: WORKFLOW_ADMIN_KEYS.detail(key) })
    // 발행은 초안 행을 지운다. 다음 조회가 새 정의를 초안으로 받아야 한다.
    await client.invalidateQueries({ queryKey: WORKFLOW_DRAFT_KEY(key) })
  }, [client, key])

  const preview = React.useCallback(() => previewPublish(key), [key])

  const publish = React.useCallback(
    async (baseVersion: number) => {
      const result = await publishDraft(key, baseVersion)
      await invalidatePublished()
      return result
    },
    [key, invalidatePublished],
  )

  const migrate = React.useCallback(
    (baseVersion: number, mappings: StatusMappingInput[]) => migrateStatuses(key, baseVersion, mappings),
    [key],
  )

  const resetToDefault = React.useCallback(
    async (baseVersion: number) => {
      const restored = await resetToDefaultApi(key, baseVersion)
      // 정규 테이블은 안 바뀐다 — 초안 캐시만 갈아 끼운다.
      client.setQueryData(WORKFLOW_DRAFT_KEY(key), restored)
      return restored
    },
    [key, client],
  )

  const discard = React.useCallback(async () => {
    await discardDraft(key)
    await client.invalidateQueries({ queryKey: WORKFLOW_DRAFT_KEY(key) })
  }, [key, client])

  return { preview, publish, migrate, resetToDefault, discard }
}
