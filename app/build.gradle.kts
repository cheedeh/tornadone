plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Version — prefer values injected via `-PversionName=X.Y.Z -PversionCode=N`
// (used by CI). Fall back to static defaults for local development builds.
// ---------------------------------------------------------------------------
val appVersionName: String = findProperty("versionName")?.toString() ?: "0.1.0"
val appVersionCode: Int    = findProperty("versionCode")?.toString()?.toInt() ?: 1

// ---------------------------------------------------------------------------
// Signing — read credentials from environment variables so that the keystore
// secrets are never committed to source control. The signing config is only
// applied when all three variables are present (i.e. in CI). Local debug
// builds continue to use the default debug keystore.
// ---------------------------------------------------------------------------
val storePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val keyAlias:      String? = System.getenv("SIGNING_KEY_ALIAS")
val keyPassword:   String? = System.getenv("SIGNING_KEY_PASSWORD")
val canSign: Boolean = listOf(storePassword, keyAlias, keyPassword).all { !it.isNullOrBlank() }

android {
    namespace  = "com.tornadone"
    compileSdk = 35

    defaultConfig {
        applicationId         = "com.tornadone"
        minSdk                = 26
        targetSdk             = 35
        versionCode           = appVersionCode
        versionName           = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Register the signing config only when credentials are available.
    // This keeps local builds working without any secrets configured.
    if (canSign) {
        signingConfigs {
            create("release") {
                storeFile     = file("keystore.jks")  // decoded into app/ by CI
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias      = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword   = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.onnxruntime.android)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
