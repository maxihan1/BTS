// 멘션 자동완성 상태 및 이벤트 핸들러 훅 — FR-MN-02 Task 2
import { useState, useRef, useCallback, useEffect, type RefObject, type ReactNode } from 'react'
import { createElement } from 'react'
import { useUsers } from '@/hooks/use-users'
import { useDebounce } from '@/hooks/use-debounce'
import { detectActiveMention, spliceMention } from './mention-detect'
import { MentionDropdown } from './MentionDropdown'
import type { UserSummary } from '@/api/users'
import type {
  ChangeEvent,
  KeyboardEvent,
  CompositionEvent,
  SyntheticEvent,
  FocusEvent,
} from 'react'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 검색어 debounce 지연 시간(ms) */
const DEBOUNCE_DELAY_MS = 250

/** 활성 멘션 감지를 위한 최소 query 길이 */
const MIN_QUERY_LENGTH = 1

/** listbox 기본 id */
const LISTBOX_ID = 'mention-autocomplete-listbox'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * requestAnimationFrame 이후 textarea에 포커스를 두고 caret을 복원한다.
 *
 * F1: 언마운트된 노드에 focus()를 거는 race를 막기 위해 isConnected를 검사한다.
 *
 * @param el - 대상 textarea
 * @param caretPos - 복원할 caret 위치
 */
