<!-- FR-CA-01 개인 캘린더 월/주 뷰 디자인 스펙 — designer 에이전트 산출물, frontend-engineer 구현 입력 -->

# FR-CA-01 개인 캘린더 — 월/주 뷰 디자인 스펙

> slug: fr-ca-01-calendar · Plan Task 5(D6) · TDD 예외(코드/테스트 없음)
> 관련 문서. [스펙](../specs/2026-07-08-fr-ca-01-calendar.md) · [플랜](../plans/2026-07-08-fr-ca-01-calendar.md) · [ADR](../decisions/2026-07-08-fr-ca-01-calendar.md) · [DESIGN.md](../../DESIGN.md)
> 소비 Task. Task 7(`apps/web/src/routes/calendar.tsx`, `features/calendar/{CalendarView,MonthGrid,WeekGrid}.tsx`) — Task 6(`api/calendar.ts`, `useCalendar.ts`)의 응답 계약을 그대로 사용.

## 핵심 결정 요약 (3줄)

1. **상태 카테고리(TODO/IN_PROGRESS/DONE) 색은 옅은 배지가 아닌 중간톤 solid fill + 흰 텍스트**로 확정 — 옅은 배지(bg-\*-100 + border-\*-300)는 흰 페이지 배경 대비 경계 대비가 1.2~1.7:1로 WCAG 1.4.11(비텍스트 대비 3:1)을 만족하지 못해(계산 근거 §4.1) 배제.
2. **이슈 기간 막대는 셀별 세그먼트 렌더 모델**(TimelineRow류 절대좌표 오버레이 아님) — 각 날짜 셀이 독립적으로 "이 날짜가 span에 포함되는가"만 판정해 좌/우 라운드를 조건부 적용하므로 픽셀 좌표 계산 없이 네이티브 Date 비교만으로 구현 가능(신규 의존성 0 유지).
3. **월 뷰 API 조회창 = 화면에 실제로 렌더되는 42일 그리드 전체**(달력 월의 1~말일이 아님) — 그렇지 않으면 이전/다음 달 패딩 셀에 표시된 실제 날짜에 이벤트가 있어도 영구히 빈 칸으로 보이는 결함이 생긴다.

---

## 1. 목표

로그인 사용자가 자신의 담당 이슈 일정(시작~마감 기간, 마감일)과 Worklog 기록을 월/주 캘린더 한 화면에서 조회한다. 별도 저장 없이 읽기 전용 조회(`GET /users/me/calendar`)이며, 이슈 이벤트 클릭 시 이슈 상세로 이동한다. BTS 원칙(§DESIGN.md 1) 중 "명료성"·"절제"가 가장 중요 — 정보 밀도가 높은 그리드이므로 장식을 배제하고 상태 신호만 또렷하게 남긴다.

## 2. 레퍼런스 — 기존 컴포넌트 전수 조사 결과

| 참고 대상 | 재사용 포인트 |
|---|---|
| `apps/web/src/components/timeline/TimelineRow.tsx` | 이슈 막대 클릭 인터랙션(`role="button"` + `tabIndex=0` + `onKeyDown` Enter/Space) 패턴 재사용. 단, TimelineRow는 픽셀 절대좌표 오버레이 방식이라 **좌표 모델은 재사용하지 않음**(아래 §4.2 결정 2). |
| `apps/web/src/routes/projects.$projectKey.board.tsx` | 로컬 `Skeleton` 컴포넌트(`animate-pulse rounded-md bg-muted`, shadcn Skeleton 미설치라 인라인 구현) — 로딩 상태에 동일 패턴 재사용. 페이지 컨테이너 `p-6`(제약 없는 전체 폭) 패턴도 재사용. |
| `apps/web/src/routes/inbox.tsx` | 로딩/에러/빈 상태 3분기 렌더 구조, 에러 배너(`role="alert"`, `border-destructive/30 bg-destructive/10 text-destructive`) 재사용. |
| `apps/web/src/lib/duration.ts` (`formatSeconds`) | Worklog 소요시간 포맷 — 기존 Velocity/Burndown/WorklogSection이 전부 이 함수로 "Xh Ym" 표기. **신규 포맷 발명 금지, 그대로 재사용**(§4.4). |
| `apps/web/src/components/board/BoardColumn.tsx` | `StateCategory` 타입(`'TODO' | 'IN_PROGRESS' | 'DONE'`, `workflow.types.ts`)이 이미 있으나, **카테고리별 색 매핑은 어디에도 없음**(BoardColumn은 카테고리를 무채색 텍스트 배지로만 표기) — 본 스펙이 최초 색 매핑 도입.
| `apps/web/src/components/issue/IssueScheduleFields.tsx` | "시작일"/"마감일" 한국어 용어 그대로 재사용(`issueDetailStrings`). |
| `apps/web/src/components/Header.tsx` | 메인 nav 링크 패턴(`<Link className="text-muted-foreground hover:text-foreground [&.active]:text-foreground [&.active]:font-semibold">`) — Task 7의 진입점(C3) 추가 시 이 패턴 그대로 사용 권장(아이콘 없이 텍스트만, 기존 "대시보드" 링크와 동일 결). |
| `apps/web/src/routes/search.tsx`, `auth/useAuditLogsQuery.ts` | `placeholderData`(TanStack Query "이전 데이터 유지") 기존 사용례 — 캘린더 이전/다음 네비게이션 시 전체 스켈레톤 재노출 대신 이 패턴 재사용 권장(§7). |
| `apps/web/src/components/ui/button.tsx` | 현재 실제 variant/size(shadcn 초기 문서 §9와 다소 드리프트 — `default` 높이는 `h-8`, `lg`가 `h-9`). 본 스펙은 **실제 코드 기준**으로 표기(§6). |

**결론 — 상태 카테고리 색 토큰은 기존에 없음(신규)**. 나머지(버튼/카드/스켈레톤/에러배너/포맷 함수)는 전부 재사용.

---

