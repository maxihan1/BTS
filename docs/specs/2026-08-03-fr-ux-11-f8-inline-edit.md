# FR-UX-11 F8 — 이슈 상세 인라인 편집 — 스펙

> FR ID. **FR-UX-11** (§4.9 personalization) · 승계 PR **F8** · slug `fr-ux-11-f8-inline-edit`
> type. ui · agent. frontend-engineer · 작성 2026-08-03
> 범위. **F8 만**. F9(이슈 목록 셀 인라인 편집)는 별도 PR (Maxi 확정 2026-08-03)

## 0. 착수 전 실측 — 정본이 뒤집혔다

정본 `docs/plan/product/personalization.md` §4.9 는 *"현재 BTS 는 인라인 편집이 **전무**해,
제목 한 글자를 고치려 해도 **폼 화면으로 이동**해야 한다"* 고 적었다. **거짓이다.**
기존 E2E 가 이 기능을 **이미 「인라인 편집」으로 지칭**한다(`issue-crud-happy.spec.ts:1,17` ·
`issue-edit-conflict.spec.ts:7`).

| 대상 | 진입 | 저장 | 취소 | OCC(낙관적 동시성) | 근거 |
|---|---|---|---|---|---|
| 제목 | 버튼 `✎ 제목 수정` | 버튼 `저장`(`data-testid=issue-title-save`) | 버튼 `취소` | **완비** — `expectedVersion` 전달, 409 시 toast + 편집모드 유지 | `issues.$key.tsx:698-726` · 핸들러 `:464-480` |
| 본문 | 버튼 `본문 편집` | 버튼 `저장` | 버튼 `취소` | `EditMode`(쓰기/미리보기 탭 + 멘션 자동완성) | `IssueDescription.tsx:123-144` · 핸들러 `:96-110` |

**따라서 F8 의 실제 잔여는 3가지다.**
① 텍스트 자체를 클릭한 진입 ② `Enter` 저장 ③ `Esc` 취소. (①②③ 모두 현재 **0건**)

이 전복은 #328·#331·#333·#336 에 이어 **5연속**이다. **정정 노트가 아니라 원문을 고친다**(F3 #333 선례).

## 1. 사용자 시나리오 (Given-When-Then)

- **S1 (제목 클릭 진입).** Given 이슈 상세를 열고 수정 권한이 있다.
  When 제목 텍스트를 클릭한다. Then 제목이 편집 입력창으로 바뀌고 **커서가 텍스트 끝에** 놓인다.
- **S2 (제목 Enter 저장).** Given 제목 편집 중이고 값을 바꿨다.
  When `Enter` 를 누른다. Then 저장되고 읽기 모드로 돌아간다 — 저장 버튼을 누른 것과 동일 경로.
- **S3 (제목 Esc 취소).** Given 제목 편집 중이고 값을 바꿨다.
  When `Esc` 를 누른다. Then 원래 제목이 복원되고 읽기 모드로 돌아간다. **패널은 닫히지 않는다.**
- **S4 (본문 클릭 진입).** Given 이슈 상세를 열고 수정 권한이 있다.
  When 본문 영역을 클릭한다. Then 본문 편집기(쓰기 탭)가 열리고 포커스가 입력 영역에 놓인다.
- **S5 (본문 Ctrl/Cmd+Enter 저장).** Given 본문 편집 중이다.
  When `Ctrl+Enter`(mac `Cmd+Enter`)를 누른다. Then 저장되고 읽기 모드로 돌아간다.
- **S6 (본문 Enter 는 줄바꿈).** Given 본문 편집 중이다.
  When `Enter` 를 누른다. Then **줄이 바뀔 뿐 저장되지 않는다.**
- **S7 (본문 Esc — 변경분 보호).** Given 본문 편집 중이고 내용을 바꿨다.
  When `Esc` 를 누른다. Then **확인을 거친 뒤** 취소한다. 확인을 거부하면 편집 모드가 유지된다.
- **S8 (본문 Esc — 변경 없음).** Given 본문 편집 중이고 내용을 안 바꿨다.
  When `Esc` 를 누른다. Then 확인 없이 즉시 읽기 모드로 돌아간다.
