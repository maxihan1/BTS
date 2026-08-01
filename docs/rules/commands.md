<!-- 디렉토리 구조 + 자주 쓰는 명령어 — CLAUDE.md 에서 분리 -->

# 디렉토리 · 명령어

> 정본. 이 파일. `CLAUDE.md` 는 링크만 갖는다.

## 디렉토리 (한눈에)

```
BTS/
├── CLAUDE.md / DEVELOPMENT.md / DATA.md   # 헌법 3종
├── docs/INDEX.md                          # docs 인덱스 라우터 (자동 생성)
├── docs/rules/                            # CLAUDE.md 에서 분리한 상세 절차
├── docs/sdd/                              # 설계 문서 26개 챕터
├── .claude/skills/bts-*/SKILL.md          # 워크플로우 스킬 10개
│                                          #   (bts, bts-start/domain/spec/plan/
│                                          #    review-plan/impl/codereview/merge/workflow)
├── .claude/agents/*-engineer.md           # sub-agent 6개
├── .claude/settings.json                  # 권한 (Bash deny 6종)
├── scripts/workflow/                      # classify-task.ts · 판별식 7종 (Node 22+)
├── scripts/doc-index/                     # 문서 인덱스 생성기
├── backend/modules/                       # 10개 모듈 = 9 BC + 배포 조립 `app`
│                                          #   (identity-access·issue-tracking·project-workflow·
│                                          #    shared-kernel·agile-planning·notification·
│                                          #    search-export-import·slack-integration·automation)
└── apps/web/                              # React 19 단일 SPA (packages/ 모노레포 분할 없음)
Maxi_wiki/BTS/                             # Obsidian (외부, 단방향 미러)
```

## 자주 쓰는 명령어

```bash
# 백엔드 (Gradle)
./gradlew test                      # 단위 + 통합 (Testcontainers)
./gradlew :backend:bootRun          # 로컬 실행
./gradlew flywayMigrate             # DB 마이그레이션
./gradlew ktlintCheck detekt        # 린트 + 정적 분석

# 프론트엔드 (pnpm 모노레포)
pnpm dev / test / test:e2e / lint / typecheck
pnpm verify                         # 통합 (lint + typecheck + test + build)

# 인프라
docker-compose -f infra/docker-compose.dev.yml up postgres redis minio

# 문서·검증
node scripts/build-doc-index.mjs           # 인덱스 재생성
node scripts/build-doc-index.mjs --check   # 비교만 (파일 안 씀)
node scripts/build-dashboard.mjs           # docs/progress.html 재생성
bash scripts/verify-master-plan.sh         # FR 정합·카운트 drift
pnpm test:workflow                         # 판별식 7종
pnpm test:doc-index                        # 인덱스 생성기 단위 테스트
bash scripts/doc-index/mutation-probe.sh   # 판별식 비-공허 확인 (클린 상태에서)
```

## 관련

- FR 변경 시 전수 동기화. [`docs/rules/fr-sync-checklist.md`](fr-sync-checklist.md)
- 문서 인덱싱 규칙. [`docs/rules/doc-index.md`](doc-index.md)
