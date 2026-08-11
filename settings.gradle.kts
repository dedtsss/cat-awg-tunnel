pluginManagement {
	repositories {
		mavenLocal()
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		mavenLocal()
		google()
		mavenCentral()
		maven { url = uri("https://jitpack.io") }
	}
}

rootProject.name = "Cat AWG Tunnel"

include(":app")
include(":logcatter")
include(":networkmonitor")
include(":tunnel")
include(":hevtunnel")
include(":pinger")
include(":pinger")
include(":catcore")