## 3. 레이아웃

### 3.1 페이지 컨테이너

```
<div className="flex flex-col gap-4 p-4 sm:p-6">
  <PageHeader />      {/* h1 "캘린더" + 툴바 */}
  <CalendarBody />    {/* MonthGrid | WeekGrid, 로딩/에러/빈 상태 분기 */}
</div>
```

- 좌우 폭 제약 없음(Inbox의 `max-w-3xl`과 달리 그리드는 가로 공간이 필요 — Board 페이지의 `p-6` 무제약 컨테이너 패턴을 따른다).
- `<h1 className="text-2xl font-semibold">캘린더</h1>` — 페이지 제목 타이포는 DESIGN.md §3 그대로.

### 3.2 툴바

```
<div className="flex flex-wrap items-center gap-4">
  <div className="flex items-center gap-2" role="group" aria-label="기간 이동">
    <Button variant="outline" size="icon" aria-label="이전">
      <ChevronLeft />
    </Button>
    <Button variant="outline" size="sm">오늘</Button>
    <Button variant="outline" size="icon" aria-label="다음">
      <ChevronRight />
    </Button>
  </div>

  <span className="text-lg font-semibold" aria-live="polite">
    {monthLabel 또는 weekLabel}
    {isFetching && <Loader2 className="ml-1.5 inline size-3.5 animate-spin text-muted-foreground" aria-hidden />}
  </span>

  <div className="ml-auto flex items-center gap-1" role="group" aria-label="보기 전환">
    <Button
      variant={view === 'month' ? 'secondary' : 'ghost'}
      size="sm"
      aria-pressed={view === 'month'}
    >
      월
    </Button>
    <Button
      variant={view === 'week' ? 'secondary' : 'ghost'}
      size="sm"
      aria-pressed={view === 'week'}
    >
      주
    </Button>
  </div>
</div>
```

- **월/주 토글은 shadcn ToggleGroup/Tabs 미설치**이므로 `<Button aria-pressed>` 2개 조합으로 구현한다(신규 npm 의존성 0 유지, Inbox의 `role="tablist"` 탭 패턴 대신 더 가벼운 `aria-pressed` 버튼 그룹 — 캘린더 뷰 전환은 "탭"이라기보다 "토글"에 가까워 `aria-pressed`가 의미상 더 정확하다).
- 라벨 형식(순수함수로 추출 권장 — Task 7 REFACTOR 노트와 정합).
  - 월 뷰. `formatMonthLabel(date)` → `"2026년 7월"`
  - 주 뷰. `formatWeekLabel(weekStart, weekEnd)` → 같은 달이면 `"2026년 7월 6일 – 12일"`, 달이 걸치면 `"2026년 6월 29일 – 7월 5일"`
- 터치 타깃(DESIGN.md §4 재사용). 이전/다음/오늘/월/주 버튼은 데스크탑 `size="icon"`(32px)/`size="sm"`(28px)이 기본이나, **모바일(`<640px`)에서는 `h-11`(44px)로 보강**한다 — 클래스 예시. `className="h-11 sm:h-8"`(아이콘 버튼) / `className="h-11 sm:h-7"`(텍스트 버튼, `sm` 사이즈 기준 `h-7`).

### 3.3 월 뷰 그리드 (6주 × 7일)

```
<div role="grid" aria-label="{monthLabel} 캘린더" className="overflow-hidden rounded-lg border border-border">
  <div role="row" className="grid grid-cols-7 border-b border-border bg-muted">
    {WEEKDAYS.map(w => (
      <div role="columnheader" className="p-2 text-center text-xs font-medium text-muted-foreground">{w}</div>
    ))}
  </div>
  {weeks.map(week => (
    <div role="row" className="grid grid-cols-7 border-b border-border last:border-b-0">
      {week.map(day => <DayCell ... />)}
    </div>
  ))}
</div>
```

- **주 시작 요일 = 월요일**(월화수목금토일). 사내 협업 도구는 업무 주 단위가 자연스럽고, Linear/Jira 등 협업 툴 관례와 정합(디자인 결정, Maxi 재검토 시 변경 가능 — 백엔드 API는 요일 시작에 의존하지 않으므로 프론트 상수 1곳만 바꾸면 됨).
- 6주 그리드 = 42셀 고정(윤년 2월 등 최대 6주 케이스를 항상 커버해 월마다 그리드 높이가 들쭉날쭉하지 않게 한다).
- **`DayCell` 구조**.
  ```
  <div role="gridcell" aria-label="{ariaDateLabel}" className="min-h-28 md:min-h-24 border-r border-border p-1 last:border-r-0">
    <span className={isToday ? 'flex size-6 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground' : 'text-sm text-foreground'}>
      {dayNumber}
    </span>
    <div className="mt-1 flex flex-col gap-1">
      {/* 기간 막대 세그먼트 → 마감일 칩 → Worklog 칩 순 (§4) */}
      {visibleEvents}
      {overflowCount > 0 && <OverflowButton count={overflowCount} />}
    </div>
  </div>
  ```
- **이달 외 패딩 셀**(이전/다음 달 날짜): `dayNumber`를 `text-muted-foreground`로, 셀 전체에 `opacity-70`을 적용해 시각적으로 우선순위를 낮추되 **이벤트는 그대로 렌더**(§핵심결정 3 — 그 날짜에 실제 이벤트가 있으면 흐리게라도 보여야 한다). 클릭/키보드 동작은 이달 셀과 동일(비활성화하지 않음).

### 3.4 주 뷰 그리드 (7일 단일 행)

```
<div role="grid" aria-label="{weekLabel} 캘린더" className="grid grid-cols-7 gap-2">
  {days.map(day => (
    <div role="gridcell" aria-label="{ariaDateLabel}" className="flex flex-col rounded-lg border border-border p-2">
      <div className="flex items-baseline gap-1">
        <span className="text-xs text-muted-foreground">{weekdayShort}</span>
        <span className={isToday ? 'flex size-6 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground' : 'text-sm font-medium'}>
          {dayNumber}
        </span>
      </div>
      <div className="mt-2 flex flex-col gap-1 overflow-y-auto max-h-96">
        {allEventsForDay /* 오버플로 "+N개" 없음 — 컬럼 내부 스크롤(§4.5) */}
      </div>
    </div>
  ))}
</div>
```

