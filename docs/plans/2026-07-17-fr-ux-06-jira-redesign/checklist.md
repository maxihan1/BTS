# FR-UX-06 UI/UX 개편 체크리스트

> 관련: plan.md · context-notes.md · [ADR](../../decisions/2026-07-17-fr-ux-06-jira-redesign.md) · [디자인 스펙](../../design/fr-ux-06-jira-redesign.md)
> 마커. `[ ]` 미진행 / `[~]` 진행중 / `[x]` 머지 완료 / `[!]` 차단 — **4종 외는 verify가 exit 2로 거부**

## P0 조사 (완료)

- [x] `apps/web` 전수 실측 — 52 라우트 / 프로덕션 .tsx 239 / 약 52,100 LOC / Playwright spec 122
- [x] 앱 셸 부재 확인 — `__root.tsx` 1,776 bytes, 사이드바 컴포넌트 파일 없음
- [x] 네비 링크 2개뿐 확인 → `DEFAULT_PROJECT_KEY='ATLAS'` 하드코딩의 원인 규명
- [x] 팔레트 무채색 확인 — `--primary: oklch(0.205 0 0)`, chroma 0, `--chart-1~5` 참조 0건
- [x] 프리미티브 7종 / Dialog 직접 import 35~37파일 확인
- [x] 중첩 레이아웃 0개 확인 — 52 라우트 전부 `rootRoute` 직속
- [x] **aria-label 4종이 e2e 18건의 유일한 계약임을 실측**
- [x] **`router.test.tsx`가 트리 모양을 검증하지 않음을 실측** → 라우터 위험 오판 정정
- [x] **route-id 결합 1곳**(`settings.account-links.tsx:411`)만 존재 확인
- [x] 프로젝트 목록 API 부재 확인 (`ProjectDirectory`에 `exists`/`resolveKeyToId`뿐)
- [x] **댓글 백엔드가 Import 부산물·읽기 전용임을 확인** (`CommentController` GET만) — 앞선 "완비" 보고 정정
- [x] `software-default.yaml`이 Jira status category와 1:1임을 확인
- [x] ADS 팔레트 4개 지점 독립 검증 (`#E9F2FF`/`#082145`/`#172B4D`/`#0052CC`는 구세대)
- [x] **동시 PR #277 발견** — FR-PJ-01~04 + FR-PM-10 5개 선점(123→128), PR-5가 프론트 UI
- [x] 레포 기록 관례 실측 — `docs/decisions/`가 정본(106개), `docs/adr/`은 휴면(34개·07월 0)

## P1 기록 (본 PR — 문서 전용)

- [~] plan 3종 (`plan.md` · `checklist.md` · `context-notes.md`)
- [ ] ADR `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` (D1~D7)
- [ ] 디자인 스펙 `docs/design/fr-ux-06-jira-redesign.md` (14섹션 + §12 DESIGN.md 패치안)
- [x] 메모리 5종 신설 + 2종 정정 + `MEMORY.md` 압축 (24.9KB → 17.5KB)
- [ ] `bash scripts/verify-master-plan.sh` → exit 0 (카운트 불변 회귀 확인)
- [ ] PR 생성

## P2 FR 전수 동기화 (**#277 머지 후** — 본 PR 범위 밖)

> 🔴 지금 하면 안 된다. main이 123인데 129를 주장하면 verify 즉시 fail.

- [ ] #277 머지 확인 → 실제 합계 재확인 (128 예상, **머지 직전 재확인 필수**)
- [ ] `docs/plan/fr-index.md` — §A.1 FR ID 행 · §A.2 personalization `12`→`13` · 합계 `128`→`129` · 상단 주석 · **§A.4 변경 이력 append**
- [ ] `docs/sdd/02-requirements.md` + 20장(개인화) — FR-UX-06 (**누락 시 verify exit 1**)
- [ ] `docs/plan/product/personalization.md` — §4 본문 + D1~D7 · L1 주석 `12 FR`→`13 FR` · `소속 FR. 12개`→`13개` · BC 완료 게이트 · **책임 문구에 전역 네비게이션 추가**
- [ ] `docs/plan/README.md` — §1 BC 테이블 행 · 합계 · **§7 변경 이력 append**
- [ ] `CLAUDE.md` — FR 총수 갱신
- [ ] `node scripts/build-dashboard.mjs` 재생성
- [ ] `bash scripts/verify-master-plan.sh` → exit 0
- [ ] Obsidian 미러 (수동) — `decisions/` · `plans/` 복사 + `history.md` append

### ★ verify 함정 (실측)

- [ ] **§4 헤더를 `(FR-UX, 4개)`로 바꾸지 않았는지** — 현재 `(FR-UX-01, 04, 05)` 열거형이라 룰 D 미발동. 바꾸는 순간 활성화 → **`(FR-UX-01, 04, 05, 06)` 열거형 유지**
- [ ] **`CLAUDE.md`에 2자리+ 숫자 + " FR" 잔여 없는지** — 룰 E가 `[0-9]{2,} FR`을 전부 검사
- [ ] **체크박스 4종만** 사용 (`[X]` 대문자 거부 — 룰 exit 2)
- [ ] **`소속 FR. 13개` ↔ `§A.2` personalization 행 `13`** 일치 (룰 F)
- [ ] verify가 못 잡는 새 카운트 표기를 도입했다면 **같은 PR에서 스크립트 확장 + 일부러 위반 넣어 fail 확인** (`CLAUDE.md:38` 명시 의무)

