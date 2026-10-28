<!-- Jira Cloud REA 갭 분석 뒤 Maxi 결정 5건 — 근거 인정 · 인라인 생성 · 스프린트 완료 판정 · 워크플로 초안 · 로드맵 정본 -->

# Jira Cloud REA 갭 분석 후속 결정 (D1~D5)

> **결정자**. Maxi · **결정일**. 2026-10-28
> **근거 문서**. [`docs/design/2026-10-28-jira-cloud-rea-gap.md`](../design/2026-10-28-jira-cloud-rea-gap.md) (PR #580)
> **관련 FR**. FR-UX-09 · FR-UX-13 · FR-BL-01 · FR-BL-02 · FR-WF-02 · FR-WF-07
> **실행 목록**. [`docs/design/jira-parity-roadmap.md`](../design/jira-parity-roadmap.md) §2차 캠페인

## D1. Jira Cloud 실물 관찰(REA)을 근거로 인정한다

계약 §1 의 근거 도메인 표에 **Jira Cloud 실물 관찰**을 더한다. 문서 인용이 없어도 행이 성립한다.
조건은 셋 — 관찰한 화면 URL · REA evidence_id(또는 CDP 판독 표기) · 조회일, 그리고 Cloud 사이트일 것.
문서와 실물이 어긋나면 **실물이 이긴다** — 이번 분석에서 DC 문서 기반 승계 행 다수가 실물과 달랐다(갭 문서 §2).

반영: [`jira-parity-contract.md`](../design/jira-parity-contract.md) §1.

## D2. 보드 · 백로그 인라인 생성을 다시 넣는다 — ADR fr-ux-09-f3 D-3 을 대체

[`2026-08-03-fr-ux-09-f3-create-issue-entry-points.md`](2026-08-03-fr-ux-09-f3-create-issue-entry-points.md) D-3 은
「컬럼별 + 는 그 컬럼을 약속하는데 D-1 이 그 약속을 못 지키게 했다」는 이유로 진입점을 보드 헤더 1곳으로 모았다.
이번 결정은 그 진입점을 되살린다(갭 JA-C-5 · JB-B-1 · JB-L-1).

**조건 — 약속을 지키는 수단을 같은 묶음에서 정한다.** Jira 는 컬럼에서 만든 이슈가 그 컬럼 상태로 들어간다.
R2 는 그 수단(생성 직후 전환, 또는 생성 API 의 초기 상태 수용)을 착수 때 고르고, 백엔드가 필요하면
issue-tracking PR 을 따로 낸다. 수단 없이 + 만 되살리면 D-3 이 지적한 거짓 약속이 돌아온다.

## D3. 스프린트 완료 판정은 Jira 기준 — 보드 맨 오른쪽 열

Jira company-managed 는 보드 **맨 오른쪽 열**에 있는 이슈만 완료로 센다(갭 JCB-5).
BTS 는 상태 카테고리 DONE 으로 센다(`apps/web/src/lib/backlog-completion.ts:88-98`). Jira 기준으로 바꾼다.
완료 다이얼로그의 「미완료 이슈 이동 대상」 집계와 보드 · 백로그 진척 표시가 같은 판정을 쓴다.

## D4. 워크플로 초안은 Jira 와 동일하게 — 초안은 스킴에만

Jira Cloud 는 초안 → 게시가 **워크플로 스킴**에만 있고, 워크플로 자체는 편집기에서 저장하면 바로 반영된다
(상태를 지우면 그 자리에서 「기본 대상 + 스페이스 × 업무 유형 예외」 이관 모달). BTS 는 워크플로에 초안 · 발행을 둔다(FR-WF-07).
Jira 와 같게 옮긴다 — 스킴 교체 · 유형 재배정은 초안 → 게시 + 유형별 상태 매핑 마법사(갭 JCW-1)로,
워크플로 편집은 즉시 반영으로.

이 결정은 [`2026-10-20-workflow-jira-parity-gap.md`](../specs/2026-10-20-workflow-jira-parity-gap.md) §6 순 4
(스킴 변경 시 상태 이관, FR 후보)를 **채택**으로 바꾸고, #524 의 「옮기지 않고 막는다」를 이관으로 대체할 길을 연다.
FR-WF-07 범위 변경이므로 착수 PR 에서 `docs/rules/fr-sync-checklist.md` 를 맞춘다.
§6 순 9(조건 · 검증기 종류 확장) **기각은 그대로다** — D4 는 초안 · 이관 모델만 다룬다.

## D5. 로드맵 정본은 `jira-parity-roadmap.md`

갭 문서 §4 의 묶음 표를 정본 로드맵의 「2차 캠페인」 절로 옮긴다. 갭 문서는 근거로만 남는다.
