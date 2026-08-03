<!-- 컨텍스트 단축키 — 레지스트리 분리 경계 · 단일 판별 파이프라인 · 항법 커서의 정체 -->

# FR-UX-10 F10 — 컨텍스트 단축키 아키텍처

> **FR**. FR-UX-10 (컨텍스트 의존 단축키) §4.8
> **PR**. #336 · **선행**. FR-UX-05(전역 단축키 5종) · FR-PF-03(키맵 커스터마이즈) · FR-UX-06 PR20(split view)
> **BC**. 논리 = personalization / 물리 = `apps/web` 프론트 전용 (백엔드 0 · 마이그레이션 0)
> **관련 계약**. [`docs/design/jira-parity-contract.md`](../design/jira-parity-contract.md) §2 단축키 레지스트리 동결

## 배경 — 키를 늘리는 순간 4곳이 동시에 깨진다

현재 BTS 의 단축키는 **전역 5종뿐이고 전부 "이동" 계열**이다(`?` 도움말 · `c` 생성 ·
`/` 검색 · `g i` 내 이슈 · `g d` 대시보드). 지라(25종+)를 쓰던 사람의 손이 기억하는
목록 항법·상세 액션이 하나도 없다. F10 은 그 첫 층인 목록 항법을 연다.

문제는 **기존 5종이 손댈 수 없는 계약**이라는 점이다. 실측(2026-08-03)으로 4중 계약을
전부 파일에서 확인했다.

| 층 | 위치 | 내용 |
|---|---|---|
| DB | `V033__user_keymap.sql` | `user_keymap_action_chk CHECK (action IN ('help','create-issue','search','goto-my-issues','goto-dashboard'))` |
| 백엔드 | `KeymapAction.kt` | enum 5종 + `WHITELIST_IDS` — 주석이 *"프론트 `SHORTCUTS` 와 1:1 대응해야 한다(계약)"* 를 명시 |
| 프론트 ① | `shortcuts.test.ts:121` | `expect(SHORTCUTS).toHaveLength(5)` |
| 프론트 ② | `shortcuts.test.ts:147` | `DEFAULT_KEYMAP` **완전일치** (`toEqual`) |

`SHORTCUTS` 에 키 하나를 추가하면 네 곳이 **동시에** red 가 된다. 이 계약의 소유자는
§3.3 FR-PF-03 이고 F10 이 아니다.

**부수 발견 — 계약 문서의 실측 명령이 고장나 있다.** `jira-parity-contract.md:39` 가
지시하는 `grep -n "toHaveLength" apps/web/src/lib/shortcuts.test.ts` 는 **존재하지 않는
경로**라 종료코드 2 로 죽는다(실제 경로는 `apps/web/src/components/keyboard-shortcuts/`).
계약 §2 는 *"개수 리터럴은 stale 해지는 순간 거짓이 된다 — 각 행의 명령으로 착수 시점에
실측하라"* 로 실측을 강제하는데 **그 명령이 실측을 못 한다**. 지시대로 실행한 사람은
0건을 보고 "계약 없음" 으로 오판한다. 같은 PR 에서 경로를 정정한다.

## D-1. 분리 경계 — 도메인 지위가 다르다

**결정.** `CONTEXT_SHORTCUTS` 를 **별도 레지스트리**로 신설한다. `SHORTCUTS` 는 무변경.
경계의 근거를 "파일을 나눈다" 가 아니라 **도메인 지위 차이**로 정의한다.

| | 전역 단축키 `SHORTCUTS` | 컨텍스트 단축키 `CONTEXT_SHORTCUTS` |
|---|---|---|
| 영속 상태 | **있음** — `user_keymap` (사용자별 override) | **없음** — 프론트 코드 상수 |
| BC 소속 | identity-access | 없음 (화면 지역 규약) |
| 사용자 재배치 | 가능 (FR-PF-03) | v1 불가 — 고정 키 |
| 성격 | **개인화 도메인 개념** | **화면 지역 조작 규약** |

