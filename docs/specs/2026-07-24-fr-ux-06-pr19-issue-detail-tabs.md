# FR-UX-06 PR19 이슈 상세 탭화 + IssueMetaPanel 분해 — 스펙

> slug: fr-ux-06-pr19-issue-detail-tabs · type: ui · agent: frontend-engineer
> BC: issue-tracking (프론트) · FR 불변 129 (FR-UX-06 D-step)
> 지배 결정: ADR `2026-07-17-fr-ux-06-jira-redesign.md` §D4 + 디자인 스펙 `fr-ux-06-jira-redesign.md` §3.3·§282-283·§302

## 배경 (실측)

- `routes/issues.$key.tsx`: 2컬럼 그리드 `lg:grid-cols-[1fr_280px]`(좌 본문/첨부 · 우 `IssueMetaPanel` 1204줄 + 일정 + 추정) **아래** 전체폭으로 세로 적층: `WorklogSection` → `IssueLinksPanel`+`EpicChildrenSection`+`LinkGraph` → `IssueChangelog`. 디자인 스펙 §3.3이 지적한 "무한 적층".
- **댓글 UI 부재** — comment 컴포넌트 import 0. `comment-backend-is-import-byproduct-read-only`(CommentController GET-only·쓰기 REST 미노출·댓글은 별도 신규 FR) 확증.

## 범위 (Maxi 게이트 결정 2026-07-24)

**IN**
1. 활동 영역 탭화 — 그리드 아래 적층 섹션을 Radix Tabs 3탭으로:
   - **작업로그** = `WorklogSection` (기본 활성 탭)
   - **연결** = `IssueLinksPanel` + `EpicChildrenSection`(에픽 타입 조건부) + `LinkGraph`
   - **이력** = `IssueChangelog`
2. 2컬럼 그리드 우측 폭 `280px → 340px` (디자인 스펙 §3.3, Jira 기준).
3. `IssueMetaPanel`(1204줄) 분해 — 서브패널을 별도 파일로 추출(우선순위/영향도·담당자·컴포넌트·버전·보안등급·라벨·환경 등). **DOM 구조·`data-testid`·`aria-*` 완전 보존**(e2e 무영향). 목표 −500 LOC급.

**OUT (이연)**
- **split view**(목록+상세 2분할) → 별도 후속 PR20. 새 라우트/레이아웃·목록↔상세 URL 동기화가 필요한 별개 기능.
- **댓글 탭** → 댓글 FR 도입 시 4번째 탭으로 확장. 디자인 스펙 §3.3 deviation 주석 명시(이 PR에서 §3.3 갱신).

## 사용자 시나리오 (Given-When-Then)

- G: 이슈 상세를 연다 / W: 활동 영역을 본다 / T: **작업로그 탭이 기본 활성**, 연결·이력 탭으로 전환.
- G: 활동 탭 전환 / W: 탭 클릭 / T: 같은 라우트 내 패널 전환(라우팅 아님, ADR §D4) — **URL 미변경**(로컬 상태, 뒤로가기 오염 방지).
- G: 에픽 타입 이슈 / W: 연결 탭 / T: `EpicChildrenSection`이 연결 탭 안에 렌더(비에픽은 미렌더, 현 조건 보존).
- G: 뷰포트 <1024px / W: 상세 화면 / T: 1컬럼(메타패널 본문 아래, 디자인 §282-283 현 반응형 보존).

## 비기능 요구사항 (NFR)

- Radix Tabs `role="tablist"/"tab"/"tabpanel"` a11y 기본 제공. 탭 라벨 한글(작업로그/연결/이력).
- 권한 fail-closed 현 동작 보존(`canEdit` 전파).
- `IssueMetaPanel` 분해는 순수 구조 리팩터 — 렌더 출력 diff 0(`git diff -w` 기준 서브패널 이관).

## 엣지 케이스

- **E2E 폭발 반경**: 탭화로 작업로그/연결/이력이 기본 비활성 탭 뒤로 숨음 → 해당 e2e(worklog·issue-links·changelog 등)는 탭 활성 클릭 선행 필요. **동일 PR에서 봉합**(UI+E2E 같은 PR, PR18 선례). 셀렉터 verbatim 보존 + 탭 클릭만 추가.
- 첨부(`AttachmentSection`)는 좌측 본문 유지 — 탭 대상 아님.
- 탭 라벨 단일단어 아님(작업로그/연결/이력) → `playwright-getbyrole-exact-strict-mode` 위험 낮음. 단 "이력"/"연결"이 페이지 타 영역에 등장하는지 grep 확인.
- 삭제 확인 UI(`confirmDelete`)는 우측 컬럼 현 동작 보존.

## 측정 가능한 완료 기준

- [ ] 활동 3탭 Radix Tabs(`ui/tabs` 프리미티브 소비), 기본=작업로그.
- [ ] `IssueMetaPanel` 서브패널 별도 파일 추출로 파일 크기 유의미 감소(−500 LOC급), DOM/testid 보존.
- [ ] 그리드 우측 340px.
- [ ] 디자인 스펙 §3.3 deviation 주석(댓글 탭 이연) 갱신.
- [ ] 관련 e2e 동일 PR 봉합 → 전수 green(로컬, CI e2e 잡 없음).
- [ ] typecheck 0 · eslint 0 · vitest green · FR 129 불변.

## Brainstorming Check

✅ 통과 (자체 sanity check, right-size). blocking gap 0. impl-plan으로 넘길 확인 3건:
- **G1 (첫 소비자)**: `ui/tabs`(PR2 산출)의 **현 소비자 0 → PR19가 첫 소비자**(PR5 Dialog 선례). 표준 shadcn tabs 래퍼(Tabs/TabsList/TabsTrigger/TabsContent)라 위험 낮으나, plan에서 사용 계약(controlled `value`/`onValueChange` vs uncontrolled `defaultValue`) 확정.
- **G2 (분해 테스트 표면)**: 섹션 단위 테스트(`WorklogSection.test`·`IssueChangelog.test`)는 컴포넌트 독립 마운트 → 탭화 무영향. `IssueMetaPanel` 자체 3 테스트파일(합 2869줄)은 조합 패널 렌더 검증 → 분해 시 **DOM/testid 보존으로 green 유지**, 추출 서브패널엔 신규 단위테스트. 탭화 영향은 route 테스트(`issues.$key.test`)+e2e 한정.
- **G3 (지연 마운트)**: Radix Tabs는 비활성 패널을 언마운트 → 연결/이력 탭 콘텐츠(LinkGraph·Changelog)는 **활성 시점 fetch**(현 eager 대비 초기 요청 감소, 1000명 규모 이점). `forceMount` 미사용(기본 lazy) 채택 → e2e는 탭 클릭 후 검증.
