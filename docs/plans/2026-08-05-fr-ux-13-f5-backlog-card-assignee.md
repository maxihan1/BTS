# FR-UX-13 F5 — 백로그 카드 담당자·에러 상태 봉합

> slug: fr-ux-13-f5-backlog-card-assignee
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning
> 생성: 2026-08-05

## Brief

**사용자 원문.** `FR-UX-13 F5 백로그 카드 담당자·에러 상태 봉합`

**정본 근거.** `docs/plan/product/personalization.md` §4.11 FR-UX-13 (백로그 사용성).
승계 PR 3건 중 **F5 단독** — F15(세로 스택·스프린트 다이얼로그·키보드 DnD)·F16(필터바·에픽 패널)은
로드맵 임계경로(`B2 → F14 → F15 → F16`)상 FR-UX-14 뒤라 이번 범위 밖.

**정본이 적시한 결함 2건.**
1. 백로그/스프린트 카드의 담당자가 **전원 `?`(이름 미확인)로 렌더** — 빈 `Map` 을 만들어 그대로
   넘기고 채우는 코드가 없다(`BacklogBoard.tsx:217`). 보드는 정상이고 백로그만 누락.
2. 조회 실패 시 **빈 `<div/>`** 반환 — 에러 안내도 재시도도 없다(`:214`).

**정본이 지목한 처방.** `board.tsx:333` 의 `useUsersByIds` 조립 패턴을 복제.

**classify 결과 + 컨트롤러 정정 2건.**
- type=ui · agent=frontend-engineer (정본 §4.11 D6 책임과 일치, 변경 없음)
- primary_bc. `project-workflow` → **`agile-planning`** ('상태' 가 워크플로우 신호로 오견인.
  데이터 소유자는 agile-planning)
- slug. `fr-ux-13-f5` → **`fr-ux-13-f5-backlog-card-assignee`** (정본 Plan slug
  `fr-ux-13-backlog-usability` 는 FR 전체용이라 후속 F15·F16 과 충돌. 선례
  `fr-ux-12-f13-global-search-input` 의 `fr-ux-NN-fM-<서술>` 규칙 승계)

**선행 읽기에서 고른 관련 교훈 4건** (`/bts-codereview` 발췌 주입에 재사용).
- 2026-05-31 **UI PR 이 E2E 를 후속으로 미루면 기존 E2E 회귀가 머지 시점에 잠복** — 같은 화면
  (`backlog.spec.ts`)을 이 PR 에서 반드시 함께 돌린다
- 2026-05-30 Zod 응답 스키마 강화가 산재한 인라인 mock 을 깬다 (계약갭 역방향)
- 2026-05-30 메타 mutation `setQueryData`(부분응답)가 본문을 placeholder 로 덮는 플리커
- 2026-05-26 E2E 셀렉터는 i18n 정본 import (hardcoded string drift 차단)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
