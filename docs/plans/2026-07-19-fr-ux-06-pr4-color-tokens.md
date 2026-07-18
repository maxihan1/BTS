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

## 스펙

전체 스펙. [docs/specs/2026-07-19-fr-ux-06-pr4-color-tokens.md](../specs/2026-07-19-fr-ux-06-pr4-color-tokens.md)

핵심 요약.
- IN-SCOPE **95건 / 31파일** 상태 팔레트 리터럴 → 시맨틱 토큰(warning 64·success 16·danger 10·info 3·accent/card 2).
- **★ status-text 토큰 4종 신설**(index.css): tint 위 색 텍스트는 bold 토큰(AA 미달)이 아니라 `text-{status}-text`(라이트 -800/다크 -300, AA 실측 통과). 원본 `text-{c}-800` 근사 = no-op.
- 범주색(~25, 차트 포함)·캘린더 coordinated(3)·스크림(~44)·mermaid(3) = DEFER/OUT.
- 손수 짠 `dark:` 색 페어 삭제(순 LOC 감소).

## Brainstorming Check

✅ 통과 — 울트라코드 4렌즈 adversarial 검증. BLOCKER 1(tint 텍스트 라이트 AA 4.1<4.5 → status-text 토큰 신설로 해소, Maxi Option A)·CONCERN 2(tint-on-tint 중첩 배지 붕괴 → 내부 배지 bold)·완결성 갭 3(recharts 차트·오버레이 스크림·mermaid .ts를 DEFER/OUT에 편입)·매핑 오배정 0.

## Plan

> 규칙셋·매핑·DEFER/OUT은 스펙 참조. IN-SCOPE 95건/30파일(WorkflowDiagram L82는 OUT). 커밋 타입:
> Task 1 = `test:`→`feat:`(토큰 신설). 색텍스트 어서션 있는 파일 = `test:`(어서션 갱신, RED)→`refactor:`(이관, GREEN).
> 나머지 className 스왑 = `refactor:`(동작 보존). DEFER/OUT 파일은 절대 수정 금지.

### Task 1. status-text 토큰 4종 신설 + 배선 (FOUNDATION)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/__tests__/state-tokens.test.ts`, `apps/web/src/index.css`, `DESIGN.md`]
- depends-on: []

**RED**: `state-tokens.test.ts`에 어서션 추가 — `:root` 4값(`--success-text:#216E4E`·`--warning-text:#7F5F01`·`--danger-text:#AE2A19`·`--info-text:#0055CC`), `.dark` 4값(`#7EE2B8`·`#F5CD47`·`#FF9C8F`·`#85B8FF`), `@theme inline` 4배선(`--color-success-text:var(--success-text)` 등), 중복선언 throw. 실패(토큰 부재).

**GREEN**: `index.css` `:root`·`.dark`에 4토큰씩 + `@theme inline`에 `--color-*-text` 4배선. 기존 §C 토큰 인접 배치.

**REFACTOR**: `DESIGN.md` §C 표에 status-text 4행 추가 + "텍스트 페어링" 절에 "tint 위 색 텍스트 = `text-{status}-text`(bold `-foreground` 아님, 라이트 -800/다크 -300, AA≥4.9), bold 배경 = `text-{status}-foreground`" 명시. §10 대비표에 status-text/tint AA 행 추가.

**검증**: `pnpm --filter web test -- state-tokens` GREEN. 이 4토큰은 Task 2~7이 실소비(소비자0 아님).

### Task 2. admin/ 이관 (7파일, 색텍스트 테스트 포함)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/admin/MappingTable.tsx`, `apps/web/src/components/admin/NotificationPolicyTable.tsx`, `apps/web/src/components/admin/SchemeMetaPanel.tsx`, `apps/web/src/components/admin/WebhookTable.tsx`, `apps/web/src/components/admin/WorkflowSchemeSidebar.tsx`, `apps/web/src/components/admin/WebhookDeliveryTable.tsx`, `apps/web/src/components/admin/__tests__/WebhookDeliveryTable.test.tsx`]
- depends-on: [1]

