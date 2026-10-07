plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.plugin.serialization)
}

private fun Provider<String>.getInt() = get().toInt()

android {
    namespace = "com.wholphinplus.sources"
    compileSdk {
        version = release(libs.versions.compileSdk.getInt())
    }
    defaultConfig {
        minSdk = libs.versions.minSdk.getInt()
        consumerProguardFiles("consumer-rules.pro")
        // -PwholphinPlusPublic=true: the shared build (text badges, in-app updates from
        // -PwholphinPlusRepo=owner/name). Default: the personal build.
        val public = providers.gradleProperty("wholphinPlusPublic").orNull == "true"
        val repo = providers.gradleProperty("wholphinPlusRepo").orNull.orEmpty()
        // -PorcaCloudUrl=https://…: the Orca+ cloud. Not in the source: the official release gets
        // it from a repository secret, personal builds from .cloud-url. Without it, no cloud.
        val cloud = providers.gradleProperty("orcaCloudUrl").orNull.orEmpty().trim()
        buildConfigField("String", "CLOUD_URL", "\"$cloud\"")
        buildConfigField("boolean", "PUBLIC_BUILD", "$public")
        buildConfigField("boolean", "UPDATES_ENABLED", "${public && repo.isNotBlank()}")
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"" + (if (repo.isNotBlank()) "https://api.github.com/repos/$repo/releases/latest" else "") + "\"",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jellyfin.core)
    implementation(libs.jellyfin.api)
    implementation(libs.timber)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
