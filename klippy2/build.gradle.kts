plugins {
    kotlin("multiplatform") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("com.google.devtools.ksp") version "2.2.0-2.0.2"
}

version = "0.1"

repositories {
    mavenCentral()
}

kotlin {
    val hostOs = System.getProperty("os.name")
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" && isArm64 -> macosArm64("native")
        hostOs == "Mac OS X" && !isArm64 -> macosX64("native")
        hostOs == "Linux" && isArm64 -> linuxArm64("native")
        hostOs == "Linux" && !isArm64 -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
        compilations.getByName("main") {
            cinterops {
                val libchelper by creating {
                    definitionFile.set(project.file("src/nativeInterop/libchelper.def"))
                    packageName("chelper")
                }
            }
        }
    }
    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val nativeMain by getting
        val nativeTest by getting

        commonMain.dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation("io.github.oshai:kotlin-logging:5.1.4")
        }

        nativeMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0-RC")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
        }
    }
}

dependencies {
    project(":klippy")
    add("kspCommonMainMetadata", project(":klippycodegen"))
    add("kspNative", project(":klippycodegen"))
}
