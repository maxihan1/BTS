# FR-UX-06 Phase 5 PR19 — 이슈 상세 탭화 + IssueMetaPanel 분해

> slug: fr-ux-06-pr19-issue-detail-tabs
> type: ui (classify가 backend로 오분류 → 실측 정정, FR-UX-06 UI 시리즈 선례 PR9/PR10/PR12 동일 함정)
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트)
> 생성: 2026-07-24

## Brief

FR-UX-06(BTS UI/UX를 Jira Cloud 2025 방식으로 전면 개편) Phase 5(화면) 세 번째 PR.
이슈 상세 화면을 Radix Tabs로 탭화하고, 거대해진 `IssueMetaPanel`(~1204줄)을 분해한다.
split view(목록+상세 2분할)도 이 PR과 함께 검토 (PR18에서 의도적으로 이연).

### 착수 전 필독 (메모리)
- `frontend-nav-aria-label-e2e-contract` — **규칙: 라우트 변경=nav+Link, 같은 라우트 패널 전환=Radix Tabs.**
  이슈 상세 활동 탭은 탭 0개·라우팅 아님 → **여기선 Tabs가 정답**.
- `playwright-getbyrole-exact-strict-mode` — 단일단어 라벨(저장/삭제/취소/확인/추가) substring 매칭 위험 → `exact:true`.
- `e2e-playwright-filter-arg-drop` — 특정 spec만 돌리려면 `apps/web/node_modules/.bin/playwright` 직접 호출. CI에 e2e 잡 없음 → 로컬 e2e 필수.

## 도메인 정리

- **BC**: issue-tracking (프론트 뷰). 순수 UI 재구조화, 백엔드/도메인 모델 영향 0.
- **새 용어**: 없음. 유비쿼터스 언어 변경 없음(탭/패널은 UI 표현일 뿐 도메인 개념 아님).
- **기존 결정 충돌**: 없음. 오히려 **방향이 기존 결정으로 이미 확정됨** — right-size 도메인 단계(신규 grill-with-docs 불필요, PR18 선례 "domain 용어/ADR 0"과 동일).
- **지배 결정 (이 작업을 지시하는 정본)**:
  - ADR `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` **§D4** — "라우트 변경=nav+Link / 같은 라우트 패널 전환=Radix Tabs. **이슈 상세 활동 탭은 Radix Tabs가 정답**(탭 0개라 깨질 어서션 없음·라우팅 아님)."
  - 디자인 스펙 `docs/design/fr-ux-06-jira-redesign.md` **§3.3 이슈 상세 2컬럼** — `grid-template-columns: minmax(0,1fr) 340px`(현재 280px→340px). 활동 영역(댓글/히스토리/작업로그/연결)을 **탭으로 접는다**(현재는 2단 그리드 바깥 세로 무한 적층). §282-283 반응형: ≥1024px 2컬럼 / <1024px 1컬럼(메타패널 본문 아래). row246 `tabs` 프리미티브=이슈 상세 활동(PR19). §302 이슈 상세 활동=Radix Tabs. line140 `IssueMetaPanel` 분해 −500 LOC 예상.
- **관련 ADR**: 신규 생성 없음(§D4가 이미 커버). 관련 = D4 + 디자인 스펙 §3.3.
- **⚠️ 스펙 단계 확인 필요(도메인 아님)**: 디자인 스펙이 활동 탭에 "댓글"을 열거하나, 메모리 `comment-backend-is-import-byproduct-read-only`상 CommentController는 GET만(쓰기 REST 미노출). 현재 이슈 상세에 댓글 UI가 실재하는지 실측 후 탭 구성 확정 → /bts-spec에서 판정.

## 스펙

전체 스펙. [docs/specs/2026-07-24-fr-ux-06-pr19-issue-detail-tabs.md](../specs/2026-07-24-fr-ux-06-pr19-issue-detail-tabs.md)

**범위 (Maxi 게이트 2026-07-24)**
- IN: ①활동 3탭(작업로그[기본]/연결/이력) Radix Tabs화 ②그리드 우측 280→340px ③`IssueMetaPanel`(1204줄) 서브패널 분해(DOM/testid 보존, −500 LOC급).
- OUT 이연: split view → 후속 PR20 · 댓글 탭 → 댓글 FR 도입 시(디자인 스펙 §3.3 deviation 주석 갱신).

핵심 시나리오.
- 이슈 상세 활동 영역이 세로 무한 적층 → 3탭으로 접힘, 기본=작업로그.
- 탭 전환은 같은 라우트 패널 전환(ADR §D4) → URL 미변경·로컬 상태.
- `IssueMetaPanel`은 우측 컬럼 유지, 내부만 서브패널 파일로 분해(순수 구조 리팩터).

## Brainstorming Check

✅ 통과 (자체 sanity check, blocking gap 0). impl-plan 확인 3건:
- G1 `ui/tabs` 첫 소비자(계약 확정) · G2 IssueMetaPanel 3 테스트파일 green 보존 + 서브패널 신규테스트 · G3 Radix Tabs 지연마운트(연결/이력 탭 활성 시 fetch, e2e 탭클릭 선행).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
