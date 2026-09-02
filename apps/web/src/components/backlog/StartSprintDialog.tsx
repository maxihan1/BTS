// 스프린트 시작 다이얼로그 — 기간·목표 확인 후 PATCH(변경분만) → start 2단계 (FR-UX-13 F15 FR-3·FR-4)
import type { JSX } from 'react'
import { useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
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
import { useStartSprint, useUpdateSprint, backlogKeys } from '@/hooks/use-backlog'
import type { SprintMeta, UpdateSprintBody } from '@/api/backlog'
import { ApiError } from '@/api/client'
import { backlogLabels } from '@/i18n/backlog-labels'
import { issueCreateStrings } from '@/i18n/ko'

/** 낙관적 잠금·상태 전환 충돌(409)인지 */
function isConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}

// ─────────────────────────────────────────────────────────────────────────────
// 실패 갈래 — 넷을 하나로 뭉뚱그리면 거짓말이 된다 (FR-4)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 다이얼로그에 남는 실패 종류.
 *
 * `start` 409(E10)는 여기 없다 — 재시도해도 반드시 409 라 **다이얼로그를 닫고**
 * 토스트로만 알린다. 나머지 셋과 처방이 정반대다.
 */
type FailureKind = 'patch' | 'patch-conflict' | 'start'