- 세로 시간축 없음(요구사항대로 — 이벤트에 시:분 단위 정보가 없어 시간축이 무의미하다. Worklog `date`/이슈 `startDate`/`dueDate` 모두 날짜 단위).
- 컬럼 높이는 월 뷰보다 넉넉(`max-h-96`=384px)하므로 오버플로 버튼 대신 **컬럼 내부 스크롤**로 처리(§4.5에서 월 뷰와의 차이 이유 설명).

### 3.5 API 조회창(from/to) 계산 규칙

| 뷰 | `from` | `to` | 비고 |
|---|---|---|---|
| 월 | 그리드 1번째 셀의 실제 날짜(전달 패딩 포함) | 그리드 42번째 셀의 실제 날짜(다음달 패딩 포함) | 최대 42일 ≪ 90일 상한(spec §제약) |
| 주 | 그 주 월요일 | 그 주 일요일 | 7일 |

- `timezone` 필드는 응답 에코일 뿐 **프론트가 재변환하지 않는다**(spec 명시 — 이중 tz 변환 시 날짜 밀림 버그, memory 상당). `startDate`/`dueDate`/`date`를 그대로 신뢰해 그리드 셀에 매칭한다.
- 네비게이션/토글 시 쿼리 키는 `['calendar', from, to]`(Task 6 관례상 filter-aware queryKey) — `placeholderData`(이전 데이터 유지, §2 레퍼런스)로 전환 중 스켈레톤 재노출을 피한다.

---

## 4. 이벤트 렌더링

### 4.1 상태 카테고리 색상 매핑 (신규 — DESIGN.md 미등록)

**결정 — 옅은 배지가 아닌 solid fill 채택.** 옅은 배지(예: `bg-blue-100 text-blue-800 border-blue-300`, `WipCountBadge`류 기존 관례)는 흰 배경(`--background: oklch(1 0 0)`) 위에서 border 대비가 다음처럼 계산되어(공식은 DESIGN.md §8 Syntax 절과 동일 `Y=((L×100+16)/116)^3`, `contrast=(Y1+0.05)/(Y2+0.05)`) WCAG 1.4.11(그래픽 객체 3:1)을 만족하지 못한다.

| 후보 | OKLCH L | Y_rel | 흰 배경 대비 | 판정 |
|---|---|---|---|---|
| `blue-300`(배지 테두리) | 0.809 | 0.583 | 1.66:1 | ❌ 3:1 미달 |
| `blue-100`(배지 배경) | 0.932 | 0.834 | 1.19:1 | ❌ 3:1 미달 |

→ **중간톤 solid fill + 흰 텍스트/아이콘**으로 전환. 4개 범주(TODO/IN_PROGRESS/DONE/Worklog) 모두 L 42~45% 대역으로 통일해 범주 간 시각적 무게가 균등하도록 선정했다(Tailwind v4 기본 팔레트, `apps/web/node_modules/tailwindcss/theme.css` 값 인용).

| StateCategory | Tailwind 클래스 | OKLCH L | 흰 텍스트 대비 | 판정 | 아이콘(Lucide, 24×24 기본) | 텍스트 보강 |
|---|---|---|---|---|---|---|
| `TODO` | `bg-slate-600` | 44.6% | 5.45:1 | AA ✅ | `Circle` (size-3) | — |
| `IN_PROGRESS` | `bg-blue-800` | 42.4% | 5.91:1 | AA ✅ | `CircleDot` (size-3) | — |
| `DONE` | `bg-emerald-800` | 43.2% | 5.74:1 | AA ✅ | `CheckCircle2` (size-3) | `line-through`(summary만) |
| Worklog(범주 아님) | `bg-violet-800` | 43.2% | 5.74:1 | AA ✅ | `Clock` (size-3) | — |

- 모든 칩 텍스트/아이콘 색 = `text-white`(다크 solid fill 위 고정 흰색 — 라이트 모드 전용 범위인 본 스펙에서는 다크 모드 페어 미정의, §12 패치안에 다크 모드 여백만 표기).
- **색만으로 구분하지 않는다**(요구사항 5) — 아이콘 3종(Circle/CircleDot/CheckCircle2) + DONE의 취소선까지 3중 인코딩(색+아이콘+텍스트 스타일).
- Worklog가 TODO와 같은 "무채색 계열"이 아니라 **violet(보라)**을 쓰는 이유. 이슈 워크플로 범주(TODO/IN_PROGRESS/DONE)와 Worklog(로그성 데이터, 범주 없음)를 혼동하면 안 되므로 별도 색상군 배정. slate를 재사용하면 "TODO 이슈 막대"와 "Worklog 칩"이 같은 색으로 보여 셀 안에서 혼동 위험이 있음.
- `IssueTypeIcon`(epic/story/task/bug) 등 **이슈 타입 아이콘은 표시하지 않는다**(스코프 결정 — 칩 하나에 상태 아이콘+키+요약까지 이미 정보량이 많다. 타입 정보는 클릭 시 이슈 상세에서 확인 가능. "절제" 원칙).

### 4.2 이슈 기간 막대 — 셀별 세그먼트 렌더 모델

**결정 2 — TimelineRow의 절대좌표 오버레이 방식을 재사용하지 않는다.** TimelineRow는 `computeBarGeometry`로 `dayWidth × 날짜차이` 픽셀을 계산하는 절대 포지셔닝 오버레이인데, 이는 "행 전체가 하나의 연속 트랙"인 Gantt 레이아웃 전제에서만 성립한다. 월 그리드는 각 날짜가 이미 독립된 그리드 셀이므로, 오버레이 대신 **각 셀이 자신을 지나는 이슈 span과의 교차만 판정**하면 된다(순수 Date 비교, 픽셀 계산 0).

