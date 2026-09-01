# 스프린트 편집·삭제 UI + 보드 부채 정리

> 티어: T2
> slug: sprint-manage-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-01

## Brief

**Maxi 원문.** 「1~3번 한 pr로 처리 가능한거 아니야?」 → 「전부 한 PR」 선택.
가리키는 1~3번은 직전 응답의 목록이다.

1. 스프린트 이름 바꾸기 · 스프린트 지우기 (로드맵 A2)
2. 직전 PR #416 이 남긴 부채 4건 (장부 145~148)
3. 아직 장부에 없는 문제 2건 등재

**classify 결과.** `type=ui` · `agent=frontend-engineer` · `primary_bc=agile-planning` · `tier=T2`(선언).
frontend-engineer 가 기본 담당이지만 **백엔드 task 가 1개 있다**(아래) — 그 task 만 backend-engineer 로 지정한다.

**FR.** `FR-BL-02`(스프린트 CRUD) 의 **D6 프론트 범위 회수**. 신규 FR 없음 → **총수 불변 143**.
로드맵 `~/.claude/plans/playful-cooking-minsky.md` §FR 동기화 표가 A 를 「FR-BD-01-2 회수 +
FR-BL-02 D6 범위 회수 · 불변 143」으로 적었고, 앞의 것은 PR #416 이 이미 회수했다.

**착수 전 실측 1건 (이미 수행).** 로드맵 A2 계약이 *"COMPLETED 스프린트 제약이 백엔드에 있는지
착수 시 실측하라"* 를 남겼다. **없다.** `SprintApplicationService.update(:137)` 에 상태 가드가 없고,
`assignIssue(:326)` 만 `ASSIGN_BLOCKED_BY_COMPLETED` 로 막는다. Jira 는 완료 스프린트에서 이름·목표만
허용하고 날짜를 잠근다. 로드맵 지시대로 **프론트 잠금으로 흉내내지 않고 백엔드 계약을 red 로 먼저
고정**한다 → 이 PR 이 순수 프론트가 아닌 이유.

**범위 밖.** WIP 제한 편집(로드맵 B) · 보드 설정 화면(B) · P0 판별식(FR-WF-07 D6 대기) ·
PR #417(다른 세션 소관 · FR-WF-07 D4).

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
