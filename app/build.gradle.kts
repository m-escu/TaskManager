import java.util.Properties
val isIzzyOrFdroid = false


plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)

    alias(libs.plugins.baselineprofile)
}


android {
    namespace = "com.rk.taskmanager.app"
    // 37 resolves to platforms/android-37; the stable platform is android-37.0.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    // The app module runs stripReleaseDebugSymbols over the packaged native libs, so it must use
    // the same NDK that built them (see taskmanagerd) or stripping differs between machines.
    ndkVersion = libs.versions.ndk.get()

    lint {
        disable += "MissingTranslation"
    }

    dependenciesInfo {
        includeInApk = isIzzyOrFdroid.not()
        includeInBundle = isIzzyOrFdroid.not()
    }

    signingConfigs {
        create("release") {
            val isGitHubAction = System.getenv("GITHUB_ACTIONS") == "true"

            val propertiesFilePath = if (isGitHubAction) {
                "/tmp/signing.properties"
            } else {
                "signing.properties" // project root; see signing.properties.example
            }

            val propertiesFile = File(propertiesFilePath)
            if (propertiesFile.exists()) {
                val properties = Properties()
                properties.load(propertiesFile.inputStream())
                keyAlias = properties["keyAlias"] as String?
                keyPassword = properties["keyPassword"] as String?
                storeFile = if (isGitHubAction) {
                    File("/tmp/fork.keystore")
                } else {
                    (properties["storeFile"] as String?)?.let { File(it) }
                }
                storePassword = properties["storePassword"] as String?
            } else {
                // No signing properties available: fall back to the debug keystore so that
                // release builds remain installable on devices. Provide signing.properties
                // (or CI secrets) to sign with a real key.
                val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
                if (debugKeystore.exists()) {
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }


    buildTypes {
        release{
            isMinifyEnabled = isIzzyOrFdroid.not()
            isCrunchPngs = isIzzyOrFdroid.not()
            isShrinkResources = isIzzyOrFdroid.not()

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug{
            versionNameSuffix = "-DEBUG"
        }
    }

    defaultConfig {
        // Fork identity: different from upstream (com.rk.taskmanager) so both can coexist
        // on the same device. The Shizuku provider authority derives from this ID.
        applicationId = "com.mescu.taskmanager"
        minSdk = 26
        targetSdk = 37

        //versioning
        versionCode = 76
        versionName = "1.15.0-fork17"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

}

tasks.whenTaskAdded {
    if (isIzzyOrFdroid && name.contains("ArtProfile")) {
        println("Skipped Task $name")
        enabled = false
    }
}

dependencies {

    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    implementation(libs.androidx.room.ktx)

    implementation(project(":main"))
}
