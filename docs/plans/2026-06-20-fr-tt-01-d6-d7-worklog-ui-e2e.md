# FR-TT-01 D6/D7 — Worklog 프론트 UI + E2E

> slug: fr-tt-01-d6-d7-worklog-ui-e2e
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (백엔드), 프론트 apps/web
> 생성: 2026-06-20

## Brief

FR-TT-01 — Worklog (추정/실제/잔여 시간)의 **D6(프론트 UI) + D7(E2E)**. 백엔드 D1~D5는 #163으로 머지 완료.

- 원문: "fr-tt-01 d6, d7 진행해줘"
- classify: type=qa 오판정 → **ui 교정**(D6 프론트가 주작업, D7 E2E는 qa-engineer 단일 task). 선례 #155/#158/#164.
- product: docs/plan/product/agile-planning.md §5.1
  - [ ] D6. 프론트 UI — Worklog 입력 폼 + 잔여 시간 자동 계산 (designer → frontend-engineer)
  - [ ] D7. E2E (qa-engineer)
- 백엔드 계약(#163 산출): `POST/GET/PATCH/DELETE /api/v1/issues/{key}/worklogs`, 추정 PATCH(`PATCH /issues/{key}` — originalEstimateSeconds/remainingEstimateSeconds), IssueResponse 3필드(originalEstimateSeconds/timeSpentSeconds/remainingEstimateSeconds, `@JsonInclude(NON_NULL)`), 잔여 자동차감 max(0, remaining−timeSpent) 또는 newRemaining override

## 도메인 정리

- **BC**: issue-tracking (백엔드), 프론트는 apps/web
- **신규 도메인 변경**: 없음 — D6/D7은 #163이 확정한 백엔드 계약(엔드포인트·DTO 필드)을 프론트에서 소비하는 view layer + E2E. 새 엔티티/관계/불변식 0.
- **상속 ADR**: [docs/adr/2026-06-20-worklog-time-tracking-model.md](../adr/2026-06-20-worklog-time-tracking-model.md) (#163 생성, 변경 없음)
- **용어**: Worklog / Original Estimate / Time Spent / Remaining Estimate / Log Work — #163에서 도입. glossary는 수동 영역으로 미반영 상태(머지 게이트에서 Maxi 승인 시 일괄 추가 후보). D6/D7에서 신규 도메인 용어 추가 없음(UI 라벨은 도메인 용어 아님).
- **기존 결정 충돌**: 없음
- **grill-with-docs**: 스킵 — 도메인 모델 변경 0인 view layer 작업에 대화형 도메인 검증은 비용>효익(선례 #155/#164/#158 D6/D7 동일).

## 스펙

전체 스펙. [docs/specs/2026-06-20-fr-tt-01-d6-d7-worklog-ui-e2e.md](../specs/2026-06-20-fr-tt-01-d6-d7-worklog-ui-e2e.md)

**Maxi 확정(스펙 게이트)**: ① 시간=시간(h)+분(m) 분리 입력, 표시 "2h 30m" ② 풀 범위(추정 편집 + worklog 풀 CRUD + 잔여 자동계산).

핵심 시나리오 요약.
- 추정 카드(원추정/기록/잔여 "2h 30m" 표시 + original/remaining PATCH 3-state·OCC), timeSpent 읽기전용
- Worklog 섹션(목록 + 추가/수정/삭제, 본인만 수정/삭제) — AttachmentSection 패턴, 자체 query/mutation
- 잔여 자동차감 미리보기(자동=newRemaining undefined / 직접지정=값), startedAt datetime-local↔ISO
- ★ worklog mutation 후 issue query cross-invalidate(추정 카드 갱신, FR9)

## Brainstorming Check

✅ 통과 (자체 적대적 갭 분석, 8 갭 보강). 핵심.
- G8 cross-invalidate(worklog→issue query, 추정 카드 stale 방지) → FR9
- G7 Zod `.nullable().optional()`(mock fanout 회피, startDate 선례)
- G2/G3 authorId→useUsersByIds / 현재사용자=whoami userId(WatchersSection 선례)
- G9 D7 MSW stateful store(자동차감 재현, reload 금지)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
