object Constants {
    const val VERSION_NAME = "5.2.3"
    // 5.2.1 test-channel artifacts reached 50250. Keep local/offline artifacts newer as well.
    const val VERSION_CODE = 50253
    const val TARGET_SDK = 37
    const val MIN_SDK = 26

    const val NDK_VERSION = "28.2.13676358"
    // Keep Kotlin namespaces stable for upstream sync. Generated R and BuildConfig remain
    // here, while the installable application can coexist with WG Tunnel.
    const val APP_ID = "com.zaneschepke.wireguardautotunnel"
    const val APPLICATION_ID = "com.dedtsss.catawgtunnel"
    const val APP_NAME = "cat-awg-tunnel"

    // build types
    const val RELEASE = "release"
    const val CAT_TEST = "catTest"
    const val NIGHTLY = "nightly"
}
