# FR-UX-06 PR4 — 하드코딩 색 → 시맨틱 토큰 스펙

> slug: fr-ux-06-pr4-color-tokens · type: ui · agent: frontend-engineer · 2026-07-19
> 상위: docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md (PR4) · ADR docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md (D2)
> 정본 규칙: DESIGN.md §2 §C · index.css

## 배경

PR3(#287)이 ADS v2 bold 상태 토큰(`--warning`/`--success`/`--danger`/`--info` + `-foreground`)을 도입했다.
PR4는 apps/web의 하드코딩 Tailwind 색 리터럴을 이 토큰으로 이관한다. **기대치 = 이관 대상에 대해 시각적 no-op.**

## 전수 열거 (울트라코드 병렬 추출 + 4렌즈 adversarial 검증)

논리 발생 단위(base + `dark:` 페어 = 1건)로 셌다. grep 정규식이 놓친 것을 병렬 추출·adversarial이 잡았다
(`bg-black/40` 스크림, recharts raw hex, `.ts` mermaid — spec-stated-count-becomes-blindfold 재현).

| 버킷 | 건수 | 처리 |
|---|---|---|
| **IN-SCOPE** (상태 시맨틱 + 명확 뉴트럴) | **95 / 31파일** | 이관 |
| DEFER — 범주(categorical) | ~25 (35파일 스캔 10 + 차트 6파일 ~14) | PR22 (`--chart-*` 동결) |
| DEFER — 캘린더 coordinated | 3 | PR22 (구조 결합·no-op 아님) |
| OUT — 오버레이 스크림 | 전 Dialog.Overlay (~44파일, `bg-black/40|50`) | 유지 (대응 토큰 부재) |
| OUT — mermaid light-only | WorkflowDiagram `#ffffff`·L82 stroke · link-graph-mermaid.ts(파서 var 미지원) | 유지 |

IN-SCOPE 토큰 분포. warning 64 · success 16 · danger 10 · info 3 · accent 1 · card 1.
※카운트는 열거의 **완전성 신호**일 뿐, 규칙 적용은 파일별 grep으로 전수 재확인(counts-are-blindfolds).

## 신규 토큰 — status-text 4종 (★ BLOCKER 해소, Maxi 결정 Option A)

**문제.** `--warning/--success/--danger`는 ADS `color.background.<status>.bold`(중간명도)라 tint 위 **글자색**으로 쓰면
라이트에서 AA 미달(text on `bg-{token}/10`: success 4.11 · warning 4.09 · danger 4.48 < 4.5). PR3가 tint-텍스트용
어두운 텍스트 토큰을 안 만든 간극. contrast-matrix-state-vs-surface-blindfold의 텍스트↔tint 변종.

**해소.** index.css에 ADS `color.text.<status>` 토큰 4종 신설(라이트=-800 어두움, 다크=-300 밝음), `@theme inline` 배선.
원본이 `text-{c}-800`을 쓰던 것과 근사(-800 매핑 = 사실상 no-op). 전 후보 tint(흰·muted·bg·card) AA 실측 통과.

| 토큰 | 라이트 | 다크 | ADS 스텝 | tint AA(라이트/다크 최소) |
|---|---|---|---|---|
| `--success-text` | `#216E4E` | `#7EE2B8` | Green800 / Green300 | 5.10 / 8.59 |
| `--warning-text` | `#7F5F01` | `#F5CD47` | Yellow800 / Yellow300 | 4.90 / 8.69 |
| `--danger-text` | `#AE2A19` | `#FF9C8F` | Red800 / Red300 | 5.45 / 6.97 |
| `--info-text` | `#0055CC` | `#85B8FF` | Blue800 / Blue300 | 5.41 / 6.82 |

- `@theme inline`에 `--color-{status}-text` 배선(→ `text-warning-text` 등 유틸 사용 가능).
- DESIGN.md §C 표·페어링 절 갱신, state-tokens.test.ts에 8값 + 배선 어서션 추가(값 drift CI 차단).
- warning-text는 Yellow 램프 사용(warning 다크가 이미 Yellow) — impl에서 Orange800(#8F4700, amber-800 근사)로 교체해 no-op 극대화할지 확인.

## 정본 매핑 규칙셋 (FIXED — 발명 금지, 적용만)

**팔레트 family → 토큰.** amber/yellow/orange→warning · green/emerald/lime→success · red/rose→danger
(예외: `<input>` 폼 검증 에러는 `--destructive` 유지) · blue/sky→info (예외: primary CTA=`--primary`) ·
slate/gray/zinc/neutral/stone→베이스 뉴트럴(`bg-accent`/`bg-card`, `text-muted-foreground`, `border-border`).

**페어링 변환 (DESIGN.md §C).**
- **Bold 배경**(`bg-{c}-500~900` + `text-white`) → `bg-{status} text-{status}-foreground`. hover 진한 스텝 → `hover:bg-{status}/90`.
- **Tint 배경**(`bg-{c}-50~200` [/NN]) → `bg-{status}/10`.
- **배너 border**(`border-{c}-200|300`) → `border-{status}`. off-spec 알파(`border-amber-500/40`)는 `border-warning/40` 알파 보존.
- **tint/밝은 배경 위 색 텍스트**(`text-{c}-600~900`) → **`text-{status}-text`** (★ bold 토큰 아님 — AA 미달).
- **text-only**(`text-{c}-700|800`) → `text-{status}-text`.
- **핵심: 손수 짠 `dark:` 색 페어는 전부 삭제** — 토큰이 다크 자동 처리(순 LOC 감소).

**badge.tsx 프리미티브.** variant 이름(API) 유지, 내부만: green→success·red→danger·yellow→warning·blue→info.
tint 패턴 `bg-{status}/10 text-{status}-text`, dark 페어 삭제.

## 엣지 케이스 해소

1. **destructive vs danger.** 상태 배지·알림 배너·발송상태(FAILED)·성공실패 아이콘 = `--danger`/`--danger-text`. `<input>` 폼 검증 에러 = `--destructive`(기존 유지·추출 밖). RuleExecutionTraceRow가 한 파일서 둘 다(L39/119 danger, L124 destructive) — 정상.
2. **★ tint-on-tint 중첩 붕괴 (contrast-matrix 제3변종).** 배지가 **같은 색 tint 배너 위에** 겹치면 둘 다 `bg-{status}/10`이 되어 칩 구분 소실. 해당 2곳은 **내부 배지를 bold로**: RuleConflictWarningModal L48 `bg-amber-200 text-amber-900`(L47 `bg-amber-50` 배너 안) → `bg-warning text-warning-foreground`. MappingTable L41 `bg-amber-100` 배지(L34 `bg-amber-50/50` row 안) → `bg-warning text-warning-foreground`.
3. **부정 테스트 vacuous.** WebhookDeliveryTable.test.tsx L81/L82 `not.toContain('bg-green-100')` 등은 PENDING(중립) 배지 검증 → 구현이 `bg-success/10`로 바뀌면 **부정 대상 문자열도 갱신**(vacuous 회귀 방지). 양성+부정 어서션 모두 같은 task에서(guard-handler-matrix-blindfold).
4. **테스트 lockstep.** badge.test.tsx(4)·WebhookDeliveryTable.test.tsx(4) 어서션은 구현 색 교체와 동일 커밋. render(API)는 불변.
5. **공유 상수.** AutomationYamlImportDialog `AMBER_WARNING_BOX_CLASS`(L71→L158/212), MappingTable `DEFAULT_*_CLASS`(L34/37/41) — 상수 1곳 수정=다수 반영.
6. **bold 버튼 hover 방향.** SprintColumn L130 `bg-green-600 hover:bg-green-700`(어두워짐) → `bg-success hover:bg-success/90`(라이트서 밝아짐, 반전). 형제 버튼 L120이 이미 `bg-primary hover:bg-primary/90` + 앱 전역 관례라 수용.
7. **WeekGrid coordinated `text-white`(L194).** 3색이 공유하는 단일 text-white → bold 변환 시 카테고리별 foreground 분리 = 구조변경. no-op 아님 → 전체 DEFER.

## DEFER / OUT 명세 (변경 금지)

**DEFER — 범주(PR22 `--chart-*` 동결):**
- FavoriteButton `text-yellow-400`(골드 별) · EpicProgressBar `bg-emerald-500`/`bg-blue-400`(진행바) · TimelineRow 이슈타입 점 4종 + `COLOR_FALLBACK bg-gray-400` + `text-amber-500`(◆ 마커) · WeekGrid violet 칩(L249/266).
- **recharts 차트 6파일 ~14 raw hex**(BurndownChart·CfdChart·CycleTimeBoxPlot·CycleTimeHistogram·VelocityChart·WorklogAggregateChart의 `#6366f1`/`#f59e0b`/`#94a3b8` data-series). **family 무시 DEFER**(#f59e0b amber·slate hex가 warning/neutral로 오이관될 위험 — 각 파일 주석에 명시).

**DEFER — 캘린더 coordinated:** WeekGrid `STATE_CATEGORY_STYLE` slate-600/blue-800/emerald-800(L86~88, §엣지7).

**OUT — 오버레이 스크림:** 전 `Dialog.Overlay` 백드롭 `bg-black/40|50`(**~44파일**, 공유 정본 `ui/dialog.tsx:40`). `black`은 토큰 대응 없고, 뉴트럴 솔리드로 바꾸면 반투명 딤이 표면색이 됨(`--foreground`는 다크서 밝아 반전). **유지.** 미래 `--overlay`/`--scrim` 토큰은 별도 PR — 워크리스트는 파일 나열 말고 `bg-black/(40|50)` 전수 grep을 정본으로.

**OUT — mermaid:** WorkflowDiagram `#ffffff`(L134, light-only themeVar)·**L82 stroke**(토큰화 시 다크서 흰 bg 위 밝은 stroke 대비저하 — light-only 다이어그램이라 OUT) · link-graph-mermaid.ts(flowchart 파서가 `var()` 미지원, 파일 주석 L23 명시 → OUT).

## 비기능 요구사항 (NFR)

- **시각 no-op.** 이관 95건 라이트·다크 렌더 실질 동일(-800 텍스트 토큰 = 원 `-800` 근사).
- **WCAG AA 보존.** tint 위 텍스트 = `text-{status}-text`(라이트≥4.9·다크≥6.8 실측). 게이트2에서 **텍스트↔tint 2축**(흰·muted·bg·card 표면) + 중첩 배지 재검증.
- **순 LOC 감소** (`dark:` 색 페어 삭제).
- **소비 회귀 0.** BacklogBoard 배너(PR3 이관)·프리미티브 배선 무영향.
- **테스트 lockstep** (양성+부정 어서션, vacuous 0).

## 측정 가능한 완료 기준

1. **IN-SCOPE 31파일에서 상태 팔레트 리터럴 잔여 0** — 잔여 grep은 **IN-SCOPE 파일 + numbered 상태 팔레트**로 스코프(전역 `bg-black`/raw hex grep 금지 — 정당 잔존 스크림·차트를 오탐).
2. status-text 4토큰 index.css 신설 + `@theme` 배선 + DESIGN.md §C + state-tokens.test 8값 어서션.
3. `pnpm --filter web typecheck`+`lint`+`test` 통과.
4. badge.test.tsx·WebhookDeliveryTable.test.tsx 어서션 토큰 갱신(부정 포함, vacuous 0).
5. E2E 회귀 0 (색 클래스 결합 셀렉터 없음 — 사전 grep).
6. 게이트2 adversarial: 텍스트↔tint AA + 중첩 배지 붕괴 0.

## Brainstorming Check

✅ 4렌즈 adversarial(울트라코드) 통과 — BLOCKER 1(tint 텍스트 AA, Option A 해소)·CONCERN 2(중첩 배지→bold)·완결성 갭 3(차트·스크림·mermaid .ts 열거 편입)·매핑 오배정 0. 잔여는 gate1 요약에 명시.
