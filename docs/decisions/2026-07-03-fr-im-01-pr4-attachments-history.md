<!-- FR-IM-01 PR4 첨부(zip 두-part 업로드)/이력(충실 재생) Import의 BC 경계·재사용·트랜잭션·occurredAt 주입 결정 ADR -->

# ADR — FR-IM-01 PR4 (첨부/이력 Import): 첨부 zip 두-part 업로드 + Jira changelog 충실 재생

- 날짜: 2026-07-03
- 상태: 제안 (게이트 1 승인 대기)
- 관련 FR: FR-IM-01 (Jira 마이그레이션 에픽 PR4 — 마지막 백엔드 PR)
- BC: search-export-import (논리) + issue-tracking (물리)
- 선행 ADR: [2026-07-02-fr-im-01-csv-json-import.md](2026-07-02-fr-im-01-csv-json-import.md) (PR1 — 에픽 상위 결정), FR-AC-01 [../adr/2026-06-15-fr-ac-01-attachment-storage.md](../adr/2026-06-15-fr-ac-01-attachment-storage.md), FR-HS-01 이슈 이력 2테이블

## 맥락 (Context)

SDD 10.6.3 Jira 마이그레이션 보존 대상은 "이슈 키 · 첨부 · 댓글 · **이력** · Worklog · 사용자 매핑". 이 중 코어(PR1) · 컴포넌트/버전/상태(PR2) · 댓글/Worklog(PR3)가 완료됐고, **첨부**와 **이력**이 PR4로 남았다. 코드베이스 조사(Explore) 결과.

1. **첨부 도메인 완성형 — FR-AC-01/02(issue-tracking).** `IssueAttachmentService.upload(actor, issueKey, filename, contentType, sizeBytes, input)` — 권한 `IssuePermission.UPDATE` → 타입검증(`AttachmentTypePolicy` MIME 화이트리스트, SVG/HTML/exe 제외) → ClamAV 스캔(`VirusScanPort`, **fail-closed** — 데몬 미가용=거부) → MinIO put(`bts-attachments`, `minioClient`) → repo insert. `createdAt = clock.instant()`(서버 시각 박제). 100MB 상한(Spring multipart).
2. **이력 도메인 — FR-HS-01(issue-tracking).** Jira식 2테이블 `issue_change_group`(actor_id, created_at) + `issue_change_item`(field, from/to_value, from/to_label). append-only(update/delete 없음, issues FK 없음). `IssueHistoryRecorder.record(before, after, actor, projectId)`는 **before/after 스냅샷 diff**로만 항목을 산출(임의 이벤트 주입 불가). insert SQL이 created_at을 생략 → 항상 `NOW()`. actor_id는 주입 경로 있음. `created_at`은 스키마상 `NOT NULL DEFAULT NOW()`라 **명시 삽입 자체는 허용**.
3. **Import 어댑터/파서 — 원본 시각 관통 패턴 확립(PR3).** 댓글/워크로그가 파서 raw String → shared VO `Instant`(`ImportComment.createdAt`) → 어댑터 → `CommentApplicationService.create(createdAt)`/`WorklogService.createImported(startedAt)`까지 원본 시각을 관통. 현재 파서는 `changelog`를 `skipChildren()`로 건너뛰고 `fields.attachment`를 미참조 → 둘 다 신규 추출.
4. **업로드 진입점은 단일 파일.** `POST /api/v1/imports`가 `file` MultipartFile 하나만 받고(`ImportAcceptCommand` 단일 stream), `import_jobs`(V604)에 첨부 관련 컬럼 없음. search 모듈 마이그레이션 대역 V600~, 현재 V604 → 다음 V605.

## 결정 (Decision)

### D1. 첨부 — zip 두 part 한 요청 + `IssueAttachmentService.upload` 위임 (서버 fetch 0 = SSRF 0)

`POST /api/v1/imports`에 **두 번째 multipart part** `attachmentsZip`(optional)을 추가한다. 매니페스트(`file`)와 zip을 각각 MinIO `bts-imports`에 저장하고, `import_jobs`에 nullable `attachments_object_key` 컬럼(**V605**)을 추가한다. worker는 zip을 임시 디렉토리로 추출(대용량 메모리 미적재, FR-AC-01 임시파일 선례) 후, 행별 `fields.attachment[].filename`을 zip 엔트리와 매칭해 `IssueAttachmentService.upload(actor=requester, ...)`로 위임한다.

