import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        // JitPack repository for the CloudStream Gradle tooling.
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // CI provides the fork that owns the generated packages. Local builds can
        // override the neutral placeholder with -Pcloudstream.repository=owner/repo.
        setRepo(
            System.getenv("GITHUB_REPOSITORY")
                ?: providers.gradleProperty("cloudstream.repository").orNull
                ?: "OWNER/REPOSITORY"
        )

        authors = emptyList()
    }

    android {
        namespace = "com.cloudstream.extensions"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
                freeCompilerArgs.addAll(
                    listOf(
                        "-Xno-call-assertions",
                        "-Xno-param-assertions",
                        "-Xno-receiver-assertions",
                        "-Xannotation-default-target=param-property"
                    )
                )
            }
        }
    }


    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all Cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // these dependencies can include any of those which are added by the app,
        // but you dont need to include any of them if you dont need them
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle
        implementation(kotlin("stdlib"))                                              // Kotlin'in temel kütüphanesi
        implementation("com.github.Blatzar:NiceHttp:0.4.11")                          // HTTP kütüphanesi
        implementation("org.jsoup:jsoup:1.18.3")                                      // HTML ayrıştırıcı
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")   // CloudStream'in eski Android uyumluluğu için sabitlenmiştir
        implementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")          // JSON-nesne dönüştürme kütüphanesi
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")      // Kotlin için asenkron işlemler
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
    rootProject.projectDir.listFiles()
        ?.filter { it.isDirectory && File(it, "build.gradle.kts").isFile }
        ?.forEach { delete(File(it, "build")) }
}
