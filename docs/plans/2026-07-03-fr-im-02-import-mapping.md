# FR-IM-02 — Import 매핑 UI (필드/사용자 매핑)

> slug: fr-im-02-import-mapping
> type: api (풀스택 feature — 백엔드 검증 API + 프론트 매핑 마법사)
> agent: backend-engineer (프론트 task는 frontend-engineer, E2E는 qa-engineer)
> 생성: 2026-07-03

## Brief

FR-IM-02 Import 매핑 UI (필드/사용자 매핑). 선행 FR-IM-01(CSV/JSON Import, 전체 완료).
소스(CSV/JSON) 필드를 BTS 대상 필드에 매핑 + 소스 사용자를 BTS 사용자에 매핑 + 미매핑 처리.
매핑 검증 API + 매핑 마법사(다단계) 프론트.

- BC. search-export-import (물리 구현 위치는 domain 단계에서 확정 — FR-IM-01은 논리 search-export-import / 물리 자체 모듈 or issue-tracking 재사용 패턴 확인 필요)
- Plan slug (product). search/import-mapping
- product D단계.
  - D1. 도메인 — ImportMapping
  - D2. 명세 — 필드 매핑 + 사용자 매핑 + 미매핑 처리
  - D3. 데이터 모델 — import_mappings(import_job_id, source_field, target_field)
  - D4. 백엔드 — 매핑 검증 API
  - D5. 백엔드 테스트
  - D6. 프론트 UI — 매핑 마법사 (다단계)
  - D7. E2E

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
