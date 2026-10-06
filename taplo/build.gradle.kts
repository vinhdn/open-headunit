import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// The system flavor shares the system user id, so it must carry the head unit ROM's platform key.
// platform.properties (git-ignored) names it: storeFile, storePassword, keyAlias, keyPassword.
val platformSigning: Properties? = rootProject.file("platform.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { FileInputStream(f).use { load(it) } } }

// The taplo companion: what a BAIC/Qinggan launcher embeds on the instrument cluster. It draws
// nothing itself; the head unit decodes the second Android Auto screen into its surface over
// contract/TaploLink. Its label carries "Navi" because that is how the launcher recognises a map.
android {
    compileSdk = 36
    namespace = "com.andrerinas.openheadunit.taplo"

    defaultConfig {
        applicationId = "com.qinggan.androidauto.navi"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        setProperty("archivesBaseName", "${applicationId}_${versionName}")
    }

    // Matches the head unit: standard pairs with its github/playstore builds, system with its
    // system build. The two must agree, or the signature permission on TaploLink refuses the bind.
    flavorDimensions.add("distribution")
    productFlavors {
        create("standard") { dimension = "distribution" }
        create("system") { dimension = "distribution" }
    }

    // Must match the head unit's key: TaploLink's permission is a signature permission.
    signingConfigs {
        if (platformSigning != null) create("platform") {
            storeFile = rootProject.file(platformSigning.getProperty("storeFile"))
            storePassword = platformSigning.getProperty("storePassword")
            keyAlias = platformSigning.getProperty("keyAlias")
            keyPassword = platformSigning.getProperty("keyPassword")
        }
        create("release") {
            rootProject.file("headunit-release-key.jks").takeIf { it.exists() }?.let { storeFile = it }
            keyAlias = "headunit-revived"
            val keyfile = rootProject.file("key.properties")
            if (keyfile.exists()) {
                val props = Properties().apply { load(FileInputStream(keyfile)) }
                props.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
                props.getProperty("storePassword")?.let { storePassword = it }
                props.getProperty("keyAlias")?.let { keyAlias = it }
                props.getProperty("keyPassword")?.let { keyPassword = it }
            } else {
                (System.getenv("HEADUNIT_KEYSTORE_PASSWORD") ?: project.findProperty("HEADUNIT_KEYSTORE_PASSWORD") as? String)
                    ?.let { storePassword = it }
                (System.getenv("HEADUNIT_KEY_PASSWORD") ?: project.findProperty("HEADUNIT_KEY_PASSWORD") as? String)
                    ?.let { keyPassword = it }
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val relConfig = signingConfigs.getByName("release")
            if (relConfig.storeFile?.exists() == true) signingConfig = relConfig
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        (this as KotlinJvmOptions).jvmTarget = "1.8"
    }
}

// The system flavor, debug and release alike, is signed with the platform key.
androidComponents {
    onVariants(selector().withFlavor("distribution" to "system")) { variant ->
        if (platformSigning == null) {
            logger.warn("${variant.name}: no platform.properties, so this build will not install as a system app")
            return@onVariants
        }
        variant.signingConfig?.setConfig(android.signingConfigs.getByName("platform"))
    }
}

dependencies {
    implementation(project(":contract"))
}