- 셀 c(날짜 `d`)에 대해 이슈 이벤트 `e`(`spanStart = e.startDate ?? e.dueDate`, `spanEnd = e.dueDate ?? e.startDate`)가 렌더 대상인 조건. `spanStart <= d <= spanEnd`.
- 세그먼트 모양(같은 색 막대가 여러 셀에 걸쳐 이어 붙어 보이도록):
  - `d === spanStart` → `rounded-l-sm`(좌측만 라운드)
  - `d === spanEnd` → `rounded-r-sm`(우측만 라운드)
  - 중간 날짜(`spanStart < d < spanEnd`) → 라운드 없음, 셀 좌우 패딩을 상쇄하는 `-mx-1`(셀 `p-1`과 대칭)로 이웃 셀과 시각적으로 끊김 없이 이어지게 한다.
  - **주 경계를 넘는 span**은 그 주 마지막/첫 날짜에서도 동일 판정이 그대로 성립(각 셀이 독립 판정이므로 별도 로직 불필요) — 단, 그 주의 첫 셀이 실제 `spanStart`가 아니면 좌측도 라운드 없음(이어짐 표시), 마지막 주의 마지막 셀이 실제 `spanEnd`가 아니면 우측도 라운드 없음.
- 막대 높이 `h-5`(20px), 텍스트 `text-xs font-medium truncate`, 내용 = `{key} {summary}`(요약이 너무 길면 셀 폭에서 `truncate` + `title` 속성으로 전체 텍스트 제공 — Tooltip 컴포넌트 미설치라 네이티브 `title` 사용, 의존성 0).
- 시작만 있거나(`openEnd`, 우측 무한) 마감만 있는(`openStart`, 좌측 무한) 이슈는 span이 1일로 축약된 것으로 취급(TimelineRow의 `MIN_BAR_DAYS=1` 결정과 동형) — 즉 시작일만 있으면 그 하루만 막대(우측 라운드 없음 불필요, 하루짜리라 양쪽 다 라운드), 마감일만 있으면 §4.3의 "마감일 칩"으로 별도 렌더(더 작은 점 형태가 시각적으로 "특정 시점"을 더 잘 전달).
- 인터랙션(TimelineRow 패턴 재사용). `role="button" tabIndex={0}` + `onClick`/`onKeyDown`(Enter/Space) → `navigate({ to: '/issues/$key', params: { key: e.key } })`. `hover:brightness-95 active:brightness-90`, `focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:ring-ring`.
- `aria-label` 예시. `"ATLAS-12, 결제 모듈 리팩터링, 진행중, 시작일 7월 3일, 마감일 7월 10일"`(카테고리는 사람이 읽는 한국어로 — `IN_PROGRESS` 원문 노출 금지, §11 라벨 참고).

### 4.3 마감일 전용 칩

- `dueDate`만 있는 이슈(spec S1의 ATLAS-30 예) 전용. 알약형(`rounded-full`, 콘텐츠 폭만 차지 — 기간 막대의 "셀 전체 폭 사각형"과 형태로 구분).
- 구성. `<CircleDot 크기 아이콘 대신 카테고리 아이콘(§4.1)/> {key}` — lg 이상 폭 여유 시 요약도 truncate로 병기.
- 클릭/키보드/aria 동작은 §4.2 기간 막대와 동일(같은 이슈 상세 이동).

### 4.4 Worklog 칩

- 알약형, `bg-violet-800 text-white`, `<Clock size-3 />` + 텍스트.
- **텍스트 포맷 — 기존 `formatSeconds`(`@/lib/duration`) 그대로 재사용**. `"{formatSeconds(timeSpentSeconds)} {issueKey}"` → 예. 3시간 정각이면 `"3h 0m ATLAS-12"`(작업 지시서의 "3h ATLAS-12" 축약 표기는 예시일 뿐, 실제 렌더는 기존 유틸 포맷을 따른다 — 신규 포맷 발명 금지).
- **`issueSummary === null`(비가시 이슈 마스킹, spec FR-CA-01.4/C4) 처리 = disabled 상태의 실제 사례**. `issueKey`는 유지하되 이동 불가.
  - 활성(요약 존재). `role="button" tabIndex={0}`, hover/focus/active 정상.
  - 비활성(요약 null). 일반 `<span>`(role/tabIndex 없음 — 탭 순서에서 제외), `opacity-70`, `cursor-default`, hover 효과 없음, `aria-label="{issueKey}, 비공개 이슈"`. **클릭해도 이동하지 않는다** — 마스킹된 이슈는 조회자가 볼 권한이 없으므로 이동시키면 403으로 이어지는 막다른 경로가 된다.

### 4.5 셀 오버플로 "+N개"

- **월 뷰**: 셀 높이 제약이 크므로(§3.3 `min-h-28`/`min-h-24`) lg~xl은 최대 3개, md는 최대 2개까지 노출하고 나머지는 `+N개` 버튼(`text-xs text-muted-foreground hover:text-foreground hover:underline`)으로 접는다.
  - 클릭 시 동작 — **그 날짜가 포함된 주의 주 뷰로 전환**(뷰 상태를 `week`로, 포커스 날짜를 해당 셀 날짜로 갱신). Popover류 오버레이 컴포넌트가 미설치인 상태에서 새 의존성 없이 "모두 보기"를 구현하는 가장 단순한 방법이며, 이미 존재하는 월↔주 토글 상태만 재사용한다(신규 컴포넌트 0).
  - `aria-label`. `"{date} 이벤트 {N}개 더 보기"`.
- **주 뷰**: §3.4처럼 셀당 세로 공간이 넉넉(384px)하므로 "+N개" 대신 **컬럼 내부 스크롤**(`overflow-y-auto`)로 처리 — 뷰별로 오버플로 처리 방식이 다른 이유는 가용 공간 차이 때문이며, 두 방식 모두 "이벤트를 누락 없이 접근 가능하게 유지"라는 동일 목표를 만족한다.

