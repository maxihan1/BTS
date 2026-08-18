# FR-IM-01 PR4 — 첨부/이력 Import — 스펙

> slug: fr-im-01-pr4-attachments-history
> 논리 BC: search-export-import / 물리 BC: issue-tracking
> 도메인/ADR: [../decisions/2026-07-03-fr-im-01-pr4-attachments-history.md](../decisions/2026-07-03-fr-im-01-pr4-attachments-history.md)
> 선행: PR1(#218)·PR2(#221)·PR3(#224). 신규 FR 0 (FR-IM-01 에픽 내부).

Jira 마이그레이션 에픽의 마지막 백엔드 PR. 남은 보존 대상 **첨부파일**과 **변경이력(changelog)** 을 생성 이슈에 함께 import. Maxi 결정(도메인 게이트) — 이력=충실 재생, 첨부 zip=두 part 한 요청, 한 PR.

---

## 사용자 시나리오 (Given-When-Then)

**S1 — 첨부 포함 import (happy path).**
- Given. 관리자가 Jira에서 이슈를 JSON으로 export하고, 첨부 바이너리를 zip으로 모음(엔트리 `<원본JiraKey>/<filename>`).
- When. `POST /api/v1/imports`에 매니페스트(`file`=JSON) + `attachmentsZip`을 함께 업로드.
- Then. 각 이슈 생성 후 `fields.attachment[]`의 각 항목을 zip에서 찾아 ClamAV·MIME 통과분만 첨부. 원본 업로드 시각·업로더 보존. 성공 요약 + 첨부 경고(있으면) 로그.

**S2 — 이력 충실 재생 (happy path).**
- Given. JSON에 `issues[].changelog.histories[]`(각 history=author/created/items[]).
- When. import 실행.
- Then. 각 history를 이슈 이력 그룹 1건(actor=원본 author, 발생시각=원본 created)으로, 각 item을 이력 항목(field=BTS 매핑, from/to=Jira 표시 문자열)으로 재생. status 변경도 텍스트 이력으로 기록(BTS 전환 실행 아님).

**S3 — clamd 미가용 중 첨부 import.**
- Given. ClamAV 데몬 다운.
- When. import 실행.
- Then. 이슈·이력·댓글은 정상 생성, 첨부는 **행 실패 없이 첨부 단위 best-effort 경고**("스캔 미가용, 첨부 스킵"). 전건 실패 아님.

**S4 — dry-run.**
- Given. `dryRun=true`.
- When. import 실행.
- Then. insert 0. 첨부(권한 없음/zip 부재/MIME 거부 예측)·이력(시각 파싱 실패/미매핑 필드/author 미해석 예측) 유효성 경고를 **별도 경로**로 미리보기. FORBIDDEN 행에 첨부/이력 경고를 중복 엮지 않음.

**S5 — CSV import (첨부/이력 미지원).**
- Given. CSV 매니페스트(첨부는 URL 참조, changelog 부재).
- When. import 실행.
- Then. 코어·컴포넌트/버전·댓글은 기존대로. 첨부/이력은 미수행(경고 없이 자연 무시 — CSV 형식 자체가 미지원). `attachmentsZip`이 CSV와 함께 오면 무시 + 1회 경고.

---

## 기능 요구사항 (R — 에픽 내부 요구사항, 신규 FR 아님)

### 첨부 (Attachments)

- **R1. 두 part 업로드.** `POST /api/v1/imports`가 `file`(매니페스트, 기존) + `attachmentsZip`(optional MultipartFile) 두 part 수용. zip을 MinIO `bts-imports`에 별도 오브젝트로 저장, `import_jobs.attachments_object_key`(nullable, V605)에 키 기록.
- **R2. zip 엔트리 매칭.** worker가 zip을 임시 디렉토리로 두고 `java.util.zip.ZipFile`(랜덤 액세스)로 엔트리명 인덱싱. 행별 `fields.attachment[].filename`을 **①`<sourceKey>/<filename>` ②플랫 `<filename>`** 순으로 매칭. 미발견 → 첨부 단위 경고("첨부 파일이 zip에 없음"). 한 이슈 내 동일 filename 다건 → zip에 동일 경로 1개뿐이면 첫 건 매칭 + 경고.
- **R3. 보안 게이트 재사용 + best-effort.** 매칭 바이너리를 `IssueAttachmentService.upload(actor=requester, ...)`로 위임(FR-AC-01 ClamAV·MIME 게이트 그대로). 실패 강등 — MIME 거부·감염·스캔 미가용·zip 부재 = **행 실패 아닌 첨부 단위 경고**, 이슈는 유지.
- **R4. 원본 메타 보존.** `IssueAttachmentService.upload`에 optional `createdAt: Instant?`·`uploadedBy: UUID?` 추가(null이면 기존 clock/actor). import는 `fields.attachment[].created`·`author.emailAddress`(→resolveByEmails, 미해석 시 requester)를 주입. 댓글 `create(createdAt)` 선례 미러. 기존 upload 호출부 동작 불변. **contentType** = Jira `mimeType` 있으면 사용, 없으면 파일명 확장자에서 파생(`URLConnection.guessContentTypeFromName`). **sizeBytes** = zip 엔트리 비압축 크기(`ZipEntry.getSize()`); **-1 반환(크기 미상) 시 실제 복사 바이트 카운트로 폴백**(eng-review NIT — `size_bytes BIGINT NOT NULL`에 -1 저장 의미 왜곡 방지). **filename/contentType 길이 사전체크**(≤500/≤100, R15 BLOCKER-2) 후 upload.
- **R5. 첨부 zip 상한.** zip part 최대 크기 config `bts.import.attachments-zip.max-size`(기본 500MB) + Spring `max-request-size`를 매니페스트+zip 합에 맞춰 상향. 개별 첨부는 FR-AC-01 100MB 상한 계승. zip-bomb 방어 — 엔트리 비압축 크기가 100MB 초과면 해당 첨부 스킵+경고. zip-slip 방어 — 엔트리명 `..`/절대경로 포함 시 스킵+경고(추출 경로 미사용이라 영향 낮으나 방어).

### 이력 (Changelog full replay)

- **R6. changelog 재생.** JSON `issues[].changelog.histories[]`의 각 history를 `IssueChangeGroup`(issueId=**생성된 BTS 이슈 id**, issueKey=**새 BTS 키**[sourceKey 아님], actorId=원본 author 해석, createdAt=원본 created) 1건, 각 `items[]`를 `IssueChangeItem`(field=매핑, fromValue/toValue=Jira `fromString`/`toString`, label=null)으로 재생. **BTS 라벨 resolver 미적용**(과거 Jira 상태를 현 BTS 엔티티로 매핑 불가 — Jira 표시 문자열 직접 저장).
- **R7. occurredAt 주입.** `IssueHistoryRecorder.recordImported(group)`(detector 우회) 신설 + `insertGroup`/`SQL_INSERT_GROUP`을 `created_at` 명시 삽입으로 확장(스키마 변경 0 — 컬럼 이미 `NOT NULL DEFAULT NOW()`). 기존 `record(before, after, ...)` 경로·insert(NOW() 박제) 불변.
- **R8. 필드 매핑.** Jira `items[].field` → BTS `IssueChangeItem.field` 매핑 테이블.

  | Jira field | BTS field |
  |---|---|
  | status | status |
  | priority | priority |
  | assignee | assignee |
  | summary | summary |
  | description | description |
  | resolution | resolution |
  | issuetype | type |
  | labels | labels |
  | Component / components | components |
  | Fix Version / fixVersions | fixVersions |
  | Version / versions (affects) | affectsVersions |
  | duedate | dueDate |
  | Epic Link | epic |

  **미매핑 필드(Attachment·Comment·Link·WorklogId·Sprint·Rank·custom 등) → 스킵 + 행당 유형별 집약 경고.** 근거 — 이들은 BTS 이력 모델의 필드-변경이 아니거나(Attachment/Comment/Link) 별도 채널로 import되므로(첨부=본 PR, 댓글/워크로그=PR3) 이력 재생 중복·모델 불일치.
- **R9. author 폴백.** `history.author.emailAddress`→resolveByEmails→actorId. **미해석 시 actorId=null**(원본 author 미상 — requester로 오귀속하지 않음. 댓글/워크로그의 requester 폴백과 의도적 상이 — 이력은 "누가 했나"의 사실 기록이라 오귀속이 왜곡).
- **R10. 시각 파싱.** `history.created` = `parseInstantOrNull`(ISO-8601 + `+0000` 오프셋 정규화, PR3 재사용). **파싱 실패 시 그룹 스킵 + 경고**(NOW() 대체 금지 — 과거 시각 왜곡 방지).
- **R11. 이력 행 상한.** 이슈당 change group 최대 1,000건. 초과분 스킵 + 경고("이력 상한 초과, N건 스킵"). changelog 페이지네이션 truncated(`maxResults < total`) 감지 시 경고.

### 공통 (파서·어댑터·tx)

- **R12. 파서 확장(JSON 전용).** `buildJsonRow`에 `sourceKey`(=`issueNode.key`)·`attachments`(=`fields.attachment[]`)·`changelog`(=`issueNode.changelog.histories[]`) 추출 추가. 이미 per-issue readTree된 `issueNode`에서 읽기만 확장(추가 스트리밍 복잡도 0). CSV는 첨부/이력 미지원(worklog JSON-전용 선례 계승).
- **R13. shared VO + cross-BC 바이너리 소스.** `IssueImportCommand`(shared-kernel)에 `sourceKey: String?`·`attachments: List<ImportAttachment>`·`changelog: List<ImportChangeGroup>` additive. `ImportAttachment(filename, authorEmail?, createdAt: Instant?, mimeType?, sizeBytes: Long?)`, `ImportChangeGroup(authorEmail?, occurredAt: Instant?, items: List<ImportChangeItem>)`, `ImportChangeItem(field=**raw Jira field**, fromValue?, toValue?)`. 기존 필드 불변. **첨부 바이너리 전달** — zip은 search BC의 MinIO에 있고 upload는 issue-tracking 어댑터가 수행 → BC 격리 위해 shared-kernel `fun interface ImportAttachmentSource { fun open(filename: String, sourceKey: String?): InputStream? }` 신설. `IssueImportPort.importIssue(cmd, attachments: ImportAttachmentSource? = null)`로 시그니처 확장(default null=첨부 스킵, 기존 호출 하위호환). **filename→zip엔트리 매칭 로직은 source 구현체(search)에 위치**(zip 소유), 어댑터는 스트림만 받아 upload.
- **R14. 어댑터 위임 + 필드 매핑.** `IssueImportAdapter.executeImport`에 createIssue 후 (기존 priority/labels/assignee/version/status/comments/worklogs 뒤) **attachments → changelog** 단계 추가. UPDATE 권한 사전체크(comments/worklogs와 공유). 이메일 해석은 `resolveByEmails` 단일 배치에 첨부 author·이력 author 이메일 합류(`FieldResolution` 확장). **Jira→BTS 필드 매핑(R8)은 어댑터가 수행**(BTS field 문자열은 issue-tracking 도메인 소유 — VO는 raw Jira field 운반, 어댑터가 매핑·미매핑 스킵+경고).
- **R15. tx 오염 사전체크.** 잔여 throw 강등 — 첨부(권한·zip 부재·MIME 사전판정·**`filename` 비어있음/길이>500·`contentType` 길이>100 사전체크**; put/scan 예외는 upload가 repo.insert 전 throw라 catch 안전) / 이력(author 미해석→null·시각 실패→스킵·미매핑→스킵으로 사전 정제, recordImported는 append-only nullable insert라 throw 위험 낮음). **정정(eng-review BLOCKER-2)** — `AttachmentRepository.insert`는 `@Transactional`(REQUIRED)이라 행 tx에 참여 → **insert 이후 실패(길이 초과 등)는 catch 무력·행 전체 롤백**. 따라서 upload는 **insert 이전 실패(권한·MIME·scan·put·zip부재·길이 사전체크)만 best-effort 경고**, insert throw는 행 원자성으로 롤백. 이 구분을 통합테스트로 실증(S10a 길이초과 사전체크 스킵·S10b generic throw 행 롤백). **스트림 close(eng-review BLOCKER-1)** — `upload`의 `input`은 호출자 close 책임이라 어댑터가 `source.open(...)?.use{}`로 close(FR-AC-01 누수 선례).
- **R16. dry-run 별도 경고 경로.** `warnAttachmentsIfNeeded`·`warnChangelogIfNeeded` — `validateDryRun` FORBIDDEN early-return **이후** 배치, `rowTriggersUpdate`에 미엮음(PR2 CONCERN-A). 행당 유형별 집약.

---

## 비기능 요구사항 (NFR)

- **N1. 메모리 안전.** zip은 임시파일 + `ZipFile` 랜덤 액세스(전량 메모리 적재 금지). 첨부 스트림은 `upload` 위임(내부 임시파일 버퍼). 이력은 per-issue 노드 범위(에픽 10만행 OOM 방지 기조 유지).
- **N2. 보안.** SSRF 0(서버 아웃바운드 fetch 없음, zip만). ClamAV·MIME 게이트 우회 0. FD/스트림 누수 0(`.use{}`, FR-AC-01 선례). zip-slip/zip-bomb 방어(R5).
- **N3. 성능.** Import 1만건 120s 게이트(에픽 NFR) 저해 없음 — 첨부 I/O는 tx 밖, 이력은 배치 insert.
- **N4. BC 격리.** search BC는 issue-tracking 직접 import 0(IssueImportPort만). ArchUnit 유지.

---

## API 인터페이스 (REST)

- `POST /api/v1/imports` (기존) — multipart 확장. part `file`(필수, 매니페스트), `attachmentsZip`(optional, application/zip). query/form `projectKey`·`format`·`dryRun` 불변. 응답 형식 불변(ImportJob 접수). CSV+`attachmentsZip` 동시 → zip 무시 + 접수 경고.
- 신규 엔드포인트 없음. GET `/api/v1/imports/{id}`(기존) 진행/결과 조회 그대로. 첨부/이력 경고는 기존 에러 로그 CSV(severity 컬럼)에 합류.

---

## 데이터 모델 변경

- **V605 (search-export-import).** `ALTER TABLE import_jobs ADD COLUMN attachments_object_key VARCHAR(500) NULL`. init_codegen.sql 미러 필수. **V번호 머지 직전 재확인**(동시 브랜치 충돌, memory `migration-vnumber-concurrent-branch-collision`).
- **이력 마이그레이션 0.** `issue_change_group.created_at`은 이미 존재(`NOT NULL DEFAULT NOW()`) — insert SQL만 코드 확장. issue-tracking 스키마 변경 없음.
- **첨부 테이블 변경 0.** `issue_attachments` 재사용(신규 컬럼 없음 — `created_at`/`uploaded_by`는 이미 존재, 서비스가 주입값으로 채우도록 코드만 변경).

---

## 엣지 케이스

- **E1.** `attachmentsZip` 없음 + `fields.attachment[]` 있음 → 첨부별 경고("zip 미제공"), 이슈 생성.
- **E2.** zip 있음 + 행에 `fields.attachment[]` 없음 → 첨부 단계 no-op.
- **E3.** 손상 zip(비-zip/깨진 CRC) → 접수는 성공하되 worker가 zip 열기 실패 시 전 행 첨부 스킵 + job 레벨 경고(이슈/이력은 정상).
- **E4.** changelog `items[]`가 매핑·미매핑 혼재 → 매핑분만 재생, 미매핑분 집약 경고. 전 item 미매핑이면 그룹 자체 스킵.
- **E5.** history author=null(Jira 시스템 변경) → actorId=null 그룹.
- **E6.** 동일 이슈 다수 history 동일 created(같은 시각) → 조회 인덱스 `(issue_id, created_at DESC, id DESC)`로 id 역순 안정 정렬(V018 설계 그대로).
- **E7.** 매우 큰 첨부(>100MB) → 스킵 + 경고(FR-AC-01 상한).
- **E8.** SVG/HTML/exe 첨부 → MIME 거부 경고(FR-AC-01 화이트리스트, 우회 없음).
- **E9.** 재실행(중복 import) → 이슈 새 키 재생성(에픽 MVP 허용), 첨부/이력도 중복 생성. dry-run 권장(PR1 정책 계승).
- **E10 (한계 명시).** 첨부 upload는 MinIO put(tx 밖)을 repo.insert 전에 수행 → 행 tx가 **예상외 throw로 롤백**되면 이슈/첨부 DB row는 롤백되나 이미 put된 **MinIO 객체는 고아**로 잔존(스토리지 누수). best-effort 사전체크로 예상 throw는 전부 강등되므로 롤백은 드묾 — MVP 수용, ADR D4 리스크에 박제. (대안: 첨부를 행 커밋 후 처리 = 원자성 약화라 미채택.)
- **E11 (additive 이력 공존).** createIssue(PR1)가 import 시각에 BTS-native "created" 이력 1건을 남김 → 재생된 Jira 이력(원본 과거 시각)과 공존. `(created_at DESC)` 정렬 시 "created"가 맨 위(가장 최근=import 시각)로 표시. 의미 — BTS "created"=BTS 진입 시각, 재생 이력=원본 변경. **additive 수용**(억제 안 함). D6/D7 UI가 둘 다 렌더.

---

## 제약 조건

- 첨부/이력 **JSON 전용**(CSV 미지원). worklog JSON-전용(PR3) 선례 일관.
- 첨부 바이너리는 **zip 업로드만**(서버 fetch 금지 — SSRF 0, ADR D1).
- 이력은 **field-change 유형만** 재생(Attachment/Comment/Link/Sprint 등 비-필드변경 미재생, R8).
- occurredAt 주입은 import 경로 전용(`recordImported`) — 일반 `record`의 NOW() 박제 불변.
- 새 키 자동생성(Jira 키 미보존) — `sourceKey`는 첨부 매칭·이력 issueKey 표기에만 사용, BTS 키로 승격 안 함.

---

## 측정 가능한 완료 기준

- [ ] 통합테스트(Testcontainers 실 tx) — S1 첨부 생성+메타 보존, S2 이력 재생+occurredAt/actor 보존, S3 clamd 미가용→첨부 경고·이슈 커밋, S4 dry-run→insert 0·FORBIDDEN 미엮음, R15 첨부/이력 예외→행 tx 미오염(예상외 throw만 행 롤백), R8 미매핑 필드 스킵, R11 상한 초과 스킵.
- [ ] 파서 단위테스트 — JSON `fields.attachment[]`·`changelog.histories[]`·`sourceKey` 추출, CSV 미지원 확인.
- [ ] V605 마이그레이션 적용 + init_codegen 미러 정합(SchemaMigrationTest류 카운트 정합).
- [ ] ktlint·detekt·ArchUnit(BC 격리·jOOQ repository) clean. verify-master-plan 통과(카운트 불변 123).
- [ ] 전 모듈 풀빌드 0 failures.

---

## Brainstorming Check

✅ 통과 (1회 iteration, 적대적 self-review). gap 4건 발견 후 반영 — (E10) MinIO 고아 객체 한계 명시, (E11) BTS-native "created" 이력 additive 공존, (R6) 이력 issueKey=새 BTS 키 명확화, (R4) contentType/sizeBytes zip 엔트리 파생. Maxi 결정 필요 gap 0(모두 합리적 기본값 + 리뷰 게이트 확인). E11(이력 공존)은 게이트1 요약에 특기.
