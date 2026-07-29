package jp.linkserver.beastlocator

import org.json.JSONArray
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

data class AppReleaseNoteInfo(
    val tagName: String,
    val channel: ReleaseChannel,
    val releaseNotes: String,
    val releaseUrl: String,
    val isPrerelease: Boolean
)

data class AppUpdateInfo(
    val tagName: String,
    val channel: ReleaseChannel,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkAssetName: String?,
    val apkDownloadUrl: String?,
    val isPrerelease: Boolean,
    val intermediateReleaseNotes: List<AppReleaseNoteInfo> = emptyList()
)

object GitHubReleaseChecker {
    fun check(
        repositoryUrl: String,
        currentVersion: String,
        userAgentName: String = "BeastLocator",
        showLatestForTesting: Boolean = false
    ): AppUpdateInfo? {
        val repository = parseGitHubRepository(repositoryUrl)
            ?: error("Unsupported GitHub repository URL")
        val releaseInfos = fetchReleases(
            owner = repository.owner,
            repo = repository.name,
            userAgent = "$userAgentName/$currentVersion"
        ).map { it.toUpdateInfo() }
        val latest = selectLatestUpdate(releaseInfos, currentVersion, showLatestForTesting)
            ?: return null
        val intermediateReleaseNotes = releaseInfos
            .asSequence()
            .filter { !it.tagName.equals(latest.tagName, ignoreCase = true) }
            .filter { isNewerRelease(it.tagName, currentVersion, it.isPrerelease) }
            .filter { compareUpdateInfo(it, latest) < 0 }
            .sortedWith { left, right -> compareUpdateInfo(right, left) }
            .map { it.toReleaseNoteInfo() }
            .toList()
        return latest.copy(intermediateReleaseNotes = intermediateReleaseNotes)
    }

