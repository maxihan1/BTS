# FR-UX-06 UI/UX 개편 — 컨텍스트 노트 (결정 + 근거)

> 작업 진행하며 계속 append. 다음 세션(사람/에이전트)이 재유도 없이 이어받도록.
> 관련: plan.md · checklist.md

## 2026-07-17 — 착수 배경

Maxi 원문 — *"전체 ui/ux와 디자인을 지라 클라우드 방식으로 개편하려고 해 현재 코드와 지라 클라우드 ui/ux를 확인해서 계획 세우고 변경되는 ui/ux 디자인 시안을 HTML으로 보여줘"*.

시안(동작하는 프로토타입 8화면·ADS v2·라이트/다크) — https://claude.ai/code/artifact/de59cb1e-4fae-4755-b18b-fe888ee96023

정적 아트보드 8장 대신 **셸이 유지되고 내용만 바뀌는 프로토타입**으로 만들었다. 이번 개편의 핵심이 셸이므로 그림으로 보는 것보다 직접 눌러 "이슈로 어떻게 가지" "설정은 어디 있지"를 확인하는 게 검증이 된다.

### Maxi 확정 결정 (질문 응답)

| # | 질문 | 선택 | 담당자 권고와의 차이 |
|---|---|---|---|
| 1 | 네비 세대 | **2025 신형 통합 사이드바** | 일치 |
| 2 | 브랜드 색 | **ADS 팔레트 그대로** | 일치 |
| 3 | 이번 범위 | **시안 + 로드맵만** (코드는 승인 후) | 일치 |
| 4 | 시안 화면 | **8종 전부** | 일치 |
| 5 | 댓글 | **별도 FR로 분리** | 일치 (권고 정정 후) |
| 6 | FR 추적 | **FR-UX-06 신설** | ✗ 권고는 "FR 없이 ADR로만" |
| 7 | FR 범위 | **개편 전체를 FR로** | ✗ 권고는 "전역 네비만 FR" |
| 8 | 기록 형식 | **디렉토리형 3종** | 일치 |
| 9 | 주 BC | **personalization** (Maxi가 "네가 추천해줄래") | 담당자 결정 |
| 10 | #277 순서 | **Phase 0 먼저 머지** | 일치 |
| 11 | Phase 4 | **#277에 양보** | 일치 |

**#6·#7은 Maxi가 담당자 권고를 기각한 지점이다.** 기록해 둔다 — 나중에 "왜 이렇게 했나"가 다시 나올 때 재논의를 막기 위해서다.

- **#6 권고 기각 사유(추정).** 진척 가시성. FR이 없으면 `docs/progress.html`에 개편이 안 보인다.
- **#7의 대가.** D1~D7 7단계 모델에 20 PR을 밀어 넣으면 **D6 하나에 15 PR**이 몰려 진척 표현이 무너진다. → **D6를 PR 체인으로 쪼개 표기**해 완화한다 (FR-AT-07의 PR-A/B/C 선례, `docs/decisions/2026-07-16-fr-at-07-pr-b-fix-version-port.md`).

## 주 BC = personalization — 왜 그렇게 골랐나 (담당자 결정)

Maxi가 추천을 요청했다. 후보 3개.

| 후보 | 근거 | 기각 사유 |
|---|---|---|
| **personalization** (12→13) | ① UX 그룹 관례(UX-01/04/05가 이미 거기) ② FR-UX-05 ADR D4 "논리 ≠ 물리" 선례가 동형 ③ **★ personalization은 이미 논리 BC이고 물리 구현이 identity-access** | **채택** |
| identity-access (24→25) | 프로젝트 목록 API가 물리적으로 여기 | "UI 개편 FR이 인증 BC에 산다"가 의미적으로 나쁨 |
| project-workflow (3→4) | 프로젝트 개념의 주인, FR이 3개뿐 | 책임이 워크플로우/권한스킴이라 안 맞음 |

**③이 결정적이었다.** personalization의 물리 구현이 이미 identity-access이므로, 프로젝트 목록 API를 identity-access에 물리 구현해도 **논리 소속은 personalization으로 일관**된다. 다른 후보엔 이 정합성이 없다.

**감수하는 것.** personalization BC 완료 게이트(12/12)가 **풀린다.** BC 책임 문구도 갱신해야 한다 — "UX 편의 (퀵 필터, Slash, 단축키)"에 전역 네비게이션 추가. 다만 이건 어느 BC를 골라도 FR을 늘리면 같고, personalization이 "완료"인데 UX 개편이 남아있다는 게 실상이기도 하다.

## ★ 위험 평가를 뒤집은 발견 — 라우터는 위험하지 않았다

당초 최대 위험을 "pathless layout route 도입 → E2E 122 + `router.test.tsx` 동시 폭발"로 잡았다. **틀렸다.**

