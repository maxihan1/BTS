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
| **FR-UX-10** | 컨텍스트 의존 단축키 | F10 · F11 | ⬜ 미착수 |
| **FR-UX-11** | 인라인 편집 | F8 · F9 | ⬜ 미착수 |
| **FR-UX-12** | 검색 진입 (커맨드 팔레트 · 전역 검색) | F4 · F13 | ⬜ 미착수 |
| **FR-UX-13** | 백로그 사용성 | F5 · F15 · F16 | ⬜ 미착수 (F5 는 완료 여부 실측 후 착수) |
| **FR-UX-14** | 이슈 카드 밀도 | F14 · B2 | ⬜ 미착수 |
| *(FR 아님 — chore)* | 기존 FR 결손 봉합 — F6 도움말 배선(UX-05 결손) · F7 댓글 기본탭(CO 결손) · F18~F25 Tier 3 마감(UX-06 결손) | F6 · F7 · F18~F25 | 개별 실측 후 착수 |

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
| **F8** | 이슈 상세 인라인 편집 — 제목/본문 클릭 진입, Enter 저장, Esc 취소 | `routes/issues.$key.tsx` · `IssueDescription.tsx` | 없음 |
| **F9** | 이슈 목록 셀 인라인 편집 (담당자·우선순위·상태) | `IssueTable.tsx` · `issue-columns.ts` · `meta/*` 재사용 · `popover.tsx` | F8 |
| **F10** ⭐ | 컨텍스트 단축키 아키텍처 + 목록 항법 `j`/`k`/`o`/`t`/`[` | **신규** `context-shortcuts.ts`·`useContextShortcuts.ts` · `ShortcutsHelpDialog.tsx` | 없음 |
| **F11** | 상세 액션 단축키 `a`/`i`/`m`/`e`/`l`/`w`/`.` | `issues.$key.tsx` · `IssueMetaPanel.tsx` · `WatchersSection.tsx` · `CommentSection.tsx` | F10, F8 |
| **F13** | 상단바 전역 검색 입력창 + 자연어 폴백 | `TopBar.tsx` · `routes/search.tsx` · **신규** `lib/aql-natural.ts` | (F1 ✅) |
| **F14** | 보드/백로그 카드 밀도 (유형 아이콘·라벨 칩·추정) | `BoardCard.tsx` · `BacklogCard.tsx` · `api/boards.ts` · `IssueTypeIcon.tsx` 재사용 | **B2** |
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
| **F24** | 모바일 드로어 (Sheet) | 중 — `complementary` 랜드마크 유지, 데스크톱 관리메뉴 기본펼침 **불변** |
| **F25** | ProjectNavTabs 정규화 + DESIGN.md 드리프트 | 중 — **Radix Tabs로 바꾸지 말 것** (계약 §2) |

### 의존 그래프 (잔여)

```
B2 ──▶ F14 ─┐
            ├──▶ (F15 병합점)
F5 ────▶ F15 ──▶ F16
F10 ──▶ F11      F8 ──▶ F9
F4 / F13 / F6 / F18~F25 (독립)
```

**임계경로 = `B2 → F14 → F15 → F16`** (백엔드 3모듈 + e2e 대형 스펙 재작성).

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
