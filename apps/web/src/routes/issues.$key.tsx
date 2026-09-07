// 이슈 상세 페이지 라우트 — 시안 2 사이드 메타패널 (좌 본문 / 우 메타패널, 상태전환 컨트롤 포함)
// KeyboardEvent 는 별칭으로 받는다 — 그냥 이름으로 들이면 usePaneEscapeClose(:79)가 쓰는
// 전역 DOM KeyboardEvent 를 모듈 스코프에서 가려 document 리스너 타입이 조용히 바뀐다.
import type { JSX, RefObject, KeyboardEvent as ReactKeyboardEvent } from 'react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { useParams, useNavigate, useSearch } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { fetchIssue, updateIssue, IssueRedirectError } from '@/api/issues'
import { useRecentIssues } from '@/hooks/use-recent-issues'
import type { IssueTransition, CustomFieldValues } from '@/api/issues'
import { useUpdateIssueSummary, issueQueryKey } from '@/api/useUpdateIssueSummary'
import { useChangeAssignee } from '@/api/useChangeAssignee'
import { useDeleteIssue } from '@/api/useDeleteIssue'
import { useChangeComponents } from '@/api/useChangeComponents'
import { useChangeSecurityLevel } from '@/api/useChangeSecurityLevel'
import { useChangeAffectsVersions, useChangeFixVersions } from '@/api/issue-versions'
import { fetchComponents } from '@/api/components'
import { useVersions } from '@/hooks/use-versions'
import { useIssueTypes } from '@/hooks/use-issue-types'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { useIssueTransitions, useIssueTransitionFlow } from '@/hooks/use-issue-transitions'
import { useUsers } from '@/hooks/use-users'
import { useIssuePeople } from '@/hooks/use-issue-people'
import { useDebounce } from '@/hooks/use-debounce'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { Button } from '@/components/ui/button'
import { IssueDetailHeader } from '@/components/issue/IssueDetailHeader'
import { downloadIssuePdf } from '@/api/issues'
import { triggerBlobDownload } from '@/lib/download'
import { IssueDescription } from '@/components/issue/IssueDescription'
import type { ChangelogRefs } from '@/lib/changelog-labels'
import { AttachmentSection } from '@/components/issue/AttachmentSection'
import { IssueMetaPanel, isFieldHidden, isFieldDisabled } from '@/components/issue/IssueMetaPanel'
import { AmbiguousTransitionPrompt } from '@/components/issue/meta/AmbiguousTransitionPrompt'
import { IssueScheduleFields } from '@/components/issue/IssueScheduleFields'
import { IssueEstimatePanel } from '@/components/issue/IssueEstimatePanel'
import { IssueActivityTabs, ACTIVITY_TABS } from '@/components/issue/IssueActivityTabs'
import type { ActivityTabValue } from '@/components/issue/IssueActivityTabs'
import { useContextShortcuts } from '@/components/keyboard-shortcuts/useContextShortcuts'
import {
  useHasOpenModal,
  useReportModalOpen,
} from '@/components/keyboard-shortcuts/useOpenModalRegistry'
import { useAuthUser } from '@/auth/authStore'
import { ResolutionModal } from '@/components/issue/ResolutionModal'
import { CloneIssueDialog } from '@/components/issues/CloneIssueDialog'
import { MoveIssueDialog } from '@/components/issues/MoveIssueDialog'
import { issueDetailStrings, worklogStrings } from '@/i18n/ko'
import { classifyMetaMutationError } from '@/components/issue/meta/meta-mutation-error'
// 전환 불가 사유 판정(스펙 E5) — 목록 상태 셀과 공용이라 lib 로 승격했다 (FR-UX-11 F9).
// 복제하면 상세와 목록이 같은 응답을 서로 다르게 설명하게 된다.
import { resolveTransitionUnavailableReason } from '@/lib/transition-availability'
import { invalidateIssueViews } from '@/api/issue-view-invalidation'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — pane variant 헤더/포커스/Escape (FR-UX-06 PR20 Task 1)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * pane variant 전용 — Escape 키 입력 시 `onClose`를 호출하는 keydown 리스너를 등록한다.
 * `variant !== 'pane'`이면 리스너를 등록하지 않는다. 언마운트/변경 시 자동 해제.
 */