- **왜 두 part 한 요청**. Jira export를 한 번에 업로드하는 원자적 UX. 기존 단일-파일 CSV/JSON 경로를 깨지 않고(첫 part 그대로) 둘째 part만 additive. 별도 엔드포인트(2-step job 상태 복잡)·단일 통짜 zip(format 판별/매니페스트 추출 신설, 기존 경로와 이질) 대비 변경 최소.
- **왜 서버 fetch 안 함**. Jira 첨부 URL을 서버가 다운로드하면 SSRF(내부망 요청 위조) 위험. 사용자가 zip으로 바이너리를 직접 올리면 서버 아웃바운드 0.
- **보안 게이트 재사용 + best-effort 강등**. import 첨부도 FR-AC-01의 ClamAV·MIME 게이트를 **그대로 통과**(우회 없음). 단 import 문맥에서 스캔 실패(clamd 미가용)·MIME 거부·zip 엔트리 부재는 **행 실패가 아니라 첨부 단위 best-effort 경고**로 강등(전건 실패 방지, 에픽 경고 로그 계승).
- **원본 첨부 시각**. `upload`가 clock으로 박제 → 원본 보존이 필요하면 `upload`에 optional `createdAt` 주입 파라미터 추가(댓글 `create(createdAt)` 선례 미러). 스펙에서 확정.

### D2. 이력 — Jira changelog 충실 재생 (occurredAt 주입 + detector 우회 경로)

Jira `changelog.histories[]`의 각 `history`를 `IssueChangeGroup` 1건(actor=원본 author 이메일 해석, createdAt=원본 `created` 시각)으로, 각 `item`을 `IssueChangeItem`(field=매핑, fromValue/toValue=Jira `fromString`/`toString`)으로 재생한다. `IssueHistoryRecorder`에 **import 전용 기록 경로**(예 `recordImported(group)`)를 신설해 detector를 우회하고, `insertGroup`/`SQL_INSERT_GROUP`을 **created_at 명시 삽입**으로 확장한다(스키마 변경 0 — 컬럼은 이미 존재).

- **왜 충실 재생(Maxi 결정)**. 마이그레이션의 목적은 원본 변경이력 보존. provenance 단일 엔트리는 상세 이력을 잃는다.
- **Jira 표시 문자열 그대로 저장 — BTS 라벨 resolver 미적용**. `IssueChangeLabelResolver`는 현 BTS 엔티티(type/version/user 등)를 조회해 라벨을 박제하나, import된 과거 Jira 상태는 현 BTS 엔티티로 매핑 불가. Jira가 changelog에 이미 담아준 `fromString`/`toString`(사람이 읽는 표시값)을 fromValue/toValue로 직접 저장.
- **status 변경은 텍스트 이력이지 전환 재생 아님**. FR-HS는 status를 라벨 범위 밖(project-workflow BC)으로 뒀으나, import 이력의 status는 BTS FSM 전환을 실행하는 게 아니라 "field=status, To Do→In Progress" 텍스트 사실을 기록하는 것 → project-workflow BC 미개입, BC 침범 아님.
- **occurredAt 주입의 안전성**. append-only 이력에 과거 시각 삽입은 감사 무결성 관점에서 예외적이나, import는 원본 시각을 보존하는 것이 목적이며 조회 인덱스가 `(issue_id, created_at DESC, id DESC)`라 정렬도 자연 정합. 컬럼이 이미 `NOT NULL DEFAULT NOW()`라 마이그레이션 불필요.
- **필드 매핑**. Jira 필드명(status/priority/assignee/summary/description/resolution/labels/Fix Version/Component 등) → BTS `IssueChangeItem.field` 문자열 매핑 테이블. 미매핑 필드는 원본 필드명을 보존하거나 스킵+경고(스펙 확정).

### D3. cross-BC 경계 — 기존 포트 확장, 신규 BC 0

첨부·이력 모두 issue-tracking BC 내부 서비스(`IssueAttachmentService`·`IssueHistoryRecorder`)라 `IssueImportAdapter`가 직접 호출(PR3 댓글/워크로그 동형, cross-BC 포트 불필요). shared-kernel `IssueImportCommand`에 `attachments`/`changelog` 중첩 VO를 additive 확장. search BC는 issue-tracking을 직접 import하지 않고 `IssueImportPort`로만 위임(불변).

### D4. 트랜잭션 — 행별 best-effort + 사전체크 (PR2/PR3 원칙 계승)

첨부 upload·이력 record는 어댑터의 행 `@Transactional`에 참여 → 예외 throw 시 rollback-only 오염. 잔여 throw 집합을 **호출 전 사전체크로 강등**한다: 첨부는 (권한 UPDATE·MIME 거부·**filename/contentType 길이[≤500/≤100]**·zip 부재)를 사전 판정 후 경고, 이력은 (author 이메일 미해석→null 폴백·시각 파싱 실패→스킵·미매핑 필드→스킵)를 사전 처리.

