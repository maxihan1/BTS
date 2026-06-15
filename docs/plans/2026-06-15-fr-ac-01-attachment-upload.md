# FR-AC-01 첨부 업로드 (최대 100MB/파일)

> slug: fr-ac-01-attachment-upload
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-15

## Brief

FR-AC-01 — 이슈에 파일 첨부 업로드. 최대 100MB/파일. issue-tracking BC, SDD §4.2.1.

- 사용자 원문: "fr-ac-01 진행해줘"
- classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- BC: issue-tracking
- 영향 엔티티: Attachment (신규, 첫 구현). 대상 Issue.
- 용어: "어테처(Attachment)" — glossary 기등재(37행, "이슈에 첨부된 파일") → 신규 용어 추가 불필요
- 핵심 발견:
  - backend `Attachment` 클래스 0건 → FR-AC-01이 첨부 첫 구현
  - MinIO gradle 의존성·dev/test 인프라 전무 → **신규 외부 의존성(io.minio:minio) + 인프라 구성 필요**
  - `issues` PK = UUID → attachment도 UUID PK/FK (SDD §05.7의 BIGINT 표기는 stale → deviation)
  - SDD §11 첨부 API 미정의 → spec서 신규 설계
  - 다음 Flyway V번호: V023 (issue-tracking 최신 V022)
- Maxi 결정 (2026-06-15):
  - 저장소 SDK = MinIO Java SDK (`io.minio:minio`)
  - 업로드 방식 = 서버 경유 멀티파트 스트리밍
  - 범위 = 업로드 + 다운로드 + 목록 + 삭제 (FR-AC-02 미리보기는 별도 FR 제외)
- 기존 결정 충돌: 없음
- 관련 ADR: [docs/adr/2026-06-15-fr-ac-01-attachment-storage.md](../adr/2026-06-15-fr-ac-01-attachment-storage.md) (생성됨)


## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
