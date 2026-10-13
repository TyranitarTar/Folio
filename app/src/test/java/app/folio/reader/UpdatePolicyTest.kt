package app.folio.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdatePolicyTest {
    private fun metadata() = JSONObject().put("tag_name", "v0.7.1").put("body", "Release notes")
        .put("assets", JSONArray().put(JSONObject().put("name", "Folio-0.7.1.apk")
            .put("browser_download_url", "https://github.com/TyranitarTar/Folio/releases/download/v0.7.1/Folio-0.7.1.apk")
            .put("size", 521000000).put("digest", "sha256:" + "ab".repeat(32))))
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException) }
    @Test fun numericVersionsNeverDowngradeAndRejectPreviewOrMalformedLabels() {
        assertTrue(UpdatePolicy.newer("v0.10.0", "0.7.1")); assertTrue(UpdatePolicy.newer("0.7.1", "0.7.0"))
        assertFalse(UpdatePolicy.newer("0.7.1", "0.7.1")); assertFalse(UpdatePolicy.newer("0.6.0", "0.7.1"))
        listOf("1.0", "0.7.2-beta", "../v0.7.2", "999999999.0.0").forEach { rejected { UpdatePolicy.version(it) } }
    }
    @Test fun pinnedRepositoryStableReleaseAndAssetDigestAreRequired() {
        val good = UpdatePolicy.parse(metadata()); assertEquals("0.7.1", good.version)
        assertEquals(good, UpdatePolicy.restore(good.json()))
        val foreign = metadata(); foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/evil.apk")
        rejected { UpdatePolicy.parse(foreign) }
        val missing = metadata(); missing.getJSONArray("assets").getJSONObject(0).put("digest", "")
        rejected { UpdatePolicy.parse(missing) }
        val oversized = metadata(); oversized.getJSONArray("assets").getJSONObject(0).put("size", UpdatePolicy.maximumBytes + 1)
        rejected { UpdatePolicy.parse(oversized) }
        rejected { UpdatePolicy.parse(metadata().put("prerelease", true)) }
        rejected { UpdatePolicy.parse(metadata().put("draft", true)) }
        val duplicate = metadata(); duplicate.getJSONArray("assets").put(JSONObject(duplicate.getJSONArray("assets").getJSONObject(0).toString()))
        rejected { UpdatePolicy.parse(duplicate) }
    }
    @Test fun matchingPackageNewerCodeAndExactSigningIdentityAreAllMandatory() {
        fun check(pkg: String = "app.folio.reader", code: Long = 8, signers: Set<String> = setOf("trusted"), name: String = "0.7.1") =
            UpdatePolicy.validateIdentity("app.folio.reader", 7, pkg, code, setOf("trusted"), signers, "0.7.1", name)
        check()
        rejected { check(pkg = "other.application") }; rejected { check(code = 7) }; rejected { check(code = 6) }
        rejected { check(signers = setOf("attacker")) }; rejected { check(signers = emptySet()) }
        rejected { check(signers = setOf("trusted", "attacker")) }; rejected { check(name = "0.8.0") }
    }
}
