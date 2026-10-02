plugins {
    alias(libs.plugins.spautifaille.android.application)
    alias(libs.plugins.spautifaille.android.compose)
    alias(libs.plugins.spautifaille.hilt)
}

android {
    namespace = "com.spautifaille.app"

    defaultConfig {
        applicationId = "com.spautifaille.app"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // Signature release stable : le keystore et ses mots de passe viennent de variables
    // d'environnement (secrets GitHub Actions en CI), jamais du dépôt (public).
    // Variables lues via `providers` : compatible avec le configuration cache.
    val releaseKeystorePath = providers.environmentVariable("SPAUTIFAILLE_KEYSTORE_PATH").orNull
    val releaseKeystorePassword = providers.environmentVariable("SPAUTIFAILLE_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("SPAUTIFAILLE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("SPAUTIFAILLE_KEY_PASSWORD").orNull
    val releaseKeystoreFile = releaseKeystorePath?.takeIf { it.isNotBlank() }?.let { file(it) }
    val hasReleaseSigning = releaseKeystoreFile != null && releaseKeystoreFile.isFile &&
        listOf(releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrEmpty() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storeType = "pkcs12"
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Clé release fixe si les 4 variables SPAUTIFAILLE_KEY* sont fournies et que le
            // fichier existe ; sinon repli sur la clé de debug (builds locaux, forks sans secrets).
            // Attention : la clé de debug change d'une machine / d'un run CI à l'autre, donc
            // un tel APK ne peut pas se mettre à jour par-dessus un autre.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: keystore release absent ou incomplet (SPAUTIFAILLE_KEYSTORE_PATH, " +
                        "SPAUTIFAILLE_KEYSTORE_PASSWORD, SPAUTIFAILLE_KEY_ALIAS, SPAUTIFAILLE_KEY_PASSWORD) : " +
                        "l'APK release est signé avec la clé de debug (non stable, pas de mise à jour par-dessus).",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":player"))
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    kspTest(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.newpipe.extractor) // type Downloader du module de test (implementation dans :data)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.work.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
