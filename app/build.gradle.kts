import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.gradle.api.tasks.OutputDirectory
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

abstract class GenerateOssAssetsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

plugins {
    id("com.android.application")
}

val appCodeName = "NKTIDKSG"
val appVersionName = "0.9.6-IntDev_rev0"
val buildNumberFiles = (
    fileTree("src") { exclude("**/build/**") }.files + listOf(
        project.file("build.gradle.kts"),
        rootProject.file("build.gradle.kts"),
        rootProject.file("settings.gradle.kts"),
        rootProject.file("gradle/libs.versions.toml")
    ).filter { it.isFile }
).sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }

val buildContentHash = MessageDigest.getInstance("SHA-256").run {
    buildNumberFiles.forEach { file ->
        update(file.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
        update(0)
        update(file.readBytes())
        update(0)
    }
    digest().joinToString("") { "%02x".format(it) }
}
val buildTimestamp = DateTimeFormatter.ofPattern("yyMMdd-HHmm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(buildNumberFiles.maxOf { it.lastModified() }))
val generatedBuildNumber =
    "$appCodeName-v${appVersionName.substringBefore('-')}-$buildTimestamp-${buildContentHash.take(3)}"

data class OssCatalogEntry(
    val title: String,
    val coordinate: String,
    val license: String,
    val url: String,
    val body: String
)

fun jsonEscape(value: String): String {
    return buildString(value.length + 16) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}

data class PomLicense(val name: String, val url: String?)

data class PomMetadata(
    val name: String?,
    val projectUrl: String?,
    val licenses: List<PomLicense>
)

fun parsePomMetadata(pomFile: File): PomMetadata {
    return runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = factory
            .newDocumentBuilder()
            .parse(pomFile)
            .apply { documentElement.normalize() }
        fun firstTextByTag(tag: String): String? {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val value = nodes.item(i)?.textContent?.trim()
                if (!value.isNullOrBlank()) return value
            }
            return null
        }
        val projectName = firstTextByTag("name")
        val projectUrl = firstTextByTag("url")
        val licenseNodes = doc.getElementsByTagName("license")
        val licenses = buildList {
            for (i in 0 until licenseNodes.length) {
                val node = licenseNodes.item(i) ?: continue
                val children = node.childNodes
                var licenseName: String? = null
                var licenseUrl: String? = null
                for (j in 0 until children.length) {
                    val child = children.item(j) ?: continue
                    when (child.nodeName) {
                        "name" -> licenseName = child.textContent?.trim()
                        "url" -> licenseUrl = child.textContent?.trim()
                    }
                }
                if (!licenseName.isNullOrBlank()) {
                    add(PomLicense(name = licenseName, url = licenseUrl))
                }
            }
        }
        PomMetadata(
            name = projectName,
            projectUrl = projectUrl,
            licenses = licenses
        )
    }.getOrElse {
        PomMetadata(name = null, projectUrl = null, licenses = emptyList())
    }
}

fun readNoticeOrLicenseText(artifactFile: File): String? {
    if (!artifactFile.exists() || !artifactFile.isFile) return null
    val candidateNames = listOf(
        "META-INF/NOTICE",
        "META-INF/NOTICE.txt",
        "META-INF/NOTICE.md",
        "META-INF/LICENSE",
        "META-INF/LICENSE.txt",
        "META-INF/LICENSE.md"
    )
    return runCatching {
        ZipFile(artifactFile).use { zip ->
            candidateNames.firstNotNullOfOrNull { name ->
                val entry = zip.getEntry(name) ?: return@firstNotNullOfOrNull null
                zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.readText().takeIf { it.isNotBlank() }
                }
            }
        }
    }.getOrNull()
}

fun prettifyArtifactName(name: String): String {
    return name.split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            when (token.lowercase(Locale.US)) {
                "ktx" -> "KTX"
                "api" -> "API"
                "sdk" -> "SDK"
                else -> token.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                }
            }
        }
}

fun resolveDisplayTitle(group: String, name: String): String {
    return when {
        group == "androidx.activity" && name == "activity-ktx" -> "AndroidX Activity KTX"
        group == "androidx.appcompat" && name == "appcompat" -> "AndroidX AppCompat"
        group == "androidx.core" && name == "core-ktx" -> "AndroidX Core KTX"
        group == "androidx.constraintlayout" && name == "constraintlayout" -> "AndroidX ConstraintLayout"
        group == "com.google.android.material" && name == "material" -> "Material Components for Android"
        group.startsWith("org.jetbrains.kotlin") && name.startsWith("kotlin-stdlib") -> "Kotlin Standard Library"
        group == "com.google.android.gms" && name == "play-services-location" -> "Google Play services Location"
        group.startsWith("androidx.") -> "AndroidX ${prettifyArtifactName(name)}"
        else -> prettifyArtifactName(name)
    }
}

