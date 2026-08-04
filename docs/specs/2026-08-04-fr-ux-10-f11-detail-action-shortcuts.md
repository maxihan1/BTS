# FR-UX-10 F11 — 이슈 상세 액션 단축키 8종 · 스펙

> **FR ID.** FR-UX-10 (컨텍스트 의존 단축키) — 잔여 F11.
> 정본 `docs/plan/product/personalization.md §4.8` · 선행 ADR
> [`2026-08-03-fr-ux-10-f10-context-shortcuts.md`](../decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md).
> 이 스펙이 닫히면 §4.8 D6/D7 이 `[x]` 가 되고 **FR-UX-10 이 완주**한다.

**office-hours 스킵 근거.** ui 경량 경로(Maxi 확정 2026-08-03) + F10 선례와 동형이다 —
① 범위를 Maxi 가 확정했고(`.` 구성 수준, 아래 §Jira 대조 3-b) ② 8종 전부의 대상 UI 실재를
착수 전 전수 실측했으며 ③ ADR 5개 조항(D-1·D-2·D-4·D-5)이 선확정돼 있다.
Maxi 결정 필요 gap 은 `.` 1건이었고 착수 전에 닫았다.

## Jira 대조 (계약 §1 4단계)

### 1. 대응 화면 식별

Jira Cloud **이슈 상세 뷰**(Work item detail — 전체화면 + 네비게이터 우측 패널 양쪽).
BTS 는 `routes/issues.$key.tsx` 의 `IssueDetailPage` 하나가 `variant='page'|'pane'` 로
두 자리를 겸한다(`issues.index.tsx:28` 이 split view 우측 페인으로 마운트).

### 2. 조작감 갭 — 공식 문서 + 커뮤니티 대조 실측 (2026-08-04)

F10 과 같은 이유로 이번에도 **레포에 근거 기록이 0건**이라 직접 대조했다.

