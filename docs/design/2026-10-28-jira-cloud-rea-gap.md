# Jira Cloud ↔ BTS UI/UX 갭 분석 — REA 실물 관찰 (2026-10-28)

> **지위.** 조사 기록. 결정 문서가 아니다. 이 문서의 J행은 [`jira-parity-contract.md`](jira-parity-contract.md) §1-0 의 재사용 대상이다 — 같은 표면을 다시 조사하지 말고 행을 승계한다.
> **관련 FR.** FR-UX-06(시각 계층) · FR-UX-07~14(인터랙션 계층) · FR-WF-01(워크플로) 후속 갭 발굴.
> **조회일.** 2026-10-28 · **Jira.** Cloud(`gwalho.atlassian.net`, 2026-10 빌드) · **BTS.** `main` cf567672a + 실서비스 `bts.maxihan.com`.

## 0. 읽는 법

### 방법

- Jira Cloud 실물을 REA(`inspect_web_page` · `capture_web_screenshot`)로 관찰하고 evidence_id 를 행에 붙였다. REA `capture_browser_scenario` 는 connect 모드에서 응답이 끊겨, 클릭·키 입력은 CDP 를 직접 붙인 스크립트로 보냈다. 이때 증거가 DOM 판독뿐인 행은 「CDP 판독」으로 표기돼 있다.
- BTS 열은 repo 코드(`file:line`)가 기본이다. 시각 비교(§F)와 이슈 생성(§A)은 실서비스 화면도 함께 봤다.
- 화면 묶음은 A~F 여섯 개다. 묶음마다 subagent 하나가 맡았다.

### 근거 지위 — 계약 §1 과의 관계

계약 §1 이 근거로 인정하는 것은 문서 5개 도메인뿐이다. **REA 실물 관찰은 그 표에 없는 근거 유형이다.** 그래서 이 문서의 행은 두 가지로 나뉜다.

- `support.atlassian.com` 인용이 붙은 행 → 계약상 근거로 바로 쓴다.
- 「실물 관찰만」 행 → 스펙에 승계할 때 문서 인용을 보강하거나, 실물 관찰을 근거로 인정하도록 계약을 고칠지 Maxi 가 정한다.

### 관찰 한계 — 해석할 때 감안할 것

- **team-managed(TM) 프로젝트 하나뿐이다.** company-managed 와 다를 수 있는 행은 `[TM]` 표기가 있다. 특히 워크플로 · 보드 설정 · 유형관리가 그렇다.
- **막 만든 빈 워크스페이스다.** 데이터 의존 화면(카드 밀도 · 포인트 합계 · 가젯 결과)은 `[REA]` 샘플 몇 건으로만 봤다.
- **관찰 도중 Jira 를 바꿨다.** 워크플로에 `[REA] 검증 대기` 상태가 생겼다(보드 열 증가 가능). Automation 규칙이 약 3분 켜져 SCRUM-5·7·8 에 자동 댓글이 달렸다. 그 뒤의 관찰은 이 상태를 포함한다.
- 스크린샷 14장은 작업 임시 폴더에만 있었고 repo 에 넣지 않았다. 아래 §F 의 `shots/` 경로는 남아 있지 않다.

## 1. P1 요약 — 매일 쓰는 조작이 다른 것

우선순위 정의. **P1** 매일 쓰는 조작이 다름 · **P2** 눈에 띄지만 우회 가능 · **P3** 미세. 전체 행(P2·P3·의도적 편차 X/JX)은 §A~§F 에 있다.

| 주제 | # | Jira Cloud | BTS 현재 | 비고 |
|---|---|---|---|---|
| 회귀 | JA-D-1 | 이슈를 열면 활동 기본 탭이 **댓글** | `routes/issues.$key.tsx:258-260` 초기값이 `HISTORY`. 라우트가 값을 쥐어 `IssueActivityTabs.tsx:124` 의 기본=댓글이 먹지 않음 | F7 #447 이 고친 동작의 회귀. 코드로 재확인함 |
| 생성 | JA-C-1 | `c` 는 보던 화면 위에 모달, URL 유지 | `shortcuts.ts:88` 이 `/issues/new` 로 이동 → 보드 맥락 상실 | 코드로 재확인함 |
| 생성 | JA-C-2 | 「다른 항목 만들기」 연속 생성. 프로젝트·유형 유지, 건수 카운터 | 없음 | |
| 생성 | JA-C-3 | 입력 중 X → 「변경 사항이 저장되지 않습니다」 확인 | Esc·바깥 클릭·취소에 확인 없이 소실 (`CreateIssueDialog.tsx:88`) | |
| 생성 | JA-C-4 | 전역 생성 목록에 Subtask 없음. 부모 상세에서만 생성 | 전역 생성에서 Subtask 선택 가능, 부모 입력란 없음 → 부모 없는 Subtask 생성됨 | 실서비스 QAWEB-156 이 실제로 생김 |
| 생성 | JA-C-5 · JB-B-1 · JB-L-1 | 보드 컬럼 끝·빈 컬럼, 백로그 섹션 끝에서 한 줄 인라인 생성. 만든 이슈는 그 컬럼 상태로 | 모달 하나뿐 | ADR `2026-08-03-fr-ux-09-f3-create-issue-entry-points` D-3 이 의도적으로 뺐다 — 재결정 사안 |
| 생성 | JA-D-2 | 상세 본문에 하위 작업 표 + 진행률 + 연속 입력 행 | 하위 작업 섹션 없음 | |
| 보드 | JB-B-2 | 카드 작업 메뉴(상태 변경 · 키 복사 · 플래그 등) | 카드 메뉴 없음 | |
| 보드 | JB-B-3 | 보드 툴바에 「스프린트 완료」 + 남은 일수 | 완료는 백로그에서만 | |
| 보드 | JB-B-4 | 「보드 검색」 텍스트 입력 + 담당자 아바타 필터 줄 | 자유 텍스트 검색 없음 | |
| 백로그 | JB-L-2 | 행에서 상태 드롭다운으로 바로 변경 | 행에 상태 표시 자체가 없음 | |
| 백로그 | JB-L-3 | 스프린트 헤더에 날짜 + 상태별 포인트 배지 3개 | 날짜·포인트 없음, `ACTIVE`·`PLANNED` 원문 노출 | |
| 백로그 | JV-12 | 이슈 1건 ≈ 40px 한 줄 | 103~115px 카드 (`BacklogCard.tsx:230`) → 한 화면 이슈 수 약 1/2.6 | |
| 검색 | JC-L-1 | JQL 결과가 목록과 같은 표(컬럼·셀 편집·일괄 툴바) | `/search` 는 카드 목록 (`search.tsx:408`) → 저장 필터·AQL 결과에서 일괄·셀 편집 불가 | |
| 검색 | JC-N-1 | 상단 검색창 클릭만으로 최근 항목·보드·필터·「모든 업무 항목 보기」 패널 | 포커스해도 아무것도 안 뜸, Enter 제출만 (`TopBar.tsx:157-173`) | |
| 워크플로 | JW-7 | 전환 규칙 12종(제한 5 · 세부 확인 4 · 작업 수행 3) `[TM]` | validator 3종 + `CALL_WEBHOOK` 1종 | |
| Automation | JAU-5 | 트리거 24종 이상 | 6종. 상태 전환됨 · 할당됨 · 필드 값 변경 · 수동 실행 · 스프린트/버전 없음 | |
| Automation | JAU-6 | 액션 26종 이상 | 5종. 이슈 전환 · 이슈/하위작업 생성 · 연결 · 관찰자 · 이메일 없음 | |
| 알림 | JN-1 | 벨 클릭 → 그 자리 팝오버(직접/관찰 탭 · 안읽음 토글 · 회신) | `/inbox` 페이지로 이동 | |
| 대시보드 | JD-19 | 가젯 `⋯` 에 강조 색 8종 · 구성 · 복제 · 삭제 | 복제·삭제만 (`DashboardTile.tsx:193-221`) → 만든 가젯을 다시 설정할 수 없음 | |

### 묶어서 보면

1. **생성 흐름**이 가장 크다 — JA-C-1~5 · JB-B-1 · JB-L-1 · JA-D-2. 모달 이동·연속 생성·이탈 확인·인라인 생성이 한 덩어리다.
2. **보드·백로그 행 조작** — 카드 메뉴 · 행 상태 변경 · 스프린트 헤더 · 행 밀도.
3. **검색을 표로** — JC-L-1 은 목록 화면 자산(셀 편집·일괄)을 `/search` 에 재사용하는 문제다.
4. **워크플로·Automation 카탈로그 폭** — 화면 갭이 아니라 백엔드 기능 폭의 갭이다.
5. 회귀 JA-D-1 은 크기가 작고 원인이 확인돼 있다.

## 2. 승계 문서와 실물이 어긋난 곳 — 정정 후보

| 문서 | 기존 기록 | 이번 실물 관찰 |
|---|---|---|
| `docs/specs/2026-08-05-fr-ux-12-f13-global-search-input.md` J6 | 「`/` 단축키가 Jira 에 없다」 | `?` 단축키 목록에 「빠른 검색 /」가 있다 |
| `docs/specs/2026-08-03-fr-ux-10-f10-context-shortcuts.md` | 사이드바 토글 `Ctrl+[` | 단일 키 `[`. BTS 의 선택이 결과적으로 맞다 |
| `docs/plans/2026-09-07-jira-parity-project-header-and-scoped-tabs.md` J5-12 | 「List 탭은 제거 불가」 | 새 TM 스페이스에는 목록 탭이 처음부터 없고 「탐색에 추가」로 넣는다 |

## 3. 변경 기록 — 정리 대상

사용자 승인(2026-10-28) 아래 양쪽에 쓰기를 했다. 이름에 `[REA]` 접두어를 붙였다. 화면별 URL 은 각 절 끝 「변경 기록」에 있다.

| 곳 | 남은 것 |
|---|---|
| Jira | 이슈 SCRUM-5~9 · 워크플로 상태 `[REA] 검증 대기` + 전환 `[REA] 검증 요청`(유효성 규칙 포함) · Automation 규칙 `[REA] 이슈 생성 시 댓글`(꺼 둠) · `[REA] 대시보드 관찰`(dashboards/10001) · 작업 유형 `[REA] 관찰 유형`(issuetypes/10006) · 저장 필터 `[REA] 목록 관찰 필터 (C)`(filter=10001) · 스페이스 탭에 목록·캘린더 추가 · SCRUM-8 우선순위 Medium→Low(원복 안 함) |
| BTS 실서비스 | QAWEB-156 — 부모 없는 Subtask(JA-C-4 재현 결과). 정리 필요 |

---

## A — 이슈 상세 · 이슈 생성 조작감 갭 (2026-10-28)

> 범위 지시 반영. 이슈 상세는 **구조만**(P1/P2 위주), 이슈 생성은 **깊게**(행 수 제한 없음).
> Jira 사이트 UI 언어가 한국어라 관찰 문구는 한국어 라벨 그대로 적는다(예: Create another = 「다른 항목 만들기」).

### 이슈 상세 (JA-D)
관찰 URL · 조회일 2026-10-28 · REA evidence_id 목록
- `https://gwalho.atlassian.net/browse/SCRUM-1` 전체 페이지 — `ev_b7ecefd73b62`
- 같은 페이지 ⋯ 메뉴 펼친 상태 — `ev_fdb785a8e311`
- 같은 페이지 `?` 단축키 도움말 — `ev_d2260891d4c8`
- `https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1?selectedIssue=SCRUM-1` 보드 모달 — `ev_2aecad579d2a`
- `https://gwalho.atlassian.net/browse/SCRUM-5` 하위 작업 인라인 생성 뒤 — `ev_dcce7cf0803d`

#### Jira Cloud 관찰 요약
- **헤더(왼쪽 칼럼 맨 위)**: 브레드크럼 `스페이스 / QA Hub / [Epic 추가] / [유형 아이콘 — 업무 유형 변경] SCRUM-1 [링크 복사]`. 그 아래 제목(클릭하면 인라인 편집), 그 아래 「관련 업무 추가 또는 만들기」 · 「앱 추가」 버튼.
- **왼쪽(본문) 칼럼 순서**: 설명 → 첨부 파일(접기 가능) → **하위 작업**(「하위 작업 추가」) → **연결된 업무 항목**(「연결된 업무 항목 추가」) → 활동.
- **오른쪽 칼럼 맨 위 버튼 묶음**: 제한(자물쇠) · 관찰(👁 + 인원수) · Share · ⋯(작업). 그 아래 **상태 버튼**(「해야 할 일 ▾」) · 자동화 · 「업무 유형 개선」.
- **「세부 사항」 접이식 그룹(기본 펼침)** + 「필드 구성」 톱니. 필드 순서는 담당자(+「나에게 할당」 링크) → 상위 항목 → Sprint → 우선 순위 → 레이블 → 기한 → Team → Start date → Story point estimate → 보고자. 필드마다 「위에 고정(pin)」 토글이 있다. 그 아래 접힌 그룹 「개발」 · 「자동화」, 맨 아래 만듦 / 업데이트 시각과 「구성」 링크.
- **활동**: 탭 `전체 · 댓글 · 기록 · 업무 로그`이고 **기본 선택은 댓글**이다. 정렬 토글은 「최신 항목」. 댓글 입력창은 탭 바로 아래(목록 위)에 있다. 빠른 댓글 칩 3개(「누가 이 작업을 하고 있습니까...?」 · 「더 자세한 정보를 얻을 수 있습니까...?」 · 「상태 업데이트...」)와 「전문가 팁: M 눌러서 댓글 추가」가 붙는다.
- **⋯ 메뉴 항목**: 작업 로그(Q) · 명령 팔레트 열기(⌘K) | 플래그 추가 · 투표 추가 | 커버 선택 | 상위 항목 추가 · 복제 · 이동 · 보관 · 삭제 | 인쇄 · Excel 내보내기 · Word 내보내기 · XML 내보내기 | 둘러보기 · 구성.
- **하위 작업 영역(SCRUM-5 에서 생성 후)**: 「하위 작업」 접기 헤더, 그 옆에 열 구성 · 하위 항목 만들기 · 제안된 업무 항목 만들기. 진행률 「0% 완료」가 붙고, 표 열은 업무 · 우선 순위 · 담당자 · 상태다. 인라인 행은 [유형 선택 = Subtask] + 「이 subtask의 이름 지정」 + 만들기 · 기존 항목 선택 · 취소로 이뤄진다. **Enter 로 만든 뒤에도 입력 행이 열린 채 비워지고 포커스가 남는다.**
- **보드 모달**: 중앙 1160×601(뷰포트 1280×720). 헤더에 「사이드바로 전환」 · 닫기가 있고, 2단 구성은 전체 페이지와 같다.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JA-D-1 | 이슈를 열었을 때 활동 기본 탭 | 「댓글」 탭이 selected=True | 라우트가 탭을 쥐고 **초기값이 「이력」**이다 — `routes/issues.$key.tsx:258-260` (`focusCommentId` 없으면 `ACTIVITY_TABS.HISTORY`). 실서비스 QAWEB-156 에서도 「이력*」으로 열림. `IssueActivityTabs.tsx:89-95` KDoc 은 「기본=댓글(J3)」이라 적었지만 `:124` defaultValue 는 controlled 라 죽은 코드 | **회귀** — 2026-09-04 J3 결정이 라우트 쪽 초기값에 반영 안 됨 | REA `ev_b7ecefd73b62` 2026-10-28 · 승계: docs/plans/2026-09-04-issue-detail-jira-parity.md J3 "By default the activity feed shows comments" | P1 |
| JA-D-2 | 하위 작업 보기·만들기 | 본문 칼럼 고정 섹션 「하위 작업」(표 + 진행률). 「하위 작업 추가」 → 인라인 행에서 Enter, 행이 열린 채 연속 입력. 「기존 항목 선택」도 같은 행. 단축키 Shift+C | 상세에 하위 작업 섹션·인라인 생성 **없음**(grep `하위 작업 추가|createChild` 0건). 부모 지정은 활동 탭 「연결」 안 부모 섹션 `IssueLinksPanel.tsx:600`. 하위 작업을 만들려면 전역 생성에서 유형 Subtask → 상세에서 부모 지정, 2단계 | 진입점 자체가 없음 | REA `ev_dcce7cf0803d` 2026-10-28 · 문서: "select Add a child work item" (team-managed) / "select Create subtask" (company-managed) — https://support.atlassian.com/jira-software-cloud/docs/create-a-work-item-and-a-subtask/ | P1 |
| JA-D-3 | 연결된 이슈 보기·추가 | 본문 칼럼 고정 섹션 「연결된 업무 항목」 + 추가 버튼(설명·하위 작업 다음). 단축키 Shift+K | 활동 탭 4번째 「연결」 안(`IssueActivityTabs.tsx` · `IssueLinksPanel.tsx:564`) — 탭을 눌러야 보임 | 위치가 탭 안으로 숨음 | REA `ev_b7ecefd73b62` 2026-10-28 · 실물 관찰만 | P2 |
| JA-D-4 | ⋯ 더보기 메뉴 | 오른쪽 위 버튼 묶음 제한 · 관찰(인원수) · Share · ⋯. ⋯ 에 작업 로그 · 플래그 · 투표 · 상위 항목 추가 · 복제 · 이동 · 보관 · 삭제 · 인쇄 · 내보내기 3종 | ⋯ 메뉴 없음. 「이슈 이동」 · 「PDF」는 헤더 버튼(`issues.$key.tsx:1037`), 「이슈 클론」 · 「이슈 삭제」는 메타 패널 아래쪽 버튼(`issues.$key.tsx:1124` IssueMetaPanel), 감시는 메타 패널 안 「지켜보는 중」 | 파괴적·드문 조작이 상시 버튼으로 흩어져 있음. 플래그 · 보관 · 공유 없음 | REA `ev_fdb785a8e311` 2026-10-28 · 실물 관찰만 | P2 |
| JA-D-5 | 오른쪽 필드 패널 구성 | 상태 버튼 → 「세부 사항」 접이식 그룹(담당자 맨 위 + 「나에게 할당」 링크, 보고자 맨 아래) → 접힌 「개발」 · 「자동화」 → 만듦/업데이트. 값은 텍스트로 보이고 클릭하면 편집. 필드 pin · 「필드 구성」 | 평평한 카드 나열. 상태 → 유형 → 우선순위 → 영향도 → 환경 → 라벨 → 담당자 → 감시자 → 컴포넌트 → 버전들 → 보안등급 → 보고자 → 프로젝트 → 생성/수정 → 클론/삭제 → 일정 → 시간 추적. 유형 · 우선순위 · 영향도가 **상시 노출된 select 상자**이고 환경 · 라벨 · 일정은 개별 「저장」 버튼(실서비스 QAWEB-156) | 담당자가 7번째로 밀림. 그룹 접기·pin 없음. 편집 위젯이 늘 펼쳐져 있어 밀도가 다름 | REA `ev_b7ecefd73b62` 2026-10-28 · 실물 관찰만 | P2 |
| JA-D-6 | 상세 단축키 목록 | `?` 「이슈 작업」: O 보기 · **Shift+C 하위 이슈 만들기** · **Shift+K 기존 이슈 연결** · A 할당 · I 내게 할당 · W 관찰 · M 댓글 · L 레이블 · **D 상태 변경** · **Q 작업 로그** · Shift+S 공유. 「이슈 탐색」: J/K 다음·이전 · T 목록/상세 토글 · Z 확장 · U 고급 검색 복귀 | `context-shortcuts.ts:122-158` = a · i · m · e(제목) · l · s(즐겨찾기) · w | D · Q · Shift+C · Shift+K · Shift+S 없음. BTS 의 e · s 는 Jira 상세 목록에 없음 | REA `ev_d2260891d4c8` 2026-10-28 · 문서: "Press A to assign the work item to someone" · "Press M to quickly add a comment" — https://support.atlassian.com/jira-software-cloud/docs/use-keyboard-shortcuts/ (D·Q·Shift+C 등은 실물 관찰만) · 승계: docs/specs/2026-08-04-fr-ux-10-f11-detail-action-shortcuts.md | P2 |
| JA-D-7 | 빠른 댓글 칩 | 댓글 입력창 아래 칩 3개 + 「M 눌러서 댓글 추가」 팁 | 없음(`CommentSection.tsx` grep `칩|quick` 0건) | 없음 | REA `ev_b7ecefd73b62` 2026-10-28 · 실물 관찰만 | P3 |
| JA-D-8 | 상세 열기 형태(모달/사이드) | 보드에서 중앙 모달이 기본, 「사이드바로 전환」 버튼 | modal/sidePanel 토글 `issueDetailModalStore.ts` · `__root.tsx:37` · `ShellLayout.tsx:184` | 재조사 안 함 | 승계: docs/plans/2026-09-04-issue-detail-jira-parity.md J1 · REA `ev_2aecad579d2a`(모달 크기·버튼만 재확인) | — |

#### BTS 가 이미 동등하거나 앞선 것
- 제목 인라인 편집(`issues.$key.tsx:785`)과 본문 편집(`IssueDescription.tsx:100`)은 Jira 와 같은 「클릭하면 편집」이다(F8 스펙 승계).
- 상세 모달 ↔ 사이드 패널 토글은 Jira 「사이드바로 전환」과 동등하다(J1).
- PDF 다운로드는 Jira 에 없다(Jira 는 인쇄·Word/Excel/XML 내보내기만).
- 상태 전이 다이얼로그(전이 화면, `issues.$key.tsx:928`)는 Jira team-managed 보다 기능이 많다.

#### 관찰 한계
- 「전체」 · 「기록」 · 「업무 로그」 탭 내용과 댓글 정렬 토글은 구조 지시에 따라 펼쳐 보지 않았다.
- 「개발」 · 「자동화」 그룹, 「필드 구성」, pin 동작은 펼치지 않았다(미관찰).
- `[TM]` team-managed 라 「세부 사항」 필드 구성(Team · Story point · Sprint)이 company-managed 와 다를 수 있다.

---

### 이슈 생성 모달 (JA-C)
관찰 URL · 조회일 2026-10-28 · REA evidence_id 목록
- 보드 `https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1` 에서 `c` → 간략 모달 — `ev_63d0a4bc399f`
- 「더 보기(확장)」 전체 양식 — `ev_d599c21a1986`
- 「다른 항목 만들기」 체크 뒤 제출 → 모달 유지 · 플래그 — `ev_8d08645cbc25`
- 값 입력 뒤 X → 「변경 사항이 저장되지 않습니다」 확인 — `ev_a55136ef765e`
- 보드 컬럼 인라인 생성 Enter 뒤 — `ev_0fcd2cff37ec`
- SCRUM-5 상세 하위 작업 인라인 생성 뒤 — `ev_dcce7cf0803d`

#### Jira Cloud 관찰 요약
- **진입**: 상단바 「+ 만들기」 또는 `c` 를 누르면 **현재 화면 위에 모달**이 뜨고 URL 은 바뀌지 않는다(보드 URL 그대로).
- **기본은 간략 모달**이다(800×273, 첫 사용 때 「더 많은 필드가 필요한 경우 확장 아이콘을 클릭하여 전체 양식을 확인하세요」 안내 말풍선). 헤더 왼쪽에 **칩 2개 `[프로젝트 아이콘] SCRUM | [유형 아이콘] 작업`**, 오른쪽에 화면 모드 아이콘 · 확장(↗) · 닫기가 있다. 본문은 큰 글씨 「요약」 textarea → 테두리 없는 설명(「설명을 추가하거나 /를 입력하여 작업 및 Rovo를 사용합니다」) → **필드 알약(pill) 줄**(담당자 「자동」 · 상위 항목 · 스프린트 · 우선순위 「Medium」 · 레이블) 순이다. 푸터는 왼쪽 피드백 아이콘, 오른쪽 「☐ 다른 항목 만들기」 + 「만들기」. **취소 버튼은 없다.**
- **확장(전체 양식)**: 높이만 601 로 늘어나고 폭은 800 그대로다. 헤더 버튼은 최소화 · 화면 모드(전체 화면) · 간략히 보기 · ⋯ 추가 옵션 · 닫기. 필드 순서는 요약 → 설명 → 상태(해야 할 일) → 담당자(자동) → 상위 항목 → 스프린트 → 우선 순위(Medium) → 레이블 → 기한 → Team → 시작 날짜 → Story point estimate → **보고자\***(별표는 여기 하나뿐) → 첨부 파일(「첨부할 파일 끌어놓기 또는 찾아보기」) → 연결된 업무 항목 → 다음으로 제한 → Flagged(Impediment).
- **⋯ 추가 옵션**: 필드 구성 · 사용하지 않는 필드 숨기기 · 항상 이 보기에서 열기 · 관찰 중지(w) · 관찰자 추가.
- **유형 선택기(헤더 칩)**: 에픽 · 작업 · 스토리. **Subtask 는 전역 생성에 없다** `[TM]`. 프로젝트 선택기는 「최근 스페이스 / 모든 스페이스」 두 묶음이다.
- **유형별 필드 변화**: 작업 = 스토리로 같다. **에픽은 우선 순위가 빠지고 「이슈 색상」이 추가**되며 순서가 바뀐다(담당자 → 레이블 → 스프린트 → 기한 → 상위 항목 …).
- **설명 에디터**: 고정 툴바가 없고 `/` 명령으로 서식을 넣는다(포커스해도 role=toolbar 0개).
- **연속 생성**: 「다른 항목 만들기」를 켜고 만들면 모달이 유지되고, 요약만 비고 프로젝트·유형은 남는다. 포커스는 요약으로 돌아간다. 푸터에 「✓ 1개의 업무 항목이 만들어짐」 카운터가 뜨고, 왼쪽 아래에 「업무 항목 작업 SCRUM-5을(를) 만듦 · 업무 항목 보기 · 링크 복사」 플래그가 뜬다. 체크를 끄고 한 건 더 만들면 모달이 닫히고 플래그가 **「2개의 업무 항목을 만들었습니다」로 합쳐진다**. 화면 이동은 없다.
- **닫기 확인**: 값이 있으면 X 를 눌렀을 때 「변경 사항이 저장되지 않습니다 — 이 페이지에서 나가면 데이터를 저장할 수 없습니다. [뒤로 가기] [버리기]」가 뜬다. 문서상 바깥 클릭은 닫지 않고 **아래쪽 dock 으로 최소화**한다.
- **보드 인라인 생성**: 카드 사이 「+」를 누르면 카드 모양 입력이 뜬다. 구성은 「무엇을 완료해야 합니까?」 + [유형 아이콘 ▾](스토리 · 작업 · 커스텀 유형, **에픽 없음**) + 기한 아이콘 + 담당자 아이콘 + ⏎ 만들기. Enter 로 만들면 카드가 그 자리에 생기고 입력이 **열린 채 비워지고 포커스가 남는다**(연속 입력). Esc 는 취소다.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JA-C-1 | `c` 키로 생성 | 보던 화면(보드) 위에 모달. URL 그대로, 만든 뒤에도 제자리 | `shortcuts.ts:86-88` 가 `navigate('/issues/new')` → **화면을 떠난다**. 닫으면 `/issues` 로, 만들면 상세로 이동(`issues.new.tsx:38-46`). 상단바 버튼만 제자리 모달(`TopBar.tsx:209`) | 같은 「만들기」가 키보드로 열면 보던 보드·백로그 맥락을 잃음 | REA `ev_63d0a4bc399f` 2026-10-28(보드 URL 유지) · 문서: "Select Create (+) in the navigation bar at the top of the screen" — https://support.atlassian.com/jira-software-cloud/docs/create-a-work-item-and-a-subtask/ | P1 |
| JA-C-2 | 연속 생성(Create another) | 푸터 「다른 항목 만들기」 체크 → 제출 뒤 모달 유지, 요약만 비움, 프로젝트·유형 유지, 포커스 요약, 「✓ N개의 업무 항목이 만들어짐」 카운터, 닫을 때 플래그 합산 | 없음(grep `createAnother|다른 항목 만들기` 0건). 성공하면 무조건 닫힘(`CreateIssueDialog.tsx:82-85`) | 기능 없음 — 여러 건 등록 때 매번 모달을 다시 열고 프로젝트·유형을 다시 고름 | REA `ev_8d08645cbc25` 2026-10-28 · 문서: "select the Create another checkbox" — 같은 문서 | P1 |
| JA-C-3 | 입력 중 닫기·Esc·바깥 클릭 | X → 「변경 사항이 저장되지 않습니다 [뒤로 가기][버리기]」. 바깥 클릭 → dock 최소화 | 취소 · Esc · 바깥 클릭 모두 즉시 닫히고 입력이 사라짐(`CreateIssueDialog.tsx:88` Radix `onOpenChange` 직결, `:119` 취소). dirty 확인 grep 0건 | **입력 유실** | REA `ev_a55136ef765e` 2026-10-28 · 문서: "If you click outside the create dialog, it minimizes into a dock at the bottom of your screen rather than closing" — 같은 문서 | P1 |
| JA-C-4 | 하위 작업(Subtask) 만들기 진입점 | 전역 생성 유형 목록에 Subtask 없음 `[TM]`. 부모 상세 「하위 작업 추가」 인라인 행 또는 Shift+C 에서만 만든다(부모 자동) | 전역 생성 유형에 Subtask 가 있고 **부모 입력란이 없다**(`IssueCreateBasicFields.tsx` · 유형 바꿔도 필드 동일). 실서비스에서 부모 없는 Subtask QAWEB-156 이 그대로 생성됨 | 고아 Subtask 생성 가능 + 부모에서 만드는 길 없음(JA-D-2) | REA `ev_dcce7cf0803d` · `ev_63d0a4bc399f` 2026-10-28 · 문서: "select Add a child work item" — 같은 문서 · BTS 변경 기록 QAWEB-156 | P1 |
| JA-C-5 | 보드·백로그에서 바로 입력(인라인 + Create) | 컬럼 카드 사이 「+」 → 인라인 카드(요약 + 유형 ▾ + 기한 + 담당자 + ⏎). Enter 를 눌러도 입력이 열린 채 연속 입력 | 인라인 없음. 보드는 헤더 버튼 1개 → 전체 모달(`projects.$projectKey.board.tsx:400-408`, 「컬럼별 버튼은 지키지 못할 약속」 ADR `docs/decisions/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md` D-3). 백로그도 스프린트 헤더 버튼 → 모달(`BacklogBoard.tsx:776-787`, `SprintColumnHeader.tsx:136`) | 한 줄 빠른 입력 없음. **ADR 로 의도한 차이**라 재결정 대상 | REA `ev_0fcd2cff37ec` 2026-10-28 · 실물 관찰만 | P1 |
| JA-C-6 | 모달 기본 형태·밀도 | 간략 모달 800×273. 요약(큰 글씨) → 설명 → 필드 알약 줄 → 푸터. 「확장」을 눌러야 전체 양식 | 672px 고정 전체 양식, 라벨 붙은 상자 나열(`CreateIssueDialog.tsx:90`). 섹션 「기본/배정/추가」, 본문 스크롤 | 첫 화면이 길다. 간략↔전체 전환 없음 | REA `ev_63d0a4bc399f` · `ev_d599c21a1986` 2026-10-28 · 문서: "select the expand icon to switch to full form mode" · "Up to 3 required fields appear as chips below the description." — 같은 문서 | P2 |
| JA-C-7 | 프로젝트·유형 선택 위치 | 헤더 왼쪽 칩 `SCRUM \| 작업`(클릭 → 드롭다운. 프로젝트는 최근/모든 스페이스 묶음) | 본문 맨 위 전체 폭 select 2개 「프로젝트」 · 「유형 선택」(`IssueCreateBasicFields.tsx:49`) | 위치·형태 다름. 최근 프로젝트 묶음 없음 | REA `ev_63d0a4bc399f` 2026-10-28 · 실물 관찰만 | P2 |
| JA-C-8 | 최소화·화면 모드·⋯ | 헤더에 최소화(dock) · 화면 모드 · 간략히/확장 · ⋯(필드 구성 · 사용하지 않는 필드 숨기기 · 항상 이 보기에서 열기 · 관찰자) · 닫기 | 닫기(X) 하나 | 최소화하고 다른 화면을 참고하며 쓰는 흐름이 없음 | REA `ev_d599c21a1986` 2026-10-28 · 문서(dock 최소화) 같은 문서 | P2 |
| JA-C-9 | 필드 구성(전체 양식 순서) | 요약 · 설명 · 상태 · 담당자 · 상위 항목 · 스프린트 · 우선순위 · 레이블 · 기한 · Team · 시작 날짜 · Story point · 보고자\* · 첨부 파일 · 연결된 업무 항목 · 제한 · Flagged | 프로젝트 · 유형 · 제목 · 설명 · 담당자 · 우선순위 · 라벨 · 컴포넌트 · 보안등급 · (커스텀) — 실서비스 `/issues/new` 관찰 | 생성 때 못 넣는 것: **상위 항목(에픽/부모)** · 스프린트 · 상태 · 기한/시작일 · 보고자 · 첨부 · 연결 이슈 | REA `ev_d599c21a1986` 2026-10-28 · 실물 관찰만 | P2 |
| JA-C-10 | 유형별 필드 변화 | 작업 = 스토리. 에픽은 우선순위가 빠지고 「이슈 색상」이 추가되며 순서도 바뀜 | Story/Task/Bug/Epic/Subtask 어느 것을 골라도 필드가 같음(실서비스 select 전환으로 확인) | 유형 인지 양식 없음 | REA `ev_d599c21a1986` 2026-10-28(에픽·스토리 전환 텍스트는 같은 탭 CDP 로 읽음) · 실물 관찰만 | P2 |
| JA-C-11 | 하단 버튼 배치 | 오른쪽에 「☐ 다른 항목 만들기」 + 「만들기」(primary). **취소 버튼 없음**(X · 확인 다이얼로그로 대체). 왼쪽에 피드백 아이콘 | 「취소」(outline) + 「이슈 생성」(`CreateIssueDialog.tsx:118-126`) | 버튼 라벨(「만들기」 vs 「이슈 생성」)과 취소 버튼 유무가 다름 | REA `ev_63d0a4bc399f` 2026-10-28 · 실물 관찰만 | P3 |
| JA-C-12 | 필수 표시 | 별표는 「보고자\*」 하나. 요약에는 별표 없음 | 제목에 별표 없음. required 커스텀 필드만 `*`(`IssueCreateExtraFields.tsx:86`) | 거의 동등 | REA `ev_d599c21a1986` 2026-10-28 · 실물 관찰만 | P3 |
| JA-C-13 | 설명 에디터 | 고정 툴바 없음. placeholder 로 `/` 명령 안내 | 고정 툴바 13버튼 + 제목 수준 select(`RichTextToolbar`) | 방식이 다름(BTS 가 발견하기 쉬움). 상세와 같은 리치 에디터라는 점은 동등 | 승계: docs/plans/2026-09-07-prose-css-tooltip.md J23 · REA `ev_d599c21a1986` | P3 |
| JA-C-14 | 생성 직후 피드백 | 제자리 유지 + 플래그 「…SCRUM-5을(를) 만듦 · 업무 항목 보기 · 링크 복사」. 연속 생성이면 합산 | 상단바·보드는 제자리 + sonner 토스트 「{key} … [보기]」(`TopBar.tsx:212-220`). 딥링크·`c` 는 상세로 이동 | 「링크 복사」 없음. `c` 경로만 이동(JA-C-1) | REA `ev_8d08645cbc25` 2026-10-28 · 실물 관찰만 | P3 |
| JA-C-15 | 인라인 생성의 유형 선택기 | 인라인 카드 왼쪽 아래 [유형 아이콘 ▾]. 에픽 제외(스토리 · 작업 · 커스텀) | 인라인 자체가 없음(JA-C-5) | JA-C-5 에 종속 | REA `ev_0fcd2cff37ec` 2026-10-28 · 실물 관찰만 | P2 |

