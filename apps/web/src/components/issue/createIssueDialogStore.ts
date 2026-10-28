// 전역 이슈 생성 모달(상단바 소유)의 열림 · 최소화 상태 — 상단바 버튼과 `c` 단축키가 함께 연다 (JA-C-1 · JA-C-8)
import { create } from 'zustand'

interface CreateIssueDialogState {
  /** 상단바 생성 모달 열림 여부 — 최소화 중에도 참이다(입력을 쥐고 있다) */
  open: boolean
  /** 오른쪽 아래 알약으로 접혀 있는지 */
  minimized: boolean
  /** 열림 상태를 바꾼다 — 최소화도 풀린다(최소화 중 다시 열면 그 입력으로 돌아온다) */
  setOpen: (open: boolean) => void
  /** 최소화 상태를 바꾼다 */
  setMinimized: (minimized: boolean) => void
}

/**
 * 상단바 생성 모달의 열림 · 최소화 상태.
 *
 * `c` 단축키는 `/issues/new` 로 화면을 옮기지 않고 **보던 화면 위에** 이 모달을 연다(Jira 동작).
 * 단축키 리스너는 컴포넌트 밖 함수라 상태를 props 로 받을 수 없어 스토어로 둔다.
 */
export const useCreateIssueDialogStore = create<CreateIssueDialogState>((set) => ({
  open: false,
  minimized: false,
  setOpen: (open) => { set({ open, minimized: false }) },
  setMinimized: (minimized) => { set({ minimized }) },
}))