---

## 5. 색상·타이포·간격·라운드 — 재사용 vs 신규 총정리

| 항목 | 토큰/클래스 | 재사용/신규 |
|---|---|---|
| 페이지 배경/텍스트 | `bg-background`, `text-foreground` | 재사용(DESIGN.md §2) |
| 그리드 테두리/헤더 배경 | `border-border`, `bg-muted` | 재사용 |
| 오늘 표시 | `bg-primary text-primary-foreground` | 재사용(DESIGN.md §8 대비표 "primary-foreground on primary ~17:1" 그대로 인용) |
| 에러 배너 | `border-destructive/30 bg-destructive/10 text-destructive` | 재사용(Inbox 패턴) |
| 상태 카테고리 3색 + Worklog색 | `bg-slate-600` / `bg-blue-800` / `bg-emerald-800` / `bg-violet-800` (+`text-white`) | **신규**(§4.1, §12 패치안) |
| 페이지 제목 | `text-2xl font-semibold` | 재사용(DESIGN.md §3) |
| 툴바 월/주 라벨 | `text-lg font-semibold` | 재사용("카드 제목, 섹션 소제목"과 동급) |
| 요일 헤더 | `text-xs font-medium text-muted-foreground` | 재사용 |
| 날짜 숫자 | `text-sm` | 재사용 |
| 이벤트 칩 텍스트 | `text-xs font-medium` | 재사용(DESIGN.md §3 "본문 최소 14px" 규칙은 **칩류 소형 라벨**에는 기존에도 `text-xs` 예외 적용 — WipCountBadge/board column 배지가 전부 `text-xs`. 칩은 본문이 아니라 태그/라벨류이므로 §3 사용 가이드의 "태그, 뱃지" 용례에 해당) |
| 셀 패딩 | `p-1`(4px) | 재사용(DESIGN.md §4 열거값) |
| 칩 간 간격 | `gap-1`(4px) | 재사용 |
| 툴바 내부 그룹 간격 | `gap-2`(8px)/`gap-4`(16px) | 재사용 |
| 페이지 컨테이너 패딩 | `p-4 sm:p-6` | 재사용(Board 페이지 `p-6` 변형) |
| 칩 라운드(막대 끝) | `rounded-l-sm`/`rounded-r-sm`(6px) | 재사용(DESIGN.md §5) |
| 마감일/Worklog 칩(알약형) | `rounded-full` | 재사용(다른 알약형 배지들과 동일 관례) |
| 그리드 컨테이너 라운드 | `rounded-lg`(10px) | 재사용(카드류 기본) |
| 셀 최소 높이 | `min-h-28`/`min-h-24`/`min-h-16` | 재사용 정신(InboxSkeleton `h-20`, Board 스켈레톤 `h-64` 선례와 동일하게 Tailwind 기본 h-* 스케일 사용 — DESIGN.md §4 표는 padding/margin 예시일 뿐 h-* 전체를 제한하지 않음) |

---

## 6. 컴포넌트 계층 (shadcn/Radix 매핑)

```
CalendarRouteAdapter (router.ts 어댑터, props 없음)
└─ CalendarView (features/calendar/CalendarView.tsx) — view 상태(month|week) + focusDate 상태 보유
   ├─ CalendarToolbar
   │  ├─ Button (shadcn, variant=outline, size=icon) × 2  ← 이전/다음
   │  ├─ Button (shadcn, variant=outline, size=sm)          ← 오늘
   │  ├─ span[aria-live=polite] + Loader2(lucide, isFetching 조건)
   │  └─ Button (shadcn, variant=secondary|ghost, size=sm) × 2 (aria-pressed) ← 월/주 토글
   ├─ (로딩) Skeleton × N (Board 페이지 인라인 Skeleton 패턴 재사용 — shadcn Skeleton 미설치)
   ├─ (에러) 에러 배너 div[role=alert] + Button(variant=outline) "다시 시도"
   ├─ (빈 상태) p[role=status] "이 기간에 일정이 없습니다" — 그리드 위에 병기, 그리드 자체는 계속 렌더(§7 근거)
   ├─ MonthGrid (view === 'month')
   │  └─ DayCell × 42
   │     ├─ EventBarSegment × N (기간 막대 조각, §4.2)
   │     ├─ DueDateChip × N (§4.3)
   │     ├─ WorklogChip × N (§4.4)
   │     └─ OverflowButton (§4.5, 조건부)
   └─ WeekGrid (view === 'week')
      └─ WeekDayColumn × 7 (내부 overflow-y-auto)
         └─ (동일 이벤트 컴포넌트 3종, §4.2~4.4)
```

- **신규 shadcn 컴포넌트 설치 0.** 기존 `<Button>` 하나만 재사용. Popover/ToggleGroup/Skeleton/Tooltip 모두 "미설치 상태 유지"가 스펙 요구사항(신규 npm 의존성 0)과 일치하도록 위 §3~§4에서 각각 대체 구현 방식을 명시했다(월/주 토글 = `aria-pressed` 버튼, "+N개" = 뷰 전환, 로딩 = 인라인 Skeleton div, 툴팁 = 네이티브 `title`).
- `EventBarSegment`/`DueDateChip`/`WorklogChip`은 shadcn 컴포넌트가 아닌 **순수 커스텀 프레젠테이션 컴포넌트**(TimelineRow와 동급 — 이 앱에서 이미 확립된 "커스텀 div + Tailwind" 관례).

---

## 7. 상태 매트릭스 (7종 — default/hover/active/disabled/loading/error/empty)

