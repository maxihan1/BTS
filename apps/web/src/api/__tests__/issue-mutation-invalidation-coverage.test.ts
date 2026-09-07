// 이슈를 바꾸는 파일은 예외 없이 `invalidateIssueViews` 를 쓴다 — 차집합 판별식
//
// ## 왜 있나
//
// 2026-09-07 실측. 이슈를 바꾸는 뮤테이션이 **15곳**이었는데 각자 무엇을 무효화할지 따로 적고
// 있었고, 그 결과 대부분이 자기 화면만 갱신했다. 사용자에게는 「이슈를 고쳤는데 목록·보드는
// 새로고침해야 바뀐다」로 나타났다(Maxi 보고).
//
// 헬퍼(`invalidateIssueViews`)를 만들어 전부 옮겼지만, **그것만으로는 다음 뮤테이션을 막지
// 못한다.** 「이슈 변경 API 를 쓰는 파일 목록」과 「헬퍼를 쓰는 파일 목록」이 서로를 검사하지
// 않으면 새 뮤테이션이 조용히 옛 방식으로 추가된다 — 이 저장소가 이름 붙인 지배 결함 양식이다.
// 그래서 두 목록의 **차집합을 기계가 매번 재계산**한다.
import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const SRC = path.resolve(__dirname, '../..')

/**
 * 이슈의 **내용을 바꾸는** API 함수 이름.
 *
 * 🛑 조회 함수(`fetchIssue`·`fetchIssues`)를 넣지 마라 — 그것을 쓰는 파일은 무효화할 것이 없다.
 * 🛑 새 변경 API 를 `api/issues.ts` 에 추가하면 **여기에도 더한다.** 안 더하면 그 API 를 쓰는
 *    새 뮤테이션이 이 판별식 밖으로 빠진다. (그 자체를 기계로 막으려면 `issues.ts` 의 HTTP
 *    메서드를 파싱해야 하는데, 그 파서가 또 하나의 목록이 된다 — 여기서 멈춘다.)
 */
const ISSUE_MUTATING_APIS = [
  'updateIssue',
  'transitionIssue',
  'changeAssignee',
  'changeComponents',
  'changeSecurityLevel',
  'cloneIssue',
  'deleteIssue',
  // 이슈 자체의 엔드포인트는 아니지만 **이슈의 표시 값을 바꾼다** — 보드 카드 위치(moveCard)와
  // 목록·카드의 누적 시간(worklog)이 그것이다. 화면 기준으로 세는 것이 이 판별식의 목적이다.
  'moveCard',
  'addWorklog',
  'updateWorklog',
  'deleteWorklog',
] as const

/** 판정 대상 밖 — 이 파일들은 「이슈를 바꾸는 화면」이 아니다 */
const EXCLUDED = [
  /\.test\.tsx?$/,
  /__tests__\//,
  /^mocks\//, // MSW 핸들러 — 서버 흉내이지 캐시 소비자가 아니다
  /^api\/issue-view-invalidation\.ts$/, // 헬퍼 자신
]

/**
 * 이 파일이 그 API 를 **정의**하는가.
 *
 * 🛑 정의 자리(`api/issues.ts`·`api/boards.ts`·`api/worklogs.ts`)를 이름으로 열거하지 않는다.
 *    열거하면 API 파일이 하나 늘 때마다 이 목록도 손봐야 하고, 안 손보면 그 파일이 「무효화를
 *    빠뜨린 소비자」로 오진돼 판별식이 거짓 red 를 낸다. 정의는 **모양으로** 가른다.
 */
function declaresApi(source: string, api: string): boolean {
  return new RegExp(`export (async )?function ${api}\\b`).test(source)
}

/** `src` 아래 모든 ts/tsx 를 상대 경로로 나열한다 */
function walk(dir: string, base = ''): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const rel = base === '' ? entry.name : `${base}/${entry.name}`
    if (entry.isDirectory()) return walk(path.join(dir, entry.name), rel)
    return /\.tsx?$/.test(entry.name) ? [rel] : []
  })
}

const ALL_FILES = walk(SRC)

/** 이슈 변경 API 를 **실제로 호출**하는 파일 (임포트만 하고 안 쓰는 경우는 제외) */
const MUTATING_FILES = ALL_FILES.filter((rel) => {
  if (EXCLUDED.some((re) => re.test(rel))) return false
  const source = fs.readFileSync(path.join(SRC, rel), 'utf8')
  return ISSUE_MUTATING_APIS.some(
    (api) => new RegExp(`\\b${api}\\s*\\(`).test(source) && !declaresApi(source, api),
  )
})

describe('이슈 변경 뮤테이션 ↔ 무효화 헬퍼 — 차집합 0', () => {
  it('비-공허: 이슈를 바꾸는 파일이 실제로 존재한다', () => {
    // 스캐너가 깨져 목록이 비면 아래 단언이 조용히 통과한다. 그 항진명제를 먼저 막는다.
    expect(MUTATING_FILES.length).toBeGreaterThanOrEqual(10)
  })

  it('이슈를 바꾸는 파일은 전부 `invalidateIssueViews` 를 쓴다', () => {
    // 🛑 「자기가 아는 캐시만 무효화」로 되돌아가면 여기서 잡힌다. 사용자 증상은
    //    「고쳤는데 다른 화면은 새로고침해야 바뀐다」이고, 그것은 화면을 켜 보기 전에는
    //    아무도 모른다 — 그래서 기계가 본다.
    // 🛑 `includes('invalidateIssueViews')` 로 재면 **임포트 줄만 남겨도 통과한다.**
    //    실측 2026-09-07 — 호출을 옛 `invalidateQueries` 로 되돌리고 임포트만 남긴 뮤테이션
    //    프로브가 초록이었다. 이 저장소가 이름 붙인 「도움말이 불변식을 만족시킨다」 양식이다.
    //    그래서 **호출 형태**(`invalidateIssueViews(`)를 본다.
    const missing = MUTATING_FILES.filter((rel) => {
      const source = fs.readFileSync(path.join(SRC, rel), 'utf8')
      return !source.includes('invalidateIssueViews(')
    })

    expect(missing).toEqual([])
  })

  it('헬퍼를 쓰는 파일이 이슈 변경 파일 집합 안에 있다 (유령 사용 차단)', () => {
    // 반대 방향 — 이슈를 바꾸지도 않으면서 헬퍼를 부르는 곳이 생기면 무효화가 과해진다.
    // 폴링 완료 지점(`use-bulk-operation`)은 API 를 직접 부르지 않으므로 명시 허용한다.
    const ALLOWED_WITHOUT_DIRECT_API = ['hooks/use-bulk-operation.ts']

    const users = ALL_FILES.filter((rel) => {
      if (EXCLUDED.some((re) => re.test(rel))) return false
      return fs.readFileSync(path.join(SRC, rel), 'utf8').includes('invalidateIssueViews(')
    })

    const unexpected = users.filter(
      (rel) => !MUTATING_FILES.includes(rel) && !ALLOWED_WITHOUT_DIRECT_API.includes(rel),
    )

    expect(unexpected).toEqual([])
  })
})
