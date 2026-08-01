<!-- 이슈 생성 모달 — F2/F3 범위 재분할 + 3-state 담당자의 프론트 표현 -->

# ADR — 이슈 생성 모달 (CreateIssueDialog)

> 날짜: 2026-08-01
> 상태: 채택 (FR-UX-09 F2, PR #331)
> BC: issue-tracking (프론트 소비 — 백엔드 변경 0 예상)
> 관련 SDD: [11. API 설계](../sdd/11-api-design.md)
> 정본: `docs/plan/product/personalization.md` §4.7 FR-UX-09 D2/D6
> plan: [plan](../plans/2026-08-01-fr-ux-09-f2-create-issue-dialog.md)
> 선행 ADR: [FR-UX-09 B1 — 생성 3필드](2026-07-31-fr-ux-09-b1-create-issue-fields.md)

## 맥락

PR #328(B1)이 `POST /api/v1/issues` 에 `assigneeId`·`priority`·`labels` 를 열었다. 그런데
**프론트가 그 3필드를 보내지 못한다.** 그뿐 아니라 원래부터 백엔드가 받던 `typeId`·`description`
두 필드도 못 보낸다 — 즉 생성 API 의 5필드가 프론트에서 **소비처 0** 이다.

### 착수 전 실측 (정본 §4.7 인용 4건 전량 확인)

| 정본 서술 | 실측 결과 |
|---|---|
| `CreateIssueRequest.kt:30-35` 에 `typeId`·`description` 이 이미 있다 | ✅ 정확 (`backend/modules/issue-tracking/.../rest/CreateIssueRequest.kt`) |
| `routes/issues.new.tsx:215-232` 가 그걸 안 보낸다 | ✅ 정확 — 프로젝트는 `<Input placeholder="예: ATLAS">` 자유 텍스트, 유형 선택 컨트롤 없음 |
| `api/issues.ts:266-278,551-568` | ✅ 정확 — `CreateIssueInput` 은 `projectKey`·`summary`·`componentIds`·`securityLevelId`·`customFields` 5필드뿐 |
| `TopBar.tsx:69-78` 만들기 버튼 | ✅ 존재 — `navigate({ to: '/issues/new' })`, 모달 아님 |

### 상속하는 백엔드 계약 (B1 ADR)

- **`assigneeId` 는 3-state** (B1 ADR D-2). 키 생략 = 자동 배정(`resolveDefaultAssignee`) 유지 ·
  명시 `null` = 자동 배정 **비활성** 후 미할당 확정 · 값 = 그 사용자.
  **프론트는 이 3-state 를 JSON 본문의 키 존재 여부로 표현해야 한다.**
- **`description` 은 null/공백이면 서버가 템플릿으로 대체**(FR-TM-01 옵션 C).
  non-blank 면 요청 값을 그대로 쓰고 템플릿을 조회하지 않는다.
- `priority` 생략/null → 도메인 기본값 3(Medium) · `labels` 생략/null → 빈 목록.
- 존재하지 않는 사용자 지정은 **422 `ASSIGNEE_NOT_FOUND`**.
- 라벨은 개수 ≤20 · 개별 길이 ≤50 · 공백-only 금지 (`@AssertTrue`, 위반 시 400).

## 결정

### D-1. F2/F3 범위 재분할 (2026-08-01 Maxi 확정, 추천과 일치)

**F2(이번 PR)** = `CreateIssueDialog` + 프로젝트 셀렉터 · 이슈 유형 · 본문 **+ 담당자 · 우선순위 · 라벨**.
**F3(후속)** = 진입점 3곳(`BoardColumn` · `BacklogColumn` · `SprintColumn`) 배선만.

정본 §4.7 은 3필드를 F3 에 묶어 두었다. 이 ADR 이 그 서술을 **명시적으로 승계·정정**한다.

- **근거 1.** 3필드는 `components/issue/meta/` 8종 재사용이라 신규 컴포넌트 0 —
  폼 스키마에 필드 3개와 제출 페이로드 배선이 늘어날 뿐이다.
- **근거 2.** F3 로 미루면 `CreateIssueDialog` 의 폼 스키마 · 제출 페이로드 · 단위 테스트를
  **두 번 연다.** 같은 파일 2회 수정은 회귀 표면을 두 배로 만든다.
- **근거 3.** #328 의 3필드가 소비처 0 인 동안은 **프론트 Zod 스키마와 백엔드 DTO 의 계약갭이
  드러나지 않는다.** 이 PR 이 즉시 소비하면 실서버 호출로 검증된다
  (learnings 2026-05-30 `frontend-zod-backend-dto-contract-gap` 의 역방향).
- **대가.** 정본 §4.7 의 F2/F3 서술을 이 PR 에서 고쳐야 한다 —
  `docs/rules/fr-sync-checklist.md` 9종 동기화 + `scripts/verify-master-plan.sh` 통과가 강제된다.
- **불변.** FR 카운트 139 · D 마커 총량 불변. D6 의 책임 범위 표현만 F2/F3 사이에서 이동한다.

<!-- D-2 이후는 /bts-spec 단계에서 확정되는 대로 추가한다. -->
