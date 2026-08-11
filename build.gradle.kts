import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask

plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.kotlinxSerialization) apply false
	alias(libs.plugins.ksp) apply false
	alias(libs.plugins.androidLibrary) apply false
	alias(libs.plugins.compose.compiler) apply false
	alias(libs.plugins.ktfmt)
	alias(libs.plugins.licensee) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
	alias(libs.plugins.aboutlibraries) apply false
}

tasks.named<KtfmtCheckTask>("ktfmtCheckScripts") {
	// The recursive tunnel submodule is third-party code and contains a broken symlink.
	// Script formatting is covered by the project source-set tasks; this recursive scan cannot
	// safely traverse the submodule checkout.
	enabled = false
}

subprojects {
	apply {
		plugin(rootProject.libs.plugins.ktfmt.get().pluginId)
	}

	plugins.withId("org.jetbrains.kotlin.android") {
		extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension> {
			jvmToolchain(21)
		}
	}

	plugins.withId("org.jetbrains.kotlin.jvm") {
		extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension> {
			jvmToolchain(21)
		}
	}

	tasks.register<KtfmtFormatTask>("format") {
		description = "Format Kotlin code style deviations."
        source = project.fileTree(rootDir)
		include("**/*.kt")
		exclude("**/build/**", ".*generated.*", "**/amneziawg-tools/**", "**/.gradle/**")
	}

	tasks.withType<KtfmtCheckTask>().configureEach {
		// Gradle scripts are not part of the application source-set contract. Some upstream
		// scripts use a legacy style and are checked by the repository's normal build tasks.
		if (name == "ktfmtCheckScripts") enabled = false
	}

	ktfmt {
		kotlinLangStyle()
		srcSetPathExclusionPattern.set(Regex("^(.*[\\\\/])?(build|amneziawg-tools)([\\\\/].*)?$"))
	}
}
