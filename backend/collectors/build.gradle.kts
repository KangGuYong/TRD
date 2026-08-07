plugins { id("io.spring.dependency-management") }
dependencyManagement { imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") } }
dependencies {
    implementation(project(":domain-core"))
    implementation(project(":persistence"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // 외부 API 호출
}
