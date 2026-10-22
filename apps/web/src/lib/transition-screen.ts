// 전환 화면 입력 상태의 순수 규칙 — 미리 채우기 · 필수 판정 · 요청 값 변환 (FR-WF-01 D11)
import type { TransitionScreenField, TransitionScreenValues } from '@/api/issues'

/** 대화상자의 입력 상태. 빈 값은 '' · null 로 둔다. */
export interface TransitionScreenForm {
  resolutionId: string
  assigneeId: string | null
  comment: string
}

/** 이슈의 현재 값 — 필수 칸이 이미 차 있으면 그대로 통과하도록 미리 채운다(Jira 와 같다). */
export interface TransitionScreenPrefill {
  resolutionId: string | null
  assigneeId: string | null
}

/** 미리 채운 입력 상태를 만든다. 코멘트는 늘 새로 쓴다. */
export function initialScreenForm(prefill: TransitionScreenPrefill): TransitionScreenForm {
  return { resolutionId: prefill.resolutionId ?? '', assigneeId: prefill.assigneeId, comment: '' }
}

/** 필드 하나가 채워졌는지 — 서버 강제(`WorkflowEngine.enforceScreen`)와 같은 규칙(공백뿐이면 빈 값). */
function filled(field: string, form: TransitionScreenForm): boolean {
  switch (field) {
    case 'resolution':
      return form.resolutionId !== ''
    case 'assignee':
      return form.assigneeId !== null
    case 'comment':
      return form.comment.trim() !== ''
    default:
      return true
  }
}

/** 필수 칸이 다 찼는지 */
export function isScreenComplete(screen: TransitionScreenField[], form: TransitionScreenForm): boolean {
  return screen.every((f) => !f.required || filled(f.field, form))
}

/**
 * 입력 상태를 전환 요청 값으로 바꾼다 — 화면에 있는 칸만, 빈 칸은 뺀다.
 * 해결 결과가 화면에 없으면 실리지 않아 서버가 비운다(종전 비-DONE 전환과 같다).
 */
export function toScreenValues(screen: TransitionScreenField[], form: TransitionScreenForm): TransitionScreenValues {
  const on = (field: string) => screen.some((f) => f.field === field)
  const comment = form.comment.trim()
  return {
    resolutionId: on('resolution') && form.resolutionId !== '' ? form.resolutionId : undefined,
    assigneeId: on('assignee') && form.assigneeId !== null ? form.assigneeId : undefined,
    comment: on('comment') && comment !== '' ? comment : undefined,
  }
}
