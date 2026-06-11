# FR-HS-01 이슈 변경 이력 기록

> slug: fr-hs-01-issue-history
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-11

## Brief

**원문 요청**. FR-HS-01 이슈 변경 이력 기록 구현. 이슈의 필드 변경(상태/담당자/우선순위/본문 등)이 발생하면 변경 이력(누가/언제/무엇을/이전값→새값)을 이력 테이블에 기록하는 백엔드 전용 기능.

**FR**. FR-HS-01 (이슈 변경 이력) — SDD `02-requirements.md` §5.1.1, issue-tracking BC.
**선행**. FR-IS-01 (이슈 CRUD) 완료.
**후속 unblock**. FR-HS-02 (히스토리 조회 UI), FR-MV-02 (이슈 이동 시 히스토리 보존).

**병행 작업 (충돌 회피 대상)**.
- FR-MF-01 (identity-access, worktree `fr-mf-01-totp-authenticator`, PR #113) — 다른 BC, 충돌 없음.
- FR-MN-01 (issue-tracking 멘션, worktree `fr-mn-01-mention-notify`, PR #114) — **같은 BC**. Flyway 마이그레이션 V번호 충돌 + 같은 모듈 파일 충돌 주의.
  - issue-tracking 최신 마이그레이션 V017(FR-VR-03 조인테이블)까지 확인. 새 마이그레이션은 FR-MN-01이 선점할 번호를 피해 배정.
  - 본 작업은 **백엔드 전용**(이력 기록 리스너 + 테이블) — FR-MN-01의 이슈 상세 프론트와 표면 비중첩.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