**RED**: `WebhookDeliveryTable.test.tsx` — 양성 어서션 `bg-green-100`→`bg-success/10`·`bg-red-100`→`bg-danger/10`, **부정 어서션 L81/82도** `not.toContain('bg-success/10')`·`('bg-danger/10')`로 갱신(vacuous 방지). 구 impl에 대해 실패.

**GREEN/REFACTOR**: 각 파일 이관 — tint 배지 `bg-{status}/10 text-{status}-text`, bold=`text-{status}-foreground`. 특수: **MappingTable L41 `DEFAULT_BADGE_CLASS`는 bold**(`bg-warning text-warning-foreground`, L34 row 위 중첩 붕괴 회피), L34 row→`bg-warning/10`, L37 text→`text-warning-text`. WorkflowSchemeSidebar L102 `bg-[oklch(0.94_0_0)]`→`hover:bg-accent`. dark 페어 삭제.

**검증**: `pnpm --filter web test -- WebhookDeliveryTable` GREEN + admin 파일 잔여 상태팔레트 grep 0.

### Task 3. automation/ 이관 (7파일)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/RuleConflictWarningModal.tsx`, `apps/web/src/components/automation/RuleExecutionTraceRow.tsx`, `apps/web/src/components/automation/AutomationRuleFormDialog.tsx`, `apps/web/src/components/automation/AutomationYamlImportDialog.tsx`, `apps/web/src/components/automation/GitWebhookRegisterDialog.tsx`, `apps/web/src/components/automation/GitWebhookUrlModal.tsx`, `apps/web/src/components/automation/WebhookTokenModal.tsx`]
- depends-on: [1]

**RED/GREEN/REFACTOR**(refactor): tint→`bg-{status}/10 text-{status}-text`, 배너 border→`border-{status}`. 특수: **RuleConflictWarningModal L48 배지는 bold**(`bg-warning text-warning-foreground`, L47 배너 위 중첩). RuleExecutionTraceRow danger 유지(L124 destructive 불변). AutomationYamlImportDialog `AMBER_WARNING_BOX_CLASS`(L71) 상수 1곳 수정. **`bg-black/40` 스크림은 OUT — 건드리지 말 것**. dark 페어 삭제.

**검증**: automation 파일 잔여 상태팔레트 grep 0(스크림 `bg-black/40` 제외) + 기존 automation 테스트 GREEN.

### Task 4. settings/ + auth/ 이관 (4파일)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/settings/PatCreateForm.tsx`, `apps/web/src/components/settings/PatTokenModal.tsx`, `apps/web/src/components/settings/CalendarFeedCard.tsx`, `apps/web/src/components/auth/BackupCodesSection.tsx`]
- depends-on: [1]

**refactor**: amber 경고→`bg-warning/10 text-warning-text border-warning`. 특수: **BackupCodesSection L89 `bg-white dark:bg-neutral-900`→`bg-card`**(다크 카드감), L292 amber alert→`text-warning-text`. PatTokenModal `bg-black/40`=OUT. dark 페어 삭제.

**검증**: settings/auth 잔여 상태팔레트 grep 0 + 기존 테스트 GREEN.

### Task 5. backlog/ + board/ + issues/ 이관 (5파일)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/SprintColumn.tsx`, `apps/web/src/components/board/WipCountBadge.tsx`, `apps/web/src/components/issues/BulkOperationResultDialog.tsx`, `apps/web/src/components/issues/BulkTransitionDialog.tsx`, `apps/web/src/components/issues/NodeMappingSection.tsx`]
- depends-on: [1]

**refactor**: SprintColumn L54/59 상태배지→tint, **L130 bold 버튼 `bg-green-600 text-white hover:bg-green-700`→`bg-success text-success-foreground hover:bg-success/90`**. WipCountBadge amber→warning tint. Bulk* 다이얼로그 상태색→토큰(`bg-black/40`=OUT). dark 페어 삭제.

**검증**: 잔여 상태팔레트 grep 0 + 기존 테스트 GREEN.

