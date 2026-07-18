# FR-UX-06 Phase 1 PR4 — 하드코딩 색 → 시맨틱 토큰

> slug: fr-ux-06-pr4-color-tokens
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-19
> 상위 계획: docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md (PR4)

## Brief

apps/web의 하드코딩 Tailwind 색 리터럴(text-amber-800 등)을 PR3가 도입한 시맨틱
토큰(--warning/--success/--danger/--info)으로 이관한다. 기대치 = 시각적으로 거의 no-op.

**전수 정찰 실측(controller, 착수 전).**
- 총 174건 = 팔레트 리터럴 173 + arbitrary oklch 1 / 32 프로덕션 파일(테스트 2 별도).
- 분포: amber 105 · green/emerald 29 · red 16 · blue 11 · yellow 5 · violet 2 · 기타(teal/slate/purple/neutral/gray) 5.
- plan(상위)의 "141건/29파일"은 과소 — 발생 건수 미집계. classify-task도 auth로 오분류(정정함).

**범위 확정(Maxi 결정, 2026-07-19).** 표준.
- 상태(status) 시맨틱 색 ~155건 전부 → --warning/--success/--danger/--info.
  - bold 배경(예: bg-amber-600 text-white) → bg-warning text-warning-foreground
  - tint 배경(예: bg-amber-100 text-amber-800, 배너 bg-amber-50) → bg-warning/10 text-warning (DESIGN.md §C note 98)
  - 손수 짠 dark: 페어(dark:text-amber-300 등) 삭제 — 시맨틱 토큰이 다크 자동 처리.
- badge.tsx 프리미티브 내부 토큰화(color API·badge.test.tsx 계약 유지).
- 범주(categorical) 색 ~14건은 이연 → PR22(--chart-* 동결). 대상:
  TimelineRow 이슈타입 점(epic/story/task/bug/fallback + today-marker),
  EpicProgressBar 진행바(emerald-500/blue-400), WeekGrid 캘린더 스와치(slate/blue/emerald/violet),
  WorkflowDiagram oklch category_done.

**주의(회귀 방지).**
- BacklogBoard 배너는 PR3가 이미 border-warning bg-warning/10로 이관 완료 → 중복 금지.
- button ghost dark:hover:bg-muted/50 이중감쇠는 터치 시 정리(contrast-matrix 파생 2).
- contrast-matrix-state-vs-surface-blindfold: codereview에서 상태배경↔표면 2축 대비 adversarial 검증.
- WorkflowSchemeSidebar arbitrary bg-[oklch(0.94_0_0)] → 뉴트럴(--accent/--bg-neutral-hover) 매핑.

## 도메인 정리

- BC: personalization (논리) / apps/web 프론트 (물리). FR-UX-06.
- 영향 엔티티: 없음 (순수 프레젠테이션 레이어 — CSS 클래스 문자열 교체).
- 새 용어: 0건. "시맨틱 토큰"(--warning 등)은 디자인 시스템 구현 개념으로 DESIGN.md에 이미 정의됨. glossary의 "토큰"은 공유/캘린더 자격증명 토큰이라 무관.
- 기존 결정 충돌: 없음. 본 PR은 ADR `2026-07-17-fr-ux-06-jira-redesign`(D1~D8)의 순수 적용.
- 관련 ADR: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (D2 색=ADS v2, DESIGN.md §C 페어링 규칙). 신규 ADR 0건.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
