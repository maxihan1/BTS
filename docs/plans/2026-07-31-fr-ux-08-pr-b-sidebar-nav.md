<!-- FR-UX-08 PR-B (F17) 사이드바 "내 작업"·"최근 항목" 워크플로우 진행 기록 -->
# FR-UX-08 PR-B — 사이드바 "내 작업"·"최근 항목" + nav 라벨 전수 판별식 (F17)

> slug: fr-ux-08-pr-b-sidebar-nav
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트 `apps/web/**` 전용)
> 생성: 2026-07-31

## Brief

FR-UX-08 을 완주시키는 후속 PR. PR-A(#326, F12 프로젝트 스위처 + 트리 펼침 영속)가 머지돼
코드 파일 교집합 0 이 확보됐으므로 착수 가능해졌다.

**범위 정본** — `docs/specs/2026-07-30-fr-ux-08-project-switcher.md` §11 PR-B 행.

| 축 | 값 |
|---|---|
| 로드맵 | F17 |
| 담당 FR | FR2 · FR4 · FR12 · FR13 · FR14 · FR15 · **FR16(정본 전수 동기화)** |
| 시나리오 | S7 · S8 · S9 |
| 엣지 케이스 | E2 · E3 · E4 · E8 · E9 |
| 제약 | §8 `MAIN_NAV_LINKS` 항목이 이 PR 부터 적용 |
| plan 원안 | T2 · T4 · T6 · T9 (PR-A plan 에서 이미 분해) |

**착수 전 확정 전제 3건 (PR-A 에서 실측으로 뒤집힌 것).**

1. ★ `?assignee=me` 는 **실재하지 않는다**. `IssueFilterQueryParser` 센티널은 `unassigned` 뿐이고
   그 외 값은 UUID 파싱 실패 시 **400**. → `?assignee=<whoami.userId>`.
2. ★ "최근 항목" = 최근 본 **이슈** (프로젝트 아님). 사이드바에 `ProjectTree` 전체 목록이 이미 있어 중복.
3. ★ FR16 이 PR-B 소관 → D 마커 `[x]` + 진척 132→133 + `verify-master-plan.sh` EXIT0 이 머지 조건.

classify 결과. `type=ui` / `agent=frontend-engineer` / `slug=fr-ux-08-pr-b-nav-f17-ui`
(브랜치 접두사가 이미 `ui/` 라 슬러그 말미 `-ui` 중복을 제거해 `fr-ux-08-pr-b-sidebar-nav` 로 사용).

## 도메인 정리

- **논리 BC. personalization / 물리. `apps/web`** — ADR §D6 승계 (FR-UX-05 D4 · FR-UX-06 D5 · FR-UX-07 D2).
  `classify-task.ts` 는 `primary_bc=issue-tracking` 으로 분류했으나 변경 파일이 전량 `apps/web`,
  `backend/**` 0건이라 PR-A 와 동일하게 정정한다.
- **영향 엔티티.** 신규 0. 프론트 전용 개념 2종(아래 용어)만 추가.
- **기존 결정 충돌.** 없음. 이 PR 이 다루는 D1·D3·D4 는 ADR `2026-07-30-fr-ux-08-project-switcher.md`
  에서 이미 확정됐고 PR-B 는 그 **하위집합**이다.
- **관련 ADR.** [2026-07-30-fr-ux-08-project-switcher](../decisions/2026-07-30-fr-ux-08-project-switcher.md)
  (§D1 최근 항목=이슈 · §D3 상한5·MRU·방문 시 자동기록 · §D4 키만 저장 · §D6 BC) ·
  [2026-07-28-fr-ux-07-active-project-context](../decisions/2026-07-28-fr-ux-07-active-project-context.md) ·
  [2026-07-17-fr-ux-06-jira-redesign](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (nav 라벨·S3 도입)
- **신규 ADR.** 없음. PR-A 의 ADR 이 PR-B 결정을 이미 담고 있어 새로 만들면 정본이 둘이 된다.

### grill-with-docs 미호출 (사유 명시)

이 단계의 정규 절차는 `grill-with-docs` 호출이나 **호출하지 않았다.** ADR 이 착수 조사 4개 질문
(최근 항목의 대상 · 트리 펼침 충돌 · 상한/정렬/기록시점 · 저장 범위)을 D1~D5 로 전부 닫았고,
PR-B 에서 **새로 열리는 도메인 질문이 0건**이다. 이미 확정된 결정을 재심하는 것은
「이전 세션의 정착된 결정을 조용히 재litigate 하지 않는다」에 어긋난다.
대신 아래 **실측 3건**으로 도메인 전제가 코드와 여전히 일치하는지 확인했다.

### ★실측 3건 (PR-B 착수 전제 검증)

**M1. `navLabels` S3 회귀 가드가 이 PR 로 반쯤 뒤집힌다 — 절반만 지우면 봉인이 깨진다.**

`apps/web/src/i18n/__tests__/nav-labels.test.ts:55-71` 의 `S3 — 백킹 없는 항목 제외 회귀 가드` 가
**4개 키의 부재**를 단언한다 — `myWork` · `recent` · `filters` · `projects`.
PR-B 는 이 중 **`myWork`·`recent` 두 개를 추가**한다 (ADR §충돌표 4행이 *"충돌 아님 — 이 FR 이 그 추가 시점"* 으로 이미 승인).
남은 **`filters`·`projects` 두 개는 계속 부재여야 한다** (백킹 라우트 없음).

⇒ **describe 블록째 삭제는 오답.** 그러면 `filters`·`projects` 가 가드를 잃는다 —
메모리 「봉인은 절반만 닫힌다」의 정확한 재현. **부분 반전**(2개는 존재 단언으로 전환, 2개는 부재 유지)이 맞고,
그 위에 이번 작업의 목표인 **목록 제거형 전수 판별식**을 얹는다.

**M2. `nav-labels` 테스트 파일이 두 벌이다 (선재).**

| 파일 | 줄수 | 범위 | 출처 |
|---|---|---|---|
| `apps/web/src/i18n/__tests__/nav-labels.test.ts` | 72 | e2e 계약 4종 고정 + 라벨 존재 7종 + **S3 제외 가드 4종** | FR-UX-06 PR11 |
| `apps/web/src/i18n/nav-labels.test.ts` | 27 | `breadcrumb` 신규 키 + substring 비충돌 | FR-UX-06 PR13 |

같은 대상을 두 파일이 나눠 보고 있어 **"어느 쪽에 전수 판별식을 두는가"** 가 plan 단계 결정 사항이다.
한쪽에만 두면 다른 쪽을 고치는 사람이 판별식을 못 본다(「두 목록이 서로를 안 본다」 양식).
**이 PR 이 만든 상황이 아니라 선재 상태**임을 명시한다.

**M3. glossary 신규 용어 2종이 아직 미등재다.**

ADR §신규 용어 표가 **최근 프로젝트**(Recent Projects) · **최근 본 이슈**(Recent Issues) 를
glossary 등재 대상으로 지정했으나, `Maxi_wiki/BTS/glossary.md` 실측 결과 **둘 다 없다**
(PR-A 가 「최근 프로젝트」를 스위처 정렬 축으로 실제 구현했는데도 미등재).
glossary 는 `_index.md` §동기화 규칙상 **수동 영역**(자동 갱신 안 함)이라
**Maxi 승인이 필요** → 게이트 1 안건으로 올린다.



## 스펙

전체 스펙. [docs/specs/2026-07-30-fr-ux-08-project-switcher.md](../specs/2026-07-30-fr-ux-08-project-switcher.md)
— **신규 작성이 아니라 기존 정본에 PR-B 공백을 보강**했다 (아래 G1~G4).

핵심 시나리오 3줄 요약.
- **S7** 사이드바 "내 작업" → 활성 프로젝트에서 내가 담당인 이슈 목록
- **S8** 사이드바 "최근 항목" → 최근 본 이슈 5건이 MRU 순으로 제목과 함께
- **S9** 삭제·권한회수된 이슈는 조용히 목록에서 탈락하고 사이드바는 살아 있다

### Phase A — office-hours / design-* 미호출 (사유 명시)

- **design-consultation 스킵.** `DESIGN.md` 실재(프로젝트 첫 UI 작업 아님).
- **design-shotgun 스킵.** 새 화면이 아니라 **확립된 디자인 시스템 위의 링크 2종 추가**.
  스펙 §Brainstorming Check 이 PR-A 에서 같은 사유로 이미 스킵을 기록했고 PR-B 도 동일 조건.
- **office-hours 스킵.** 스펙 정본이 이미 존재하고 FR 이 확정돼 있다. YC 아이디어 검증 프레임이
  맞지 않는다(2026-05-29 Maxi 확정 · FR-UX-07 선례 승계, 스펙 §Brainstorming Check 에 명문화).
- 대신 이 단계가 한 일은 **PR-B 범위 추출 + 실측 기반 공백 보강**이다.

### ❓ Brainstorming 발견 — 스펙 공백 4건 (전량 보강 완료)

**G1 (BLOCKER 급). S3 제외 회귀 가드가 스펙 어디에도 없었다.**
FR14 는 `nav-labels.ts` **주석**만 고치라 했고, FR15 는 `i18n/nav-labels.test.ts` 만 교체 대상으로 지목했다.
그런데 `myWork`·`recent` 추가로 **실제 red 가 되는 파일은 `i18n/__tests__/nav-labels.test.ts:55-71`** 이고
스펙에 단 한 번도 등장하지 않는다. 구현자가 "테스트가 깨졌으니 지운다" 로 가면
**`filters`·`projects` 가드가 함께 소실**된다.
⇒ **FR14-b 신설** — 부분 반전(2건 존재 단언 전환 / 2건 부재 유지) + 근거 주석 + 양방향 실증.

**G2. FR15 판별식의 거처가 미지정 + nav 라벨 테스트가 3벌인 사실이 스펙에 없었다.**
① `i18n/nav-labels.test.ts` 27줄 ② `i18n/__tests__/nav-labels.test.ts` 72줄 ③ `layout/__tests__/navigation-contract.test.tsx` 167줄.
①②는 **같은 상수를 대상으로 하면서 서로를 참조하지 않는다**.
⇒ **FR15-b 신설** — ①을 ②로 흡수해 상수 테스트를 한 파일로 통일, 판별식을 거기 둔다. ③은 렌더 계약이라 유지.

**G3. 두 신규 항목의 「배치」가 미지정이었다.**
FR13 이 "섹션" 이라 부르는데, 새 `<nav>` 를 만들면 ADR §D5 · NFR3 · `navigation-contract.test.tsx:60`
aria-label 4종 가드가 **동시에** 깨진다.
⇒ **FR13-b 신설** — 기존 `메인 메뉴` `<nav>` 안에 렌더. 근거는 실측 선례
(`Sidebar.tsx:93-101` 이 이미 `MAIN_NAV_LINKS` 3링크 + `<FavoritesMenu/>` 를 같은 nav 안에 담고 있다).

**G4. §9 완료 기준이 PR-A/PR-B 혼재라 이 PR 의 게이트로 쓸 수 없었다.**
E7 회귀가드 · `ProjectTree` 덮어쓰기 0건 · `useResolvedActiveProject` 참조는 전부 PR-A 소관(완료).
⇒ **§11-B PR-B 전용 완료 기준 신설** (14항목).

**G5(부수). glossary 등재 2종 미이행** → **FR16-b 신설**. 수동 영역이라 **Maxi 승인 필요 → 게이트 1 안건**.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 gap 5건 전량을 **스펙 정본 보강**으로 해소했고
(FR13-b · FR14-b · FR15-b · FR16-b · §11-B), Maxi 결정이 필요한 1건(G5 glossary 등재)만
게이트 1 안건으로 남겼다. Phase A↔B 루프 재진입 없음.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
