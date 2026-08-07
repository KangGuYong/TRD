plugins { id("io.spring.dependency-management") }

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") }
}

dependencies {
    implementation(project(":domain-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // pgvector 매핑 (임베딩 병합). 버전은 착수 시 고정.
    implementation("com.pgvector:pgvector:0.1.6")
}