**왜 이 정의인가.** "아직 안 만들어서 고정 키" 가 아니라 **지위가 달라서** 고정 키다.
이렇게 정의하면 B4(사용자 재배치)가 들어올 때 컨텍스트 단축키가 identity-access 로
올라가는 것이 **의도된 승격 경로**가 된다 — `KeymapAction` enum 확장 + `user_keymap`
CHECK 마이그레이션이 그 승격의 대가다. 지금 그 대가를 치르지 않기로 한 것이 v1 범위 결정이다.

**성공 판정식.** `shortcuts.test.ts` 의 `toHaveLength(5)` 가 **무수정 green** 유지.

**glossary 등재.** 하지 않는다 — 컨텍스트 단축키는 위 정의상 도메인 개념이 아니라 UI 구현
세부다. 다만 **전역 단축키/`KeymapAction` 은 도메인 개념인데 glossary 에 0건**이다.
이번 FR 범위 밖이므로 별건 후보로 남긴다.

## D-2. 단축키 파이프라인 단일화 — 컨텍스트 단축키는 새 리스너를 만들지 않는다

**결정.** 컨텍스트 단축키를 **별도 `document` 리스너로 붙이지 않는다.** 기존
`useKeyboardShortcuts`(RootLayout 단일 마운트)의 판별 파이프라인을 확장해
**전역 우선 → 미매칭 시 컨텍스트 폴백** 순서로 한 리스너가 처리한다.

