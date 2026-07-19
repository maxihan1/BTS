# FR-UX-06 PR5 — issue-tracking Dialog 흡수 (래퍼 API 확정)

> slug: fr-ux-06-pr5-dialog-absorb
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트 계층)
> 생성: 2026-07-19

## Brief

FR-UX-06 (Jira Cloud 방식 UI/UX 전면 개편)의 Phase 2 PR5.
issue-tracking BC의 화면들이 각자 Radix Dialog를 직접 import하고 Overlay 클래스
문자열을 복붙하는 것을, PR2에서 만든 공용 `components/ui/dialog` 래퍼로 흡수한다.

- **핵심 = 래퍼 API 확정.** Dialog 4분할(PR5 → PR6∥PR7 → PR8) 중 첫 PR.
  여기서 props 인터페이스를 잘못 정하면 PR6~8이 전부 재작업.
- 순수 프론트엔드 리팩터. 백엔드 코드 변경 0.
- classify 오판(api/backend) → controller가 ui/frontend-engineer로 정정(Maxi 승인).

### 🔒 반드시 지킬 계약 (선행 메모리)
- **role="dialog" 불변** — E2E 147건이 Radix `DialogPrimitive.Content` role에 의존.
  래퍼가 같은 primitive를 감싸는 한 DOM 계약 불변 → 흡수는 안전. 다른 걸로 교체 시 즉사.
- **PR8에서 ESLint 락** — `radix-ui` Dialog 직접 import 금지 룰(이번 PR 아님, 체인 마무리).
- 관련 메모리: [[fr-ux-06-jira-redesign-plan]] · [[frontend-nav-aria-label-e2e-contract]] ·
  [[frontend-zod-backend-dto-contract-gap]]

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
