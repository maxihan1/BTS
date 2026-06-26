# FR-SR-03 PR2 — 저장 필터 공유

> slug: fr-sr-03-pr2-filter-shares
> type: backend
> agent: backend-engineer
> primary_bc: search-export-import
> 생성: 2026-06-26

## Brief

사용자 원문: "fr-sr-03 pr2 진행해줘" (저장 필터 공유 기능)

FR-SR-03 "필터 저장 및 공유"의 2번째 PR. PR1(#191)에서 SavedFilter CRUD(PRIVATE 전용) + 실행을 완료했고, PR2는 **공유** 기능을 담당한다.

PR1 완료 메모리 기준 PR2 범위(잠정 — bts-spec에서 확정).
- **공유 = 대상 지정** 방식(PROJECT / GROUP / AUTHENTICATED, 지라식). `saved_filter_shares` 조인 테이블 신설.
- **cross-BC 멤버십 포트** 2종(GroupMembership / ProjectAccess) 신설 — 둘 다 shared-kernel에 부재.
- **가시성 4경로**(소유 / PROJECT 공유 / GROUP 공유 / AUTHENTICATED 공유).
- 공유받은(소유 아님) 필터용 **403 재도입**(spec EC4). PR1에서 데드코드로 제거했던 `SavedFilterForbiddenException` + handleForbidden을 visible-but-not-owner 용도로 부활.
- 별표 UI 연동(FR-UX-02 favorites 재사용) — 프론트 범위 여부 spec에서 확정.

classify 결과: type=backend, agent=backend-engineer, slug=fr-sr-03-pr2-filter-shares.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
