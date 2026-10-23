import java.security.MessageDigest
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.example.groqtranscribe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.groqtranscribe"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.7.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // 버전 설정을 바꾸면 모든 빌드 유형의 APK 파일명도 함께 바뀐다.
    applicationVariants.all {
        val apkName = "whisper-meeting-note-v${versionName}-${versionCode}-${name}.apk"
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = apkName
        }
    }
}
dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}

val verifyDiarizationArtifacts by tasks.registering {
    val expected = mapOf(
        "libs/sherpa-onnx-1.13.8.aar" to "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96",
        "src/main/assets/diarization/segmentation.int8.onnx" to "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
        "src/main/assets/diarization/nemo_en_titanet_small.onnx" to "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
    )
    inputs.files(expected.keys.map { file(it) })
    doLast {
        expected.forEach { (path, hash) ->
            val digest = MessageDigest.getInstance("SHA-256")
            file(path).inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Diarization artifact checksum mismatch: $path" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyDiarizationArtifacts) }
