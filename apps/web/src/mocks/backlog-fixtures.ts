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
// 에픽 픽스처 — 카드의 `epicKey` 와 「에픽 이름」의 **단일 출처**
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 카드가 가리키는 에픽 한 건.
 *
 * `key` 는 카드의 `epicKey` 로 들어가고, `summary` 는 `GET /api/v1/issues/{key}` 가 돌려줄
 * 이름이다 — `issue-handlers.ts` 가 이 목록을 읽어 상세 응답을 만든다. 두 곳이 같은 출처를
 * 보게 해서 「키는 심었는데 이름이 안 붙는」 갈림을 구조적으로 없앤다
 * (`msw-derived-behavior-shared-store-e2e` — 파생 응답은 공유 store 에서 읽는다).
 */
export interface BacklogEpicFixture {
  /** 에픽 이슈 키. 카드의 `epicKey` 이자 이름 조회 URL 의 경로 토큰 */
  readonly key: string
  /** 에픽 이슈의 제목 = 화면에 보일 **사람이 읽는 이름**. 반드시 {@link key} 와 달라야 한다 */
  readonly summary: string
}

/**
 * 에픽 A — 백로그 칸의 이슈가 소속된다.
 *
 * 이름을 키와 **다르게** 둔 것이 계약이다. 같으면 「패널이 키가 아니라 이름을 보여 준다」는
 * 단언이 이름 해석에 실패해도 통과하는 공허한 단언이 된다 (`e2e/backlog.spec.ts` 의
 * 「F16 에픽 픽스처」 tripwire 가 이 조건을 지킨다).
 */
export const BACKLOG_EPIC_A: BacklogEpicFixture = {
  key: 'ATLAS-EPIC-A',
  summary: '사용자 인증 개편',
}

/**
 * 에픽 B — **스프린트 칸**의 이슈가 소속된다.
 *
 * ### 왜 두 종이 필요한가
 * 한 종뿐이면 「A 를 고르면 B 는 사라진다」에 사라질 대조군이 없어, 에픽 축이 아무것도
 * 거르지 않는 오구현도 통과한다. 또 A 를 백로그 칸에, B 를 스프린트 칸에 나눠 둬야
 * 「필터가 **모든 섹션**에 동시에 걸린다」(F16-7)가 화면에서 갈린다.
 */
export const BACKLOG_EPIC_B: BacklogEpicFixture = {
  key: 'ATLAS-EPIC-B',
  summary: '검색 품질 개선',
}

