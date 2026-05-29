# FR-IS-01 상태전이 PR 2/2 — 전이 UI(프론트) + Playwright E2E

> slug: fr-is-01-transition-ui
> type: ui
> agent: frontend-engineer (E2E task는 qa-engineer)
> 생성: 2026-05-30

## Brief

PR #38(백엔드 가용전이, 머지됨)의 프론트 짝. 이슈 상세 화면이 현재 상태를 읽기전용 배지
(`IssueMetaPanel.tsx`, `data-testid="issue-state-badge"`)로만 보여주는데, 여기에 **상태 변경 컨트롤**
(shadcn select/dropdown)을 추가한다.

- `GET /api/v1/issues/{key}/transitions` 로 가용전이(서버 권위, validator/조건/권한 평가됨)를 받아 노출.
- `POST /api/v1/issues/{key}/transition` (body `{toStatusKey, expectedVersion}`) 로 전이 실행.
- 백엔드 계약: `GET .../transitions` → `{data:{transitions:[{fromStateKey,toStateKey,name,key}]}}`,
  key=`${fromStateKey}__${toStateKey}`. 에러 404(전이 없음)/422(워크플로우 미설정).
  전이 실행 에러: 409(expectedVersion 불일치 = 낙관적 잠금 충돌).

산출물: api 클라이언트 transition 함수(`src/api/issues.ts` 신규 — 현재 transition 함수 없음) +
Zod 스키마(backend DTO와 grep 정합) + MSW stateful 핸들러(`src/mocks/issue-handlers.ts`) +
i18n(409/422 에러 메시지) + Playwright E2E(happy 전이 / 가용전이 필터 / 에러).

분류: classify가 qa로 판정했으나(제목 "E2E" 키워드) 본질은 전이 UI 신규 구현 → Maxi 결정으로 ui 재분류.
D6(PR #39, 이슈 유형 셀렉터)는 별개 기능이며 이미 머지됨 — 같은 화면 파일을 건드리던 충돌은 D6 선머지로 해소.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec, 기존 docs/specs/2026-05-29-fr-is-01-transition.md 활용)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