| 컴포넌트 | default | hover | active(눌림) | disabled | loading | error | empty |
|---|---|---|---|---|---|---|---|
| 이전/다음 버튼 | `variant=outline` | `hover:bg-muted`(Button 내장) | `active:translate-y-px`(Button 내장) | `isFetching` 중 `disabled`(중복 네비게이션 방지) → `disabled:opacity-50`(Button 내장) | 버튼 자체 로딩 표현 없음 — §3.2 라벨 옆 `Loader2`로 페이지 레벨 표시(placeholderData로 그리드는 유지) | 해당 없음(버튼 자체 실패 없음) | 해당 없음 |
| 오늘 버튼 | `variant=outline size=sm` | 상동 | 상동 | 상동(`isFetching`) | 상동 | 해당 없음 | 해당 없음 |
| 월/주 토글 버튼 | 활성=`secondary`, 비활성=`ghost` | `hover:bg-muted`(ghost) | `active:translate-y-px` | 없음(항상 전환 가능 — 명시적으로 "해당 없음") | placeholderData로 그리드 유지, 라벨 옆 스피너만 | 해당 없음 | 해당 없음 |
| 이슈 기간 막대/마감일 칩 | 카테고리 solid fill(§4.1) | `hover:brightness-95` | `hover:brightness-90`(클릭/Enter 순간) | 없음(항상 클릭 가능 — 이슈는 항상 상세 이동 대상) | 개별 로딩 없음(페이지 스켈레톤이 셀 자체를 대체, §로딩행) | 개별 에러 없음(페이지 에러 배너가 그리드 전체를 대체) | 개별 없음(빈 셀은 그냥 렌더 안 함) |
| Worklog 칩(활성 — 요약 존재) | `bg-violet-800 text-white` | `hover:brightness-95` | `hover:brightness-90` | — | 상동 | 상동 | 상동 |
| Worklog 칩(비활성 — 요약 null, §4.4) | `opacity-70`, `role`/`tabIndex` 없음 | 없음(비대화형) | 없음 | **이 자체가 disabled 표현**(마스킹된 이슈라 이동 불가) | 상동 | 상동 | 상동 |
| "+N개" 버튼 | `text-xs text-muted-foreground` | `hover:text-foreground hover:underline` | `active:opacity-70` | 없음(overflow>0일 때만 렌더되므로 disabled 상태가 곧 "미렌더") | 상동 | 상동 | 상동 |
| 그리드 영역(MonthGrid/WeekGrid) | 정상 렌더 | 셀 hover 시 `hover:bg-accent/40`(포커스 어포던스) | 해당 없음(셀 자체는 비대화형 컨테이너, 내부 이벤트만 대화형) | 해당 없음 | **로딩 스켈레톤**(아래) | **에러 배너**(아래) | **빈 상태 안내**(아래) |
| 로딩(그리드 초기 로드) | — | — | — | — | Board 페이지 관례의 인라인 `Skeleton`(`animate-pulse bg-muted rounded-md`) 6×7 셀 자리에 반복 배치, 실제 그리드와 동일 치수(레이아웃 시프트 방지) | — | — |
| 에러(조회 실패) | — | — | — | — | — | `role="alert"` 배너("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요." — DESIGN.md §10 톤 그대로) + `Button variant=outline` "다시 시도"(`refetch()`) | — |
| 빈 상태(이벤트 0건) | — | — | — | — | — | — | `role="status"` `<p className="text-sm text-muted-foreground">이 기간에 일정이 없습니다.</p>` — **그리드 자체는 계속 렌더**(날짜는 이벤트 유무와 무관하게 유용한 정보이므로 Inbox처럼 리스트를 통째로 숨기지 않는다. 이 부분이 Inbox 패턴과의 의도적 차이) |

- 위 표에서 "해당 없음"으로 표기한 칸은 designer 판단으로 그 상태가 실제로 발생하지 않는 경우(예. 항상 클릭 가능한 요소의 disabled)이며, 누락이 아니라 명시적 검토 결과다.

---

## 8. 반응형 (sm/md/lg/xl)

| 브레이크포인트 | 기본 뷰 | 월 그리드 셀 | 이벤트 표시 | 툴바 |
|---|---|---|---|---|
| **sm**(<640px) | **주 뷰 강제 기본값**(최초 진입 시 뷰포트<640px면 `view` 초기값을 `week`로 — 사용자가 명시적으로 월 뷰를 다시 선택하면 존중, 자동으로 되돌리지 않음) | 해당 없음(월 뷰 비기본) | 주 뷰 컬럼 폭이 좁아지므로 칩 텍스트는 아이콘+`key`만(요약 생략), 칩 자체는 `truncate` | `flex-wrap`으로 2줄(이동 그룹/오늘 1줄, 월·주 토글 다음 줄) — 버튼 `h-11`(44px) 터치 타깃 |
| **md**(~768px) | 월/주 사용자 선택 유지 | `min-h-24`(96px) | 칩 최대 2개 노출 + "+N개", 요약 생략(키만) | 1줄, 버튼 기본 크기 |
| **lg**(~1024px) | 상동 | `min-h-28`(112px) | 칩 최대 3개 + "+N개", `key + summary` truncate 병기 | 1줄, 여유 있는 `gap-4` |
| **xl**(≥1280px) | 상동 | `min-h-28`(112px, lg와 동일 — 그리드 셀은 7등분 폭만 넓어지고 높이는 그대로) | lg와 동일하나 셀 폭이 넓어 요약 truncate 잘림이 줄어듦 | 상동 |

- **좁은 화면 처리 방침(요구사항 6)** — "월 그리드를 억지로 구겨 넣지 않는다." 42개 셀에 각각 요일 헤더까지 포함하면 640px 미만에서 셀 폭이 약 80px 미만이 되어 정보 밀도가 붕괴하므로, 아예 주 뷰를 기본값으로 전환한다. 이는 DESIGN.md §11 "데스크탑 우선, 모바일 2순위" 원칙과 정합 — 모바일에서 기능을 잘라내는 게 아니라 더 적합한 뷰(주)로 안내하는 방식.
- 사용자가 sm에서도 명시적으로 "월" 버튼을 누르면 월 그리드를 보여준다(강제 차단 없음) — 이때 칩은 텍스트 없이 카테고리 색 점(`size-1.5 rounded-full`, 아이콘 생략)만 최대 4개 + 나머지는 "+N" 숫자만(한국어 "개" 생략, 공간 극도로 부족한 예외 케이스로 표기).

