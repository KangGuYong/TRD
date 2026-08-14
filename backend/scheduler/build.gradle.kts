plugins { id("io.spring.dependency-management") }
dependencyManagement { imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") } }
dependencies {
    implementation(project(":domain-core"))
    implementation(project(":persistence"))
    implementation(project(":audit"))
    implementation(project(":merge"))
    implementation("org.springframework.boot:spring-boot-starter")
    // 다중 인스턴스 단일 실행 보장(초기 Redis 불필요, 04 §2.5)
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.13.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.13.0")
}
