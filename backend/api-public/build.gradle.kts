plugins { id("io.spring.dependency-management") }
dependencyManagement { imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") } }
dependencies {
    implementation(project(":domain-core"))
    implementation(project(":persistence"))
    implementation(project(":audit"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")     // JWT 필터체인
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