function restoreCaretAfterFrame(el: HTMLTextAreaElement, caretPos: number): void {
  requestAnimationFrame(() => {
    if (!el.isConnected) return
    el.focus()
    el.setSelectionRange(caretPos, caretPos)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** useMentionAutocomplete 훅 입력 props */
export interface UseMentionAutocompleteProps {
  /** 현재 textarea 전체 텍스트 (controlled) */
  value: string
  /** 텍스트 변경 콜백 — 부모 상태 갱신용 */
  onChange: (next: string) => void
  /** textarea DOM 참조 */
  textareaRef: RefObject<HTMLTextAreaElement | null>
}

/** useMentionAutocomplete 훅 반환값 */
export interface UseMentionAutocompleteReturn {
  /** textarea onChange 핸들러 */
  onChange: (e: ChangeEvent<HTMLTextAreaElement>) => void
  /** textarea onKeyDown 핸들러 */
  onKeyDown: (e: KeyboardEvent<HTMLTextAreaElement>) => void
  /** textarea onCompositionStart 핸들러 */
  onCompositionStart: (e: CompositionEvent<HTMLTextAreaElement>) => void
  /** textarea onCompositionEnd 핸들러 */
  onCompositionEnd: (e: CompositionEvent<HTMLTextAreaElement>) => void
  /** textarea onSelect 핸들러 (커서 이동 감지) */
  onSelect: (e: SyntheticEvent<HTMLTextAreaElement>) => void
  /** textarea onBlur 핸들러 */
  onBlur: (e: FocusEvent<HTMLTextAreaElement>) => void
  /** 드롭다운 ReactNode — open=false면 null */
  mentionDropdown: ReactNode
  /**
   * 멘션 상태를 초기화한다 (드롭다운 닫기 + query/range/activeIndex 리셋).
   * blur 타이머도 취소하므로 탭 전환 등 명시적 이탈 시 타이머 의존 없이 즉시 정리할 수 있다.
   */
  reset: () => void
  /**
   * 다음 `select` 이벤트 **1회**를 사용자 의도가 아닌 것으로 보고 멘션 감지에서 제외한다.
   * 코드가 caret 을 옮기기 **직전**에 호출한다 (예: 편집 진입 시 커서를 끝으로 보내는 경우).
   *
   * 자동완성은 *사용자가* 타이핑하거나 커서를 옮겼을 때 뜨는 것이지, 코드가 포커스·커서를
   * 옮긴 직후 저절로 뜰 것이 아니다. `setSelectionRange` 는 사용자 조작과 구별되지 않는
   * `select` 이벤트를 낳으므로, 구별을 호출 측이 명시적으로 알려 준다.
   */
  suppressNextSelect: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// useMentionAutocomplete
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 멘션 자동완성 훅.
 *
 * textarea의 이벤트 핸들러와 드롭다운 ReactNode를 반환한다.
 * 호출 측은 반환값을 textarea에 그대로 배선한다.
 *
 * 핵심 동작:
 * - onChange/onSelect 이벤트의 `e.currentTarget.selectionStart ?? 0`에서 caret 읽기(render 중 ref 금지).
 * - query 길이 ≥ 1일 때만 useDebounce → useUsers 호출.
 * - 키보드: open 중에만 Arrow/Enter/Tab/Escape를 가로채고 preventDefault.
 * - IME 조합 중에는 감지 보류(isComposingRef).
 *
 * @param value - 현재 textarea 텍스트 (controlled)
 * @param onChange - 텍스트 변경 콜백
 * @param textareaRef - textarea DOM 참조
 */
export function useMentionAutocomplete({
  value,
  onChange,
  textareaRef,
}: UseMentionAutocompleteProps): UseMentionAutocompleteReturn {
  // 활성 멘션 상태
  const [query, setQuery] = useState<string>('')
  const [open, setOpen] = useState(false)
  const [mentionRange, setMentionRange] = useState<{ start: number; end: number } | null>(null)
  const [activeIndex, setActiveIndex] = useState(-1)

  // IME 조합 상태 — state 불필요, ref로 추적
  const isComposingRef = useRef(false)

  // blur 지연 타이머 ref — unmount 시 cleanup으로 타이머 잔재 방지
  const blurTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 프로그램적 caret 이동이 낳는 select 1회를 감지에서 제외하기 위한 플래그.
  // 시간 기반 억제(setTimeout N ms)는 타이밍 의존이라 쓰지 않는다 — 정확히 1회만 소비한다.
  const suppressNextSelectRef = useRef(false)

  // debounce 된 쿼리 — MIN_QUERY_LENGTH 미만이면 빈 문자열(쿼리 비활성)
  const debouncedQuery = useDebounce(query.length >= MIN_QUERY_LENGTH ? query : '', DEBOUNCE_DELAY_MS)

  // useUsers — enabled 게이트가 없어 빈 문자열에도 GET /api/v1/users(전체 ≤50)를 호출하나(기존 assignee 셀렉터와 동일 패턴, staleTime 30s 캐시),
  // 드롭다운 표시는 아래 showDropdown(open && candidates.length>0)으로 차단된다(open은 query 길이 ≥1에서만 true).
  const { data: candidates = [] } = useUsers(debouncedQuery)

  // 경합 방지: open 상태 + 현재 query + candidates + activeIndex를 최신 ref로 유지
  const openRef = useRef(open)
  openRef.current = open
  const queryRef = useRef(query)
  queryRef.current = query
  const candidatesRef = useRef(candidates)
  candidatesRef.current = candidates
  const activeIndexRef = useRef(activeIndex)
  activeIndexRef.current = activeIndex

  /** 활성 멘션을 감지하고 state를 갱신한다. IME 조합 중이면 무시. */
  const detectAndUpdate = useCallback(
    (text: string, caret: number) => {
      if (isComposingRef.current) return

      const result = detectActiveMention(text, caret)
      if (result.active && result.query !== undefined && result.start !== undefined && result.end !== undefined) {
        const newQuery = result.query
        if (newQuery.length >= MIN_QUERY_LENGTH) {
          setQuery(newQuery)
          setMentionRange({ start: result.start, end: result.end })
          setOpen(true)
          setActiveIndex(-1)
        } else {
          // query 길이 0(@만) — fetch/open 안 함
          setQuery('')
          setOpen(false)
          setMentionRange(null)
        }
      } else {
        // 활성 멘션 없음 — 닫기
        setQuery('')
        setOpen(false)
        setMentionRange(null)
      }
    },
    [],
  )

  /** textarea onChange — 부모 onChange 전파 + 멘션 감지 */
  const handleChange = useCallback(
    (e: ChangeEvent<HTMLTextAreaElement>) => {
      // 사용자가 실제로 타이핑했다 — 소비되지 않고 남은 억제 플래그가 있더라도 여기서 버린다.
      // (프로그램적 caret 이동이 select 를 안 낳는 환경에서 플래그가 남아 다음 사용자 조작을
      //  삼키는 것을 막는 안전장치. 억제 범위를 "다음 타건 전까지"로 못 박는다.)
      suppressNextSelectRef.current = false

      // 지연된 blur 닫기 예약이 남아 있으면 취소한다.
      // 타이핑은 사용자가 이 입력칸으로 **돌아왔다**는 뜻이라, 이전 blur 로 예약된 닫기는 무효다.
      // 취소하지 않으면 방금 연 드롭다운을 150ms 뒤 stale 타이머가 닫아 버린다(실측 회귀).
      if (blurTimerRef.current !== null) {
        clearTimeout(blurTimerRef.current)
        blurTimerRef.current = null
      }
      const text = e.currentTarget.value
      const caret = e.currentTarget.selectionStart ?? 0
      onChange(text)
      detectAndUpdate(text, caret)
    },
    [onChange, detectAndUpdate],
  )

  /**
   * textarea onSelect — caret 이동 감지.
   * E6: caret이 활성 멘션 구간 밖이면 닫음.
   * 주의: value prop 클로저 대신 e.currentTarget.value를 직접 읽어
   *       React 상태 업데이트 시차 문제를 회피한다.
   */
  const handleSelect = useCallback(
    (e: SyntheticEvent<HTMLTextAreaElement>) => {
      // 코드가 옮긴 caret 은 사용자 의도가 아니다 — 플래그를 1회 소비하고 감지를 건너뛴다.
      // 사용자의 클릭·방향키로 생기는 select 는 플래그가 없으므로 종전대로 감지한다.
      if (suppressNextSelectRef.current) {
        suppressNextSelectRef.current = false
        return
      }
      const el = e.currentTarget as HTMLTextAreaElement
      const text = el.value
      const caret = el.selectionStart ?? 0
      detectAndUpdate(text, caret)
    },
    [detectAndUpdate],
  )

  /**
   * 후보 선택 처리.
   * spliceMention → onChange → caret 복원 → 닫기.
   */
  const handleSelectCandidate = useCallback(
    (candidate: UserSummary) => {
      if (mentionRange === null) return
      const { next, caret } = spliceMention(value, mentionRange.start, mentionRange.end, candidate.username)
      onChange(next)

      const el = textareaRef.current
      if (el !== null) {
        restoreCaretAfterFrame(el, caret)
      }

      setOpen(false)
      setQuery('')
      setMentionRange(null)
      setActiveIndex(-1)
    },
    [value, mentionRange, onChange, textareaRef],
  )

  /** textarea onKeyDown — open 중에만 Arrow/Enter/Tab/Escape 가로채기 */
  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      if (!openRef.current) return

      // ref를 통해 최신 candidates / activeIndex 읽기
      // setQueryData 직후 re-render 전에도 최신 값을 참조해 범위 초과 방지
      const current = candidatesRef.current
      const total = current.length
      const currentActiveIndex = activeIndexRef.current

      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setActiveIndex((prev) => (total === 0 ? -1 : (prev + 1) % total))
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        setActiveIndex((prev) => (total === 0 ? -1 : (prev - 1 + total) % total))
      } else if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault()
        // activeIndex가 범위 초과일 수 있으므로 최신 candidates 기준으로 clamp
        const safeIndex = currentActiveIndex >= 0 && currentActiveIndex < total
          ? currentActiveIndex
          : total > 0 ? 0 : -1
        const candidate = safeIndex >= 0 ? current[safeIndex] : undefined
        if (candidate !== undefined) {
          handleSelectCandidate(candidate)
        }
      } else if (e.key === 'Escape') {
        e.preventDefault()
        setOpen(false)
        setQuery('')
        setMentionRange(null)
        setActiveIndex(-1)
      }
    },
    [handleSelectCandidate],
  )

  /** IME 조합 시작 */
  const handleCompositionStart = useCallback(() => {
    isComposingRef.current = true
  }, [])

  /**
   * IME 조합 종료 — 즉시 재감지.
   * isComposingRef를 false로 설정한 뒤, compositionEnd 시점의
   * textarea.value / selectionStart로 detectAndUpdate를 직접 호출한다.
   * 이렇게 하면 compositionEnd 이후 별도 onSelect 이벤트 없이도 드롭다운이 열린다.
   */
  const handleCompositionEnd = useCallback((e: CompositionEvent<HTMLTextAreaElement>) => {
    isComposingRef.current = false
    const el = e.currentTarget as HTMLTextAreaElement
    detectAndUpdate(el.value, el.selectionStart ?? 0)
  }, [detectAndUpdate])

  /**
   * textarea onBlur — 150ms 지연 후 닫기.
   * onMouseDown preventDefault가 있으면 blur가 억제되므로 실제 blur 시에만 실행.
   * blurTimerRef로 이전 타이머를 취소해 unmount 후 잔재 방지.
   */
  const handleBlur = useCallback(() => {
    if (blurTimerRef.current !== null) {
      clearTimeout(blurTimerRef.current)
    }
    blurTimerRef.current = setTimeout(() => {
      blurTimerRef.current = null
      setOpen(false)
    }, 150)
  }, [])

  // blur 타이머 unmount cleanup — 컴포넌트 제거 시 pending 타이머 취소
  useEffect(() => {
    return () => {
      if (blurTimerRef.current !== null) {
        clearTimeout(blurTimerRef.current)
      }
    }
  }, [])

  /**
   * 멘션 상태 전체 초기화.
   * blur 타이머를 즉시 취소하므로 탭 전환 등 명시적 이탈 시 타이머 없이 바로 닫힌다.
   */
  const reset = useCallback(() => {
    if (blurTimerRef.current !== null) {
      clearTimeout(blurTimerRef.current)
      blurTimerRef.current = null
    }
    setOpen(false)
    setQuery('')
    setMentionRange(null)
    setActiveIndex(-1)
  }, [])

  /** 다음 select 1회를 감지에서 제외한다 — 프로그램적 caret 이동 직전에 호출. */
  const suppressNextSelect = useCallback(() => {
    suppressNextSelectRef.current = true
  }, [])

  // activeIndex clamp — candidates가 축소될 때 범위 초과 방지.
  // Enter 핸들러와 별개로, UI 하이라이트도 항상 유효 범위를 가리키도록 보장한다.
  useEffect(() => {
    if (candidates.length > 0 && activeIndex >= candidates.length) {
      setActiveIndex(candidates.length - 1)
    } else if (candidates.length === 0 && activeIndex !== -1) {
      setActiveIndex(-1)
    }
  }, [candidates.length, activeIndex])

  // 드롭다운 렌더
  const showDropdown = open && candidates.length > 0
  const mentionDropdown: ReactNode = showDropdown
    ? createElement(MentionDropdown, {
        candidates,
        activeIndex,
        onSelect: handleSelectCandidate,
        listboxId: LISTBOX_ID,
      })
    : null

  return {
    onChange: handleChange,
    onKeyDown: handleKeyDown,
    onCompositionStart: handleCompositionStart,
    onCompositionEnd: handleCompositionEnd,
    onSelect: handleSelect,
    onBlur: handleBlur,
    mentionDropdown,
    reset,
    suppressNextSelect,
  }
}