---

## 9. 접근성 (WCAG 2.1 AA)

### 대비

- §4.1 표에서 이미 계산(모든 카테고리 색 5.4~5.9:1, AA 여유 확보).
- 나머지 텍스트/배경 조합은 DESIGN.md §8 기존 표(`foreground on background ~19:1`, `primary-foreground on primary ~17:1`, `muted-foreground on background ~4.6:1`)를 그대로 인용 — 재계산 불필요.

### 색만으로 구분하지 않음

- 상태 카테고리 3종 + Worklog 모두 **아이콘 차등**(Circle/CircleDot/CheckCircle2/Clock) + DONE은 **취소선** 추가로 색각 이상 사용자도 구분 가능(§4.1).

### 그리드 ARIA

- `MonthGrid`/`WeekGrid` 컨테이너 = `role="grid" aria-label="{월/주 라벨} 캘린더"`.
- 요일 헤더 = `role="row"` > `role="columnheader"`.
- 주 행 = `role="row"`.
- 날짜 셀 = `role="gridcell" aria-label="{연 월 일}, 시작일/마감일 정보 또는 이벤트 요약"` — 예. `"2026년 7월 3일, 이벤트 2건"`.
- **키보드 모델 — 명시적 스코프 결정**. 셀 자체는 별도로 tab 이동하지 않는다(스프레드시트형 2차원 화살표 로빙 tabindex는 도입하지 않음 — 이 앱에 선례 없음, 신규 패턴 도입 비용 대비 실익 낮음). 대신 **셀 안의 실제 대화형 요소(이벤트 칩·"+N개" 버튼)만 자연스러운 Tab 순서**로 이동한다 — WAI-ARIA APG의 "구성 위젯을 포함한 그리드 셀"에서 허용되는 방식(셀 자체가 위젯을 담고 있으면 그 위젯이 직접 포커스를 받는 것이 표준 패턴 중 하나). 즉 Tab 순서 = 툴바(이전→오늘→다음→월→주) → 1주차 각 셀의 이벤트들(왼쪽→오른쪽) → 2주차… 화살표 키 기반 2D 내비게이션은 **후속 확장 항목**(§14)으로 분리.
- 포커스 링. `focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:ring-ring`(DESIGN.md §8 "절대 `outline-none`만으로 제거 금지" 준수).
- 오늘 셀 = `aria-current="date"` 추가(원 배지 시각 표시 + 스크린리더 병행 고지).
- 로딩/에러 상태 전환은 `aria-live="polite"`(§3.2 라벨 옆) 또는 `role="alert"`(에러, 즉시 통지)/`role="status"`(빈 상태, 완만한 통지)로 스크린리더에 전달.

---

## 10. 상호작용 요약

| 트리거 | 동작 |
|---|---|
| 이슈 기간 막대/마감일 칩 클릭 또는 포커스 중 Enter/Space | `navigate({ to: '/issues/$key', params: { key } })` |
| Worklog 칩 클릭(요약 존재) | 동일하게 참조 이슈 상세로 이동(§4.4 — 참조 관계상 자연스러운 확장이나, spec S7은 "이슈 이벤트" 클릭만 명시하므로 **Maxi/코드리뷰에서 재확인 권장**, 최소 구현 시 생략 가능) |
| Worklog 칩 클릭(요약 null) | 무동작(§4.4 disabled) |
| "+N개" 클릭/Enter | 뷰 전환 `month → week`, focusDate = 해당 셀 날짜 |
| 이전/다음 버튼 | 월 뷰: ±1개월(그리드 42일 재계산). 주 뷰: ±7일 |
| 오늘 버튼 | focusDate = 오늘, 현재 view 유지(강제 월/주 전환 없음) |
| 월/주 토글 | `view` 상태 전환, focusDate 유지(같은 날짜가 포함된 반대 뷰로) |
| 에러 배너 "다시 시도" | `refetch()` |

---

## 11. i18n 카피 — `apps/web/src/i18n/calendar-labels.ts` (신규 파일, 미생성 — Task 7에서 아래 키로 생성)

| 그룹 | 키 | 한국어 텍스트 |
|---|---|---|
| page | title | 캘린더 |
| toolbar | prev | 이전 |
| toolbar | next | 다음 |
| toolbar | today | 오늘 |
| toolbar | monthView | 월 |
| toolbar | weekView | 주 |
| toolbar | loadingAriaLabel | 불러오는 중 |
| category | TODO | 할 일 |
| category | IN_PROGRESS | 진행중 |
| category | DONE | 완료 |
| overflow | more(n) | `+${n}개` |
| overflow | moreAriaLabel(date, n) | `"${date} 이벤트 ${n}개 더 보기"` |
| worklog | maskedAriaLabel(key) | `"${key}, 비공개 이슈"` |
| empty | message | 이 기간에 일정이 없습니다. |
| error | message | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.(DESIGN.md §10 기존 문구 재사용) |
| error | retry | 다시 시도 |
| a11y | gridAriaLabel(label) | `"${label} 캘린더"` |
| a11y | cellAriaLabel(date, count) | `"${date}, 이벤트 ${count}건"`(0건이면 "이벤트 없음") |
| a11y | eventAriaLabel(key, summary, categoryKo, start, due) | `"${key}, ${summary}, ${categoryKo}, 시작일 ${start}, 마감일 ${due}"`(있는 필드만 조합) |

- board-labels.ts 관례(§2 레퍼런스) 그대로 — 콜론으로 끝나지 않음, 그룹별 `as const` 객체.
- **`currentStateKey`(API 원문, 예. `"in_progress"`) 자체를 화면에 노출하지 않는다** — 반드시 위 `category` 그룹의 한국어 라벨로 치환(스네이크/영문 키 노출 금지, 클린 카피 원칙).

