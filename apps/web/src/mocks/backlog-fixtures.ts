// 백로그·스프린트 MSW 픽스처 — 기본 시드 데이터 + stateful store 관리 함수 (FR-BL-01/02 D6/D7)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: mutation 핸들러가 store를 변이하고 GET이 읽어야 가짜그린 회피
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - fr-bd-01: 신규 store는 모듈 로드 시 자동 시드 필수
//

import type { BacklogIssue, SprintMeta } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// store 내부 전용 확장 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW store 내부 스프린트 단위.
 * sprintId 기준으로 인덱싱된다.
 */
export interface StoredSprint {
  sprint: SprintMeta
  issues: BacklogIssue[]
}

/**
 * MSW store 내부 프로젝트 단위.
 * projectKey 기준으로 인덱싱된다.
 */
export interface StoredBacklogProject {
  /** 프로젝트 키. 예: "ATLAS" */
  projectKey: string
  /** 스프린트 미할당 이슈 (rank 순) */
  backlog: BacklogIssue[]
  /** 스프린트별 이슈 묶음 */
  sprints: StoredSprint[]
  /** 카드 수 cap 초과 여부 */
  truncated: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID 생성 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * Zod v4 z.string().uuid() 검증을 통과하는 형식을 보장한다.
 */
export function generateUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // Math.random 폴백 — RFC4122 v4 형식 (version=4, variant=8~b)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16)
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// LexoRank 단순 계산 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 두 rank 문자열 사이의 중간값 rank를 계산한다.
 * 실제 LexoRank 라이브러리 없이 문자열 평균으로 단순 구현.
 * previousRank/nextRank 중 없는 쪽은 경계값으로 처리한다.
 */
export function computeRank(previousRank: string | null, nextRank: string | null): string {
  const MIN = '0|'
  const MAX = 'z|'

  const prev = previousRank ?? MIN
  const next = nextRank ?? MAX

  // 두 문자열을 같은 길이로 맞추고 중간값 계산
  const maxLen = Math.max(prev.length, next.length) + 1
  const p = prev.padEnd(maxLen, '0')
  const n = next.padEnd(maxLen, '0')

  // 문자코드 평균
  const mid = p
    .split('')
    .map((ch, i) => {
      const pCode = ch.charCodeAt(0)
      const nCode = (n[i] ?? '0').charCodeAt(0)
      return Math.floor((pCode + nCode) / 2)
    })
    .map((code) => String.fromCharCode(code))
    .join('')
    .trimEnd()

  // prev와 같아지는 경우 간단히 prev + '5' 로 뒤에 붙임
  if (mid <= prev) {
    return prev + '5'
  }
  return mid
}

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — projectKey → StoredBacklogProject
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 store — projectKey → StoredBacklogProject.
 * mutation 핸들러가 변이하고, GET 핸들러가 읽어 응답을 구성한다.
 */
export let backlogStore: Map<string, StoredBacklogProject> = new Map()

// ─────────────────────────────────────────────────────────────────────────────
// store 관리 함수 — E2E addInitScript 또는 테스트 setup에서 호출
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 store를 초기 상태로 리셋한다.
 * 각 테스트 beforeEach/afterEach에서 호출해 테스트 간 격리를 보장한다.
 */
export function resetBacklogStore(): void {
  backlogStore = new Map()
}

/**
 * StoredBacklogProject 데이터를 store에 시드한다.
 * 동일 projectKey가 이미 있으면 덮어쓴다.
 *
 * @param project 시드할 프로젝트 데이터
 */
export function seedBacklog(project: StoredBacklogProject): void {
  backlogStore.set(project.projectKey, structuredClone(project))
}

/**
 * 새로 생성된 이슈를 해당 프로젝트의 **백로그 칸 맨 끝**에 붙인다 (FR-UX-09 F3 FR-13).
 *
 * ★왜 이 함수가 여기 있나.
 * `POST /api/v1/issues`(issue-handlers)와 `GET /projects/{key}/backlog`(이 파일의 store)는
 * 원래 **서로 다른 저장소**였다. 그래서 「만들었더니 백로그에 나타난다」를 검증하려는 테스트가
 * **구현이 옳아도 실패**했고, 그 실패를 피해 「호출됐다」로 단언을 약화하면
 * `assignToSprintHandler` 가 모르는 키를 201 로 멱등 처리하는 탓에 **항상 통과**하는
 * 가짜 그린이 됐다. 두 목이 같은 출처를 보게 해서 그 갈림 자체를 없앤다.
 *
 * **store 에 없는 프로젝트면 아무것도 하지 않는다** — 백로그를 쓰지 않는 화면의
 * 기존 테스트가 이 확장 때문에 깨지면 안 된다.
 *
 * @param projectKey 생성된 이슈의 프로젝트 키
 * @param issue rank 를 제외한 백로그 이슈 필드 (rank 는 맨 끝 값으로 계산해 붙인다)
 */
export function appendCreatedIssueToBacklog(
  projectKey: string,
  issue: Omit<BacklogIssue, 'rank'>,
): void {
  const project = backlogStore.get(projectKey)
  if (project === undefined) return

  // ★같은 키는 두 번 들어가지 않는다 — store 의 불변식이다(실제 백로그도 같다).
  //
  // 이 가드가 없으면 실측으로 **한 번의 생성이 두 줄을 만든다**. 이 환경의 MSW 는
  // 요청 1회에 리졸버를 2회 실행한다(2026-08-03 계측 — `request:start` 2회 발화,
  // 백로그 길이 2 → 4). 그러면 배정이 한 줄만 옮겨 **백로그에 유령 한 줄이 남는다**.
  // 게다가 생성 목의 키는 `createdIssueFixture.key` 고정값이라 같은 테스트에서
  // 두 번 만들면 어차피 키가 겹친다 — 중복 금지가 우회가 아니라 올바른 의미다.
  //
  // ⚠️ **다른 append 형 목 핸들러도 같은 이유로 중복될 수 있다.** 이 PR 범위 밖이라
  //    TODOS 로 넘긴다 — 여기서는 이 경로만 불변식으로 닫는다.
  if (findIssueInProject(project, issue.key) !== undefined) return

  const lastRank = project.backlog.at(-1)?.rank ?? null
  project.backlog.push({ ...issue, rank: computeRank(lastRank, null) })
}

/**
 * store에서 이슈(key 기준)를 찾는다. backlog + 모든 스프린트에서 탐색.
 * 발견 시 해당 이슈 객체와 소속 컨테이너 참조를 반환한다.
 *
 * @param project 탐색할 프로젝트 store 엔트리
 * @param issueKey 탐색할 이슈 키
 */
function findIssueInProject(
  project: StoredBacklogProject,
  issueKey: string,
): BacklogIssue | undefined {
  const fromBacklog = project.backlog.find((i) => i.key === issueKey)
  if (fromBacklog !== undefined) return fromBacklog

  for (const sw of project.sprints) {
    const found = sw.issues.find((i) => i.key === issueKey)
    if (found !== undefined) return found
  }
  return undefined
}

/**
 * store에서 이슈를 제거한다 (backlog + 스프린트 모두에서).
 * issue-tracking 백로그나 스프린트 중 하나에서 발견하면 제거하고 해당 이슈를 반환한다.
 *
 * @param project 탐색·변이할 프로젝트 store 엔트리
 * @param issueKey 제거할 이슈 키
 */
function removeIssueFromProject(
  project: StoredBacklogProject,
  issueKey: string,
): BacklogIssue | undefined {
  const backlogIdx = project.backlog.findIndex((i) => i.key === issueKey)
  if (backlogIdx !== -1) {
    const [removed] = project.backlog.splice(backlogIdx, 1)
    return removed
  }

  for (const sw of project.sprints) {
    const sprintIdx = sw.issues.findIndex((i) => i.key === issueKey)
    if (sprintIdx !== -1) {
      const [removed] = sw.issues.splice(sprintIdx, 1)
      return removed
    }
  }
  return undefined
}

/**
 * 새 스프린트를 store에 추가한다.
 * POST /api/v1/sprints 핸들러가 내부적으로 호출한다.
 *
 * @param projectKey 프로젝트 키
 * @param name 스프린트 이름
 * @param goal 스프린트 목표 (선택)
 * @param startDate 시작일 ISO 문자열 (선택)
 * @param endDate 종료일 ISO 문자열 (선택)
 */
export function createSprintInStore(
  projectKey: string,
  name: string,
  goal?: string,
  startDate?: string,
  endDate?: string,
): SprintMeta {
  const sprint: SprintMeta = {
    sprintId: generateUUID(),
    name,
    goal: goal ?? null,
    status: 'PLANNED',
    startDate: startDate ?? null,
    endDate: endDate ?? null,
    version: 0,
  }

  const project = backlogStore.get(projectKey)
  if (project !== undefined) {
    project.sprints.push({ sprint, issues: [] })
  } else {
    // 프로젝트가 없으면 새로 생성
    backlogStore.set(projectKey, {
      projectKey,
      backlog: [],
      sprints: [{ sprint, issues: [] }],
      truncated: false,
    })
  }

  return sprint
}

/**
 * store에서 sprintId로 스프린트 엔트리를 찾는다.
 * backlogStore 전체를 순회해 탐색한다.
 *
 * @param sprintId 찾을 스프린트 UUID
 */
export function findSprintInStore(sprintId: string): {
  project: StoredBacklogProject
  storedSprint: StoredSprint
} | undefined {
  for (const project of backlogStore.values()) {
    const sw = project.sprints.find((s) => s.sprint.sprintId === sprintId)
    if (sw !== undefined) {
      return { project, storedSprint: sw }
    }
  }
  return undefined
}

// findIssueInProject / removeIssueFromProject를 외부에서도 쓸 수 있도록 re-export
export { findIssueInProject, removeIssueFromProject }

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — alice(userId=00000000-0000-4000-8000-000000000001) 로그인 기준
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 기본 백로그 픽스처 — ATLAS 프로젝트, backlog 이슈 2개, 스프린트 1개(이슈 2개).
 *
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장.
 * alice(userId=00000000-0000-4000-8000-000000000001)가 ATLAS 프로젝트 BROWSE 가능 전제.
 */
export const DEFAULT_BACKLOG: StoredBacklogProject = {
  projectKey: 'ATLAS',
  backlog: [
    {
      key: 'ATLAS-1',
      summary: '첫 번째 이슈 — 로그인 페이지 구현',
      currentStateKey: 'open',
      assigneeId: '00000000-0000-4000-8000-000000000001',
      priority: 1,
      rank: '0|hzzzzz:',
      version: 0,
      epicKey: null,
    },
    {
      key: 'ATLAS-2',
      summary: '두 번째 이슈 — 이슈 목록 페이지 UI 구현',
      currentStateKey: 'open',
      assigneeId: null,
      priority: 2,
      rank: '0|i00007:',
      version: 0,
      epicKey: null,
    },
  ],
  sprints: [
    {
      sprint: {
        sprintId: 'a0000000-0000-4000-8000-000000000001',
        name: '스프린트 1',
        goal: '첫 번째 스프린트 목표',
        status: 'PLANNED',
        startDate: null,
        endDate: null,
        version: 0,
      },
      issues: [
        {
          key: 'ATLAS-3',
          summary: '세 번째 이슈 — 이슈 상세 페이지 구현',
          currentStateKey: 'open',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          priority: 1,
          rank: '0|hzzzzz:',
          version: 0,
          epicKey: null,
        },
        {
          key: 'ATLAS-4',
          summary: '네 번째 이슈 — 보드 뷰 구현',
          currentStateKey: 'open',
          assigneeId: null,
          priority: 2,
          rank: '0|i00007:',
          version: 0,
          epicKey: null,
        },
      ],
    },
  ],
  truncated: false,
}

// 모듈 로드 시 기본 백로그를 자동 시드한다 — fr-bd-01 교훈 (신규 store 자동 시드 필수).
// dev(pnpm dev) · E2E 진입 시 store가 비어 있어 빈 백로그가 노출되는 결함 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
//
// ★`import.meta.env` 는 **번들러가 주입하는 값이라 항상 있지 않다** (FR-UX-09 F3에서 실측).
// Playwright 는 spec 파일을 Node 로더로 읽는데, spec 이 `src/mocks/*` 를 import 하면
// 이 모듈이 그 로더 위에서 평가된다. 거기엔 `import.meta.env` 가 **없어서**
// 가드 없이 `.MODE` 를 읽으면 `TypeError` 가 나고 **Playwright 수집이 통째로 실패**한다
// (`Total: 0 tests in 0 files`). 파일 하나가 아니라 **E2E 전체**가 사라지는 형태라
// 「테스트가 깨졌다」가 아니라 「테스트가 없다」로 보인다 — 가장 알아채기 어려운 실패다.
//
// 값이 없으면 시드하는 쪽이 맞다 — 건너뛰는 것은 vitest 가 스스로 제어할 때뿐이다.
const bundlerEnv = (import.meta as { env?: { MODE?: string } }).env
if (bundlerEnv?.MODE !== 'test') {
  seedBacklog(DEFAULT_BACKLOG)
}
