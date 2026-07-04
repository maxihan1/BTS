# FR-IM-02 PR-B — Import 사용자 매핑 (전 작성자 필드)

> slug: fr-im-02-pr-b-user-mapping
> type: backend
> agent: backend-engineer
> 생성: 2026-07-04

## Brief

FR-IM-02 순차 에픽의 두 번째 PR(PR-B). PR-A(#230, 필드 매핑)에 이어 **사용자 매핑**을 추가.
소스 파일에서 발견되는 **전 작성자 식별자**(reporter/assignee + 댓글/worklog/첨부/changelog 작성자)를
BTS 사용자 UUID로 매핑. `import_user_mappings` 테이블 신규. analyze→validate→confirm 흐름을 사용자 매핑으로 확장.

- BC. search-export-import (`com.bts.search.imports`) — classify가 identity-access로 오분류, domain에서 정정.
- 선행. PR-A(#230, 필드 매핑 완료), FR-IM-01(CSV/JSON Import 전체 완료).
- 후속. PR-C(값 매핑 status/type/priority), 프론트 D6/D7(매핑 마법사).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