---

## 12. DESIGN.md 패치안 (신규 토큰 — 별도 반영 필요, 본 작업에서는 미적용)

> **주의**: 이번 Task 5는 산출 파일이 `docs/design/fr-ca-01-calendar.md` 1개로 한정되어 있어 `DESIGN.md`는 이 스펙 파일 안에서 패치 초안만 제시한다. Task 7 구현 착수 전(또는 별도 소규모 PR)에서 아래 내용을 실제 `DESIGN.md` §2(컬러 토큰) 뒤에 반영해야 한다.

### DESIGN.md §2 뒤에 추가할 신규 섹션 — "캘린더 상태 카테고리 색상 (FR-CA-01)"

```markdown
### 캘린더 상태 카테고리 색상 (FR-CA-01 — PR #〈캘린더 PR 번호〉~)

이슈 워크플로 상태 카테고리(TODO/IN_PROGRESS/DONE) + Worklog를 캘린더에서 구분하는 색.
옅은 배지(bg-*-100 + border-*-300)는 흰 배경 대비 3:1 미만이라(WCAG 1.4.11) 배제하고,
중간톤 solid fill + 흰 텍스트를 채택했다(4개 범주 모두 대비 5.4:1 이상).

| 대상 | Tailwind 클래스 | 대비(흰 텍스트) | Lucide 아이콘 |
|---|---|---|---|
| TODO | `bg-slate-600 text-white` | 5.45:1 | `Circle` |
| IN_PROGRESS | `bg-blue-800 text-white` | 5.91:1 | `CircleDot` |
| DONE | `bg-emerald-800 text-white` | 5.74:1 (+ `line-through` summary) | `CheckCircle2` |
| Worklog(범주 아님) | `bg-violet-800 text-white` | 5.74:1 | `Clock` |

- 색상만으로 상태를 구분하지 않는다 — 아이콘/취소선 병행 필수.
- 다크 모드 페어는 본 PR 범위 밖(DESIGN.md §7 정책과 동일 — 다크 모드 전면 비활성 상태이므로 라이트 값만 확정).
  후속 다크 모드 활성화 PR에서 각 색의 `.dark` 대응값(명도 반전, 채도 유지)을 추가해야 한다.
```

- 이 표는 신규 CSS 커스텀 프로퍼티(`--color-*` OKLCH 변수)를 추가하지 않는다 — Tailwind v4 기본 팔레트(`slate/blue/emerald/violet`)가 이미 OKLCH로 정의돼 있어 그대로 클래스명만 사용한다(TimelineRow/WipCountBadge의 기존 관례와 동일한 결). DESIGN.md §2 "임의 색상 추가 금지" 규칙은 "새 CSS 변수 없이 등록·문서화 없이 쓰는 것"을 막는 취지이므로, 위 표가 곧 그 등록 절차를 충족한다.

---

## 13. frontend-engineer 핸드오프 체크리스트

- [x] 정확한 색상 토큰/클래스명 — §4.1, §5, §12(신규 4종 + 기존 재사용 목록 전부 명시)
- [x] 타이포 스케일 — §5 표(`text-2xl`/`text-lg`/`text-sm`/`text-xs` 전부 DESIGN.md §3 기존 스케일)
- [x] 간격 — §5 표(`p-1`/`gap-1`/`gap-2`/`gap-4`/`p-4 sm:p-6`, 전부 DESIGN.md §4 열거값)
- [x] 7종 인터랙션 상태 — §7 매트릭스
- [x] 4종 반응형 브레이크포인트 — §8 표 + sm 강제 주 뷰 방침
- [x] Lucide 아이콘 이름 — `ChevronLeft`/`ChevronRight`/`Circle`/`CircleDot`/`CheckCircle2`/`Clock`/`Loader2`/`CalendarDays`(nav 진입점 아이콘, 선택 — Header 기존 링크는 아이콘 없는 텍스트 스타일이라 아이콘 생략 권장) — `apps/web/node_modules/lucide-react/dist/lucide-react.d.ts`에서 전체 실재 확인 완료.
- [x] i18n 키 위치 — §11(`apps/web/src/i18n/calendar-labels.ts`, 미생성 상태 — Task 7에서 생성)
- [x] 접근성 — §9(대비 계산, aria 구조, 키보드 범위 결정 명시, 포커스 링)
- [x] 기존 DESIGN.md 토큰과의 관계 — §5 표에서 항목별 재사용/신규 전수 표기, 신규분은 §12 패치안 별첨(실제 DESIGN.md 반영은 별도 작업 필요 — 본 Task 파일 제약)
- [x] shadcn 매핑 — §6(`<Button>`만 재사용, 나머지는 커스텀 프레젠테이션 컴포넌트임을 명시, Popover/ToggleGroup/Skeleton/Tooltip 미도입 사유 각각 기재)

---

## 14. Out of scope / 후속

- **2차원 화살표 키 로빙 tabindex 그리드 내비게이션** — 표준 WAI-ARIA APG grid 패턴의 더 풍부한 형태. 현재 Tab 순서 기반으로도 AA를 만족하나, 사용성 개선을 원하면 별도 FR/PR로 분리 권장(§9).
- **Worklog 칩 클릭 시 이슈 상세 이동 여부** — spec S7은 이슈 이벤트만 명시. §10에서 자연스러운 확장으로 제안했으나 최종 채택 여부는 frontend-engineer/코드리뷰 단계에서 Maxi 확인 권장(디자인 관점에서는 두 선택 모두 상태 매트릭스에 영향 없음 — masked 케이스의 disabled 처리만 필수).
- **다크 모드 페어 값** — DESIGN.md §7 정책상 전면 비활성이므로 본 스펙도 라이트 모드만 확정. §12 패치안에 후속 작업 필요성만 기록.
- **iCal Export(FR-CA-02)** — 별도 FR, 본 스펙 범위 아님(personalization.md §5.2).
