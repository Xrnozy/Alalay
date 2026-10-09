package com.alalay.app

import com.alalay.app.models.ModelStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class ModelStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(
        assets: Map<String, ByteArray>,
        sha: Map<String, String> = emptyMap(),
    ) = ModelStore(
        filesDir = tmp.newFolder("files"),
        listAssets = { assets.keys.toList() },
        assetSize = { assets[it]?.size?.toLong() ?: -1L },
        openAsset = { ByteArrayInputStream(assets.getValue(it)) },
        expectedSha256 = sha,
    )

    @Test fun `stageAll extracts every bundled asset`() = runTest {
        val s = store(mapOf(
            "minicpm5-1b-q4_k_m.gguf" to ByteArray(64) { 1 },
            "whisper-small-q5_0.bin" to ByteArray(32) { 2 },
            "README.md" to "docs".toByteArray(),
        ))
        val r = s.stageAll()
        assertEquals(2, r.size) // README is skipped by bundledAssetNames()
        assertTrue(r.all { it.staged && it.verified })
        assertEquals(64, s.plannerModel()!!.length())
        assertEquals(32, s.sttModel()!!.length())
    }

    @Test fun `second launch skips already-staged files`() = runTest {
        val s = store(mapOf("m.gguf" to ByteArray(16) { 3 }))
        s.stageAll()
        val r2 = s.stageAll()
        assertTrue(r2.single().let { !it.staged && it.verified })
    }

    @Test fun `stale file (size mismatch) is re-extracted`() = runTest {
        val s = store(mapOf("m.gguf" to ByteArray(16) { 3 }))
        s.stageAll()
        s.plannerModel()!!.writeBytes(ByteArray(4))
        val r2 = s.stageAll()
        assertTrue(r2.single().staged)
        assertEquals(16, s.plannerModel()!!.length())
    }

    @Test fun `sha pin is enforced after extraction`() = runTest {
        val good = mapOf("m.gguf" to ByteArray(8) { 9 })
        val s = store(good, sha = mapOf("m.gguf" to "deadbeef"))
        val r = s.stageAll()
        assertEquals(false, r.single().verified) // bad pin → flagged, not trusted
    }

    @Test fun `no models staged yields null lookups`() = runTest {
        val s = store(emptyMap())
        s.stageAll()
        assertNull(s.plannerModel())
        assertNull(s.sttModel())
    }
}
