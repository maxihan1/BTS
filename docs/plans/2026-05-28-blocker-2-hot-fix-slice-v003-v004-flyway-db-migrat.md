# BLOCKER 2 hot-fix slice — V003+V004 Flyway 마이그레이션 경로 정리

> slug: blocker-2-hot-fix-slice-v003-v004-flyway-db-migrat
> type: migration
> agent: db-engineer
> primary_bc: issue-tracking
> 생성: 2026-05-28

## Brief

**사용자 원문**.
BLOCKER 2 hot-fix slice — V003+V004 Flyway 마이그레이션 경로 정리 (db/migration → db/migration/issue-tracking/). application-{dev,test}.yml Flyway locations 및 통합테스트 location 정리.

**Classify 결과**.
- type: migration
- agent: db-engineer
- slug: `blocker-2-hot-fix-slice-v003-v004-flyway-db-migrat` (50자컷)
- task_count: 0 (자동 분석 결과, /bts-plan 에서 확정)

**배경 (저장 컨텍스트 2026-05-28T15:22 / PR #28 머지 직후)**.
- V003 (PR #18 산출물) 과 V004 (PR #27 산출물) Flyway 마이그레이션 파일이 `backend/modules/issue-tracking/src/main/resources/db/migration/` 루트에 위치
- `application-{dev,test}.yml` 의 `spring.flyway.locations` 가 `classpath:db/migration/issue-tracking` 만 명시
- 결과. prod boot 시 Flyway 가 V003/V004 를 스캔 못 함 → 운영 incident 위험
- 통합테스트는 `classpath:db/migration` 까지 우회로 동작 중 (잠재 결함 마스킹)

**범위**.
- V003 + V004 파일을 `git mv` 로 `db/migration/issue-tracking/` 하위 이동
- 통합테스트 Flyway locations 정리 (우회 경로 제거)
- 회귀 가드. boot 시 Flyway 가 모든 마이그레이션을 스캔하는지 검증 테스트

**전제 의존**. PR #28 머지 완료 (BLOCKER 1 해소, main `302a637`).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