function usePaneEscapeClose(variant: 'page' | 'pane', onClose: (() => void) | undefined): void {
  useEffect(() => {
    if (variant !== 'pane') return
    function handleKeyDown(e: KeyboardEvent): void {
      // CONCERNS-2 — Radix DismissableLayer(다이얼로그/드롭다운)는 capture 단계에서
      // Escape를 dismiss 처리하며 preventDefault()한다(stopPropagation은 하지 않음).
      // 이 리스너는 bubble 단계라 뒤늦게 도달하므로, 이미 처리된 Escape는 건너뛰어
      // 다이얼로그/드롭다운과 페인이 동시에 닫히는 이중 발화를 막는다.
      if (e.defaultPrevented) return
      if (e.key === 'Escape') {
        onClose?.()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [variant, onClose])
}

/**
 * pane variant 전용 — 이슈 데이터 로드가 끝나면 `targetRef`(상세 영역 제목)로 1회만 포커스를 이동한다.
 * 이후 메타필드 mutation으로 데이터가 refetch돼도 재포커스로 사용자 입력을 방해하지 않는다.
 * `preventScroll`로 포커스 이동이 페이지 스크롤 점프를 유발하지 않도록 한다.
 */
function usePaneFocusOnLoad(
  variant: 'page' | 'pane',
  loaded: boolean,
  targetRef: RefObject<HTMLElement | null>,
): void {
  const hasFocusedRef = useRef(false)
  useEffect(() => {
    if (variant !== 'pane' || !loaded || hasFocusedRef.current) return
    hasFocusedRef.current = true
    targetRef.current?.focus({ preventScroll: true })
  }, [variant, loaded, targetRef])
}

/**
 * 제목 편집 진입 시 입력창으로 포커스를 옮기고 커서를 **텍스트 끝**에 놓는다 (FR1).
 *
 * 두 진입로(제목 텍스트 클릭 · `✎ 제목 수정` 버튼)가 모두 `isEditingTitle` 을 켜므로
 * 여기 한 곳만 두면 경로가 갈리지 않는다.
 *
 * `autoFocus` 속성을 쓰지 않는 이유. 포커스만 줄 뿐 커서 위치가 브라우저마다 달라
 * (전체 선택 / 맨 앞 / 맨 뒤) 스펙의 "커서는 텍스트 끝"을 보장하지 못한다.
 * `preventScroll` 은 usePaneFocusOnLoad 와 같은 배려 — 포커스 이동이 스크롤 점프를 만들지 않게 한다.
 *
 * 의존성이 `isEditing` 뿐이라 편집 중 타이핑(`editSummary` 변경)으로는 재실행되지 않는다.
 * 재실행되면 사용자가 옮겨 둔 커서를 매 타건마다 끝으로 되돌려 버린다.
 *
 * **편집이 끝나면 진입면(`returnRef`)으로 포커스를 되돌린다 (WCAG 2.4.3).**
 * 되돌리지 않으면 `Input` 언마운트와 함께 포커스가 `<body>` 로 떨어져, 키보드 사용자의
 * 다음 `Tab` 이 문서 맨 앞부터 다시 시작한다. `Enter` 저장·`Esc` 취소를 1급 키보드 경로로
 * 승격시킨 이상 왕복이 닫혀야 한다.
 *
 * `wasEditingRef` 가 **마운트 시 포커스 탈취를 막는다** — 초기값 false 라 편집을 연 적이
 * 없으면 복귀 분기가 돌지 않는다. 이게 없으면 usePaneFocusOnLoad 의 제목 포커스와 싸운다.
 */
function useTitleEditFocus(
  isEditing: boolean,
  inputRef: RefObject<HTMLInputElement | null>,
  returnRef: RefObject<HTMLButtonElement | null>,
): void {
  const wasEditingRef = useRef(false)
  useEffect(() => {
    if (isEditing) {
      const input = inputRef.current
      if (input !== null) {
        input.focus({ preventScroll: true })
        const end = input.value.length
        input.setSelectionRange(end, end)
      }
    } else if (wasEditingRef.current) {
      // 편집이 방금 끝났다(저장·취소 공통). 진입면이 사라진 경우(권한이 false 로 뒤집힘)는
      // optional chaining 으로 무해하게 넘긴다 — 없는 요소에 포커스를 강제하지 않는다.
      returnRef.current?.focus({ preventScroll: true })
    }
    wasEditingRef.current = isEditing
  }, [isEditing, inputRef, returnRef])
}

// ─────────────────────────────────────────────────────────────────────────────
// router.ts 등록 방법 (code-based 패턴 — PR #11 컨벤션).
//
//   import { IssueDetailRouteAdapter } from './routes/issues.$key'
//
//   const issuesKeyRoute = createRoute({
//     getParentRoute: () => rootRoute,
//     path: '/issues/$key',
//     component: IssueDetailRouteAdapter,
//   })
//
// IssueDetailRouteAdapter는 useParams({ strict: false })로 key를 추출해
// IssueDetailPage에 전달한다. 라우터 등록은 router.ts 담당.
// ─────────────────────────────────────────────────────────────────────────────

interface IssueDetailPageProps {
  /** URL params에서 추출한 이슈 식별 키 (예: "ATLAS-1") */
  issueKey: string
  /**
   * 렌더 모드 (FR-UX-06 PR20 split view).
   * - `'page'`(기본) — 전체화면. 제목이 `<h1>`, 닫기 버튼 없음, redirect/삭제는 fullscreen navigate.
   * - `'pane'` — split view 우측 페인. 제목이 `<h2>`(문서 `<h1>` 단일 계약), 헤더에 닫기 버튼 +
   *   `Escape` 키 + 마운트 시 포커스 이동이 추가되고, redirect/삭제는 `onIssueRedirect`/`onIssueClosed`
   *   콜백에 위임한다(부모가 페인 상태를 제어).
   */
  variant?: 'page' | 'pane'
  /** pane 전용 — 닫기 버튼 클릭 또는 `Escape` 키 입력 시 호출 */
  onClose?: () => void
  /** pane 전용 — 308 redirect(옛 키 → 새 키) 감지 시 fullscreen navigate 대신 호출 */
  onIssueRedirect?: (newKey: string) => void
  /** pane 전용 — 삭제 성공 시 fullscreen navigate('/issues') 대신 호출 */
  onIssueClosed?: () => void
  /**
   * 열자마자 데려갈 댓글 UUID (인박스 알림 딥링크).
   *
   * 주어지면 활동 영역이 **댓글 탭으로 시작**하고 그 댓글까지 스크롤한다. Radix Tabs 는
   * 비활성 탭을 언마운트하므로 탭을 먼저 열지 않으면 스크롤할 대상이 DOM 에 없다.
   */
  focusCommentId?: string
}


/**
 * 이슈 상세 페이지 컴포넌트 (시안 2 — 사이드 메타패널).
 *
 * - useQuery로 fetchIssue(issueKey)를 호출한다.
 * - 3 상태 분기: 로딩 → 에러/미존재 → 성공.
 * - 성공 레이아웃: 좌측 본문(breadcrumb + 제목 인라인 편집) + 우측 메타패널.
 * - 상태 전환 셀렉터 + 담당자 셀렉터(useUsersByIds 별도 조회) + debounce 검색 포함.
 * - 삭제: 확인 UI → useDeleteIssue → 목록으로 navigate.
 * - `variant='pane'`(FR-UX-06 PR20)이면 fullscreen navigate 대신 `onClose`/`onIssueRedirect`/
 *   `onIssueClosed` 콜백에 위임한다 — {@link IssueDetailPageProps} 참조.
 *
 * 라우터 의존 없이 props로 issueKey를 받아 단위 테스트가 가능하다.
 */
/**
 * 전환 요청에 실을 1급 식별자 — 없으면 **미전달**로 떨어뜨린다.
 *
 * 값이 있으면 서버는 그 전환을 그대로 실행한다. 같은 상태쌍에 이름만 다른 전환이 둘 있어도
 * 어느 쪽인지 이 값 하나로 지목되므로 되묻지 않는다(ADR 2026-08-18 §D3).
 *
 * 값이 없는 구 데이터면 서버가 `toStatusKey` 로 후보를 찾고, 둘 이상이면 409
 * `AMBIGUOUS_TRANSITION` + 후보 목록으로 답한다 — `AmbiguousTransitionPrompt` 가 그 폴백이다.
 * `issueTransitionSchema.transitionId` 가 `nullable().optional()` 인 것이 이 경우를 연다.
 */
function transitionIdOf(transition: IssueTransition): string | undefined {
  return transition.transitionId ?? undefined
}

/**
 * 변경 이력의 값 표시명을 해석하는 데 필요한 참조 묶음을 조립한다.
 *
 * `IssueDetailPage` 안의 인라인 객체였다. 활동 탭을 본문 컬럼 안으로 옮기면서 들여쓰기가
 * 깊어져 `lint-ratchet` 의 200줄 래칫(동결 1013)을 넘겼고, 래칫이 요구한 대로 쪼갰다 —
 * 베이스라인 숫자를 올리는 것은 부채를 늘리는 쪽이라 택하지 않았다.
 */
function buildChangelogRefs(
  types: ChangelogRefs['types'],
  components: ChangelogRefs['components'],
  versions: ChangelogRefs['versions'],
  customFieldDefinitions: ChangelogRefs['customFieldDefinitions'],
): ChangelogRefs {
  return {
    types,
    components,
    versions,
    priorityMap: issueDetailStrings.priorityNames as Record<number, string>,
    impactMap: issueDetailStrings.impactNames as Record<number, string>,
    customFieldDefinitions,
  }
}

/**
 * 활동 영역 활성 탭 상태 — 기본값과 **댓글 딥링크**를 함께 다룬다.
 *
 * 라우트가 탭을 소유하는 이유는 단축키 `m` 이다(Radix Tabs 가 비활성 탭을 언마운트해서,
 * 탭을 먼저 열지 않으면 포커스 줄 입력이 DOM 에 없다). 딥링크도 같은 제약을 받는다 —
 * 댓글 탭이 닫혀 있으면 스크롤할 행 자체가 없다.
 *
 * ★초기값과 effect **둘 다** 필요하다.
 * - 초기값만 두면 모달이 열린 채로 다른 알림을 눌렀을 때(`focusCommentId` 만 교체) 못 따라간다.
 * - effect 만 두면 첫 렌더가 이력 탭이라 한 프레임 번쩍이고, 그 사이 댓글 목록이 마운트되지
 *   않아 스크롤 대상이 없다.
 *
 * `IssueDetailPage` 밖에 두는 것은 200줄 래칫(`lint-ratchet`) 때문이기도 하다 —
 * 베이스라인 숫자를 올리는 대신 쪼갠다(`buildChangelogRefs` 선례).
 *
 * @param focusCommentId 딥링크가 지목한 댓글 UUID (없으면 undefined)
 * @returns `[활성 탭, 탭 변경 함수]`
 */
function useActivityTab(
  focusCommentId: string | undefined,
): [ActivityTabValue, (value: ActivityTabValue) => void] {
  const [activityTab, setActivityTab] = useState<ActivityTabValue>(
    focusCommentId !== undefined ? ACTIVITY_TABS.COMMENT : ACTIVITY_TABS.HISTORY,
  )

  useEffect(() => {
    if (focusCommentId === undefined) return
    setActivityTab(ACTIVITY_TABS.COMMENT)
  }, [focusCommentId])

  return [activityTab, setActivityTab]
}

export function IssueDetailPage({
  issueKey,
  variant = 'page',
  onClose,
  onIssueRedirect,
  onIssueClosed,
  focusCommentId,
}: IssueDetailPageProps): JSX.Element {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [isEditingTitle, setIsEditingTitle] = useState(false)
  const [editSummary, setEditSummary] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [cloneDialogOpen, setCloneDialogOpen] = useState(false)
  const [moveDialogOpen, setMoveDialogOpen] = useState(false)
  const [assigneeSearchQuery, setAssigneeSearchQuery] = useState('')
  const [isPdfDownloading, setIsPdfDownloading] = useState(false)

  // ── Resolution 모달 상태 ──────────────────────────────────────────────────
  /** 현재 DONE 전환 대기 중인 전환 항목. null이면 모달 닫힘. */
  const [pendingDoneTransition, setPendingDoneTransition] = useState<IssueTransition | null>(null)

  // ── pane 전용 — 헤더 닫기/Escape/마운트 포커스 (FR-UX-06 PR20 Task 1) ───────
  const paneTitleRef = useRef<HTMLHeadingElement>(null)

  // 제목 편집 입력창 — 진입 시 포커스 + 커서 끝 배치용 (FR-UX-11 F8 FR1)
  const titleInputRef = useRef<HTMLInputElement>(null)
  // 편집 종료 후 포커스 복귀 대상 — 제목 클릭 진입면 (WCAG 2.4.3)
  const titleButtonRef = useRef<HTMLButtonElement>(null)

  // ── FR-UX-10 F11 — 상세 액션 단축키가 조작할 컨트롤 손잡이 5종 ──────────────
  // 전부 하위 컴포넌트가 자기 요소에 붙여 주는 ref다(Task 4). 이 라우트는 소비만 한다.
  /** 담당자 검색 input — 단축키 `a` */
  const assigneeSearchRef = useRef<HTMLInputElement>(null)
  /** 라벨 입력 — 단축키 `l` */
  const labelsInputRef = useRef<HTMLInputElement>(null)
  /** 즐겨찾기 토글 버튼 — 단축키 `s` */
  const favoriteToggleRef = useRef<HTMLButtonElement>(null)
  /** 관심(watch) 토글 버튼 — 단축키 `w` */
  const watchToggleRef = useRef<HTMLButtonElement>(null)
  /** 댓글 작성 textarea — 단축키 `m` */
  // WYSIWYG 전환으로 대상이 contenteditable 컨테이너가 됐다 — `.focus()` 는 그대로 동작한다.
  const commentInputRef = useRef<HTMLDivElement>(null)

  /** 활동 영역 활성 탭 — 소유 이유와 딥링크 규칙은 {@link useActivityTab} 참조. */
  const [activityTab, setActivityTab] = useActivityTab(focusCommentId)

  const { data: issue, isLoading, error } = useQuery({
    queryKey: issueQueryKey(issueKey),
    queryFn: async () => {
      try {
        return await fetchIssue(issueKey)
      } catch (err: unknown) {
        // 308 옛 키 redirect — pane이면 콜백 위임, 아니면 새 키 라우트로 교체 이동
        if (err instanceof IssueRedirectError) {
          if (variant === 'pane' && onIssueRedirect !== undefined) {
            onIssueRedirect(err.newKey)
          } else {
            void navigate({
              to: '/issues/$key' as string,
              params: { key: err.newKey },
              replace: true,
            })
          }
          // navigate/콜백 이후에도 query를 pending 상태로 유지하기 위해 re-throw
          // (undefined를 반환하면 이슈 없음 UI가 잠깐 렌더될 수 있다)
          throw err
        }
        throw err
      }
    },
    retry: false,
  })

  usePaneFocusOnLoad(variant, issue !== undefined, paneTitleRef)
  usePaneEscapeClose(variant, onClose)
  // usePaneFocusOnLoad 와 충돌하지 않는다 — 그쪽은 hasFocusedRef 로 **데이터 로드 시 1회만**
  // 발화하고 끝나므로, 그 뒤에 열리는 편집 포커스와 시점이 겹치지 않는다.
  useTitleEditFocus(isEditingTitle, titleInputRef, titleButtonRef)

  // ── 최근 본 이슈 기록 (FR-UX-08 PR-B FR4) ──────────────────────────────────
  /**
   * 이 앱에서 최근 본 이슈를 기록하는 **유일한 지점**이다.
   *
   * 기록 지점을 늘리면 저장값 생산 지점이 둘이 되어, 가드가 한쪽에만 붙는 결함이 재발한다
   * (FR-UX-07 코드리뷰 CR3). 사이드바 "최근 항목"은 이 스토어를 **읽기만** 한다.
   *
   * **조회 성공 후에만 기록한다.** `issue`가 확정된 뒤에 돌므로 404/403 키는 들어오지 않는다 —
   * 조회 전에 기록하면 죽은 키가 목록을 오염시키고 그 키는 다음 마운트에서 또 실패한다.
   * 308 옛 키 redirect도 `queryFn`이 re-throw하므로 `issue`가 확정되지 않고, 새 키로 이동한
   * 뒤 그쪽 마운트에서 **새 키가** 기록된다(옛 키는 기록되지 않는다).
   *
   * `props.issueKey`가 아니라 **응답의 `issue.key`**를 쓴다 — 둘이 갈리는 경우(대소문자·리다이렉트)
   * 저장값은 언제나 백엔드가 인정한 키여야 사이드바의 제목 조회가 성공한다.
   */
  const pushRecentIssue = useRecentIssues((s) => s.pushRecentIssue)
  useEffect(() => {
    if (issue === undefined) return
    pushRecentIssue(issue.key)
  }, [issue, pushRecentIssue])

  // 권한 조회 — fail-closed: 로딩 중·에러·미확정이면 false(비활성)
  const {
    data: permissionsData,
    isLoading: isPermissionsLoading,
    isError: isPermissionsError,
  } = useIssuePermissions(issueKey)
  const canEdit =
    !isPermissionsLoading && !isPermissionsError && permissionsData?.permissions.UPDATE === true

  const { data: availableTypes = [] } = useIssueTypes()
  /** S2: debounce 적용 — 1000명 규모 매 키스트로크 요청 방지 (250ms) */
  const debouncedAssigneeSearchQuery = useDebounce(assigneeSearchQuery, 250)
  const { data: users = [] } = useUsers(debouncedAssigneeSearchQuery)

  /**
   * 담당자 후보는 **검색해야 나온다**.
   *
   * `useUsers` 는 빈 검색어에 전체 사용자 목록을 돌려준다(훅 KDoc 참조 — 필터 계열
   * 소비처가 그 동작에 의존하므로 훅에 전역 `enabled` 를 달 수 없다). 그대로 넘기면
   * 담당자 칸을 여는 순간 사내 1,000명이 펼쳐진다.
   *
   * 판정 표현을 생성 폼의 정본 처방과 **문자 그대로 동일**하게 둔다 —
   * `components/issue/create/use-assignee-picker.ts:58-61`. 두 화면이 같은 규칙임을
   * grep 으로 확인할 수 있어야 한다.
   *
   * `currentAssignee`(useUsersByIds 경로)와 `canEdit` 은 이 배열과 독립적이라
   * 여기서 걸러도 현재 담당자 표시·읽기 전용 경로는 영향받지 않는다.
   */
  const assigneeCandidates = useMemo(
    () => (debouncedAssigneeSearchQuery.trim() === '' ? [] : users),
    [debouncedAssigneeSearchQuery, users],
  )

  // 담당자·보고자 이름은 검색 결과가 아니라 id 조회로 확보한다 — 근거는 훅 KDoc (C1·C3 회귀).
  const { currentAssignee, reporter } = useIssuePeople(issue?.assigneeId, issue?.reporterId)

  const changeAssigneeMutation = useChangeAssignee()
  const changeComponentsMutation = useChangeComponents()
  const changeSecurityLevelMutation = useChangeSecurityLevel()
  const changeAffectsVersionsMutation = useChangeAffectsVersions()
  const changeFixVersionsMutation = useChangeFixVersions()

  /** 프로젝트 컴포넌트 목록 — issue 로드 후 projectKey 기준으로 조회 */
  const { data: projectComponents = [] } = useQuery({
    queryKey: ['components', issue?.projectKey],
    queryFn: () => fetchComponents(issue!.projectKey),
    enabled: issue !== undefined,
    staleTime: 60_000,
  })

  /** 프로젝트 버전 목록 — issue 로드 후 projectKey 기준으로 조회 */
  const { data: projectVersions = [] } = useVersions(issue?.projectKey ?? '')

  /** 커스텀 필드 정의 목록 — changelog refs 주입용. issue 로드 후 활성화 */
  const { data: customFieldDefinitions = [] } = useCustomFields(
    issue?.projectKey ?? '',
    { enabled: issue !== undefined },
  )

  const {
    data: transitions = [],
    isError: isTransitionsError,
    error: transitionsError,
  } = useIssueTransitions(issueKey)

  // 스펙 E5: 422(워크플로우 미설정) vs 200+빈 배열(종료상태) 구분
  const transitionUnavailableReason = resolveTransitionUnavailableReason({
    isError: isTransitionsError,
    error: transitionsError,
    transitionCount: transitions.length,
  })

  // 전환 실행 배선 전부 — mutation · 에러 토스트 · 409 후보 선택 (ADR 2026-08-18 §D3)
  const transitionFlow = useIssueTransitionFlow(issueKey)

  const updateMutation = useUpdateIssueSummary()
  const deleteMutation = useDeleteIssue({
    onSuccess: () => {
      // pane이면 콜백 위임, 아니면 fullscreen navigate — '/issues' 경로는 router.ts 등록
      // 완료 시 타입 추론됨 — 현재 string cast로 우회
      if (variant === 'pane' && onIssueClosed !== undefined) {
        onIssueClosed()
      } else {
        void navigate({ to: '/issues' as string })
      }
    },
  })

  /**
   * 메타필드 mutation 공통 onError 처리기.
   * - 409 → versionConflictError toast + invalidate (최신 데이터 재조회 유도)
   * - 그 외 → 호출자가 전달한 fallbackMsg toast
   */
  function handleMetaMutationError(err: unknown, fallbackMsg: string) {
    const kind = classifyMetaMutationError(err)
    if (kind === 'version-conflict') {
      toast.error(issueDetailStrings.versionConflictError)
      void invalidateIssueViews(queryClient, issueKey)
      return
    }
    // 커스텀 필드 검증 실패는 **재시도해도 안 되는** 입력 문제라 폴백 문구
    // (「잠시 후 다시 시도해 주세요」)가 거짓말이 된다.
    //
    // ★특히 진단 불가한 경로가 있다. 백엔드는 **활성 정의 전량**을 기준으로 병합·검증하는데
    //   (`IssueApplicationService.mergeCustomFieldsAndValidate`) 클라이언트는 열람 권한이 없는
    //   필드를 **렌더도 검증도 하지 않는다** — 그 값은 응답에서 아예 마스킹돼 온다
    //   (`IssueResponse.maskInvisible`). 그래서 그 필드가 required 이고 비어 있으면
    //   사용자는 **화면에 없는 필드 때문에** 저장에 영원히 실패하면서 이유를 알 수 없다.
    //   클라이언트가 그 상태를 **판정할 수는 없다**(값이 마스킹돼 있어 「비어 있음」과
    //   「채워져 있음」이 구분되지 않는다). 할 수 있는 것은 원인을 짐작할 단서를 주는 것뿐이다.
    if (kind === 'custom-field-invalid') {
      toast.error(issueDetailStrings.customFieldValidationError)
      return
    }
    toast.error(fallbackMsg)
  }

  const typeChangeMutation = useMutation({
    mutationFn: ({ typeId, expectedVersion }: { typeId: number; expectedVersion: number }) =>
      updateIssue(issueKey, { typeId, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void invalidateIssueViews(queryClient, issueKey)
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.typeChangeError)
    },
  })

  // ★HTML 로 보낸다(V039) — 마크다운 `description` 과 배타다. 근거는 UpdateIssueInput KDoc.
  const descriptionMutation = useMutation({
    mutationFn: (v: { descriptionHtml: string; expectedVersion: number }) => updateIssue(issueKey, v),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void invalidateIssueViews(queryClient, issueKey)
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.typeChangeError)
    },
  })

  const priorityMutation = useMutation({
    mutationFn: ({ priority, expectedVersion }: { priority: number; expectedVersion: number }) =>
      updateIssue(issueKey, { priority, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void invalidateIssueViews(queryClient, issueKey)
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.priorityChangeError)
    },
  })

  const impactMutation = useMutation({
    mutationFn: ({ impact, expectedVersion }: { impact: number; expectedVersion: number }) =>
      updateIssue(issueKey, { impact, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void invalidateIssueViews(queryClient, issueKey)
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.impactChangeError)
    },
  })

  const environmentMutation = useMutation({
    mutationFn: ({ environment, expectedVersion }: { environment: string; expectedVersion: number }) =>
      updateIssue(issueKey, { environment, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void invalidateIssueViews(queryClient, issueKey)
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.environmentSaveError)
    },
  })

  const labelsMutation = useMutation({
    mutationFn: ({ labels, expectedVersion }: { labels: string[]; expectedVersion: number }) =>
      updateIssue(issueKey, { labels, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — PATCH 응답의 descriptionHtml은 항상 null이라
      // 본문이 placeholder로 깜빡인다. invalidate 후 단건 GET refetch가 모든 필드를 정확히 채운다.
      void invalidateIssueViews(queryClient, issueKey)
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, issueDetailStrings.labelsSaveError)
    },
  })

  const customFieldsMutation = useMutation({
    mutationFn: ({ customFields, expectedVersion }: { customFields: CustomFieldValues; expectedVersion: number }) =>
      updateIssue(issueKey, { customFields, expectedVersion }),
    onSuccess: () => {
      // C2: setQueryData(updatedIssue) 금지 — invalidate-only로 최신 데이터 refetch
      void invalidateIssueViews(queryClient, issueKey)
    },
    onError: (err: unknown) => {
      handleMetaMutationError(err, '커스텀 필드 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
    },
  })

  // ── FR-UX-10 F11 — 상세 액션 단축키 7종 (`.` 은 app-shell 소관) ─────────────

  /** 현재 로그인 사용자 id — `i`(나에게 할당)가 쓴다. 미인증이면 null */
  const currentUserId = useAuthUser()?.userId ?? null

  /**
   * `i` 가 마지막으로 발행한 요청의 서명 — `«이슈 버전»:«대상 담당자»`. 같은 서명이 또 오면 버린다.
   *
   * ★state 가 아니라 **ref** 인 것이 요점이다. 브라우저 키 auto-repeat 는 한 tick 안에서
   * keydown 을 여러 번 흘리는데 그 사이에는 렌더가 없다. `changeAssigneeMutation.isPending`
   * 같은 state 로 막으려 하면 반복 호출이 전부 같은(아직 false 인) 클로저를 보고 통과한다.
   *
   * ★해제 기준이 「요청 종료」가 아니라 **버전 변화**인 이유. `useChangeAssignee` 는
   * `onSettled` 에서 invalidate 만 하므로(setQueryData 금지 — descriptionHtml 플리커 전력)
   * 응답이 와도 `issue.version` 은 재조회가 도착할 때까지 옛 값이다. 그 창에서 재입력을
   * 허용하면 같은 `expectedVersion` 이 또 나가 409 「버전 충돌」 토스트만 쌓인다.
   * 버전이 실제로 움직였을 때만 다음 요청을 허용하면 그 창이 사라진다.
   */
  const lastAssignToMeRequestRef = useRef<string | null>(null)

  /**
   * 단축키가 이 필드를 조작해도 되는가 — **열람 숨김과 수정 금지 두 목록을 모두** 본다.
   *
   * 🛑 한쪽만 보는 판정으로 줄이지 마라. 백엔드 `buildNoneditableKeys` 가 `restrictedSet` 을
   * filter 하므로 **열람 숨김 필드는 수정 금지 목록에 절대 오지 않는다.** 그래서
   * `noneditableFields` 만 보는 구현은 화면에서 숨긴 필드를 단축키가 서버로 밀어 넣어 403 과
   * 원인 불명 토스트를 만들고, `restrictedFields` 만 보는 구현은 수정 금지 필드를 열어 준다.
   * 두 목록이 배타적이라 **한쪽만 보면 다른 쪽 시나리오에서 그대로 뚫린다**
   * (PR #338 F9 가 실제로 낸 결함).
   *
   * `lib/` 로 올리지 않는 이유. 목록 셀 인라인 편집과 공유할 후속 항목이 이미 F9 에 잡혀
   * 있어, 여기서 먼저 올리면 그 정리와 충돌한다.
   *
   * @param fieldKey 판정할 필드 키 (`assigneeId` · `labels` · `summary`)
   * @returns 단축키로 조작해도 되면 true. 이슈 미로드·열람 숨김·수정 금지면 false
   */
  function canUseField(fieldKey: string): boolean {
    if (issue === undefined) return false
    if (isFieldHidden(fieldKey, issue.restrictedFields)) return false
    return !isFieldDisabled(fieldKey, canEdit, issue.noneditableFields)
  }

  /**
   * 댓글 작성 입력으로 포커스를 옮긴다 — 필요하면 댓글 탭을 먼저 연다 (단축키 `m`).
   *
   * ★`flushSync` 가 이 함수의 요점이다. Radix Tabs 는 **비활성 탭 콘텐츠를 언마운트**하고
   * (기본 활성 탭은 「이력」), 활성으로 바뀐 첫 커밋에도 아직 콘텐츠를 붙이지 않는다 —
   * `Presence` 가 layout effect 로 상태를 한 번 더 밀어 그다음 커밋에 마운트한다. 그런데 그
   * 커밋은 **이 라우트를 다시 렌더하지 않으므로** 여기의 effect 로는 마운트 시점을 관측할 수
   * 없다(실측: effect 가 `ref.current === null` 을 보고 끝난다). `flushSync` 로 탭 전환 렌더를
   * 그 자리에서 끝내면 반환 시점에 textarea 가 DOM 에 있어 포커스를 바로 줄 수 있다.
   *
   * 이 함수는 React 이벤트가 아니라 document keydown 리스너에서 호출되므로 렌더 중
   * `flushSync` 경고 대상이 아니다.
   *
   * `canUpdate=false` 여서 작성 폼이 없으면 ref 가 계속 null 이고 옵셔널 체이닝이 무동작으로
   * 처리한다.
   */
  function focusCommentInput(): void {
    if (activityTab !== ACTIVITY_TABS.COMMENT) {
      flushSync(() => setActivityTab(ACTIVITY_TABS.COMMENT))
    }
    commentInputRef.current?.focus()
  }

  /**
   * 이 라우트가 **직접 소유한** 차단 상태를 모달 레지스트리에 보고한다.
   *
   * 아래 3종은 `open` 을 prop 으로 내려받는 자식이라 자기 파일에서 보고할 값이 없고,
   * 삭제 확인은 Dialog 가 아니라 메타패널 자리를 대체하는 `<aside>` 라 애초에 모달이
   * 아니다 — 그래서 이 네 가지만 여기서 보고한다. 나머지 모달은 **자기가 보고한다**.
   */
  useReportModalOpen(
    pendingDoneTransition !== null || cloneDialogOpen || moveDialogOpen || confirmDelete,
  )

  /**
   * 이 화면 어딘가에 모달이 열려 있는가 — 게이트가 읽는 **단 하나의** 신호.
   *
   * 게이트 식에 `useHasOpenModal()` 을 직접 넣지 않는다. `&&` 는 단락 평가라 앞 항이
   * false 인 렌더에서 훅 호출이 통째로 건너뛰어지고, 그건 렌더마다 훅 개수가 달라지는
   * 조건부 훅이다(로딩 → 로드 완료 전환에서 바로 터진다).
   */
  const hasOpenModal = useHasOpenModal()

  /**
   * 상세 액션 단축키를 **등록할지** 여부 (ADR D-5-a).
   *
   * 콜백만 끊으면 판별이 성공해 `preventDefault` 까지 한 뒤 아무 일도 일어나지 않는다.
   * 모달은 입력 요소가 없으면 `shouldIgnoreEvent` 를 그냥 통과하므로 여기서 막아야 한다(E2).
   *
   * ★🛑 여기에 모달을 **다시 열거하지 마라.** 원래 이 식은 모달 4종을 손으로 나열했는데,
   * 이 페이지가 실제로 렌더하는 모달은 6종이었다 — 빠진 둘(댓글 삭제 확인 · 첨부 미리보기)
   * 위에서 `i` 가 담당자 PATCH 를 실제로 발행했다(리뷰 실측). 열거를 2건 늘리는 처방은
   * **다음 모달이 생기는 순간 똑같이 뚫린다**. 그래서 판정을 「내가 아는 모달이 열렸나」에서
   * 「무엇이든 열렸나」로 뒤집었다 — 새 모달은 `useReportModalOpen` 한 줄이면 자동으로 막히고,
   * 그 한 줄을 빠뜨렸는지는 `routes/__tests__/issue-detail-modal-gate.test.ts` 가
   * 소스 전수 스캔으로 되잰다(차집합 0).
   */
  const detailShortcutsEnabled = issue !== undefined && error === null && !hasOpenModal

  useContextShortcuts(
    'issue-detail',
    {
      // 필드 권한 판정은 전부 `canUseField` 한 곳을 지난다 — 두 목록을 모두 보는 규칙이
      // 키마다 흩어지면 한 키만 뒤처져 조용히 뚫린다(그 함수의 🛑 주석 참조).
      onFocusAssignee: () => {
        if (!canUseField('assigneeId')) return
        assigneeSearchRef.current?.focus()
      },
      onAssignToMe: () => {
        if (issue === undefined) return
        if (!canUseField('assigneeId')) return
        if (currentUserId === null) return // E7 — 미인증이면 누구에게 할당할지 알 수 없다
        // Jira 문구가 `Toggle` 이다 — 이미 나면 해제한다. 저장 경로는 기존 핸들러 재사용.
        const nextAssigneeId = issue.assigneeId === currentUserId ? null : currentUserId
        // ★중복 발행 금지(E8). `s`/`w` 는 버튼이 가진 진행 중 판정을 재사용하는데 `i` 만
        //   그 짝이 없어, 키를 누르고 있으면 같은 expectedVersion 으로 N건이 나갔다
        //   (1건 200 · 나머지 409 → 원인 불명 「버전 충돌」 토스트 N-1개). 판정 근거는
        //   lastAssignToMeRequestRef 의 주석 참조.
        const requestSignature = `${issue.version}:${nextAssigneeId ?? 'null'}`
        if (lastAssignToMeRequestRef.current === requestSignature) return
        lastAssignToMeRequestRef.current = requestSignature
        handleAssigneeChange(nextAssigneeId)
      },
      onFocusComment: focusCommentInput,
      onEditTitle: () => {
        if (!canUseField('summary')) return
        // ★이미 편집 중이면 **다시 열지 않는다.** `handleEditStart()` 는 입력값을
        //   `issue.summary` 로 되돌리므로, 입력창 밖을 클릭해 blur 시킨 뒤 `e` 를 다시 누르면
        //   고쳐 쓰던 제목이 조용히 사라진다(입력창 안에서는 shouldIgnoreEvent 가 삼켜서
        //   blur 를 거쳐야만 닿는 좁은 경로다). 게이트(detailShortcutsEnabled)가 아니라
        //   여기서 막는 이유 — 게이트에 넣으면 편집 중에 `s`/`w`/`m` 까지 전부 죽는다.
        //   문제는 `e` 하나뿐이므로 차단 범위도 `e` 하나여야 한다.
        if (isEditingTitle) {
          // 무동작으로 끝내면 「아무 일도 안 일어났다」와 구분되지 않는다 —
          // `a`/`l`/`m` 과 같은 「그 컨트롤로 간다」 규칙을 지켜 입력창으로 되돌린다.
          titleInputRef.current?.focus()
          return
        }
        // 전용 진입 함수를 탄다 — `setIsEditingTitle(true)` 만 하면 입력창이 빈 값으로 열려
        // Enter 한 번에 제목이 지워진다.
        handleEditStart()
      },
      onFocusLabels: () => {
        if (!canUseField('labels')) return
        labelsInputRef.current?.focus()
      },
      // ★F-2 — 포커스를 **먼저** 옮기고 누른다. 순서가 접근성의 전부다. 두 버튼은
      // `aria-pressed` 를 올바로 갖고 있지만 포커스가 없으면 스크린리더가 그 변화를 읽지
      // 않고, 성공 토스트도 `aria-live` 영역도 없어서 화면을 못 보는 사용자에게는
      // **아무 일도 안 일어난 것과 구분되지 않는다.**
      //
      // `.click()` 으로 미는 이유. 두 버튼이 이미 가진 진행 중 판정과 토스트 처리를
      // 재사용한다 — 복제하면 규칙이 두 벌이 되어 어긋난다.
      //
      // 🛑 중복 발행 금지(E8)의 증인이 **어디에 있는지 착각하지 마라.** 예전 주석은
      //    "`disabled` 버튼의 `click()` 은 브라우저가 무시하므로 공짜로 성립한다" 고 적혀
      //    있었는데, Task-5b 가 두 버튼에서 네이티브 `disabled` 를 벗기고 `aria-disabled` 로
      //    바꾼 순간(포커스 보존 목적) **그 문장은 거짓이 됐다** — 브라우저는 이제 이
      //    `.click()` 을 막지 않는다. 지금 막는 것은 각 컴포넌트의 첫 줄 가드다.
      //      · 즐겨찾기 — `FavoriteButton.handleClick` 의 `if (isMutating) return`
      //      · 관심     — `WatchersSection.handleToggle` 의 `if (!canToggle) return`
      //    짝 테스트도 그쪽에 있다(`FavoriteButton.test.tsx` S7c · `WatchersSection.test.tsx` S9c).
      //    두 컴포넌트 자기 파일의 주석은 이미 이 사실을 정확히 적고 있다.
      onToggleFavorite: () => {
        favoriteToggleRef.current?.focus()
        favoriteToggleRef.current?.click()
      },
      onToggleWatch: () => {
        watchToggleRef.current?.focus()
        watchToggleRef.current?.click()
      },
    },
    detailShortcutsEnabled,
  )

  // ── 로딩 상태 ──────────────────────────────────────────────────────────────
  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">
        {issueDetailStrings.loading}
      </div>
    )
  }

  // ── 에러 / 미존재 상태 ────────────────────────────────────────────────────
  if (error !== null || issue === undefined) {
    return (
      <div role="alert" className="p-8 text-destructive">
        {issueDetailStrings.notFound}
      </div>
    )
  }

  // ── 편집 모드 핸들러 ──────────────────────────────────────────────────────
  function handleEditStart() {
    setEditSummary(issue?.summary ?? '')
    setIsEditingTitle(true)
  }

  function handleEditCancel() {
    setIsEditingTitle(false)
    setEditSummary('')
  }

  /**
   * 제목 텍스트 클릭 → 편집 진입 (FR1). 본문(`IssueDescription.handleContentClick`)과
   * **같은 판정식**으로 텍스트 선택 중을 배제한다 (E2 / 편차 D-2).
   *
   * 드래그로 제목을 복사하려는 참이면 열지 않는다 — Jira Cloud 는 이 경우에도 편집이
   * 열려 선택이 날아가는 미해결 결함이 있다(JRA-64389 · JRA-29063). 결함까지 복제하지 않는다.
   *
   * 본문에 있는 `closest('a')` 배제는 **여기선 불필요하다 (실측)** — 제목은
   * `{issue.summary}` 문자열이 텍스트 노드로만 들어가고 이 파일에 `dangerouslySetInnerHTML`
   * 이 0건이라 버튼 안에 앵커가 생길 경로가 없다. 없는 경우를 위한 가드는 두지 않는다.
   */
  function handleTitleClick() {
    if (window.getSelection()?.isCollapsed === false) return
    handleEditStart()
  }

  function handleEditSave() {
    if (issue === undefined) return
    updateMutation.mutate(
      { key: issue.key, summary: editSummary, expectedVersion: issue.version },
      { onSuccess: () => setIsEditingTitle(false) },
    )
  }

  /**
   * 제목 편집 입력창의 키 처리 — Enter 저장 / Esc 취소 (FR2/FR3).
   *
   * 저장·취소는 버튼과 **같은 핸들러**를 탄다. 새 저장 경로를 만들면
   * useUpdateIssueSummary 의 OCC(낙관적 동시성 제어)·409 롤백·toast 를 승계하지 못한다.
   */
  function handleTitleKeyDown(e: ReactKeyboardEvent<HTMLInputElement>) {
    // E4 — 한글 IME(입력기) 조합을 확정하는 Enter 를 저장으로 오인하지 않는다.
    // keyCode 229 는 조합 중 keydown 을 쓰는 브라우저용 이중 방어.
    if (e.nativeEvent.isComposing || e.keyCode === 229) return

    if (e.key === 'Enter') {
      e.preventDefault()
      // E5 — 저장 진행 중이거나 권한이 없으면 중복 제출을 막는다 (저장 버튼 disabled 와 동일 조건)
      if (updateMutation.isPending || !canEdit) return
      handleEditSave()
    } else if (e.key === 'Escape') {
      // ★ E1 — 이 preventDefault 가 usePaneEscapeClose(:86)의 defaultPrevented 가드를 세운다.
      // 지우면 pane 에서 Esc 한 번에 편집 취소와 패널 닫기가 동시 발화한다.
      e.preventDefault()
      handleEditCancel()
    }
  }

  // ── 타입 변경 핸들러 ─────────────────────────────────────────────────────
  function handleTypeChange(typeId: number) {
    if (issue === undefined) return
    typeChangeMutation.mutate({ typeId, expectedVersion: issue.version })
  }

  // ── 본문 저장 핸들러 ─────────────────────────────────────────────────────
  function handleDescriptionSave(html: string) {
    if (issue === undefined) return
    descriptionMutation.mutate({ descriptionHtml: html, expectedVersion: issue.version })
  }

  // ── 우선순위 변경 핸들러 ─────────────────────────────────────────────────
  function handlePriorityChange(priority: number) {
    if (issue === undefined) return
    priorityMutation.mutate({ priority, expectedVersion: issue.version })
  }

  // ── 영향도 변경 핸들러 ───────────────────────────────────────────────────
  function handleImpactChange(impact: number) {
    if (issue === undefined) return
    impactMutation.mutate({ impact, expectedVersion: issue.version })
  }

  // ── 환경 저장 핸들러 ─────────────────────────────────────────────────────
  function handleEnvironmentSave(environment: string) {
    if (issue === undefined) return
    environmentMutation.mutate({ environment, expectedVersion: issue.version })
  }

  // ── 라벨 저장 핸들러 ─────────────────────────────────────────────────────
  function handleLabelsSave(labels: string[]) {
    if (issue === undefined) return
    labelsMutation.mutate({ labels, expectedVersion: issue.version })
  }

  // ── 커스텀 필드 저장 핸들러 (FR-IS-10) ───────────────────────────────────
  function handleCustomFieldsSave(customFields: CustomFieldValues) {
    if (issue === undefined) return
    customFieldsMutation.mutate({ customFields, expectedVersion: issue.version })
  }

  // ── 담당자 변경 핸들러 ───────────────────────────────────────────────────
  function handleAssigneeChange(userId: string | null) {
    if (issue === undefined) return
    changeAssigneeMutation.mutate({ key: issue.key, assigneeId: userId, expectedVersion: issue.version })
  }

  // ── 보안등급 변경 핸들러 ─────────────────────────────────────────────────
  function handleSecurityLevelChange(levelId: string | null) {
    if (issue === undefined) return
    changeSecurityLevelMutation.mutate({
      key: issue.key,
      securityLevelId: levelId,
      expectedVersion: issue.version,
    })
  }

  // ── 컴포넌트 변경 핸들러 ─────────────────────────────────────────────────
  /**
   * 컴포넌트 다중 할당 변경 핸들러.
   * useChangeComponents mutation을 통해 PATCH /api/v1/issues/{key}/components 호출.
   * expectedVersion은 현재 issue.version을 사용한다 (OCC 낙관락).
   *
   * componentIds는 issueResponseSchema에 추가되어 issue.componentIds로 직접 접근한다(백엔드 단건 응답이 채움).
   *
   * @param ids 새로 할당할 컴포넌트 UUID 배열
   */
  function handleComponentsChange(ids: string[]) {
    if (issue === undefined) return
    changeComponentsMutation.mutate({ key: issue.key, componentIds: ids, expectedVersion: issue.version })
  }

  // ── 영향 버전 변경 핸들러 ─────────────────────────────────────────────────
  function handleAffectsVersionsChange(ids: string[]) {
    if (issue === undefined) return
    changeAffectsVersionsMutation.mutate({ key: issue.key, versionIds: ids, expectedVersion: issue.version })
  }

  // ── 수정 버전 변경 핸들러 ─────────────────────────────────────────────────
  function handleFixVersionsChange(ids: string[]) {
    if (issue === undefined) return
    changeFixVersionsMutation.mutate({ key: issue.key, versionIds: ids, expectedVersion: issue.version })
  }

  // ── 상태전환 핸들러 ───────────────────────────────────────────────────────

  /**
   * 전환 선택 핸들러.
   * - toCategory === 'DONE': Resolution 모달을 오픈하고 전환을 대기한다.
   * - 그 외: 즉시 전환 실행 (resolutionId 없음).
   *
   * @param transition 셀렉터가 고른 전환 — `toStateKey` 로 목록을 되훑지 않는다
   *   ({@link transitionIdOf} 가 식별 규칙의 정본). 되훑으면 같은 상태쌍의 첫 전환이 잡혀
   *   사용자가 고른 것과 다른 전환의 `toCategory` 로 DONE 여부를 판단한다.
   */
  function handleTransition(transition: IssueTransition) {
    if (issue === undefined) return
    if (transition.toCategory === 'DONE') {
      // DONE 전환 → 모달 오픈 후 resolution 선택 대기
      setPendingDoneTransition(transition)
    } else {
      // 비DONE 전환 → 즉시 실행
      transitionFlow.mutate({ toStatusKey: transition.toStateKey, expectedVersion: issue.version, transitionId: transitionIdOf(transition) })
    }
  }

  /**
   * Resolution 모달 확인 핸들러.
   * 모달에서 선택된 resolutionId를 포함해 전환을 실행한다.
   */
  function handleResolutionConfirm(resolutionId: string) {
    if (issue === undefined || pendingDoneTransition === null) return
    transitionFlow.mutate({
      toStatusKey: pendingDoneTransition.toStateKey,
      expectedVersion: issue.version,
      resolutionId,
      transitionId: transitionIdOf(pendingDoneTransition),
    })
    setPendingDoneTransition(null)
  }

  /**
   * Resolution 모달 취소 핸들러.
   * 대기 중인 전환을 취소하고 모달을 닫는다.
   */
  function handleResolutionCancel() {
    setPendingDoneTransition(null)
  }

  // ── 삭제 핸들러 ───────────────────────────────────────────────────────────
  function handleDeleteClick() {
    setConfirmDelete(true)
  }

  function handleDeleteConfirm() {
    if (issue === undefined) return
    deleteMutation.mutate(issue.key)
  }

  function handleDeleteCancel() {
    setConfirmDelete(false)
  }

  /**
   * PDF 다운로드 핸들러.
   *
   * GET /api/v1/issues/{key}/pdf → Blob → 브라우저 앵커 click 다운로드.
   * 진행 중 버튼 disabled, 실패 시 toast.error.
   * GET 요청이므로 CSRF 토큰 불요.
   */
  async function handlePdfDownload(issueKey: string) {
    setIsPdfDownloading(true)
    try {
      const blob = await downloadIssuePdf(issueKey)
      triggerBlobDownload(blob, `${issueKey}.pdf`)
    } catch {
      toast.error(issueDetailStrings.pdfDownloadError)
    } finally {
      setIsPdfDownloading(false)
    }
  }

  const changelogRefs = buildChangelogRefs(availableTypes, projectComponents, projectVersions, customFieldDefinitions)

  // ── 성공 레이아웃 ─────────────────────────────────────────────────────────
  //
  // ## 3영역 — 고정 헤더 · 본문 스크롤 · 메타 스크롤 (Jira 패리티 J25~J27)
  //
  // Atlassian 이 이슈 뷰의 오른쪽을 「the right scroll area on an issue」라고 부른다
  // (developer.atlassian.com/cloud/jira/platform/issue-view/ · 2026-09-07 조회) — 오른쪽
  // 필드 열은 본문과 **별개의 스크롤 영역**이다. 종전 BTS 는 격자 하나가 통째로
  // `ShellLayout` 의 `<main overflow-y-auto>` 안에서 흘러 스크롤이 하나였고, 긴 본문을
  // 읽으려 내리면 담당자·상태 같은 메타가 화면 밖으로 사라졌다.
  //
  // ★높이를 받는 방식이 표시 방식마다 다르다(2026-09-07 실측 · 뮤테이션으로 확정).
  //   - 전체화면. 부모 `<main>` 의 높이가 `h-screen` 사슬로 **확정**돼 있어 `h-full`(=100%)이
  //     해소된다. 이 자리에서 `h-full` 을 빼면 바깥이 스크롤한다.
  //   - 모달·사이드패널. 껍데기는 `max-h-[90vh]` 뿐 **확정 높이가 없다.** 그런 컨테이너의
  //     자식에게는 백분율이 해소되지 않아 `h-full` 이 조용히 `auto` 로 떨어진다 — 실측에서
  //     루트가 414px 대신 **2646px** 로 늘어 안쪽 스크롤이 통째로 죽었다.
  //     대신 껍데기 안쪽 래퍼가 `flex flex-col` 이라 이 루트가 **flex 축소로** 높이를 받는다
  //     (`min-h-0` 이 콘텐츠 높이 아래로 줄어드는 것을 허용한다).
  //   ★여기에 `flex-1` 을 더하고 싶어지지만 **불필요하다.** 뮤테이션으로 확인했다 — 지워도
  //     E2E 5/5 초록이고, 같은 실험에서 래퍼의 `flex flex-col` 을 지우자 red 가 났다.
  //     실제로 값을 하는 것은 래퍼 쪽이다.
  //
  // ★`min-h-0` 이 빠지면 flex 자식의 최소 높이가 콘텐츠 높이가 되어 컨테이너가 늘어나고,
  //   안쪽 `overflow-y-auto` 는 선언돼 있어도 **한 번도 발동하지 않는다**.
  //   같은 조합을 `routes/issues.index.tsx` 의 split view 가 먼저 쓰고 있다.
  //
  // ★세로 여백을 루트가 아니라 **각 영역**이 갖는다. 루트에 `py-10` 을 두면 그 여백이
  //   스크롤 영역 **밖**에 남아 스크롤 가능 높이를 그만큼 깎는다.
  //
  // ★독립 스크롤 전체를 `lg:` 로 건다 — **2단 격자와 같은 분기**여야 한다. `lg:` 미만에서는
  //   격자가 1열 2행이 되는데 그때도 두 행이 각자 스크롤하면 좁은 화면에서 **각각 반쪽짜리
  //   구멍**이 된다(900×700 실측 — 메타 2459px 를 229px 창으로 본다). 그 폭에서는 종전처럼
  //   `<main overflow-y-auto>` 가 한 덩어리로 흐르게 둔다.
  //   좁은 폭은 미지원이 아니다 — `routes/issues.index.tsx` 가 `(min-width: 1024px)` 미만을
  //   명시적 지원 모드로 선언하고 그 모드의 행 클릭이 **이 화면**으로 온다.
  return (
    <div className="mx-auto flex w-full max-w-[960px] flex-col px-6 pt-10 lg:h-full lg:min-h-0">
      <IssueDetailHeader
        issue={issue}
        variant={variant}
        issueKey={issueKey}
        canEdit={canEdit}
        isEditingTitle={isEditingTitle}
        editSummary={editSummary}
        isSavingTitle={updateMutation.isPending}
        isPdfDownloading={isPdfDownloading}
        onEditSummaryChange={setEditSummary}
        onTitleKeyDown={handleTitleKeyDown}
        onTitleClick={handleTitleClick}
        onEditStart={handleEditStart}
        onEditSave={handleEditSave}
        onEditCancel={handleEditCancel}
        onPdfDownload={() => { void handlePdfDownload(issue.key) }}
        onMoveClick={() => setMoveDialogOpen(true)}
        onClose={onClose}
        titleInputRef={titleInputRef}
        titleButtonRef={titleButtonRef}
        paneTitleRef={paneTitleRef}
      />

      {/* 2-컬럼 그리드 — 좌 본문 / 우 메타패널. 두 열이 **각자** 스크롤한다. */}
      <div className="grid grid-cols-1 gap-8 lg:min-h-0 lg:flex-1 lg:grid-cols-[1fr_340px]">
        {/* 좌측 본문 — 설명·첨부·활동.
            ⚠️ `aria-label` 이 「이슈 상세」인데 이 요소는 이제 **본문 열만** 가리킨다(제목·액션은
               헤더로 나갔다). 이름과 범위가 어긋나 있다 — 이름을 바꾸지 않는 이유는 `landmark.spec`
               이 이 문자열로 범위를 좁히고 있어서다. 이 region 안에서 제목을 찾으면 안 된다
               (`issue-update-propagation.spec.ts` 가 실제로 그 함정을 밟았다). */}
        <section
          aria-label="이슈 상세"
          data-testid="issue-detail-body-scroll"
          className="py-6 lg:min-h-0 lg:overflow-y-auto"
        >
          <div>
            <IssueDescription
              descriptionHtml={issue.descriptionHtml}
              issueKey={issue.key}
              onSave={handleDescriptionSave}
              isSaving={descriptionMutation.isPending}
              canEdit={canEdit}
              restrictedFields={issue.restrictedFields}
              noneditableFields={issue.noneditableFields}
            />
          </div>

          {/* 첨부 파일 섹션 — IssueDescription 하단 (FR-AC-01 D6) */}
          <AttachmentSection issueKey={issue.key} canUpdate={canEdit} />

          {/* 활동(댓글/이력/작업로그/연결) — 본문 컬럼 **안**, 첨부 아래 (J2). grid 바깥
              전체폭이던 FR-UX-06 PR19 배치를 되돌렸다 — 근거는 IssueActivityTabs KDoc. */}
          <IssueActivityTabs
            issueKey={issue.key}
            canUpdate={canEdit}
            issue={issue}
            changelogRefs={changelogRefs}
            value={activityTab}
            focusCommentId={focusCommentId}
            onValueChange={setActivityTab}
            commentInputRef={commentInputRef}
          />
        </section>

        {/* 우측 메타패널 + 삭제 확인 UI — Jira 의 「right scroll area」(J26).
            ★스크롤 컨테이너를 **삼항 밖**에 둔다. 두 분기 각각에 두면 판별식·E2E 가 잡을
              `issue-detail-meta-scroll` 이 **둘**이 되고, 그러면 「어느 쪽을 재고 있는가」가
              분기 상태에 따라 달라진다.
            ★종전 주석은 여기에 「스크롤 위치가 유지된다」도 적었는데 **거짓이다**(리뷰 실측).
              삭제 확인 UI 가 짧아 `scrollHeight == clientHeight` 가 되면 브라우저가 `scrollTop`
              을 0 으로 clamp 하고 취소해도 0 이다. 컨테이너를 밖에 둔 것과 무관하다. */}
        <div
          data-testid="issue-detail-meta-scroll"
          className="py-6 lg:min-h-0 lg:overflow-y-auto"
        >
          {confirmDelete ? (
            <aside className="flex flex-col gap-3">
              <div className="border border-destructive/40 rounded-xl p-4 text-sm text-destructive space-y-3">
                <p>{issueDetailStrings.deleteConfirmMessage}</p>
                <div className="flex gap-2">
                  <Button
                    variant="destructive"
                    size="sm"
                    className="flex-1 min-h-[44px]"
                    onClick={handleDeleteConfirm}
                    disabled={deleteMutation.isPending}
                    aria-label={issueDetailStrings.confirmButton}
                  >
                    {issueDetailStrings.confirmButton}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    className="flex-1 min-h-[44px]"
                    onClick={handleDeleteCancel}
                  >
                    {issueDetailStrings.cancelButton}
                  </Button>
                </div>
              </div>
            </aside>
          ) : (
            <div className="flex flex-col gap-4">
              <IssueMetaPanel
                issue={issue}
                availableTypes={availableTypes}
                onTypeChange={handleTypeChange}
                onDeleteClick={handleDeleteClick}
                onCloneClick={() => setCloneDialogOpen(true)}
                transitions={transitions}
                onTransition={handleTransition}
                isTransitioning={transitionFlow.isPending}
                unavailableReason={transitionUnavailableReason}
                onPriorityChange={handlePriorityChange}
                onImpactChange={handleImpactChange}
                onEnvironmentSave={handleEnvironmentSave}
                onLabelsSave={handleLabelsSave}
                users={assigneeCandidates}
                onAssigneeSearch={setAssigneeSearchQuery}
                onAssigneeChange={handleAssigneeChange}
                currentAssignee={currentAssignee}
                reporter={reporter}
                componentIds={issue.componentIds}
                components={projectComponents}
                onComponentsChange={handleComponentsChange}
                versions={projectVersions}
                affectsVersionIds={issue.affectsVersionIds}
                fixVersionIds={issue.fixVersionIds}
                onAffectsVersionsChange={handleAffectsVersionsChange}
                onFixVersionsChange={handleFixVersionsChange}
                onSecurityLevelChange={handleSecurityLevelChange}
                onCustomFieldsSave={handleCustomFieldsSave}
                assigneeSearchRef={assigneeSearchRef}
                labelsInputRef={labelsInputRef}
                favoriteToggleRef={favoriteToggleRef}
                watchToggleRef={watchToggleRef}
              />
              {/* 일정 필드 — FR-PL-01 시작일·마감일·목표일 (IssueMetaPanel 인근 하단 배치) */}
              <div className="border border-border rounded-xl px-3.5 py-3">
                <p className="text-xs text-muted-foreground mb-3">{issueDetailStrings.scheduleLabel}</p>
                <IssueScheduleFields issue={issue} disabled={!canEdit} />
              </div>
              {/* 추정 카드 — FR-TT-01 원 추정·잔여 추정·기록 시간 (design-C1: wrapper는 route가) */}
              <div className="border border-border rounded-xl px-3.5 py-3">
                <p className="text-xs text-muted-foreground mb-3">{worklogStrings.estimateSectionTitle}</p>
                <IssueEstimatePanel issue={issue} disabled={!canEdit} />
              </div>
            </div>
          )}
        </div>
      </div>

      {/* DONE 전환 시 Resolution 선택 모달 (B9) */}
      <ResolutionModal
        open={pendingDoneTransition !== null}
        prefilledResolution={issue.resolution ?? null}
        onConfirm={handleResolutionConfirm}
        onCancel={handleResolutionCancel}
      />

      {/* 모호 전환(409 AMBIGUOUS_TRANSITION) 후보 선택 프롬프트 (ADR 2026-08-18 §D3) */}
      <AmbiguousTransitionPrompt flow={transitionFlow} />

      {/* 이슈 클론 Dialog (FR-IS-06) */}
      <CloneIssueDialog
        issueKey={issue.key}
        open={cloneDialogOpen}
        onOpenChange={setCloneDialogOpen}
      />

      {/* 이슈 이동 마법사 Dialog (FR-MV-01 D6) */}
      <MoveIssueDialog
        issueKey={issue.key}
        issueVersion={issue.version}
        open={moveDialogOpen}
        onOpenChange={setMoveDialogOpen}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueDetailRouteAdapter — router.ts에 등록되는 라우트 어댑터
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 *
 * useParams로 URL의 $key param을 추출하여 IssueDetailPage에 전달한다.
 *
 * 등록 예시.
 * ```ts
 * import { IssueDetailRouteAdapter } from './routes/issues.$key'
 *
 * const issuesKeyRoute = createRoute({
 *   getParentRoute: () => rootRoute,
 *   path: '/issues/$key',
 *   component: IssueDetailRouteAdapter,
 * })
 * ```
 */
export function IssueDetailRouteAdapter(): JSX.Element {
  const { key } = useParams({ strict: false })
  // 인박스 알림을 새 탭/⌘클릭으로 연 경로. 좌클릭은 모달이 가로채 스토어로 넘긴다.
  const { comment } = useSearch({ strict: false }) as { comment?: string }
  return <IssueDetailPage issueKey={key ?? ''} focusCommentId={comment} />
}
