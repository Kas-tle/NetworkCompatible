description = "NetherNet transport for Netty"

val nativePlatforms = listOf(
    "windows-x86_64",
    "windows-aarch64",
    "linux-x86_64",
    "linux-aarch64",
    "macos-x86_64",
    "macos-aarch64"
)

dependencies {
    api(libs.bundles.netty)
    api(libs.netty.codec.http)
    api(libs.expiringmap)
    api(libs.webrtc.java)

    implementation(libs.gson)
    nativePlatforms.forEach { platform ->
        implementation(libs.webrtc.java) {
            artifact {
                classifier = platform
            }
        }
    }

    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.junit.platform.launcher) 
}

configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withJavadocJar()
    withSourcesJar()
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "dev.kastle.netty.transport.nethernet"
}

tasks.register<JavaExec>("runDiscovery") {
    mainClass.set("dev.kastle.netty.util.nethernet.NetherNetScanner") 
    classpath = sourceSets["main"].runtimeClasspath 
}