#### BTS 가 이미 동등하거나 앞선 것
- 설명 필드가 상세와 같은 리치 에디터다(J23 승계). 툴바가 늘 보여 Jira 의 `/` 명령보다 발견하기 쉽다.
- 생성 때 컴포넌트 · 보안등급 · 프로젝트별 커스텀 필드(필수 `*`)를 넣을 수 있다. Jira 간략 모달에는 없다.
- 명령 팔레트 `/issue <제목>` 으로 제목을 미리 채운다(`CommandPalette.tsx:204`). 권한이 없으면 제출 전에 미리 알린다(`use-issue-create-permission-gate.ts`).
- 상단바·보드에서 만들면 제자리에 머물고 토스트로 갈 길을 남긴다. Jira 플래그와 동등하다.

#### 관찰 한계
- Esc 는 CDP 로 keyDown 을 보냈지만 요약에 포커스가 있는 상태에서 모달이 닫히지 않았다. Jira 의 Esc 동작이 확인 다이얼로그인지 무시인지는 **미관찰**이다. X 클릭 확인 다이얼로그만 확정이다.
- 바깥 클릭 → dock 최소화와 최소화 버튼 동작은 문서 인용뿐이다(실물 미관찰).
- 이 프로젝트의 유형은 에픽·작업·스토리(+ 다른 에이전트가 만든 `[REA] 관찰 유형`)뿐이다 — Bug 유형이 없어 「Bug 생성」은 관찰하지 못했다 `[TM]`.
- 백로그·목록 뷰 인라인 + Create 는 이번에 열지 않았다. 보드 컬럼만 관찰했다.
- REA `capture_browser_scenario` 는 connect 모드 click 한 건에서 응답 없이 30분 멈춰 버렸다. 그래서 조작(클릭·키·텍스트 입력)은 같은 탭에 CDP `Runtime.evaluate` / `Input.*` 를 직접 보냈고, 증거는 각 상태에서 REA `inspect_web_page` / `capture_web_screenshot` 로 남겼다. 메뉴 항목·필드 순서 텍스트 일부는 CDP DOM 읽기에서 왔다.
- 생성된 Jira 이슈는 스프린트를 안 골라서 스프린트 보드에 나타나지 않았다(SCRUM-5·7). 보드 모달 생성 뒤 「보드에 안 보임」은 Jira 도 같다.

---

### Jira 변경 기록
| 키 | 유형 | 제목 | 만든 방법 | URL |
|---|---|---|---|---|
| SCRUM-5 | 작업 | [REA] 생성 흐름 1 (Create another) | 보드 `c` 모달, 「다른 항목 만들기」 체크 | https://gwalho.atlassian.net/browse/SCRUM-5 |
| SCRUM-7 | 작업 | [REA] 생성 흐름 2 (단건) | 같은 모달 연속, 체크 해제 후 제출 | https://gwalho.atlassian.net/browse/SCRUM-7 |
| SCRUM-8 | 작업 | [REA] 인라인 생성 1 | 보드 「해야 할 일」 컬럼 인라인 + Enter | https://gwalho.atlassian.net/browse/SCRUM-8 |
| SCRUM-9 | Subtask | [REA] 하위 작업 1 | SCRUM-5 상세 「하위 작업 추가」 인라인 + Enter | https://gwalho.atlassian.net/browse/SCRUM-9 |

(SCRUM-6 「[REA] 자동화 트리거 테스트 (D-secondary)」는 다른 에이전트가 만든 것이다. SCRUM-5 에 달린 Automation 댓글도 다른 에이전트의 `[REA]` 규칙이 남긴 것이다.)

### BTS 변경 기록
| 키 | 유형 | 제목 | 만든 방법 | URL |
|---|---|---|---|---|
| QAWEB-156 | Subtask(부모 없음) | [REA] 생성 흐름 Subtask 부모 없이 | `/issues/new` 딥링크 모달, 유형 Subtask, 제출 → 상세로 이동 | https://bts.maxihan.com/issues/QAWEB-156 |

---

## B — 보드 · 백로그 (Jira Cloud 실물 vs BTS)

조회일 2026-10-28 · Jira 사이트 gwalho.atlassian.net, 프로젝트 SCRUM("QA Hub", team-managed), UI 언어 한국어.
관찰 방식은 REA `inspect_web_page` · `capture_web_screenshot`(증거)와 CDP 직접 클릭(메뉴 열기·닫기)이다. REA `capture_browser_scenario` connect 모드는 30분 무응답으로 끊겨서 조작은 CDP로 대신했다.

### 보드 (JB-B)
관찰 URL https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1 · 2026-10-28 · REA evidence: `ev_2ef1f23509f8`(AX 트리) · `ev_f0579b1540db`(기본 화면 스크린샷) · `ev_53a7239fb427`(보기 설정 패널 스크린샷)

#### Jira Cloud 관찰 요약
- 상단 탭: 요약 · 백로그 · **보드** · 개발 · 타임라인 · 문서 · 양식 · `+`. 탭 행 오른쪽에 공유 · 자동화(번개) · 피드백 · 전체 화면 버튼.
- 툴바 왼쪽: 「보드 검색」 입력 → 담당자 아바타 체크박스(「할당되지 않음」, 사람별) → 「필터」(팝업, Shift+F) → 「그룹」.
- 툴바 오른쪽: **「스프린트 완료」(파란 주 버튼)** → 스프린트 세부 정보(팝업: 「SCRUM Sprint 0 · 9일 남음 · 시작/종료 날짜」) → 스프린트 인사이트(오른쪽 패널) → 보기 설정(오른쪽 패널) → `⋯`.
- 컬럼 헤더: 「해야 할 일 1」처럼 이름 옆에 개수. 이름을 누르면 바로 편집되고(「상태 열 편집」), 접기 버튼과 `⋯`가 있다. 마지막 컬럼 뒤에 「상태 추가 +」.
- 카드: 제목 → 기한(「2026년 11월 2일」) → 유형 아이콘 + 키 → 담당자 아바타. 추정 버튼 「Story point estimate: 없음」은 값이 없으면 「–」로 보인다. 카드에서 제목을 바로 고칠 수 있다(「요약 편집」).
- 컬럼마다 카드 아래와 빈 컬럼 안에 점선 「+」(업무 항목 만들기). 카드 사이에도 「SCRUM-1 다음에 업무 항목 만들기」 버튼이 있다.
- 빈 컬럼은 점선 「+」 칸만 보이고 문구는 없다. 빈 보드 상태는 데이터가 있어 못 봤다.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JB-B-1 | 컬럼 안 인라인 생성 | 컬럼 끝과 빈 컬럼에 점선 `+`. 누르면 그 자리에 입력칸(placeholder 「무엇을 완료해야 합니까?」)과 유형 선택(기본 스토리), 아이콘 2개, 「만들기 ⏎」가 열린다. Enter로 제출하고 Esc로 취소한다. 카드 사이 삽입 버튼도 있다. 만든 항목은 **그 컬럼 상태**로 생긴다. | 보드에는 인라인 생성이 없다. 셸 헤더 「이슈 추가」 모달만 있다(`routes/projects.$projectKey.board.tsx:485` `boardHeaderPortals`). `BoardColumn.tsx`에서 create를 grep하면 0건이다. | 컬럼에서 바로 만들 수 없어서 모달을 열고 상태를 다시 골라야 한다. | REA `ev_2ef1f23509f8` 「업무 항목 만들기」 ×4 · CDP 클릭 관찰 · 실물 관찰만 [TM] | P1 |
| JB-B-2 | 카드 `⋯`/작업 메뉴 | 카드마다 「카드 작업」 메뉴가 있다. 항목은 업무 항목 이동 · 상태 변경 · 링크 복사 · 키 복사 · 업무 항목 연결 · 상위 항목 변경 · 커버 선택 · 레이블 편집 · 플래그 추가 · 보관 · 삭제. | 카드 메뉴가 없다. `BoardCard.tsx`에 DropdownMenu/ContextMenu가 0건이다. 카드는 링크와 드래그만 된다(`BoardCard.tsx:415`). | 상태 변경·키 복사·플래그를 하려면 상세 화면에 들어가야 한다. | CDP 메뉴 열기(menu 「업무 항목 작업」) · 실물 관찰만 | P1 |
| JB-B-3 | 보드에서 스프린트 완료 · 남은 일수 | 툴바 오른쪽에 파란 「스프린트 완료」 버튼이 있다. 누르면 모달이 뜬다: 「완료한 0개 및 미해결 2개 … 미해결 업무 항목을 다음으로 이동: [SCRUM Sprint 1▼]」 · 취소 / 스프린트 완료. 「스프린트 세부 정보」 팝업에 **「9일 남음」**과 시작·종료 날짜가 나온다. | 보드에는 스프린트 이름과 기간만 보인다(`ActiveSprintSummary.tsx:51-60`, board.tsx:529). 완료 버튼은 백로그 헤더에만 있고(`SprintColumnHeader.tsx` ACTIVE 분기) 남은 일수 표시는 없다. | 스프린트를 끝내려면 백로그로 옮겨 가야 한다. 남은 일수는 사람이 직접 셈해야 한다. | CDP 모달 관찰(취소로 닫음) · 실물 관찰만 | P1 |
| JB-B-4 | 보드 텍스트 검색 · 아바타 필터 | 툴바 첫 칸이 「보드 검색」 입력이고, 그 옆에 담당자 아바타 체크박스 줄(「할당되지 않음」, 사람별)이 있다. | 담당자·라벨·컴포넌트 드롭다운, 초기화, 필터 저장이 있다(`components/filters/FilterBar.tsx:183,276,311`, board.tsx:543). 자유 텍스트 검색과 아바타 줄은 없다. bts.maxihan.com QAWEB 보드에서 「스윔레인 · 담당자 · 라벨 · 컴포넌트 · 초기화 · 필터 저장」을 확인했다. | 카드를 찾을 때 제목 일부를 입력하는 가장 흔한 동작을 할 수 없다. | REA `ev_2ef1f23509f8` textbox 「보드 검색」, group 「담당자로 필터링」 · BTS `ev_062a42f697f3` | P1 |
| JB-B-5 | 필터 팝업 구조 | 「필터」 팝업에 **기본 / 고급 / JQL** 탭이 있다. 필드는 상위 항목 · 담당자 · 상태 · 업무 유형 · 레이블 · 필드 추가이고 「모두 지우기」가 있다. 단축키 Shift+F. | 필터 바에 항목이 고정되어 펼쳐져 있다. 상태·유형·상위(에픽)를 고를 수 없고 JQL 모드도 없다. | 상태·유형으로 좁히지 못한다. | CDP dialog 「필터」 텍스트 · 실물 관찰만 [TM] | P2 |
| JB-B-6 | Group by | 「그룹」 메뉴 항목은 Subtask · 담당자 · 레이블 · 에픽 · 우선 순위다. 툴바에 있어서 누구나 바꿀 수 있다. | 「스윔레인」 Select에 없음 · 담당자 · 우선순위 · 에픽이 있다(`i18n/board-labels.ts:278-291`). CREATE 권한자에게만 보이고 보드 단위로 저장된다(board.tsx:532-537). | 레이블·하위 작업 그룹이 없다. 권한이 없으면 셀렉터가 아예 안 보인다. | CDP menu 「그룹화 기준 옵션」 · 승계: TODOS.md J10(스윔레인 설정) | P2 |
| JB-B-7 | 보기 설정(카드 필드 · 열 크기) | 오른쪽 패널에 「완료한 업무 항목 숨기기: 안 함」 · 열 크기(고정/유연) · 스크롤(열 내에서/전체 보드) · 필드 표시가 있다. 필드 표시에서 development · Flagged · Story point estimate · 기한 · 담당자 · 레이블 · 상위 항목 · 업무 유형 · 업무 항목 키 · 요약 · 우선 순위 · 하위 작업 요약을 하나씩 켜고 끈다. | 카드 필드는 보드 설정 화면의 카드 레이아웃(관리자, `/board/settings`)에서만 바꾼다. 사용자 보기 패널, 완료 숨김, 열 폭 옵션은 없다. | 개인 단위로 바로 조절할 수 없다. | REA `ev_53a7239fb427` 스크린샷 · 승계: TODOS.md J12·J16·J17 (company-managed 기준) [TM] | P2 |
| JB-B-8 | 컬럼 헤더 조작(접기 · WIP 최소/최대 · 이름 편집) | 헤더 이름을 누르면 바로 편집된다. 「접기」 버튼이 있다. `⋯`에는 열 제한 설정 · 열을 오른쪽으로 이동 · 상태 삭제가 있다. 열 제한 모달은 「이 열의 **최소 및 최대** 업무 항목 한도」 · 최소 · 최대 · 취소/저장이다. 마지막 컬럼 뒤에 「+」(상태 추가)가 있다. | 헤더에는 이름 · 카테고리 배지(raw 「TODO」) · `count/limit` WIP 배지가 있다(`BoardColumn.tsx:144-153`, `WipCountBadge.tsx`). WIP는 최대값 하나뿐이고 설정 화면에서만 바꾼다. 접기와 헤더 바로 편집은 없다. | 접기가 없고 최소 WIP가 없다. 헤더에 영문 카테고리 코드가 그대로 보인다. | CDP dialog 「열 한도」 · 승계: TODOS.md J9 · BTS 실화면 「Open \| TODO \| 45」 `ev_062a42f697f3` | P2 |

#### BTS 가 이미 동등하거나 앞선 것
- 드래그: 포인터와 **키보드** 드래그를 모두 지원한다(`board-drag-sensors.ts:33`, `KanbanBoard.tsx:225`). Jira 보드에서는 드래그를 관찰하지 않았다(지시).
- 보드 스위처(1개여도 노출)와 보드 `⋯` 관리 메뉴는 Jira TM의 단일 보드보다 다중 보드 모델을 더 분명히 드러낸다.
- 퀵필터 칩과 「필터 저장」이 툴바에 바로 있다. Jira TM 보드는 `⋯ > 사용자 지정 필터 관리`를 거쳐야 한다.
- 필터 결과 0건 빈 상태(「조건에 맞는 카드가 없습니다」 + 초기화, board.tsx:596)와 스크럼 무스프린트 빈 상태(:610)가 있다. Jira 빈 컬럼에는 문구가 없다.
- `truncated` / `unplacedCount` 경고 배너. Jira에서는 대응 요소를 관찰하지 못했다.

#### 관찰 한계
- 카드의 라벨·에픽 칩, 우선순위 아이콘, 추정 값은 샘플 데이터에 값이 없어 미관찰이다(필드 목록만 보기 설정에서 확인했다).
- 카드 우클릭 메뉴는 `⋯` 메뉴와 같은 메뉴인지 확인하지 못했다. 빈 보드(스프린트 없음) 상태와 「스프린트 인사이트」 패널 본문은 로딩 중이라 미관찰이다.
- Jira 문서 인용(b)은 보드 쪽에서 새로 확보하지 못했다. 열 제한 TM 문서 URL은 404였다.

### 백로그 (JB-L)
관찰 URL https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog · 2026-10-28 · REA evidence: `ev_6ad0a7fb006e`(AX 트리) · `ev_61a27af449f4`(기본 화면) · `ev_f407f0768a80`(인라인 생성 열림 + 설정 보기 패널). BTS 실화면은 `ev_0f8bb4e6aaa7`(bts.maxihan.com/projects/QAWEB/backlog).
team-managed 프로젝트지만 백로그는 켜져 있다.

#### Jira Cloud 관찰 요약
- 툴바: 「백로그 검색」 · 담당자 아바타 · 「필터」 | 오른쪽 백로그 인사이트 · 설정 보기 · `⋯`.
- 섹션은 위에서부터 활성 스프린트 → 계획 스프린트 → Backlog 순으로 쌓인다. 각 헤더에는 전체 선택 체크박스, 접기(⌄), 이름, 날짜(「28 10월~11 11월」, 미설정이면 「✎ 날짜 추가」), 「(N개의 업무 항목)」이 있다. 오른쪽에는 **상태별 포인트 배지 3개(회색/파랑/초록, 「해야 할 일: 0개 중 0개(스토리 포인트)」)**, 스프린트 완료/시작 버튼, `⋯`가 있다.
- Backlog 헤더 오른쪽에는 「스프린트 만들기」 버튼이 있다(배지 3개 옆).
- 행: 유형 아이콘 · 키 · 제목 … **상태 드롭다운 로젠지(「해야 할 일 ⌄」)** · 기한 칩 · 추정 「-」 · 우선순위 아이콘 · 담당자 아바타.
- 섹션마다 맨 아래에 「+ 만들기」가 있다. 빈 스프린트 플레이스홀더 문구는 「업무 항목을 스프린트에 끌어서 놓거나 스프린트 바닥글을 끌어서 스프린트를 계획하세요.」이다.
- 스프린트와 백로그 사이에 바닥글 드래그 핸들(⇕)과 「0/0개의 업무 항목 표시 | 추정치: 0/0」이 있다.
- 설정 보기 패널: Epic 패널(단축키 E, 기본 꺼짐) · 빈 스프린트(켜짐) · 밀도 기본값/간단히 · 필드 토글(업무 유형 · 키 · Epic · 기한 · 상태 · 우선 순위 · 담당자).

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JB-L-1 | 섹션 안 인라인 생성 | 섹션 맨 아래 「+ 만들기」를 누르면 그 자리에 한 줄 입력 행이 열린다. 유형 선택(기본 작업), placeholder 「해야 할 일을 설명하거나 Confluence, Loom 또는 이미지에서…」, 기한 · 담당자 아이콘, 「만들기 ↵」가 있다. 만든 항목은 그 스프린트에 들어간다. | 헤더 아이콘 버튼이 **모달**(`CreateIssueDialog`, `BacklogBoard.tsx:776/787/927`)을 연다. 입력 행은 없다. | 연속으로 입력하는 속도가 다르다. 생성 진입점도 섹션 아래가 아니라 헤더에 있다. | REA `ev_f407f0768a80` 스크린샷 · 문서: "Just select **+ Create work item** at the bottom of a sprint or the backlog section." https://support.atlassian.com/jira-software-cloud/docs/use-your-scrum-backlog/ | P1 |
| JB-L-2 | 행에서 상태 바로 바꾸기 | 각 행에 상태 로젠지 드롭다운(「해야 할 일 ⌄」 · 「진행 중 ⌄」)이 있다. | 행에 상태 표시가 없다. bts.maxihan.com 행 텍스트는 「제목 · 라벨 · 키 · P2 · 담당자」뿐이다(`BacklogCard.tsx:269-285`). | 상태를 보거나 바꾸려면 상세 화면을 열어야 한다. | REA `ev_6ad0a7fb006e` button 「해야 할 일」 ×N · BTS `ev_0f8bb4e6aaa7` · 실물 관찰만 | P1 |
| JB-L-3 | 스프린트 헤더 정보 | 헤더에 날짜 범위(없으면 「날짜 추가」 인라인 버튼), 「(N개의 업무 항목)」, 상태별 포인트 배지 3개가 있다. 상태 문자열은 없다. | 헤더에 이름 · **raw 「ACTIVE」/「PLANNED」 배지** · 개수 숫자 · 시작/완료 · 번다운 링크가 있다(`SprintColumnHeader.tsx:118-133`). 날짜와 포인트 합은 0건이다(grep startDate 0). | 날짜와 진척 포인트가 안 보이고 영문 코드가 그대로 노출된다. | REA `ev_61a27af449f4` status 「해야 할 일: 0개 중 0개(스토리 포인트)」 · BTS 실화면 「QAWEB Sprint 2 \| ACTIVE \| 30」 | P1 |
| JB-L-4 | 빈 스프린트 플레이스홀더 | 점선 상자에 「업무 항목을 스프린트에 끌어서 놓거나 스프린트 바닥글을 끌어서 스프린트를 계획하세요.」 설정 보기의 「빈 스프린트」 토글로 숨길 수 있다. | 「이슈 없음」만 있다(`SprintColumn.tsx:161-163`, `BacklogColumn.tsx:203-205`). | 다음 행동 안내가 없다. | REA `ev_61a27af449f4` · 실물 관찰만 | P2 |
| JB-L-5 | 「스프린트 만들기」 위치 | Backlog 섹션 헤더 오른쪽 버튼을 한 번 누르면 바로 다음 스프린트가 생긴다(이름 입력 폼 없음, 관찰상 버튼 1개). | Backlog 칸의 이름 입력 폼과 「스프린트 생성」 버튼(`BacklogColumn.tsx:181` `CreateSprintForm`). | 단계가 하나 더 있다(이름 입력). 위치는 같은 섹션이다. | REA `ev_6ad0a7fb006e` button 「스프린트 만들기」 · 문서: "Select **Create sprint**, then drag and drop work items into your new sprint." (use-your-scrum-backlog) | P2 |
| JB-L-6 | 에픽 패널 토글 | 기본은 꺼짐이다. 설정 보기의 「Epic 패널」 토글이나 단축키 **E**로 연다. 빈 상태는 「작업의 많은 부분을 계획하고 우선 순위를 지정하세요. 첫 에픽을 만들어…」 + 「만들기 에픽」이다. | 에픽 패널이 늘 펼쳐져 있고 토글이 없다(`BacklogBoard.tsx:650`). 빈 상태는 「에픽 없음 · 백로그에 이슈가 있는 에픽만 표시됩니다.」이고 만들기 진입점은 없다. | 화면 폭을 차지하고, 빈 상태에서 에픽을 만들러 갈 길이 없다. | CDP 토글 관찰(켰다 끔) · 문서: "To open and close the panel, use the toggle in the epic filter dropdown." (use-your-scrum-backlog, company-managed 표현) [TM] | P2 |
| JB-L-7 | 스프린트 `⋯` 메뉴 | 활성 스프린트: 업무 순서 변경 · 스프린트 편집 · 스프린트 삭제. 계획 스프린트: 스프린트 편집 · 스프린트 삭제. | 편집 · 삭제(`SprintActionsMenu.tsx:162-169`). | 「업무 순서 변경」만 없다. | CDP menu 관찰 · 승계: docs/plans/2026-09-01-sprint-manage-ui.md J2 · J4 | P3 |
| JB-L-8 | 밀도 · 행 필드 토글 | 설정 보기에서 밀도(기본값/간단히)와 필드 7종을 하나씩 켜고 끈다. | 행 구성이 고정이다. 카드 레이아웃은 보드 설정에만 있다. | 개인 조절 수단이 없다. | REA `ev_f407f0768a80` 패널 · 승계: TODOS.md J18(백로그/활성 스프린트 필드를 따로 설정) | P3 |

#### BTS 가 이미 동등하거나 앞선 것
- 세로 스택 순서(스프린트 위, 백로그 아래)는 같다. 승계: docs/plans/2026-09-02-scrum-board-screen.md J7.
- 「백로그 검색」 입력이 있다(`BacklogFilterBar.tsx:162`). Jira와 같은 위치다.
- 스프린트 접기(`SprintColumnHeader.tsx:110`)는 Jira의 ⌄ 접기와 같다.
- 시작 다이얼로그(기간·목표 편집 후 시작)와 완료 다이얼로그(미완료 이관 대상 선택)도 Jira 흐름과 같다. 승계: 2026-09-02-scrum-board-screen J10. 시작·완료 실패 메시지는 BTS 쪽이 더 촘촘하다.
- 헤더의 번다운 진입 링크는 Jira 백로그 헤더에 없다(Jira는 「백로그 인사이트」 패널).

#### 관찰 한계
- 행 우클릭 메뉴는 합성 contextmenu 이벤트에 반응하지 않아 미관찰이다(문서에는 "Right-click a work item and select **Split work item**"이 있다).
- 스프린트 시작 다이얼로그는 계획 스프린트가 비어 버튼이 비활성이라 열지 못했다. 백로그 툴바 `⋯`도 미관찰이다.
- 에픽 칩, 추정 값, 포인트 합계는 데이터에 값이 없어 0 표시만 확인했다.

### 설정 진입 경로 (상단바 톱니 「설정」)
관찰 2026-10-28, 보드 화면 상단바 오른쪽 톱니(알림 · 도움말 다음, 프로필 아바타 앞). 목록까지만 보고 하위 페이지는 열지 않았다.
- **개인 Jira 설정**: 검색(⌘+K) · 일반 설정(「언어, 표준 시간대 및 기타 개인 기본 설정」) · 알림 설정(「이메일 및 앱 내 알림」).
- **Jira 관리자 설정**: 시스템(「일반 구성, 보안, 자동화, 사용자 인터페이스 등」) · Jira 앱(「Jira 전반의 액세스, 설정 및 통합」) · 스페이스(「스페이스 설정, 범주 등」) · 업무 항목(「업무 유형, 워크플로, 화면, 필드 등」) · Marketplace 앱.
- **Atlassian 관리자 설정**: 사용자 관리(「사용자, 그룹 및 액세스 요청」) · 청구.
- 보드 쪽 설정 진입은 보드 `⋯`(보드 보기 작업 더 보기)에 있다: 스탠드업 · 열 구성 · 스프린트 편집 · 워크플로 관리 · 사용자 지정 필터 관리 [TM]. company-managed의 「Configure board」 경로는 승계: TODOS.md J7.

