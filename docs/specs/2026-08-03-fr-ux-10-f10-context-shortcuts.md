# FR-UX-10 F10 컨텍스트 단축키 — 스펙

> slug: fr-ux-10-f10-context-shortcuts | type: ui | BC: personalization (논리, 물리=`apps/web`)
> ADR: [docs/decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md](../decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md)
> 작성: 2026-08-03 | 프론트 전용 (백엔드/DB/API 변경 0) | PR #336

## 범위 (Maxi 확정 2026-08-03)

FR-UX-10 의 승계 PR 2건 중 **F10 만**. F11 은 F8(FR-UX-11 인라인 편집) 의존이라 제외.

**F10 = 컨텍스트 단축키 아키텍처 + 목록 항법 5종.**

| 키 | 동작 | 컨텍스트 |
|---|---|---|
| `j` | 목록 커서 아래(다음 이슈) | `issue-list` |
| `k` | 목록 커서 위(이전 이슈) | `issue-list` |
| `o` | 커서 이슈를 전체화면 상세로 열기 | `issue-list` |
| `t` | 상세 페인(split view) 토글 | `issue-list` |
| `[` | 사이드바 접기/펼치기 | `app-shell` |

**범위 밖.** F11 상세 액션 8종 · 사용자 키 재배치(로드맵 B4) · 보드/백로그 컨텍스트 ·
페이지 경계를 넘는 커서 이동 · `]`(펼침 전용 키, 단일 `[` 토글로 대체).

## Jira 대조 (계약 §1 4단계)

### 1. 대응 화면 식별

Jira Cloud **이슈 네비게이터**(Issue Navigator — 좌측 목록 + 우측 상세 패널). BTS 의
`/issues` split view (`issues.index.tsx:849 IssueListSplitView`, FR-UX-06 PR20)가 같은 자리다.

### 2. 조작감 갭 — 공식 문서 대조 실측 (2026-08-03)

로드맵에 키만 있고 **근거 기록이 레포에 0건**이라(전수 grep) 이번에 처음 대조했다.

