<!-- FR-IS-01 이슈 CRUD 풀스택 — issue-tracking BC 진입 1-PR plan stub -->

# FR-IS-01 이슈 CRUD — issue-tracking BC 진입

> slug: issue-tracking-bc-fr-is-01-crud
> type: api (feature 수준 풀스택)
> primary agent: backend-engineer
> 보조 agent: db-engineer, security-engineer, frontend-engineer, designer, qa-engineer
> BC: issue-tracking
> 생성: 2026-05-22

## Brief

이슈 CRUD (Create / Read / Update / Delete) + 상태 변경 시 워크플로우 검증 + 알림 이벤트 발행을 D1~D7 풀스택으로 구현하는 issue-tracking BC의 진입 작업.

### 사용자 원문

> "issue tracking 작업 진행하자" → AskUserQuestion 결과. FR-IS-01 D1~D7 풀스택 monster PR 선택.

### classify-task 결과

```json
{
  "type": "api",
  "agent": "backend-engineer",
  "primary_bc": "issue-tracking",
  "slug": "issue-tracking-bc-fr-is-01-crud"
}
```

### 작업 범위 (D1~D7 풀스택)

`docs/plan/product/issue-tracking.md §2.1.1` 기준.

- D1. 도메인 — Issue Aggregate Root, IssueKey VO (backend-engineer + Maxi)
- D2. 명세 — Given/When/Then. 7 엣지 케이스 (중복 키 / 권한 / 전이 위반 / 대용량 / 동시 편집 / 소프트 삭제 / 키 보존) (backend-engineer)
- D3. 데이터 모델 — Flyway. `issues`, `issue_key_redirects`. DATA.md 영속성 (db-engineer)
- D4. 백엔드 — `POST/GET/PATCH/DELETE /api/v1/issues`. `@Transactional`. pgmq 이벤트 발행 동일 트랜잭션 (backend-engineer + security-engineer 가드)
- D5. 백엔드 테스트 — MockK 단위 + Testcontainers 통합. TDD red→green→refactor (backend-engineer)
- D6. 프론트 UI — `IssueDetail.tsx`. TanStack Query 캐싱 + 낙관적 업데이트 (designer → frontend-engineer)
- D7. E2E + NFR — 생성→조회→수정→상태 전이→소프트 삭제→키 영속성 (qa-engineer)

### 진입 조건 점검 (`§0` 대조)

| 진입 조건 | 상태 | 처리 |
|---|---|---|
| identity-access §2.1 AuthenticationProvider | ✅ 완료 (PR #2~#8) | 활용 |
| identity-access §4.2 PERMISSION 가드 | ❌ 미완료 | 임시 가드 (`@PreAuthorize` 자리만 두고 본 가드는 FR-AU-12 후속) |
| project-workflow §1 FSM + §2.1 FR-WF-01 | ✅ 백엔드 완료 (PR #10) | 상태 전이 호출에 활용 |
| notification §1 STOMP / pgmq 트랜잭션 PoC | ⚠️ pgmq만 (PR #10 도입) / STOMP 미완 | pgmq 이벤트 발행만 본 PR. STOMP 구독은 후속 |
| DATA.md §이슈키 영속성 / §A.3 #5 이슈 키 prefix | ⚠️ DATA.md OK / prefix는 본 PR ADR에서 결정 | ADR 신규 — `<date>-issue-key-prefix-strategy` |

### 충돌 회피

- 진행 중인 PR #13 (`ui/project-workflow-fr-wf-01-frontend-2-pr`) 이 `apps/web/**` 영역 수정 중. **D6 wave는 PR #13 머지 확인 후 시작**.
- 진행 중인 PR #12 (`chore/eslint-no-console-frontend-logging-adr`) 가 frontend ESLint rule 추가 중. D6 frontend wave 시작 시 PR #12 머지 후 rebase 권장.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