**★정정(eng-review BLOCKER-2)**. 초안은 "첨부 upload가 행 tx를 오염시키지 않음(MinIO/ClamAV I/O tx 밖)"을 무조건 주장했으나 부정확하다. upload 실행 순서 = 권한→MIME→scan→**MinIO put(tx 밖)**→**`AttachmentRepository.insert`(@Transactional REQUIRED, 행 tx 참여)**. put·scan은 insert 이전이라 throw해도 미오염(catch 안전)이 맞으나, **`insert` throw(예: Jira 과다 긴 filename이 VARCHAR(500) 초과)는 rollback-only를 세팅해 catch 무력·행 전체 롤백**. 따라서 정확한 계약은 — **insert 이전 실패(권한·MIME·scan·put·길이 사전체크·zip부재)만 best-effort 첨부 경고, insert throw는 행 원자성으로 롤백**. filename/contentType 길이를 upload 호출 전 사전체크해 insert throw를 예방한다(comment/worklog 사전체크 동형). 통합테스트 S10a(길이초과 사전체크→첨부만 스킵·이슈 커밋)·S10b(generic throw→행 롤백)로 실증.

**★스트림 close(eng-review BLOCKER-1)**. `IssueAttachmentService.upload`의 `input` 스트림은 **호출자 close 책임**(upload는 복사만). 어댑터는 `ImportAttachmentSource.open(...)`이 준 스트림을 `?.use{}`로 close(FR-AC-01 MinIO put 스트림 누수 회귀 방지).

dry-run은 유효성-예측 경고를 **별도 경로**로 미러(FORBIDDEN 미엮음, PR2 CONCERN-A 재발 방지) + zip 미다운로드(권한·filename 비어있음만 미리보기).

### D5. 범위 — 한 PR(첨부+이력), D6/D7 프론트는 후속

첨부·이력은 독립 기능이라 wave 병렬 dispatch. 에픽 D박스는 FR-IM-01 전체 완료 시 일괄 마킹, FR 카운트 123 불변. D6/D7(업로드 UI·진행률·E2E)는 후속 프론트 PR.

## 결과 (Consequences)

- **긍정**. SDD 10.6.3 마이그레이션 보존 대상 6종 전부 충족(에픽 종결 근접). 첨부는 기존 보안 게이트 100% 재사용(스캔 우회 0). 이력은 원본 충실. 신규 BC·신규 cross-BC 포트 0, 마이그레이션 1건(V605).
- **부정/리스크**. (a) 충실 재생은 필드 매핑 손실·이력 행 폭증 가능 — 미매핑 필드 처리와 상한을 스펙에서 확정. (b) occurredAt 명시 삽입은 append-only 감사 테이블의 관례 예외 — ADR로 근거 박제. (c) 첨부 upload가 행 tx를 오염시키지 않는지(내부 I/O tx 밖 설계 의존) 통합테스트 필수. (d) PR 규모 큼(12~16 task) — wave 분해로 관리.
- **되돌리기**. 첨부/이력은 additive(기존 import 경로 불변). 문제 시 파서에서 `attachments`/`changelog` 추출을 비활성화하면 PR1~3 동작으로 회귀.

## 코드리뷰 후속 정정 (게이트 2, 2026-07-03)

- **★C1 정정 — 첨부 multipart 상한은 module-local yml이 아니라 프로그래매틱 @Bean**. 초안(D1)은 첨부 zip(500MB) 상한을 `search-export-import`의 `application-dev.yml`/`application-test.yml`로 뒀으나, 이 모듈은 `@SpringBootApplication` 부팅 앱이 없는 라이브러리(memory `no-cross-bc-deployment-assembly`)라 (a) yml을 로드하는 부팅 앱 부재 (b) 동명 `application-dev.yml` 3개 classpath 충돌 시 승자 비결정이라 실효/검증이 없었다. → 두 yml 삭제, `ImportMultipartConfiguration`의 `MultipartConfigElement` @Bean으로 교체(`MultipartAutoConfiguration` `@ConditionalOnMissingBean` 결정적 override + 단위 테스트로 상한 어서트). **미결 명시**. 전역 multipart 상한은 모듈 공유 자원 — 실제 부팅 조립 도입 시 issue-tracking 100MB 첨부 상한과 **재조정 필수**(별도 부팅 앱 분리 또는 part별 앱-레벨 검증). 그때까지 이 @Bean은 "준비된 설정"이며 end-to-end 실효는 조립 시점에 승격 검증한다.
- **★C2 정정 — 첨부 크기는 Jira 메타 아닌 실제 바이트**. 어댑터가 Jira 보고 `sizeBytes`를 MinIO Content-Length로 신뢰하면 실제 zip 바이트가 더 클 때 침묵 절단 후 성공 위장. → 항상 100MB bounded read로 실크기 계산(zip-bomb 방어), 초과 시 best-effort 스킵(`TOO_LARGE`).
- **★C3 정정 — 권한예외는 전파**. `applyAttachmentItem`이 `IssueAccessDeniedException`을 스킵-경고로 강등하던 것을 제거 → 행-원자성 안전망으로 전파(FORBIDDEN 롤백). D4의 best-effort 강등 집합은 insert 이전 실패(권한 사전체크·MIME·scan·put·길이·zip부재)만이며, **실행 시점 권한거부는 강등 대상이 아님**을 명확히 한다.
