<!-- ADR: 이슈 키 prefix 결정 정책 — 사용자 직접 입력 (Jira 동일), 검증 + 예약어 차단 -->

# ADR — issue-key-prefix-policy

**일자**. 2026-05-22
**상태**. Accepted
**관련 PR**. `issue-tracking-bc-fr-is-01-crud`
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

BTS 이슈 키는 `<PROJECT_KEY>-<NUMBER>` 형식 (예. `ATLAS-123`). DATA.md §1.1 절대 원칙으로 **재발급 금지 + 영구 보존**이 명시되어 있다. 그러나 `PROJECT_KEY` (prefix) 자체를 어떻게 결정할지는 미정 — SDD `§A.3 #5` (이슈 키 prefix 결정) 가 "issue-tracking BC 진입 시 결정" 으로 위임.

prefix는 한 번 발급되면 외부 시스템 (Slack unfurl, 이메일, 위키, 외부 문서) 에 영구 인용되므로 사실상 **수정 불가**. 잘못 정한 prefix는 IssueKeyRedirect 로도 완전히 회수 못 함 (외부 시스템 수동 갱신 필요).

### 고려한 정책

**옵션 A — 사용자 직접 입력 (Jira 동일).**
- 프로젝트 생성 시 사용자가 prefix 영문 대문자 입력
- 장점. 의미 있는 prefix 선택 가능 (`ATLAS`, `INFRA`, `WIKI`). Jira 이주 경험 일치
- 단점. 입력 검증 + 중복/예약어 차단 가드 필요

**옵션 B — 프로젝트 이름에서 자동 생성 + 사용자 수정.**
- 프로젝트 이름 입력 시 자동 prefix 제안 (예. "Atlas Issues" → `ATLAS`), 사용자가 수정 가능
- 장점. UX 멘토링
- 단점. 자동 생성 규칙이 한글 프로젝트명/약어 패턴별로 복잡. 결국 사용자가 수정하므로 옵션 A에 UI 도움말 한 줄 추가 수준

**옵션 C — 시스템 자동 생성 (해시 기반).**
- 사용자 입력 없이 자동 생성
- 장점. 검증 부담 없음
- 단점. 의미 없는 prefix가 외부 시스템에 영구 인용됨 → UX 재앙

## 결정

**옵션 A — 사용자 직접 입력 (Jira 동일) 채택.**

### 입력 규칙

```
prefix 정규식. ^[A-Z][A-Z0-9]{1,9}$
- 첫 글자. 영문 대문자
- 이후. 영문 대문자 또는 숫자
- 길이. 2~10 자
```

### 예약어 차단 (대소문자 무관 비교)

운영/보안/혼동 가능성이 있는 단어는 prefix로 사용 불가.

| 카테고리 | 예약어 |
|---|---|
| 시스템 | `API`, `WWW`, `ADMIN`, `ROOT`, `SYSTEM`, `BTS`, `ATLAS` (BTS 자체 명) |
| 보안 | `NULL`, `UNDEFINED`, `TEST`, `DEBUG` |
| HTTP | `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS` |
| SQL | `SELECT`, `INSERT`, `UPDATE`, `DROP`, `TABLE`, `FROM`, `WHERE` |

예약어 목록은 `IssueKeyPrefixReservedWords` 상수 (`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueKeyPrefixReservedWords.kt`) 로 관리. 추가/제거는 ADR 갱신 + Maxi 승인.

### 중복 차단

- `projects.key` 컬럼 `UNIQUE` 제약
- 생성 시 race condition 방지 — `INSERT ... ON CONFLICT DO NOTHING + RETURNING` 패턴
- 충돌 시 사용자에게 "이미 사용 중인 prefix" 에러 (HTTP 409 Conflict)

### 변경 불가

- 한 번 발급된 prefix는 변경 불가 (DATA.md §1.1 의 키 영속성을 prefix 수준에서도 적용)
- 변경 필요 시 새 프로젝트 생성 + 이슈 이동 (FR-MV-01) — 옛 키는 `IssueKeyRedirect` 로 영구 보존

## 본 PR 적용 범위

FR-IS-01 본 PR은 **이슈 CRUD 코어**에 집중하므로 프로젝트 생성 API를 제공하지 않는다. 다음을 본 PR에서 도입:

1. `projects` 테이블 (V007 마이그레이션에 함께 포함) — `key VARCHAR(10) NOT NULL UNIQUE`, `key_sequence BIGINT NOT NULL DEFAULT 0`, `name VARCHAR(255) NOT NULL`
2. `data-dev.sql` 에 dev seed 프로젝트 1건 — `key='ATLAS'`, `name='Atlas Issues'`, `key_sequence=0`
3. `IssueKeyPrefixReservedWords` 상수만 본 PR 도입 (검증 로직은 프로젝트 생성 API 도입 PR 에서 활용)

프로젝트 생성/수정/삭제 API는 별도 PR (Project Management) 에서 사용자 입력 검증 + 예약어 차단 + UI 도입.

## 결과

### 긍정

- **Jira 이주 경험 일치** — 사용자에게 익숙한 prefix 결정 모델
- **영구 보존 가드 명시** — DATA.md §1.1 의 키 영속성이 prefix 수준에서도 일관 적용
- **외부 시스템 안전성** — 예약어 차단 + 정규식으로 잘못된 prefix (`API-123` 같은 RESTful URL 충돌) 사전 방지

### 부정 / 위험

- **prefix 결정 후 후회 — 회수 불가** — 사용자가 잘못된 prefix 선택 시 이슈 이동 + redirect 만 가능. UI 단계에서 "한 번 결정하면 변경 불가" 경고 표시 권장 (Project Management PR)
- **예약어 목록 진화** — 새 HTTP 동사/SQL 키워드/보안 단어 추가 시 ADR 갱신 부담. 자동화 (`grep -i` 기반 lint 룰) 검토 후속

## 관련

- `docs/plans/2026-05-22-issue-tracking-bc-fr-is-01-crud.md` §도메인 정리 §ADR-1
- `DATA.md §1.1` — 이슈 키 영구 보존
- `docs/sdd/05-data-model.md` — 이슈 키 스키마
- `docs/sdd/02-requirements.md §A.3 #5` — 이슈 키 prefix 결정 위임 항목 (본 ADR 로 해소)