## Phase 0 — 기반 (4개 독립·병렬. #277과 무관하므로 먼저 머지)

- [ ] **PR2** `ui/*` 프리미티브 15종 추가 — **소비자 0, 순수 추가라 위험 0**
- [ ] **PR3** `index.css` ADS 팔레트 + 시맨틱 토큰 4쌍 (`--warning`/`--success`/`--danger`/`--info`)
  - [ ] `atlassian.design/components/tokens/all-tokens` **전수 대조** (시안 값은 정본 아님)
  - [ ] oklch → hex (공식 문서 대조 가능하게)
  - [ ] `--radius` calc 파생 폐기 → 2/3/4/8/12px 명시
  - [ ] **라이트·다크 both** (다크가 이미 동작 중 — 안 하면 대비 붕괴)
  - [ ] `--chart-1~5`는 **건드리지 않음** (PR22로)
  - [ ] `--syntax-*` 5종 **건드리지 않음** · `.mention` 대비 재확인
  - [ ] `DESIGN.md` 재작성 (화석 상태 — §10~12가 로그인 폼 기준)
- [ ] **PR9** `settings.account-links.tsx:411` → `useSearch({ strict: false })` (1줄)
- [ ] **머지 후 #277에 디자인 스펙 공유** — PR-5가 새 시스템 위에 짓도록

## Phase 1 — 토큰 정착

- [ ] **PR4** 하드코딩 색 141건/29파일 → 시맨틱 토큰 (amber ~70건이 `--warning` 하나로 붕괴, 손수 짠 `dark:` 페어 삭제로 순 LOC 감소)

## Phase 2 — Dialog 흡수 (BC별 4분할)

- [ ] **PR5** `issue-tracking` — **API 확정 PR. 가장 어려운 곳부터**
- [ ] **PR6** `agile-planning` (PR5와 병렬 가능)
- [ ] **PR7** `identity-access` + `notification` (PR6과 병렬)
- [ ] **PR8** 잔여 BC + **`radix-ui` Dialog 직접 import 금지 ESLint 룰** (없으면 재발)

## Phase 3 — Shell

- [ ] **PR10** pathless `_shell` + 51개 재부모화 — **시각 변화 0. 사이드바 안 넣음**
  - [ ] `loginRoute`는 `rootRoute` 직속 유지 (**실패 시나리오 1**)
  - [ ] 49개 `beforeLoad` 가드 hoist 금지 (**실패 시나리오 2**)
  - [ ] E2E 122 + `router.test.tsx` **무수정 전원 통과** = 무해함의 증거
  - [ ] `App.tsx` + `App.test.tsx` 삭제
- [ ] **PR11** 전역 사이드바 + `Header` 축소
  - [ ] **착수 전** `navigation-contract.test.tsx` 추가 (현 Header 기준 즉시 green)
  - [ ] aria-label 4종 보존 · `검색`은 상단바만 · **관리 메뉴 기본 펼침**
- [ ] **PR12** 프로젝트 사이드바 확장 + `ProjectNavTabs` — **Radix Tabs 금지, nav+Link**
- [ ] **PR13** `PageLayout`/`PageHeader`/`Breadcrumb` + `/settings`·`/admin` 인덱스
  - [ ] `PageHeader`가 `<h1>` 단독 소유, 채택 페이지의 기존 h1 삭제 (e2e 34건)

## Phase 4 — #277에 양보 (본 계획 범위 밖)

- [x] ~~PR14 `/projects` 목록~~ → #277 PR-5
- [x] ~~PR15 `/projects/$projectKey` 요약 + `DEFAULT_PROJECT_KEY` 제거~~ → #277 PR-5
- [x] ~~PR0' `GET /api/v1/projects`~~ → #277 PR-3

## Phase 5 — 화면

- [ ] **PR17** `IssueFilterBar`+`BoardFilterBar` → 공유 `FilterBar` (−350 순감)
- [ ] **PR18** 이슈 목록 카드 → `ui/table` 네비게이터 (정렬·컬럼 선택·split view)
- [ ] **PR19** 이슈 상세 탭화 + `IssueMetaPanel`(1204줄) 분해 — **여기선 Radix Tabs가 정답**
- [ ] **PR21** `@dnd-kit/sortable` → 보드 컬럼 내 순서변경
- [ ] **PR22** 원시 `<button>` 정리 + `FilteredEmptyState`/`Skeleton` 중복 제거 + **`--chart-*` 실소비 정의**

## 별도 FR (본 FR 범위 밖)

- [ ] **댓글 FR** — POST 노출(얇음, `create()` 존재) + 수정/삭제(신규) + 멘션 확장(FR-MN-01이 대기 중) + 이벤트 발행 + 프론트 전부
- [ ] **Pretendard 한글 웹폰트** — 토큰 PR과 엮지 말 것
