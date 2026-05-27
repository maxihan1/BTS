// Gradle 멀티 모듈 루트 설정 — BTS 백엔드 프로젝트 모듈 목록 정의

rootProject.name = "bts-backend"

include(":modules:identity-access")
include(":modules:project-workflow")
include(":modules:issue-tracking")
include(":modules:shared-kernel")