### Jira 변경 기록
- https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog: 설정 보기의 「Epic 패널」 토글을 켰다가 바로 껐다(원래 값은 꺼짐, 복원함). 보기 설정만 바꿨고 데이터 변경은 없다.
- 그 밖에는 메뉴·모달을 열고 Esc/취소로 닫기만 했다. 「스프린트 완료」 모달과 「열 한도」 모달도 취소로 닫았다. 화면에 보이는 [REA] 이슈(SCRUM-5~8)와 「[REA] 검증 대기」 컬럼은 다른 에이전트가 만든 것이다.

### BTS 변경 기록
- 없음. bts.maxihan.com의 QAWEB 보드와 백로그는 읽기만 했다.

---

## C — 이슈 목록/검색(JC-L) · 전역 내비(JC-N) 조작감 갭

> 표기 메모. 이 사이트의 Jira UI 언어는 한국어이고, project=「스페이스」, issue=「업무 항목」으로 표시된다. 아래 Jira 열은 화면 문구를 그대로 옮겼다.
> evidence_id 는 앞 12자다(전체 id 는 관찰 한계 절 끝에 있다). 「CDP 판독」은 REA evidence 없이 CDP `Runtime.evaluate` 로 DOM 텍스트를 읽었다는 뜻이다(관찰 한계 참조).

### 이슈 목록/검색 (JC-L)
관찰 URL · 조회일 2026-10-28
- https://gwalho.atlassian.net/issues/?jql=project%3DSCRUM — ev_1eef6df87(기본) · ev_96b86336b(열 구성) · ev_804836340(헤더 ⋯) · ev_5b7446942(담당자 셀 편집기) · ev_b94a24937(일괄 툴바) · ev_814aff0b3(모달 상세) · ev_10d8c9a0d(사이드바 분할) · ev_f7dc13071(JQL 자동완성) · ev_2716d615d(0건) · ev_dfcf2fad4(저장 필터)
- https://gwalho.atlassian.net/jira/software/projects/SCRUM/list — ev_97a42f44b(목록 탭 없음 상태) · ev_47519888f(탐색에 추가 대화)
- https://gwalho.atlassian.net/jira/software/projects/SCRUM/list/VZJ44HA — ev_e31a9b3a0(목록 뷰) · ev_c8c3cf143(필터 팝업)