/** 실패 종류별 안내 문구 */
const FAILURE_MESSAGE: Record<FailureKind, string> = {
  patch: backlogLabels.startDialog.patchFailed,
  'patch-conflict': backlogLabels.startDialog.patchConflict,
  start: backlogLabels.startDialog.startFailed,
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** StartSprintDialog props */
export interface StartSprintDialogProps {
  /** 열림 여부 (controlled) */
  open: boolean
  /** 열림 상태 변경 콜백 — 취소·Esc·바깥 클릭·시작 성공·E10 에서 발화 */
  onOpenChange: (open: boolean) => void
  /**
   * 대상 스프린트 메타. **초기값의 유일한 출처**다 — 별도 조회를 하지 않는다 (FR-3).
   *
   * ⚠️ 부모는 반드시 `key={sprint.sprintId}` 로 마운트한다. 기준값을 폼 내부 state 에 두므로
   * 대상이 바뀌어도 재마운트되지 않으면 낡은 값이 남는다.
   */
  sprint: SprintMeta
  /**
   * 백로그 queryKey 대상 프로젝트 키.
   *
   * `useUpdateSprint`·`useStartSprint` 가 invalidate 대상을 알아야 해서 필요하다
   * (`use-backlog.ts` 의 모든 mutation 훅이 `projectKey` 를 받는다). 전역 활성 프로젝트를
   * 경유하지 않고 명시로 받는 것은 같은 디렉토리의 선례를 따른 것이다
   * (`SprintColumn`·`CreateSprintForm`·`CreateIssueDialog` 모두 명시 전달).
   */
  projectKey: string
  /**
   * 화면이 보고 있는 보드 UUID. `?board=` 미지정이면 `undefined` (FR-BD-04).
   *
   * 🛑 **선택 prop 이 아니다.** 409 복구가 백로그 캐시를 **완전 일치 키로** 읽으므로
   * (`SprintFormActions.recoverFromConflict`) 보드를 빠뜨리면 `getQueryData` 가 에러 없이
   * `undefined` 를 돌려주고 기준값 교체가 **무음으로 멈춘다** — 재시도가 낡은 `version` 으로
   * 나가 409 를 되풀이한다. 값이 없을 수는 있어도 **말하지 않을 수는 없다**.
   */
  boardId: string | undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// StartSprintDialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트 시작 다이얼로그.
 *
 * ### 2단계 요청 (FR-4)
 * 변경분이 1개 이상이면 `PATCH /sprints/{id}`(바뀐 필드 + `version`) → `POST /sprints/{id}/start`.
 * 변경분이 0이면 `PATCH` 를 보내지 않는다.
 *
 * ### 폼은 `SprintForm` 이 쥔다 (NFR-1)
 * 입력 3칸(이름은 그리지 않는다)과 변경분 계산은 편집 다이얼로그와 **같은 폼**이 담당한다.
 * 부분 저장 유실 방어(편집한 필드만 `PATCH` 에 싣는다)도 거기 산다.
 *
 * ### 기준값이 폼 내부 state 인 이유
 * 부모 props 를 기준값으로 쓰면 `PATCH` 성공 후에도 props 가 안 바뀌어(두 요청이 모두
 * 성공해야 부모가 갱신된다) 재시도가 **낡은 `version` 으로 409** 를 받는다.
 * 그래서 `PATCH` 응답의 SprintMeta 를 `applyServerResponse` 로 폼에 되먹인다.
 *
 * ### 재시도 버튼 이름을 `다시 시도` 로 재사용해도 되는 이유
 * 백로그 **조회 실패** 화면에도 같은 이름의 버튼이 있지만 둘은 공존할 수 없다 —
 * 조회가 실패하면 `BacklogBoard.tsx:125-143` 이 조기 반환해 이 다이얼로그가 통째로
 * 언마운트되기 때문이다. 그래서 새 문자열을 만들지 않는다 (FR-10).
 */
export function StartSprintDialog({
  open,
  onOpenChange,
  sprint,
  projectKey,
  boardId,
}: StartSprintDialogProps): JSX.Element {
  const queryClient = useQueryClient()
  const updateSprint = useUpdateSprint(projectKey)
  const startSprint = useStartSprint(projectKey)

  const [failure, setFailure] = useState<FailureKind | null>(null)

  /**
   * `PATCH` 만 성공한 상태인지. state 가 아니라 ref 인 이유는 **닫기 직전에 동기로 읽어야**
   * 하기 때문이다 — `setState` 직후의 클로저는 아직 옛 값을 본다.
   */
  const patchAppliedRef = useRef(false)

  const pending = updateSprint.isPending || startSprint.isPending

  /**
   * 백로그를 새로 받는다. 두 mutation 훅도 성공 시 같은 일을 하지만 실패 경로에는 없다.
   *
   * 무효화는 **프로젝트 접두 키**다 — 스프린트 시작은 그 보드뿐 아니라 이 프로젝트의 모든
   * 보드 백로그 칸에 영향을 준다(E12).
   */
  async function invalidateBacklog(): Promise<void> {
    await queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
  }

  /** 1단계. 성공하면 기준값을 응답으로 갈아끼우고 `true` 를 반환한다 */
  async function runPatch(body: UpdateSprintBody, actions: SprintFormActions): Promise<boolean> {
    try {
      const updated = await updateSprint.mutateAsync({ sprintId: sprint.sprintId, body })
      // 편집분이 서버에 반영됐으니 폼이 기준값과 편집 집합을 갱신한다 — 재시도의 변경분이
      // 0이 되고, 세 칸 모두 응답값 표시로 돌아간다
      actions.applyServerResponse(updated)
      patchAppliedRef.current = true
      return true
    } catch (error) {
      if (isConflict(error)) {
        setFailure('patch-conflict')
        // E9 — 기준값(`version`)만 서버 최신으로. 사용자가 친 값은 폼이 그대로 지킨다
        await actions.recoverFromConflict()
        return false
      }
      setFailure('patch')
      return false
    }
  }

  /** 2단계. 409(E10)는 재시도가 불가능하므로 토스트만 남기고 닫는다 */
  async function runStart(): Promise<void> {
    try {
      await startSprint.mutateAsync(sprint.sprintId)
      // 훅이 이미 invalidate 했다 — 닫기 게이트가 한 번 더 부르지 않도록 깃발을 내린다
      patchAppliedRef.current = false
      closeDialog()
    } catch (error) {
      if (!isConflict(error)) {
        setFailure('start')
        return
      }
      toast.error(backlogLabels.startDialog.startConflict)
      patchAppliedRef.current = false
      await invalidateBacklog()
      closeDialog()
    }
  }

  /**
   * 제출. E8(종료일 < 시작일) 가드는 폼 안에 있어 여기까지 오지 않는다.
   *
   * @param body 폼이 계산한 변경분. 0이면 `null` 이라 `PATCH` 를 건너뛴다
   * @param actions 응답·409 를 폼 기준값에 되먹이는 통로
   */
  async function handleSubmit(
    body: UpdateSprintBody | null,
    actions: SprintFormActions,
  ): Promise<void> {
    setFailure(null)
    if (body !== null && !(await runPatch(body, actions))) return
    await runStart()
  }

  /**
   * 닫힘 경로 **단일 창구**. Esc·바깥 클릭·취소·성공·E10 이 전부 여기를 지난다.
   *
   * `PATCH` 만 성공한 채 닫히면 목록을 새로 받는다 — 안 하면 재개봉 시 기준값이
   * 낡은 `version` 으로 리셋돼 다음 `PATCH` 가 409 다.
   */
  function handleOpenChange(next: boolean): void {
    if (!next && patchAppliedRef.current) {
      patchAppliedRef.current = false
      void invalidateBacklog()
    }
    onOpenChange(next)
  }

  /** 닫기 — 게이트를 우회하는 경로를 만들지 않기 위한 얇은 별칭 */
  function closeDialog(): void {
    handleOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{backlogLabels.startSprint}</DialogTitle>
          <DialogDescription>{backlogLabels.startDialog.description}</DialogDescription>
        </DialogHeader>

        <SprintForm
          sprint={sprint}
          projectKey={projectKey}
          boardId={boardId}
          showName={false}
          disabled={pending}
          onSubmit={(body, actions) => {
            void handleSubmit(body, actions)
          }}
        >
          {failure !== null && (
            <SprintFormFailure message={FAILURE_MESSAGE[failure]} disabled={pending} />
          )}

          <DialogFooter>
            <Button type="button" variant="outline" disabled={pending} onClick={closeDialog}>
              {issueCreateStrings.cancelButton}
            </Button>
            {/* 기간이 어긋나도 버튼을 **비활성화하지 않는다** — 비활성 버튼은 이유를 말해주지
                않고, 그렇게 하면 폼의 제출 가드가 도달 불가능한 죽은 코드가 돼 그것을 지키는
                테스트가 공허해진다. 이유는 필드 아래에 이미 있다. */}
            <Button type="submit" disabled={pending}>
              {pending ? backlogLabels.startDialog.pending : backlogLabels.startSprint}
            </Button>
          </DialogFooter>
        </SprintForm>
      </DialogContent>
    </Dialog>
  )
}
