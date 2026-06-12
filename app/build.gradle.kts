plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("com.chaquo.python")
}

chaquopy {
    defaultConfig {
        // recommended: start with the default (3.13)
        // Different Python versions support different package wheels.
        version = "3.13"

        pip {
            // Best practice: DON'T pin versions at first.
            // So Chaquopy would pick the newest compatible wheels from its repository.
            install("numpy")
            install("pandas")
            install("matplotlib")
            install("seaborn")
        }

        // Only needed if Gradle can't find a matching Python on our build machine:
        // buildPython("C:/Path/To/python.exe")
    }
}

android {
    namespace = "com.example.goforitGit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.goforitGit"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // for chaquopy
        ndk {
            // arm64-v8a for current Android devices, and emulators on Apple silicon
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    packaging {
        resources {
            // Fix duplicate META-INF resources (AGP mergeDebugJavaResource)
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.md"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

// Exclude Janino globally (Android-safe). For GH 7.0 offline + CH graphs this should not be needed at runtime.
configurations.all {
    exclude(group = "org.codehaus.janino", module = "janino")
    exclude(group = "org.codehaus.janino", module = "commons-compiler")
}

// Exclude conflicting logging implementations
configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-jdk14")
    exclude(group = "ch.qos.logback", module = "logback-classic")
    exclude(group = "ch.qos.logback", module = "logback-core")
    exclude(group = "log4j")
}

// Use h3-android instead of h3 (JNI compatibility)
configurations.configureEach {
    exclude(group = "com.uber", module = "h3")
}

dependencies {
    // firebase
    implementation(platform("com.google.firebase:firebase-bom:34.9.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Lifecycle Kotlin
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(libs.androidx.activity.ktx)

    // makes Task.await() work with coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // module needed for graphHopper to work
    implementation(project(":sourceversion-shim"))

    // GraphHopper 11.0 (latest) - Janino excluded globally above
    implementation("com.graphhopper:graphhopper-core:7.0") {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }

    // H3 for Android
    implementation("com.uber:h3-android:4.4.0")

    // MapLibre
    implementation("org.maplibre.gl:android-sdk:12.3.1")

    // NanoHTTPD for tile server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // SLF4J for Android (no-op or android logger)
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-android:1.7.36")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // Gson
    implementation("com.google.code.gson:gson:2.11.0")

    // OSMDroid
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Location services
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity)

    // Compose (if needed)
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material")
    implementation(libs.androidx.annotation)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}