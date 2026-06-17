# FR-MV-02 — 이동 시 히스토리 보존 + 링크 유지

> slug: fr-mv-02-move-preserve
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-17

## Brief

프로젝트 간 이슈 이동(FR-MV-01, 완료)이 이슈 `id`(UUID)를 보존하고 key만 변경하는 구조 위에서,
이슈에 연결된 모든 부속 데이터(히스토리·링크·Watcher·첨부 등 FK가 issue_id를 참조하는 것)가
이동 후에도 빠짐없이 보존됨을 보장·검증한다.

- 선행 완료: FR-MV-01(§6.1.1, 단건+서브태스크 동반 이동), FR-HS-01(§5.1.1, 이력), FR-LK-01(§5.3.1, 링크)
- D3(데이터 모델)은 이미 [x] — id 보존 + key만 변경 구조 기존재
- 남은 D단계: D1(도메인), D2(명세), D4(백엔드 FK 보존 검증), D5(invariant 비교 테스트), D6(프론트 갱신), D7(E2E)

원문(classify): backend / backend-engineer / issue-tracking BC

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
