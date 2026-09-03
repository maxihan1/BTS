// 워크플로우 전환 key 합성 규칙의 프론트 정본 — backend WorkflowTransition.key 게터와 1:1

/**
 * 출발 상태가 있는 전환(NORMAL)의 key 를 만든다.
 *
 * ★**응답에 실린 `key` 를 그대로 쓰는 것이 언제나 정답이고**, 이 함수는 MSW fixture 처럼
 * 전환을 만들어 내는 자리에서만 쓴다. 화면이 이 규칙으로 key 를 합성하기 시작하면 backend
 * 게터와 서로를 검사하지 않는 두 벌이 생긴다.
 *
 * @param fromStateKey 출발 상태 키
 * @param toStateKey 도착 상태 키
 * @returns `from__to` 형식 문자열
 */
export function transitionKey(fromStateKey: string, toStateKey: string): string {
  return `${fromStateKey}__${toStateKey}`
}

/**
 * 출발 상태가 없는 전환(GLOBAL·INITIAL)의 key 를 만든다.
 *
 * @param kind 전환 종류
 * @param toStateKey 도착 상태 키
 * @returns `KIND__to` 형식 문자열
 */
export function kindTransitionKey(kind: 'GLOBAL' | 'INITIAL', toStateKey: string): string {
  return `${kind}__${toStateKey}`
}

/** {@link composeTransitionKey} 가 읽는 최소 필드 — 픽스처와 API 응답 양쪽을 받는다 */
export interface TransitionKeyParts {
  readonly fromStateKey: string | null | undefined
  readonly toStateKey: string
  readonly kind?: string | null
}

/**
 * 전환 하나의 key 를 종류에 따라 합성한다 — 위 두 규칙의 단일 진입점.
 *
 * ★**이 함수와 「backend 게터가 같다」를 프론트 단위 테스트로 증명할 수는 없다.** 그것을
 * 재려면 계약 스냅샷처럼 backend 산출물을 읽는 장치가 있어야 한다. 이 함수의 존재 이유는
 * 더 좁다 — 프론트 안에 **규칙 사본이 여럿 생기는 것**을 막는 것이다. 종전에는 셋이었고
 * (`workflow.types.ts` · `workflow-fixtures.ts` · `workflow-fixtures.test.ts` 의 인라인
 * 재구현), 그중 테스트가 대조하던 것은 **사본 A 와 사본 B** 라 규칙이 통째로 틀려도 초록이었다.
 *
 * @param transition 전환의 출발·도착·종류
 * @returns backend 게터 규칙에 따른 key
 */
export function composeTransitionKey(transition: TransitionKeyParts): string {
  const { fromStateKey, toStateKey, kind } = transition
  if (fromStateKey === null || fromStateKey === undefined) {
    return kindTransitionKey(kind === 'INITIAL' ? 'INITIAL' : 'GLOBAL', toStateKey)
  }
  return transitionKey(fromStateKey, toStateKey)
}

/** {@link transitionElementKey} 가 읽는 최소 필드 */
export interface TransitionElementKeyParts {
  readonly key?: string | null
  readonly transitionId?: string | null
  readonly fromStateKey?: string | null
  readonly toStateKey: string
}

/**
 * 전환 목록을 렌더할 때 쓰는 React `key`.
 *
 * ★**응답의 `key` 를 그대로 React key 로 쓰면 안 된다.** 두 가지 이유가 겹친다.
 * - `UNIQUE(workflow_id, from, to)` 해제로 **같은 (from, to) 쌍에 이름만 다른 전환이 여럿**
 *   있을 수 있다. 그 둘은 `from__to` 로 같은 문자열을 갖는다(409 `AMBIGUOUS_TRANSITION` 이
 *   나는 바로 그 조합이다).
 * - 서버 계약상 `key` 는 `String?` 라 **null 일 수 있다**(「미계산」).
 *
 * 그래서 1급 식별자인 `transitionId` 를 먼저 쓰고, 그것이 없을 때만 key 또는 상태쌍에
 * 목록 위치를 덧붙인다. 위치를 섞는 것은 차선이지만, 같은 값이 둘 나오는 것보다 낫다.
 *
 * @param transition 대상 전환
 * @param index 목록에서의 위치 — 앞의 둘이 모두 고유하지 않을 때의 최후 수단
 * @returns 형제 사이에서 고유한 문자열
 */
export function transitionElementKey(
  transition: TransitionElementKeyParts,
  index: number,
): string {
  if (transition.transitionId !== null && transition.transitionId !== undefined) {
    return transition.transitionId
  }
  const base = transition.key ?? `${transition.fromStateKey ?? ''}__${transition.toStateKey}`
  return `${base}#${index}`
}