| 키 | Jira 문구 (verbatim) | 확신도 | 출처 |
|---|---|---|---|
| `a` | *"assign the work item to someone"* | 확실 | [공식 Use keyboard shortcuts](https://support.atlassian.com/jira-software-cloud/docs/use-keyboard-shortcuts/) |
| `i` | *"assign a work item to yourself"* / *"**Toggle** assignment of the selected issue to yourself"* | 확실 | 공식 + [커뮤니티](https://community.atlassian.com/forums/App-Central-articles/Jira-Keyboard-Shortcuts-A-Comprehensive-List-for-Power-Users/ba-p/2546146) |
| `m` | *"quickly add a comment"* | 확실 | 공식 |
| `l` | *"Open the dialog to edit issue labels"* | 커뮤니티 | 커뮤니티 |
| `w` | *"Toggle watching status for issue notifications"* | 커뮤니티 | 커뮤니티 |
| `.` | 두 문서 모두 **미기재**. **Maxi 실사용 증언**(2026-08-04) — 누르면 드롭다운이 열리고 **빠른 작업 · 사이트 탐색 · Jira 설정** 구역이 나온다 | 확실(실사용) | Maxi |
| `e` | 두 문서 모두 **미기재**. Jira Server 시절 `e`=Edit issue 관례 | **낮음** | — |
| `s` | *"**Share the search criteria** used for the current view"* — **이슈 편집과 무관한 다른 기능** | 확실 | 커뮤니티 |

> **★ 정본 정정 2건.**
> ① 정본 대사표(`personalization.md:288`)의 *"`s` 지라 대응 없음"* 은 **부정확**하다 —
> Jira 에 `s` 는 있고 의미가 **검색 조건 공유**다. 이슈 상세 컨텍스트에 대응이 없다는 결론은
> 같지만, 근거가 "없음"이 아니라 **"또 하나의 글자만 같은 항목"** 이다. 대사표의 세 번째 사례다.
> ② 정본 §4.8 이 *"`s` 의 BTS 대응은 이슈 즐겨찾기이고 **기능이 이미 완비**"* 라며 REST 3매핑만
> 실측했는데, **UI 도 이미 있다** — `IssueMetaPanel.tsx:322` 의 `<FavoriteButton targetType="ISSUE">`
> (FR-UX-02 산출물). `s` 는 새 버튼을 만들 필요 없이 **기존 버튼의 동작을 재사용**한다.

### 3. BTS 제약과 교차

**3-a. 「연다」가 「포커스를 옮긴다」가 된다.** Jira 는 `a`/`l` 에서 **다이얼로그**를 띄우지만
BTS 의 담당자·라벨은 우측 메타 패널에 **상시 렌더**돼 있다(`IssueAssigneeSelect` ·
`IssueLabelsEdit`). 없는 다이얼로그를 새로 만드는 것은 계약 §4(새로 만들지 말 것) 위반이고
시각 계층까지 바꾸므로, **이미 보이는 컨트롤로 포커스를 옮기는 것**으로 번역한다.
손이 도달하는 결과는 같다 — 키 하나로 그 필드를 조작할 수 있게 된다.

**3-b. `.` 은 팔레트를 여는 별칭이다 (Maxi 확정 2026-08-04).**
Jira `.` 의 3구역 중 BTS 팔레트(`command-palette/`)가 이미 갖춘 것은 **사이트 탐색**뿐이다
(바로가기 4종 + 슬래시 명령 3종). **빠른 작업** 구역 신설은 이번 범위에서 제외하고
`.` 은 기존 팔레트를 여는 **두 번째 열쇠**로만 만든다.

| 검토안 | 판정 |
|---|---|
| **팔레트 열기 별칭** | **채택.** 신규 UI 0. 팔레트 내용을 채우는 일은 §4.10 FR-UX-12(검색 진입)가 이미 예약 |
| 「이 이슈에 대한 작업」 구역 신설 | 기각(이번 범위). 단축키 PR 에 팔레트 기능 확장이 겹친다 |
| `.` 제외 | 기각. Maxi 실사용 증언으로 근거가 확보돼 「대상 없음」이 해소됐다 |

**3-c. 즉사 계약(§2) 교차.** `SHORTCUTS` 무변경 — F11 은 `CONTEXT_SHORTCUTS` 에만 추가한다.
전역 5종의 기본 키(`?` `c` `/` `g i` `g d`)와 F11 8종은 **충돌 0**(단일 트리거 기준 실측).

**3-d. 재사용 자산(§4) 교차.** 8종 전부 기존 자산을 **소비**한다. 새로 만드는 UI 는 0 이다.

| 키 | 소비 대상 | 실재 위치 |
|---|---|---|
| `a` | 담당자 검색 입력 | `meta/IssueAssigneeSelect.tsx:97` |
| `i` | 담당자 변경 뮤테이션 | `useChangeAssignee` (`issues.$key.tsx:14`) |
| `m` | 댓글 입력 textarea | `CommentSection.tsx:66` |
| `e` | 제목 편집 진입 | `issues.$key.tsx` `isEditingTitle` (F8 #337) |
| `l` | 라벨 칩 에디터 | `meta/IssueLabelsEdit.tsx` |
| `s` | 즐겨찾기 토글 | `favorite/FavoriteButton.tsx` (`IssueMetaPanel.tsx:322`) |
| `w` | 관심 토글 | `WatchersSection.tsx:78` `watch-toggle-button` |
| `.` | 명령 팔레트 | `__root.tsx:16` `useCommandPalette` |

### 4. 대응 없는 항목

- **`e`** — Jira Cloud 공식·커뮤니티 모두 미기재다. **ADS 준용도 불가**(디자인 시스템에
  단축키 규범이 없다). BTS 는 F8 이 만든 **제목 인라인 편집 진입**으로 배치한다. 근거는
  ① Jira 편집 다이얼로그의 첫 필드가 summary 라 손의 기대와 어긋나지 않고 ② F8 자산 재사용이며
  ③ 본문 편집은 `IssueDescription` 이 별도 진입면을 이미 갖고 있어 키가 겹치지 않는다.
  **확신도가 낮다는 사실을 그대로 기록한다** — 후속에 사용자 피드백으로 재배치될 수 있다.
- **`s`** — 위 §2 정정 참조. BTS 고유 배치(즐겨찾기)이며 정본 대사표가 확정한 사항이다.

## 사용자 시나리오 (Given-When-Then)

- **S1 담당자 지정.** Given 이슈 상세를 보고 있다 · When `a` 를 누른다 · Then 담당자 검색
  입력에 포커스가 가고 바로 이름을 칠 수 있다.
- **S2 나에게 할당.** Given 담당자가 내가 아니다 · When `i` 를 누른다 · Then 담당자가 나로
  바뀐다. Given 담당자가 이미 나다 · When `i` · Then **할당이 해제된다**(Jira `Toggle` 문구).
- **S3 댓글.** Given 상세를 보고 있다 · When `m` · Then 댓글 입력창에 포커스가 가고 화면이
  그 위치로 스크롤된다.
- **S4 제목 편집.** Given 상세를 보고 있다 · When `e` · Then 제목이 입력 상태로 바뀌고
  커서가 제목 끝에 놓인다(F8 `useTitleEditFocus` 재사용).
- **S5 라벨.** Given 상세를 보고 있다 · When `l` · Then 라벨 편집 컨트롤에 포커스가 간다.
- **S6 즐겨찾기.** When `s` · Then 별이 켜지거나 꺼진다(기존 버튼과 동일 동작·동일 토스트).
- **S7 관심.** When `w` · Then 관심 상태가 토글되고 감시자 수가 갱신된다.
- **S8 팔레트.** When `.` · Then 명령 팔레트가 열린다(Cmd+K 와 같은 결과).
- **S9 ★와이드 동시 생존.** Given 1024px 이상에서 목록 + 우측 상세가 동시에 떠 있다 ·
  When `j`/`k` 를 누른다 · Then **목록 커서가 그대로 움직인다**. When `a`/`m` 을 누른다 ·
  Then 우측 상세의 해당 컨트롤이 반응한다. **두 레이어가 동시에 살아 있어야 한다.**

## 기능 요구사항 (FR)

- **FR1.** `CONTEXT_SHORTCUTS` 에 8종을 추가한다. **`shortcuts.ts` 의 `SHORTCUTS` 는 손대지 않는다.**
- **FR2.** 새 레이어 `issue-detail` 을 `ShortcutContext` 유니온에 추가한다. `.` 만은
  **`app-shell` 레이어**에 둔다 — 팔레트는 전역 기능이고 Jira 도 전역에서 발화한다.
- **FR3.** `issue-detail` 의 폴백 순서는 `['issue-detail', 'issue-list', 'app-shell']` 이다.
  상세가 활성일 때도 목록 항법(`j`/`k`/`o`/`t`)이 **살아 있어야 한다**(S9).
- **FR4.** 판별은 레이어를 함께 반환하고 dispatch 는 그 레이어로 조회한다(ADR D-5-c 승계).
  액션 종류로 소속을 재추론하지 않는다.
- **FR5.** 등록은 `useContextShortcuts('issue-detail', handlers, enabled)` 로 하고, `enabled`
  조건을 만족하지 않으면 **등록 자체를 하지 않는다**(ADR D-5-a 승계).
- **FR6.** 도움말 모달(`ShortcutsHelpDialog`)에 「이슈 상세」 그룹이 자동으로 나타난다 —
  레지스트리 파생이므로 별도 배선을 만들지 않는다.
- **FR7.** 키맵 재배치 예약 키에 8종이 자동 포함된다 — `KeymapForm.tsx:119` 가 이미
  `CONTEXT_SHORTCUTS` 에서 파생하므로 **코드 변경 없이** 성립해야 한다(ADR D-4 가 F11 을
  지목해 경고한 지점. 파생이 실제로 도는지 테스트로 증명한다).
- **FR8.** 필드 권한을 존중한다. 담당자·라벨·제목이 **열람 숨김**(`restrictedFields`)이거나
  **수정 금지**(`noneditableFields`)면 해당 키는 **무동작**이다.

## 비기능 요구사항 (NFR)

- **NFR1.** 백엔드 0줄 · 마이그레이션 0 · 신규 의존성 0 · 신규 UI 컴포넌트 0.
- **NFR2.** `shortcuts.ts` · `shortcuts.test.ts` **git diff 0** (F10 판정식 승계).
- **NFR3.** 새 `keydown` 리스너 0 — 기존 파이프라인에 레이어만 얹는다(ADR D-2).
- **NFR4.** 접근성 — 포커스 이동 계열(`a`/`m`/`e`/`l`)은 실제로 포커스가 이동해야 한다
  (스크린리더가 그 컨트롤을 읽는다). 토글 계열(`s`/`w`)은 기존 `aria-pressed` 를 그대로 쓴다.
- **NFR5.** FR 총수 139 불변.

## 엣지 케이스

- **E1 입력 중 무발화.** 제목·댓글·라벨 입력 중에는 8종 어느 것도 발화하지 않는다
  (`shouldIgnoreEvent` 가 input/textarea/contentEditable 을 막는다). 특히 `.` 은 문장부호라
  본문에 자주 쳐진다 — **이 가드가 뚫리면 댓글을 쓰다 팔레트가 뜬다.**
- **E2 모달 열림 중 차단.** 상세의 모달 3종(`ResolutionModal` · `CloneIssueDialog` ·
  `MoveIssueDialog`)과 삭제 확인 UI 가 떠 있으면 `issue-detail` 을 등록하지 않는다(ADR D-5-b).
- **E3 팔레트가 떠 있을 때.** `.` 로 연 팔레트 안에서는 입력창이 포커스를 가지므로 E1 이
  적용된다. 닫기는 `Esc` 다 — `.` 로는 닫히지 않는다(실질적으로 「열기」).
- **E4 로딩·에러 상태.** 이슈를 아직 못 받았거나 조회 실패면 등록하지 않는다.
- **E5 전체화면 상세에서 `j`.** 목록이 마운트돼 있지 않으므로 `issue-list` 핸들러가 없다.
  **판별이 성공해 `preventDefault` 만 하고 아무 일도 안 일어나는 상태를 만들지 않는다**
  (ADR D-5-a 가 경고한 형태). 등록되지 않은 레이어는 폴백 대상에서 빠져야 한다.
- **E6 필드 권한 부분 적용.** 담당자만 숨김이고 라벨은 편집 가능한 경우, `a`/`i` 는 무동작이고
  `l` 은 동작한다. **F9 #338 이 `restrictedFields` 를 놓쳐 담당자를 「미배정」으로 거짓 표시한
  전례가 있다** — 두 목록을 모두 본다.
- **E7 `i` 대상 부재.** 로그인 사용자 정보를 아직 못 받았으면 `i` 는 무동작이다.
- **E8 즐겨찾기·관심 요청 진행 중.** 이미 요청이 날아가 있으면 중복 발행하지 않는다
  (기존 버튼의 `disabled` 조건을 그대로 따른다).
- **E9 좁은 폭.** 좁은 폭에서는 split view 가 없고 전체화면 상세만 있다. 8종은 그대로 동작한다
  (F10 의 커서 4종이 와이드 전용인 것과 다르다 — 상세 액션은 폭과 무관).
- **E10 이미 예약 키로 저장된 키맵.** F11 배포 전에 사용자가 전역 액션을 `a` 등으로 재배치해
  **저장해 둔 값**은 서버에 남아 있다. 새 예약어 가드는 **저장 시점**에만 검사하므로 기존
  저장값은 통과한 채다 — 그 사용자는 `a` 가 전역에 잡혀 상세 액션이 죽는다. **알려진 한계로
  기록**하고(데이터 손상 없음·fail-safe) 후속 판단에 넘긴다.

## 제약 조건

- **C1 🛑 `shortcuts.ts` 불변.** 4중 계약(프론트 단언 2 + 백엔드 `KeymapAction` +
  DB CHECK)의 소유자는 §3.3 FR-PF-03 이다. 판정식은 `git diff` 0.
- **C2 BC 격리.** 프론트 전용. 백엔드 모듈을 건드리지 않는다.
- **C3 계약 §2 e2e 문자열.** 기존 `aria-label` · 헤딩 이름을 바꾸지 않는다.
- **C4 팔레트 계약.** 빈 입력 시 바로가기 4개와 **순서 보존**(`command-palette.spec.ts`).
  `.` 은 여는 경로만 늘리므로 내용에 영향이 없어야 한다.

## 측정 가능한 완료 기준

1. `CONTEXT_SHORTCUTS` 가 **13종**(F10 5 + F11 8)이고 키 배열이 정확히
   `['j','k','o','t','[','a','i','m','e','l','s','w','.']` 이다.
2. `shortcuts.ts` · `shortcuts.test.ts` **git diff 0** — `toHaveLength(5)` 무수정 green.
3. 도움말 모달의 「이슈 상세에서」 그룹에 **7종**(`a` `i` `m` `e` `l` `s` `w`)이 렌더된다.
   `.` 은 `app-shell` 레이어라(§Jira 대조 3-b) 「어디서나」 그룹으로 가고, 거기서 팔레트
   행에 **별칭으로 접혀** `Cmd/Ctrl` `K` 또는 `.` 한 줄로 표기된다 — F11 8종의 렌더 자리는
   두 그룹에 나뉜다.
4. `KeymapForm` 예약어 검사가 **코드 변경 없이** 8종을 막는다(파생 증명 테스트).
5. 와이드 split view 에서 `j`/`k` 와 `a`/`m` 이 **동시에** 동작한다(S9).
6. 유닛 전량 green · 기존 E2E 동반 실행 green.

## 시각 검증 기준 (계약 §6 — 생략 금지)

**동반 실행할 기존 E2E** (계약 §5 사전 grep 결과).

| 스펙 | 왜 |
|---|---|
| `context-shortcuts.spec.ts` | F10 8 시나리오 — 레이어 추가가 목록 항법을 죽이지 않는지 |
| `keyboard-shortcuts.spec.ts` | 전역 5종 무회귀 |
| `command-palette.spec.ts` | `.` 추가가 바로가기 4개 순서 계약을 건드리지 않는지 |
| `inline-edit.spec.ts` | F8/F9 제목·본문 편집과 `e` 가 충돌하지 않는지 |
| `favorites.spec.ts` | `s` 가 기존 즐겨찾기 동작과 같은 결과인지 |
| `field-permissions.spec.ts` | FR8 필드 권한 |
| `issue-clone.spec.ts` · `issue-attachments.spec.ts` 등 상세 계열 | 모달 열림 중 차단(E2) |

**브라우저 눈확인 항목** (라이트/다크 양쪽).

1. `a`/`m`/`e`/`l` 을 눌렀을 때 **포커스 링이 실제로 보이는가** — 계산값이 아니라 눈으로.
2. `s`/`w` 토글 후 별·버튼 상태가 즉시 바뀌는가.
3. 댓글 본문에 마침표를 쳐도 팔레트가 뜨지 않는가(E1).
4. 와이드 split view 에서 `j` 로 목록을 옮기고 이어서 `m` 으로 우측 댓글에 진입되는가(S9).
5. 도움말 모달(`?`)의 「이슈 상세」 그룹 대비가 라이트/다크 양쪽에서 읽히는가
   (F10 에서 그룹 헤딩 대비 결함이 눈확인으로 적발된 전례).

## Brainstorming Check

✅ 통과 (ui 경량 경로 — brainstorming 스킵, `## Jira 대조` + 즉사 계약 §2 교차가 대체).
**착수 전 실측이 정본 2건을 정정**했다(§2 `s` 대응 유무 · 즐겨찾기 UI 실재). Maxi 결정
필요 gap 1건(`.` 구성 수준)은 착수 전에 닫았다. 남은 확신도 낮은 항목은 `e` 1건이고
스펙에 그대로 기록했다.