    private fun fetchReleases(owner: String, repo: String, userAgent: String): List<GitHubRelease> {
        val connection = (
            URL("https://api.github.com/repos/$owner/$repo/releases").openConnection()
                as HttpURLConnection
            ).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use(::readLimitedText).orEmpty()
            check(status in 200..299) { "GitHub returned HTTP $status" }
            return parseReleaseResponse(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedText(input: InputStream): String {
        val output = StringBuilder()
        input.bufferedReader().use { reader ->
            val buffer = CharArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                check(output.length + read <= MAX_RESPONSE_CHARS) {
                    "GitHub response is too large"
                }
                output.append(buffer, 0, read)
            }
        }
        return output.toString()
    }

    private fun parseReleaseResponse(response: String): List<GitHubRelease> {
        val json = JSONArray(response)
        return buildList {
            for (index in 0 until json.length()) {
                val release = json.optJSONObject(index) ?: continue
                if (release.optBoolean("draft", false)) continue
                val tagName = release.optString("tag_name").ifBlank { release.optString("name") }
                if (tagName.isBlank()) continue
                val assetsJson = release.optJSONArray("assets")
                val assets = buildList {
                    if (assetsJson != null) {
                        for (assetIndex in 0 until assetsJson.length()) {
                            val asset = assetsJson.optJSONObject(assetIndex) ?: continue
                            val name = asset.optString("name").trim()
                            val downloadUrl = asset.optString("browser_download_url").trim()
                            if (name.isNotBlank() && isTrustedGitHubDownloadUrl(downloadUrl)) {
                                add(GitHubReleaseAsset(name, downloadUrl))
                            }
                        }
                    }
                }
                add(
                    GitHubRelease(
                        tagName = tagName,
                        name = release.optString("name"),
                        body = release.optString("body"),
                        htmlUrl = release.optString("html_url"),
                        isPrerelease = release.optBoolean("prerelease", false),
                        assets = assets
                    )
                )
            }
        }
    }

    private fun GitHubRelease.toUpdateInfo(): AppUpdateInfo {
        val apkAsset = assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .maxWithOrNull(
                compareBy<GitHubReleaseAsset> { it.name.contains("beast", ignoreCase = true) }
                    .thenBy { it.name.contains("locator", ignoreCase = true) }
                    .thenBy { it.name.contains("universal", ignoreCase = true) }
            )
        val channelSource = listOf(tagName, name, apkAsset?.name.orEmpty())
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return AppUpdateInfo(
            tagName = tagName.ifBlank { name.ifBlank { "unknown" } },
            channel = detectChannel(channelSource, isPrerelease),
            releaseNotes = body,
            releaseUrl = htmlUrl,
            apkAssetName = apkAsset?.name,
            apkDownloadUrl = apkAsset?.browserDownloadUrl,
            isPrerelease = isPrerelease
        )
    }

    private fun AppUpdateInfo.toReleaseNoteInfo(): AppReleaseNoteInfo = AppReleaseNoteInfo(
        tagName = tagName,
        channel = channel,
        releaseNotes = releaseNotes,
        releaseUrl = releaseUrl,
        isPrerelease = isPrerelease
    )

    private data class GitHubRepository(val owner: String, val name: String)

    private data class GitHubRelease(
        val tagName: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val isPrerelease: Boolean,
        val assets: List<GitHubReleaseAsset>
    )

    private data class GitHubReleaseAsset(val name: String, val browserDownloadUrl: String)

    private fun parseGitHubRepository(repositoryUrl: String): GitHubRepository? {
        val uri = runCatching { URI(repositoryUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("github.com", ignoreCase = true)
        ) {
            return null
        }
        val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0].trim()
        val name = parts[1].removeSuffix(".git").trim()
        return if (owner.isBlank() || name.isBlank()) null else GitHubRepository(owner, name)
    }

    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 15_000
    private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
}

internal fun selectLatestUpdate(
    releases: List<AppUpdateInfo>,
    currentVersion: String,
    showLatestForTesting: Boolean
): AppUpdateInfo? = releases
    .filter {
        showLatestForTesting ||
            isNewerRelease(it.tagName, currentVersion, it.isPrerelease)
    }
    .maxWithOrNull(::compareUpdateInfo)

internal fun isNewerRelease(
    remoteTag: String,
    currentVersion: String,
    remoteIsPrerelease: Boolean = false
): Boolean = compareReleaseVersions(remoteTag, remoteIsPrerelease, currentVersion, false) > 0

internal fun compareReleaseVersions(
    leftTag: String,
    leftIsPrerelease: Boolean,
    rightTag: String,
    rightIsPrerelease: Boolean
): Int {
    val left = parseVersionNumbers(leftTag)
    val right = parseVersionNumbers(rightTag)
    val maxSize = maxOf(left.size, right.size)
    for (index in 0 until maxSize) {
        val leftPart = left.getOrElse(index) { 0 }
        val rightPart = right.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }
    val leftPriority = channelPriority(detectChannel(leftTag, leftIsPrerelease))
    val rightPriority = channelPriority(detectChannel(rightTag, rightIsPrerelease))
    if (leftPriority != rightPriority) return leftPriority.compareTo(rightPriority)

    val leftChannelBuild = parseChannelBuild(leftTag)
    val rightChannelBuild = parseChannelBuild(rightTag)
    if (leftChannelBuild != null &&
        rightChannelBuild != null &&
        leftChannelBuild.channel == rightChannelBuild.channel
    ) {
        return leftChannelBuild.number.compareTo(rightChannelBuild.number)
    }
    return 0
}

private fun compareUpdateInfo(left: AppUpdateInfo, right: AppUpdateInfo): Int =
    compareReleaseVersions(left.tagName, left.isPrerelease, right.tagName, right.isPrerelease)

private fun parseVersionNumbers(value: String): List<Int> {
    val dotted = Regex("""\d+(?:\.\d+)+""").find(value)?.value
    if (dotted != null) return dotted.split('.').mapNotNull { it.toIntOrNull() }
    val normalized = value.lowercase(Locale.US).removePrefix("v")
    val compact = Regex("""\d{3,}""").find(normalized)?.value
    if (compact != null) return compact.map { it.digitToInt() }
    return Regex("""\d+""").findAll(normalized).mapNotNull { it.value.toIntOrNull() }.toList()
}

private data class ChannelBuild(val channel: ReleaseChannel, val number: Long)

private fun parseChannelBuild(value: String): ChannelBuild? {
    val match = Regex(
        """(?:^|[-_.])(intdev|internal|beta|alpha|rc|stable|release)(?:[-_.]?rev)?[-_.]?(\d*)$""",
        RegexOption.IGNORE_CASE
    ).find(value.trim()) ?: return null
    val channel = detectChannel(match.groupValues[1], false)
    val numberText = match.groupValues[2]
    val number = if (numberText.isEmpty()) 0L else numberText.toLongOrNull() ?: return null
    return ChannelBuild(channel, number)
}

private fun detectChannel(value: String, isPrerelease: Boolean): ReleaseChannel {
    val detected = ReleaseChannelDetector.detect(value)
    return if (detected == ReleaseChannel.UNKNOWN && isPrerelease) {
        ReleaseChannel.PRE_RELEASE
    } else {
        detected
    }
}

private fun channelPriority(channel: ReleaseChannel): Int = when (channel) {
    ReleaseChannel.INTDEV -> 0
    ReleaseChannel.PRE_RELEASE -> 1
    ReleaseChannel.BETA -> 2
    ReleaseChannel.RELEASE -> 3
    ReleaseChannel.UNKNOWN -> -1
}

internal fun isTrustedGitHubDownloadUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase(Locale.US) ?: return false
    return host == "github.com" ||
        host.endsWith(".github.com") ||
        host.endsWith(".githubusercontent.com")
}
