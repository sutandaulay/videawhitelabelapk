import java.io.FileInputStream
import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

val localProperties = Properties()
val propsFile = File(rootDir, "env.properties")

if (propsFile.exists()) {
    localProperties.load(FileInputStream(propsFile))
}

rootProject.name = localProperties.getProperty("ROOT_NAME") ?: "APP"
include(":app")
 