plugins { id("io.spring.dependency-management") }
dependencyManagement { imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") } }
dependencies {
    implementation(project(":domain-core"))
    implementation(project(":persistence"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.core:jackson-databind")   // 판정 근거(evidence_json) 직렬화
}