- **S9 (기존 버튼 보존).** Given 이슈 상세를 연다.
  When 화면을 본다. Then `✎ 제목 수정` · `본문 편집` 버튼이 **그대로 있고** 클릭하면 종전대로 진입한다.
- **S10 (권한 없음).** Given 수정 권한이 없다.
  When 제목/본문 텍스트를 클릭한다. Then **편집 모드로 들어가지 않는다** — 현재 버튼 비활성 규칙과 동일.

## 2. Jira 대조 (계약 §1 4단계)

### 2-1. 대응 화면 식별

Jira Cloud **이슈 상세(Issue view)** 의 Summary · Description 필드. BTS 의 `routes/issues.$key.tsx`
좌측 본문 영역이 같은 자리다.

### 2-2. 조작감 갭 — 공식/티켓 대조 실측 (2026-08-03)

| 항목 | Jira 실제 동작 | 확신도 | 출처 |
|---|---|---|---|
| Summary 클릭 진입 | 필드를 클릭하고 바로 입력 | 확실 | [Editing an Issue](https://confluence.atlassian.com/agile065/jira-agile-user-s-guide/working-with-issues/editing-an-issue) |
| Summary `Enter` 저장 · `Esc` 취소 | *"Press the Enter key to save your changes (or Esc to cancel)"* | 확실 | 〃 |
| Description 클릭 진입 | 동작하지만 **사용자 불만 대상** — 텍스트를 선택하려다 편집 모드로 들어간다 | 확실(결함 티켓) | [JRA-64389](https://jira.atlassian.com/browse/JRA-64389) · [JRA-29063](https://jira.atlassian.com/browse/JRA-29063) · [community](https://community.atlassian.com/forums/Jira-questions/How-do-I-disable-click-to-edit-description/qaq-p/2195913) |
| Description `Esc` | **확인 없이 작성분을 버린다.** Atlassian 은 개선을 **하지 않기로 결정** | 확실(결함 티켓) | [JRACLOUD-36670](https://jira.atlassian.com/browse/JRACLOUD-36670) · [JRACLOUD-41814](https://jira.atlassian.com/browse/JRACLOUD-41814) |

### 2-3. BTS 제약과 교차 — 의도적 편차 2건

**패리티(patiry, 동등성)의 목표는 「Jira 와 똑같이」가 아니라 「Jira 쓰던 손이 헤매지 않게」다.**
위 대조는 Jira 의 이 동작에 **공개된 미해결 결함이 2건** 있음을 보여준다. 그대로 베끼면
결함까지 복제한다. 계약 §1 3단계(제약과 교차)에 따라 2건을 편차로 확정한다.

- **편차 D-1. 본문 `Esc` 는 변경분이 있으면 확인을 거친다** (S7/S8).
  Jira 는 즉시 버리고 Atlassian 이 개선 거부를 공표했다(JRACLOUD-41814). BTS 는 확인을 넣는다.
  근거 없는 자체 발명이 아니라 **공개 결함 티켓을 근거로 한 의도적 개선**이다.
- **편차 D-2. 본문 클릭 진입은 텍스트 선택 중이면 발동하지 않는다** (E4).
  Jira 의 최대 불만(JRA-64389·JRA-29063)이 정확히 이 오발동이다. 드래그로 텍스트를 선택하는
  동작과 충돌하지 않게 `Selection.isCollapsed` 로 가른다.

제목은 편차 없이 Jira 그대로다 — 한 줄이라 오발동·소실 위험이 낮다.

## 3. 기능 요구사항 (FR)

- **FR1.** 제목 텍스트(`h1`/`h2`)를 클릭하면 편집 모드로 진입한다. 진입 시 커서는 텍스트 끝.
- **FR2.** 제목 편집 중 `Enter` 는 저장한다. 기존 `handleEditSave` 와 **같은 경로**를 탄다(OCC·409 처리 승계).
- **FR3.** 제목 편집 중 `Esc` 는 취소한다. 기존 `handleEditCancel` 과 같은 경로.
- **FR4.** 본문 읽기 영역을 클릭하면 편집 모드로 진입한다.
- **FR5.** 본문 편집 중 `Ctrl+Enter` / `Cmd+Enter` 는 저장한다. 맨 `Enter` 는 **줄바꿈**이며 저장하지 않는다.
- **FR6.** 본문 편집 중 `Esc` 는 취소하되, **초안이 원본과 다르면 확인**을 거친다.
- **FR7.** 기존 `✎ 제목 수정` · `본문 편집` 버튼은 **문자열·동작·`data-testid` 모두 불변**으로 유지한다.
- **FR8.** 수정 권한이 없으면 클릭 진입이 발동하지 않는다(기존 `canEdit` / `isEditDisabled` 재사용).
- **FR9.** 클릭 진입 요소는 **키보드로도 도달·발동 가능**해야 한다 — 마우스 전용 진입면을 만들지 않는다.
- **FR10.** 편집 취소(`Esc`)는 `preventDefault()` 로 소비해 **pane 닫기와 이중 발화하지 않는다.**

## 4. 비기능 요구사항 (NFR)

- **NFR1 (계약 보존).** `shortcuts.test.ts` 의 `toHaveLength(5)` 와 `DEFAULT_KEYMAP` 완전일치가
  **무수정 green**. 이 PR 은 `SHORTCUTS`·`CONTEXT_SHORTCUTS` 어느 레지스트리에도 키를 추가하지 않는다 —
  편집 중 키 처리는 **해당 입력 요소 지역 핸들러**이지 전역 단축키가 아니다.
- **NFR2 (E2E 무손상).** 기존 E2E 가 **한 줄도 수정 없이** 통과한다. 대상 실측 —
  `issue-crud-happy` · `issue-edit-conflict` · `issue-permission` · `issue-mention-render`.
- **NFR3 (캐시 정합).** 저장 후 캐시 갱신은 **invalidate-only** 를 유지한다. `setQueryData` 로
  PATCH 응답을 통째 교체하지 않는다 — `descriptionHtml` 이 null 로 덮여 본문이 깜빡인 사고
  선례(`learnings.md:616`, PR #46).
- **NFR4 (접근성).** 클릭 진입 요소는 `role`·포커스 링·키보드 발동을 갖춘다. WCAG AA 대비 유지.
- **NFR5 (시각 확인).** 라이트/다크 양쪽에서 브라우저 눈확인(계약 §6). 진입 어포던스(hover 상태)가
  두 테마에서 모두 식별 가능해야 한다.
- **NFR6 (백엔드 0).** 백엔드 · 마이그레이션 · 신규 API · 신규 의존성 모두 **0**.

## 5. API 인터페이스 (REST)

**변경 없음.** 기존 `PATCH /api/v1/issues/{key}` 를 그대로 소비한다
(제목 = `summary`, 본문 = `description`, 둘 다 `expectedVersion` 동반).

## 6. 데이터 모델 변경

**없음.** 마이그레이션 0. 클라이언트 상태만 다룬다.

## 7. 엣지 케이스

- **E1 (Esc 이중 발화).** pane variant 에서 편집 중 `Esc` → 편집만 취소되고 **패널은 열린 채**.
  `usePaneEscapeClose`(`issues.$key.tsx:76-92`)가 `e.defaultPrevented` 를 존중하므로(`:84`),
  편집 취소가 `preventDefault()` 하면 자동 해소된다. **가드 테스트 필수** — 이 규약이 깨지면 조용히 회귀한다.
- **E2 (본문 텍스트 선택).** 본문을 드래그해 복사하려 할 때 편집 모드로 들어가면 안 된다
  (편차 D-2). 판정은 `window.getSelection()?.isCollapsed`.
- **E3 (링크 클릭).** 본문 안의 링크를 클릭하면 **링크가 동작**해야 하고 편집 진입은 안 된다.
  **★ 정정 (2026-08-03 구현 중 실측).** 초안은 "링크나 **멘션**"으로 적었으나 멘션은 `<a>` 가
  아니다 — 백엔드 `MentionExtension.kt:121-123` 이 **`span.mention`** 으로 렌더하고 클릭 동작이
  아예 없다. 따라서 멘션 배제 가드는 **공허**하고, 오히려 막으면 클릭해도 아무 일 없는 죽은
  영역이 생긴다. 가드는 `closest('a')` 하나로 둔다. 훗날 멘션이 실제 링크가 되면 같은 가드가
  자동으로 커버한다.
- **E4 (제목 Enter 와 IME).** 한글 입력 중 조합 확정 `Enter` 가 저장으로 오인되면 안 된다 —
  `isComposing` / `keyCode 229` 를 배제한다.
- **E5 (저장 중 재입력).** `updateMutation.isPending` 동안 `Enter` 연타가 중복 제출을 만들면 안 된다.
- **E6 (409 충돌 중 Esc).** 409 로 편집 모드가 유지된 상태에서 `Esc` → 기존 취소 규칙대로 원본 복원.
- **E7 (권한 없는 클릭).** `canEdit === false` 에서 클릭 → 아무 일도 일어나지 않는다(에러 토스트 없음).
- **E8 (빈 제목 저장).** 제목을 비우고 `Enter` → 기존 저장 버튼과 **같은 검증 결과**여야 한다
  (새 우회로를 만들지 않는다).
- **E9 (본문 편집기 탭 전환).** 미리보기 탭에서 `Ctrl+Enter` → 쓰기 탭과 동일하게 저장.
- **E10 (멘션 자동완성 열림 중 키).** 멘션 팝업이 열린 상태의 `Enter`/`Esc` 는 **팝업이 먼저 소비**한다
  (`IssueDescription.tsx:281` `mention.onKeyDown` 선행). 편집 저장/취소로 새지 않아야 한다.

## 8. 제약 조건

- **C1.** `SHORTCUTS` 레지스트리 동결(계약 §2). 이 PR 은 전역/컨텍스트 단축키를 **추가하지 않는다**.
- **C2.** `<h1>` 단일 + 이름 verbatim(계약 §2). 제목을 클릭 가능하게 만들되 **heading role 과 텍스트를 보존**한다.
- **C3.** 기존 버튼의 문자열·`data-testid` 불변(FR7) — E2E 4종의 진입점이다.
- **C4.** 한 PR = 한 BC. 백엔드 0줄.
- **C5.** F9(목록 셀)·F11(상세 단축키)는 이 PR 범위 밖. 공용 추상화를 **선제적으로 만들지 않는다**
  (구현 접근 A, Maxi 확정 — 각자 최소 변경 + Esc 규약만 공용 헬퍼).

## 9. 측정 가능한 완료 기준

1. 신규 유닛 테스트가 FR1~FR10 을 각각 검증하고 **전부 green**.
2. 기존 E2E 4종(`issue-crud-happy` · `issue-edit-conflict` · `issue-permission` ·
   `issue-mention-render`)이 **파일 수정 0** 으로 통과.
3. `shortcuts.test.ts` **무수정 green**(NFR1) — `git diff --exit-code` 로 기계 확인.
4. 신규 E2E 가 S1~S10 중 최소 S1·S2·S3·S4·S5·S7 을 커버.
5. E1(Esc 이중 발화) 가드가 **비-공허**임을 뮤테이션으로 확인 — `preventDefault()` 를 제거하면
   그 테스트가 **실제로 빨강**이 되어야 한다.
6. `pnpm typecheck` 0 · `pnpm lint` 신규 0 · `pnpm build` 성공.
7. 브라우저 눈확인 라이트/다크 양쪽 완료, 결과를 PR 본문/게이트 2 요약에 기록(계약 §6).
8. 백엔드 diff 0줄 · 마이그레이션 0 · `package.json` diff 0.

## Brainstorming Check

✅ 통과 (ui 경량 경로 — brainstorming 스킵, `## Jira 대조` §2 + 즉사 계약 §2 교차가 대체).

**착수 중 발견 3건.**
① 정본 §4.9 의 "인라인 편집 전무" 서술이 거짓 — 원문 정정 대상(§0).
② `Esc` 가 pane 닫기에 이미 배정돼 있어 신규 충돌면(E1).
③ Jira 의 대응 동작에 **미해결 결함 티켓 2건** — 그대로 베끼면 결함 복제. 편차 D-1·D-2 로 확정(§2-3).

**Maxi 결정 완료 3건.** 범위(F8만) · 진입 방식(버튼 유지 + 클릭 추가) · 적용 대상(제목+본문) ·
구현 접근(A. 각자 최소 변경). **미해결 gap 0.**
