plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "kr.trendstage"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")

    java {
        // 설계서는 Java 21이나 현 개발환경 JDK 17에 맞춰 시작. 추후 21로 상향 가능.
        toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    }

    tasks.withType<JavaCompile>().configureEach {
        // 소스는 UTF-8. (Windows 기본 코드페이지로 읽으면 한글 주석이 깨진다)
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
