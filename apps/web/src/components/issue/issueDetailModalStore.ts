// 이슈 상세 모달의 열림 대상과 표시 방식 선호를 담는 전역 UI 스토어 (Jira 패리티 J1)
import { create } from 'zustand'

/** 세션 내 표시 방식 선호를 담는 sessionStorage 키. */
const PREFER_KEY = 'bts.issueDetail.presentation'

/** 상세를 어떤 형태로 열지. Jira 는 modal 과 sidebar 를 토글하고 세션 내내 기억한다(J1). */
export type IssueDetailPresentation = 'modal' | 'sidePanel'

/**
 * 저장된 선호를 읽는다.
 *
 * sessionStorage 접근은 사설 창·차단 설정에서 **접근 자체가 throw** 한다.
 * `use-sidebar-collapsed.ts` 의 fail-safe 템플릿과 같은 이유로 통째로 감싼다.
 *
 * @returns 저장된 선호. 없거나 읽기 실패면 `'modal'`(Jira 기본)
 */
function readPreference(): IssueDetailPresentation {
  try {
    return sessionStorage.getItem(PREFER_KEY) === 'sidePanel' ? 'sidePanel' : 'modal'
  } catch {
    return 'modal'
  }
}

/** 선호를 저장한다. 실패해도 무시한다 — 이번 세션에서 기억되지 않을 뿐 기능은 그대로다. */
function writePreference(value: IssueDetailPresentation): void {
  try {
    sessionStorage.setItem(PREFER_KEY, value)
  } catch {
    // 저장 실패는 조용히 넘긴다 (fail-safe).
  }
}

interface IssueDetailModalState {
  /** 모달로 열려 있는 이슈 키. null 이면 닫힘. */
  openKey: string | null
  /**
   * 열자마자 데려갈 댓글 UUID. null 이면 평소대로 이슈 상단에서 시작한다.
   *
   * 인박스 알림처럼 「그 댓글 때문에」 상세를 여는 진입점이 채운다.
   * `openKey` 와 함께 살고 함께 죽는다 — 닫으면 같이 비운다.
   */
  openCommentId: string | null
  /** 세션 내 표시 방식 선호 (J1 — "persist across Jira views within the same session"). */
  presentation: IssueDetailPresentation
}

interface IssueDetailModalActions {
  /**
   * 이슈 상세를 모달로 연다.
   *
   * @param issueKey 열 이슈 키
   * @param commentId 열자마자 데려갈 댓글 UUID (선택). 생략하면 이슈 상단에서 시작한다.
   */
  open: (issueKey: string, commentId?: string | null) => void
  /** 모달을 닫는다. */
  close: () => void
  /** 표시 방식을 바꾸고 세션에 기억한다. */
  setPresentation: (value: IssueDetailPresentation) => void
}

/**
 * 이슈 상세 모달 전역 스토어.
 *
 * ## 왜 전역인가
 *
 * 상세를 여는 진입점이 13곳이다 — 보드 카드 · 백로그 카드 · 목록 셀 · 인박스 · 활동 피드 ·
 * 대시보드 가젯 · 최근 항목 · 커맨드 팔레트 2 · 캘린더 · 상단바 2 · 에픽 자식 · 링크 그래프.
 * 각자 열림 상태를 들면 「보드에서 연 모달이 백로그로 이동해도 남아 있다」 같은 어긋남이 생기고,
 * 무엇보다 **모달이 여러 개 동시에 마운트**될 수 있다.
 *
 * `loginPromptStore` 와 같은 형태다 — 비영속 zustand + `__root.tsx` 단일 마운트.
 * 다만 표시 방식 선호만은 세션 유지가 요구(J1)라 sessionStorage 를 겸한다.
 *
 * ## 영속하지 않는 것
 *
 * `openKey` 는 절대 영속하지 않는다. 새로고침 후 유령 모달이 뜬다 — `loginPromptStore` 가
 * `authStore`(persist)에 얹히지 않은 것과 같은 이유다.
 */
export const useIssueDetailModalStore = create<IssueDetailModalState & IssueDetailModalActions>()(
  (set) => ({
    openKey: null,
    openCommentId: null,
    presentation: readPreference(),
    // ★두 번째 인자를 항상 덮어쓴다. 생략 시 `null` 로 되돌리지 않으면 이전에 딥링크로 연
    //   댓글이 다음 번 평범한 열기까지 따라와 엉뚱한 댓글로 스크롤한다.
    open: (issueKey, commentId = null) => { set({ openKey: issueKey, openCommentId: commentId }) },
    close: () => { set({ openKey: null, openCommentId: null }) },
    setPresentation: (value) => {
      writePreference(value)
      set({ presentation: value })
    },
  }),
)
