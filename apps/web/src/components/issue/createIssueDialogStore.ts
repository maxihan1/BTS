// 전역 이슈 생성 모달(상단바 소유)의 열림 상태 — 상단바 버튼과 `c` 단축키가 함께 연다 (JA-C-1)
import { create } from 'zustand'

interface CreateIssueDialogState {
  /** 상단바 생성 모달 열림 여부 */
  open: boolean
  /** 열림 상태를 바꾼다 */
  setOpen: (open: boolean) => void
}

/**
 * 상단바 생성 모달의 열림 상태.
 *
 * `c` 단축키는 `/issues/new` 로 화면을 옮기지 않고 **보던 화면 위에** 이 모달을 연다(Jira 동작).
 * 단축키 리스너는 컴포넌트 밖 함수라 상태를 props 로 받을 수 없어 스토어로 둔다.
 */
export const useCreateIssueDialogStore = create<CreateIssueDialogState>((set) => ({
  open: false,
  setOpen: (open) => { set({ open }) },
}))
