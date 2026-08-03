# FR-UX-11 F8 — 이슈 상세 인라인 편집

> slug: fr-ux-11-f8-inline-edit
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (논리 소속은 personalization — ADR §D2 논리 ≠ 물리)
> 생성: 2026-08-03

## Brief

**사용자 원문.** `/bts fr-ux-11 구현`

**범위 확정 (Maxi 확정 2026-08-03).** FR-UX-11 은 F8 + F9 두 PR 이고 F9 가 F8 에 의존한다
(`docs/design/jira-parity-roadmap.md:60-61` · `~/.claude/plans/ui-ux-sorted-kay.md:88-89` 실측).
**이번 PR 은 F8 만** 담는다. F9(이슈 목록 셀 인라인 편집)는 별도 PR.

**F8 내용.** 이슈 상세 화면에서 제목/본문을 클릭해 편집 진입, Enter 저장, Esc 취소.
정본이 지목한 대상은 `routes/issues.$key.tsx:704-726` · `IssueDescription.tsx:123-144`.
백엔드 변경 0 예상 — 기존 이슈 PATCH API 소비.

**하류 효과.** F8 은 F9 와 §4.8 FR-UX-10 의 F11(상세 액션 단축키)의 **공통 선행**이다.
현재 FR-UX-10 은 D6/D7 이 `[ ]` 로 F11 을 기다리는 상태(PR #336).

**classify 정정 1건.** `classify-task.ts` 초회 실행이 `type=backend`/`backend-engineer` 를
냈다 — 제목에 UI 키워드(`UI_KEYWORDS`)도 경로 패턴(`apps/web/`)도 없어 **신호 0 → backend
기본값**으로 떨어진 것이다. 실제 작업은 `apps/web` 프론트 전용이라 제목을 정정해
`type=ui`/`frontend-engineer`/`slug=fr-ux-11-f8-inline-edit` 로 재분류했다.

## 착수 전 확보한 선행 교훈

- `learnings.md:616` **메타 mutation `setQueryData`(부분 응답)가 본문을 placeholder 로 덮는 플리커**
  (PR #46). PATCH 응답의 `descriptionHtml` 은 항상 null 이라 캐시 전체 교체 시 본문이 사라진다.
  **처방은 invalidate-only 통일.** 인라인 편집은 정확히 이 경로를 다시 밟는다.
- `learnings.md:631` **UI PR 이 E2E 를 미루면 기존 E2E 회귀가 머지 시점에 잠복** (PR #47).
  같은 화면에 저장 버튼이 늘어 `getByRole('button',{name:'저장'})` 이 strict mode violation.
  **이슈 상세는 이미 그 사고가 난 화면이다** — 인라인 편집이 저장 버튼/편집 진입점을 또 늘린다.
- `docs/design/jira-parity-roadmap.md:300` F8 은 **시각/조작 변화 PR** 목록 — 머지 전
  실제 브라우저 확인(라이트/다크 양쪽) 대상이다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
