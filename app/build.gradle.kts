plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.rgraphx"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.rgraphx"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // O SDK do OpenCV no repositório só tem binários para estas duas ABIs
        // (arm64-v8a = celulares, x86_64 = emulador).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // A libopencv_java5.so depende da libc++_shared.so
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // :opencv e :app geram a libc++_shared.so; mantém só uma cópia no APK
    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":opencv"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}