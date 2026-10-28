plugins { id("org.jetbrains.kotlin.multiplatform"); id("com.android.library") }
kotlin {
    androidTarget()
    jvm()
    if (providers.gradleProperty("enableIos").orNull == "true") {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework { baseName = "LedgerFlowCore"; isStatic = true }
        }
    }
    jvmToolchain(17)
    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}
android { namespace = "com.qiye.ledgerflow.core"; compileSdk = 35; defaultConfig { minSdk = 26 } }
