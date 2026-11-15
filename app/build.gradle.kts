import java.util.Properties
plugins { id("com.android.application") }
// Demo keys live in local.properties (git-ignored). Empty values simply disable online media preparation.
// -PpublicBuild leaves every key out, for an APK that is published: keys compiled into an APK can be read back.
val localKeys = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun key(name: String) = "\"" + (if (project.hasProperty("publicBuild")) "" else localKeys.getProperty(name) ?: "").trim().replace("\\", "").replace("\"", "") + "\""
// -PlocalLlm (debug only): generation goes to an OpenAI-compatible server on the development PC, which the emulator
// reaches as 10.0.2.2. Its port and key come from LOCAL_LLM_PORT and LOCAL_LLM_KEY in local.properties.
fun localLlm(field: String): String {
    if (!project.hasProperty("localLlm")) return "\"\""
    val port = (localKeys.getProperty("LOCAL_LLM_PORT") ?: "2048").trim()
    val value = if (field == "url") "http://10.0.2.2:$port/v1" else (localKeys.getProperty("LOCAL_LLM_KEY") ?: "").trim()
    return "\"" + value.replace("\\", "").replace("\"", "") + "\""
}
android {
    namespace = "kr.cabinstory"
    compileSdk = 35
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    defaultConfig {
        applicationId = "kr.cabinstory"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "0.7.0"
        buildConfigField("String", "GEMINI_API_KEY", key("GEMINI_API_KEY"))
        buildConfigField("String", "GEMINI_IMAGE_MODEL", key("GEMINI_IMAGE_MODEL"))
        buildConfigField("String", "TOUR_API_KEY", key("TOUR_API_KEY"))
        buildConfigField("String", "LOCAL_LLM_URL", "\"\"")
        buildConfigField("String", "LOCAL_LLM_KEY", "\"\"")
        buildConfigField("String", "LOCAL_LLM_TEXT_MODEL", "\"" + (project.findProperty("localLlmText") ?: "gemini-4-hydrogen") + "\"")
        buildConfigField("String", "LOCAL_LLM_IMAGE_MODEL", "\"gemini-nano-banana-2.1\"")
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "LOCAL_LLM_URL", localLlm("url"))
            buildConfigField("String", "LOCAL_LLM_KEY", localLlm("key"))
        }
        getByName("release") {
            // shortcut: local competition build uses a private local key; replace for market distribution.
            signingConfig = signingConfigs.create("competition") {
                storeFile = file("../signing/cabin-story.jks")
                storePassword = System.getenv("CABIN_KEY_PASSWORD")
                keyAlias = "cabin-story"
                keyPassword = System.getenv("CABIN_KEY_PASSWORD")
            }
        }
    }
}
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
}
