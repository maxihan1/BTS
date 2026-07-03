# FR-IM-01 PR4 — 첨부/이력 Import

> slug: fr-im-01-pr4-attachments-history
> type: backend (feature-equivalent, full workflow)
> agent: backend-engineer
> primary_bc: search-export-import (논리) + issue-tracking (물리)
> 생성: 2026-07-03

## Brief

FR-IM-01 Jira 마이그레이션 Import 에픽 4단계(마지막 백엔드 PR). PR1(코어)·PR2(컴포넌트/버전/상태)·PR3(댓글/Worklog) 완료 이후 후속.

SDD 10.6.3 마이그레이션 보존 대상 중 남은 두 가지 — **첨부파일**과 **이력(history)** 을 Import.

- 첨부. **zip 업로드 방식**(서버가 Jira URL을 fetch하지 않음 = SSRF 없음, Maxi 결정). Jira export 시 함께 받은 첨부 바이너리를 zip으로 업로드 → 생성된 이슈에 매핑.
- 이력. Jira changelog/history를 생성된 이슈 이력으로 보존(세부 정책 spec에서 확정).

에픽이므로 D박스 마킹은 FR-IM-01 전체 완료 시. FR 카운트(123) 불변, 신규 FR 0.

## 도메인 정리

- **BC**. 논리 search-export-import(파서/커맨드/업로드 계약) + 물리 issue-tracking(첨부·이력 어댑터 위임). PR1~3과 동일 구조, 신규 BC 0.
- **재사용 엔티티/서비스** (Explore 매핑, 실코드 확인).
  - 첨부. `IssueAttachmentService.upload(actor, issueKey, filename, contentType, sizeBytes, input)` (`issue-tracking/.../attachment/application/IssueAttachmentService.kt:101`). 권한 `IssuePermission.UPDATE`(댓글/워크로그와 동일 → 사전체크 패턴 재사용). 스캔 게이트 `VirusScanPort.scan`(ClamAV fail-closed) + `AttachmentTypePolicy.isAllowed`(MIME 화이트리스트, SVG/HTML/exe 제외). MinIO 버킷 `bts-attachments`(`minioClient`), storageKey=`issues/{issueId}/{attachmentId}`. **createdAt은 clock으로 서버 시각 박제**(원본 시각 보존하려면 시그니처 확장 필요).
  - 이력. `IssueHistoryRecorder.record(before, after, actor, projectId)`는 **before/after diff만** 산출 → 임의 Jira 이벤트 기록 불가. VO `IssueChangeGroup(issueId, issueKey, actorId?, items, createdAt?)` + `IssueChangeItem(field, fromValue?, toValue?, fromLabel?, toLabel?)`, 저장 `IssueChangeHistoryRepository.record(group)`(append-only). **author 주입 가능**(actorId), **occurredAt 주입 불가**(insert SQL이 created_at 생략 → NOW()). 스키마는 `created_at NOT NULL DEFAULT NOW()`라 **명시 삽입 허용 → 마이그레이션 불필요, insert SQL만 확장**.
- **Maxi 결정 (2026-07-03, 도메인 게이트)**.
  1. **이력 = 충실 재생(full replay)**. Jira `changelog.histories[]`의 각 history를 `IssueChangeGroup`(actor=원본 author, createdAt=원본 시각) 1건, 각 `item`을 `IssueChangeItem`(field=매핑, fromValue/toValue=Jira `fromString`/`toString`)으로 재생. **detector 우회 경로 신설**(`recordImported` 계열) + **occurredAt 주입**(insertGroup에 created_at 포함) 필요. Jira의 표시 문자열을 그대로 저장(BTS 라벨 resolver 미적용 — 과거 Jira 상태는 현 BTS 엔티티로 매핑 불가). status 변경도 텍스트 이력으로 기록(전이 재생 아님 → project-workflow BC 미개입).
  2. **첨부 zip = 두 part 한 요청**. `POST /api/v1/imports`에 매니페스트(`file`) + 첨부 zip(`attachmentsZip`, optional) 두 multipart part. `import_jobs`(V604)에 nullable `attachments_object_key` 컬럼 추가(**V605**, 머지 직전 재확인 — 동시 브랜치 충돌). worker가 zip 추출 후 행별 `fields.attachment[].filename` 매칭 → `IssueAttachmentService.upload` 위임.
  3. **PR 분할 = 한 PR**(첨부+이력). 두 기능 독립 → 병렬 dispatch. 규모 큼(예상 12~16 task).
- **기존 결정과의 관계**.
  - FR-HS-01은 status를 이력 라벨 범위 밖으로 뒀으나(project-workflow BC), import 이력의 status는 **BTS 전이 재생이 아니라 Jira 표시 문자열의 텍스트 기록**이라 BC 침범 아님.
  - PR3 "Import는 이벤트 재생 아님"(worklog 이력 생략)과 표면상 긴장이나, PR4 이력은 **import 행위의 부수 이력이 아니라 원본 Jira 변경이력 자체의 보존**이라 대상이 다름. dry-run·best-effort·tx 오염 사전체크 원칙은 계승.
  - 보안. 첨부는 FR-AC-01의 ClamAV+MIME 게이트를 **그대로 재사용**하되 import 문맥에서 실패는 **best-effort 경고**로 강등(전건 실패 방지). 우회 없음.
- **새 용어**. 없음(첨부·이력·changelog 기존 용어). glossary 갱신 불필요.
- **관련 ADR**. [docs/decisions/2026-07-03-fr-im-01-pr4-attachments-history.md](../decisions/2026-07-03-fr-im-01-pr4-attachments-history.md) (생성) — PR1 Import ADR(`2026-07-02-fr-im-01-csv-json-import.md`)의 후속.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
