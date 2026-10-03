package dev.stagecraft.service

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BranchContextCacheTest {

    private fun tempDir(): File = Files.createTempDirectory("stagecraft-branchctx").toFile()

    @Test
    fun `a branch context round trips through disk`() {
        val dir = tempDir()
        try {
            val saved = BranchContext("git@github.com:corp/svc.git", "feature/ORD-214", 214)
            BranchContextCache.save(dir, saved)

            assertEquals(saved, BranchContextCache.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a context that names nothing is not worth painting from`() {
        val dir = tempDir()
        try {
            BranchContextCache.save(dir, BranchContext(null, null, null))

            assertNull(BranchContextCache.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a corrupted context file is a miss, not a crash`() {
        val dir = tempDir()
        try {
            val file = BranchContextCache.cacheFile(dir)
            file.parentFile.mkdirs()
            file.writeText("not json at all")

            assertNull(BranchContextCache.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
