package jp.linkserver.beastlocator

import jp.linkserver.goldy.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Untrusted metadata only. Never serializes a VerifiedApk, signature authority or install lease. */
internal object UpdateTransferSnapshot {
    fun encode(candidate: UpdateCandidate, requireAsset: Boolean = true): String {
        val release = candidate.release
        val assets = JSONArray()
        release.assets.forEach { asset -> assets.put(JSONObject().put("id", asset.id).put("name", asset.name)
            .put("url", asset.downloadUrl).put("size", asset.size ?: JSONObject.NULL).put("digest", asset.digest ?: JSONObject.NULL)) }
        val raw = JSONObject().put("schema", 1).put("id", release.id).put("tag", release.tag).put("name", release.name)
            .put("url", release.url).put("notes", release.notes).put("prerelease", release.prerelease)
            .put("published", release.publishedAt?.toString() ?: JSONObject.NULL).put("assets", assets).toString()
        decode(raw, requireAsset) // Reject invalid snapshots before scheduling, as well as during restoration.
        return raw
    }
    fun decode(raw: String, requireAsset: Boolean = true): UpdateCandidate {
        require(raw.length <= 4 * 1024 * 1024)
        val json = JSONObject(raw)
        require(json.getInt("schema") == 1)
        val id = json.getLong("id"); require(id > 0)
        val tag = json.getString("tag"); require(tag.isNotBlank() && tag.length <= 512)
        val url = json.getString("url"); requireGithubUrl(url, "tag")
        val entries = json.getJSONArray("assets"); require(entries.length() in 0..1000)
        val assets = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val assetId = entry.getLong("id"); require(assetId > 0)
            val name = entry.getString("name"); require(name.isNotBlank() && name.length <= 512)
            val address = entry.getString("url"); requireGithubUrl(address, "download")
            val size = if (entry.isNull("size")) null else entry.getLong("size").also { require(it > 0) }
            val digest = if (entry.isNull("digest")) null else entry.getString("digest")
            GitHubAsset(assetId, name, address, size, digest)
        }
        val release = GitHubRelease(id, tag, name = json.getString("name"), notes = json.getString("notes"), url = url,
            prerelease = json.getBoolean("prerelease"), assets = assets,
            publishedAt = if (json.isNull("published")) null else Instant.parse(json.getString("published")))
        if (requireAsset) require(ApkUpdateProfile.selection.select(release) != null)
        return UpdateCandidate(release)
    }
    private fun requireGithubUrl(value: String, kind: String) {
        require(value.length <= 8192 && value.none(Char::isISOControl))
        val uri = java.net.URI(value)
        require(uri.scheme == "https" && uri.host.equals("github.com", true) && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && uri.fragment == null && uri.query == null &&
            uri.path.startsWith("/${ApkUpdateProfile.repository.owner}/${ApkUpdateProfile.repository.name}/releases/$kind/"))
        // This only bounds restored metadata. Gold independently validates canonical URLs,
        // redirects, selected membership and every APK byte before creating any authority.
    }
}
