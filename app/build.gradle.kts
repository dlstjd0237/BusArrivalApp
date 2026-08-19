import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 인증키는 local.properties(로컬) 또는 DATA_GO_KR_KEY 환경변수(CI)에서만 읽는다. git에 올라가지 않는다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val busApiKey = providers.environmentVariable("DATA_GO_KR_KEY").orNull
    ?: localProps.getProperty("DATA_GO_KR_KEY").orEmpty()

// 태그 빌드면 v1.2.3 -> 1.2.3, 아니면 개발 버전
val ciVersionName = providers.environmentVariable("GITHUB_REF_NAME").orNull
    ?.removePrefix("v")?.takeIf { Regex("""\d+\.\d+\.\d+.*""").matches(it) }
val ciVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()

android {
    namespace = "dev.dlstjd.busarrival"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dlstjd.busarrival"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"
        buildConfigField("String", "BUS_API_KEY", "\"$busApiKey\"")
    }

    signingConfigs {
        create("release") {
            val ks = providers.environmentVariable("KEYSTORE_FILE").orNull?.let { file(it) }
            if (ks != null && ks.exists()) {
                storeFile = ks
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 키스토어 환경변수가 있으면 그걸로 서명. 없으면 로컬에선 디버그 키로 서명해 바로 설치 가능하게 하고,
            // CI에서는 unsigned 로 남긴다(디버그 키로 서명된 릴리스가 배포되는 사고 방지).
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug").takeIf { providers.environmentVariable("CI").orNull == null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
