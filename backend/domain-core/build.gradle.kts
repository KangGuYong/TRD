// 순수 도메인. 어떤 프레임워크에도 의존하지 않는다 — 시뮬레이션(ADM-600)과 운영이 재사용.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
