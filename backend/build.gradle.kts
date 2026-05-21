// BTS 백엔드 루트 빌드 스크립트 — 공통 플러그인·의존성·툴체인 설정

plugins {
    kotlin("jvm") version "2.0.10" apply false
    kotlin("plugin.spring") version "2.0.10" apply false
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    // DB 마이그레이션 (Flyway 11.x — flyway-core 11.x 와 동일 major 버전으로 호환)
    id("org.flywaydb.flyway") version "11.0.0" apply false
    // jOOQ 코드 생성 (Gradle 8 + jOOQ 3.19+ 호환 안정 버전)
    id("nu.studer.jooq") version "9.0" apply false
}