1. **`router.test.tsx`(14KB)는 라우트 트리 모양을 검증하지 않는다.** `createMemoryHistory`로 URL을 넣고 **렌더된 텍스트만** 확인한다. `routesById`/`fullPath`/`getParentRoute`/트리 구조 assertion **0건**.
2. **TanStack Router의 pathless layout은 자식의 `fullPath`를 바꾸지 않는다.** 바뀌는 건 route **id**뿐(`/issues` → `/_shell/issues`).
3. **route id에 묶인 코드가 전체에서 1곳.** `getRouteApi` 0건 · `useSearch/useParams({strict:false})` 36건(id 비의존) · id 결합 `from:`은 `settings.account-links.tsx:411` **한 줄**뿐이고 그마저 `as` 캐스팅이라 타입 이득도 없다.

→ **재부모화의 관측 가능한 변화 = wrapper div 하나.**

### 그럼 진짜 위험은 — 사이드바의 접근성 계약

E2E의 실질 계약은 DOM 구조가 아니라 **aria-label 4개 문자열**이다. `getByRole('navigation')` 18건이 전부 `{name}` 스코프.

**즉 "사이드바 재구축"이 "라벨 4개 보존"으로 환원된다.** 헤더 nav를 사이드바로 옮기되 라벨을 그대로 달면 spec은 무수정 통과한다.

unscoped selector 860건(`getByRole('button')` 515 + `getByText()` 345)이 지뢰밭처럼 보이지만 실제 충돌면은 좁다 — `만들기`/`보드`/`백로그`/`설정`/`요약`/`리포트`는 정확 일치 **0건**. 위험은 셋뿐이다.

- 🔴 `검색` — `Header.tsx:124`에 이미 있고 **5 spec** 의존 → 사이드바 중복 금지
- 🔴 **관리 메뉴 접기** — Jira처럼 아코디언에 접고 싶어지지만 3개 spec이 `adminNav.getByRole('link').click()`을 한다 → not visible → 클릭 실패. **기본 펼침으로 출시**
- 🔴 **`프로젝트 뷰 전환`을 Radix Tabs로** — `role="tablist"`가 `role="navigation"`을 없애 e2e 5 + 유닛 5 즉사. 게다가 **라우트 이동**이라 뒤로가기도 깨지고 시맨틱도 틀림

> ★ **규칙으로 정리. 라우트가 바뀌면 nav+Link, 같은 라우트에서 패널만 바뀌면 Radix Tabs.** 이슈 상세 활동 탭은 현재 탭 0개라 깨질 어서션이 없고 라우팅도 아니므로 거기선 Tabs가 정답이다.

## ★ 오보 정정 — "댓글 백엔드 완비"는 틀렸다

담당자가 서브에이전트 보고를 **컨트롤러 확인 없이** Maxi에게 옮겨 오보했다. 메모리에 `sub-agent 보고 불신, controller 검증`이라고 적혀 있는데도 어겼다.

| 계층 | 실제 |
|---|---|
| Comment 도메인 · Repository | 있음 |
| `CommentApplicationService.create()` | **있음** — Import가 직접 호출 |
| `CommentController` | **GET만.** POST/PATCH/DELETE 없음. `create()`가 REST로 **미노출** |
| 수정 · 삭제 | 서비스 메서드 자체가 없음 |
| `NotificationEventType.ISSUE_COMMENTED` | 있음 (`NotificationTitleBuilder:31`까지) |
| automation `ISSUE_COMMENTED` 트리거 | `V300__automation_rules.sql:37` CHECK에 있음 |
| 프론트 `*comment*` | **0건** |

**원인.** 최초 커밋이 `029dd82fe [feature] FR-IM-01 PR3 — 댓글/Worklog Import (#224)`다. 외부 시스템에서 댓글을 **가져오려면** Comment 도메인이 필요했다. 즉 **댓글 백엔드는 댓글 기능이 아니라 Import의 부산물**이다. 파일 L1 주석도 `// 댓글 REST 컨트롤러 — 목록 조회 엔드포인트 (FR-IM-01 PR3)`.

**댓글 FR은 `fr-index.md`에 없다.** 오히려 여러 FR이 명시적으로 이연하고 **기다리고 있다**.
- `FR-MN-01` — *"댓글 멘션은 댓글 기능 부재로 제외(**댓글 FR 도입 시** sourceField=\"comment\"로 확장)"*
- `FR-IS-06`(클론) — *"Attachment/Watcher/IssueComment가 미구현이라 이번 범위에서 제외"*

→ 댓글은 온전한 신규 FR. **UI 개편과 분리**(Maxi 확정). 시안에는 남겨뒀다.

