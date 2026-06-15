<!-- ADR: ClamAV 첨부 바이러스 스캔 연동 (FR-AC-01 D2) — 정책·연동 방식 결정 -->

# 첨부 바이러스 스캔 모델 (FR-AC-01 D2 — ClamAV)

> 상태: Accepted
> 날짜: 2026-06-15
> BC: issue-tracking
> 관련 FR: FR-AC-01 D2 (첨부 보안 게이트 — 크기제한·MIME 화이트리스트·바이러스 스캔 중 마지막 잔존분)
> 관련: [2026-06-15-fr-ac-01-attachment-storage.md](2026-06-15-fr-ac-01-attachment-storage.md)(저장 모델), SDD §4.2.1(MinIO 첨부), §16 인프라, domain/issue-tracking.md(Attachment)
> 관련 PR: #145(업로드 백엔드)·#149(MIME 화이트리스트, 검증=권한직후·put이전)

## 맥락

FR-AC-01 D2(첨부 보안 게이트)는 3항목. 크기제한(완료)·MIME 화이트리스트 업로드(#149)·미리보기 MIME 화이트리스트(#147)는 닫혔고 **바이러스 스캔만 잔존**해 D2가 `[~]`. 사내 1K 규모 협업 워크스페이스에 업무 파일(문서/이미지/PDF/동영상)을 광범위하게 허용하므로, 악성코드가 첨부를 매개로 조직 내 전파되는 경로를 차단해야 한다.

기존 업로드 흐름(`IssueAttachmentService.upload`).
`권한 검증(UPDATE) → AttachmentTypePolicy.isAllowed(MIME #149) → 이슈 조회 → storagePort.put(InputStream→MinIO 스트리밍) → repository.insert(실패 시 보상 삭제)`.

조사로 드러난 제약.
- backend에 바이러스 스캔/ClamAV 관련 코드·의존성 0건 → 첫 구현.
- ClamAV 기본 `StreamMaxLength` 25MB < BTS 첨부 한도 100MB → 데몬 설정 상향 필요.
- 업로드 InputStream은 1회 read라 스캔과 MinIO put 양쪽에 동시 사용 불가.

## 결정

### 1. 장애 정책 — fail-closed (Maxi 확정 2026-06-15)

ClamAV 데몬(clamd) 미가용·타임아웃·통신 오류 시 업로드를 **거부**한다(검사 불가 = 차단). 데몬 장애 동안 첨부 업로드가 일시 중단되는 가용성 손해는 운영 복구로 해소한다.

근거. fail-open(검사 불가 시 통과)은 데몬을 DoS하거나 타임아웃시키면 악성코드가 그대로 통과하는 우회 경로를 만든다 — 스캔 기능의 의미가 사라진다. BTS는 fail-open이 보안 갭을 가린 과거 사고가 다수라 fail-closed가 일관 기조.

### 2. 스캔 시점 — 저장 전 동기 스캔 (Maxi 확정 2026-06-15)

MinIO 저장 *전*에 clamd로 스캔한다. 감염 파일은 저장소에 아예 들어가지 않는다.

근거. 저장 후 비동기 스캔은 감염 파일이 잠시 저장·노출되는 윈도우를 만들고 `pending scan` 상태·다운로드 게이팅·격리/삭제 상태머신 등 부품이 늘어난다. 기존 동기 업로드 흐름에 스캔 한 단계만 추가하는 쪽이 단순하고 안전.

### 3. clamd 연동 방식 — raw INSTREAM 프로토콜 직접 구현 (의존성 0) (Maxi 확정 2026-06-15)

clamd의 INSTREAM 프로토콜(TCP 소켓: `zINSTREAM\0` → `<4바이트 big-endian 길이><청크 바이트>` 반복 → `<0000>` 종료 → `stream: OK` / `stream: <signature> FOUND` 응답)을 Kotlin으로 직접 구현한다. 신규 외부 의존성을 추가하지 않는다.

근거. (a) #147(react-pdf/video.js 폐기→네이티브 HTML5)·#149(Tika 미도입)의 제로의존 기조와 일치, (b) clamd INSTREAM은 명령 수 적고 응답이 단순 문자열이라 직접 구현 부담 작음, (c) DEVELOPMENT.md §외부 의존성(신규 의존성 Maxi 승인) 게이트 — MinIO SDK는 정본 결정이라 승인했으나 ClamAV 클라이언트는 직접 구현으로 회피 가능.

**위험과 완화**. 프레이밍/타임아웃/응답 파싱을 직접 다루므로 버그 시 스캔 우회(fail-closed 무력화) 위험. 완화 — (a) 연결/read 타임아웃을 명시하고 타임아웃·예외는 모두 fail-closed(거부)로 귀결, (b) 응답이 정확히 `stream: OK`가 아니면 전부 거부(FOUND·미상 응답·빈 응답 모두 차단), (c) Testcontainers EICAR 표준 테스트 파일로 clean/infected 양쪽 + 데몬 다운 시나리오 통합 검증.

### 4. 스캔 입력 — 임시 파일 버퍼링 (저장 전 스캔의 귀결)

업로드 InputStream은 1회 read라 스캔과 MinIO put에 동시 사용 불가. 업로드 바이트를 디스크 임시 파일로 받아 ① clamd INSTREAM 스캔 → ② clean이면 임시 파일을 MinIO로 스트리밍한다. FR-AC-01 저장 ADR의 "100MB를 메모리에 전부 적재하지 않는다"는 디스크 임시파일로 유지된다(메모리 적재 아님). Spring multipart도 임계값 초과 업로드는 이미 임시파일 백업. 임시 파일은 try/finally로 확실히 정리.

### 5. clamd 스캔 한도 — StreamMaxLength ≥ 100MB

데몬 설정에서 `StreamMaxLength`(기본 25MB)·`MaxFileSize`·`MaxScanSize`를 BTS 첨부 한도(100MB) 이상으로 상향. 미상향 시 100MB 파일이 `INSTREAM size limit exceeded`로 거부되어 정상 업로드가 막힌다.

### 6. 에러 응답 (방향 — 정확한 코드/상태는 spec에서 확정)

- 감염 판정. 신규 에러코드(예 `ISSUE_ATTACHMENT_INFECTED`, BC `ISSUE_` 접두사 관례) + 4xx(거부). 응답 message는 일반화(시그니처명 등 내부정보 비노출).
- 데몬 미가용/타임아웃(fail-closed). 503(재시도 안내). 권한·MIME과 구분되는 일시적 거부임을 클라이언트가 인지 가능하게.

## Deviation 기록

- FR-AC-01 저장 ADR §2 "서버 경유 멀티파트 스트리밍(메모리 미적재)" → 스캔을 위해 디스크 임시파일 버퍼링 경유로 보강(메모리 미적재 원칙은 유지, 저장 전 스캔의 귀결).
- SDD §4.2.1/§12에 바이러스 스캔 API/권한 별도 정의 없음 → 업로드 경로 내부 게이트로 흡수(신규 엔드포인트·권한 없음).

## 결과

- 신규 외부 의존성: **없음**(raw INSTREAM 직접 구현).
- 신규 인프라: `infra/docker-compose.dev.yml`에 ClamAV(clamd) 서비스 + Testcontainers ClamAV 컨테이너(singleton `.apply { start() }` 패턴 — learnings 2026-05-21 stale port 함정 회피).
- 신규 코드: clamd INSTREAM 스캔 port/adapter + 업로드 흐름 스캔 단계 삽입 + 감염/장애 에러 처리.
- 완료 시 `docs/plan/product/issue-tracking.md` §4.2.1 D2 `[~]`→`[x]`, 대시보드 재생성.