> **★ 명제 정정 (plan 리뷰 실측, 2026-08-03).** 초안은 이 결정을 *"리스너는 하나"* 로
> 적었으나 **사실과 다르다.** `document`/`window` keydown 리스너가 이미 **5개** 공존한다 —
> `useKeyboardShortcuts:155`(전역 단축키) · `useCommandPalette:52`(팔레트) ·
> `issues.$key.tsx:89`(페인 Escape 닫기) · `ShareDashboardModal:181` ·
> `use-timeline-zoom:139`(window). 여러 리스너 공존은 BTS 의 **기존 설계**이고,
> 이중 발화는 **`e.defaultPrevented` 체크로 조정**하는 관례가 이미 있다
> (`usePaneEscapeClose` 주석 — *"Radix DismissableLayer 가 capture 단계에서
> preventDefault 하므로 bubble 단계인 이 리스너는 이미 처리된 Escape 를 건너뛴다"*).
>
> 정확한 명제는 **"앱에 리스너가 하나"** 가 아니라 **"컨텍스트 단축키가 `SHORTCUTS`
> 와 같은 키 공간을 공유하므로 그 둘은 반드시 한 파이프라인에서 판별돼야 한다"** 다.
> 아래 두 결함은 **같은 키 공간을 나눠 가질 때만** 발생하며, 팔레트·타임라인처럼
> 키 공간이 분리된 리스너와는 무관하다.
>
> **파생 — 컨텍스트 단축키는 발화 시 `preventDefault` 를 반드시 호출한다.** 그래야
> 후행 bubble 리스너들이 `defaultPrevented` 로 걸러내는 기존 관례가 성립한다.

**기각한 대안 — 독립 리스너 2개.** 코드 구조가 두 개의 실동작 결함을 강제한다.

| 결함 | 근거 |
|---|---|
| `g` 누른 뒤 `j` → **커서가 움직인다** | `resolveKeydown` 은 leader 대기 중 미등록 키에 `reset` 을 반환하고(`shortcuts.ts:193`), `dispatchAction` 의 `reset` 분기는 **`preventDefault` 를 호출하지 않는다**(`useKeyboardShortcuts.ts:92-94`). 이벤트가 그대로 흘러 두 번째 리스너에 도달한다 |
| 도움말 모달이 열렸는데 **배후 목록이 스크롤된다** | `helpOpen` 은 `useKeyboardShortcuts` 내부 `useState`(`:183`)라 외부 리스너가 알 수 없다. 전역은 모달 열림 중 help 외 전부 무동작(E7 이탈 방지)인데 그 가드가 컨텍스트에는 적용되지 않는다 |

**계약 무손상 확인.** 확장 대상은 판별 **함수**와 **훅**이지 `SHORTCUTS` **배열**이 아니다.
`shortcuts.test.ts:121` 은 배열 길이만 단언하므로 무영향. `resolveKeydown` 시그니처는
`keymap` 인자가 이미 밟은 **옵셔널 기본값 확장 패턴**(`shortcuts.ts:175`)을 따라
기존 호출부를 무회귀로 유지한다.

**가드 재사용.** `shouldIgnoreEvent`(IME 조합 중 · meta/ctrl/alt · input/textarea/select/
contentEditable)는 컨텍스트 단축키에도 그대로 선행 적용된다 — 한글 입력 중 `ㅓ`(j 자리)가
커서를 움직이면 안 된다.

## D-3. 항법 커서 = split 선택 재사용 (2026-08-03 Maxi 확정)

**결정.** `j`/`k` 가 움직이는 커서를 **새로 만들지 않고** 기존 split view 선택
(`selectedKey` = URL search param `selected`)을 그대로 커서로 쓴다.

**실측한 현재 구조.** 이슈 목록에는 이미 선택 개념이 **둘** 있고, 코드가 그 혼동을
명시적으로 경고하고 있다.

```
IssueTable.tsx:159  ★split 선택(selectedKey) ≠ bulk 선택(selection) — 이 둘은 완전히
                     독립된 개념이다.
IssueTable.tsx:211  const isCurrent = selectedKey != null && issue.key === selectedKey
IssueTable.tsx:171  data-state="selected" + aria-current="true"   ← 강조 표기 완비
issues.index.tsx:687  selected → split view 우측 상세 페인에 열린 이슈 키 (URL)
issues.index.tsx:823  와이드 + selected 일 때만 2컬럼. 그 외는 목록 전체폭
```

**대안 2안을 놓고 Maxi 가 A 를 선택했다.**

| 안 | 내용 | 기각/채택 사유 |
|---|---|---|
| **A** | **커서 = `selectedKey` 재사용** | **채택.** 지라 이슈 네비게이터와 동형 — 커서 이동이 곧 상세 교체. 새 개념 0 · 강조 표기 자산 그대로 · 새로고침/링크 공유 보존 |
| B | 별도 커서 로컬 상태 신설 | 선택 개념이 **셋**이 된다. 코드가 이미 2개의 혼동을 경고하는 자리에 하나를 더 얹고, 강조 표기가 둘(커서·split)이라 시각 충돌 설계가 추가로 필요. 이후 F9(목록 셀 인라인 편집)·F11 이 전부 그 위에 쌓여 되돌리기가 비싸진다 |

**연타 처리는 상태 분리가 아니라 요청 지연으로 푼다.** URL 은 즉시 갱신하되 우측 상세
페치만 지연시킨다 — 값은 하나(URL)뿐이라 두 상태가 어긋나는 구간이 없고, 따라서 E2E 가
타이밍에 의존하지 않는다. 구체적 지연 방식(debounce 값 · `replace` 옵션 · `staleTime`)은
**D2 스펙에서 확정**한다.

**좁은폭 동작.** `issues.index.tsx:823` 상 좁은폭은 split view 로 전환되지 않지만
`getCurrentRowAttrs` 는 `selectedKey` 만 있으면 행 강조를 붙이므로 **커서 시각 피드백은
유지**된다. 좁은폭에서 `o` 는 전체화면 상세로 이동한다.

## 남긴 것 — 스펙(D2)이 닫아야 할 것

- **`## Jira 대조` 기록 부재.** 계약 §1 이 4단계 대조와 그 기록을 요구하는데 F10 의 키 선정
  (`j`/`k`/`o`/`t`/`[`)은 **로드맵에 키만 있고 근거 기록이 없다**(레포 전수 grep 0건).
  `j`/`k`/`o` 는 지라 관례가 확실하나 **`t`(뷰 전환 추정) · `[`(사이드바 토글 추정)는
  확신도가 낮다** — 스펙 단계에서 실제 지라로 확인하고 기록을 남긴다.
- 키별 의미 확정 · 활성 컨텍스트 판정 규칙 · 도움말 모달 노출 방식 (정본 D2 범위)
- `[` 가 사이드바 토글이면 `hooks/use-sidebar-collapsed.ts`(zustand + localStorage
  fail-safe, 계약 §4 재사용 자산 등재)를 소비한다 — 새로 만들지 않는다