> ★ **교훈. 도메인·서비스·repo가 다 있어도 REST 노출이 없으면 기능이 없는 것이다.** 파일 존재 ≠ 기능 존재. "에이전트 보고 불신, controller 검증"이 lint뿐 아니라 **기능 유무 판단에도** 적용된다.

## ★ 동시 PR #277 충돌 — 계획 축소

worktree를 만들다 **DRAFT PR #277 「프로젝트 관리 — 생성/목록/설정/아카이브 + FR 신설」**(`backend/project-management-crud`, 어제 시작, 6 PR 체인 중 **PR-1 진행 중**)을 발견했다.

| 충돌 | 처리 |
|---|---|
| #277이 `FR-PJ-01~04` + `FR-PM-10` **5개 선점 → 123→128** | FR-UX-06은 **128→129**. **본 PR에서 FR 동기화 안 함** |
| #277이 `GET`/`POST /api/v1/projects`를 만듦 | PR-0' **삭제** |
| #277 **PR-5 = "D6 프론트 UI — 생성·목록·설정·아카이브 화면"** | Phase 4(PR14·PR15) **양보** |
| #277이 identity **V036** 선점 | 회피 |

★ **`fr-index.md`엔 FR-PJ가 아직 없다 — `docs/specs/2026-07-17-project-management-crud.md`에만 선점돼 있다.** fr-index만 grep했다면 못 봤다. 메모리 `parallel-fr-overlapping-frontend-infra-collision`("공유자원 기능은 spec서 선점 FR grep")이 정확히 이 상황이었다.

**순서 (Maxi 확정).** Phase 0(문서·토큰·프리미티브)을 **먼저 머지**한다. #277과 독립이고 소비자를 안 건드려 위험 ≈ 0이며, 그래야 #277 PR-5가 새 디자인 시스템 위에 짓는다. 반대면 PR-5가 **현재 디자인**(무채색·프리미티브 7종·셸 없음) 위에 화면 4개를 짓고 개편이 그걸 다시 뜯는다.

**머지 후 할 일.** `docs/design/fr-ux-06-jira-redesign.md`를 **#277에 공유**한다 — PR-5의 입력이 되어야 한다.

**설정 nav.** `/settings`·`/admin` 인덱스는 #277 범위 밖이라 양보 대상이 아니다 → 사이드바(PR11)/PageLayout(PR13)에 흡수, 별도 PR 없음.

## 기록 위치 — 관례 실측 (지어낸 것 없음)

| 산출물 | 경로 | 본 |
|---|---|---|
| plan 3종 | `docs/plans/2026-07-17-fr-ux-06-jira-redesign/` | `docs/plans/2026-07-10-production-deployment-foundation/` |
| ADR | `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` | `docs/decisions/2026-07-05-fr-ux-05-keymap.md` (96줄, UI 선례) |
| 디자인 스펙 | `docs/design/fr-ux-06-jira-redesign.md` | `docs/design/fr-ca-01-calendar.md` (434줄, 14섹션) |

### 함정

- 🔴 **`docs/adr/`에 쓰지 않는다.** 두 폴더가 있지만 **정본은 `docs/decisions/`**(106개·07월 커밋 38) 하나고 `docs/adr/`은 **휴면**(34개·07월 **0**). 스킬·에이전트가 전부 decisions를 가리킨다(`bts-domain/SKILL.md:62`, `db-engineer.md:38`, `bts-merge/SKILL.md:101`). `CLAUDE.md:34`가 "또는"이라 써서 교환 가능해 보이는 게 혼란의 원인. **stale 포인터 주의** — `docs/plan/README.md` §0.1과 `fr-index.md` §A.3이 아직 `docs/adr/`을 정본으로 지목한다.
- 🔴 **`docs/plan/`(단수) ≠ `docs/plans/`(복수).** 단수 = FR 진척 대장(영구), 복수 = 작업 실행 계획(1작업 1파일). 이름만 비슷하고 역할이 다르다.
- 🔴 **`docs/design/`(단수)이 실제 디렉토리.** `.claude/agents/designer.md`가 두 곳에서 `docs/designs/`(복수)라 하는데 **에이전트 지시가 틀렸다** (선례 파일이 단수 경로에 있음).
- **Obsidian 미러는 수동이다.** `Maxi_wiki/BTS/_index.md`가 "post-merge hook이 `scripts/workflow/sync-obsidian.ts` 실행"이라 주장하지만 **그 스크립트는 존재하지 않고** post-merge 훅은 대시보드 재생성 전용이다. `_index.md`가 틀렸다. 정본은 `bts-merge/SKILL.md` Step 7 (Phase 0 수동).
- **`docs/design/`은 Obsidian 미러 경로가 없다.** Step 7이 복사하는 건 `decisions/`·`plans/` 둘뿐이고 볼트에 `design/` 폴더도 없다 → 디자인 스펙은 레포에만 남긴다. 새 관례를 만들지 않는다.
- **`docs/design/` §12 "DESIGN.md 패치안" 관례는 한 번도 닫힌 적 없는 루프다.** `fr-ca-01-calendar.md` §12가 패치안을 제시했지만 `grep 캘린더 DESIGN.md` → **0건**. 관례는 선언됐으나 반영된 적이 없다.

