// Gradle 멀티 모듈 루트 설정 — BTS 백엔드 프로젝트 모듈 목록 정의

rootProject.name = "bts-backend"

include(":modules:identity-access")
include(":modules:project-workflow")
include(":modules:issue-tracking")
include(":modules:shared-kernel")
include(":modules:notification")
include(":modules:agile-planning")
include(":modules:search-export-import")
include(":modules:slack-integration")
include(":modules:automation")

// 배포 조립 모듈 — BC 들을 하나의 실행 가능한 Spring Boot 앱으로 통합 (프로덕션 배포 산출물).
// app 은 9개 BC (automation 포함) 를 조립한다 (automation-prod-assembly, PR #259 에서 배선 완료).
include(":modules:app")