| 키 | Jira 공식 문구 | 확신도 | 출처 |
|---|---|---|---|
| `j` | *"Press **J** to go down"* | 확실 | [Use keyboard shortcuts](https://support.atlassian.com/jira-software-cloud/docs/use-keyboard-shortcuts/) |
| `k` | *"Press **K** to go up"* | 확실 | 〃 |
| `o` | *"Press **O** to open the selected work item"* | 확실 | 〃 |
| `t` | *"toggle the detailed issue view … in the sidebar panel view"* | 커뮤니티 | [Comprehensive list](https://community.atlassian.com/forums/App-Central-articles/Jira-Keyboard-Shortcuts-A-Comprehensive-List-for-Power-Users/ba-p/2546146) |
| `[` | JSM = *"press `]` to expand and `[` to collapse"* / Jira Software = *"`Ctrl + [` to expand or collapse"* | 확실(제품별 상이) | [New navigation](https://support.atlassian.com/jira-software-cloud/docs/what-is-the-new-navigation-in-jira/) |

곁가지로 F11 키도 공식 확인됐다 — `a`=assign · `i`=assign to me · `m`=add comment.
보드용 `n`/`p`(다음/이전 컬럼)는 F10 범위 밖(보드 컨텍스트 미대상).

### 3. BTS 제약과 교차

- **`[` 는 단일 키를 채택한다** (JSM 방식). Jira Software 의 `Ctrl+[` 를 쓰지 않는 이유 3가지.
  ① `shouldIgnoreEvent` 가 `metaKey || ctrlKey` 를 **무조건 무시**한다(`shortcuts.ts:238`) —
  뚫으려면 FR-UX-05 가 브라우저 단축키 보호를 위해 의도적으로 넣은 가드를 깨야 한다
  ② macOS `Cmd+[` 는 뒤로가기라 손이 혼동한다 ③ 로드맵도 단일 `[` 로 적었다.
  `]`(펼침 전용)은 도입하지 않는다 — 단일 `[` 토글로 충분하고 키 표면을 줄인다.
- **즉사 계약(§2) 교차** — `SHORTCUTS` 무변경. `CONTEXT_SHORTCUTS` 신설로 우회한다.
- **재사용 자산(§4) 교차** — `[` 는 `hooks/use-sidebar-collapsed.ts`(zustand + localStorage
  fail-safe)를 **소비**한다. 새 스토어를 만들지 않는다. 커서는 기존 `selectedKey` 재사용(ADR D-3).

### 4. 대응 없는 항목

없음. 5종 전부 Jira 대응이 확인됐다. (`s`=즐겨찾기는 Gmail 기준 이연분으로 F11 배치 —
`personalization.md §4.8` 대사표 참조.)

## 사용자 시나리오 (Given-When-Then)

### S1. 목록 커서 이동 (`j` / `k`)
- Given 로그인 사용자가 `/issues` 에 있고 입력 포커스가 없을 때
- When `j` 를 누르면 → 커서가 다음 행으로 이동. URL `?selected` 가 그 이슈 키로 바뀌고
  행에 `aria-current="true"` + `data-state="selected"` 가 붙는다
- When `k` 를 누르면 → 이전 행으로 같은 동작

### S2. 커서 없는 상태에서 첫 이동
- Given `/issues` 에 `?selected` 가 없을 때
- When `j` 또는 `k` 를 누르면 → **첫 행**이 선택된다 (`k` 도 첫 행 — 경계 wrap 없음 규칙과 일관)

### S3. 이슈 열기 (`o`)
- Given 커서가 `ATLAS-13` 에 있을 때
- When `o` 를 누르면 → `/issues/ATLAS-13` 전체화면 상세로 이동한다

### S4. 상세 페인 토글 (`t`)
- Given 커서가 있고 상세 페인이 열려 있을 때 → `t` 로 닫는다 (URL 에서 `selected` 제거)
- Given `?selected` 가 없을 때 → `t` 로 **첫 행**을 선택해 연다
- 좁은폭에서는 2컬럼 전환이 없으므로(`issues.index.tsx:823`) `t` 는 **행 강조만** 토글한다

### S5. 사이드바 토글 (`[`)
- Given 로그인 사용자가 셸이 있는 **아무 화면**에 있고 입력 포커스가 없을 때
- When `[` 를 누르면 → 사이드바가 접히거나 펼쳐지고 localStorage 에 영속된다

### S6. 도움말 모달 노출 (`?`)
- Given 사용자가 `?` 로 도움말을 열면
- Then 전역 5종 + `Cmd+K` **에 더해** 컨텍스트 단축키 5종이 **컨텍스트 라벨과 함께** 표시된다
- Then **F11 미구현 키는 표시하지 않는다** (FR-UX-05 FR8 계약 — 비구현 단축키를 동작하는
  것처럼 표기 금지)

### S7. 전역 단축키와의 공존
- Given 사용자가 `/issues` 에서 `g` 를 누른 뒤 (leader 대기 중)
- When `j` 를 누르면 → **아무 일도 일어나지 않는다.** leader 시퀀스만 리셋되고 커서는
  움직이지 않는다 (ADR D-2)
- Given 도움말 모달이 열려 있을 때 → `j`/`k`/`o`/`t`/`[` 전부 무동작 (배후 목록 불변)

### S8. 입력 포커스·IME 가드
- Given 사용자가 검색 입력창에 포커스를 두고 한글을 조합 중일 때
- When `ㅓ`(j 자리) 또는 `ㅏ`(k 자리)를 입력하면 → 커서가 움직이지 않고 문자가 정상 입력된다

## 기능 요구사항 (FR)

- **FR1** 신규 `context-shortcuts.ts` 가 `CONTEXT_SHORTCUTS` 레지스트리와 순수 판별 함수를
  소유한다. 기존 `shortcuts.ts` 의 `SHORTCUTS`·`DEFAULT_KEYMAP` 은 **무변경**.
- **FR2** 컨텍스트는 **레이어**로 정의한다 — `app-shell`(셸이 렌더된 모든 화면) ⊃
  `issue-list`(`/issues`). 판별 순서는 **전역 `SHORTCUTS` → 좁은 컨텍스트 → 넓은 컨텍스트**.
- **FR3** 활성 컨텍스트는 **라우트 기반**으로 판정한다(포커스 기반 아님). 라우트 컴포넌트가
  자신의 컨텍스트를 선언한다.
- **FR4** 리스너는 **하나**다. 기존 `useKeyboardShortcuts`(RootLayout 단일 마운트)의 판별
  파이프라인을 확장한다. 별도 `document` 리스너 추가 금지 (ADR D-2).
- **FR5** 커서는 기존 `selectedKey`(URL search param `selected`)다. 새 커서 상태 신설 금지
  (ADR D-3). URL 갱신은 `replace: true` 로 히스토리를 오염시키지 않는다.
- **FR6** `j`/`k` 는 현재 렌더된 목록 순서를 따른다. 경계에서 **wrap 하지 않는다**(멈춘다).
- **FR7** `[` 는 `use-sidebar-collapsed.ts` 의 zustand 스토어를 소비한다. 새 상태 신설 금지.
- **FR8** 도움말 모달은 `CONTEXT_SHORTCUTS` 를 **단일 진실 출처로 구동**한다 — 훅 dispatch 와
  모달 목록이 같은 상수를 읽는다 (FR-UX-05 설계 명확화 계약 승계, 표기 drift 0).
- **FR9** 가드는 기존 `shouldIgnoreEvent` 를 **그대로 선행 적용**한다. 순서는 FR-UX-05 계약
  준수 — (1) `enabled` (2) `isComposing` (3) 입력 포커스 (4) 수정자, 실패 시 `preventDefault`
  **이전에** early return.
- **FR10** 커서 이동 시 대상 행이 뷰포트 밖이면 `scrollIntoView({ block: 'nearest' })` 로
  따라간다.
- **FR11** 커서 이동을 `aria-live="polite"` 영역으로 공지한다 (스크린리더는 `aria-current`
  변경을 자동으로 읽지 않는다).

## 비기능 요구사항 (NFR)

- **NFR1** `shortcuts.test.ts` 의 `toHaveLength(5)` · `DEFAULT_KEYMAP` `toEqual` 이 **무수정
  green** 유지 — **이것이 이 PR 의 성공 판정식**이다.
- **NFR2** 백엔드 무변경 확인 — `KeymapAction.kt` · `V033__user_keymap.sql` diff 0.
- **NFR3** 커서 반응 <100ms 체감 (서버 왕복 0 — URL 갱신은 클라이언트 라우팅).
- **NFR4** WCAG 2.1 AA — `aria-current` 유지 + 커서 공지 + 키보드만으로 목록 항법 완결.
- **NFR5** 메모리 누수 0 — 리스너가 하나뿐이므로 기존 cleanup 경로를 그대로 탄다.

## API 인터페이스 (REST)

없음. 프론트 전용. 신규 엔드포인트 0 · 기존 API 호출 패턴 변경 0.

## 데이터 모델 변경

없음. 마이그레이션 0. `user_keymap` 무변경(사용자 재배치는 B4, 범위 밖).

## 엣지 케이스

- **E1** 목록이 비었을 때 `j`/`k`/`t` → 무동작.
- **E2** `?selected` 없을 때 `j`/`k` → 첫 행 선택 (S2).
- **E3** 첫 행에서 `k` → 무동작 (wrap 없음).
- **E4** 마지막 행에서 `j` → 무동작 (페이지 넘김 없음 — 범위 밖).
- **E5** `?selected` 가 현재 목록에 없을 때(필터/정렬 변경 직후) `j` → **첫 행**부터 시작.
- **E6** leader 대기 중(`g` 직후) `j` → 무동작 + 시퀀스 리셋 (S7, ADR D-2).
- **E7** 도움말 모달 열림 중 전 키 무동작 (S7).
- **E8** 명령 팔레트(`Cmd+K`) 열림 중 → 팔레트 입력창이 입력 포커스라 가드가 삼킨다.
- **E9** IME 조합 중 `ㅓ`/`ㅏ` → 무동작 (S8).
- **E10** 입력창/textarea/select/contentEditable 포커스 중 → 무동작.
- **E11** 비로그인 → 리스너 미등록 (기존 `enabled` 가드 그대로).
- **E12** `/issues` 밖에서 `j`/`k`/`o`/`t` → 무동작 (컨텍스트 비활성). `[` 만 살아 있다.
- **E13** ~~좁은폭에서 `t` → 2컬럼 전환은 없고 행 강조만 토글~~ → **정정 (구현 중 실측,
  2026-08-03).** `issues.index.tsx:918` 이 `selectedKey={isWide ? selected : null}` 이라
  **좁은폭에는 커서 강조 자체가 없다.** 이 스펙의 S4 기술은 틀렸다.
  **확정 동작 — 커서 단축키 4종(`j`/`k`/`o`/`t`)은 와이드 전용이다.** 어댑터가 좁은폭에서
  콜백을 `undefined` 로 끊어 조용히 무동작시킨다. 끊지 않으면 `j` 가 URL 만 바꾸고 화면은
  그대로인 유령 상태가 된다. `[`(사이드바)는 폭과 무관하게 산다.

## 제약 조건

- **`shortcuts.ts` 의 `SHORTCUTS`·`DEFAULT_KEYMAP` 수정 금지.** 4중 계약이 동시에 깨진다.
- 신규 의존성 도입 금지 (FR-UX-05 ADR D2 승계 — native keydown 만).
- 별도 `document` keydown 리스너 추가 금지 (ADR D-2).
- 새 커서 상태 신설 금지 (ADR D-3).
- 도움말 모달에 F11 미구현 키 표기 금지 (FR-UX-05 FR8).
- `Sidebar.tsx` 직접 수정보다 `use-sidebar-collapsed.ts` 소비를 우선한다.

### 요청 지연 — v1 미도입 (D2 확정)

ADR D-3 이 *"연타는 상태 분리가 아니라 요청 지연으로 푼다(구체 방식은 D2 확정)"* 로
남긴 항목을 여기서 닫는다. **v1 은 지연 장치를 넣지 않는다.**

근거 3가지. ① 측정 없는 선제 최적화를 피한다 ② TanStack Query 캐시가 재방문을 흡수한다
③ **지연을 넣으면 E2E 가 타이밍 의존이 되어 flaky 위험이 생긴다** — ADR D-3 이 상태 분리를
기각한 이유와 같은 논리다. `replace: true` 로 히스토리만 보호한다.

**후속 트리거.** 브라우저 눈확인(계약 §6)에서 연타 시 체감 지연·깜빡임이 관측되면 그때
`placeholderData` 또는 상세 페인 debounce 를 별건으로 검토한다.

## 시각 검증 기준 (ui 경량 경로 필수)

### 동반 실행할 기존 E2E — 사전 grep 결과 (계약 §5)

착수 시 아래를 실측해 영향 범위를 확정한다.

```bash
grep -rn "issues.index\|'/issues'" apps/web/e2e/          # 목록 화면 접촉 스펙 전수
grep -rn "selected" apps/web/e2e/issue-*.spec.ts          # split view URL 계약
grep -rn "aria-current\|data-state" apps/web/e2e/         # 커서 강조 셀렉터
grep -n "toHaveLength" apps/web/src/components/keyboard-shortcuts/shortcuts.test.ts
```

**UI 변경 PR 은 같은 화면을 검증하는 기존 E2E 를 함께 돌린다** (learnings 2026-05-31 #47 —
D6 이 E2E 를 미뤄 strict mode 회귀가 D7 까지 잠복한 사례).

### 브라우저 눈확인 (계약 §6 — 생략 금지)

조작감이 산출물이므로 코드 검증만으로 판정 불가.

- `/issues` 에서 `j`/`k` 연타 — 커서 이동, 상세 페인 갱신, 체감 지연 유무
- `o` 로 전체화면 진입, 뒤로가기로 복귀 시 커서 보존 확인
- `t` 로 페인 열고 닫기 (와이드/좁은폭 양쪽)
- `[` 로 사이드바 토글 — 새로고침 후 상태 보존
- `?` 도움말에 컨텍스트 5종이 보이고 F11 키는 안 보이는지
- **라이트/다크 양쪽**에서 커서 강조 대비 확인

## 측정 가능한 완료 기준

- [ ] `shortcuts.test.ts` **무수정** + green (NFR1 — 성공 판정식)
- [ ] 신규 `context-shortcuts.test.ts` — 레지스트리·판별 순수 함수·경계(E1~E5)
- [ ] `useContextShortcuts` 훅 테스트 — leader 대기 중 무동작(E6) · 도움말 열림 중 무동작(E7)
- [ ] E2E — `j`/`k` 커서 이동 + URL `?selected` 반영 · `o` 전체화면 · `t` 토글 · `[` 사이드바
- [ ] E2E 회귀 — 기존 `/issues` 접촉 스펙 전량 green (사전 grep 목록)
- [ ] 도움말 모달에 컨텍스트 5종 노출 + F11 키 **미노출** 단언
- [ ] 백엔드 diff 0 확인 (`KeymapAction.kt` · `V033__user_keymap.sql`)
- [ ] `pnpm typecheck && pnpm lint && pnpm test` green
- [ ] `bash scripts/verify-master-plan.sh` EXIT 0 (FR 139 불변)
- [ ] 브라우저 눈확인 완료 — 라이트/다크 양쪽, 관찰 요지를 게이트 2 요약에 기재

## Brainstorming Check

✅ 통과 (ui 경량 경로 — brainstorming 스킵, `## Jira 대조` + 즉사 계약 §2 교차가 대체).
office-hours 스킵 근거는 FR-UX-05 선례와 동형 — 범위/구현 Maxi 확정, 대상 파일 실재 확인,
ADR 3건 선확정. **착수 중 발견 2건을 정본에 반영 완료** — ① 이연 목록 `s` 누락(대사표로
`personalization.md §4.8` 에 복원, F11 배치) ② 계약 문서 `jira-parity-contract.md:39` 의
실측 명령 경로 오류(같은 PR 에서 정정 예정). Maxi 결정 필요 gap 0.
