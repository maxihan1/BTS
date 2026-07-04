# FR-IM-02 D6/D7 — Import 매핑 마법사 프론트엔드

> slug: fr-im-02-d6-d7-mapping
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-04

## Brief

FR-IM-02 D6/D7 — Import 매핑 마법사 프론트엔드. 백엔드 3-PR(#230 PR-A 필드매핑 / #233 PR-B 사용자매핑 / #234 PR-C 값매핑) 완결, 프론트 UI만 남음.
FR-IM-01 D6/D7(#229) ImportForm(즉시 업로드 폼)을 analyze→필드/사용자/값 매핑→confirm→실행 마법사로 확장.
classify: type=ui, agent=frontend-engineer.

## 도메인 정리

- **BC**: search-export-import (프론트는 `apps/web` SPA, 백엔드 API 소비)
- **영향 엔티티**: 없음 (신규 도메인/엔티티 0 — 확정된 DTO 소비만)
- **새 용어**: 없음. 필드 매핑 / 사용자 매핑 / 값 매핑 / `AWAITING_MAPPING` 모두 ADR·백엔드 3-PR에서 확정
- **기존 결정 충돌**: 없음
- **관련 ADR**: [2026-07-03-fr-im-02-import-mapping.md](../decisions/2026-07-03-fr-im-02-import-mapping.md) — FR-IM-02 전체 아키텍처(D1~D5). "프론트 D6/D7 다단계 마법사(필드/사용자/값) + E2E"를 명시적 후속으로 규정. **신규 ADR 불필요**(이 PR은 확정된 흐름을 UI로 구현).
- **grill-with-docs 스킵**: 순수 UI 미러 작업(백엔드 완결·신규 용어 0). 도메인 정착 확인으로 갈음.
- **핵심 흐름 (ADR D1/D5)**: `analyze(업로드+persist→AWAITING_MAPPING)` → `필드매핑` → `(그 매핑 기준) distinct 사용자/값 수집` → `사용자/값 매핑` → `confirm(→PENDING+enqueue)` → 기존 폴링(FR-IM-01 재사용) → COMPLETED/FAILED. 순서 의존: 필드 매핑 먼저, 사용자/값 매핑 나중.

## 스펙

전체 스펙. [docs/specs/2026-07-04-fr-im-02-d6-d7-mapping.md](../specs/2026-07-04-fr-im-02-d6-d7-mapping.md)

핵심 요약.
- **두 모드 병존** (Maxi): 설정 Import 페이지 상단 토글 — "바로 가져오기"(기존 ImportForm 무변경) / "매핑하며 가져오기"(신규 마법사). 근거: analyze 경로는 첨부 zip 미지원.
- **마법사 흐름**: 업로드/분석(POST /analyze) → (CSV)필드 매핑(validate) → 사용자 매핑(collect/users) → 값 매핑(collect/values) → 검토·확정(POST /mapping, dryRun) → 기존 폴링(GET /{jobId}) 재사용. JSON은 필드 매핑 스킵.
- **백엔드 변경 0**. 확정된 REST 계약 소비만. Zod 스키마 백엔드 DTO 1:1(NON_NULL `.nullish()`).
- **dry-run 재적용** (Maxi): confirm 단발 소진 → dry-run 후 "이 매핑으로 실제 가져오기"는 보존 파일+매핑으로 재-analyze 후 실제 confirm.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 4건 반영 — G1 dry-run 재적용(Maxi 결정)·G2 하위단계 stale 재검증·G3 필드추천 프론트 휴리스틱·G4 동적 stepper.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
