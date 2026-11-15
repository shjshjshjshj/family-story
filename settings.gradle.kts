pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
rootProject.name = "FamilyStory"
include(":app")
// The phone app is kept outside this repository; it is built only when its folder is present.
if (file("phone").isDirectory) include(":phone")
