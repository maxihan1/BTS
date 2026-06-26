// 타임라인 MSW 픽스처 — 기본 시드 데이터 (FR-TL-01 D6 Task-4)
//
// 교훈 반영.
//   - frontend-zod-backend-dto-contract-gap: Zod 스키마 백엔드 DTO 정확 미러
//   - msw-derived-behavior-shared-store-e2e: 정적 반환 — board stateful store 아님
//   - fr-bd-01: 신규 MSW 모듈 자동 시드 필수 (MODE!=='test' 게이팅)
//
// 정렬 규칙: startDate ASC NULLS LAST → dueDate ASC NULLS LAST → key ASC
// (백엔드 TimelineController.kt 정렬 순서 미러)

import type { TimelineItem } from '@/api/timeline'

// ─────────────────────────────────────────────────────────────────────────────
// UUID 상수 — user-fixtures.ts 와 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** userAliceFixture.id 와 동기화 */
const ALICE_USER_ID = 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f'

/** userBobFixture.id 와 동기화 */
const BOB_USER_ID = 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a'

// ─────────────────────────────────────────────────────────────────────────────
// BTS 프로젝트 기본 타임라인 아이템 목록
//
// 구성.
//   - Epic 1개 (BTS-1): startDate="2026-07-01", dueDate="2026-09-30", epicKey=null
//   - 자식 Story (BTS-2): epicKey="BTS-1", startDate="2026-07-01", dueDate="2026-07-31"
//   - 자식 Task (BTS-3): epicKey="BTS-1", startDate="2026-08-01", dueDate="2026-08-31", targetDate="2026-08-25"
//   - start만 케이스 (BTS-4): startDate="2026-07-15", dueDate=null (개방 막대 EC1)
//   - due만 케이스 (BTS-5): startDate=null, dueDate="2026-10-31" (개방 막대 EC1, NULLS LAST)
//   - 미분류 이슈 (BTS-6): epicKey=null, issueType=task (epic과 무관)
//
// 정렬 순서.
//   startDate 있는 것 먼저 (ASC): BTS-1(07-01), BTS-2(07-01), BTS-4(07-15), BTS-3(08-01)
//   startDate 같으면 dueDate ASC → BTS-2(07-31) < BTS-1(09-30) → BTS-2 앞
//   startDate null NULLS LAST: BTS-5(null→마지막 그룹), BTS-6(null→마지막 그룹)
//   null끼리는 dueDate ASC: BTS-5(10-31), BTS-6(null) → BTS-6 마지막
// ─────────────────────────────────────────────────────────────────────────────

/**
 * BTS 프로젝트 기본 타임라인 픽스처 아이템 목록.
 *
 * 백엔드 정렬 순서(startDate ASC NULLS LAST → dueDate ASC NULLS LAST → key ASC) 준수.
 * 케이스 커버.
 *   - Epic + 자식(Story/Task)
 *   - targetDate 마일스톤 (BTS-3)
 *   - start만/due만 개방 막대 (EC1)
 *   - 미분류(epicKey=null, non-epic) 이슈
 *
 * @see 백엔드 계약 PR #192 — TimelineController.kt
 */
export const BTS_TIMELINE_ITEMS: TimelineItem[] = [
  // startDate="2026-07-01", dueDate="2026-07-31" → 첫 번째 그룹, dueDate 짧은 순
  {
    key: 'BTS-2',
    summary: '사용자 인증 스토리',
    issueType: 'story',
    currentStateKey: 'IN_PROGRESS',
    assigneeId: ALICE_USER_ID,
    startDate: '2026-07-01',
    dueDate: '2026-07-31',
    targetDate: null,
    epicKey: 'BTS-1',
  },
  // startDate="2026-07-01", dueDate="2026-09-30" → 같은 그룹, dueDate 긴 순
  {
    key: 'BTS-1',
    summary: '인증 시스템 에픽',
    issueType: 'epic',
    currentStateKey: 'IN_PROGRESS',
    assigneeId: ALICE_USER_ID,
    startDate: '2026-07-01',
    dueDate: '2026-09-30',
    targetDate: null,
    epicKey: null,
  },
  // startDate="2026-07-15" → 두 번째 그룹
  {
    key: 'BTS-4',
    summary: 'start만 있는 케이스 (개방 막대)',
    issueType: 'task',
    currentStateKey: 'TODO',
    assigneeId: null,
    startDate: '2026-07-15',
    dueDate: null,
    targetDate: null,
    epicKey: 'BTS-1',
  },
  // startDate="2026-08-01" → 세 번째 그룹, targetDate 마일스톤 포함
  {
    key: 'BTS-3',
    summary: '권한 관리 태스크',
    issueType: 'task',
    currentStateKey: 'TODO',
    assigneeId: BOB_USER_ID,
    startDate: '2026-08-01',
    dueDate: '2026-08-31',
    targetDate: '2026-08-25',
    epicKey: 'BTS-1',
  },
  // startDate=null (NULLS LAST), dueDate="2026-10-31" → null 그룹, dueDate 빠른 순
  {
    key: 'BTS-5',
    summary: 'due만 있는 케이스 (개방 막대)',
    issueType: 'bug',
    currentStateKey: 'TODO',
    assigneeId: null,
    startDate: null,
    dueDate: '2026-10-31',
    targetDate: null,
    epicKey: null,
  },
  // startDate=null, dueDate=null → 완전 미분류, 맨 끝
  {
    key: 'BTS-6',
    summary: '날짜 없는 미분류 태스크',
    issueType: 'task',
    currentStateKey: 'TODO',
    assigneeId: null,
    startDate: null,
    dueDate: null,
    targetDate: null,
    epicKey: null,
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// TRUNCATED 시나리오 픽스처 — 500개 초과로 일부 누락된 상황 시뮬레이션
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TRUNCATED 프로젝트 타임라인 픽스처.
 *
 * 이슈가 500개를 초과해 일부가 누락된 상황을 시뮬레이션한다.
 * truncated=true 배너 표시 시나리오 검증용.
 */
export const TRUNCATED_TIMELINE_ITEMS: TimelineItem[] = [
  {
    key: 'LARGE-1',
    summary: '에픽 — 대규모 프로젝트',
    issueType: 'epic',
    currentStateKey: 'IN_PROGRESS',
    assigneeId: ALICE_USER_ID,
    startDate: '2026-01-01',
    dueDate: '2026-12-31',
    targetDate: null,
    epicKey: null,
  },
  {
    key: 'LARGE-2',
    summary: '스토리 — 부분 포함',
    issueType: 'story',
    currentStateKey: 'IN_PROGRESS',
    assigneeId: BOB_USER_ID,
    startDate: '2026-01-01',
    dueDate: '2026-06-30',
    targetDate: null,
    epicKey: 'LARGE-1',
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 로드 시 자동 시드 — fr-bd-01 교훈 (신규 MSW 모듈 자동 시드 필수)
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 직접 제어.
// dev(pnpm dev) · E2E 환경에서는 자동 시드로 빈 화면 방지.
// ─────────────────────────────────────────────────────────────────────────────

// 이 fixtures 모듈은 순수 데이터 상수만 export하며 store가 없다 (정적 핸들러 패턴).
// MODE 게이팅은 timeline-handlers.ts 에서 처리한다.
