// 식별자 도메인으로 SSO provider 를 배경 조회하는 훅 — blur ‖ 디바운스 · dedupe · 순번 가드
import { useState, useRef, useEffect, useCallback } from 'react'
import { fetchRoute } from '@/api/route'
import type { RouteMatch } from '@/api/route'

/** 도메인 조회 디바운스 — 부분 도메인마다 요청이 나가는 것을 막는다 */
export const ROUTE_LOOKUP_DEBOUNCE_MS = 500

export interface DomainRouteLookup {
  /** 도메인 매칭 결과. null 이면 로컬/LDAP 폼만 보인다 */
  matchedRoute: RouteMatch | null
  /** 타이핑 중 호출 — 디바운스 후 조회한다 */
  scheduleLookup: (identifier: string) => void
  /** 입력 종료(blur/제출) 시 호출 — 대기 중인 디바운스를 취소하고 즉시 조회한다 */
  flushLookup: (identifier: string) => void
}

/**
 * 식별자에서 도메인을 뽑아 `GET /api/v1/auth/route` 를 배경 조회한다 (FR-AU-07).
 *
 * 이메일 선입력 1단계가 폐기되면서 도메인 라우팅이 이 훅으로 옮겨왔다. 자동 리다이렉트는
 * 하지 않고 매칭 결과만 돌려준다 — 소비자가 SSO 버튼을 그리고, 이동은 사용자 클릭이 만든다.
 * 수동적 트리거(blur/디바운스)로 풀 네비게이션을 걸면 타이핑 중이던 비밀번호가 날아가기 때문이다.
 *
 * 세 가지 가드가 있고 각각 실제 회귀를 막는다.
 * - **dedupe** — 같은 도메인을 두 번 조회하지 않는다. 단 **실패하면 키를 되돌린다**;
 *   안 되돌리면 일시적 네트워크 장애 뒤 재조회가 영구 차단돼 SSO 버튼이 영영 안 뜬다.
 * - **순번(seq)** — 늦게 도착한 옛 응답이 최신 결과를 덮어쓰지 않게 한다.
 * - **언마운트 정리** — 대기 중인 디바운스 타이머를 취소한다.
 *
 * `@` 가 없거나 도메인이 비면 조회하지 않고 이전 매칭을 **지운다**. 안 지우면 식별자를
 * LDAP 사용자명으로 바꿨는데 이전 도메인의 SSO 버튼이 남는다.
 */
export function useDomainRouteLookup(): DomainRouteLookup {
  const [matchedRoute, setMatchedRoute] = useState<RouteMatch | null>(null)
  const lastQueriedDomainRef = useRef('')
  const seqRef = useRef(0)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(
    () => () => {
      if (debounceRef.current !== null) clearTimeout(debounceRef.current)
    },
    [],
  )

  const runLookup = useCallback((identifier: string) => {
    const atIndex = identifier.indexOf('@')
    const domain = atIndex !== -1 ? identifier.slice(atIndex + 1) : ''

    if (domain === '') {
      lastQueriedDomainRef.current = ''
      setMatchedRoute(null)
      return
    }
    if (domain === lastQueriedDomainRef.current) return

    lastQueriedDomainRef.current = domain
    const seq = ++seqRef.current
    fetchRoute(domain)
      .then((result) => {
        if (seq !== seqRef.current) return
        setMatchedRoute(result.matched ? result : null)
      })
      .catch((err: unknown) => {
        // 최신 조회일 때만 키를 되돌린다 — 뒤늦게 실패한 옛 요청이 새 키를 지우면 안 된다.
        if (seq === seqRef.current) {
          lastQueriedDomainRef.current = ''
        }
        // 조회 실패는 사용자를 막지 않는다(FR-07 S4 fail-safe). 네트워크 일시 단절 등
        // 정상 운영에서도 나는 폴백 경로라 error 가 아닌 warn 이다.
        console.warn('[useDomainRouteLookup] route 조회 실패 — 로컬 로그인으로 진행', err)
      })
  }, [])

  const scheduleLookup = useCallback(
    (identifier: string) => {
      if (debounceRef.current !== null) clearTimeout(debounceRef.current)
      debounceRef.current = setTimeout(() => {
        runLookup(identifier)
      }, ROUTE_LOOKUP_DEBOUNCE_MS)
    },
    [runLookup],
  )

  const flushLookup = useCallback(
    (identifier: string) => {
      if (debounceRef.current !== null) {
        clearTimeout(debounceRef.current)
        debounceRef.current = null
      }
      runLookup(identifier)
    },
    [runLookup],
  )

  return { matchedRoute, scheduleLookup, flushLookup }
}
