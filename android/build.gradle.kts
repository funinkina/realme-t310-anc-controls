plugins { id("com.android.application") version "8.7.3" }

android {
    namespace = "dev.funinkina.budsanc"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.funinkina.budsanc"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // Committed so every CI build has the same signature and installs as an update. Debug key, not a secret.
    signingConfigs {
        getByName("debug") { storeFile = file("debug.keystore") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