val generatedOssAssetsDir = layout.buildDirectory.dir("generated/oss-assets")
val generatedOssFile = generatedOssAssetsDir.map { it.file("oss_licenses/oss_licenses_auto.json") }

val generateOssLicensesAutoJson = tasks.register<GenerateOssAssetsTask>("generateOssLicensesAutoJson") {
    outputDirectory.set(generatedOssAssetsDir)
    outputs.file(generatedOssFile)
    doLast {
        val runtimeConfigurationName = listOf(
            "debugRuntimeClasspath",
            "releaseRuntimeClasspath",
            "runtimeClasspath"
        ).firstOrNull { project.configurations.findByName(it) != null }
            ?: error("No runtime classpath configuration found for OSS generation.")

        val runtimeArtifacts = project.configurations
            .getByName(runtimeConfigurationName)
            .incoming
            .artifacts
            .artifacts
            .filterIsInstance<ResolvedArtifactResult>()

        val moduleArtifacts = runtimeArtifacts
            .mapNotNull { artifact ->
                val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                    ?: return@mapNotNull null
                id to artifact.file
            }
            .distinctBy { (id, _) -> "${id.group}:${id.module}:${id.version}" }

        val componentIds = moduleArtifacts.map { it.first }
        val pomByCoordinate = mutableMapOf<String, PomMetadata>()
        if (componentIds.isNotEmpty()) {
            val queryResult = dependencies.createArtifactResolutionQuery()
                .forComponents(componentIds)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                .execute()
            queryResult.resolvedComponents.forEach { component ->
                val id = component.id as? ModuleComponentIdentifier ?: return@forEach
                val pomArtifact = component.getArtifacts(MavenPomArtifact::class.java)
                    .filterIsInstance<ResolvedArtifactResult>()
                    .firstOrNull()
                    ?: return@forEach
                val coordinate = "${id.group}:${id.module}:${id.version}"
                pomByCoordinate[coordinate] = parsePomMetadata(pomArtifact.file)
            }
        }

        val entries = moduleArtifacts.map { (id, artifactFile) ->
            val coordinate = "${id.group}:${id.module}:${id.version}"
            val pom = pomByCoordinate[coordinate]
            val licenses = pom?.licenses.orEmpty()
            val licenseLabel = if (licenses.isEmpty()) {
                "License not specified"
            } else {
                licenses.joinToString(" / ") { it.name }
            }
            val licenseUrl = licenses.firstOrNull { !it.url.isNullOrBlank() }?.url
            val projectUrl = pom?.projectUrl
            val body = readNoticeOrLicenseText(artifactFile)
            OssCatalogEntry(
                title = pom?.name?.takeIf { it.isNotBlank() }
                    ?: resolveDisplayTitle(id.group, id.module),
                coordinate = coordinate,
                license = licenseLabel,
                url = licenseUrl ?: projectUrl ?: "https://mvnrepository.com/artifact/${id.group}/${id.module}",
                body = body ?: ""
            )
        }
            .distinctBy { it.coordinate }
            .sortedBy { it.coordinate.lowercase(Locale.US) }

        val outFile = generatedOssFile.get().asFile
        outFile.parentFile.mkdirs()
        val sourceDateEpochMillis = System.getenv("SOURCE_DATE_EPOCH")
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?.times(1_000L)
            ?: 0L
        val generatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(sourceDateEpochMillis))
        val json = buildString {
            append("{\n")
            append("  \"generatedAt\": \"").append(jsonEscape(generatedAt)).append("\",\n")
            append("  \"entries\": [\n")
            entries.forEachIndexed { index, entry ->
                append("    {\n")
                append("      \"title\": \"").append(jsonEscape(entry.title)).append("\",\n")
                append("      \"coordinate\": \"").append(jsonEscape(entry.coordinate)).append("\",\n")
                append("      \"license\": \"").append(jsonEscape(entry.license)).append("\",\n")
                append("      \"url\": \"").append(jsonEscape(entry.url)).append("\",\n")
                append("      \"body\": \"").append(jsonEscape(entry.body)).append("\"\n")
                append("    }")
                if (index != entries.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }
        outFile.writeText(json)
    }
}

android {
    namespace = "jp.linkserver.beastlocator"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "jp.linkserver.beastlocator"
        minSdk = 26
        targetSdk = 36
        versionCode = 202607291   // 2026, 07, 29, 1(年、月、日、その日のうちの何個目)
        versionName = appVersionName
        buildConfigField("String", "BUILD_NUMBER", "\"$generatedBuildNumber\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateOssLicensesAutoJson,
            GenerateOssAssetsTask::outputDirectory
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    testImplementation("junit:junit:4.13.2")
}
