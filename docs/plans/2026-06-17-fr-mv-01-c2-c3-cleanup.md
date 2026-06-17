# FR-MV-01 후속 정리 (C2 preview 응답 DTO + C3 MoveIssueDialog 분리)

> slug: fr-mv-01-c2-c3-cleanup
> type: chore (후속 리팩토링, classify 자동판정 backend → 조정)
> agent: backend-engineer (C2) + frontend-engineer (C3)
> 생성: 2026-06-17

## Brief

FR-MV-01 D6/D7(#155) 코드리뷰에서 후속으로 미뤄둔 C2·C3 정리. 한 PR.

- **C2 (백엔드 issue-tracking)**: 이슈 이동 preview 응답(`MovePreviewService.MovePreview` → `IssueMoveController.preview`)이
  도메인 객체 `Version`·`Component`·`CustomFieldDefinition`을 그대로 직렬화한다.
  `Version.startDate/releaseDate: LocalDate?`엔 `@JsonFormat`이 없어 Spring 기본 설정에 우연히 의존
  (`write-dates-as-timestamps=true`면 배열 직렬화 → 프론트 ZodError). 도메인의 내부 필드(`deletedAt` 등)도 와이어 노출.
  → preview 전용 응답 DTO(`MovePreviewResponse`)를 web layer에 도입, Controller에서 매핑.
  이미 존재하는 `VersionResponse.from`/`ComponentResponse.from`/`CustomFieldResponse.from` 재사용.
  프론트 Zod는 이미 정식 스키마(versionResponseSchema 등)를 기대 → 계약 1:1 정합(프론트 무변경 검증).

- **C3 (프론트 apps/web)**: `MoveIssueDialog.tsx` 721줄에서 `NodeMappingSection` 컴포넌트(~232줄)+
  헬퍼(`buildInitialNodeState`/`isNodeMappingValid`)+타입(`NodeMappingState`)을 별도 파일로 추출.

## Maxi 확정 (게이트 전)
- C2 범위: Version·Component·CustomFieldDefinition 3개 전부 DTO화 (일관성)
- PR 구성: 한 PR (C2 백엔드 + C3 프론트)

## 도메인 정리 (← /bts-domain 채움 — fast-track skip)
신규 용어 0, ADR 0, 도메인 변경 0. skip.

## 스펙 (← /bts-spec 채움 — fast-track skip)
신규 FR 0, 동작 변경 0. 직접 기술 스펙은 Plan에 흡수. skip.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움 — fast-track skip)