### verify-master-plan 함정 (실측 — 룰 A~G)

본 PR은 `docs/plans/`·`docs/decisions/`·`docs/design/`만 건드리므로 **스캔 대상이 아니라 안 깨진다.** FR 동기화 커밋에서 걸릴 것들.

- 🔴 **§4 헤더 형식을 바꾸지 말 것.** 현재 `## §4 UX 편의 (FR-UX-01, 04, 05)`는 열거형이라 **룰 D의 `(FR-XX, N개)` 정규식에 안 걸린다.** `(FR-UX, 4개)`로 바꾸면 그 순간 활성화 → **열거형 유지.**
- 🔴 **룰 E** — `CLAUDE.md`에서 `[0-9]{2,} FR`을 전부 검사. 본문에 FR 개수를 무심코 쓰면 즉시 fail.
- 🔴 **exit 1** — 정규식이 파일 전체를 훑는다. `docs/plan/`에 대문자 `FR-UX-06`을 예시로라도 쓰고 SDD에 안 넣으면 fail.
- 🔴 **exit 2** — 체크박스 `[ ]` `[~]` `[x]` `[!]` **4종만**. 대문자 `[X]` 거부.
- **CI에 없다.** `bash scripts/verify-master-plan.sh` 수동 실행이 유일한 게이트.

### 기존 drift 3건 (본 PR에서 만든 게 아님 — 고칠지는 별건)

1. `CLAUDE.md:10`의 `docs/plan/progress.html` → 실물은 `docs/progress.html`
2. `docs/plan/README.md` §0.1 · `fr-index.md` §A.3이 `docs/adr/`을 정본 지목 → 실제 정본은 `docs/decisions/`
3. `fr-index.md` §A.1 제목 `(122개 전수)` ↔ 합계 `123` — 룰 A/D 정규식에 안 걸려 통과 중

## 팔레트 — 정본이 아니다

`atlassian.design`이 JS 렌더링이라 **전수 검증 실패**. 4개 지점만 독립 확인했고 전부 일치했다.

| 값 | 역할 | 출처 |
|---|---|---|
| `#E9F2FF` | Blue100 (램프 최명부) | Atlassian 개발자 문서 (`color.background.information` 라이트) |
| `#082145` | Blue1000 (최암부) | 동 문서 다크 |
| `#172B4D` | Neutral 텍스트 | 검색 교차 확인 |
| `#0052CC` | **구세대 v1 B400** | 검색 — "Atlassian Design Primary" |

**마지막 항목이 중요하다.** `#0052CC`는 널리 알려진 "지라 파랑"이지만 **구세대 값**이다. 2023 토큰 리프레시에서 램프가 재편돼 현재 brand bold는 **Blue700 `#0C66E4`**다. 신형 네비게이션(2025)을 택했으므로 세대를 맞춰 v2를 쓴다. 구형을 쓰면 네비만 신형이고 색은 구형인 어정쩡한 조합이 된다.

램프 양 끝점이 검증됐으니 중간 값 신뢰도는 높지만 **정본이 아니다** → PR3에서 `atlassian.design/components/tokens/all-tokens` 전수 대조를 D단계 작업으로 넣는다.

## 뜻밖의 정합 — 상태 모델은 이미 Jira다

`workflows/software-default.yaml`이 **이미 Jira의 status category 모델과 동일**하다 (Open=TODO / In Progress·In Review=IN_PROGRESS / Done·Closed=DONE). 이슈 타입 시드도 epic/story/task/subtask로 1:1. **로젠지 색 매핑에 발명이 필요 없었다.**

## 한글 폰트 — 별도 PR

Jira의 Atlassian Sans에는 **한글 글리프가 없어** 그대로 베낄 수 없다. 시안은 Jira가 실제로 수년간 쓴 **시스템 스택**(`-apple-system`/`Segoe UI` + 한글 OS 폴백)으로 갔다 — 정확하면서 로딩 비용 0이고, CSP가 폰트 CDN을 막는 환경에서도 안전하다.

현재 BTS의 Geist도 라틴 전용이라 한글이 OS 폴백이다. Pretendard 도입은 `DESIGN.md`에서 "후속 결정"으로 보류 중 → **토큰 PR(PR3)과 엮지 말고 별도 PR로.** 섞으면 "팔레트 때문에 깨진 것"과 "폰트 때문에 깨진 것"을 구분 못 한다.
