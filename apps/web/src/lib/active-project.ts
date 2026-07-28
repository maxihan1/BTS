// 활성 프로젝트(Active Project) 4단 해소 순수 함수 — FR-UX-07 (ADR D3)

/**
 * 활성 프로젝트 키가 어디서 왔는지 나타내는 출처.
 *
 * 호출자가 **저장값을 갱신할지 판단하려면 출처를 알아야 한다**(FR4) — `url`·`first`는
 * 저장하고 `stored`는 이미 저장돼 있으므로 write를 생략한다(E7).
 */
export type ActiveProjectSource = 'url' | 'stored' | 'first' | 'none'

/** {@link resolveActiveProjectKey}의 반환값 — 해소된 키와 그 출처 */
export interface ActiveProjectResolution {
  /** 해소된 프로젝트 키. 접근 가능한 프로젝트가 없으면 null */
  key: string | null
  /** 키의 출처 — 저장 여부 판단에 쓴다 */
  source: ActiveProjectSource
}

/** {@link resolveActiveProjectKey} 입력 — 조회는 호출자가 끝낸 뒤 주입한다 */
export interface ActiveProjectInput {
  /** URL이 지정한 키 — 검색 파라미터 `?projectKey=` 또는 경로 파라미터 `/projects/$projectKey/*` */
  urlKey: string | null | undefined
  /** localStorage에 마지막으로 저장된 키 */
  storedKey: string | null | undefined
  /** 사용자가 접근 가능한 프로젝트 목록 — `useProjects()` 결과를 그대로 넘긴다 */
  projects: readonly { key: string }[]
}

/**
 * 값이 비어 있지 않은 문자열일 때만 그대로 반환한다.
 *
 * `''`를 미지정으로 접는 이유 — 빈 문자열이 그대로 흘러가면 백엔드가 빈 스코프로
 * 권한을 평가해(`IssueApplicationService.assertPermission(..., IssueScope.Project(""))`)
 * 조용히 차단된다. 원인 추적이 어려운 실패라 입구에서 막는다.
 */
function nonEmpty(value: string | null | undefined): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

/**
 * 활성 프로젝트를 4단으로 해소한다 (ADR D3).
 *
 * ```
 * ① URL 키               → source 'url'
 * ② 저장값(목록에 실재)    → source 'stored'
 * ③ 목록의 첫 원소         → source 'first'
 * ④ 프로젝트 0개          → key null, source 'none'
 * ```
 *
 * **URL이 최상위인 이유.** 링크 공유·뒤로가기·새로고침이 같은 화면을 재현해야 한다.
 * 저장값이 URL을 이기면 공유한 링크가 받는 사람에게 다른 프로젝트를 연다.
 *
 * **URL 키는 접근 가능 여부를 검사하지 않는다.** 사용자가 명시로 요청한 값이므로
 * 조용히 다른 프로젝트로 바꾸지 않는다 — 권한 실패는 호출자가 에러로 표시하고
 * 저장값도 갱신하지 않는다(E4). 조용한 대체는 권한 문제를 숨긴다.
 *
 * **저장값은 목록 대조를 거친다.** 프로젝트가 삭제·아카이브되거나 권한이 회수되면
 * 저장값이 낡는다. 대조에서 탈락시켜 ③으로 내려보내면 자가 치유된다(S6/E3).
 *
 * **프론트에서 재정렬하지 않는다.** 백엔드가 `ORDER BY name ASC`
 * (`ProjectQueryRepository.kt:62`)로 내려주고, 저장소 관례가 "백엔드 정렬 신뢰,
 * 프론트 재정렬 없음"이다(`routes/projects.index.tsx:42`). 클라이언트에서 다시
 * 정렬하면 Postgres collation과 어긋날 수 있다.
 *
 * @param input URL 키 · 저장값 · 접근 가능한 프로젝트 목록
 * @returns 해소된 키와 출처
 */
export function resolveActiveProjectKey(input: ActiveProjectInput): ActiveProjectResolution {
  const urlKey = nonEmpty(input.urlKey)
  if (urlKey !== null) return { key: urlKey, source: 'url' }

  const storedKey = nonEmpty(input.storedKey)
  if (storedKey !== null && input.projects.some((p) => p.key === storedKey)) {
    return { key: storedKey, source: 'stored' }
  }

  const first = input.projects[0]
  if (first !== undefined) return { key: first.key, source: 'first' }

  return { key: null, source: 'none' }
}