/** 이름 해석 대상 에픽 전량 — `issue-handlers.ts` 가 이 배열만 보고 상세 응답을 만든다 */
export const BACKLOG_EPIC_FIXTURES: readonly BacklogEpicFixture[] = [BACKLOG_EPIC_A, BACKLOG_EPIC_B]

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — alice(userId=00000000-0000-4000-8000-000000000001) 로그인 기준
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 기본 백로그 픽스처 — ATLAS 프로젝트, backlog 이슈 2개, 스프린트 **3개**(ACTIVE·PLANNED·COMPLETED).
 *
 * UUID는 RFC4122 v4 형식 — Zod v4 z.string().uuid() 통과 보장.
 * alice(userId=00000000-0000-4000-8000-000000000001)가 ATLAS 프로젝트 BROWSE 가능 전제.
 *
 * ### ★ 왜 스프린트가 3개인가 (FR-UX-13 F15 FR-18)
 * 원래 `PLANNED` 1개뿐이었고, `COMPLETED` 문자열이 이 파일에 **0건**이었다. 그 상태에서는
 * 완료 다이얼로그의 「이관 대상에 COMPLETED 스프린트가 **없다**」는 단언이 **자동으로 참**이 돼
 * 아무것도 재지 않는다 (`unreachable-state-fixture-is-fake-green` — PR #342 에서 2회 적발).
 * 「없다」를 재려면 「있다」가 먼저 있어야 한다.
 * 또 이관 대상 Select 에 「백로그」 말고 **실제 선택지**가 있어야 「다른 스프린트로 이관」 경로가
 * 성립한다 — 그래서 완료되지 않은 스프린트가 2개(ACTIVE·PLANNED) 필요하다.
 *
 * ### ★ 에픽은 2종이고 **서로 다른 섹션**에 흩어져 있다 (FR-UX-13 F16)
 * `ATLAS-1`(백로그 칸) → {@link BACKLOG_EPIC_A}, `ATLAS-4`(PLANNED 스프린트 칸) →
 * {@link BACKLOG_EPIC_B}. 나머지 5건은 에픽 미지정이다.
 * 이 배치를 바꾸면 e2e 가 재던 것이 조용히 줄어든다 — 한 종으로 줄이면 「A 를 고르면 B 가
 * 사라진다」가, 한 섹션에 몰면 「모든 섹션에 동시 적용」이, 미지정을 0건으로 만들면
 * 「에픽 없음」축이 각각 재지 못하는 상태가 된다. `e2e/backlog.spec.ts` 의
 * 「F16 에픽 픽스처」 tripwire 가 세 조건을 전부 지킨다.
 *
 * ### 배열 순서 = 백엔드 정렬 순서
 * `ACTIVE → PLANNED → COMPLETED` 로 둔다. 백엔드 `sprintComparator`
 * (`BacklogApplicationService.kt:209-214`)가 그 순서로 내려주고, 클라이언트는 스프린트를
 * **정렬하지 않기** 때문이다(스펙 FR-1). 목이 다른 순서를 주면 「응답 순서를 그대로 그린다」가
 * 화면에서 검증되지 않는다.
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
      // ★백로그 칸의 유일한 에픽 소속 이슈 (F16). 스프린트 쪽 ATLAS-4 와 **다른 에픽**이라
      //   「A 를 고르면 B 는 사라진다」와 「모든 섹션에 동시 적용」을 함께 잴 수 있다.
      epicKey: BACKLOG_EPIC_A.key,
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
    // ── ACTIVE — 완료 다이얼로그를 여는 유일한 트리거이자 「다른 스프린트로 이관」 대상 ──
    // 이름이 '스프린트 1' 로 시작하면 e2e 의 `^` 앵커 칸 조회(`/^스프린트 1/`)와 겹친다.
    // 접두 충돌이 없는 이름을 쓴다.
    {
      sprint: {
        sprintId: 'a0000000-0000-4000-8000-000000000002',
        name: '진행 중 스프린트',
        goal: '진행 중 스프린트 목표',
        status: 'ACTIVE',
        startDate: '2026-06-01',
        endDate: '2026-06-14',
        version: 1,
      },
      issues: [
        {
          key: 'ATLAS-5',
          summary: '다섯 번째 이슈 — 알림 설정 화면',
          currentStateKey: 'open',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          priority: 1,
          rank: '0|hzzzzz:',
          version: 0,
          epicKey: null,
        },
        {
          key: 'ATLAS-6',
          summary: '여섯 번째 이슈 — 검색 결과 페이지',
          currentStateKey: 'open',
          assigneeId: null,
          priority: 2,
          rank: '0|i00007:',
          version: 0,
          epicKey: null,
        },
      ],
    },
    // ── PLANNED — 시작 다이얼로그 트리거. 기존 e2e 가 이 sprintId·이름·이슈 키에 묶여 있다 ──
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
          // ★스프린트 칸의 유일한 에픽 소속 이슈 (F16). 백로그 쪽 ATLAS-1 과 짝이다 —
          //   둘이 다른 섹션에 있어야 「에픽 필터가 스프린트 섹션도 좁힌다」가 성립한다.
          epicKey: BACKLOG_EPIC_B.key,
        },
      ],
    },
    // ── COMPLETED — 이관 대상 목록에서 **제외돼야 하는** 스프린트 ──
    // 넣으면 반드시 409 로 끝나는 선택지가 되고(`SprintController.kt:244`), 그 이슈는
    // 꺼낼 수도 옮길 수도 없다(ADR C1 영구 동결). 그 「제외」를 재려면 실물이 있어야 한다.
    {
      sprint: {
        sprintId: 'a0000000-0000-4000-8000-000000000003',
        name: '완료된 스프린트',
        goal: null,
        status: 'COMPLETED',
        startDate: '2026-05-01',
        endDate: '2026-05-14',
        version: 2,
      },
      issues: [
        {
          key: 'ATLAS-7',
          summary: '일곱 번째 이슈 — 지난 스프린트에서 끝낸 일',
          currentStateKey: 'done',
          assigneeId: '00000000-0000-4000-8000-000000000001',
          priority: 3,
          rank: '0|hzzzzz:',
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
