# ADR — FR-UX-08 프로젝트 스위처 · 최근 항목 · 내 작업

> 날짜: 2026-07-30
> 상태: 결정됨 (Maxi 확정)
> 관련 FR: FR-UX-08 (본 문서) · FR-UX-07 (활성 프로젝트 컨텍스트 — 선행, PR #320) · FR-UX-06 (시각계층 개편 — ProjectTree·nav 라벨 도입)
> 관련 slug: `fr-ux-08-project-switcher`

## 맥락

FR-UX-07(PR #320)이 **활성 프로젝트**(Active Project — "사용자가 지금 작업 중인 프로젝트", 4단 해소 함수)라는 컨텍스트를 세웠다. 그런데 그것을 **손으로 바꿀 UI 가 없다.** 사용자가 다른 프로젝트로 옮기려면 URL 을 직접 고치거나 프로젝트 트리에서 해당 프로젝트의 보드로 들어가는 우회로밖에 없다.

이 FR 은 로드맵 §PR 체인 Tier 2 의 **F12**(프로젝트 스위처 + 트리 펼침 영속)와 **F17**(사이드바 "내 작업" + "최근 항목")을 승계한다.

착수 조사에서 네 가지가 결정을 요구했다.

1. 사이드바 "최근 항목"이 **무엇의** 목록인가 — 정본 §4.6 D1 은 "최근 프로젝트"라 적었으나 사이드바에는 이미 전체 프로젝트 트리가 있다.
2. "트리 펼침 영속"이 **기존에 문서화된 반대 동작**(라우트 이동 시 수동 펼침 리셋)과 충돌한다.
3. "최근" 목록의 상한·정렬·기록 시점.
4. 최근 이슈를 브라우저에 저장할 때 **무엇까지** 저장하는가.

## 조사가 뒤집은 것 — `?assignee=me` 는 실재하지 않는다

정본 `personalization.md` §4.6 이 v1 "내 작업"을 *"활성 프로젝트 스코프(`?assignee=me`)"* 로 적었다. **`me` 센티널은 없다.**

`IssueFilterQueryParser.kt:42` 가 인정하는 센티널은 `SENTINEL_UNASSIGNED = "unassigned"`(대소문자 구분) 하나뿐이고, 그 외 모든 `assignee` 값은 `parseUuid(value, "assignee")` 로 파싱한다. **파싱 실패 시 `ResponseStatusException`(BAD_REQUEST) 400.** `?assignee=me` 를 보내면 400 이다.

**대체 경로는 실재하고, 선례도 있다.** `WhoamiResponseSchema`(`api/schemas.ts:22`)가 `userId` 를 담고 있고, 같은 일을 하는 코드가 이미 있다 — `useGadgetData.ts:92-107` 의 `fetchAssignedToMe(config, userId)` 가 `filter.assigneeIds: [userId]` 로 조립한다(FR-DB-02 `assigned_to_me` 가젯).

⇒ **"내 작업" = `/issues?projectKey=<활성>&assignee=<whoami.userId>`.** 표기만 정정되고 **"신규 API 0" 전제는 유지된다**(백엔드 Kotlin 0줄). 정본 §4.6 본문의 `?assignee=me` 표기는 같은 PR 에서 정정한다.

> 이 확인은 learnings 2026-07-17(*"도메인·서비스·repo 가 다 있어도 REST 노출이 없으면 기능이 없는 것"*)의 적용이다. 정본 문구를 그대로 믿고 구현했으면 400 이 나는 링크를 사이드바에 박을 뻔했다.

## 결정

### D1. 사이드바 "최근 항목" = 최근 본 **이슈**. "최근 프로젝트"는 스위처 **안에서만** 쓴다

**정본 §4.6 D1 의 "최근 프로젝트(Recent Projects)" 를 사이드바 항목의 정의로는 채택하지 않는다.** 그 용어는 **스위처 내부 정렬**의 정의로 살아남고, 사이드바 "최근 항목"은 **최근 본 이슈**가 된다.

**근거.**
- **사이드바에 이미 전체 프로젝트 목록이 있다.** `ProjectTree`(`components/layout/ProjectTree.tsx`)가 `useProjects()` 전량을 렌더하고 최상단에 "모든 프로젝트" 링크까지 둔다. 그 바로 아래 "최근 항목"이 또 프로젝트 목록이면 **같은 화면에 프로젝트 목록이 두 벌**이다 — 현재 dev 규모(프로젝트 3~4개)에서는 사실상 동일한 목록이 두 번 보인다.
- **지라의 사이드바 Recent 도 이슈다.** 프로젝트 전환은 스위처가 맡고, "방금 보던 것으로 되돌아가기"는 이슈 단위 수요다. 두 기능이 겹치지 않는다.
- **"최근 프로젝트"는 버려지지 않는다** — 스위처 목록의 **정렬 축**으로 쓴다(D3). 즉 이 결정은 개념을 버리는 게 아니라 **표시 자리**를 나눈 것이다.

**감수하는 것.** 사이드바에 새 기록 장치가 하나 더 생긴다(`use-recent-issues`). 프로젝트만 추적했으면 훅 하나로 끝났을 일이 둘이 된다.

### D2. 트리 펼침은 **영속이 우선**. 활성 프로젝트 자동펼침은 **더하기만** 한다 (덮어쓰기 폐지)

**기존 동작을 정정한다.** `ProjectTree.tsx` 의 `useEffect` 가 `activeProjectKey` 가 바뀔 때마다 `setExpandedKeys(new Set([activeProjectKey]))` 로 **펼침 집합 전체를 갈아엎는다.** JSDoc 이 이를 *"라우트가 바뀌면 이 자동펼침 집합을 다시 계산해 이전 수동 펼침을 덮어쓴다(FR5)"* · *"수동 펼침은 ephemeral `useState` 이며 영속하지 않는다"* 로 **의도된 동작으로 명시**하고 있다.

**새 동작.**
- 펼침 집합은 localStorage 에 영속한다.
- 활성 프로젝트가 바뀌면 그 키를 집합에 **추가**한다(`add`). **다른 키를 제거하지 않는다.**
- 사용자가 디스클로저를 접으면 그 상태가 유지된다 — 라우트 이동이 다시 펼치지 않는다.

**근거.**
- **"내가 접은 게 왜 다시 열리나"는 명백한 사용성 결함이다.** 지라·VS Code·파일 탐색기 어느 것도 라우트/선택 변경으로 사용자의 접기를 되돌리지 않는다.
- F12 의 "트리 펼침 영속"이 이 FR 의 명시 범위다. 기존 FR5 를 그대로 두면 F12 의 절반이 미구현으로 남는다.
- 덮어쓰기를 **더하기로 바꾸는 것만으로** 자동펼침의 원래 목적("현재 프로젝트가 보이게 한다")은 100% 보존된다. 잃는 기능이 없다.

**★ 이것은 FR-UX-06 PR12 의 FR5 를 뒤집는 정정이다.** 조용히 바꾸지 않고 여기 명시한다 — `ProjectTree.tsx` JSDoc 과 `ProjectTree.test.tsx` 의 해당 단언도 같은 PR 에서 함께 정정해야 하며, **"테스트가 깨졌으니 되돌린다"가 아니라 "결정이 바뀌었으니 테스트를 고친다"** 가 맞다.

**감수하는 것.** 프로젝트가 많은 사용자가 여러 개를 펼쳐 두면 사이드바가 길어진다. 상한은 두지 않는다 — 사용자가 스스로 접을 수 있고, 그 접기가 이제 유지되기 때문이다.

### D3. "최근" 상한 **5** · **방문 시 자동 기록** · MRU 정렬 (프로젝트·이슈 공통)

- **상한 5.** 초과 시 가장 오래된 항목을 밀어낸다(LRU 축출).
- **정렬 = MRU**(Most Recently Used — 가장 최근 방문이 맨 위).
- **기록 시점 = 방문 시 자동.** 사용자의 명시 행동을 요구하지 않는다.
  - **프로젝트** — 기존 `useTrackActiveProject`(`hooks/use-track-active-project.ts`)의 **기록 지점을 확장**한다. 이 훅은 이미 `ShellLayout` 에 단독 배선돼 있고, URL 이 프로젝트를 담을 때 활성값을 갱신하며, **접근 가능 목록 대조 가드**(`isKnownProject`)를 갖고 있다.
  - **이슈** — `/issues/$key` 라우트(`router.ts:183`) 방문 시 기록한다.

**근거.**
- **기록 지점을 하나로 유지한다.** 프로젝트 기록을 새 훅에 또 만들면 저장값 생산 지점이 둘이 된다 — FR-UX-07 코드리뷰 CR3 가 정확히 그 문제(*"저장값을 쓰는 생산 지점이 둘인데 목록 대조 가드가 한쪽에만 있었다"*)로 걸렸다. `useTrackActiveProject` 확장이 그 재발을 막는다.
- **자동 기록이라야 첫 사용에 값이 있다.** 명시 선택만 기록하면 스위처를 한 번도 안 쓴 사용자에게 목록이 영원히 비어 기능의 존재를 모른다.
- 상한 5 는 사이드바 세로 공간과 "최근"의 의미(전체 목록의 대체가 아님) 사이의 값이다.

### D4. 최근 이슈는 **키만 저장**한다. 제목은 마운트 시 조회한다

localStorage 에 저장하는 것은 **이슈 키 문자열 5개**(`["ATLAS-12","MIDDLE-3",…]`)뿐이다. 표시용 제목은 저장하지 않고 사이드바가 뜰 때 조회한다.

**근거 — 유출.**
- **업무 내용이 `localStorage` 에 저장된 전례가 0건이다.** 실측한 기존 사용처는 전부 화면 설정값이다 — `bts.theme`(`lib/theme.ts:19`) · `bts.sidebar.collapsed` · `bts.active-project` · `issue-table-columns`(`routes/issues.index.tsx:61`) · `timeline-zoom` · 열 표시 설정. 이슈 제목은 성격이 다르다.
- **로그아웃이 `localStorage` 를 지우지 않는다.** `authStore.ts:34-37` 의 `clearSession()` 은 `sessionStorage.removeItem('bts.auth')` 만 한다. 제목을 저장하면 **같은 브라우저를 쓰는 다음 사용자가 이전 사용자의 이슈 제목을 읽는다.**
- 절대 규칙 §1.18 은 **토큰**을 대상으로 하므로 이 결정을 직접 강제하지는 않는다. 그러나 §1.18 의 취지(민감값을 영속 스토리지에 두지 않는다)와 위 전례 부재를 함께 보면 **키만 저장이 이 코드베이스의 일관된 답**이다.

**근거 — 자가 치유.**
- 삭제됐거나 권한이 회수된 이슈는 조회가 **403/404** 로 떨어져 목록에서 자동 탈락한다. 별도 stale 정리 로직이 필요 없다.
- 이는 FR-UX-07 D4 가 localStorage 를 택한 근거(*"값이 stale 해도 해소 순서 ③이 자동 복구한다"*)와 **같은 철학**이다.

**감수하는 것.** 하드 리로드 직후 사이드바가 최대 5건을 조회한다. 세션 중 이동은 `routes/issues.$key.tsx:186` 의 `useQuery` 캐시에 적중하므로 실 요청은 그보다 적다. 조회 실패 항목은 조용히 숨기고 사이드바 전체를 막지 않는다(`ProjectTree` 의 에러 시 `null` 반환과 같은 fail-safe 관례).

### D5. 스위처는 `role="listbox"`. **`<nav>` 로 만들지 않는다** (정본 승계)

정본 §4.6 의 제약을 그대로 채택한다. `components/ui/popover.tsx`(현재 소비처 0) + `role="listbox"` 로 만든다.

**근거.** `navLabels.projectNav = '프로젝트'` 가 `navLabels.projectViewNav = '프로젝트 뷰 전환'` 의 substring 이고, Playwright `getByRole` 은 기본이 substring 매칭이다(`nav-labels.ts:23-26` 이 이미 경고). 스위처를 또 하나의 `<nav>` 로 만들면 `getByRole('navigation')` 조회가 3중으로 겹쳐 기존 e2e 계약이 깨진다.

**배치.** 정본이 지목한 대로 `TopBar.tsx`. 사이드바 최상단은 이미 `ProjectTree` 가 점유하고 있고, 상단바는 프로젝트 컨텍스트를 전 화면에서 일정한 자리에 노출한다.

### D6. 논리 BC = personalization / 물리 = `apps/web` (선례 승계)

FR-UX-05 D4 · FR-UX-06 D5 · FR-UX-07 D2 를 그대로 승계한다. 백엔드 변경 0.

> `classify-task.ts` 는 이 작업을 `primary_bc=issue-tracking` 으로 분류했다. 변경 파일이 전량 `apps/web`, `backend/` 0건임을 실측해 정정했다(`.bts-cache/classify.fr-ux-08-project-switcher.json` 에 정정 사유 기록).

## 신규 용어 (glossary 등재 대상)

| 용어 | 정의 |
|---|---|
| **최근 프로젝트** (Recent Projects) | 사용자가 최근 방문한 프로젝트 키의 MRU 목록. 상한 5, LRU 축출. `useTrackActiveProject` 가 URL 이 프로젝트를 담을 때 자동 기록하며 **접근 가능 목록에 없는 키는 기록하지 않는다**. localStorage 영속. **프로젝트 스위처(F12) 목록의 정렬 축**으로만 쓰이고 사이드바에는 노출하지 않는다(§D1). |
| **최근 본 이슈** (Recent Issues) | 사용자가 최근 방문한 이슈 키의 MRU 목록. 상한 5, LRU 축출. `/issues/$key` 방문 시 자동 기록. **키만 영속하고 제목은 저장하지 않는다** — 표시용 제목은 마운트 시 조회하며 403/404 항목은 자동 탈락한다(§D4). 사이드바 "최근 항목"의 데이터 소스. |

## 기존 결정과의 충돌

| 충돌 | 처리 |
|---|---|
| FR-UX-06 PR12 **FR5** — *"라우트가 바뀌면 자동펼침 집합을 다시 계산해 이전 수동 펼침을 덮어쓴다"* | **정정한다**(§D2). `ProjectTree.tsx` JSDoc · `ProjectTree.test.tsx` 해당 단언을 같은 PR 에서 함께 갱신 |
| 정본 `personalization.md` §4.6 — *"v1 은 활성 프로젝트 스코프(`?assignee=me`)"* | **표기 정정**. `me` 센티널 부재, `?assignee=<whoami.userId>` 로 교체 |
| 정본 `personalization.md` §4.6 D1 — *"최근 프로젝트(Recent Projects) 목록의 정의"* | **범위 재배치**(§D1). 용어는 유지하되 사이드바 노출 대상은 최근 본 이슈 |
| `nav-labels.ts:9` **S3** — *"백킹 라우트·기능이 없는 항목(내 작업·최근·필터)은 포함하지 않는다. 각 항목은 해당 기능 FR 에서 추가한다"* | **충돌 아님 — 이 FR 이 그 "추가 시점"이다.** 두 항목 모두 실 라우트를 갖는다 |

## 선재 갭 (이 FR 이 함께 닫는다)

**`ProjectTree` 의 자동펼침이 활성 프로젝트를 보지 않는다.** `ProjectTree.tsx:369` 가 `useParams({ strict: false }).projectKey`(URL **경로 파라미터**)만 읽는다. FR-UX-07 이 만든 활성 프로젝트 해소(`useResolvedActiveProject`)를 참조하지 않으므로 `/issues?projectKey=MIDDLE`(검색 파라미터)에서는 트리가 **전부 접힌 채**로 뜬다 — 사용자가 MIDDLE 로 작업 중인데 트리는 아무것도 활성으로 표시하지 않는다. §D2 의 자동펼침 소스를 해소값으로 교체하면 함께 닫힌다.

## 결과

**좋아지는 것.** 프로젝트 전환이 클릭 한 번이 된다(현재는 URL 직접 편집 또는 트리 우회). 접은 트리가 유지된다. "내 작업"·"최근 항목"이 사이드바에서 백킹 라우트를 갖고 살아난다. `?projectKey=` 검색 파라미터 경로에서도 트리가 활성 프로젝트를 표시한다.

**감수하는 것.** localStorage 키가 3개 늘어난다(펼침 집합·최근 프로젝트·최근 이슈). 하드 리로드 직후 사이드바가 최대 5건을 조회한다. FR-UX-06 PR12 의 FR5 가 정정되어 관련 테스트를 고쳐야 한다.

**남는 것.** cross-project "내 작업"(로드맵 B3 — `PROJECTS.KEY.eq(projectKey)` 단일 축 하드코딩, 합집합 조립은 fail-open 위험). 서버 영속(`user_preferences`) — FR-UX-07 D4 가 기각하고 후속 FR 후보로 남긴 것을 그대로 승계한다.

## 관련

- [FR-UX-07 활성 프로젝트 컨텍스트](2026-07-28-fr-ux-07-active-project-context.md) — §D3 4단 해소 · §D4 localStorage · §D5 프로젝트 스코프 유지 · §D6 분할 매핑(FR-UX-08 = F12+F17)
- [FR-UX-06 Jira 재설계](2026-07-17-fr-ux-06-jira-redesign.md) — ProjectTree · nav 라벨 · `_shell` 도입
- 정본 등록 위치 — `docs/plan/product/personalization.md` §4.6 · `docs/plan/fr-index.md:186` · `docs/sdd/20-personalization.md:153`
- 로드맵 정본 — `~/.claude/plans/ui-ux-sorted-kay.md` §PR 체인 (F12 · F17)