### Task 6. ui/badge 프리미티브 이관 (2파일, 계약 테스트)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/badge.tsx`, `apps/web/src/components/ui/badge.test.tsx`]
- depends-on: [1]

**RED**: `badge.test.tsx` 어서션 — `bg-green-100`/`text-green-800`→`bg-success/10`/`text-success-text`, red→danger, (yellow→warning, blue→info 있으면). 구 impl 대해 실패.

**GREEN**: `badge.tsx` variant 내부색 교체(이름 API 유지): green→`bg-success/10 text-success-text`·red→danger·yellow→warning·blue→info. dark 페어 삭제.

**검증**: `pnpm --filter web test -- badge` GREEN.

### Task 7. dashboard/ + routes 이관 (5파일)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/ShareDashboardModal.tsx`, `apps/web/src/routes/dashboards.$dashboardId.tsx`, `apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/projects.$projectKey.timeline.tsx`, `apps/web/src/routes/projects.$projectKey.settings.workflow-scheme.tsx`]
- depends-on: [1]

**refactor**: 상태색→토큰. 특수: **workflow-scheme L168 `border-amber-500/40`→`border-warning/40`**(알파 보존). ShareDashboardModal `bg-black/40`=OUT. board/timeline route의 이슈타입/범주색이 섞여 있으면 상태색만 이관(범주=DEFER). dark 페어 삭제.

**검증**: 잔여 상태팔레트 grep 0(스크림/범주 제외) + 기존 route 테스트 GREEN.

## Plan 메타

- task 수: 7 (Task 1 FOUNDATION → Task 2~7 병렬 wave)
- 예상 wave: 2 (Task 1 단독 → Task 2~7 6-병렬, 파일 무겹침)
- TDD 강제: Task 1·2·6 = test→구현. Task 3·4·5·7 = refactor(동작 보존 className 스왑).
- 잔여 검증 스코프: **IN-SCOPE 30파일 + numbered 상태팔레트만**(bare `bg-black`/raw hex grep 금지 — 정당 잔존 스크림·차트 오탐).
- 추가 검증: typecheck, lint, vitest 전체, (E2E 색 결합 셀렉터 없음 사전확인).

## 리뷰 결과

**리뷰 방식**. spec 단계에서 이미 울트라코드 5-에이전트 adversarial(완결성2·매핑정확성·대비·vacuous) 완료 → plan은 구조 검증 중심(autoplan-overkill 회피).

**plan 구조 검증(controller, 기계적)**.
- ✅ IN-SCOPE 30파일 ↔ Task 2~7 files 메타 **집합 완전 일치**(30/30), 파일 중복 0.
- ✅ DEFER/OUT 파일(차트·스크림·범주·mermaid·`ui/dialog.tsx`) files 메타 미포함.
- ✅ 의존성: Task1(토큰)→Task2~7 전부 [1] 의존, 상호 무의존(파일 무겹침) → wave 2회.
- ✅ TDD: Task1(test→feat)·Task2·6(test→refactor) 정합, Task3·4·5·7 refactor(동작보존).
- ✅ 토큰 소비: status-text 4토큰을 Task2~7이 실소비(소비자0 아님).

**설계 검증(spec adversarial 승계)**.
- BLOCKER(tint 텍스트 라이트 AA 4.1<4.5) → status-text 토큰 Option A로 해소(Maxi 승인). -800/-300 실측 AA 통과.
- CONCERN 2(중첩 배지 붕괴) → 내부 배지 bold(Task2 MappingTable·Task3 RuleConflict).
- 완결성 갭 3(차트·스크림~44·mermaid) → DEFER/OUT 명시 편입(PR4 미이관).

**게이트1 판단 대기 항목**(Maxi).
1. status-text 토큰 신설(index.css/DESIGN.md/state-tokens.test) — PR3 토큰층 확장, full-sync 대상.
2. 범주색·차트·스크림·캘린더 = PR22/후속 이연(변경 금지).
3. no-op 재정의: 이관 95건은 시각 등가, 단 SprintColumn bold버튼 hover 방향·BackupCodes 다크 카드감은 미세 변화(수용).
