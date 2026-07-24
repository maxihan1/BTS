# FR-UX-06 PR21 — @dnd-kit/sortable 보드 컬럼 내 카드 순서변경

> slug: fr-ux-06-pr21-dnd-kit-sortable
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning
> 생성: 2026-07-25

## Brief

사용자 원문: FR-UX-06 PR21 — @dnd-kit/sortable 보드 컬럼 내 카드 순서변경

classify 결과: type=ui, agent=frontend-engineer, primary_bc=agile-planning, slug=fr-ux-06-pr21-dnd-kit-sortable

FR-UX-06(BTS UI/UX Jira Cloud 방식 개편) Phase 5(화면)의 다섯 번째 PR. 칸반 보드에서 같은 컬럼 안 카드들의 순서를 드래그로 바꾸는 기능. agile-planning BC는 이미 @dnd-kit·LexoRank 인프라 보유.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: 주 = agile-planning(보드 소유), 종속 = issue-tracking(rank 컬럼·리랭크 API 소유, **재사용만·변경 0**), 프론트 apps/web.
- **grill-with-docs 생략**: 도메인 모델(LexoRank·rank 소유권)이 ADR로 이미 확립, 새 엔티티/용어 0(PR20 선례와 동일 근거).

### 현 상태 실측 (코드 대조)

| 항목 | 현재 상태 | 근거 |
|---|---|---|
| 보드 컬럼 간 이동(상태 전이) | ✅ 구현됨 | `KanbanBoard.tsx` `resolveDropAction`→`useMoveCard`(`POST /boards/{id}/cards/{key}/move`) |
| **보드 컬럼 내 순서변경** | ❌ **미구현 — 같은 컬럼 드롭 = noop(EC1)** | `KanbanBoard.tsx:84` `fromColumnId === toColumnId → noop` |
| 보드 카드 응답 rank 노출 | ❌ **없음** | `BoardResponses.kt` `BoardCardResponse`(issueKey/summary/assigneeId/priority/version/epicKey), rank 필드 부재 |
| 보드 카드 컬럼 내 정렬 기준 | ❌ rank 정렬 아님 | `BoardRepository.kt` 컬럼만 DISPLAY_ORDER 정렬, 카드 정렬 기준 없음 |
| `issues.rank` 컬럼 + 리랭크 API | ✅ **완비(재사용 가능)** | `PATCH /api/v1/issues/{key}/rank`(FR-BL-01), `BacklogRankService.rerank`(이웃 중간값+rebalance), `issues.rank` TEXT |
| 프론트 리랭크 API 클라이언트 | ⚠️ backlog.ts에만 존재 | `api/backlog.ts`(백로그용), `api/boards.ts`엔 rank 함수 없음 |
| `@dnd-kit/sortable` 설치 | ❌ **미설치** | package.json에 `@dnd-kit/core`·`@dnd-kit/utilities`만, sortable 없음 |

### 기존 결정과의 정합

- **LexoRank ADR(2026-06-23 FR-BL-01) 결정2·결정4가 "미래 보드 컬럼 내 정렬(agile-planning)에서도 재사용 가능"을 이미 명시** → PR21이 정확히 그 예견된 케이스. 결정 충돌 없음.
- rank 소유권(issue-tracking) 불변. 보드는 조회 시 rank를 미러 노출만(백로그 `BacklogResponses.kt` 선례와 동일 패턴).

### ADR 후보

- 보드 조회 rank 노출/정렬 + 컬럼 내 리랭크가 기존 리랭크 API 재사용임을 기록 (신규 리랭크 경로 없음). BC 경계: agile-planning 조회 view layer가 issue-tracking rank를 미러(백로그 선례).

### ★ 스코프·의존성·FR 귀속 — Maxi 확정 (2026-07-25)

1. **스코프 = 풀스택 완제품** ✅ — agile-planning 보드 조회에 rank 노출/정렬 추가 + 프론트 `@dnd-kit/sortable` + 기존 리랭크 API(`PATCH /api/v1/issues/{key}/rank`) 재사용. issue-tracking 변경 0(조회만).
2. **FR 귀속 = FR-UX-06 Phase 5** ✅ — FR 총수 **129 불변**. product 문서 D단계 마킹만(신규 FR 없음). 22 PR 체인 PR21에 편입.
3. **`@dnd-kit/sortable` 승인** ✅ — @dnd-kit/core(6.3.1)·utilities 공식 패밀리 확장, core 버전과 호환. SortableContext로 컬럼 내 정렬.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
