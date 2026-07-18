# FR-UX-06 PR4 — 하드코딩 색 → 시맨틱 토큰 스펙

> slug: fr-ux-06-pr4-color-tokens · type: ui · agent: frontend-engineer · 2026-07-19
> 상위: docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md (PR4) · ADR docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md (D2)
> 정본 규칙: DESIGN.md §2 §C (페어링), §사용 가이드

## 배경

PR3(#287)이 ADS v2 시맨틱 토큰(`--warning`/`--success`/`--danger`/`--info` + foreground 4쌍)을
index.css에 도입했다. PR4는 apps/web에 흩어진 하드코딩 Tailwind 색 리터럴을 이 토큰으로 이관한다.
**기대치 = 이관 대상 96건에 대해 시각적으로 거의 no-op.** 어긋나면 곧 버그.

## 전수 열거 (울트라코드 35파일 병렬 추출, Workflow wf_18f6278b-266)

grep 줄수(173)가 아니라 **논리 발생 단위**로 셌다(base + 그 `dark:` 페어 = 1건). 총 122건.
grep 정규식이 놓쳤던 **`bg-black/40` 모달 스크림 11건**을 병렬 추출이 잡아냈다
(`black`은 숫자 셰이드가 없어 팔레트 정규식에 안 걸림 — spec-stated-count-becomes-blindfold 재현).

| 버킷 | 건수 | 처리 |
|---|---|---|
| IN-SCOPE (상태 시맨틱 + 명확 뉴트럴) | 96 / 31파일 | 이관 |
| DEFER — 범주(categorical) | 11 | PR22 (`--chart-*` 동결) |
| DEFER — 캘린더 coordinated 세트 | 3 | PR22 (구조 결합·no-op 아님) |
| OUT — 오버레이 스크림 | 11 | 유지 (대응 토큰 부재) |
| OUT — mermaid light-only 테마 bg | 1 | 유지 |

IN-SCOPE 토큰 분포. warning 64 · success 17 · danger 10 · info 3 · accent 1 · muted 1.

## 정본 매핑 규칙셋 (FIXED — 발명 금지, 적용만)

**팔레트 family → 토큰.**
- amber / yellow / orange → `--warning`
- green / emerald / lime → `--success`
- red / rose → `--danger` (예외: `<input>` 하위 폼 검증 에러는 `--destructive` — 같은 hex, shadcn 관례)
- blue / sky → `--info` (예외: primary CTA 배경은 `--primary`)
- slate / gray / zinc / neutral / stone → 베이스 뉴트럴 (`bg-accent`/`bg-muted`, `text-muted-foreground`, `border-border`)

**페어링 변환 (DESIGN.md §C note 98).**
- Bold 배경(`bg-{c}-500~900` + `text-white`) → `bg-{token} text-{token}-foreground`. hover 진한 스텝은 `hover:bg-{token}/90`.
- Tint 배경(`bg-{c}-50~200` [/NN]) → `bg-{token}/10`.
- 배너 border(`border-{c}-200|300`) → `border-{token}`. off-spec 알파 셰이드(`border-amber-500/40`)는 `border-warning/40`로 알파 보존.
- tint 위 색 텍스트(`text-{c}-600~900`) → `text-{token}`.
- text-only(`text-{c}-700|800`) → `text-{token}`.
- **핵심: 손수 짠 `dark:` 색 페어는 전부 삭제** — 시맨틱 토큰이 다크를 자동 처리(순 LOC 감소).

**badge.tsx 프리미티브.** variant 이름(API) blue/green/red/yellow 유지, 내부만 교체:
green→success · red→danger(폼 아님) · yellow→warning · blue→info. tint 패턴 `bg-{token}/10 text-{token}`, dark 페어 삭제.

## 엣지 케이스 해소

1. **destructive vs danger.** 상태 배지·알림 배너·발송상태(FAILED)·성공실패 아이콘 = `--danger`. `<input>` 폼 검증 에러 메시지 = `--destructive`(기존 유지). RuleExecutionTraceRow가 한 파일에서 둘 다 씀(L39/L119 danger, L124 destructive 유지) — 정상.
2. **부정 테스트 어서션 vacuous 위험.** WebhookDeliveryTable.test.tsx L81/L82 `not.toContain('bg-green-100')`/`('bg-red-100')`는 PENDING(중립) 배지가 상태색이 없음을 검증. 구현이 `bg-success/10`로 바뀌면 **부정 대상 문자열도 함께 갱신**해야 항상통과(vacuous) 회귀가 안 남 (guard-handler-matrix-blindfold / negative-guard-needs-body-discriminator).
3. **테스트 어서션 lockstep.** badge.test.tsx(4) + WebhookDeliveryTable.test.tsx(4) 어서션은 구현 색 교체와 **같은 task**에서 갱신. API(render)는 불변.
4. **공유 상수.** AutomationYamlImportDialog `AMBER_WARNING_BOX_CLASS`(L71) 한 곳 수정으로 L158/L212 동시 반영(중복 아님). MappingTable `DEFAULT_*_CLASS`(L34/L37/L41) 3상수도 동일.
5. **MappingTable amber=강조 의미.** 엄밀히 warning 상태는 아니나(기본 매핑 행 emphasis), 규칙셋대로 `--warning` 매핑(시각 등가 유지). row 전체(bg+text+badge)가 같은 amber 의미 공유.
6. **WeekGrid IN_PROGRESS/DONE bold-bar의 공유 `text-white`(L194).** 3색이 단일 text-white 공유 → bold 변환 시 카테고리별 foreground 분리라는 **구조 변경** 필요. no-op 아님 + coordinated 세트 → 전체 DEFER(PR22).

## DEFER / OUT 명세 (변경 금지, 근거)

**DEFER — 범주(PR22, `--chart-*` 동결):**
- FavoriteButton `text-yellow-400`(즐겨찾기 골드 별 — 장식, warning 아님)
- EpicProgressBar `bg-emerald-500`/`bg-blue-400`(진행바 fill)
- TimelineRow 이슈타입 점 `bg-purple-500`/`bg-blue-400`/`bg-teal-400`/`bg-red-400` + `COLOR_FALLBACK bg-gray-400` + `text-amber-500`(마일스톤 ◆ 마커)
- WeekGrid worklog 칩 `bg-violet-800`(L249/L266)

**DEFER — 캘린더 coordinated:** WeekGrid `STATE_CATEGORY_STYLE` slate-600/blue-800/emerald-800(L86~88) — 위 §엣지6 사유.

**OUT — 오버레이 스크림 `bg-black/40`(11건):** AutomationRuleFormDialog·AutomationYamlImportDialog·GitWebhookRegisterDialog·GitWebhookUrlModal·RuleConflictWarningModal·WebhookTokenModal·ShareDashboardModal·BulkOperationResultDialog·BulkTransitionDialog·PatTokenModal·dashboards.$dashboardId. `black`은 시맨틱 토큰 대응이 없고, 뉴트럴 솔리드 토큰으로 바꾸면 반투명 딤이 표면색이 됨. `--foreground`는 다크에서 밝아 스크림 반전(오답). **대응 토큰이 없어 이관 불가 → 유지.** 향후 `--overlay`/`--scrim` 토큰 도입은 별도 PR(DESIGN.md 추가).

**OUT — mermaid light-only bg `#ffffff`(WorkflowDiagram L134):** themeVariables 라이트 전용 값, Tailwind 클래스 아님. (단 L82 `oklch category_done`는 L81이 이미 `var(--primary)`를 쓰므로 대칭으로 `var(--success)` 이관 = IN-SCOPE.)

## 비기능 요구사항 (NFR)

- **시각 no-op.** 이관 96건은 라이트·다크 모두 렌더 결과가 실질 동일(토큰 값 = 원 팔레트 근사).
- **WCAG AA 보존.** 상태배경↔표면 2축 대비(contrast-matrix-state-vs-surface-blindfold) — bold/tint 전환이 표면 위 대비를 깨지 않음을 codereview에서 adversarial 검증.
- **순 LOC 감소.** `dark:` 색 페어 삭제로 클래스 문자열 축소.
- **소비 회귀 0.** BacklogBoard 배너(PR3가 이미 이관)·프리미티브 focus/hover 배선 무영향.
- **테스트 lockstep.** 색 클래스 어서션(구현+부정)이 구현 변경과 동일 커밋.

## 측정 가능한 완료 기준

1. IN-SCOPE 31파일에서 상태 팔레트 리터럴 잔여 0 (grep 검증). DEFER/OUT 대상은 의도적 잔존(주석/스펙 근거).
2. `pnpm --filter web typecheck` + `lint` + `test` 전원 통과.
3. badge.test.tsx·WebhookDeliveryTable.test.tsx 어서션이 토큰 문자열로 갱신(부정 어서션 포함, vacuous 0).
4. E2E 회귀 0 (색 클래스에 결합된 셀렉터 없음 — 사전 grep 확인).
5. 게이트2 adversarial 대비검증에서 상태-표면 충돌 0.

## Brainstorming Check

(← Phase B)
