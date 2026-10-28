# BTS UI/UX — Jira Cloud 2025 사용성 패리티 로드맵 (FR-UX-07 · UX-08~14)

> **이관 고지 (2026-08-03)**. 정본이 레포 밖(`~/.claude/plans/ui-ux-sorted-kay.md`)에 있어
> 서브에이전트가 도달할 수 없던 것을 레포로 이관했다. 이관하며 stale 을 정정했다 —
> **FR-UX-07 은 F1(#320)로, FR-UX-09 는 B1(#328) · F2(#331) · F3(#333)로 완주**.
> 영속 규범(즉사 계약 · 재사용 자산 · 사고 절차)은 [`jira-parity-contract.md`](jira-parity-contract.md)로
> 분리했다 — 이 문서는 **소진되는 작업 목록(번다운)** 만 추적한다.

## Context

### 왜 이 로드맵인가

FR-UX-06 "Jira Cloud 방식 재설계" 22 PR 체인(2026-07-25 완결 #308)은 **시각 계층**(ADS v2
토큰 70종 · 프리미티브 24종 · `_shell` 셸 구조)에 집중했다. **인터랙션 계층**은 손대지 않아
색과 구조는 지라를 닮았는데 **조작감이 다르다** — 단축키 5개(지라는 25종+) · 인라인 편집 전무 ·
댓글이 4번째 탭 · 보드 카드 3필드 · 백로그가 가로 칸반 · 프로젝트 스위처 부재.

지라 클라우드를 쓰던 사람이 BTS로 옮겨와도 **손이 기억하는 대로 동작**하게 만드는 것이 목표다.

### 확정 결정 (Maxi, 2026-07-28~29)

| # | 결정 |
|---|---|
| 1 | **범위 = 전부** — Tier 1(막힌 기능) + Tier 2(지라 조작감) + Tier 3(마감·일관성) |
| 2 | **백엔드 = B1+B2만** — B3(프로젝트 무관 "내 작업")은 v2 이연 |
| 3 | 27 PR 로드맵을 **FR-UX-07 + FR-UX-08~14 (8개 FR, 132→139)** 로 분할. B1=FR-UX-09 D4, B2=FR-UX-14 D4 승격 |
| 4 | **검색 이름표 분리** — 상단바 입력창 `전역 검색`, 기존 `검색`은 AQL 제출 버튼 전용 |

## 진행 현황 — FR 매핑 (정본)

F번호는 아래 §PR 체인의 것. **이 표가 PR→FR 매핑의 정본**이다 (표를 한 벌만 둬서 drift 차단).

| FR | 이름 | 승계 PR | 상태 |
|---|---|---|---|
| FR-UX-07 | 활성 프로젝트 컨텍스트 | F1 | ✅ **완주** — #320 |
| FR-UX-08 | 프로젝트 전환 · 최근 항목 · 내 작업 | F12 · F17 | ✅ **완주** — PR A·B (D 마커 정본: personalization.md §4.6) |
| FR-UX-09 | 이슈 생성 흐름 (모달 · 진입점) | F2 · F3 · B1 | ✅ **완주** — B1 #328 · F2 #331 · F3 #333 |
| **FR-UX-10** | 컨텍스트 의존 단축키 | F10 · F11 | ✅ **완주** — F10 #336 + F11 #339 (D1~D7 `[x]`). 컨텍스트 키 **13종**(목록 5 + 상세 8) (D 마커 정본: personalization.md §4.8) |
| FR-UX-11 | 인라인 편집 | F8 · F9 | ✅ **완주** — F8 #337 · F9 #338 (D 마커 정본: personalization.md §4.9) |
| **FR-UX-12** | 검색 진입 (커맨드 팔레트 · 전역 검색) | F4 · F13 | ✅ **완주** — F4 **#340**(D1~D5) + F13 **#341**(D6/D7) |
| **FR-UX-13** | 백로그 사용성 | F5 · F15 · F16 | ✅ **완주** — F5 **#342** + F15 **#343** + F16 **#344** (D 마커 정본: personalization.md §4.11) |
| **FR-UX-14** | 이슈 카드 밀도 | F14 · B2 | ✅ **완주** — B2 **#346**(D1·D3·D4·D5) + F14 **#349**(D2·D6·D7) |
| *(FR 아님 — chore)* | 기존 FR 결손 봉합 — F6 도움말 배선(UX-05 결손) · ~~F7 댓글 기본탭(CO 결손)~~ **F7 ✅ #447** · F18~F25 Tier 3 마감(UX-06 결손) | F6 · ~~F7~~ · F18~F25 | F7 닫힘 — 나머지는 개별 실측 후 착수 |

**잔여 실측 명령** (완료 추정 금지 — 착수 전 확인).

```bash
grep -A3 "FR-UX-1[0-4]" docs/plan/product/personalization.md | grep -E "D[1-7]\."   # D 마커 현황
gh pr list --state merged --search "FR-UX-1" --limit 30                              # 머지된 PR
node scripts/build-dashboard.mjs && open docs/progress.html                          # 진척판
```

## PR 체인 (잔여분)

### Tier 2 — 지라 핵심 조작감

| PR | 목적 | 주요 파일 | 의존 |
|---|---|---|---|
| **F4** | Cmd+K 실체 검색 — 이슈키 즉시매칭 + 프로젝트 로컬필터 + `text ~ "…"` AQL 디바운스 | `CommandPalette.tsx` · `components/ui/command.tsx`(소비처 0→1) · `api/search.ts` | (F1 ✅) |
| **F8** ✅ **#337** | 이슈 상세 인라인 편집 — 제목/본문 클릭 진입, Enter 저장, Esc 취소 | `routes/issues.$key.tsx` · `IssueDescription.tsx` | 없음 |
| **F9** ✅ **#338** | 이슈 목록 셀 인라인 편집 (담당자·우선순위·상태) | `IssueTable.tsx` · `issue-columns.ts` · **신규** `cells/{Editable,Assignee,Priority,Status}Cell.tsx`·`use-issue-list-cell-field.ts`·`lib/transition-availability.ts` · `popover.tsx`(소비처 1→2) | F8 |
| **F10** ⭐ ✅ **#336** | 컨텍스트 단축키 아키텍처 + 목록 항법 `j`/`k`/`o`/`t`/`[` | **신규** `context-shortcuts.ts`·`useContextShortcuts.ts` · `ShortcutsHelpDialog.tsx` | 없음 |
| **F11** ⭐ ✅ **#339** | 상세 액션 단축키 `a`/`i`/`m`/`e`/`l`/`s`/`w`/`.` (8종 — `s`=즐겨찾기, FR-UX-05 이연분 복원. `.`=팔레트 여는 두 번째 열쇠) | `issues.$key.tsx` · `IssueActivityTabs.tsx` · `IssueMetaPanel.tsx` + 메타 3종 · `WatchersSection.tsx` · `CommentSection.tsx` · `FavoriteButton.tsx` · `useCommandPalette.ts` · `ShellLayout.tsx` · `ShortcutsHelpDialog.tsx` | F10, F8 |
| **F13** ✅ **#341** | 상단바 전역 검색 입력창 + 자연어 폴백 | `TopBar.tsx` · **신규** `lib/aql-natural.ts` · **신규** `lib/issue-key.ts` (`routes/search.tsx` 는 **무변경** — 4단 해소·`ActiveProjectGate` 를 이미 소유) | (F1 ✅) |
| **F14** ✅ **#349** | 보드/백로그 카드 밀도 (유형 아이콘·라벨 칩·추정) | `BoardCard.tsx` · `BacklogCard.tsx` · `api/boards.ts` · `api/backlog.ts` · **신규** `CardLabelChips.tsx`·`CardEstimateBadge.tsx`·`i18n/card-labels.ts` · `IssueTypeIcon.tsx` 재사용 | **B2** ✅ |
| **F15** | 백로그 세로 스택 + 스프린트 다이얼로그 + 키보드 DnD | `BacklogBoard.tsx` · **신규** `StartSprintDialog.tsx`·`CompleteSprintDialog.tsx` | F5 |
| **F16** | 백로그 필터바 + 에픽 패널 | `FilterBar.tsx` 슬롯 재사용 · `routes/projects.$projectKey.backlog.tsx` | F15 |
| **B2** | `shared-kernel`+`issue-tracking`+`agile-planning` 보드/백로그 카드 필드 — `BoardIssueView`에 `typeKey`·`typeIconName`·`labels`·`originalEstimateSeconds` | 선례 템플릿 = 커밋 `dcbf130e6`. **N+1 회귀 가드 필수** | 없음 |

Tier 1 잔여 — **F5**(백로그 담당자 `?` 봉합 + 에러/로딩 3종) · **F6**(도움말 버튼 배선). 착수 전 완료 여부 실측.

### Tier 3 — 마감·일관성 (전부 독립 병렬)

| PR | 목적 | 리스크 |
|---|---|---|
| **F18** | 프리미티브 흡수 (checkbox·textarea·select·table) + ESLint 락 | **높음** — `button-primitive-usage.test.ts` 동형 전수비교 테스트 복제 필수 |
| **F19** | AlertDialog 래퍼 신설 + 흡수 + import 락 | 낮음 — `role="alertdialog"`로 바꾸지 말 것 |
| **F20** | Skeleton/재시도/EmptyState 전수 | 중 |
| **F21** | PageLayout/PageHeader/Breadcrumb 전수 적용 | **최고** — h1 이름 **글자 단위 verbatim 보존**이 유일한 성공 조건 (계약 §2) |
| **F22** | 404/에러 라우트 + `/` 홈 가드 | 낮음 — `/dashboard`(단수)는 리다이렉트 대상이라 **삭제 금지**, 콘텐츠만 교체 |
| **F23** | Toaster 다크 + 한글 폰트 fallback + font-size/spacing 토큰 | 낮음 — `state-tokens.test.ts` 동반 갱신 |
| **F24** | 모바일 드로어 (오프캔버스 + 백드롭) | 중 — `complementary` 랜드마크 유지, 데스크톱 관리메뉴 기본펼침 **불변** · 🚧 PR #399 진행 중 (완주 아님) |
| **F25** | ProjectNavTabs 정규화 + DESIGN.md 드리프트 | 중 — **Radix Tabs로 바꾸지 말 것** (계약 §2) |

> **F24 수단 기술 정정 (2026-08-24 · PR #399).** 원 표는 수단을 「Sheet」로 적었으나 계약 §4
> 재사용 자산 레지스트리에 `sheet` 항목이 **없다**(`popover.tsx` 만 있다). 즉 로드맵이 존재하지
> 않는 자산을 수단으로 지목한 상태였고, 새 프리미티브를 만들지 않고 `max-md:` 오프캔버스 +
> 백드롭으로 푼 것이 「만들기 전에 소비처를 grep」의 정신에 맞는다. 표의 수단 기술을 결과
> 기술로 바꿨다. **완주 마킹은 하지 않는다** — 드로어 완전 포커스 트랩(`aria-modal` + `inert`)이
> `TODOS.md` 에 별건으로 남아 있다.

### 의존 그래프 (잔여)

```
B2 ✅ ──▶ F14 ✅ ─┐
                  ├──▶ (F15 병합점) ✅
F5 ✅ ──▶ F15 ✅ ──▶ F16 ✅
F10 ✅ ──▶ F11 ✅      F8 ✅ ──▶ F9 ✅
F4 ✅ / F13 ✅ / F6 / F18~F25 (독립)
```

**임계경로 `B2 → F14 → F15 → F16` 은 2026-08-07 로 전량 소진됐다** — 실제 순서는 역방향으로
풀렸다(F5·F15·F16 이 먼저, B2·F14 가 마지막). **잔여는 FR 이 아닌 chore 갈래뿐** — F6 도움말
배선 · ~~F7 댓글 기본탭~~(**✅ #581** — #447 이 J3 로 닫았다고 적었으나 컴포넌트 `defaultValue` 에만 넣어
라우트 초기값 `HISTORY` 에 막혀 있었다. 2026-10-28 REA 관찰로 발견, #581 이 실제로 적용) ·
F18~F25 Tier 3 마감.

## 2차 캠페인 — Jira Cloud 실물(REA) 갭 (2026-10-28~)

> **근거**. [`2026-10-28-jira-cloud-rea-gap.md`](2026-10-28-jira-cloud-rea-gap.md) — Jira Cloud 실물(TM · CM 프로젝트)을 REA 로 관찰한 갭 표.
> **결정**. [`2026-10-28-jira-cloud-rea-parity-decisions.md`](../decisions/2026-10-28-jira-cloud-rea-parity-decisions.md) D1~D5 (Maxi).
> **FR**. 신설 · 범위 변경 없이 기존 FR 에 귀속한다 — 착수 때 `/bts` 1단계가 확정하고, 범위가 바뀌면(R11) 같은 PR 에서 `docs/rules/fr-sync-checklist.md`.
> **BC**. 2026-10-28 확인 — BC 통합 없음(BC 8 + `shared-kernel` + `app`). 「한 PR = 한 BC」.
> **워크플로 규칙 카탈로그 · 사용처 표시는 이 캠페인 밖** — `docs/specs/2026-10-20-workflow-jira-parity-gap.md` §6 의 기각(순 8 · 9)이 그대로다.

| 묶음 | 갭 행 | 기존 FR | PR 단위(BC) | 재사용 자산 | 선행 | 상태 |
|---|---|---|---|---|---|---|
| **R0 활동 탭 기본값** | JA-D-1 | FR-UX-11 | web | — | — | ✅ **#581** |
| **R1 생성 흐름** — `c` 는 모달 · 연속 생성 · 입력 중 닫기 확인 · 최소화(후순위) | JA-C-1 · JA-C-2 · JA-C-3 · JCB-22 | FR-UX-09 | web 1~2 | `CreateIssueDialog` · 전역 `c` effect | 없음 | 🟡 #582 — 남은 것: 최소화(dock) |
| **R1b Subtask 진입점** — 부모 없는 Subtask 거부 · 상세 하위작업 섹션 | JA-C-4 · JA-D-2 | FR-UX-09 · 이슈 계층 FR | issue-tracking 1 + web 1 | 상세 링크 영역 | 없음 | ⬜ |
| **R2 인라인 생성** — 보드 컬럼 · 백로그 섹션 한 줄 입력 | JA-C-5 · JB-B-1 · JB-L-1 | FR-UX-09 · FR-UX-13 | web 1 (+ 컬럼 상태 수단이 백엔드면 issue-tracking 1) | R1 생성 경로 | **D2 결정 완료** — 컬럼 상태 약속 수단을 같은 묶음에서 정한다 | ⬜ |
| **R3 행 조작** — 카드 메뉴 · 백로그 행 메뉴 · 행 상태 드롭다운 · 행 밀도 · 보드에서 스프린트 완료 · 보드 텍스트 검색 | JB-B-2 · JCB-17 · JB-L-2 · JV-12 · JB-B-3 · JB-B-4 | FR-UX-13 · FR-UX-14 · FR-BD-02 · FR-BL-01 | web 2~3 | 카드/행 공용 메뉴 하나 · F9 `StatusCell` · 백로그 완료 다이얼로그 | 없음 | ✅ **#583 · #584 · #585** |
| **R3b 스프린트 헤더** — 상태별 포인트 합 · 예정 스프린트 날짜 | JB-L-3 | FR-BL-01 · FR-BL-02 | agile-planning 1 + web 1 | `ActiveSprintResponse`(활성만 날짜 보유) | 없음 | ⬜ |
| **R4 검색** — `/search` 결과를 표로 · 검색창 포커스 패널 | JC-L-1 · JC-N-1 | FR-UX-12 | web 2 | `IssueTable` · `use-recent-issues` · `use-recent-projects` | AQL 응답 스키마 확인 | ✅ **#586 · #587 · #588 · #589** |
| **R5 알림 팝오버** | JN-1 | FR-UX-03 · FR-NT-01 | web 1 | inbox API · `InboxBell` | 없음 | ✅ **#590** |
| **R6 가젯 재설정** | JD-19 | FR-DB-02 | web 1 | `GadgetConfigForm` | 저장 API 확인 | ✅ **#591** |
| **R9 Automation 카탈로그** — 트리거(상태 전환 · 할당 · 필드 변경 · 수동 · 스프린트/버전) · 액션(전환 · 생성 · 연결 · 관찰자 · 이메일) | JAU-5 · JAU-6 | FR-AT-01 · FR-AT-02 | automation N — 트리거 · 액션 하나씩 | pgmq 이벤트 구독 | 스펙 · 이벤트 계약(위험 경로 가능) | ⬜ |
| **R10 스프린트 완료 판정 = 맨 오른쪽 열** | JCB-5 | FR-BL-01 · FR-BL-02 | agile-planning 1 + web 1 | `lib/backlog-completion.ts` | **D3 결정 완료** | ⬜ |
| **R11 워크플로 초안을 스킴으로** — 워크플로 편집 즉시 반영 · 스킴 초안 → 게시 + 유형별 상태 매핑 마법사 | JCW-1 · 워크플로 spec §6 순 4 | FR-WF-02 · FR-WF-07 (**범위 변경**) | project-workflow N + issue-tracking + web | FR-WF-07 이관 마법사 · #524 판정 카운트 | **D4 결정 완료** · 스펙 · 위험 경로(데이터 이관) | ⬜ |

**순서.** R1 → R3 → R4 → R5 → R6 (web 단독, 병렬 가능) · R1b · R3b · R10 (백엔드 1 BC + web) · R2(R1 뒤) · R9 · R11(스펙 먼저).
R7 · R8(갭 문서 초판)은 지웠다 — 워크플로 spec §6 의 기존 결정과 겹쳤다. R8 의 내용은 D4 로 R11 이 되었다.

## 계약 · 재사용 자산 · 검증 규범

**본문을 여기 두지 않는다** — [`jira-parity-contract.md`](jira-parity-contract.md) 가 정본.

- 깨면 즉사하는 계약 8행 (aria-label 4종 · h1 verbatim · 단축키 동결 …) → 계약 §2
- 새로 만들지 말 것 — 재사용 자산 레지스트리 → 계약 §4
- 착수 전 사전 grep 절차 → 계약 §5
- 브라우저 눈확인 (생략 금지) → 계약 §6

### PR별 red→green (잔여분)

| PR | red가 될 테스트 |
|---|---|
| F4 | `CommandPalette.test.tsx` "비-슬래시 입력 시 AQL 검색 호출" + **"빈 입력은 바로가기 4개 유지"(회귀 가드)** |
| F8/F9 | 인라인 편집 진입/저장/취소 어서션 (신규) |
| F10 | **신규** `context-shortcuts.test.ts` · **기존 `shortcuts.test.ts` `toHaveLength(5)`가 green 유지되는지가 성공 판정식** |
| F14 | `BoardCard.test.tsx` 유형 아이콘/라벨 · `api/boards.test.ts` 스키마 |
| F18 | **신규** `primitive-usage.test.ts` — `button-primitive-usage.test.ts` 동형 |
| B2 | `BoardControllerTest` 필드 · `IssueRepositoryIntegrationTest` SELECT · **N+1 미발생 가드** |

### 전체 검증 (PR마다)

```bash
cd apps/web && pnpm typecheck && pnpm lint && pnpm test
pnpm exec playwright test --config=<config> <spec>    # positional 필터가 삼켜지므로 바이너리 직접호출
bash scripts/verify-master-plan.sh                    # FR PR에서 EXIT 0
```

## 이연 목록 (v2 후속)

| 항목 | 이유 | 후속 |
|---|---|---|
| **B3 — 프로젝트 무관 "내 작업" API** | 보안 fail-open 위험 — visibility 술어가 프로젝트 키로 하드코딩돼 있어 격리 OR 조립 필요 | v2. 템플릿은 `UserCalendarLookupAdapter.kt` `resolveIsolatedAccessByProjectId` |
| **전역 검색 (프로젝트 무관 혼합)** | `AqlSearchRequest` `projectKey @NotBlank` 3층 차단 · `project`/`assignee` AQL 필드 미구현 | v1은 프로젝트 스코프 + 스위처로 대체 |
| **B4 — 단축키 사용자 재배치** | `KeymapAction` enum + CHECK 신규 마이그레이션 필요 | v1은 고정 키 |
| **스프린트 완료 시 미완료 이슈 이관 선택** | `complete(id)`에 이관 파라미터 없음 | 프론트가 완료 전 개별 DELETE 반복 |

## 실행 방식

BTS 표준 `/bts` 체인을 PR마다 돈다. worktree per 작업. FR-UX-10~14 는 각자 D1(도메인)·D2(명세)부터
시작한다 — FR-UX-06 처럼 스펙 1벌로 전체를 덮는 방식은 기각됐다 (D 마커 자기모순).
