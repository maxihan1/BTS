// 스프린트 편집 다이얼로그 — 공용 SprintForm + version 낙관적 락 PATCH (FR-BL-02 D6 FR-1·FR-2)
import type { JSX } from 'react'
import { useState } from 'react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { SprintForm, SprintFormFailure } from './SprintForm'
import type { SprintFormActions } from './SprintForm'
import { useUpdateSprint } from '@/hooks/use-backlog'
import type { SprintMeta, UpdateSprintBody } from '@/api/backlog'
import { ApiError } from '@/api/client'
import { backlogLabels } from '@/i18n/backlog-labels'
import { issueCreateStrings } from '@/i18n/ko'

/** 낙관적 잠금 충돌 HTTP 상태 */
const CONFLICT_STATUS = 409

/**
 * 저장 실패 종류.
 *
 * 둘을 뭉뚱그리면 거짓말이 된다 — 409 는 **남이 먼저 고쳤다**는 사실과 「내 입력은 그대로
 * 남았다」는 처방을 함께 말해야 하고, 나머지는 그냥 다시 보내면 된다.
 */
type FailureKind = 'conflict' | 'failed'

/** 실패 종류별 안내 문구 */
const FAILURE_MESSAGE: Record<FailureKind, string> = {
  conflict: backlogLabels.editDialog.saveConflict,
  failed: backlogLabels.editDialog.saveFailed,
}

/** 낙관적 잠금 충돌(409)인지 */
function isConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === CONFLICT_STATUS
}

/** EditSprintDialog props */
export interface EditSprintDialogProps {
  /** 열림 여부 (controlled) */
  open: boolean
  /** 열림 상태 변경 콜백 — 취소·Esc·바깥 클릭·저장 성공에서 발화 */
  onOpenChange: (open: boolean) => void
  /**
   * 편집 대상 스프린트. **초기값의 유일한 출처**다 — 별도 조회를 하지 않는다.
   *
   * ⚠️ 부모는 반드시 `key={sprint.sprintId}` 로 마운트한다. 기준값을 폼 내부 state 에
   * 두므로 대상이 바뀌어도 재마운트되지 않으면 낡은 값이 남는다.
   */
  sprint: SprintMeta
  /** 백로그 queryKey 대상 프로젝트 키. `useUpdateSprint` 가 무효화 대상을 알아야 한다 */
  projectKey: string
  /**
   * 화면이 보고 있는 보드 UUID. `?board=` 미지정이면 `undefined` (FR-BD-04).
   *
   * 🛑 **선택 prop 이 아니다.** 409 복구가 백로그 캐시를 완전 일치 키로 읽으므로
   * 보드를 빠뜨리면 기준값 교체가 무음으로 멈추고 재시도가 409 를 되풀이한다.
   */
  boardId: string | undefined
}

/**
 * 스프린트 편집 다이얼로그 (FR-1).
 *
 * ### 신규 폼을 만들지 않는다 (NFR-1)
 * 입력 4칸과 변경분 계산은 `SprintForm` 이 통째로 쥔다. 시작 다이얼로그와 **같은 폼**이라
 * 거기서 이미 푼 **부분 저장 유실 방어**(편집한 필드만 `PATCH` 에 싣는다)를 그대로 물려받는다.
 * 사본을 만들면 그 방어가 한쪽에서만 살아남는다.
 *
 * ### 응답을 버리지 않는다
 * `useUpdateSprint` KDoc 이 못박는 계약이다 — 저장 응답의 `SprintMeta` 로 폼의 기준값과
 * `version` 을 갱신하지 않으면, 창을 다시 열어 저장할 때 **낡은 `version` 으로 409** 를 받는다.
 * 훅이 invalidate 를 하더라도 재조회는 비동기라 그 사이를 막아주지 못한다.
 *
 * ### 완료된 스프린트 (FR-2 · J1)
 * 날짜 잠금은 폼이 `status` 로 판정한다. 프론트 잠금은 안내일 뿐이고 **백엔드가 400 으로
 * 거부**하는 것이 진짜 계약이다.
 */
export function EditSprintDialog({
  open,
  onOpenChange,
  sprint,
  projectKey,
  boardId,
}: EditSprintDialogProps): JSX.Element {
  const updateSprint = useUpdateSprint(projectKey)
  const [failure, setFailure] = useState<FailureKind | null>(null)

  const pending = updateSprint.isPending

  /**
   * 저장. 변경분이 0이면 요청 없이 닫는다 — 불필요한 `version` 증가는 낙관적 잠금 충돌면을
   * 넓히기만 한다.
   */
  async function save(body: UpdateSprintBody | null, actions: SprintFormActions): Promise<void> {
    setFailure(null)
    if (body === null) {
      onOpenChange(false)
      return
    }

    try {
      const updated = await updateSprint.mutateAsync({ sprintId: sprint.sprintId, body })
      // ★ 응답 흡수. 다음 저장이 최신 version 으로 나가게 한다 (`useUpdateSprint` KDoc)
      actions.applyServerResponse(updated)
      onOpenChange(false)
    } catch (error) {
      if (isConflict(error)) {
        setFailure('conflict')
        await actions.recoverFromConflict()
        return
      }
      setFailure('failed')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{backlogLabels.editSprint}</DialogTitle>
          <DialogDescription>{backlogLabels.editDialog.description}</DialogDescription>
        </DialogHeader>

        <SprintForm
          sprint={sprint}
          projectKey={projectKey}
          boardId={boardId}
          showName
          disabled={pending}
          onSubmit={(body, actions) => {
            void save(body, actions)
          }}
        >
          {failure !== null && (
            <SprintFormFailure message={FAILURE_MESSAGE[failure]} disabled={pending} />
          )}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => {
                onOpenChange(false)
              }}
            >
              {issueCreateStrings.cancelButton}
            </Button>
            {/* 기간이 어긋나거나 이름이 비어도 버튼을 **비활성화하지 않는다** — 비활성 버튼은
                이유를 말해주지 않고, 그렇게 하면 폼의 제출 가드가 도달 불가능한 죽은 코드가
                된다. 이유는 필드 아래에 이미 있다. */}
            <Button type="submit" disabled={pending}>
              {pending ? backlogLabels.editDialog.pending : backlogLabels.editSprint}
            </Button>
          </DialogFooter>
        </SprintForm>
      </DialogContent>
    </Dialog>
  )
}