#### Jira Cloud 관찰 요약
- **표면이 두 개이고 같은 표 컴포넌트를 쓴다.** 하나는 전역 「모든 업무」(`/issues?jql=`), 하나는 스페이스의 「목록」 탭(`/list/<viewId>`)이다. 둘 다 `native-issue-table` 이다.
- 전역 검색 상단에는 「AI에게 물어보기」, 「기본 | JQL」 라디오, 「업무 검색」 텍스트, 필터 칩(스페이스=QA Hub · 담당자 · 유형 · 상태 · 더 많은 필터 옵션), 「필터 지우기」, 「필터 저장」이 있다. 우측 상단에는 앱 · 공유 · 추가 작업이 있다.
- 기본 컬럼은 [체크박스] · 업무(유형 아이콘+키+요약 합본) · 담당자 · 보고자 · 우선 순위 · 상태 · 해결 · 만듦 · 업데이트 · 기한 · 「열 구성」이고, 행 끝에 ⋯ 이 붙는다. 표 아래에는 「새로 고침」이 있다.
- 셀 대부분이 인라인 편집 버튼이다(요약·담당자·보고자·우선순위·상태·기한). 선택하면 바로 저장되고 토스트는 뜨지 않는다.
- 키를 누르면 기본으로 **모달**이 열린다(`selectedIssue=`). 「사이드바로 전환」을 누르면 표 오른쪽 분할 패널이 되고, 이 선택은 기억된다.
- 빈 결과에서는 헤더가 유지되고 「여기에 아직 업무 항목이 없습니다」 / 「업무 항목이 없거나 기존 업무 항목이 현재 필터와 일치하지 않습니다.」가 뜬다. CTA 는 없다.
- 이 TM 스페이스에는 처음에 **목록 탭이 없었다**(`/list` → 「원하는 페이지를 찾을 수 없음」 + 「기본 보기로 이동」). 「탐색에 추가」로 넣어야 했다.
- 목록 뷰 툴바는 업무 검색 · 담당자 아바타 필터 · 「필터」(Shift+F) · 「그룹화」 · 추가 작업으로 되어 있고, 표 아래에 「+ 만들기」가 있다.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JC-L-1 | 검색 결과를 표로 다루기 | JQL 검색 결과가 목록과 **같은 표**다. 컬럼 · 인라인 편집 · 체크박스 · 일괄 툴바가 그대로 있다 | `/search` 는 **카드 목록**이다(`routes/search.tsx:408` `<ul aria-label="검색 결과">` + `SearchResultCard`). 표·인라인 편집·일괄은 `/issues` 에만 있다(`issues.index.tsx:568`) | 저장 필터·AQL 결과에서는 일괄 편집도 셀 편집도 할 수 없다. 검색과 목록이 서로 다른 UI 다 | ev_1eef6df87 · /issues/?jql=project%3DSCRUM · 2026-10-28 / 실물 관찰만 | P1 |
| JC-L-2 | 기본 모드 ↔ JQL 모드 전환 | 같은 화면에 「검색 모드 선택」 라디오(기본/JQL)가 있다. 기본은 텍스트+필터 칩, JQL 은 편집기 · 「편집기」 · 「구문 도움말」 · 「검색」 | 목록(`IssueFilterBar`, `issues.index.tsx:23`)과 AQL(`search.tsx:335` 「AQL 검색」)이 **별도 화면**이고 토글이 없다 | 칩으로 걸러 놓은 조건을 AQL 로 넘겨 다듬는 흐름이 없다 | ev_1eef6df87 · ev_f7dc13071 · 2026-10-28 / "a query created using the basic search can be translated to advanced JQL search, and back again." — https://support.atlassian.com/jira-software-cloud/docs/what-is-advanced-search-in-jira-cloud/ | P2 |
| JC-L-3 | JQL 입력 자동완성 | `project=SCRUM AND sta` 를 입력하면 listbox 에 「Start date · status · statusCategory · statusCategoryChangedDate」가 뜬다. Enter 로 검색하고 Shift+Enter 로 줄을 바꾼다 | `AqlHighlighter` 는 구문 강조만 한다(`search.tsx:350`). 자동완성 코드가 없다(grep `autocomplete\|suggestion` 결과는 주석 1곳뿐). 제출은 Cmd/Ctrl+Enter(`search.tsx:375`) | 필드명·값 후보가 없다. 제출 키도 다르다(Enter vs Cmd+Enter) | ev_f7dc13071 · 2026-10-28 / "As you type, Jira will offer a list of "autocomplete" suggestions based on the context of your query." — 같은 문서 | P2 |
| JC-L-4 | 컬럼 선택기 | 표 오른쪽 끝 헤더 「열 구성」을 누르면 탭 「내 기본값 / 시스템」, 「열 검색」 combobox, 다중 선택 listbox 가 뜬다(37개 안팎 — 진행 시간 합 · Story point estimate · 스프린트 · 레이블 · 상위 항목 · 진행률 · 커스텀 필드 등) | 툴바 「컬럼」 드롭다운 체크박스(`ColumnSelector.tsx:39-80`). 후보는 7개(유형·키·요약·상태·담당자·우선순위·수정일, `issue-columns.ts:278-339`)이고 localStorage 기기별 저장이다(`issues.index.tsx:103`) | 검색이 없다. 보고자·만듦·기한·레이블·스프린트·SP 컬럼이 없다. 사용자/시스템 기본값 구분과 서버 저장이 없다 | ev_96b86336b · 2026-10-28 / 실물 관찰만 | P2 |
| JC-L-5 | 기본 컬럼 구성 | 업무(합본) · 담당자 · 보고자 · 우선 순위 · 상태 · 해결 · 만듦 · 업데이트 · 기한 | 유형 · 키 · 요약 · 상태 · 담당자 · 우선순위 · 수정일(`issue-columns.ts:278-339`) | 보고자 · 해결 · 만듦 · 기한이 빠졌다. 유형·키·요약이 세 컬럼으로 나뉘어 있다(Jira 는 한 셀) | ev_1eef6df87 · 2026-10-28 / "The columns in your list" 절 — https://support.atlassian.com/jira-software-cloud/docs/what-is-the-list-view/ | P2 |
| JC-L-6 | 컬럼 헤더 ⋯ 메뉴 | 헤더마다 ⋯ 가 있고 「오름차순으로 정렬 · 내림차순으로 정렬 \| 열을 오른쪽으로 이동 · 열을 마지막 위치로 이동 · 열 제거 \| 열 크기 조정」이 들어 있다. 헤더 라벨을 눌러도 정렬된다 | 헤더 클릭은 asc→desc→해제 3단 순환이다(`issues.index.tsx:157`, `IssueTable.tsx:238-245`). 폭 조절 separator 가 있다(`IssueTable.tsx:132`). 헤더 메뉴는 없다 | 열 순서 변경 · 헤더에서 바로 열 제거가 안 된다 | ev_804836340 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-L-7 | 셀 인라인 편집 범위 | 편집 버튼이 붙은 셀은 **요약**(텍스트 input), **담당자**(검색 input + 「자동」·사용자 listbox), **보고자**, **우선 순위**(Highest~Lowest listbox), **상태**(전이 대상만, 예: 검토 중/진행 중/완료), **기한**(날짜 input)이다. Esc 로 취소하면 값이 그대로 남는다 | 담당자 · 우선순위 · 상태만 된다(`cells/EditableCell.tsx:62`). 승계: `docs/specs/2026-08-04-fr-ux-11-f9-list-cell-inline-edit.md` §Jira 대조 갭표(담당자·우선순위·상태 행) | 요약 · 보고자 · 기한 인라인 편집이 없다 | ev_5b7446942(담당자 편집기) · ev_1eef6df87(편집 버튼 목록) · 상태/우선순위/요약/기한 편집기는 CDP 판독 · 2026-10-28 / 실물 관찰만 | P2 |
| JC-L-8 | 인라인 저장 피드백 | SCRUM-8 우선순위 Medium→Low 를 고르자 셀이 즉시 「Low」로 바뀌었다. 토스트·플래그는 없었다(3초 관찰) | 대조 미실시(BTS 실서비스 확인 못 함 — 관찰 한계) | 미확인 | CDP 판독 · /issues/?jql=project%3DSCRUM%20ORDER%20BY%20key%20DESC · 2026-10-28 / 실물 관찰만 | P3 |
| JC-L-9 | 행 ⋯ 메뉴 | 업무 항목 보기 · 댓글 · 작업 로그 · Connect Slack channel · 애자일 보드 · 맨 위로/맨 아래로 순위 변경 · 파일 첨부 · 투표자 · 관찰 중지 · 지켜보는 사람 · 하위 작업 만들기 · 하위 작업으로 변환 · 이동 · 복제 · 삭제 · 보관 · 업무 항목 연결 · 웹 링크 추가 | 행 메뉴 컬럼이 없다(컬럼 정의 7개뿐, `issue-columns.ts:278-339`) | 목록에서 복제·연결·삭제·관찰을 하려면 상세로 들어가야 한다 | CDP 판독(SCRUM-1 ⋯) · 2026-10-28 / 실물 관찰만 | P2 |
| JC-L-10 | 행 체크박스 · 일괄 작업 바 | 헤더 「모두 선택」 + 행 체크박스를 쓴다. 하나라도 고르면 표 아래 toolbar 「Bulk actions」가 뜬다(**Select all · 필드 편집 · 상태 변경 · 관찰 옵션 · 삭제 · Clear all**). 추가 작업 ⋯ 에 「업무 항목 일괄 변경」(구 일괄 마법사)도 있다 | 툴바 「N건 선택됨 · 일괄 편집 · 일괄 전환 · 선택 해제」(`IssueBulkActionBar.tsx:33-55`, 결선 `issues.index.tsx:831/871/879`) | 일괄 관찰·일괄 삭제가 없다. 「Select all」(결과 전체) 대응 여부는 미확인이다 | ev_b94a24937 · 2026-10-28 / "Select multiple work items in the list." · "You can edit fields in up to 1,000 work items at the same time." — https://support.atlassian.com/jira-software-cloud/docs/edit-multiple-issues/ | P3 |
| JC-L-11 | 상세 열기 · 분할 패널 | 키를 누르면 **모달**(`?selectedIssue=SCRUM-1`, 헤더 「이전 업무 항목 · 다음 업무 항목 · 사이드바로 전환 · 닫기」)이 열린다. 「사이드바로 전환」을 누르면 표 오른쪽 `region "Panel"`(이전/다음 · Open in new tab · Expand · Close)로 바뀌고, 그 선택을 다음에도 기억한다 | 행을 클릭하면 우측 split pane 이 열린다(`selected=`, `IssueTable.tsx:94/351`). `t` 로 토글한다. 모달 모드는 없다 | 기본 형태가 반대다(Jira 모달 기본 + 분할 선택, BTS 분할 고정). 패널에 이전/다음 버튼이 없다(키 `j`/`k` 만) | ev_814aff0b3 · ev_10d8c9a0d · 2026-10-28 / 실물 관찰만 | P2 |
| JC-L-12 | 그룹화 [TM] | 프로젝트 목록 뷰 「그룹화」 listbox: Agent · Story point estimate · 기한 · 담당자 · 레이블 · 만듦 · 보고자 · 상태 · 스프린트 · 시작 날짜. 전역 검색에는 그룹화 버튼이 없다 | 없다(`issues.index.tsx` 에 groupBy 없음) | 목록 그룹화가 없다 | ev_e31a9b3a0(버튼) · 목록은 CDP 판독 · 2026-10-28 / 실물 관찰만 | P2 |
| JC-L-13 | 목록 뷰 필터 진입 [TM] | 「필터」 버튼(툴팁 「필터 Shift F」)을 누르면 팝업에 탭 「기본 · 고급 · JQL」 + 「저장된 필터」 버튼이 있다. 툴바에는 담당자 아바타 체크박스 필터가 있다 | `IssueFilterBar` 칩 — 상태·담당자·레이블·컴포넌트(`IssueFilterBar.tsx:56-154`) | 목록 안에서 JQL·저장 필터로 들어가는 길이 없다. 아바타 필터와 Shift+F 가 없다 | ev_c8c3cf143 · ev_e31a9b3a0 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-L-14 | 필터 저장 · 저장 필터 진입 | 「필터 저장」을 누르면 모달(이름* · 설명 · 조회자 「비공개」 · 편집기 「비공개」 · 취소/저장)이 뜬다. 저장하면 URL 이 `?filter=10001`, 제목이 필터명이 되고 **자동 별표**(pressed)가 붙으며 사이드바 필터 목록에 나타난다. 버튼은 「필터 복사」로 바뀐다. 사이드바 「필터」를 펼치면 업무 항목 검색 · 기본값 필터 9종(나의 미해결 · 내가 보고함 · 모든 · 미해결 · 완료된 · 최근 확인 · 최근에 만듦 · 최근에 해결됨 · 최근 업데이트) · 모든 필터 보기 | `/search` 툴바 `SavedFilterMenu` + 「저장」(`search.tsx:573-584`, 승계 저장 필터 :575). 사이드바에는 필터가 없다(`i18n/nav-labels.ts:17` "filters는 여전히 백킹이 없어 미추가") | 사이드바 진입점 · 기본 필터 · 저장 후 별표가 없다. `/issues` 화면에서는 저장할 수 없다 | ev_dfcf2fad4 · ev_1eef6df87(사이드바 필터) · 2026-10-28 / "save searches, called filters, for later use" — https://support.atlassian.com/jira-software-cloud/docs/save-your-search-as-a-filter/ | P2 |
| JC-L-15 | 빈 결과 상태 | 헤더 행이 유지되고 「여기에 아직 업무 항목이 없습니다」 + 「업무 항목이 없거나 기존 업무 항목이 현재 필터와 일치하지 않습니다.」가 뜬다. CTA 는 없다 | 필터가 있으면 `FilteredEmptyState` 「필터 조건에 맞는 이슈가 없습니다.」 + 초기화 CTA(`issues.index.tsx:841-843`), 없으면 「이슈가 없습니다.」(`:192`), `/search` 는 「검색 결과가 없습니다. / 다른 쿼리를 시도해 보세요.」(`search.tsx:398-402`). 표 헤더는 사라진다 | 문구 · 헤더 유지가 다르다. CTA 는 BTS 가 앞선다 | ev_2716d615d · /issues/?jql=project%3DSCRUM%20AND%20summary%20~%20%22zzqqxx%22 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-L-16 | 검색 화면 추가 작업 ⋯ | 업무 항목을 차트로 보기 · 내보내기 · CSV에서 업무 항목 가져오기 · 업무 항목 일괄 변경 · 피드백 보내기. 별도 「공유」 버튼이 있다 | `/search` 에 내보내기(`ExportDialog`) · 공유(`ShareFilterDialog`)가 있다(`components/search/`) | 차트 보기 · CSV 가져오기가 없다 | CDP 판독 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-L-17 | 목록 키보드 | `?` 「이슈 탐색」 절: O 선택한 이슈 보기 · J 다음 · K 이전 · Z 이슈 전체 화면 토글 · [ 필터 패널 고정 · N/P 활동 · F 검색 필드 포커스 · U 이슈 검색 · **T 필터 보기 전환** · Y 세부 사항 보기 정렬 · S 검색 기준 공유 | j · k · o · t(상세 패널 토글)(`context-shortcuts.ts:92-110`). 승계: `docs/specs/2026-08-03-fr-ux-10-f10-context-shortcuts.md` §Jira 대조 | `t` 의 의미가 다르다(Jira 「필터 보기 전환」, BTS 「상세 패널」). z · f · u · s 가 없다 | ev_dae7c6f55 · 2026-10-28 / "Press the ? on your keyboard from any page" — https://support.atlassian.com/jira-software-cloud/docs/use-keyboard-shortcuts/ | P3 |

#### BTS 가 이미 동등하거나 앞선 것
- 정렬 상태의 URL 영속(`sort=field,dir`, `issues.index.tsx:131-154`) — Jira 는 JQL `ORDER BY` 로 URL 에 남긴다. 동등하다.
- 정렬·필터 전환 중 이전 결과 유지(`keepPreviousData`, `:616`) — 동등하다.
- 필터 0건일 때 「필터 초기화」 CTA — Jira 0건 화면에는 CTA 가 없다. BTS 가 앞선다.
- 컬럼 폭 조절 + 「너비 초기화」(`ColumnSelector.tsx`) — Jira 는 헤더 메뉴 「열 크기 조정」. 동등하다.
- j/k/o 커서 이동 — 동등하다(승계 F10).

#### 관찰 한계
- 아래 「공통 관찰 한계」 절을 함께 본다.
- BTS 열은 **코드 기준**이다. 실서비스(bts.maxihan.com) 대조는 Bash 차단으로 하지 못했다.
- 「Select all」이 페이지 너머 전체 결과를 고르는지는 누르지 않아 미관찰이다.
- 목록 뷰 하단 「+ 만들기」 인라인 생성과 BTS 대응은 대조하지 않았다.

### 전역 내비 (JC-N)
관찰 URL · 조회일 2026-10-28
- https://gwalho.atlassian.net/jira/for-you — ev_b06385b14(검색 드롭다운) · ev_dae7c6f55(`?` 단축키) · ev_761a0978c(Cmd+K 팔레트)
- https://gwalho.atlassian.net/issues/?jql=project%3DSCRUM — ev_1eef6df87(상단바 · 사이드바 · 필터 펼침)
- https://gwalho.atlassian.net/jira/software/projects/SCRUM/list — ev_97a42f44b(스페이스 헤더 · 탭) · ev_47519888f(탐색에 추가)

#### Jira Cloud 관찰 요약
- 상단바(banner, 왼쪽→오른쪽): 사이드바 접기 · 사이트 또는 앱 전환 · Jira 로고(홈) · 검색 · **만들기** · (플랜 보기 — 체험 배너) · 알림(이름에 개수, 예 「알림 1개」) · 도움말 · 설정(톱니) · 아바타.
- 사이드바: 추천(For you) · 최근 항목▸ · 별표 표시됨▸ · 앱 · 계획 · 스페이스(+ 만들기, ⋯, 최근 「QA Hub」, 추가 스페이스) · 필터▸ · 대시보드(+ 만들기). 구분선 아래 자산↗ · 팀↗ · 「사이드바 사용자 지정」 · 「패널 크기 조정」 slider. 기본 폭은 320px 이다.
- 스페이스 화면은 브레드크럼 「스페이스」 → 이름 「QA Hub」(편집 가능) + 기여하는 팀 연결 · 추가 작업 · 공유 · 자동화 · 피드백 제공 순이고, 그 아래 **수평 탭**이 온다: 요약 · 백로그 · 보드 · 개발 · 타임라인 · 문서 · 양식 · [탐색에 추가 +]. 탭마다 ⋯ 이 있다.
- 검색창에 포커스하면 패널이 열려 최근 조회 업무 항목 · 최근 보드/프로젝트/필터 · 범주 이동 · 필터 칩을 보여 준다.
- 다른 것은 단축키다. `?` 는 키보드 단축키 다이얼로그, `[` 는 사이드바 토글, Cmd+K 는 명령 팔레트를 연다.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JC-N-1 | 검색창 포커스 드롭다운 | 클릭만 해도 패널이 열린다. 「최근에 조회」(키 + 요약 + 스페이스명, 예 「SCRUM-1 작업 1 · QA Hub」) → 「최근 보드, 프로젝트 및 필터」 → 「모든 항목으로 이동: 보드 · 프로젝트 · 필터 · 사용자」 → 필터 칩(마지막 업데이트 Any time~Past year · 프로젝트 · 담당자 · 보고자 · 상태 Open/Done · 레이블) → 「모든 업무 항목 보기」 | 포커스해도 드롭다운이 없다. Enter 로 제출하면 자연어를 AQL 로 바꾼다(`TopBar.tsx:157-173`, `lib/aql-natural.ts`). 승계: `docs/specs/2026-08-05-fr-ux-12-f13-global-search-input.md` J2(결과 묶음) · J3(View all) | 입력 전 「최근 항목」 재방문 경로가 없다. 범주 이동과 필터 칩도 없다 | ev_b06385b14 · 2026-10-28 / "Search: Find work items, spaces, boards, queues, filters and people." — https://support.atlassian.com/jira-software-cloud/docs/navigate-to-your-work/ | P1 |
| JC-N-2 | 상단바 구성·순서 | 접기 · 앱 전환 · 로고 · 검색 · 만들기 · 알림 · 도움말 · 설정 · 아바타 | 접기 · 로고 Atlas(→`/dashboards`) · **프로젝트 스위처** · 검색 · 만들기 · 벨 · (도움말 숨김) · **관리 허브** · 설정 · 계정(`TopBar.tsx:110-260`) | 프로젝트 스위처가 상단바에 있다(Jira 는 사이드바 「스페이스」). 관리가 톱니와 따로 노출된다. 도움말이 없다. 앱 스위처는 제품 범위 밖일 수 있다 | ev_1eef6df87 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-N-3 | 도움말 | 「도움말」을 누르면 **오른쪽 도움말 패널**(H2 「도움말」)이 열린다. 단축키 `H` 도 있다 | 버튼은 있지만 `ShellLayout` 이 `onHelpClick` 을 넘기지 않아 숨겨져 있다(`TopBar.tsx:230`, 주석 :72-75, F6 미착수) | 상단바에서 도움말·단축키 안내로 가는 길이 없다(`?` 키만 있다) | CDP 판독(오른쪽 x≈1553 H2 「도움말」) · 2026-10-28 / 실물 관찰만 | P2 |
| JC-N-4 | 설정 톱니 | 톱니를 누르면 **메뉴**가 열린다. 「개인 Jira 설정」(검색 ⌘K 힌트 · 일반 설정 · 알림 설정) / 「Jira 관리자 설정」(시스템 · Jira 앱 · 스페이스 · 업무 항목 · Marketplace 앱) / 「Atlassian 관리자 설정」(사용자 관리 · 청구) | 톱니는 `/settings` 로 **바로 이동하는 링크**다(`TopBar.tsx:248-255`). 관리는 별도 아이콘 `AdminHubLink`(`:243`) | 한 진입점 안의 개인/관리 구획이 없다. 설명 문구가 붙은 메뉴도 없다 | CDP 판독 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-N-5 | 사이드바 항목 | 추천 · 최근 항목 · 별표 표시됨 · 앱 · 계획 · 스페이스 · 필터 · 대시보드 · (자산 · 팀) · 사이드바 사용자 지정. 최근 항목·별표는 **플라이아웃**이다(「오늘」 그룹 + 상대 시각, 보드·스페이스·업무 항목·대시보드·필터가 섞인다. 끝에 「… 모두 보기」) | 내 작업 · 이슈 · 대시보드 · 캘린더(`Sidebar.tsx:58-62`) · 즐겨찾기(:199) · 최근 항목(:241, **이슈만**) · 프로젝트 설정 내비(:253) · 프로젝트 트리(:255) | 「필터」 항목이 없다. 최근 항목이 이슈로 한정된다. 사이드바 사용자 지정(순서·숨김)이 없다 | ev_1eef6df87 · 플라이아웃은 CDP 판독 · 2026-10-28 / "You starred items appear at the top of each menu, followed by your recently visited items." · "To customize and reorder your sidebar, select Customize sidebar" — https://support.atlassian.com/jira-software-cloud/docs/navigate-to-your-work/ | P2 |
| JC-N-6 | 프로젝트 수평 탭 구성·순서 [TM] | 요약 · 백로그 · 보드 · 개발 · 타임라인 · 문서 · 양식 + 「탐색에 추가」. 숨은 보기는 릴리스 · **목록** · 목표 · 바로 가기 · 보고서 · 보관된 업무 항목 · 첨부 파일 · 캘린더이고, 대화에서 골라 탭으로 추가한다 | 요약 · 타임라인 · 보드 · 백로그 · 캘린더 · 대시보드 · 컴포넌트 · 이슈 · 버전 · 리포트 고정(`project-view-tabs.ts:88-157`). 승계: `docs/plans/2026-09-07-jira-parity-project-header-and-scoped-tabs.md` J5-1 · J5-8~10 | 순서가 다르다(Jira 는 백로그·보드가 타임라인 앞). 탭 추가·숨김을 사용자화할 수 없다 | ev_97a42f44b · ev_47519888f · 2026-10-28 / 승계 J5-1 문서 https://support.atlassian.com/jira-software-cloud/docs/manage-and-customize-the-project-navigation/ | P3 |
| JC-N-7 | 「목록」 탭 기본 존재 [TM] | 새 TM 스페이스(SCRUM)에는 목록 탭이 **없었다**. `/list` 는 「원하는 페이지를 찾을 수 없음」이었고, 「탐색에 추가」에서 「목록 — 팀의 업무 목록을 스프레드시트 형식의 표로 확인하세요.」를 골라 추가했다 | 「이슈」 탭이 항상 있다(`project-view-tabs.ts:137`) | 갭은 아니다. **승계 J5-12(「List … can't be removed」)와 실물이 어긋난다** — 「제거 불가」라도 새 스페이스에서 기본 노출되지는 않는다. J5-12 를 재검토해야 한다 | ev_97a42f44b · ev_47519888f · 2026-10-28 / 승계 J5-12 인용 "Note that the List and Apps tabs can't be removed" | P3 |
| JC-N-8 | `?` 단축키 — 전역 키 | 「전역 바로 가기」: `/` 빠른 검색 · Esc 서랍 닫기 · C 업무 항목 만들기 · ? · **G→I 업무 항목 찾기** · **G→P 스페이스로 이동** · `[` 사이드바 토글 · **H 도움말 패널** · Cmd+K 명령 팔레트. `g d` 는 **없다** | `?` · `c` · `/`(→`/search` 이동) · `g i`(내 이슈) · `g d`(대시보드) + Cmd/Ctrl+K(`shortcuts.ts:80-117`) | `g p` · `h` 가 없다. `g i` 의 목적지가 다르다(Jira 검색 화면, BTS 내 이슈). `/` 의 의미가 다르다(Jira 검색창 포커스, BTS 페이지 이동). **승계 F13 J6 「Jira 에 그런 단축키가 없다」는 실물과 어긋난다** — 실물에 「빠른 검색 /」가 있다 | ev_dae7c6f55 · 2026-10-28 / "Global shortcuts — shortcuts that can be used in any part of Jira." — https://support.atlassian.com/jira-software-cloud/docs/use-keyboard-shortcuts/ | P2 |
| JC-N-9 | `?` 단축키 — 이슈·보드·일괄 | 이슈 작업: Shift+C 하위 이슈 · Shift+K 기존 이슈 연결 · A · M · W · L · `.` 작업 메뉴 · I · D 상태 변경 · Q 작업 로그 · Shift+S 공유. 보드: N/P 열 · T 세부 정보 · E 에픽 패널 · V 버전 패널 · `-` 모든 스윔레인 · S→T 맨 위로 · S→B 맨 아래로. 일괄: Cmd+B 도구 모음으로 건너뛰기. 계획: Ctrl+Opt+W/M/Q/Y/X/T/R/A/L | BTS 컨텍스트 단축키 정의(`context-shortcuts.ts`), 팔레트 `.`(`command-palette/use-palette-search.ts:87`) | 이 화면 범위 밖이다(상세·보드 담당 에이전트와 교차 확인 권장). 목록만 놓고 보면 Cmd+B 일괄 툴바 점프가 없다 | ev_dae7c6f55 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-N-10 | 사이드바 접기 `[` | 단일 키 `[` 로 접힌다. 폭 320→0, 버튼 이름이 「사이드바 펼치기」로 바뀐다. 다시 `[` 를 누르면 320 으로 돌아온다. `?` 목록 문구는 「사이드바 토글 [」 | `[` 접기(`Sidebar.tsx:273`) | 동등하다. **승계 F10 문서의 「Jira Software = Ctrl+[」 기재는 실물(단일 `[`)과 다르다** → 문서를 정정하면 된다 | CDP 판독(폭) · ev_dae7c6f55 · 2026-10-28 / 승계 `docs/specs/2026-08-03-fr-ux-10-f10-context-shortcuts.md` `[` 행 | P3 |
| JC-N-11 | 명령 팔레트 Cmd+K | 다이얼로그 「작업 검색」 + 「/」 힌트(업무 항목, 스페이스 등 검색). 섹션: 빠른 작업(업무 항목 만들기 C · 현재 페이지에서 검색 F · 현재 페이지 URL 복사 · 알림 확인) · 사이트 탐색(나의 미해결/내가 보고한 업무 항목 · 모든 스페이스/보드/필터/대시보드/계획 보기) · Atlassian 관리자 · 개인 설정(테마 전환 · 개인/계정 설정) · 도움말(도움 받기 H · 키보드 바로 가기 Shift+/) · 피드백 제공 | Cmd+K / `.` 팔레트(`use-palette-search.ts:87`). 승계: `docs/specs/2026-08-04-fr-ux-12-f4-command-palette-search.md` J1–J6 | 섹션 구성 대조는 미실시다(승계 문서 범위) | ev_761a0978c · 2026-10-28 / 승계 F4 J2 문서 | P3 |
| JC-N-12 | 알림 벨 | 버튼 이름에 미읽음 수(「알림 1개」). 누르면 팝업: 「알림」 · 「읽지 않은 항목만 표시」 토글 · 「새 탭에서 알림 열기」 · 추가 작업 · 탭 「직접 / 관찰 중」 · 날짜 그룹 「오늘」 | `InboxBell`(`TopBar.tsx:228`) | BTS 쪽 내용은 대조하지 않았다 | CDP 판독 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-N-13 | 계정 메뉴 · 앱 스위처 | 아바타: 이름/이메일 · Profile · Account settings · Theme · 계정 전환 · 로그아웃. 앱 전환: 홈 · Jira · Assets · 팀 · 관리 · 추천(Confluence · Work requests) · Atlassian 앱 더 보기 | `AccountMenu`(`TopBar.tsx:260`). 앱 스위처는 없다 | 계정 메뉴 항목 대조는 미실시다. 앱 스위처는 BTS 범위 밖일 수 있다(Atlas Wiki 전환 경로는 확인 필요) | CDP 판독 · 2026-10-28 / 실물 관찰만 | P3 |
| JC-N-14 | 기본 착지 화면 | 로고/홈을 누르면 `/jira/for-you`(「maxi님 님, 환영합니다. 10월 28일 수요일입니다.」 · 스페이스 찾기 · 추천 항목)로 간다 | 로고를 누르면 `/dashboards` 로 간다(`TopBar.tsx` 주석 :60) | 개인 「추천」 랜딩이 없다(사이드바 「내 작업」이 일부 대응) | CDP 판독(H1) · 2026-10-28 / "For you: Get an overview of the things you care most about in Jira." — https://support.atlassian.com/jira-software-cloud/docs/navigate-to-your-work/ | P3 |

#### BTS 가 이미 동등하거나 앞선 것
- `[` 단일 키 사이드바 토글과 폭 조절(`Sidebar.tsx:273`, `SidebarResizeHandle`) — 동등하다(Jira 「패널 크기 조정」 slider).
- Cmd/Ctrl+K 명령 팔레트 — 동등하다(승계 F4).
- 상단바 상시 검색 입력 — 동등하다(승계 F13 J1).
- 스페이스(프로젝트) 제목행 + 수평 탭 구조 — 동등하다(승계 J5-8~10).
- `g d` 대시보드 이동은 BTS 고유다(Jira 에 없음).

#### 관찰 한계
- 아래 「공통 관찰 한계」 절을 함께 본다.
- 「사이드바 사용자 지정」 · 「스페이스」 플라이아웃 세부 · 알림 패널 본문 · 도움말 패널 본문은 열지 않았거나 일부만 봤다.
- 검색창에 실제 문자열을 입력했을 때의 결과 묶음은 다시 보지 않았다(승계 F13 J2 에 맡김).

### 공통 관찰 한계
- `capture_browser_scenario`(connect 모드)가 1807초 동안 응답하지 않아 중단됐다. 이후 조작은 내 탭 1개에 CDP 를 직접 붙인 스크립트(`c-scripts/cdp.mjs` — navigate·클릭·키·insertText)로 했다. 증거는 조작 직후 `inspect_web_page` 로 남겼다. 상태·우선순위·요약·기한 편집기, 행 ⋯, 추가 작업, 그룹화 목록, 톱니·계정·앱 전환·최근·별표·알림·도움말은 **CDP DOM 판독만** 있고 별도 evidence_id 가 없다.
- 탭이 백그라운드(`visibilityState=hidden`)일 때는 react-select 옵션 선택이 반영되지 않았다. `Page.bringToFront` 로 앞으로 가져온 뒤에 반영됐다. 다른 에이전트 화면 포커스에 잠깐 영향을 줬을 수 있다.
- 작업 막바지에 세션이 worktree 격리로 바뀌어 **Bash 가 모든 명령을 거부했다**. 그래서 ① BTS 실서비스(bts.maxihan.com) 대조를 하지 못했다 — BTS 열은 전부 코드(file:line) 기준이다. ② **내 탭 `3BD340E337ED3A133DD523D1F3B5344E` 를 닫지 못했다** — 부모가 `curl -s http://127.0.0.1:9222/json/close/3BD340E337ED3A133DD523D1F3B5344E` 로 닫아 줘야 한다. ③ SCRUM-8 우선순위를 원복하지 못했다(아래 변경 기록).
- 문서 404: `search-for-work-items-using-the-search-bar`, `edit-multiple-work-items`. 「열 구성」 · 모달/사이드바 전환 · 그룹화를 다룬 Cloud 문서는 찾지 못해 「실물 관찰만」으로 적었다.
- 전체 evidence_id: ev_97a42f44bbfeed8ecd1ff25f66872ee4267895eb8b471e64c7fedf971f580847 · ev_1eef6df874177f44c276bb0d12bbffcd39d458efcfbdbf80ee3596e138ee3865 · ev_96b86336bc9af84e22a26575402eb07033f5f669e3fc24d46b36bd6f1e774556 · ev_804836340f2c3f9389f9a971d1020d59ec8b97bee2cf4c946ec9ac8932fa4dcc · ev_5b744694223d416e643b4fa9211e33c3f48b2ecfc23e291588154760c483790d · ev_b94a24937c577b9647fefce0f774c1764f9fea7d0b4d6fc64b35df01efde7431 · ev_814aff0b3fe3c94e360e9047b380bfc750bf617676e661af429c5d84a84dace · ev_10d8c9a0d4d51450d456c9f9febfc1afc7c090f4ab6d63e5492c2a88292e1317 · ev_f7dc1307152c3300ec8cf68db7dd42542d97100f8bb99d08238533929f300b5f · ev_2716d615d8c17d4fb3f936eb4a7da3f268c20330ad3ab47fac44b9b8ee59c4c0 · ev_47519888f44ecc20cc8cd08918d2508e414581532248e9a196afe7e65ff89224 · ev_e31a9b3a083e71082a3fb87d55b392f1ac3550407d42deb1826eefe6064799a3 · ev_c8c3cf143aac8c75e89a722c00bcec7e26ce363caa0eb7b46da94b7e8e9143f2 · ev_b06385b1479cca057de584ad1893b58b9286279ac5530c887ca56481c715e544 · ev_dae7c6f5560f8074f9cdf8ea637afd2bbdb21a0403bf01f8cf77710dd10b8d1e · ev_761a0978c0e8a81ce4dbf2be34f40ffb61bd2e43f13d8e1f6d1fd5c8e2e05be0 · ev_dfcf2fad44e094a16a33bae8e13aa4171b55d3944f129f15033dc7f450541523
  - (ev_761a0978c… 는 앞 12자만 확실하다. 나머지 자리는 도구 출력 파일 `mcp-rea-inspect_web_page-1793153933378.txt` 의 `.evidence_id` 를 정본으로 본다.)

### Jira 변경 기록
| 무엇 | URL | 원래 값 | 현재 | 되돌림 |
|---|---|---|---|---|
| SCRUM 스페이스 내비게이션에 「목록」 탭 추가(탐색에 추가) | https://gwalho.atlassian.net/jira/software/projects/SCRUM/list/VZJ44HA | 목록 탭 없음 | 목록 탭 있음(view id VZJ44HA) | 안 함(다른 에이전트도 쓸 수 있어 유지) |
| SCRUM-8 「[REA] 인라인 생성 1」 우선순위를 목록 셀에서 인라인 변경 | https://gwalho.atlassian.net/browse/SCRUM-8 | Medium | Low | **못 함**(Bash 차단). [REA] 데이터라 그대로 둬도 무방 |
| 저장 필터 「[REA] 목록 관찰 필터 (C)」 생성(JQL `project=SCRUM ORDER BY key DESC`, 조회자·편집기 비공개, 저장 시 자동 별표) | https://gwalho.atlassian.net/issues?filter=10001 | 없음 | 있음(filter id 10001) | 안 함 |
| 업무 항목 보기 방식 선호: 모달 → 「사이드바로 전환」 → 「Expand」로 모달 복귀 | https://gwalho.atlassian.net/issues?jql=project%3DSCRUM&selectedIssue=SCRUM-1 | 모달 | 모달 | 복원함 |

### BTS 변경 기록
- 없음(BTS 실서비스에 접속하지 못했다).

### 부록: 보드 추가 관찰
- 없음. 보드·백로그 조사 지시는 정정 전에 이미 범위 밖으로 판단해 손대지 않았다.

---

## D-secondary — 프로젝트 설정(워크플로 · Automation · 기타) · 알림 · 타임라인/캘린더 (+대시보드 일부 관찰)

> 조회일 2026-10-28 · Jira Cloud `gwalho.atlassian.net` 프로젝트 SCRUM("QA Hub", team-managed) · UI 언어 한국어
> 범위 조정 반영: 대시보드(JD)·업무 유형(Issue types)은 다른 에이전트로 넘어갔다. 이 파일의 JD 절은 넘기기 전 관찰한 구조 메모만 남긴다.
> REA evidence_id 는 앞 12자(+`ev_`). 모든 BTS 경로는 `apps/web/src/` 기준.

---

### 1. 워크플로우 (접두어 JW) [TM]

관찰 URL · 조회일 2026-10-28
- `.../projects/SCRUM/settings/issuetypes/10004` → 「워크플로 편집」 → `.../settings/issuetypes/10004/workflow` — ev_38079814401e(편집기) · ev_c86c7527ad86(스크린샷) · ev_fb652f5696f6(저장 뒤)
- 상태 추가 · 전환 추가 · 규칙 추가 모달과 상태/전환 사이드 패널은 CDP 로 클릭하며 텍스트를 읽었다(같은 탭).

#### Jira Cloud 관찰 요약
- **진입** [TM]: 스페이스 설정 › 업무 유형(사이드 내비가 Epic/Story/Task/Subtask/업무 유형 추가로 펼쳐짐) › 유형 페이지 상단 「워크플로 편집」. 편집기는 **전체 화면**으로 바뀐다(설정 내비가 사라짐).
- **헤더**: 왼쪽 「다음에 대한 워크플로: [작업·에픽·Subtask·스토리 아이콘]」(기본 TM 은 한 워크플로를 4유형이 공유), 가운데 툴바 「상태 추가 · 전환 추가 · 규칙 추가 · 에이전트 추가」, 오른쪽 「워크플로 업데이트」(변경 전 disabled) · 「닫기」. 변경이 생기면 「닫기」가 「변경 사항 취소」로 바뀐다.
- **캔버스**: 「다이어그램 | 텍스트」 토글, 「전환 레이블 표시」 체크박스, 도움말(?), 오른쪽 접이식 사이드 패널(선택 전에는 가이드 링크), 오른쪽 아래 미니맵 + 줌 슬라이더. 키보드 안내가 스크린리더 텍스트로 있다: 「이동 모드 m」 · 「선택 후 delete 로 삭제」 · 「방향키로 이동」 · 「+/- 로 확대/축소」.
- **노드 구성(기본)**: 「시작」 → Create → 해야 할 일 · 진행 중 · 검토 중 · 완료. 상태마다 **「어느 것이든」 알약**이 붙어 Any-status 전환을 그 상태 바로 위에 그린다. 색은 범주별 — 할 일 회색, 진행 중·검토 중 파랑, 완료 초록.
- **텍스트 보기**: 표 「상태(ID) | 전환(ID)」, 상태별 그룹 · 「접기」/「모두 접기」. 전환 행에 출발 → 도착(「모든 상태 → 진행 중」).
- **상태 사이드 패널**: 이름 인라인 편집 · 범주 편집(「처리 중」) · 「팀에서 관리하는 스페이스에서만 사용할 수 있음 QA Hub」 · 전환 수신 n/발신 n + 「수신/발신 전환 만들기」 · 속성 열기 · 제거 · 바꾸기. 새 상태에 전환이 없으면 「경고 보기(1개)」 + 「이 상태에는 전환이 필요합니다」.
- **전환 사이드 패널**: 이름 · 경로(시작 상태 → 전환할 상태, 패널 안에서 바로 바꿈) · 규칙 3묶음(전환 제한 / 세부 정보 확인 / 작업 수행 — 각각 개수 배지 + 「… 규칙 추가」) · 「모두 보기」 · 속성 열기 · 삭제.
- **저장**: 「워크플로 업데이트」 → 모달 「선택한 업무 유형의 워크플로 저장」(업무 유형 다중 선택, 기본 4유형 모두, 「추가 워크플로를 만들면 보드에서 열을 직접 고칠 수 없다」 경고) → 「저장하고 계속」 → 토스트 「워크플로 업데이트 — 최근 변경 사항으로 영향을 받은 업무 항목을 업데이트하려면 시간이 다소 소요됩니다」. 상태를 지우지 않아서 상태 이관 모달은 나오지 않았다. URL 에 `?selectedTransition=2` 가 붙어 선택 상태를 기억한다.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JW-1 | 편집기 진입 경로 [TM] | 설정 › 업무 유형 › (유형) › 「워크플로 편집」 | 설정 › 이슈 › 워크플로우(목록 표: 이름·키·소유·상태·전환) › 편집 `routes/projects.$projectKey.settings.workflows.tsx:122-143` · 메뉴 `components/project/project-shell-mode.ts:84` | 진입점이 「유형」이 아니라 「워크플로 목록」이다. 결과는 같다 | 승계: docs/specs/2026-09-08-project-owned-workflows.md 「편집 진입 경로」 행 · ev_38079814401e | P3 |
| JW-2 | 워크플로 ↔ 업무 유형 연결 [TM] | 편집기 헤더에 「다음에 대한 워크플로: 4유형 아이콘」, 저장 모달에서 적용할 유형을 고른다 | 별도 화면 「워크플로우 스킴」 `routes/projects.$projectKey.settings.workflow-scheme.tsx:157-183`(스킴 선택) | Jira TM 은 스킴 개념 없이 **저장 순간 유형을 고른다**. BTS 는 스킴 화면을 따로 거친다 | ev_fb652f5696f6 · 저장 모달 원문 「이 워크플로를 복사할 업무 유형을 선택하세요」 | P2 |
| JW-3 | 편집기 레이아웃 | 캔버스 하나 + 오른쪽 사이드 패널(선택하면 상세) + 다이어그램/텍스트 토글 | 탭 3개 「상태 · 전환 · 다이어그램」 `components/workflow/editor/WorkflowEditorTabs.tsx:87-90`, 상세 편집은 다이얼로그 | 선택 → 옆 패널 편집 흐름이 없고 다이얼로그다 | 승계: docs/plans/2026-09-03-xyflow-fr-wf-07-d8-pr-9.md J2(편차 X2) · J7(편차 X1) · ev_38079814401e | P2 |
| JW-4 | Any-status 전환 표시 | 상태마다 「어느 것이든」 알약이 붙음 | 캔버스 구석 별도 패널 「모든 상태에서」 `components/workflow/editor/WorkflowEditorCanvas.tsx:242-245` | 어느 상태가 전역 진입을 받는지 노드 옆에서 안 보인다 | 승계: xyflow 계획 J6 · ev_c86c7527ad86 | P3 |
| JW-5 | 상태 추가 | 툴바 → 모달: 「상태 이름」 콤보(검색 또는 「'X' 상태 만들기」) → 새 이름이면 「상태 범주」(할 일/진행 중/완료, 기본 할 일) · 「모든 상태에서 전환 허용」 체크 · 「이 스페이스에서만 사용」 안내 · 취소/추가 | 카탈로그 선택 `StatusPickerDialog.tsx` + 새 상태 폼 `StatusCreateForm.tsx`(키 입력 필수, 「시스템 관리자만 만들 수 있습니다」 `i18n/workflow-editor-labels.ts:194`) | ① 프로젝트 관리자가 새 상태를 못 만든다 ② 키를 따로 입력해야 한다 ③ 추가하면서 Any-status 전환을 함께 거는 체크가 없다 | 실물 관찰(모달 텍스트) · ev_fb652f5696f6 | P2 |
| JW-6 | 전환 추가 | 모달: 시작 상태 **다중 선택**(첫 항목 「모든 상태」) · 전환할 상태 · 이름 + 이름 팁 | `TransitionFormDialog.tsx:156,237-243` 시작 상태 단일 선택 + 종류 라디오(특정 상태/모든 상태) + 전환 화면 필드 | 여러 출발 상태를 한 번에 못 고른다. 전환 화면(필드 입력) 설정은 BTS 가 앞선다 | 실물 관찰 · 승계: xyflow 계획 J3·J4(드래그 생성) | P3 |
| JW-7 | 전환 규칙 카탈로그 | 「규칙 추가」 모달 필터 「모든 규칙/전환 제한/세부 정보 확인/작업 수행」. **전환 제한 5**: 특정 상태를 통과한 경우로 제한 · 이동 가능 사용자 제한 · 이전에 상태를 바꾼 사용자 제한 · 필드가 특정 값일 때 · 하위 작업 상태에 따라. **세부 정보 확인 4**: 권한 확인 · 상위 항목 상태 확인 · 특정 상태를 거쳤는지 · 필드 유효성 검사(단일 값/비어 있지 않음/다른 날짜와 비교/정규 표현식, 오류 메시지, 컨텍스트 무시 옵션). **작업 수행 3**: 필드 업데이트 · 할당 · 필드 값 복사 | validator 3종 `api/validators.ts:102-113`(RequiredField · permission-check · not-status-category, phase AVAILABILITY/EXECUTION) + post-action CALL_WEBHOOK 1종 `i18n/post-action-labels.ts:4` | 12종 대 4종. 사용자 제한 · 필드 값 조건 · 하위 작업 조건 · 할당/필드 업데이트/복사 액션이 없다 | ev_fb652f5696f6(전환 패널 규칙 3묶음) · 문서 「This page describes each of the workflow rules you can add to your team-managed spaces.」 https://support.atlassian.com/jira-software-cloud/docs/available-workflow-rules-in-team-managed-projects/ · 승계: docs/plans/2026-08-25-backend-workflow-transition-rule-crud.md 「Rules 4그룹」 행 | **P1** |
| JW-8 | 규칙 편집 위치 · 반영 시점 | 편집기 안 전환 패널에서 추가 → 다른 변경과 함께 「워크플로 업데이트」로 저장 | 편집기 밖 별도 화면 링크 「전환 규칙 편집」(`WorkflowEditorPage.tsx:135`), 「발행 없이 바로 반영 · 초안이 열려 있으면 못 고침」 `i18n/workflow-editor-labels.ts:125` | 상태·전환 변경과 규칙 변경을 한 번에 저장할 수 없다. 초안이 있으면 규칙 편집이 막힌다 | ev_fb652f5696f6 | P2 |
| JW-9 | 저장 · 되돌리기 흐름 | 변경 즉시 「워크플로 업데이트」 활성 + 「닫기」→「변경 사항 취소」. 저장 = 유형 선택 모달 1번 → 비동기 반영 토스트 | 서버 초안 자동 저장(DraftStatusBar: 편집 시작 전/저장하지 않은 변경/저장됨) → 「발행」 다이얼로그(사라지는 상태 · 건수) → 이관 마법사 · 「초안 폐기」 · 「기본값으로 되돌리기」 `i18n/workflow-publish-labels.ts:21-190` | BTS 는 저장과 발행이 두 단계다. 초안이 서버에 남고 충돌 배너까지 있어 Jira 보다 안전하다 | 승계: docs/plans/2026-08-26-workflow-draft-publish.md J1·J2·J6 · ev_fb652f5696f6(토스트) | P2 |
| JW-10 | 상태 이관(상태 삭제 시) | 이번 저장은 삭제가 없어 미관찰 | MigrationWizard `components/workflow/editor/MigrationWizard.tsx` | — | 승계: docs/plans/2026-08-31-workflow-status-migration-wiring.md J1·J4·J7·J8 · docs/plans/2026-09-02-fr-wf-07-d6b-pr-10b.md J9(기각 X1) | — |
| JW-11 | 상태 사이드 패널 | 이름·범주 인라인 편집, 수신/발신 전환 수 + 거기서 전환 만들기, 속성, 제거/바꾸기 | 상태 탭 목록(추가·제거·끌어서 순서) `StatusListPanel.tsx` | 상태를 고른 채로 그 상태의 들어오고 나가는 전환을 보거나 만들 수 없다 | 실물 관찰(패널 텍스트) | P3 |
| JW-12 | 구조 경고 | 「경고 보기(1개)」 · 「이 상태에는 전환이 필요합니다」 | 전환 없는 상태 경고 없음(editor 디렉터리 grep 「경고·도달」 결과 없음) | 고아 상태를 저장 전에 알려 주지 않는다 | 실물 관찰 | P3 |
| JW-13 | 캔버스 탐색 | 미니맵 + 줌 슬라이더, 키보드 m/delete/방향키/± | xyflow `Controls`(확대·축소·전체 보기) `WorkflowEditorCanvas.tsx:228-236`, 미니맵·키보드 이동 모드 없음 | 미니맵·키보드 조작 부재 | ev_c86c7527ad86 | P3 |

---

### 2. Automation (접두어 JAU)

관찰 URL · 조회일 2026-10-28
- `.../settings/automation#/tab/rule-library` 템플릿 ev_152e2ac90738 · `#/tab/rules` 빈 목록 ev_014be0e5fa87 · `#/tab/audit-log` ev_20c5718fea7a · `#/tab/usage` ev_8dbba574b967
- 빌더 `#/rule/new` 트리거 카탈로그 ev_298abd1235a7 · 단계(브랜치) 카탈로그 ev_d5ee25d22d97
- 실제로 만든 규칙 `#/rule/01a18046-22ea-71e7-a288-793672bce3d2` → 규칙 감사 로그 ev_b774e97e7fe1 · 규칙이 있는 목록 ev_435b77fd9028

#### Jira Cloud 관찰 요약
- **화면 머리**: 「Automation」 + 「전역 관리」 링크 + 「흐름 만들기」(드롭다운: Rovo로 만들기 / 처음부터 만들기 / 템플릿으로 만들기) + 「추가 작업」. 탭 4개 **흐름 · 감사 로그 · 템플릿 · 사용량**. 규칙이 0개면 처음 들어올 때 템플릿 탭으로 간다. Jira 는 규칙을 「흐름(flow)」이라고 부른다.
- **흐름 목록**: 하위 탭 「이 스페이스 | Studio」, 필터 「흐름 필터링」 검색 · 범위(스페이스 흐름) · 소유자 · 작업 · 트리거 · Type · 레이블. 컬럼 **이름 · 레이블(「레이블 추가」) · 소유자 · 범위 · 업데이트됨 · Type(Flow) · 사용(토글, 확인 없이 즉시) · 작업(⋯)**. 기본 정렬은 이름 오름차순, 20개씩. 빈 상태는 「아직 만든 흐름이 없습니다」 + 「라이브러리 찾아보기」 · 「흐름 만들기」.
- **빌더(전체 화면)**: 왼쪽 세로 캔버스(트리거 → 「단계 추가」) + 오른쪽 설정 패널 + 줌(100%, ±). 제목 「제목 없는 흐름」을 클릭하면 인라인으로 고친다. 오른쪽 위 「Save and enable」 + ▾(「Save without enabling」). 처음 열면 둘러보기(Add a trigger/condition/branch/action) 투어가 뜬다. 저장하지 않고 나가면 확인창 「변경 사항 취소 — 흐름을 취소하면 복구할 수 없습니다」. 저장하지 않은 흐름은 브라우저에 남아 다음 진입 때 「저장되지 않은 흐름을 찾음 — 변경 사항 취소 / 계속 편집」으로 물어본다.
- **트리거 카탈로그**(앱별로 묶고 검색): Automation 3(서비스 한도를 초과 · 수신 웹후크 · 예약됨) · Compass 2 · Confluence 1 · DevOps 14(디자인 연결 · 배포 상태 변경/성공/실패 · 브랜치 · 빌드 상태/성공/실패 · 연결된 디자인 변경/업데이트 · 커밋 · PR 거부/생성/병합) · Focus 4 · Guard Detect 1 · **Jira 24**(버전 삭제/릴리스 취소/릴리스/생성/업데이트 · 스프린트 시작/완료/생성 · 댓글 편집 · 업무 항목 만들어짐/삭제됨/업데이트됨/연결됨/연결 삭제됨/**전환됨**/**할당됨** · 댓글 달림 · **수동 트리거** · 댓글 삭제 · 이동됨 · 여러 업무 항목 이벤트 · 작업 기록됨 · 취약성 발견 · **필드 값이 변경됨**) · JSM 4 · Loom 3 · Projects 2.
- **단계 유형**: Action · Condition · Branch 셋 중 고른다(「1/65개 추가함」 한도 표시).
- **액션 카탈로그**(앱 그룹): Automation 6(동적 조회 표 · 로그 · 변수 · 사용자 지정 이메일 · 웹 요청 · 조회 표) · 코딩 에이전트 4 · Bitbucket 4 · Confluence 2 · Focus 7 · **Jira 26**(관찰자 관리 · 댓글 지우기/편집 · 버전 릴리스/릴리스 취소/만들기 · 스프린트 만들기/Start sprint · 데이터 다시 가져오기 · 업무 항목 만들기/복제/삭제/연결/연결 삭제/조회/편집/할당/보드로 이동/댓글/웹 링크 · 엔터티 속성 · 작업 로그 · **전환 업무 항목** · 첨부 삭제 · 취약성 연결 · 하위 작업 만들기) · JSM 9 · 외부 연동(AWS 99+ · Asana 4 · Azure DevOps 23 · Confluent 8 · Datadog 7 · Docker 20 · GitHub 9 · Gitlab 6 · Google 94 · Jenkins 12 · Microsoft 81 · PagerDuty 12 · Salesforce 6 · Slack 2 · Splunk 9 · Twilio 1 · Zendesk 4 · 다른 앱 1).
- **조건 카탈로그**: {{smart values}} 조건 · 관련 업무 항목 조건 · 사용자 조건 · 업무 항목 첨부 파일 · 업무 항목 필드 조건 · 디자인 연결 여부 · 요청 필드 조건 · 포커스 영역 필드 조건 · AQL 조건 · IF 또는 ELSE · JQL 조건. 트리거 패널 안에도 「조건 추가」가 있다.
- **브랜치 카탈로그**: 고급 브랜칭 · 관련 업무 항목에 대한 흐름 분기 · AQL 브랜치.
- **댓글 액션 폼**: 「댓글*」 textarea + 「스마트 값 패널 표시」 + 「한 번만 추가해 중복 방지」 + 「댓글 표시 여부」 + Rovo 제안 카드.
- **감사 로그**(프로젝트·규칙 두 곳): 필터 흐름 검색 · 지난 30일 · 감사 로그 ID · 작업 · 트리거 · 필터 초기화. 컬럼 날짜 및 시간 · 감사 로그 ID(링크 복사) · 흐름 · 상태(성공 / **구성 변경**) · 총 시간. 행을 펼치면 단계별 시각(「업무 항목 만들어짐 11:10:46」 → 「업무 항목에 대한 댓글 11:10:47」, 각각 펼치기)이 나온다. 규칙 화면 상단은 「흐름 | Audit log」 탭 + 「사용 ENABLED」 토글 + Save.
- **사용량**: 마지막 업데이트 시각 · 구독 업그레이드 · 날짜 범위(이번 달) · 새로 고침 · 표 「흐름 · 흐름 실행 · 자동화 단계 · Rovo 크레딧 · 소유자 · 사용으로 설정됨」.
- **실행 확인**: `[REA]` 규칙을 켜고 SCRUM-6 을 만들자 약 1초 뒤 작성자 「Automation for Jira」로 댓글이 달렸다. 감사 로그 성공 2.29초.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JAU-1 | 화면 구성 | 탭 4개(흐름/감사 로그/템플릿/사용량) + 전역 관리 + 만들기 드롭다운 3갈래 | 한 화면에 규칙 목록 · Git 웹훅 섹션 · YAML 가져오기/내보내기 `routes/projects.$projectKey.settings.automation.tsx:145-178` | 감사 로그 · 템플릿 · 사용량 탭이 없다 | ev_014be0e5fa87 · ev_152e2ac90738 | P2 |
| JAU-2 | 목록 컬럼 · 필터 | 이름·레이블·소유자·범위·업데이트됨·Type·사용 토글·⋯, 검색과 필터 6종, 정렬, 20개 페이지 | 행 = 이름 · 트리거 배지 · 활성 배지 · (SCHEDULED) 다음 실행 · 액션 배지 + 버튼 활성/편집/이력/삭제 `components/automation/AutomationRuleList.tsx:198-290` | 검색·필터·레이블·소유자·최종 수정 시각이 없다. 대신 액션 요약 배지와 다음 실행 시각은 BTS 가 앞선다 | ev_435b77fd9028 | P2 |
| JAU-3 | 템플릿 라이브러리 | 검색 · 범주 · 트리거 필터, 범주 12개(Rovo AI 에이전트·코딩 에이전트·Loom·전략·완료한 작업 관리·작업 체계화·이슈 전환·생산성·릴리스 관리·개발자 도구·디자인·보안), 예: 「모든 하위 이슈가 완료되면 → 상위 이슈 종료」 | 없음 | 템플릿으로 시작하는 길이 없다 | ev_152e2ac90738 | P2 |
| JAU-4 | 규칙 편집 형태 | 전체 화면 세로 캔버스 + 오른쪽 패널, 제목 인라인 편집, 「Save and enable」/「Save without enabling」, 나갈 때 확인, 로컬 미저장본 복구 | 모달 폼 한 장: 트리거 설정 · 액션 목록 · 조건 빌더 · 실행 주체 `components/automation/AutomationRuleFormDialog.tsx:685-723` | 단계 순서와 분기를 눈으로 따라갈 수 없다. 미저장본 복구가 없다 | ev_298abd1235a7 · ev_d5ee25d22d97 | P2 |
| JAU-5 | 트리거 카탈로그 | Jira 그룹만 24종 + 검색 + 앱 그룹. 전환됨 · 할당됨 · 필드 값 변경됨 · 수동 트리거 · 스프린트/버전 이벤트 포함 | 6종 ISSUE_CREATED · ISSUE_UPDATED · ISSUE_COMMENTED · SCHEDULED · WEBHOOK · PR_MERGED `api/automation-rules.types.ts:13-20` | **상태 전환 · 할당 · 수동 실행 · 스프린트/버전 트리거가 없다** | ev_298abd1235a7 · 실물 관찰(카탈로그 전수) | **P1** |
| JAU-6 | 액션 카탈로그 | Jira 그룹만 26종(전환 · 업무 항목 만들기/복제/하위 작업 · 연결 · 관찰자 · 편집 · 할당 · 댓글 …) + Automation 6(이메일 · 웹 요청 · 변수 · 조회 표 …) | 5종 SET_FIELD · ASSIGN · ADD_COMMENT · CALL_WEBHOOK · SET_FIX_VERSIONS `api/automation-rules.types.ts:25` | **이슈 전환 · 생성/하위 작업 · 연결 · 관찰자 · 이메일 액션이 없다** | ev_d5ee25d22d97 이전 단계 텍스트(액션 목록 전수) | **P1** |
| JAU-7 | 조건 | 단계로 넣는 조건 11종(JQL · 사용자 · 관련 업무 항목 · IF/ELSE · smart value …) + 트리거 안 조건 | 필드 비교(EQUALS … EMPTY · EXISTS) AND/OR/NOT 트리 1개 `api/automation-rules.types.ts:449-457` · `components/automation/ConditionBuilder.tsx` | JQL · 사용자 · 관련 이슈 조건과 IF/ELSE 가 없다. 조건을 액션 사이에 둘 수 없다 | 실물 관찰(조건 카탈로그 전수) | P2 |
| JAU-8 | 브랜치 | 고급 브랜칭 · 관련 업무 항목 분기 · AQL 브랜치 | 없음 | 하위·연결 이슈에 액션을 돌릴 수 없다 | ev_d5ee25d22d97 | P2 |
| JAU-9 | 스마트 값 | 댓글 폼에 「스마트 값 패널 표시」, `{{issue.key}}` 가 링크 카드로 렌더됨 | 힌트 문구 「{{ issue.key }} 등 템플릿 변수」 `components/automation/ActionConfigEditor.tsx:35` | 변수 목록 패널이 없다(문자열 치환은 있음) | 실물 관찰(SCRUM-6 댓글 본문 inlineCard) | P3 |
| JAU-10 | 감사 로그 | 프로젝트 탭 + 규칙 탭 두 곳, 필터 5종, 상태 「성공/구성 변경」, 행 펼침 = 단계별 시각 | 규칙별 「실행 이력」 다이얼로그(이슈 키 필터 · 트레이스 행 · **재실행**) `components/automation/RuleExecutionHistoryDialog.tsx:25-119` | 프로젝트 전체 감사 로그와 「구성 변경」 기록이 없다. 재실행은 BTS 가 앞선다 | ev_b774e97e7fe1 · 문서 「Each time a flow is triggered, it saves a log of data that admins can audit to see when the flow was triggered」 「Automation audit logs store activity from the past 90 days.」 https://support.atlassian.com/cloud-automation/docs/what-is-the-automation-audit-log/ | P2 |
| JAU-11 | 사용량 | 이번 달 실행 수 · 단계 수 · 소유자 · 활성 여부 표 | 없음 | 한도 개념이 없으니 필요성이 낮다 | ev_8dbba574b967 | P3 |
| JAU-12 | 실행 주체 표시 | 결과 댓글 작성자 = 「Automation for Jira」 봇 | 실행 주체(actor) = 고른 멤버, 비우면 만든 사람 `AutomationRuleFormDialog.tsx:65-66` | 자동 작업이 사람 이름으로 남아 사람의 작업과 구분되지 않는다 | 실물 관찰(SCRUM-6 댓글 author) | P3 |

---

### 3. 나머지 프로젝트 설정 (접두어 JS) [TM]

관찰 URL · 조회일 2026-10-28
- `.../settings/details` ev_e9e23d464bab · `/access` ev_d564886e1d44 · `/features` ev_a8b00752b995 · `/fields` ev_c5ae57a143e7 · `/notifications` ev_e2cd0814a6b8

#### Jira Cloud 관찰 요약
- **설정 사이드 내비**(실물 순서): 세부 사항 · 액세스 · 알림(펼침: 설정 · 스페이스 이메일 감사) · 자동화 · 필드 · 업무 유형(펼침: 유형별 + 「업무 유형 추가」) · 기능 · 사용자 지정 필터 · 도구 체인 · 앱. 맨 위에 「이전 페이지로 돌아가기」. **워크플로 · 권한 · 화면 메뉴는 따로 없다**(워크플로는 업무 유형 아래).
- **세부 사항**: 아이콘 변경 · 이름* · 스페이스 키* · 범주 · 스페이스 소유자 · 기본 담당자 · 저장 · 「더 많은 옵션」(⋯).
- **액세스**: 「이 스페이스에 2개의 역할이 있습니다」 · 탭 「현재 사용자 / 액세스 요청 0」 · 「사용자 추가」 · 「역할 검색」 · 「역할 선택」 필터 · 표 이름/이메일/역할(드롭다운 「관리자」)/작업(Remove).
- **기능**: Planning — 스프린트(「백로그가 필요합니다」) · 추정(스토리 포인트 / 시간 라디오). More items — Jira에서 스탠드업. 모두 토글. 백로그·타임라인 토글은 여기에 없다(탭 「+」 메뉴로 옮겨 갔다).
- **필드**: 「필드 추가」 · 검색 · 「필드 범주」 · 표 이름/필드 유형/사용 가능한 위치/설명/작업(⋯), 시스템 필드에 「잠금」.
- **알림**: 「알림 추가」 · 발신 주소 `jira@gwalho.atlassian.net` 「편집」 · 표 **이벤트 × 받는 사람 × 작업** 11행(업무 항목을 만듦/편집됨/할당됨/삭제됨/이동됨 · 다른 사용자가 댓글 추가 · 댓글 편집됨 · 작업이 로그됨 · 업무 로그 편집/삭제 · 워크플로 전환으로 업데이트됨), 기본 받는 사람 「보고자, 지켜보는 모든 사람, 현재 담당자」 · 「Slack에 연결」 CTA. 안내 문구 「이 페이지의 설정은 사용자의 개인 알림 기본 설정보다 우선합니다」.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JS-1 | 설정 진입 · 내비 | 사이드바 스페이스 ⋯ → 스페이스 설정, 설정 전용 사이드 내비 10항목 | 그룹 4개 「일반(상세정보·프로젝트 리드) / 이슈(워크플로우·워크플로우 스킴·이슈 템플릿·커스텀 필드·필드 권한) / 액세스(멤버) / 연동(자동화·Slack 채널·가져오기)」 `components/project/project-shell-mode.ts:73-114` · 렌더 `components/project/ProjectSettingsNav.tsx:150-214` | 진입·형태는 같다. 알림 · 기능 · 업무 유형 · 사용자 지정 필터 · 도구 체인 항목이 없다. 라우트가 있는 components · versions 는 메뉴에 없다(`router.ts:559,617`) | 승계: docs/plans/2026-09-08-project-nav-subapp-icon.md JS-1 · JS-2 · ev_e9e23d464bab | P2 |
| JS-2 | 세부 사항 | 아이콘 · 이름 · 키 · 범주 · 소유자 · 기본 담당자를 한 화면에서 저장 | 이름 하나 + 위험 구역 `routes/projects.$projectKey.settings.details.tsx:200-242`, 리드는 별도 화면 `projects.$projectKey.settings.project-lead.tsx:104-114` | 아이콘 · 범주 · 기본 담당자 · 키 표시가 없다. 리드가 다른 페이지에 있다 | 승계: 같은 계획 JI-1~JI-3 · ev_e9e23d464bab | P2 |
| JS-3 | 액세스 · 역할 [TM] | 탭 「현재 사용자 / 액세스 요청」, 역할 검색 · 역할 필터, 행 안 역할 드롭다운 · Remove | 카드 목록 + 「멤버 추가」 다이얼로그 + 행별 역할 변경/제거, 역할 2종 PROJECT_ADMIN · MEMBER `components/admin/MemberList.tsx:56,108` · `api/project-members.types.ts:13` | 액세스 요청 처리 · 검색 · 필터 · 이메일 열이 없다 | ev_d564886e1d44 | P3 |
| JS-4 | 기능 토글 [TM] | 스프린트 · 추정(포인트/시간) · 스탠드업 켜고 끄기 | 해당 화면 없음 | 프로젝트별로 스프린트·추정 방식을 끌 수 없다 | ev_a8b00752b995 | P2 |
| JS-5 | 프로젝트 알림 규칙 [TM] | 이벤트 × 받는 사람 표 + 알림 추가 + 발신 주소 편집. 개인 설정보다 우선 | 프로젝트 단위 알림 설정 없음(`routes/` 에 해당 라우트 없음). 개인 매트릭스만 `components/settings/NotificationSubscriptionMatrix.tsx:145-180` | 프로젝트 관리자가 누가 어떤 이벤트를 받는지 정할 수 없다 | ev_e2cd0814a6b8 · 원문 「이 페이지의 설정은 사용자의 개인 알림 기본 설정보다 우선합니다」 | P2 |
| JS-6 | 필드 목록 | 이름·유형·사용 위치·설명·작업, 검색·범주 필터, 잠금 표시 | 커스텀 필드 화면 `routes/projects.$projectKey.settings.custom-fields.tsx` + 필드 권한 화면 | 시스템 필드와 커스텀 필드를 한 표에서 보지 못한다(세부 비교는 업무 유형 담당 에이전트와 겹친다) | ev_c5ae57a143e7 | P3 |
| JS-7 | 사용자 지정 필터 · 도구 체인 · 앱 | 내비 항목으로 존재 | 없음 | 미세 | ev_e9e23d464bab | P3 |

---

### 4. 알림 (접두어 JN)

관찰 URL · 조회일 2026-10-28
- 상단바 벨(「알림 3개」) 패널 ev_dfe150314300 · 개인 설정 `https://gwalho.atlassian.net/jira/settings/personal/notifications` ev_7d25ce8009ae

#### Jira Cloud 관찰 요약
- 벨을 누르면 **그 자리에 팝오버 패널**이 열린다. 머리: 「알림」 · 「읽지 않은 항목만 표시」 토글 · 「새 탭에서 알림 열기」 · 「추가 작업」(모두 읽음으로 표시 / 피드백 보내기). 탭 **직접 · 관찰 중**(기본 직접). 「오늘」 같은 날짜 그룹.
- 항목: 「Automation for Jira 님이 작업에 댓글을 추가했습니다 · 11초 전」 · 이슈 요약 · 「SCRUM-8 • 해야 할 일」 · 댓글 본문 · 반응 이모지 4개 + 더보기 · 「회신」 · 「스레드 보기」 · hover 「읽음으로 표시」.
- 개인 설정 › 이메일 및 알림: 이메일 기본 설정(담당자/보고자/내가 바꾼 경우 체크) · 알림 이메일 묶기(3분마다) · 형식 HTML/텍스트 · 「스페이스 알림 추가」(스페이스별 사용자 지정) · 기본 알림 표(제품 내 / 이메일 열 × 할당 · 멘션 · 변경 · 댓글 · 업무 로그 · 기타 이벤트 · 스페이스 액세스 요청 · 에이전트 알림). 사이드에 「다이제스트」 메뉴.

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JN-1 | 벨 클릭 | 현재 화면 위 팝오버 패널, Esc 로 닫힘 | `/inbox` 로 페이지 이동 링크 `components/inbox/InboxBell.tsx:20-35` | 알림 하나 보려고 하던 화면을 떠나야 한다 | ev_dfe150314300 | **P1** |
| JN-2 | 탭 · 필터 | 직접 / 관찰 중 + 「읽지 않은 항목만」 토글 | 전체 / 안읽음 / 보관함 탭 + 보낸 사람 필터 `routes/inbox.tsx:170,299` | 「관찰 중」(내가 지켜보는 이슈) 분리가 없다. 보관함 · 발신자 필터는 BTS 가 앞선다 | ev_dfe150314300 | P2 |
| JN-3 | 항목 안 조작 | 반응 이모지 · 회신 · 스레드 보기 · 읽음 표시 | 읽음 · 보관 토글 `routes/inbox.tsx:173,238` | 알림에서 바로 답하거나 반응할 수 없다 | 승계: docs/plans/2026-09-05-inbox-comment-deeplink.md J1–J3(딥링크) · ev_dfe150314300 | P2 |
| JN-4 | 일괄 읽음 | ⋯ 메뉴 「모두 읽음으로 표시」 | 상단 버튼 「모두 읽음」 `routes/inbox.tsx:173` | 위치만 다르다 | ev_dfe150314300 | P3 |
| JN-5 | 개인 알림 설정 | 이메일 체크 · 묶기 주기 · 형식 · 스페이스별 사용자 지정 · 제품 내/이메일 매트릭스 | 이벤트 × 채널 토글 매트릭스 `components/settings/NotificationSubscriptionMatrix.tsx:145-180` | 묶기 주기 · 프로젝트별 덮어쓰기가 없다 | ev_7d25ce8009ae · 승계: docs/plans/2026-09-05-mention-notify-autowatch.md J1–J4(멘션·자동 관찰) | P3 |

---

### 5. 타임라인 · 캘린더 (접두어 JT)

관찰 URL · 조회일 2026-10-28
- 타임라인 `.../projects/SCRUM/boards/1/timeline` ev_ddf36e7600a9 (`/projects/SCRUM/timeline` 은 404) · 보기 설정 팝오버는 스크린샷으로 확인
- 캘린더 `.../boards/1/calendar` ev_26ccdb7413c4 (기본 탭에 없어서 「+」 → 캘린더 → 「탐색에 추가」로 추가함 — 변경 기록 참고)

#### Jira Cloud 관찰 요약
- **타임라인 툴바**: 「타임라인 검색」 · 담당자 아바타 필터(할당되지 않음 / maxi님) · 「필터」 · 오른쪽 「설정 보기」 · ⋯.
- **좌측 그리드**: 열 **업무 · 상태 · 담당자**(정렬 + 열별 ⋯). 첫 행 「스프린트」(SCRUM Sprint 0 막대 2026/10/28~11/11). 맨 아래 「+ 만들기」. 에픽이 없으면 행이 비어도 그리드와 스프린트 행은 그대로다(따로 「빈 상태」 일러스트 없음).
- **시간 축**: 월 머리 Oct '25 … Oct '28, 「Today is 10/28/2026」 세로선.
- **오른쪽 아래**: 「오늘」 · 라디오 **주 / 개월 / 분기**(기본 개월) · 「범례」(일반 · 스프린트 · 두 업무 항목 간 종속성 · 롤업 날짜 · 업무 항목 막대 · 종료 날짜 없음 · 시작 날짜 없음) · 「타임라인 기간」.
- **보기 설정 팝오버**: 완료한 업무 항목 숨기기 · 계층 구조(필터 후 인라인 계층 표시) · 필드 표시(담당자 열 · 상태 열 · 에이전트 세션 열 · 경고 · 종속성) · 타임라인 막대(색 = 상태 일치 / 사용자 지정, 막대 정렬 대상).
- **캘린더**: 툴바 「캘린더 검색」 · 담당자 필터 · 「필터」 · 「오늘」 · ‹ 2026년 10월 › · 「캘린더 유형 선택: 월」(월/주) · 「예약 취소된 업무」 패널 토글 · 「설정 보기」. 그리드 기본 **평일 5열(월~금)**, 칸 hover 「+」 = 「2026년 10월 09일 업무 항목 만들기」, 스프린트가 여러 날에 걸친 막대로 보인다. 오른쪽 「예약 취소된 업무」 패널: 「각 업무 항목을 캘린더로 끌어와서 기한 날짜를 설정합니다」 · 검색 · 「최근 항목 내림차순」 · 필터 · 이슈 목록(키 · 상태).

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JT-1 | 좌측 그리드 열 | 업무 · 상태 · 담당자 열 + 정렬 + 열 메뉴 | 에픽 그룹(미분류) 행, 상태 · 담당자 열 없음(`components/timeline/TimelineRow.tsx` 에 status/assignee 참조 없음) | 막대만 보고 상태·담당자를 알 수 없다 | ev_ddf36e7600a9 | P2 |
| JT-2 | 툴바 검색 · 필터 | 타임라인 검색 · 담당자 아바타 · 필터 | 줌 컨트롤만 `routes/projects.$projectKey.timeline.tsx:58` | 좁혀 볼 방법이 없다 | ev_ddf36e7600a9 | P2 |
| JT-3 | 오늘로 이동 | 「오늘」 버튼 + Today 세로선 | 없음(줌 week~quarter + 확대/축소 `components/timeline/TimelineZoomControl.tsx:10-24`) | 스크롤한 뒤 오늘로 돌아오는 버튼이 없다 | ev_ddf36e7600a9 | P2 |
| JT-4 | 줌 단위 · 위치 | 주 / 개월 / 분기 라디오(기본 개월), 오른쪽 아래 | 주 / 월 / 분기 + 확대·축소 `TimelineZoomControl.tsx:10-19` | 단위는 같다. 위치와 ± 버튼만 다르다 | ev_ddf36e7600a9 | P3 |
| JT-5 | 스프린트 행 | 그리드 첫 행에 스프린트 막대 | 없음 | 스프린트 기간과 일정을 겹쳐 볼 수 없다 | ev_ddf36e7600a9 | P3 |
| JT-6 | 보기 설정 · 범례 | 완료 숨기기 · 계층 · 열/경고/종속성 표시 · 막대 색 + 범례 7항목 | 없음(의존선은 `DependencyOverlay.tsx` 로 항상 표시) | 표시 옵션과 범례가 없다 | 스크린샷 관찰(보기 설정) · ev_ddf36e7600a9(범례 텍스트) | P3 |
| JT-7 | 빈 상태 | 그리드 · 스프린트 행 · 「+ 만들기」가 그대로 남음 | 「표시할 이슈가 없습니다」 빈 화면 `routes/projects.$projectKey.timeline.tsx:51` · `i18n/timeline-labels.ts:48` | 빈 상태에서 바로 만들 수 없다 | ev_ddf36e7600a9 | P3 |
| JT-8 | 캘린더 진입 [TM] | 기본 탭이 아니다. 「+」 보기 메뉴(릴리스·목록·목표·바로 가기·보고서·보관된 업무 항목·첨부 파일·캘린더)에서 추가 | 프로젝트 탭에 항상 있다. 데이터는 개인 캘린더 API(편차 X-J5-13) `router.ts:370-372` | 데이터 범위가 「프로젝트」가 아니라 「나」다 | ev_26ccdb7413c4 | P2 |
| JT-9 | 캘린더 툴바 | 검색 · 담당자 · 필터 · 오늘 · ‹› · 월/주 드롭다운 · 예약 안 된 업무 토글 · 설정 보기 | 이전/오늘/다음 + 월/주 토글 `features/calendar/CalendarView.tsx:99,125` | 검색·필터·설정이 없다 | ev_26ccdb7413c4 | P2 |
| JT-10 | 예약 안 된 업무 패널 | 기한 없는 이슈 목록, 끌어다 놓으면 기한 설정, 검색·정렬·필터 | 없음 | 기한 없는 이슈를 캘린더에서 배치할 수 없다 | ev_26ccdb7413c4 · 원문 「각 업무 항목을 캘린더로 끌어와서 업무의 기한 날짜를 설정합니다」 | P2 |
| JT-11 | 월 그리드 | 기본 평일 5열, 칸 hover 「+ 업무 항목 만들기」, 스프린트 막대 | 6주 × 7일 고정 42칸 `features/calendar/MonthGrid.tsx:119-130` | 칸에서 바로 만들기가 없다. 주말 열을 숨기지 않는다 | ev_26ccdb7413c4 | P3 |

---

### 6. 대시보드 · 요약 (접두어 JD) — 넘기기 전 관찰 메모

> 다른 에이전트가 깊게 맡는다. 여기에는 관찰한 사실만 남기고 갭 표는 쓰지 않는다.
- 대시보드 목록 `https://gwalho.atlassian.net/jira/dashboards` ev_405f2e3fe37b — 「대시보드 만들기」 · 「대시보드 검색」 · 필터 콤보(사용자 · 프로젝트 · 그룹) · 열 별표 표시됨 / 이름 / 소유자 / 조회자 / 편집기 / 별표 표시(수) / ⋯. 행 1개 「Default dashboard · 내 조직 · 비공개」.
- 기본 대시보드 `.../jira/dashboards/10000` ev_6930fb69c445 — 머리 「링크 복사 · 새로 고침 · 편집(링크) · 추가 대시보드 작업(⋯)」. 가젯 4개(소개 · 프로젝트 · 나에게 할당됨 · 활동 스트림)에 각각 최소화 / 최대화 / 새로 고침 / 링크 복사 / 추가 작업. 편집 모드는 들어가지 않았다.
- 요약 `.../projects/SCRUM/summary` ev_410ccfea5a1e — 카드 4개(지난 7일 완료 · 업데이트 · 만듦, 다음 7일 마감 예정) · 상태 개요 · 최근 활동 · 우선 순위 분석 · 업무 유형 분포 · 팀 워크로드 · Epic 진행률. 스페이스 탭 요약 · 백로그 · 보드 · 개발 · 타임라인 · 문서 · 양식 · 「+」.

---

### BTS 가 이미 동등하거나 앞선 것
- **워크플로 범주 색** — 할 일 회색 · 진행 중 파랑 · 완료 초록이 Jira 와 같다 `components/workflow/editor/StatusNode.tsx:35-37`.
- **드래그로 전환 생성 · self-loop · 전역 전환 렌더** — `WorkflowEditorCanvas.tsx:232`(onConnect) · `TransitionEdge.tsx:203-214` (승계 xyflow J3~J6).
- **서버 초안 + 발행 + 이관 마법사 + 충돌 배너 + 기본값 되돌리기** — Jira TM 은 클라이언트 편집 후 한 번 저장이고, 이관은 상태를 지울 때만 뜬다. 초안이 서버에 남는 BTS 가 더 안전하다 `i18n/workflow-publish-labels.ts:21-195`.
- **전환 화면(전환할 때 입력받을 필드 · 필수)** — 전환 다이얼로그에서 정한다 `i18n/workflow-editor-labels.ts:255-261`. Jira TM 편집기 전환 패널에는 「속성」만 있고 화면 설정은 보이지 않았다.
- **프로젝트 소유 워크플로 / 전역 템플릿 복제** — 승계 project-owned-workflows 스펙.
- **Automation 고유 기능** — YAML 가져오기/내보내기 · 규칙 충돌 경고(CYCLE · FIELD_CONFLICT · PRIORITY_AMBIGUITY · PERMISSION_MISSING `api/automation-rules.types.ts:44`) · 실행 이력에서 **재실행** · SCHEDULED 다음 실행 시각 표시 · Git 웹훅 섹션.
- **설정 쪽 추가 화면** — 이슈 템플릿 · 필드 권한 · 워크플로우 스킴 · Slack 채널 · 가져오기. Jira TM 설정 내비에는 없다.
- **인박스 보관함 · 발신자 필터 · 페이지네이션** — Jira 패널에는 보관이 없다.
- **타임라인 줌 3단 + 의존선 · 잘림 배너** — 단위는 Jira 와 같다.

### 관찰 한계
- 워크플로 다이어그램 노드는 SVG 라 CDP 좌표 클릭으로 선택되지 않았다. 상태·전환 사이드 패널은 「텍스트」 보기에서 골라 관찰했다.
- 상태 삭제 → 이관 모달은 해 보지 않았다(BTS 승계 행으로 대신함). 「에이전트 추가」 · 「속성 열기」 · 규칙의 세부 설정 폼은 「필드 유효성 검사」 하나만 끝까지 봤다.
- 「전환 제한」 · 「작업 수행」 규칙 폼, Automation 액션 외부 연동 그룹 항목 전체(AWS 99+ 등)는 그룹 이름과 개수만 기록했다.
- 캘린더 「설정 보기」 팝오버 내용은 미관찰(열었지만 텍스트를 못 잡음). 타임라인은 에픽이 없어 막대 · 하위 펼침 · 의존선 실물은 미관찰.
- 프로젝트 감사 로그 첫 관찰(규칙 0개)은 빈 표였다. 규칙을 만든 뒤에는 규칙 감사 로그로 관찰했다.
- support.atlassian.com 문서는 3곳 시도해 2곳이 404 였다. 대부분 행의 (b) 칸은 「실물 관찰」 또는 BTS 문서의 기존 Cloud 인용 승계로 채웠다.
- 다른 에이전트도 같은 Chrome 을 써서 CDP 가 몇 분간 응답하지 않은 구간이 있었다. 재시도해서 관찰은 끝냈다.

### Jira 변경 기록 (2026-10-28, 사용자 승인 하 쓰기)
| 대상 | 변경 | URL |
|---|---|---|
| 워크플로(QA Hub — 작업·에픽·Subtask·스토리 공유) | 상태 **`[REA] 검증 대기`**(범주 진행 중) 추가 · 전환 **`[REA] 검증 요청`**(검토 중 → [REA] 검증 대기, 전환 ID 2) 추가 · 그 전환에 「세부 정보 확인 › 필드 유효성 검사 — 담당자 비어 있지 않음」 규칙 추가 → 「워크플로 업데이트」 저장(4유형 모두 적용). ⚠ 새 상태라 보드에 열이 하나 생겼을 수 있다 | https://gwalho.atlassian.net/jira/software/projects/SCRUM/settings/issuetypes/10004/workflow |
| Automation 규칙 | **`[REA] 이슈 생성 시 댓글`**(트리거 업무 항목 만들어짐 → 액션 댓글 「[REA] 자동화 규칙이 이 업무 항목 생성에 반응했습니다 — {{issue.key}}」) 만들고 켬 → 감사 로그 확인 뒤 **끔(DISABLED)**. ⚠ 켜져 있던 약 3분 동안 다른 에이전트가 만든 SCRUM-5 · 7 · 8 에도 「Automation for Jira」 댓글이 달렸다(이슈 상세 · 알림 관찰 때 이 댓글을 감안할 것). 감사 로그 실행 ID: ebff143e… 등 | https://gwalho.atlassian.net/jira/software/projects/SCRUM/settings/automation#/rule/01a18046-22ea-71e7-a288-793672bce3d2 |
| 이슈 | **SCRUM-6 `[REA] 자동화 트리거 테스트 (D-secondary)`** (작업, REST `POST /rest/api/3/issue` 로 생성) | https://gwalho.atlassian.net/browse/SCRUM-6 |
| 스페이스 탭 | 「캘린더」 보기를 탭에 추가(「+」 → 캘린더 → 탐색에 추가). `[REA]` 이름을 붙일 수 없는 설정 변경이라 따로 적는다 | https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1/calendar |
| 기타 | 처음 연 빌더 초안 2개는 「변경 사항 취소」로 버림(저장 안 됨) | — |

### BTS 변경 기록
- 없음. BTS 는 코드 읽기로만 비교했다(https://bts.maxihan.com 에는 접속하지 않음).

---

## E — 유형관리(JY) · 대시보드·요약(JD) 조작감 갭

> Jira UI 는 한국어 로케일이다. 2026 현재 Jira Cloud 는 issue type → 「업무 유형(work type)」, project → 「스페이스(space)」로 이름을 바꿨다. 표의 Jira 용어는 화면에 보인 문자열을 그대로 옮긴다.
> REA evidence_id 는 앞 12자만 적는다. 조회일은 모두 2026-10-28 이다.

| 약칭 | evidence_id | 관찰 URL · 상태 |
|---|---|---|
| E1 | ev_c27a4bb58 | `/jira/software/projects/SCRUM/settings/issuetypes/10004` (Story 레이아웃 편집기) |
| E2 | ev_6a28c468d | 같은 URL, 우측 「필드 만들기」 펼친 상태(필드 유형 카탈로그) |
| E3 | ev_63af47976 | 같은 URL, 「더 많은 옵션 → 업무 유형 삭제」 다이얼로그 |
| E4 | ev_d3362c641 | 같은 URL, 「업무 유형 추가 → 업무 유형 만들기 → 아이콘 변경」 아이콘 선택 다이얼로그 |
| E5 | ev_554751126 | 같은 URL, 「레이블 - 필드 구성 열기」로 펼친 필드 구성 |
| E6 | ev_551aad6ec | `/jira/software/projects/SCRUM/settings/fields` (스페이스 필드 목록) |
| E7 | ev_adf7a0ea2 | `/jira/settings/issues/issue-hierarchy` (전역 업무 유형 계층 구조) |
| E8 | ev_37c893725 | `/jira/settings/issues/issue-types` (전역 업무 유형) |
| E9 | ev_0147762fa | `/jira/settings/issues/priorities` |
| E10 | ev_b10f76ca0 | `/secure/admin/ViewCustomFields.jspa` |
| E11 | ev_250e2f4d5 | `/jira/dashboards` (목록) |
| E12 | ev_9751acaf4 | `/jira/dashboards`, 「대시보드 만들기」 다이얼로그 |
| E13 | ev_30abd7486 | `/jira/dashboards/10001/edit` ([REA] 대시보드 편집 모드 + 가젯 추가 패널) |
| E14 | ev_7593bee73 | `/jira/dashboards/10001/edit`, 파이 차트 가젯 `⋯` 메뉴 |
| E15 | ev_c0322183f | `/jira/software/projects/SCRUM/summary` |
| E16 | ev_85a3d3e11 | `/jira/software/projects/SCRUM/settings/issuetypes/10006` ([REA] 유형 레이아웃 저장 직후) |

E 증거 없이 같은 탭의 `Runtime.evaluate`(읽기 전용 innerText)로만 본 화면도 있다. 업무 유형 구성표 · 업무 유형 화면 구성표 · 화면 · 필드 구성표 · 해결책 목록, Default dashboard 의 `⋯` 메뉴 · 자동 새로 고침 · 공유 · 레이아웃 변경 팝업이 그렇다. 해당 행에는 「eval 관찰」이라고 적었다.

---

### 1. 유형관리 (JY)

관찰 URL은 위 E1~E10, E16 이다. 조회일은 2026-10-28.

#### Jira Cloud 관찰 요약
- **[TM] 진입 구조.** 스페이스 설정 사이드 내비의 「업무 유형」은 펼침 그룹이다. 하위에 유형 목록(Epic · Story · Task · Subtask)과 맨 아래 「업무 유형 추가」 버튼이 있다. 유형 하나를 누르면 `/settings/issuetypes/<id>` 편집기가 열린다(E1).
- **[TM] 편집기 3단.** 상단에는 아이콘 버튼, 이름(인라인 편집), 설명(인라인 편집), 「워크플로 편집」, `⋯`(→ 「업무 유형 삭제」 한 항목)이 있다. 본문 왼쪽은 「설명 필드」 구역과 「컨텍스트 필드」 구역이고, 컨텍스트 필드 아래에는 접히는 「비어 있으면 숨기기」 하위 구역이 있다. 오른쪽 팔레트에는 필드 검색, 「기타 필드」, 「시스템 필드」, `<details>` 「필드 만들기」(필드 유형 12종), 「필드 페이지로 이동」이 있다. 하단 고정 바에는 「버리기」와 「변경 사항 저장」이 있다(E1 · E2).
- **필드 행 조작.** 필드 행마다 `⋯` 메뉴와 「필드 구성 열기」 펼침이 있다. `⋯` 메뉴 항목은 섹션 내 순서 변경(맨 위 · 맨 아래 · 위 · 아래), 섹션으로 이동(설명 필드 · 필드가 비어 있을 때 숨기기), 필드 제거다. 펼친 구성에는 「기본 <필드>」 기본값 입력, 「필수」 체크박스, 「제거」가 있다(E5).
- **[TM] 계층은 TM 편집기에 표시되지 않는다.** Epic 편집기(`/issuetypes/10001`)에도 레벨 표기가 없다. 계층은 전역 「업무 유형 계층 구조」에서만 보인다(1 = 에픽 · 표준 유형 · 하위 유형, 「수준 만들기」는 Premium)(E7). 전역 업무 유형 표는 이름 · 계층 구조 수준 · 관련 구성표 · 작업 컬럼으로 되어 있다(E8).
- **전역 관리 내비(company-managed 쪽).** 「업무 항목」 아래로 업무 유형(계층 구조 · 업무 유형 · 업무 유형 구성표 · 하위 작업), 워크플로(워크플로 · 구성표), 화면(화면 · 화면 구성표 · 업무 유형 화면 구성표), 필드(필드 · 필드 구성표), 우선 순위(우선 순위 · 우선 순위 구성표), 업무 항목 기능(시간 추적 · 업무 항목 연결), 업무 항목 특성(상태 · 해결책), 보안 · 알림 · 권한 구성표가 이어진다(eval 관찰).
- **빈 워크스페이스 기본값.** TM 유형은 Epic · Story · Task · Subtask 4개이고 Bug 는 없다. Bug 는 「업무 유형 추가」의 제안 목록에만 있다. 우선순위 5단계 Highest~Lowest 는 「Default priority scheme」 소속이다. 해결책은 Done 1개다. 사용자 지정 필드는 46개가 활성이다(E9 · E10 · eval).

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JY-1 | 업무 유형 목록 보기 · 진입 [TM] | 스페이스 설정 사이드 내비 「업무 유형」을 펼치면 유형이 각각 링크다. 클릭하면 유형별 편집기 URL(`/issuetypes/<id>`)로 이동한다 | 유형 관리 화면이 없다. 이슈 유형은 seed 5종(epic · story · task · subtask · bug)뿐이고 API 는 read-only다(`apps/web/src/api/issue-types.ts:1`, `backend/.../V003__issue_types.sql:36-41`). 라우트에 `issue-type` 이 0건이다(`apps/web/src/router.ts:511-636`) | 유형 관리 화면 자체가 없음 | E1 ev_c27a4bb58 · 2026-10-28 / 「You can add up to 30 work types.」 https://support.atlassian.com/jira-software-cloud/docs/set-up-issue-types-in-team-managed-projects/ | P2 |
| JY-2 | 업무 유형 추가 [TM] | 「업무 유형 추가」 다이얼로그가 열린다. 「제안」 목록(버그 · 작업 · 스토리, 각 한 줄 설명)에서 고르거나 「업무 유형 만들기」를 누른다. 만들기 폼은 이름* · 설명 · 아이콘(아이콘 변경)으로 되어 있고 버튼은 만들기 · 취소다. 만들면 새 유형 편집기로 바로 이동한다(→ `/issuetypes/10006`) | 없음. `issue_types` 테이블에 행을 추가하는 API · UI 가 없다 | 유형 추가 불가 | E1 · E16 ev_85a3d3e11 · 실제 생성 관찰 / 위 문서 「You can add up to 30 work types.」 | P2 |
| JY-3 | 아이콘 선택 | 「아이콘 선택」 다이얼로그. 「이미지를 여기에 끌어 놓습니다 / 사진 업로드」 업로드 영역과 기본 아이콘 radiogroup(고리 · 점 · 그래프 · 문서 · 꺽쇠 괄호 …)이 있다. 편집기 상단 아이콘 버튼으로도 바꾼다 | `icon_name VARCHAR(50)` 컬럼만 있고 편집 UI 가 없다(`V003__issue_types.sql:15`) | 아이콘 변경 · 업로드 불가 | E4 ev_d3362c641 / 「you can also upload a custom image or choose one from the list」 위 문서 | P3 |
| JY-4 | 이름 · 설명 편집 [TM] | 편집기 상단의 이름 heading 과 설명 문장이 각각 「편집」 버튼(인라인 편집)이다 | 없음 | 유형 이름 · 설명 변경 불가 | E1 (button 「Story - 업무 유형 이름 편집」 · 「… 설명 편집」) / 실물 관찰만 | P3 |
| JY-5 | 업무 유형 삭제 · 이전 [TM] | `⋯` → 「업무 유형 삭제」 → 「이슈 유형 삭제」 다이얼로그. 「프로젝트에 1개의 "Story" 업무 항목이 있습니다」 경고와 함께 「기존 "Story" 업무 항목을 다음으로 변경」 선택기(기본값 Subtask)가 뜬다. 설정이 다르면 충돌 안내와 「업무 유형 직접 변경」 링크(마법사)가 나온다. 버튼은 삭제 · 취소다(취소로 닫음) | 없음(soft delete 컬럼만 있다, `V003__issue_types.sql:21`) | 삭제와 이슈 이전 흐름이 없다 | E3 ev_63af47976 / 「If you have work items that use that type, we'll warn you about them.」 위 문서 | P3 |
| JY-6 | 유형별 필드 레이아웃 — 2구역 [TM] | 유형마다 「설명 필드」(본문)와 「컨텍스트 필드」(우측 패널) 2구역을 따로 둔다. 요약은 「필수」 고정이고 상태는 컨텍스트 맨 위 고정이다 | 커스텀 필드는 **프로젝트 단위 한 벌**이다. 유형 구분이 없다(`apps/web/src/api/custom-fields.types.ts:39-49`, `issueTypeId` 없음). 위치(본문 · 사이드) 개념이 없고 `displayOrder` 숫자 입력만 있다(`components/custom-fields/CustomFieldFormDialog.tsx:308-316`) | 유형별로 필드가 갈리지 않는다. 본문 · 컨텍스트 배치를 고를 수 없다 | E1 / 「Fields you add to Description fields … appear in the main content area … Context fields … appear on the right side」 https://support.atlassian.com/jira-software-cloud/docs/customize-an-issues-fields-in-team-managed-projects/ | P2 |
| JY-7 | 필드 유형 카탈로그 | 「필드 만들기」를 펼치면 「필드 유형을 왼쪽 섹션으로 끌어 놓으세요」 안내와 함께 짧은 텍스트 · 단락 · 날짜 · 숫자 · **수식** · 타임스탬프 · **레이블** · 드롭다운 · 확인란 · **사용자** · **종속 드롭다운** · URL, 12종이 나온다 | 10종이다. SHORT_TEXT · LONG_TEXT · NUMBER · DATE · DATETIME · SINGLE_SELECT · MULTI_SELECT · CHECKBOX · RADIO · URL(`apps/web/src/api/custom-fields.types.ts:12-23`) | 수식 · 레이블 · 사용자(People) · 종속 드롭다운이 없다. 반대로 RADIO 는 Jira 카탈로그에 없다 | E2 ev_6a28c468d / 실물 관찰만(공식 문서에 전수 목록 없음 — 위 문서는 「More on the types of custom fields」 링크뿐) | P2 |
| JY-8 | 필드 추가 방식 — 드래그 [TM] | 팔레트의 유형 칩이나 기존 필드(`li[draggable=true]`)를 왼쪽 구역으로 끌어 놓는다. 팔레트에는 「이 스페이스의 검색 필드」 검색창이 있다 | 「필드 추가」 버튼 → 모달 폼(이름 · key · 유형 select · 필수 · 순서 · 옵션)(`CustomFieldFormDialog.tsx:275-330`) | 드래그 배치가 아니라 폼 입력이다. 팔레트 검색이 없다 | E2 (draggable 확인) / 「Drag the field from the toolbar into the list of fields.」 위 문서 | P3 |
| JY-9 | 필드 행 `⋯` 메뉴 — 재정렬 · 섹션 이동 · 제거 | 맨 위 · 맨 아래 · 위 · 아래로 이동, 섹션으로 이동(설명 필드 / 필드가 비어 있을 때 숨기기), 필드 제거. 키보드만으로 배치를 바꿀 수 있다. [REA] 유형에서 실행해 저장을 확인했다 | 행 액션은 수정 · 삭제뿐이다(`components/custom-fields/CustomFieldRow.tsx:1`). 순서는 폼의 숫자 입력으로만 바꾼다 | 메뉴형 재정렬과 섹션 이동이 없다 | E16 ev_85a3d3e11 (레이블→설명 필드, 기한→비어 있으면 숨기기 후 저장) / 실물 관찰만 | P3 |
| JY-10 | 「비어 있으면 숨기기」 | 컨텍스트 필드 아래에 접히는 하위 구역이 있다. 필드를 이 구역에 넣으면 값이 빌 때 이슈 화면에서 숨는다 | 없음(`grep hideWhenEmpty` 0건) | 빈 필드를 숨길 수 없다. 이슈 우측 패널이 빈 필드로 길어진다 | E1 · E16 / 「If the field is empty, it’s hidden.」 위 customize 문서 | P2 |
| JY-11 | 필드 구성 펼침 — 유형별 기본값 · 필수 | 행을 펼치면 「기본 레이블」 같은 기본값 입력(combobox), 「필수」 체크박스, 「제거」가 나온다. 기본값은 **유형마다 다르게** 줄 수 있다 | 필수는 프로젝트 전체 단위다(`CustomFieldFormDialog.tsx:295-302`). 기본값 필드가 없다 | 유형별 필수 · 기본값이 없다 | E5 ev_554751126 / 「the field can have a unique default value on each work type.」 위 customize 문서 | P3 |
| JY-12 | 레이아웃 변경의 저장 단위 | 레이아웃을 바꾸면 하단 바의 「버리기 / 변경 사항 저장」으로 한꺼번에 확정한다. 저장하면 「업무 유형을 업데이트함」 플래그가 뜬다 | 필드 하나마다 모달 저장 | 일괄 편집 → 확정 흐름이 없다 | E16 / 실물 관찰만 | P3 |
| JY-13 | 「워크플로 편집」 진입 [TM] | 유형 편집기 상단 버튼에서 그 유형의 워크플로 편집으로 바로 간다 | 프로젝트 워크플로 설정은 따로 있다(`router.ts:536` `/settings/workflows`). 유형 화면이 없으니 바로 가는 링크도 없다 | JY-1 에 종속 | E1 / 실물 관찰만 | P3 |
| JY-14 | 스페이스 「필드」 목록 [TM] | `/settings/fields` 표는 이름(잠금 배지) · 필드 유형 · 사용 가능한 위치 · 설명 · 작업 컬럼으로 되어 있다. 위에 필드 검색과 「필드 범주」 필터가 있다. 「필드 추가」를 누르면 우측 패널 「QA Hub에 필드 추가」에 전역 필드 목록(검색 · 범주)이 나온다 | `/projects/$projectKey/settings/custom-fields` 목록은 행에 이름 · 유형 칩 · 필수 표시가 있다(`CustomFieldRow.tsx:1`). 검색 · 범주 필터가 없고 전역 필드 개념도 없다 | 검색 · 필터와 전역 필드 재사용이 없다 | E6 ev_551aad6ec / 실물 관찰만 | P3 |
| JY-15 | 계층 레벨 표시 | TM 편집기에는 표시가 없다. 전역 「업무 유형」 표에 「계층 구조 수준」 컬럼(에픽 1 · 스토리 0)이 있다. 전역 「업무 유형 계층 구조」는 수준 이름을 바꿀 수 있고 「수준 만들기」는 Premium 이다 | 계층 정의 화면이 없다. 에픽 → 표준 → 하위 고정 규칙만 코드에 있다 | 계층 시각화 · 이름 변경이 없다(TM 기준으로는 Jira 도 미표시) | E7 ev_adf7a0ea2 · E8 ev_37c893725 / 「In team-managed spaces, subtasks are called "children" of standard work items」 set-up 문서 | P3 |
| JY-16 | 전역 「업무 유형 구성표」 · 「업무 유형 화면 구성표」 · 「화면」 · 「필드 구성표」 | 목록 표가 각각 있다. 구성표는 이름 · 옵션 · 스페이스 · 작업, 화면 구성표는 이름 · 스페이스 · 작업(연결된 구성표는 삭제 불가 안내), 화면은 이름 · 화면 구성표 · 워크플로 · 작업, 필드 구성표는 검색 · 정렬 가능 표에 「63개 필드」 같은 표기를 쓴다 | 없음. BTS 는 TM 모델(프로젝트 단위)만 있다. 워크플로 스킴만 company-managed 식으로 있다(`router.ts:228-246`) | company-managed 구성표 층이 없다. **의도적 편차 후보**(1,000명 단일 사이트에서 구성표 층은 과설계) | eval 관찰 · `/jira/settings/issues/issue-type-schemes` 등 / 실물 관찰만 | P3 |
| JY-17 | 전역 「사용자 지정 필드」 | 활성 · 삭제됨 탭이 있고 「사용자 지정 필드 만들기」 버튼이 있다. 표는 이름 · 유형 · 화면 및 컨텍스트(「1개 컨텍스트」) · 스페이스 · 마지막 사용 컬럼이다(46개 활성) | 프로젝트 커스텀 필드만 있다. 삭제됨(휴지통) 탭과 마지막 사용 통계가 없다 | 사이트 단위 필드 · 휴지통 복원이 없다 | E10 ev_b10f76ca0 / 실물 관찰만 | P3 |
| JY-18 | 우선 순위 관리 | 표는 순서 · 아이콘 및 이름 · 설명 · 색상 · 계획 · 작업(편집 · 삭제 · 이동) 컬럼이다. 위에 「우선순위 추가」가 있다. 기본 5단계에 각 설명이 붙는다 | `priority SMALLINT CHECK 1~5` 고정(`V006__issue_body_priority_labels.sql:20`). 관리 UI 가 없다 | 우선순위 추가 · 이름 · 색 · 순서를 바꿀 수 없다(5단계 고정) | E9 ev_0147762fa / 실물 관찰만 | P3 |
| JY-19 | 해결책 관리 | 표는 이름 · 설명 · 주문 · 작업(편집 · 삭제 · 기본) 컬럼이다. 위에 「해결책 번역하기」 · 「기본값 지우기」가 있고 아래에 「새 해결책 추가」 인라인 폼이 있다. 빈 사이트 기본값은 Done 1개 | `resolutions` 마스터 + seed 5종(`V011__resolutions.sql:1`). 관리 UI 가 없다 | 해결책 추가 · 기본 지정 UI 가 없다 | eval 관찰 `/secure/admin/ViewResolutions.jspa` / 실물 관찰만 | P3 |

#### BTS 가 이미 동등하거나 앞선 것
- **이슈 템플릿**(`/projects/$projectKey/settings/issue-templates`, `components/issue-templates/IssueTemplateFormDialog.tsx:32` 의 issueTypeId 바인딩 · 변수 치환 `template-variables.ts`). Jira TM 설정 내비(세부 사항 · 액세스 · 알림 · 자동화 · 필드 · 업무 유형 · 기능 · 사용자 지정 필터 · 도구 체인 · 앱)에는 대응 메뉴가 없다.
- **필드 권한 규칙**(`/projects/$projectKey/settings/field-permissions`, `components/field-permissions/FieldPermissionFormDialog.tsx`). Jira TM 설정에서는 관찰되지 않았다.
- **커스텀 필드 RADIO · DATETIME · MULTI_SELECT 를 별도 유형으로 제공한다.** Jira TM 카탈로그는 「드롭다운」 하나다(다중 선택 여부는 미관찰).
- 커스텀 필드 수정 시 fieldType · key 를 바꿀 수 없게 막았다(`CustomFieldFormDialog.tsx:199` · `:377`). Jira 의 같은 제약은 미관찰이다.

#### 관찰 한계
- **팔레트 → 구역 드래그는 CDP 로 재현하지 못했다.** `li[draggable=true]` 는 확인했다. 그러나 `Input.setInterceptDrags` + 마우스 이동으로 HTML5 drag 가 시작되지 않았다. 그래서 「필드 만들기」로 새 사용자 지정 필드를 실제로 만드는 흐름(유형 칩을 떨군 뒤의 이름 · 옵션 입력 폼)은 **미관찰**이다. 기존 필드 배치는 `⋯` 메뉴로 바꾸고 저장까지 확인했다(E16).
- 편집기 상단 「제한 없음」 버튼(유형 제한으로 추정)은 클릭해도 팝업이 잡히지 않아 **관찰 실패**로 둔다.
- 전역 업무 유형은 company-managed 용(에픽 · 스토리)만 보였다. TM 유형은 스페이스 안에만 존재한다.
- 「업무 유형 추가」 제안 목록은 Bug · Task · Story 3개만 보였다. 이미 있는 유형을 빼고 보여주는지는 미확인이다.
- 「드롭다운」이 단일 · 다중 선택을 둘 다 지원하는지, 「수식」 필드 폼이 어떻게 생겼는지는 드래그 실패로 미관찰이다.

---

### 2. 대시보드 · 요약 (JD)

관찰 URL은 E11~E15 이다. eval 관찰로는 `/jira/dashboards/10000`(Default dashboard 보기 · `⋯` · 자동 새로 고침 · 공유)과 `/secure/admin/EditDefaultDashboard.jspa`(편집 모드 · 레이아웃 팝업, 변경 없이 「완료」로 나옴)를 봤다. 조회일은 2026-10-28.

#### Jira Cloud 관찰 요약
- **목록 `/jira/dashboards`.** 제목 「대시보드」와 「대시보드 만들기」, 검색창 「대시보드 검색」, 필터 combobox 3개(소유자 · 스페이스 · 그룹)가 있다. 표 컬럼은 별표 표시됨(아이콘) · 이름 · 소유자 · 조회자 · 편집기 · 별표 표시(수)다. 행 끝 `⋯` 에는 대시보드 복사 · 대시보드 공유가 있다. 기본값으로 「Default dashboard / 내 조직 / 비공개 / 0명」 1행이 있다(E11).
- **만들기 다이얼로그.** 이름* · 설명 · 조회자(기본 「비공개 · 나만」 + 「추가」) · 편집기(기본 「비공개 · 나만」 + 「추가」) · 취소 · 저장으로 되어 있다. 저장하면 곧장 `/dashboards/<id>/edit` 편집 모드로 들어간다(E12, 실제 생성).
- **보기 모드 상단.** 별표 · 링크 복사 · 「새로 고침」 · 「편집」 · `⋯` 가 있다. `⋯` 는 대시보드 관리(대시보드 공유 · 복사 · **자동 새로 고침 구성** · **View as wallboard**), 월보드(월보드 슬라이드 쇼 보기 · 구성), 대시보드 디렉터리(대시보드 만들기 · 모든 대시보드 보기) 그룹으로 나뉜다.
- **편집 모드.** 「가젯 추가」 · 「레이아웃 변경」 · 「완료」가 있고 「현재 대시보드를 편집하고 있습니다. 변경 사항은 자동으로 저장됩니다」 배너가 뜬다. 빈 열마다 「가젯을 이 열로 끌어오거나 / 새 가젯 추가」 드롭 영역이 있다. 레이아웃 팝업 선택지는 한 개 열 · 두 개 열(기본) · 세 개 열 · 왼쪽 사이드바 · 오른쪽 사이드바, 5종이다(E13).
- **가젯 추가 패널.** 우측 고정 패널이고 「가젯 검색」이 있다. 탭은 전체 31 · Jira 30 · Charts 12 · Wallboard 5 이고 카드마다 이름 · 작성자 · 설명 · 분류 · 「추가」가 있다. 추가하면 가젯이 그 자리에서 **인라인 설정 폼**으로 열린다(E13).
- **가젯 크롬.** 제목 줄에 최소화 · 최대화 · 새로 고침 · 링크 복사 · `⋯` 가 있다. `⋯` 메뉴는 강조 색상 8종(파랑 · 빨강 · 노랑 · 녹색 · 청록 · 보라 · 회색 · 흰색) · 구성 · 복제 · 삭제다. 하단에 「N분 전에 마지막으로 새로 고침」이 붙는다(E14).
- **요약 `/summary`.** 위에서부터 「보고서 탭으로 이동해 … 맞춤화」 배너(보고서로 이동 · 해제), 「담당자로 필터링」 아바타 줄과 「필터」 버튼, 7일 카드 4개, 상태 개요(도넛 + 총 업무 항목 수), 최근 활동(펼치기), 우선 순위 분석(막대), 업무 유형(분포 %), 팀 워크로드(담당자 · 업무 배분), Epic 진행률, 피드백 순이다(E15).

#### 조작감 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JD-11 | 대시보드 목록 형태 · 검색 · 필터 | **표**(별표 · 이름 · 소유자 · 조회자 · 편집기 · 별표 수)이고 이름 · 소유자 · 별표 열로 정렬된다. 「대시보드 검색」과 소유자 · 스페이스 · 그룹 필터가 있다 | **카드 그리드**(`apps/web/src/routes/dashboards.tsx:238` `grid-cols-3`). 소유 배지만 있고(`:75-88`) 검색 · 필터 · 정렬이 없다 | 대시보드가 많아지면 찾기 어렵다. 조회자 · 편집자 컬럼이 없다 | E11 ev_250e2f4d5 / 「Select **View all dashboards**.」 https://support.atlassian.com/jira-software-cloud/docs/create-and-edit-dashboards/ | P2 |
| JD-12 | 목록에서 별표 | 행 첫 칸이 별표 토글이다(「Default dashboard에 별표 표시」). 「별표 표시됨」 열로 정렬된다 | 별표는 상세 헤더에만 있다(`components/dashboard/DashboardDetailHeader.tsx:66` `FavoriteButton`). 목록 카드에는 없다 | 목록에서 바로 즐겨찾기를 못 한다 | E11 / 실물 관찰만 | P3 |
| JD-13 | 행 `⋯` — 대시보드 복사 | 목록 행과 상세 `⋯` 둘 다에 「복사」가 있다 | 복사 API · UI 가 없다(`apps/web/src/api/dashboards.ts:141-236` 에 list · get · create · patch · delete 만) | 대시보드를 복제할 수 없다 | E11 · eval(상세 `⋯`) / 실물 관찰만 | P2 |
| JD-14 | 만들기 다이얼로그 — 조회자 · **편집자** 분리 | 조회자와 편집기 두 칸이 따로 있다(각각 「비공개 · 나만」 + 추가). 저장하면 곧장 편집 모드로 들어간다 | 폼은 name · description · visibility(PRIVATE · TEAM · ORG) · sharedUserIds(`components/dashboard/DashboardForm.tsx:14` · `:31-35`). 편집자 지정이 없다(편집은 소유자만, `lib/dashboard-permission.ts`). 생성 후 상세 보기 모드로 이동한다(`routes/dashboards.tsx:141`) | 공동 편집자를 줄 수 없다. 만든 직후 바로 편집 모드가 아니다 | E12 ev_9751acaf4 / 「If your space or group isn't listed in the Viewers or Editors field, start typing keywords to search.」 create-and-edit 문서 | P2 |
| JD-15 | 편집 변경의 저장 방식 | 편집 모드는 「변경 사항은 자동으로 저장됩니다」다. 가젯 추가 · 레이아웃 변경 · 가젯 설정 저장이 즉시 서버에 반영된다. 「완료」는 모드만 끝낸다 | 편집 모드에서 바꾼 뒤 헤더의 별도 「저장」 버튼(dirty 일 때만 활성)으로 PATCH 한다(`DashboardDetailHeader.tsx:194-200`, `routes/dashboards.$dashboardId.tsx:276-287`) | 저장을 잊으면 배치가 사라진다. Jira 는 저장 버튼이 없다 | E13 ev_30abd7486 / 실물 관찰만 | P2 |
| JD-16 | 가젯 카탈로그 UI | 우측 **패널**(대시보드를 보면서 고름)에 「가젯 검색」과 분류 탭(전체 · Jira · Charts · Wallboard, 개수 표시)이 있다. 「추가」를 누르면 가젯이 맨 왼쪽 열에 들어가고 그 자리에서 인라인 설정이 열린다 | **모달** 2단계(목록 → 설정 폼)이고 category 그룹(ISSUE · STATIC · CHART · ACTIVITY)으로 나뉜다(`components/dashboard/GadgetCatalogModal.tsx:14` · `:45-75`). 검색창이 없다 | 검색이 없다. 모달이 대시보드를 가린다 | E13 / 「Use the gadget wizard to browse the list of gadgets and select **Add**」 승계: docs/plans/2026-09-07-dashboard-jira-parity-gadgets.md JD-1 | P3 |
| JD-17 | **새로 확인된 가젯**(JD-1~10 미수록) | 카탈로그 31종 중 BTS 대응이 없는 것: 2차원 필터 통계 · JIRA 로드맵 · JIRA 이슈 캘린더 · Wallboard Spacer · 거품형 차트 · 경과 시간 차트 · 레이블 가젯 · 별표 표시된 필터 · 보는 이슈 · 빠른 연결 · 상태에서 평균 시간 · 상태에서 평균 횟수 · 소개 · 스페이스 · 스프린트 잔여 일수 · 스프린트 상태 · 업무 항목 통계 · 진행 중인 이슈 · 최초 응답 시간 · 투표한 업무 항목 · 평균 수명 차트 · 해결 시간 · 히트맵 · 워크로드 파이 차트 | BTS 12종(활성 10): assigned_to_me · recently_created · filter_result · issue_count · text_widget · link_list · pie_chart · bar_chart · sprint_burndown · activity_stream (+ 비활성 created_vs_resolved · comments_recent)(`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt:112-235`) | 위 목록이 미구현이다. 일상 영향이 큰 것은 **2차원 필터 통계 · 업무 항목 통계 · 스프린트 상태 · 스프린트 잔여 일수 · 해결 시간 · 평균 수명**이고, 나머지는 P3 | E13 ev_30abd7486 / 문서 목록 29종 「Two Dimensional Filter Statistics Gadget … Work item Statistics Gadget … Sprint Health Gadget …」 https://support.atlassian.com/jira-cloud-administration/docs/use-dashboard-gadgets/ | P2 |
| JD-18 | 가젯 설정 폼 형태(파이 차트로 확인) | 가젯 카드 안에 인라인으로 「필수 필드는 별표로 표시」, 「프로젝트 또는 저장된 필터*」 자동완성(프로젝트 · 필터 그룹 제안) + 「고급 검색」, 「통계 유형*」 select 17종(Flagged · 담당자 · 레이블 · 버전별 발생/수정사항 ×4 · 보고자 · 상위 항목 · 상태 · 스프린트 · 우선 순위 · 이슈 유형 · 작성자 · 컴포넌트 · 프로젝트 · 해결), 「자동 새로 고침 □ 매 15분마다 업데이트」, 「저장」이 있다. 저장하면 제목이 「파이 차트: QA Hub」로 바뀌고 「모든 이슈: 4」 범례가 붙는다 | 모달 2단계의 설정 폼(`components/dashboard/GadgetConfigForm.tsx`)이다. 스코프 선택기는 3종(`GadgetScopePicker.tsx`)이다. pie 의 field enum 은 status · assignee · priority · issueType 4종(`GadgetType.kt:88`)이고 스코프는 projectKey 만 받는다(X-JD-1) | 통계 유형이 4종 대 17종이다. 필터 스코프는 이연 중이다. 가젯별 자동 새로 고침 체크가 없다(JD-4 승계) | E13 · E14 / 승계: docs/plans/2026-09-07-dashboard-jira-parity-gadgets.md JD-4 · JD-6 | P2 |
| JD-19 | 가젯 `⋯` — 강조 색상 · 구성 | `⋯` = 강조 색상(8색 radio) · **구성**(설정 다시 열기) · 복제 · 삭제 | 타일 `⋯` = 복제 · 삭제뿐이다(`components/dashboard/DashboardTile.tsx:193-221`). 만든 가젯의 설정을 다시 여는 조작이 없다(`onConfigure` 0건) | 가젯 설정을 고치려면 지우고 다시 만들어야 한다. 색 구분도 없다 | E14 ev_7593bee73 / 「Select **More actions** (...), then select **Duplicate** for the gadget」 승계 JD-3 | P1 |
| JD-20 | 가젯 최소화 · 최대화 · 새로 고침 · 링크 복사 | 가젯 제목 줄에 최소화 · 최대화 · 새로 고침 · 링크 복사 아이콘이 있다. 하단에 「N분 전에 마지막으로 새로 고침」이 표시된다 | 없음. 데이터는 staleTime 30초로 고정이다(`components/dashboard/gadgets/useGadgetData.ts:28`) | 접기 · 확대 · 수동 갱신 · 마지막 갱신 시각이 없다 | E13 · E14 (button 「파이 차트 최소화 / 최대화 / 새로 고침」) / 실물 관찰만 | P2 |
| JD-21 | 대시보드 「새로 고침」 · 「자동 새로 고침 구성」 | 보기 모드 상단에 「새로 고침」 버튼이 있다. `⋯` → 「자동 새로 고침 구성」 다이얼로그는 「자동 새로 고침 간격 설정(분 단위)」 텍스트 입력(placeholder 「예: 30」)과 저장 · 취소로 되어 있다 | 없음(`grep autoRefresh\|refreshInterval` 0건) | 대시보드 단위 수동 · 자동 갱신이 없다 | eval 관찰 `/jira/dashboards/10000` (input `#automatic-refresh-interval`) / create-and-edit 문서에는 자동 새로 고침 서술이 없어 실물 관찰만 | P2 |
| JD-22 | 월보드(Wallboard) | `⋯` → View as wallboard · 월보드 슬라이드 쇼 보기 · 월보드 슬라이드 쇼 구성. 가젯 카탈로그에 Wallboard 분류(5종)가 있다 | 없음 | 월보드 · 슬라이드 쇼 모드가 없다 | eval 관찰 · E13 (Wallboard 탭 5) / 「Wallboard Spacer Gadget … Used to add a space between other gadgets.」 use-dashboard-gadgets 문서 | P3 |
| JD-23 | 레이아웃 선택지 | 프리셋 5종: 한 개 열 · 두 개 열 · 세 개 열 · 왼쪽 사이드바 · 오른쪽 사이드바. [REA] 대시보드에서 세 개 열로 바꾸면 빈 열 2개에 「가젯을 이 열로 끌어오거나 / 새 가젯 추가」 드롭 영역이 생기는 것을 확인했다 | react-grid-layout 12컬럼 자유 배치(`components/dashboard/DashboardGrid.tsx:1`). **의도적 편차 X-JD-4** | 사이드바형 2종(BA · AB)이 새로 확인됐다. 편차는 유지하되 승계 JD-5 의 「2/3컬럼」 서술을 5종으로 보정해야 한다 | E13 · eval(`#layout-A/AA/AAA/BA/AB`, aria-label 확인) / 「Select **Change layout**. Choose your preferred layout.」 승계 JD-5 | P3 |
| JD-24 | 공유 다이얼로그 형태 | `⋯` → 「대시보드 공유」. 이름* · 설명 · 조회자(공유 대상 선택 + 추가)로 되어 있다. Default dashboard 는 「관리자만 편집할 수 있으며 조직과 일반 사이에 공유」 안내와 함께 대상이 「내 조직」뿐이다. 일반 대시보드는 만들기 다이얼로그와 같은 조회자 · 편집기 2칸이다 | 공유 모달 = 공개 **링크 토큰** 생성 · 복사 · 임베드 코드 · 발급 목록 취소(`components/dashboard/ShareDashboardModal.tsx:1`). 공개 범위는 설정 모달의 visibility 로 따로 바꾼다 | 「공유」의 의미가 다르다(Jira = 권한 대상 지정, BTS = 익명 링크). 편집자 공유가 없다(JD-14) | eval 관찰 · E12 / create-and-edit 문서 위 인용 | P3 |
| JD-25 | 요약 — 담당자 필터 · 필터 버튼 | 카드 위에 「담당자로 필터링」 아바타 줄과 「필터」 버튼이 있다. 요약 전체가 선택한 담당자 기준으로 바뀐다 | 없음(`routes/projects.$projectKey.tsx:102-130`) | 사람별 요약을 볼 수 없다 | E15 ev_c0322183f / 「view data for all the work items that are assigned to one or more particular people (or unassigned)」 https://support.atlassian.com/jira-software-cloud/docs/what-is-the-summary-view/ | P2 |
| JD-26 | 요약 — Epic 진행률 위젯 | 「Epic 진행률 — 에픽을 사용하여 스페이스의 대규모 이니셔티브를 추적하세요」 위젯이 있다(에픽별 상태 분포) | 위젯 4종(상태 · 우선순위 · 유형 · 담당자) + 활동 피드뿐이다(`routes/projects.$projectKey.tsx:69-77`, `rg EpicProgress` 0건) | 에픽 진행률이 없다 | E15 / 「View a breakdown of the statuses across your epics」 summary 문서 | P2 |
| JD-27 | 요약 — 상태 개요 시각화 · 「모든 업무 항목 보기」 링크 | 상태 개요는 **도넛** + 「4 총 업무 항목 수」이고 「모든 업무 항목 보기」 링크가 있다. 업무 유형 위젯에는 「모든 항목 보기」가 있다. 우선 순위 분석은 축이 있는 막대 차트다 | 4종 모두 같은 막대 목록(`components/project/summary/DistributionWidget.tsx:1`)이고 목록 이동 링크가 없다 | 요약에서 이슈 목록으로 가는 드릴다운이 없다. 상태 총계가 눈에 띄지 않는다 | E15 / 「Here’s where you can view your space's overall progress on work items」 summary 문서 · 승계: docs/plans/2026-09-03-project-summary-activity.md J4-1~J4-4(카드 · 분포 범위는 승계) | P3 |
| JD-28 | 요약 → 보고서 맞춤화 배너 | 「스페이스에 맞게 보고서 보기를 사용자 지정하세요 … 보고서 탭으로 이동하여 차트 및 위젯을 손쉽게 사용자 지정」 배너(보고서로 이동 · 해제)가 있다. 요약이 보고서 탭의 사용자 지정 대시보드로 옮겨 가는 중이라는 신호다 | 해당 없음 | 신규 동향 — 감시 대상(구현 갭 아님) | E15 / 실물 관찰만 | P3 |
| JD-29 | `/dashboard` 착지 | Jira 에는 대응 화면이 없다. 「대시보드」 사이드 항목을 누르면 `/jira/dashboards` 목록이 나온다 | `/dashboard` 는 「환영합니다」 문구 + 이슈 링크 1개짜리 스텁이다(`apps/web/src/routes/dashboard.tsx:4-21`) | 목록이나 기본 대시보드로 보내지 않는 죽은 화면 | E11 / 실물 관찰만 | P3 |

#### 승계(재조사하지 않음)
- 가젯 추가 동선 · 보기/편집 모드 분리 → 승계: docs/plans/2026-09-07-dashboard-jira-parity-gadgets.md JD-1. BTS 는 `isEditing` 토글로 구현을 마쳤다(`routes/dashboards.$dashboardId.tsx:185`, `DashboardDetailHeader.tsx:113-136`).
- 새 가젯 삽입 위치 → 승계 JD-2(`routes/dashboards.$dashboardId.tsx:220` 주석). 이번 관찰에서 Jira 도 맨 왼쪽 열 상단에 넣는 것을 다시 확인했다.
- 타일 `⋯` Duplicate → 승계 JD-3(구현됨, `DashboardTile.tsx:188-213`). 새로 확인된 「구성 · 강조 색상」은 JD-19 로 따로 적었다.
- 레이아웃 프리셋 → 승계 JD-5 + 편차 X-JD-4(보정 내용은 JD-23).
- pie 스코프 · 통계 → 승계 JD-6 + 편차 X-JD-1. activity · burndown · created_vs_resolved · comments → 승계 JD-7~JD-10.
- 요약 카드 4종 · 2주 Done · 분포 범위 · 팀 워크로드 → 승계: docs/plans/2026-09-03-project-summary-activity.md J4-1~J4-4. 요약이 기본 착지 · 사이드바 최상단 · 활동 피드 형태 → 승계: docs/plans/2026-09-03-project-summary-route-j4.md J4-5~J4-7.

#### BTS 가 이미 동등하거나 앞선 것
- **익명 공유 링크 · 임베드 코드 · 토큰 취소**(`ShareDashboardModal.tsx`, `/dashboards/shared/$token`). Jira Cloud 대시보드 공유 UI 에서는 공개 링크 발급이 관찰되지 않았다(조회자 대상은 비공개 · 내 조직 · 그룹 · 스페이스 계열).
- **12컬럼 자유 배치 + 리사이즈**(X-JD-4). Jira 프리셋 5종보다 표현력이 넓다.
- **타일 제목 인라인 편집**(X-JD-3). Jira 는 가젯 제목이 설정에서 자동으로 정해진다(「파이 차트: QA Hub」).
- **편집 충돌 OCC 409 처리**(`routes/dashboards.$dashboardId.tsx:1`). Jira 의 자동 저장 충돌 처리는 미관찰이다.
- 텍스트 위젯 · 링크 목록 가젯. Jira 카탈로그에는 「소개」(관리자 고정 텍스트)와 「빠른 연결」(고정 링크)만 있고, 사용자가 쓰는 Markdown 위젯은 없다.

#### 관찰 한계
- 「필터 결과(결과 필터)」 가젯 설정 폼(저장된 필터* · 결과의 수 최대 50 · 표시할 열 드래그 목록 · 열 추가 · 자동 새로 고침)은 봤다. 그러나 저장된 필터가 0개라 저장하지 못했다(미구성 상태로 남김).
- 요약의 「필터」 버튼 드롭다운 내용은 클릭 좌표를 잡지 못해 **관찰 실패**로 둔다.
- 「대시보드 공유」 조회자 대상 종류(그룹 · 스페이스 · 사용자 · 공개)의 전수 목록은 Default dashboard 제한(내 조직만) 때문에 **미관찰**이다. 일반 대시보드의 공유 대상 드롭다운은 열지 않았다.
- 월보드 · 슬라이드 쇼 화면은 메뉴 항목만 확인했고 진입하지 않았다.
- 대시보드 목록의 「스페이스」 · 「그룹」 필터 선택지는 열지 않았다.

---

### Jira 변경 기록
| 시각(2026-10-28) | 대상 | URL | 내용 |
|---|---|---|---|
| 1 | 대시보드 생성 | https://gwalho.atlassian.net/jira/dashboards/10001 | `[REA] 대시보드 관찰` 생성(조회자 · 편집기 기본값 비공개) |
| 2 | 가젯 추가 · 설정 | 같은 URL | 파이 차트 추가 → 프로젝트 QA Hub · 통계 유형 「상태」로 저장 |
| 3 | 가젯 추가 | 같은 URL | 결과 필터 추가(저장된 필터가 없어 **미구성 상태**로 남음) |
| 4 | 레이아웃 변경 | 같은 URL | 두 개 열 → 세 개 열 |
| 5 | 업무 유형 생성 | https://gwalho.atlassian.net/jira/software/projects/SCRUM/settings/issuetypes/10006 | `[REA] 관찰 유형`(설명 「REA 관찰용 작업 유형」, 아이콘 기본값) |
| 6 | 업무 유형 레이아웃 저장 | 같은 URL | 레이블 → 「설명 필드」로 이동, 기한 → 「비어 있으면 숨기기」로 이동, 「변경 사항 저장」 |

- Default dashboard(`/jira/dashboards/10000`, `EditDefaultDashboard.jspa`)는 편집 모드에 들어갔다가 **변경 없이** 「완료」로 나왔다. 레이아웃 팝업은 Escape 로 닫았다.
- Story 유형의 삭제 다이얼로그 · 아이콘 다이얼로그, 대시보드 만들기 · 공유 · 자동 새로 고침 다이얼로그(Default dashboard)는 모두 취소 · Escape 로 닫았다.
- 첫 번째 [REA] 유형 생성 시도에서 상단바 전역 「만들기」 버튼이 한 번 잘못 눌렸다. 다이얼로그는 생성 없이 사라졌고 이슈 생성은 일어나지 않았다(이후 `[role=dialog]` 0개 확인).

### BTS 변경 기록
- 없음. bts.maxihan.com 에 쓰지 않았다. BTS 쪽은 전부 repo 코드 grep 으로 확인했다.

---

## F — 시각 UI/UX(룩앤필) 비교 · Jira Cloud ↔ BTS

조회일 2026-10-28 · 뷰포트 1440×900(Emulation.setDeviceMetricsOverride, DPR 1) · Chrome CDP 9222 단일 탭.
수치는 `getComputedStyle` 읽기 전용 평가로 뽑았다(스크립트 `cdp.mjs` · `F-measure.js` · `F-chain.js`, 원값 JSON 은 `m/*.json`).

**BTS 관찰 대상 변경.** 처음엔 지시대로 로컬 MSW(`localhost:5173`)를 띄웠으나, 코디네이터 긴급 지시(메모리 부족)로 dev 서버를 종료하고 실서비스 **https://bts.maxihan.com**(프로젝트 `QAWEB` · 빈 보드 확인용 `MAXI`, 이슈 `QAWEB-1`)으로 바꿨다. BTS 쪽 수치는 전부 실서비스 값이다.

**판정 기준(계약 `docs/design/jira-parity-contract.md` §3).** 팔레트 = ADS v2 이식(`DESIGN.md` §2), 사이드바 = 264px 통합 사이드바(`fr-ux-06-jira-redesign.md` §3.1). `DESIGN.md:33` 이 「v8 이후 브랜드 리프레시로 Blue700 이 `#1868DB` 로 바뀐 세대를 기각」했다고 명시한다. 그래서 Jira 실물에서 관찰한 신세대 값(`#1868DB` · `#292A2E` · Atlassian Sans · radius 6px)을 그대로 따라가자는 제안은 모두 **X(의도적 편차)** 로 분류했다. JV 로 남긴 것은 (a) BTS 자신의 정본(DESIGN.md · fr-ux-06)과 구현이 어긋난 곳, (b) 팔레트와 무관한 형태 · 밀도 · 위계 차이다.

### 공통 셸 (상단바 · 사이드바 · 타이포 · 기본 컨트롤)

관찰 URL https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1 · 조회일 2026-10-28 · REA `ev_b78af744b`

#### Jira Cloud 관찰 요약
- 상단바는 48px, 흰 배경, 하단 1px `rgba(11,18,14,.14)`. 왼쪽부터 사이드바 토글 · 앱 전환 · Jira 로고, 가운데 650px 검색창, 그 오른쪽에 붙은 `만들기`(primary), 오른쪽 끝에 `플랜 보기`(보라 아웃라인) · 알림 · 도움말 · 설정 · 아바타(모두 32×32 아이콘 버튼).
- 사이드바는 320px, **흰 배경**, 오른쪽 1px 경계. 항목은 모두 32px, radius 6px, 글자 `#505258`.
- 선택된 사이드바 항목은 배경 `#E9F2FE` + 글자 `#1868DB` + 왼쪽 파란 세로 막대(스크린샷에서 보임). 굵기는 400 그대로다.
- 본문 글꼴은 `"Atlassian Sans", ui-sans-serif, -apple-system, …`, 14px/20px. 굵기 653(가변 폰트)으로 위계를 만든다.
- 기본 버튼은 32px, radius 6px, 패딩 6px 12px, 500. primary 는 `#1868DB`, subtle 은 투명 배경 + `#505258`.
- 포커스 링은 `outline 2px solid #4688EC`, offset 2px(모달을 닫은 뒤 `만들기` 에 포커스가 돌아온 상태에서 실측).
- 입력창은 32~40px, radius 6px, 테두리 1px `#8C8F97`. 포커스 시 테두리 `#4688EC` + inset 1px(합쳐서 2px).

#### 수치 대조표
| 요소 | Jira | BTS | 차이 | BTS 출처 |
|---|---|---|---|---|
| 상단바 높이 · 배경 | 48px · #FFFFFF | 48px · #FFFFFF | 같음 | `TopBar.tsx:114` `h-12 … bg-background` |
| 상단바 하단 경계 | 1px rgba(11,18,14,.14) | 1px #DCDFE4 | 시각상 거의 같음 | `index.css:200` `--border` |
| 검색창 폭 · 테두리 · radius | 650px · 1px #8C8F97 · 6px | 448px · 1px #DCDFE4 · 8px · 배경 투명 | 폭 −202px, 테두리 대비 약함 | `fr-ux-06-jira-redesign.md:60` (max 560) · `ui/input.tsx:12` · `index.css:202` `--input` |
| `만들기` 버튼 | 82×32 · r6 · pad 6/12 · #1868DB | 82×32 · **r4** · pad 0/12 · #0C66E4 | 색은 X. radius 가 다른 버튼(8px)과 다름 | `TopBar.tsx:196` `rounded-md` 덮어씀 · `index.css:182` `--primary` |
| 사이드바 폭 · 배경 | 320px · #FFFFFF · 우측 1px | 264px(조절 200~480) · #F7F8F9 · 우측 1px #DCDFE4 | −56px, 배경이 회색 | `hooks/use-sidebar-width.ts:26` · `index.css:231` `--sidebar` |
| 사이드바 항목 높이 · radius | 32px · 6px | 32px(트리 하위 28px, 즐겨찾기 34px) · 4px | 높이가 3종으로 섞임 | `layout/Sidebar.tsx` · `layout/ProjectTree.tsx` |
| 사이드바 항목 글자 | 14/20 400 · #505258 | 14/20 500 · #172B4D@80% | 굵기 +100 | 같은 파일 |
| 사이드바 선택 상태 | bg #E9F2FE · 글자 #1868DB · 왼쪽 막대 · 400 | bg **#F1F2F4**(hover 색과 같음) · 글자 #172B4D · 600 · 막대 없음 | 파란 선택 표시가 없음 | `Sidebar.tsx:52` · `ProjectTree.tsx:56` `bg-sidebar-accent`. 정의만 있고 안 쓰는 토큰 `index.css:237,240` `--sidebar-primary` |
| 본문 font-family | Atlassian Sans → 시스템 | `'Geist Variable', sans-serif` | X(폰트 정본) | `index.css:31` · `DESIGN.md` §5 |
| body 기본 크기 · 행간 | 14px / 20px | **16px / 24px**(컴포넌트가 `text-sm` 으로 덮음) | 덮지 않은 래퍼에서 16px 이 샘 | `index.css:495` html `font-sans` 만 지정, 크기는 지정 안 함 |
| 주 · 보조 텍스트 | #292A2E · #505258(+#6B6E76) | #172B4D · #626F86 | X(팔레트) | `index.css:172,192` |
| 링크 색 | #1868DB | #0C66E4(`text-primary`) | X | `index.css:182` |
| 아이콘 크기 최빈값 | 16px · 24px(버튼 박스) · 12px | 16px · 14px · 12px | 같은 축 | lucide `size-4` 기본 `ui/button.tsx:9` |
| 기본 버튼 radius | 6px 하나로 통일 | 8px(default) / 4px(sm · xs · TopBar) 섞임 | 같은 화면 안에서 radius 가 2종 | `ui/button.tsx:9,27-28` |
| 포커스 링 | outline 2px #4688EC(대비 3.5:1) · offset 2px | border #388BFF + 3px 링 `ring/50`(흰 배경 위 약 #9CC5FF, 1.77:1) | 바깥 후광이 옅음 | `ui/button.tsx:9` · `ui/input.tsx:12` `focus-visible:ring-3 ring-ring/50` |
| 입력 테두리 대비(흰 배경) | #8C8F97 = **3.24:1** | #DCDFE4 = **1.34:1** | WCAG 1.4.11(3:1) 미달 | `index.css:202` `--input` |

#### 시각 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JV-1 | 사이드바에서 현재 위치 보기 | 선택 항목: 배경 #E9F2FE, 글자 #1868DB, 왼쪽 파란 막대 | 선택 항목: #F1F2F4 회색 + 600. hover 색과 같은 토큰을 쓴다 — `Sidebar.tsx:52`, `ProjectTree.tsx:56`. BTS 자신의 정본은 「사이드바 활성 = `--bg-selected`」(`fr-ux-06-jira-redesign.md:194`), 「selected = `--bg-selected` + `--text-selected` + 600」(`:280`)이고, 쓰라고 만든 `--sidebar-primary`(#E9F2FF/#0C66E4, `index.css:237-240`)는 아무도 안 쓴다 | 현재 위치가 hover 와 구별되지 않는다. 계약과 구현이 어긋남 | REA SCRUM/boards/1 · ev_b78af744b · 2026-10-28 / 실물 관찰만 | P2 |
| JV-2 | 입력창 · select 테두리 식별 | 테두리 #8C8F97(흰 배경 대비 3.24:1) | `--input` #DCDFE4(1.34:1) — `index.css:202`, `DESIGN.md:57` | 비텍스트 대비 3:1 미달이라 빈 입력창 경계가 흐리다. fr-ux-06 §9 의 WCAG AA 와 충돌 | REA ev_bd5cff002(설정 화면, `SCRUM` 필드 wrapper border 1px #8C8F97) · 2026-10-28 / 실물 관찰만 | P2 |
| JV-3 | 버튼 · 입력 모서리 | 버튼 · 입력 · 아이콘 버튼 전부 6px(작은 것은 4px) | Button 8px(`ui/button.tsx:9`), sm/xs 4px(`:27-28`), TopBar `만들기` 4px(`TopBar.tsx:196`), 메타 select 4px(`issue/meta/IssuePrioritySelect.tsx:43`), Input 8px(`ui/input.tsx:12`) | 한 화면 안에서 4px 과 8px 이 섞인다(`DESIGN.md:372-374` 「폼 컴포넌트 모두 rounded-lg · 섞지 말 것」 위반) | 같은 화면 실측(m/bts-board.json, m/bts-create) | P3 |
| JV-4 | 포커스 링 | 2px solid #4688EC, offset 2px | 3px `ring/50` 후광(약 1.77:1) + border-ring — `ui/button.tsx:9` | 후광이 옅어서 키보드 포커스가 덜 보인다(border 는 #388BFF 3.33:1 로 기준선은 넘음) | REA ev_b78af744b · 모달 닫은 뒤 activeElement 실측 | P3 |
| JV-5 | 본문 기본 크기 | body 14/20 | body 16/24 — `index.css:495` 은 글꼴만 지정 | `text-sm` 을 안 단 래퍼(카드 내부 · 설정 카드 제목 16px/22)가 16px 로 커져 위계가 들쭉날쭉 | m/bts-*.json body | P3 |
| JX-1 | 팔레트 · 폰트 · 기본 radius | #1868DB · #292A2E · Atlassian Sans · 6px | ADS v2 `@atlaskit/tokens@1.4.2` · Geist · 8px | **X** — `DESIGN.md:33` 이 리프레시 세대(#1868DB)를 명시적으로 기각. 계약 §3 「팔레트 스크린샷은 정본이 아니다」 | REA ev_b78af744b | X |
| JX-2 | 사이드바 폭 · 배경 | 320px · 흰 배경 | 264px · `--surface-sunken` #F7F8F9 | **X** — `fr-ux-06-jira-redesign.md` §3.1 이 정한 값 | REA ev_b78af744b | X |
| JX-3 | 상단바 오른쪽 구성 | `플랜 보기`(보라) · 도움말 아이콘 있음 | 도움말 아이콘 없음(알림 · 설정 · 아바타) | 시각 차이는 작음. 플랜 보기는 Jira 상업 요소라 X | 스크린샷 F-jira-board · F-bts-board | X |

---

### 보드
관찰 URL https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1 ↔ https://bts.maxihan.com/projects/QAWEB/board · 조회일 2026-10-28 · REA `ev_b78af744b`

#### Jira Cloud 관찰 요약
- 제목 영역: 브레드크럼 「스페이스」 + 프로젝트 아이콘 + h1 「QA Hub」 28px/32 653. 그 아래 아이콘이 붙은 탭(요약 · 백로그 · 보드 …). 활성 탭은 글자 #1868DB + **아래 2px #1868DB 밑줄**.
- 도구줄은 한 줄 32px. 보드 검색(160×32, r6, 테두리 rgba(11,18,14,.14)) · 아바타 · `필터` · `그룹`(subtle) · 오른쪽 `스프린트 완료`(primary 100×32) · 아이콘 버튼 4개.
- 컬럼은 **회색 바탕 컨테이너**(#F8F8F8, radius 12px, 폭 280, 간격 12)이고, 카드는 그 안에 흰 판으로 떠 있다(테두리 없음, 그림자만).
- 컬럼 헤더 「해야 할 일 1」은 12px/16 500 #505258. 숫자는 배지 없이 글자만.
- 카드(268×104, r8): 제목 14/20 **400** → 기한(12px) → 유형 아이콘 + 키(12/16 500 #505258) + 담당자 아바타. 그림자 `0 1px 1px rgba(30,31,33,.25), 0 0 1px rgba(30,31,33,.31)`.
- 빈 컬럼은 점선 `+` 자리표시자뿐이고, 일러스트는 없다.

#### 수치 대조표
| 요소 | Jira | BTS | 차이 | BTS 출처 |
|---|---|---|---|---|
| 페이지 제목(h1) | 28px/32 653 #292A2E | 20px/28 600 #172B4D | −8px | `layout/PageHeader.tsx:45` `text-xl font-semibold` |
| 활성 탭 표시 | 글자 #1868DB + 2px 파란 밑줄 | 글자 #172B4D + 600, 밑줄 · 색 없음 | 선택 표시가 굵기뿐 | `project/ProjectNavTabs.tsx:30-33` |
| 탭 높이 · 패딩 | 32px · 6/10/6/6 | 32px · 6/12 | 같은 축 | 같은 파일 |
| 도구줄 컨트롤 높이 | 32px 한 줄 | 보드 선택 32 · 필터 칩 **44** · `필터 저장` 28, 세 줄(보드 선택 → 테두리 박스 안 필터 → 필터 저장) | 세로 +약 140px | `filters/FilterDropdown.tsx:51` · `filters/FilterBar.tsx:208` `min-h-[44px]` |
| 필터 칩 글자 | 14px 500 | 12.8px 500(`size="sm"` `text-[0.8rem]`) | −1.2px | `ui/button.tsx:28` |
| 컬럼 폭 · 간격 | 280 · 12 | 288 · 16 | +8 · +4 | `board/BoardColumn.tsx:138` `w-72` · 부모 `gap-4` |
| 컬럼 배경 | 컨테이너 전체 #F8F8F8 · r12 | 헤더만 #F7F8F9(r 8 8 0 0), 몸통은 투명 + 1px #DCDFE4 테두리(r 0 0 8 8) | 컬럼이 「회색 통」이 아니라 「테두리 상자」 | `BoardColumn.tsx:143,164` |
| 컬럼 헤더 글자 | 12/16 500 #505258 | 14/20 600 #172B4D | +2px · +100 | `BoardColumn.tsx:144` |
| 컬럼 헤더 부가 | 숫자만 | 카테고리 원문 배지(`TODO`/`IN_PROGRESS`/`DONE`) + 파란 원형 개수 배지 | 내부 enum 이 그대로 보임(「In Review」 컬럼에 `IN_PROGRESS`) | `BoardColumn.tsx:145-150` `{column.category}` |
| 카드 크기 · radius | 268w · r8 | 270w · r8 | 같음 | `board/BoardCard.tsx:417` |
| 카드 테두리 · 그림자 | 테두리 없음 · 2겹 미세 그림자 | 1px #DCDFE4 + `shadow-sm`(0 1px 3px .1, 0 1px 2px −1px .1) | 테두리가 더해져 무거움 | `BoardCard.tsx:417` `border border-border … shadow-sm` |
| 카드 패딩 · 내부 간격 | 좌우 12 · 아래 8 | 12 사방 · gap 8 | 비슷 | 같은 줄 `p-3 gap-2` |
| 카드 제목 | 14/20 **400** | 14/19.25 **500** | +100 | `BoardCard.tsx` |
| 카드 키 | 12/16 500 #505258 | 12/16 400 #626F86 | −100 | `BoardCard.tsx` |
| 카드 사이 간격 | 미관찰(컬럼당 카드 1장) | 8px | — | `BoardColumn.tsx:164` `gap-2` |
| 라벨 칩 | (카드에 라벨 없음 — 미관찰) | 12/16 500 #626F86, 회색 칩 | — | — |
| 아바타 | 24px 원 · 미배정은 회색 사람 아이콘 | 24px 원 #0C66E4 이니셜 · 미배정은 「미배정」 글자 | 표현 다름 | `BoardCard.tsx:353,368` |
| 빈 컬럼 | 점선 `+` 자리표시자 · 일러스트 없음 | 「카드 없음」 글자 · 일러스트 없음 | 형태 다름 | 스크린샷 F-bts-board-empty |

#### 시각 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JV-6 | 프로젝트 탭에서 현재 뷰 알아보기 | 활성 탭 = 파란 글자 + 2px 파란 밑줄 | 활성 = 진한 글자 + 600만 — `ProjectNavTabs.tsx:30-33`. DESIGN.md 의 Tabs 규칙은 active = `bg-selected`/`text-selected`(`DESIGN.md:235`) | 같은 화면의 비활성 탭과 굵기 차이 하나로만 갈린다 | REA ev_b78af744b(탭 래퍼 `::after` 2px rgb(24,104,219) 실측) / 실물 관찰만 | P2 |
| JV-7 | 보드 상단 도구줄 훑기 | 32px 한 줄(검색 · 아바타 · 필터 · 그룹 · 스프린트 완료) | 세 층 — 보드 선택 · 스윔레인 줄, 테두리 박스 안 44px 필터 칩, `필터 저장` 줄 — `FilterDropdown.tsx:51`, `FilterBar.tsx:208` 의 `min-h-[44px]` 가 데스크톱에도 걸린다(`DESIGN.md:338` 은 모바일 기준) | 카드 첫 줄이 Jira 는 y≈269, BTS 는 y≈412. 보드 위쪽 약 140px 이 크롬(UI 틀)으로 쓰인다 | REA ev_b78af744b · F-jira-board vs F-bts-board | P2 |
| JV-8 | 컬럼 헤더 읽기 | 「해야 할 일 1」(12px, 회색, 숫자 글자) | 카테고리 enum 원문 배지 + 파란 개수 원 — `BoardColumn.tsx:145-150` | `IN_PROGRESS` 같은 내부 코드가 사용자 눈에 보이고, 「In Review」 컬럼에도 `IN_PROGRESS` 가 찍혀 헷갈린다 | REA ev_b78af744b | P2 |
| JV-9 | 컬럼 · 카드 입체감 | 회색 컬럼 통(#F8F8F8 r12) 안에 테두리 없는 흰 카드(그림자만) | 컬럼은 투명 + 테두리, 카드는 테두리 + `shadow-sm` — `BoardColumn.tsx:164`, `BoardCard.tsx:417`. `DESIGN.md` §8 관례(그림자 대신 ring)와도 다르다 | 카드가 바탕에서 덜 떠 보이고, 선이 이중(컬럼 테두리 + 카드 테두리)이라 시각 소음이 많다 | REA ev_b78af744b | P3 |
| JV-10 | 페이지 제목 크기 | 28px 653 | 20px 600 — `PageHeader.tsx:45`. BTS 가이드는 「페이지 제목 = `text-2xl font-semibold` 또는 `text-3xl font-bold`」(`DESIGN.md:307`) | 제목이 탭 · 버튼과 크기 차이가 작아 위계가 약하다. 자기 가이드와도 어긋남 | REA ev_b78af744b | P3 |
| JV-11 | 카드 글자 위계 | 제목 400 · 키 500 | 제목 500 · 키 400 | 굵기 위계가 뒤집혀 있다(미세) | REA ev_b78af744b | P3 |

#### BTS 가 이미 동등하거나 앞선 것
- 상단바 48px · 하단 경계 · 탭 높이 32px · 카드 radius 8px · 카드 폭(≈270)은 Jira 와 같다.
- 카드에 라벨 칩 · 시간 추적(2h 30m) · 우선순위가 보인다. Jira 기본 카드보다 정보가 많다(밀도 판단은 취향).
- 보드 빈 상태에 「카드 없음」 글자가 있다(Jira 는 점선 `+` 만).

#### 관찰 한계
- Jira 보드에 카드가 컬럼당 1장뿐이라 카드 사이 간격 · 라벨 칩 모양은 미관찰. 코디네이터가 「[REA] 데이터가 찬 뒤 재측정」을 지시했지만, 두 번 다시 조회(마지막은 작업 종료 직전)해도 활성 스프린트 보드에는 SCRUM-1 · SCRUM-2 만 있었다. 그래서 내가 직접 [REA] 이슈를 만들지는 않았다(새 이슈가 백로그로 가면 보드 측정에 쓸모가 없음).
- hover · drag 상태 스타일은 클릭 · 포인터 이동 금지 범위라 재지 않았다.

---

### 백로그
관찰 URL https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog ↔ https://bts.maxihan.com/projects/QAWEB/backlog · 조회일 2026-10-28 · REA `ev_a8bf13e73`

#### Jira Cloud 관찰 요약
- 스프린트 · 백로그 섹션이 **회색 통**(#F8F8F8, radius 12, 패딩 8)이다. 헤더 32px: 체크박스 · 접기 · 「SCRUM Sprint 0」(14/20 653) · 기간 · 「(2개의 업무 항목)」 · 오른쪽에 상태별 개수 칩 3개 · `스프린트 완료`(subtle, 24px) · `⋯`.
- 이슈는 **한 줄 행**: 흰 바탕, 1px rgba(11,18,14,.14) 테두리, 첫 행 r 8 8 0 0, 높이 ≈40~42px. 유형 아이콘 · 키(14/20) · 요약 · 상태 로젠지(20px, r4 — 해야 할 일 회색, 진행 중 #CFE1FD/#123263) · 기한 칩 · 우선순위 · 아바타.
- 빈 스프린트는 점선 드롭존 + 안내 문구 한 줄. 일러스트는 없다.
- 섹션 아래에 `+ 만들기`(36px subtle)가 있다.

#### 수치 대조표
| 요소 | Jira | BTS | 차이 | BTS 출처 |
|---|---|---|---|---|
| 이슈 한 건 높이 | ≈40~42px(한 줄 행) | **103~115px**(카드 — 제목 · 라벨 줄 · 키/우선순위/담당 줄) | ×2.6 | `backlog/BacklogCard.tsx:230` `flex flex-col gap-2 … p-3` |
| 행 테두리 · radius | 행 사이 1px 선, 목록 묶음 r8 | 카드마다 1px #DCDFE4 + r8 + `shadow-sm`, 카드 간격 8 | 「목록」이 아니라 「카드 더미」 | 같은 줄 |
| 1440×900 에서 첫 화면에 보이는 이슈 | 3(데이터 3건 전부) | 3(30건 중) | 데이터가 같으면 Jira 쪽이 약 2.6배 더 보임(높이 비 기준 추정) | — |
| 스프린트 섹션 | 회색 통 #F8F8F8 r12 pad 8 | 헤더만 #F7F8F9(r 8 8 0 0, 48px) + 몸통 1px 테두리 | 보드와 같은 「테두리 상자」 문법 | `backlog/SprintColumnHeader.tsx` · `SprintColumn.tsx` |
| 스프린트 이름 | 14/20 653 | 14/20 600 | 같음 | 같은 파일 |
| `스프린트 완료` | subtle 24px, 글자 #505258 | **초록 solid** #1F845A 24px, 12px 글자 | 위계 · 색이 다름 | `SprintColumnHeader.tsx:167` `bg-success` |
| 상태 표시 | 행마다 상태 로젠지 | 행에 상태 없음(우선순위 `P2` · 담당) | — | `BacklogCard.tsx` |
| 검색 입력 | 160×32 · 아이콘 내장 · 라벨 없음 | 라벨 「백로그 검색」 + 200×**44** 입력 · 테두리 박스 안 | 높이 +12, 박스 한 겹 더 | `backlog/BacklogFilterBar.tsx:161` `min-h-[44px]` |
| 에픽 패널 | (보이지 않음) | 백로그 위에 「에픽」 접이식 패널 | — | `backlog/BacklogEpicPanel.tsx` |

#### 시각 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JV-12 | 백로그 목록 훑기 · 순서 보기 | 이슈 = 40px 한 줄 행, 행 사이 1px 선 | 이슈 = 3단 카드 103~115px, 카드마다 테두리 · 그림자 · 8px 간격 — `BacklogCard.tsx:230` | 한 화면에 보이는 이슈 수가 약 1/2.6. 백로그는 매일 훑는 목록인데 보드 카드 문법을 그대로 쓴다 | REA ev_a8bf13e73 · 2026-10-28 / support.atlassian.com/jira-software-cloud/docs/use-your-scrum-backlog/ — "The backlog of a Scrum board shows the work items for your space grouped into a backlog and sprints." | **P1** |
| JV-13 | 스프린트 섹션 구분 | 회색 통(r12) 안에 흰 행 묶음 | 회색 헤더 + 테두리 몸통 — `SprintColumnHeader.tsx` | 섹션 경계 문법이 다름(보드 JV-9 와 같은 원인) | REA ev_a8bf13e73 | P3 |
| JV-14 | `스프린트 완료` 버튼 무게 | 백로그에서는 subtle 24px(보드에서만 primary) | 초록 solid — `SprintColumnHeader.tsx:167` | 화면에서 가장 강한 색이 「스프린트 완료」 — 실수 클릭을 부르는 시각 위계 | REA ev_a8bf13e73 | P3 |
| JV-15 | 백로그 행에서 상태 읽기 | 행마다 상태 로젠지(색 구분) | 백로그 카드에 상태 표시 없음 | 상태를 보려면 상세로 들어가야 한다 | REA ev_a8bf13e73 · F-jira-backlog | P2 |
| JV-16 | 검색 · 필터줄 높이 | 32px 한 줄 | 라벨 + 44px 입력 + 44px 버튼, 테두리 박스 — `BacklogFilterBar.tsx:161` | 데스크톱에서도 44px 터치 타깃(JV-7 과 같은 원인) | REA ev_a8bf13e73 | P2 |

#### BTS 가 이미 동등하거나 앞선 것
- 스프린트 헤더에 `ACTIVE` 상태 배지(초록 tint)와 이슈 수가 붙는다. Jira 의 「(2개의 업무 항목)」 글자와 같은 정보다.
- 카드에 라벨 · 우선순위 칩이 보인다(행 문법으로 바꿀 때 한 줄에 담을지는 별도 결정).

#### 관찰 한계
- Jira 백로그 데이터가 3건이라 긴 목록 스크롤 상태는 미관찰.

---

### 이슈 상세
관찰 URL https://gwalho.atlassian.net/browse/SCRUM-1 ↔ https://bts.maxihan.com/issues/QAWEB-1 · 조회일 2026-10-28 · REA `ev_9b657e70e`

#### Jira Cloud 관찰 요약
- 콘텐츠가 사이드바 바로 오른쪽부터 **왼쪽 정렬**로 꽉 찬다(본문 x=356~1000, 오른쪽 패널 x=1044~1400).
- 브레드크럼 12~14px → h1 「작업 1」 24/28 653 → `+` · `⋯` 아이콘 버튼 → 섹션 제목(설명 · 하위 작업 · 연결된 업무 항목 · 활동) 16/20 653.
- 오른쪽 위에 상태 버튼 「해야 할 일 ▾」(32px, r6, 배경 rgba(5,21,36,.06), 1px rgba(11,18,14,.14)) · ⚡ · 「업무 유형 작업 개선」.
- 오른쪽 「세부 사항」은 **1px 테두리 카드**(r8)다. 필드 라벨 14px 500 #292A2E, 값은 **테두리 없는 글자**(「할당되지 않음」 · 「상위 항목 추가」 #505258/#6B6E76)이고, 클릭하면 편집한다. 「나에게 할당」 링크는 #1868DB.
- 활동 탭은 세그먼트 버튼(26px, 선택 = #E9F2FE 배경 + #1868DB 1px 테두리).
- 첨부는 점선 박스 + `첨부 파일 추가` 버튼.

#### 수치 대조표
| 요소 | Jira | BTS | 차이 | BTS 출처 |
|---|---|---|---|---|
| 레이아웃 폭 | 사이드바 옆부터 꽉 참(본문 644 + 패널 357) | `max-w-[960px]` 가운데 정렬(본문 ≈525 + 패널 325), 양옆 여백 ≈100px | 넓은 화면에서 양옆이 빔 | `routes/issues.$key.tsx:1020` |
| h1 | 24/28 653 | 24/33 600 | 행간 +5 | 같은 파일 |
| 본문 섹션 제목 | 16/20 653(고정 섹션) | 20/26 600(마크다운 h2) · 「첨부 파일」 14/20 600 | 본문 제목이 패널 제목보다 큼 | `index.css` `.rich-text h2 { font-size: 1.25rem }` |
| 상태 컨트롤 | 상단 오른쪽 32px 버튼 「해야 할 일 ▾」 | 패널 첫 칸의 22px 회색 pill 「Open」 + 「더 진행할 전환 없음」 | 위치 · 크기 다름 | `issue/meta/IssueStateTransition.tsx` |
| 필드 라벨 | 14px 500 #292A2E | 12/16 400 #626F86 | −2px, 옅음 | 메타 패널 |
| 필드 값 | 테두리 없는 글자(클릭하면 편집) | **항상 테두리가 있는 native select** 44px, r4, 1px #DCDFE4 | 패널이 「폼」처럼 보임 | `issue/meta/IssueTypeSelect.tsx:48` · `IssuePrioritySelect.tsx:43` · `IssueImpactSelect.tsx:48` `min-h-[44px] rounded-md` |
| 패널 카드 | 1px rgba(11,18,14,.14) · r8 | 1px #DCDFE4 · **r12** · 필드마다 하단 구분선 | 비슷(radius 만 다름) | `DESIGN.md` §7 Card r12 |
| 환경 · 라벨 저장 | (인라인 편집) | 텍스트영역 + 별도 `저장` 버튼(44px) | 버튼이 늘어남 | `issue/meta/IssueEnvironmentEdit.tsx:47` |
| 활동 탭 | 세그먼트 26px, 선택 #E9F2FE + 1px #1868DB | 28px 탭, 선택 #E9F2FF 배경 + #0C66E4 글자(테두리 없음) | 거의 같음 | — |
| 첨부 빈 상태 | 점선 박스 + 아이콘 + 버튼 | 점선 박스 + 안내 문구 + 「첨부된 파일이 없습니다.」 | 같은 축 | — |

#### 시각 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JV-17 | 오른쪽 필드 패널 읽기 | 값이 글자로 보이고(테두리 없음), 클릭하면 편집 | 유형 · 우선순위 · 영향도가 늘 44px 테두리 select, 환경은 텍스트영역 + `저장` 버튼 — `IssueTypeSelect.tsx:48`, `IssuePrioritySelect.tsx:43`, `IssueImpactSelect.tsx:48`, `IssueEnvironmentEdit.tsx:47` | 패널이 「읽는 표」가 아니라 「입력 폼」으로 보인다. 필드 하나가 Jira ≈70px, BTS ≈89px. 900px 화면 안에 보이는 필드는 Jira 9~10개(담당자~보고자), BTS 6개(상태~라벨) | REA ev_9b657e70e · F-jira-issue vs F-bts-issue | P2 |
| JV-18 | 상세 화면 가로 공간 | 왼쪽 정렬 · 꽉 참 | `max-w-[960px] mx-auto` — `issues.$key.tsx:1020` | 1440 폭에서 양옆 ≈100px 이 비고, 본문 열은 Jira 644 → BTS ≈525 로 좁다 | REA ev_9b657e70e | P2 |
| JV-19 | 상태 확인 · 전환 진입점 | 제목 줄 오른쪽 32px 상태 버튼(가장 눈에 띄는 자리) | 패널 안 22px pill — `IssueStateTransition.tsx` | 상태가 시각적으로 묻힌다(조작 흐름은 다른 에이전트 범위, 여기서는 크기 · 위치만) | REA ev_9b657e70e | P2 |
| JV-20 | 필드 라벨 위계 | 라벨 14px 500 진한 색 | 12px 400 #626F86 | 라벨이 값보다 옅고 작아 훑기 어렵다(미세) | REA ev_9b657e70e | P3 |
| JV-21 | 본문 제목 크기 | 섹션 제목 16px | 마크다운 h2 20px | 본문 h2 가 페이지 h1(24) 바로 아래 크기라 위계가 납작하다 | REA ev_9b657e70e | P3 |

#### BTS 가 이미 동등하거나 앞선 것
- h1 24px · 활동 탭 선택 스타일(파란 tint) · 첨부 점선 드롭존은 Jira 와 같은 축이다.
- `PDF` · `이슈 이동` 버튼이 상단에 바로 보인다(Jira 는 `⋯` 안).

#### 관찰 한계
- Jira SCRUM-1 은 설명 · 댓글이 비어 있어 본문 리치 텍스트 스타일(제목 · 목록 · 코드)은 미관찰. 그래서 JV-21 은 Jira 고정 섹션 제목과 비교한 것이다.

---

### 프로젝트 설정
관찰 URL https://gwalho.atlassian.net/jira/software/projects/SCRUM/settings/details ↔ https://bts.maxihan.com/projects/QAWEB/settings/details · 조회일 2026-10-28 · REA `ev_bd5cff002`

#### Jira Cloud 관찰 요약
- 사이드바가 **설정 전용 메뉴로 바뀐다**: 「← 스페이스 설정」 · 프로젝트 아이콘 + 이름 · 세부 사항(선택, #E9F2FE + 왼쪽 막대) · 액세스 · 알림 · 자동화 · 필드 · 업무 유형 …
- 본문: 브레드크럼 · h1 「세부 사항」 24/28 653 · 오른쪽 `⋯`. 가운데에 128px 프로젝트 아이콘 + `아이콘 변경`, 그 아래 **352px 폭 단일 열 폼**.
- 필드 라벨 12/16 653 #505258, 입력 40px · r6 · 1px #8C8F97. 포커스(자동 포커스된 「이름」)는 1px #4688EC + inset 1px. 필수 표시 빨간 `*`.
- `저장`은 변경 전 비활성(배경 rgba(5,21,36,.06), 글자 rgba(8,15,33,.29)).

#### 수치 대조표
| 요소 | Jira | BTS | 차이 | BTS 출처 |
|---|---|---|---|---|
| 사이드바 | 설정 전용 메뉴로 교체, 선택 #E9F2FE | 전역 사이드바 아래에 「← 프로젝트」 + 설정 메뉴(일반 · 이슈 · 액세스 · 연동 그룹), 선택 #F1F2F4 + 600 | 구성은 같은 축, 선택 색은 JV-1 | `project/ProjectSettingsNav.tsx` |
| h1 | 「세부 사항」 24/28 653 | h1 은 프로젝트 이름 20px · 섹션 h2 「상세정보」 20px | 페이지 제목이 설정 이름이 아님 | `PageHeader.tsx:45` |
| 폼 폭 · 정렬 | 352px 단일 열, 가운데 | 608px 카드, 왼쪽 정렬 | — | 설정 상세 라우트 |
| 입력 | 40px · r6 · 1px #8C8F97 | 32px · r8 · 1px #DCDFE4 · 배경 투명 | 높이 −8, 대비 약함(JV-2) | `ui/input.tsx:12` |
| 포커스 | 1px #4688EC + inset 1px | border #388BFF + 3px `ring/50` | JV-4 | `ui/input.tsx:12` |
| 라벨 | 12/16 653 #505258 | 14/20 500 #172B4D | +2px | — |
| 섹션 카드 | (카드 없음, 평면 폼) | 흰 카드 r12 + `shadow-sm` 류, 패딩 16 | 한 겹 더 | `DESIGN.md` §7 Card |
| `저장` | 비활성 회색(변경 전) | 늘 활성 primary 32px r8 | 상태 표현 다름 | — |
| 위험 작업 | (이 화면에 없음) | 「위험 구역」 카드 + `아카이브` destructive tint | — | `ui/button.tsx:21` |

#### 시각 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JV-22 | 설정 화면 제목으로 위치 알기 | h1 = 「세부 사항」(설정 이름) | h1 = 프로젝트 이름, 「상세정보」는 h2 — `PageHeader.tsx:45` | 어느 설정 화면인지가 제목 위계에서 2순위로 밀린다 | REA ev_bd5cff002 | P3 |
| JV-23 | `저장` 버튼 상태 | 변경 전 비활성(회색) | 늘 활성 primary | 「바꾼 게 있는지」를 버튼이 알려 주지 않는다 | REA ev_bd5cff002 | P3 |
| JX-4 | 폼 입력 높이 | 40px | 32px(`ui/input.tsx:12` `h-8`) | **X** — `DESIGN.md` §6 이 「Button 기본 h-8(32px, ADS v2 밀도)」로 정함 | REA ev_bd5cff002 | X |

#### BTS 가 이미 동등하거나 앞선 것
- 설정 사이드바가 그룹 제목(일반 · 이슈 · 액세스 · 연동)으로 나뉘어 있다. Jira 의 평면 목록보다 훑기 쉽다.
- 위험 작업을 별도 「위험 구역」 카드로 떼어 놓았다.

#### 관찰 한계
- 이 화면에서는 아무것도 저장하지 않았다. 그래서 Jira 저장 활성 상태 · 오류 상태는 미관찰.

---

### 대시보드 목록
관찰 URL https://gwalho.atlassian.net/jira/dashboards ↔ https://bts.maxihan.com/dashboards · 조회일 2026-10-28 · REA `ev_cf0d299ad`

#### Jira Cloud 관찰 요약
- h1 「대시보드」 24/28 653, 오른쪽에 `대시보드 만들기`(primary 32px r6).
- 필터줄은 검색(200×32) + 소유자 · 스페이스 · 그룹 드롭다운(각 200×32)이다.
- 목록은 **표**: 테이블 전체 1px rgba(11,18,14,.14) 테두리 · r12. 헤더 행 49px, 배경 #F8F8F8, 12/16 653 #505258. 열은 별표 · 이름(정렬 ↑) · 소유자 · 조회자 · 편집기 · 별표 표시 · `⋯`. 본문 행 40px, 이름은 링크 색 #1868DB.
- 사이드바 「대시보드」가 펼쳐져 최근 대시보드 · 「대시보드 모두 보기」(선택)가 나온다.

#### 수치 대조표
| 요소 | Jira | BTS | 차이 | BTS 출처 |
|---|---|---|---|---|
| h1 | 24/28 653 | 20/28 600 | −4px | `PageHeader.tsx:45` |
| 만들기 버튼 | primary 32 r6 | primary 32 r8 | X(radius) | `ui/button.tsx:9` |
| 검색 · 필터 | 검색 + 드롭다운 3개 | 없음 | — | `routes/dashboards.tsx` |
| 목록 형태 | 표(행 40px, 헤더 49px 회색) | **카드 그리드 3열**(365×83, r8, 1px #DCDFE4, `shadow-sm`, hover `shadow-md`, 간격 16) | 형태 다름 | `routes/dashboards.tsx:81,238` |
| 이름 | 14px 링크 #1868DB | 카드 제목 16~18px 600 #172B4D(링크 색 아님) | — | 같은 파일 |
| 메타 정보 | 소유자 · 조회자(아이콘 + 「내 조직」) · 편집기(자물쇠 + 「비공개」) · 별표 수 | 「내 대시보드」 · 「나만 보기」 배지 2개 + 설명 | 열 → 배지 | 같은 파일 |
| 사이드바 선택 | 「대시보드 모두 보기」 #E9F2FE | 「대시보드」 #F1F2F4 + 600 | JV-1 | — |

#### 시각 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JV-24 | 대시보드 목록 훑기 · 비교 | 정렬 가능한 표 + 검색 · 소유자 · 스페이스 · 그룹 필터 | 카드 그리드, 검색 · 필터 없음 — `routes/dashboards.tsx:238` | 수가 늘면 찾기 · 비교가 어렵다(1,000명 사내 기준). 형태 차이가 그대로 사용성 차이 | REA ev_cf0d299ad | P2 |
| JV-25 | 대시보드 이름 = 링크 인지 | 이름이 링크 색 | 카드 전체가 링크, 제목은 본문 색 — `dashboards.tsx:81` | 미세 | REA ev_cf0d299ad | P3 |

#### BTS 가 이미 동등하거나 앞선 것
- 카드에 설명 줄이 보인다(Jira 표에는 설명 열이 없다).

#### 관찰 한계
- Jira 대시보드가 1개라 빈 상태 · 다수 상태는 미관찰.

---

### 생성 모달 (상단 `만들기`)
관찰 URL https://gwalho.atlassian.net/jira/software/projects/SCRUM/boards/1 (모달) ↔ https://bts.maxihan.com/dashboards (모달) · 조회일 2026-10-28 · REA `ev_81b300db8`
양쪽 다 **열기만 하고 Esc 로 닫았다**(입력 · 제출 없음). 닫은 뒤 dialog 수 0 을 확인했다.

#### Jira Cloud 관찰 요약
- **간이 생성(quick create) 형태**: 800×273, 상단 y=60, radius 12, 그림자 `0 8px 12px rgba(30,31,33,.15), 0 0 1px rgba(30,31,33,.31)`, 오버레이 rgba(5,12,31,.46).
- 첫 줄은 「SCRUM | 작업」 선택 칩(24px) + 오른쪽에 최소화 · 전체 화면(확장) · 닫기 아이콘.
- 그 아래 테두리 없는 큰 「요약」 입력(20px 글자) → 설명 자리표시 한 줄 → 필드 **칩 줄**(자동 · 상위 항목 · 스프린트 · Medium · 레이블, 각 24px r4).
- 하단 구분선 아래 `다른 항목 만들기` 체크박스 + primary `만들기`(60×32 r6).
- 처음 열 때 「더 간편하게 만드는 방법」 코치마크(어두운 말풍선)가 떴다.
- 접근성 제목은 「새 업무 항목 만들기」 20/24 653.

#### 수치 대조표
| 요소 | Jira | BTS | 차이 | BTS 출처 |
|---|---|---|---|---|
| 모달 크기 · 위치 | 800×273, 위에서 60px | 672×616(max-h 80vh, 스크롤), 가운데 | 높이 ×2.3 | `issue/CreateIssueDialog.tsx:90` `max-w-2xl max-h-[80vh]` |
| radius · 그림자 | 12 · 2겹 그림자 | 8 · ring 1px 10% + shadow-lg | X(관례 §8) | `ui/dialog.tsx` · `DESIGN.md` §8 |
| 오버레이 | rgba(5,12,31,.46) | rgba(0,0,0,.5) | 비슷 | `ui/dialog.tsx` |
| 제목 | 숨은 h(20px) · 시각적으로는 「요약」 입력이 주인공 | 「새 이슈 만들기」 18/18 600 | — | `CreateIssueDialog.tsx` |
| 요약 입력 | 테두리 없는 20px 글자 입력 | 라벨 「제목」 + 32px r8 입력 | 위계 다름 | `ui/input.tsx:12` |
| 필드 표현 | 24px 칩 한 줄 | 라벨 + 44px native select(r4) 세로 나열(프로젝트 · 유형 · 우선순위 · 컴포넌트 …) + 32px 입력(r8) | 같은 폼 안에서 높이 2종 · radius 2종 | `IssuePrioritySelect.tsx:43` 등 · `ui/input.tsx:12` |
| 설명 | 자리표시 한 줄 | 툴바(28px 버튼 13개) + 편집 영역 ≈100px | — | `editor/*` |
| 하단 버튼 | `만들기` 60×32 r6 | `취소`(outline) + `이슈 생성` 74×32 r8 | — | `ui/button.tsx` |

#### 시각 갭 표
| # | 조작 | Jira Cloud (관찰) | BTS 현재 (file:line) | 갭 | 근거(REA · 문서 인용 · URL) | 우선순위 |
|---|---|---|---|---|---|---|
| JV-26 | 만들기 모달의 첫인상 · 밀도 | 273px 간이 카드: 요약 큰 글자 + 필드 칩 한 줄 + `만들기` | 616px 긴 폼(스크롤) — `CreateIssueDialog.tsx:90` | 「빨리 하나 만든다」는 인상이 아니라 「폼을 채운다」는 인상. 필드 구성과 흐름은 다른 에이전트 범위라 여기서는 크기 · 밀도만 적는다 | REA ev_81b300db8 · F-jira-create vs F-bts-create | P2 |
| JV-27 | 모달 안 컨트롤 일관성 | 칩 24px · 버튼 32px, radius 4/6 두 단계 | select 44px r4 + input 32px r8 섞임 — `IssuePrioritySelect.tsx:43` vs `ui/input.tsx:12`. `DESIGN.md:374` 「같은 폼 안에서 라운드 값을 섞지 말 것」 위반 | 한 폼 안에서 높이 · radius 가 들쭉날쭉(JV-3 의 대표 사례) | 같은 측정 | P2 |

#### BTS 가 이미 동등하거나 앞선 것
- 프로젝트 · 유형이 첫 두 칸에 크게 있어서 「어디에 무엇을 만드는지」가 분명하다.
- 설명 툴바가 처음부터 보인다(Jira 는 `/` 입력으로 연다).

#### 관찰 한계
- Jira 의 「전체 양식」(확장 아이콘) 모달은 열지 않았다. 간이 형태만 쟀다.

---

### 우선순위 요약
- **P1**: JV-12(백로그 이슈가 40px 한 줄 행이 아니라 ≈110px 카드 — 같은 높이에 보이는 이슈 수가 약 1/2.6).
- **P2**: JV-1(사이드바 선택이 hover 회색 — 자체 정본 위반), JV-2(입력 테두리 대비 1.34:1), JV-6(활성 탭 표시가 굵기뿐), JV-7 · JV-16(데스크톱 44px 필터줄이 세로 공간을 차지), JV-8(컬럼 헤더에 enum 원문), JV-15(백로그에 상태 없음), JV-17(상세 필드가 상시 테두리 select), JV-18(상세 960px 가운데 정렬), JV-19(상태 컨트롤이 작고 묻힘), JV-24(대시보드 목록에 표 · 검색 · 필터 없음), JV-26 · JV-27(생성 모달 밀도 · 컨트롤 불일치).
- **X(의도적 편차, 계약 §3 · DESIGN.md)**: JX-1 팔레트 · 폰트 · radius 6px, JX-2 사이드바 320px · 흰 배경, JX-3 플랜 보기, JX-4 입력 40px.

### 스크린샷 파일 (`/Users/maxi.moff/.claude/jobs/952dbed5/tmp/rea/shots/`)
- F-jira-board.png · F-bts-board.png · F-bts-board-empty.png(MAXI 빈 보드)
- F-jira-backlog.png · F-bts-backlog.png
- F-jira-issue.png · F-bts-issue.png
- F-jira-settings.png · F-bts-settings.png
- F-jira-dashboards.png · F-bts-dashboards.png
- F-jira-create.png · F-bts-create.png · F-jira-board-after-esc.png(모달 닫힘 + 포커스 링 확인)

원측정 JSON: `m/jira-*.json` · `m/bts-*.json`. REA 원본: `F-ev/jira-*.json`.

### REA evidence_id (전체)
| 화면 | evidence_id |
|---|---|
| 보드 | ev_b78af744bdd9e7e6663c42e96ca7d694b5bb7e1a6b18b3bbaec4ead43b199f15 |
| 백로그 | ev_a8bf13e73154d2285b98f444ebe876a27e046007d6eedc9c707eb06a30462e95 |
| 이슈 상세 | ev_9b657e70e225480d209c4a5349be66f6931bdbf70d8aa776fadf7d6643be373c |
| 프로젝트 설정 | ev_bd5cff0021f9efa60a7f00157d22a0f3559eb0bb5d8b2e593ffa813d777a014a |
| 대시보드 목록 | ev_cf0d299ada6fe074c2217e137c95e6c9ef5a29dea297f7a9329312493c70a496 |
| 생성 모달 | ev_81b300db89ce8a071c66f5b324f85e5cfb34b32818bba7d71be5141b32c87d72 |

(`include_accessibility_text: false` 로 떴다. 출력이 커서 role 구조만 근거로 남겼고, 이름 · 값은 CDP computed style 실측에서 가져왔다.)

### BTS 변경 기록
- 없음. bts.maxihan.com 에서는 화면 이동 · 모달 열기/Esc · 입력 하나에 `focus()`(값 변경 없음)만 했다.
- 로컬 dev 서버(localhost:5173, MSW)는 처음에 띄웠다가 코디네이터 지시로 종료했다. 그 사이 목 로그인만 했다.

### Jira 변경 기록
- 없음. 생성 모달은 열고 Esc 로 닫았다(입력 · 제출 없음). [REA] 이슈는 만들지 않았다.

---

