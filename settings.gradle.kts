rootProject.name = "DSHA"

/*
 * 国内镜像只在本地构建时启用。
 *
 * 「回落到官方源」只对 404 成立：镜像回 5xx 时 Gradle 把它当成解析失败，
 * 整个构建直接停，不会再去问后面的仓库（实测 aliyun public 对个别构件回 502）。
 * CI 在境外，直连官方源又快又稳，所以那里不挂镜像；本地想关掉就设 DSHA_NO_MIRROR=1。
 *
 * 判断只能写在各自的块里：pluginManagement 先于脚本其余部分求值，看不到外面的 val。
 */
pluginManagement {
    val useMirror = System.getenv("CI") == null && System.getenv("DSHA_NO_MIRROR") == null
    repositories {
        if (useMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useMirror = System.getenv("CI") == null && System.getenv("DSHA_NO_MIRROR") == null
    repositories {
        if (useMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

include(":app")
