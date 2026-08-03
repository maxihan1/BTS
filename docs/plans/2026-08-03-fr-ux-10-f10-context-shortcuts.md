# FR-UX-10 F10 — 컨텍스트 단축키 아키텍처 + 목록 항법

> slug: fr-ux-10-f10-context-shortcuts
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-03
> FR: FR-UX-10 (`docs/plan/product/personalization.md §4.8`)

## Brief

**사용자 원문**. "fr-ux-10 진행해줘"

### 범위 확정 (Maxi 확정 2026-08-03)

FR-UX-10 은 승계 PR 2건(F10 · F11)인데 로드맵 정본
[`docs/design/jira-parity-roadmap.md:63`](../design/jira-parity-roadmap.md) 이
**F11 의존 = F10 + F8** 로 명시한다. F8(이슈 상세 인라인 편집)은 §4.9 FR-UX-11
소속이고 **미착수**라 F11 의 `e`(편집) 키가 갈 곳이 없다.

따라서 **이번 PR 은 F10 만** 담고 FR-UX-10 의 D6/D7 은 `[ ]` 로 유지한다.
완주 순서는 `FR-UX-11(F8 → F9) → FR-UX-10 F11`. FR-UX-09 가 B1 #328 · F2 #331 ·
F3 #333 3 PR 로 완주한 것과 같은 패턴이다.

### F10 산출물

로드맵 정본 `jira-parity-roadmap.md:62` 기준.

- 신규 `context-shortcuts.ts` — `CONTEXT_SHORTCUTS` **별도** 레지스트리
- 신규 `useContextShortcuts.ts` 훅
- 목록 항법 `j` / `k` / `o` / `t` / `[`
- `ShortcutsHelpDialog.tsx` 확장

> 이관 전 사본(`~/.claude/plans/ui-ux-sorted-kay.md:90`)은 대상에 `Sidebar.tsx` 를
> 포함했으나 레포 정본에는 없다. #335 가 이관하며 stale 을 정정했으므로
> **레포 정본을 따른다** — 사이드바 변경 필요는 스펙 단계에서 별도 판정한다.

### 🛑 깨면 즉사하는 계약

기존 `shortcuts.ts` 의 `SHORTCUTS` 를 **건드리지 않는다**. 여기에 키를 추가하면

1. `shortcuts.test.ts:121` `expect(SHORTCUTS).toHaveLength(5)`
2. `shortcuts.test.ts:147` `DEFAULT_KEYMAP` 완전일치
3. 백엔드 `KeymapAction.kt` 5종 화이트리스트
4. `user_keymap.action` CHECK 제약

이 **동시에** 깨진다. 4중 계약의 소유자는 §3.3 FR-PF-03 이다.
**성공 판정식 = `shortcuts.test.ts` 의 `toHaveLength(5)` 가 무수정 green 유지.**

### ★ 착수 중 발견 — 계약 문서의 실측 명령이 고장나 있다

`docs/design/jira-parity-contract.md:39` "단축키 레지스트리 동결" 행의 실측 명령이
**존재하지 않는 경로**를 가리킨다.

```
문서:  grep -n "toHaveLength" apps/web/src/lib/shortcuts.test.ts
실행:  ugrep: warning: ... No such file or directory   ← 종료코드 2
실제:  apps/web/src/components/keyboard-shortcuts/shortcuts.test.ts
```

계약 §2 는 *"개수 리터럴은 stale 해지는 순간 거짓이 된다 — 각 행의 명령으로 착수 시점에
실측하라"* 로 실측을 강제하는데, **그 실측 명령 자체가 실측을 못 한다.** 지시대로
실행한 사람은 0건을 보고 "계약 없음" 으로 오판한다. 이번 PR 이 지켜야 할 바로 그
계약이므로 **같은 PR 에서 경로 1줄을 정정**한다.
(계열 교훈 — `discriminant-erases-its-own-evidence` · `two-lists-never-check-each-other`)

### 범위 밖

사용자 키 재배치(로드맵 B4 — `KeymapAction` enum + `user_keymap` CHECK 신규
마이그레이션 필요)는 v1 제외. **고정 키로 출시**한다.

### 불변량

백엔드 변경 0 · 마이그레이션 0 · FR 수 불변 139 · 신규 API 0 · `SHORTCUTS` 무변경.

**classify 결과**. type=ui · agent=frontend-engineer · primary_bc=null — 논리 소속은
personalization, 물리 구현은 `apps/web` (ADR §D2, FR-UX-05 D4 · FR-UX-06 D5 선례 승계).

## 도메인 정리

- **BC**. 논리 = personalization / 물리 = `apps/web` 프론트 전용. **백엔드 변경 0**이라
  BC 침범 없음. 단 `SHORTCUTS` 는 identity-access 의 `KeymapAction` 과 1:1 계약이 걸려 있어
  **건드리면 BC 격리 위반**이 된다 — 이것이 즉사 계약의 정체다.
- **영향 개념**. `SHORTCUTS`(전역 5종, 무변경) · `CONTEXT_SHORTCUTS`(신규) ·
  `selectedKey`(split view 선택 → **항법 커서 역할 겸함**)
- **새 용어**. 없음 (glossary 미등재 — D-1 참조). "컨텍스트 단축키" 는 도메인 개념이 아니라
  UI 구현 세부로 판정. 다만 **전역 단축키/`KeymapAction` 이 glossary 에 0건**인 것은
  별건 등재 후보로 남긴다.
- **기존 결정 충돌**. 없음. FR-UX-05(전역 5종) · FR-PF-03(키맵 커스터마이즈) ·
  FR-UX-06 PR20(split view) 셋 다 **확장 방향으로 승계**한다.
- **관련 ADR**. [2026-08-03-fr-ux-10-f10-context-shortcuts](../decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md) (생성됨)

### 확정 3건 (ADR 본문 참조)

| | 결정 | 한 줄 근거 |
|---|---|---|
| **D-1** | `CONTEXT_SHORTCUTS` 별도 레지스트리 — 경계는 **도메인 지위 차이** | 전역=영속(`user_keymap`)·identity-access 소속 / 컨텍스트=영속 0·화면 지역 규약. B4 승격 경로가 열림 |
| **D-2** | **단일 리스너·단일 판별 파이프라인** (전역 우선 → 컨텍스트 폴백) | 리스너 2개면 ① `g`+`j` 가 `reset`(preventDefault 없음)을 타고 새 커서까지 발화 ② `helpOpen` 이 훅 내부 state 라 모달 열림 중 배후 목록이 움직임 |
| **D-3** | **항법 커서 = 기존 `selectedKey` 재사용** (Maxi 확정) | 지라 이슈 네비게이터 동형. 선택 개념 증식 0 · 강조 표기(`aria-current`) 자산 그대로 · 연타는 상태 분리가 아니라 **요청 지연**으로 해소 |

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
