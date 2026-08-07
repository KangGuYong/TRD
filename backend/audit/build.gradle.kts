plugins { id("io.spring.dependency-management") }
dependencyManagement { imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") } }
dependencies {
    implementation(project(":persistence"))
    implementation("org.springframework.boot:spring-boot-starter")
}
