plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

group = "top.focess"
version = "1.0.0-SNAPSHOT"

// Centralized dependency versions shared across all subprojects
extra["jacksonVersion"] = "2.18.0"
extra["jeromqVersion"] = "0.6.0"
extra["slf4jVersion"] = "2.0.13"
extra["logbackVersion"] = "1.5.13"
extra["jlineVersion"] = "3.26.1"
