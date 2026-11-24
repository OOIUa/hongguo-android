package com.hongguo.android

import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CacheStorageAndUpdateNotesTest {
    @Test
    fun directorySizeAndDeleteChildrenOnlyClearContents() {
        val root = Files.createTempDirectory("hg-cache-test").toFile()
        try {
            val nested = File(root, "stream-cache").apply { mkdirs() }
            File(root, "a.bin").writeBytes(ByteArray(1200))
            File(nested, "b.mp4").writeBytes(ByteArray(3400))

            assertEquals(4600L, CacheStorage.directorySize(root))
            CacheStorage.deleteChildren(root)
            assertTrue("Cache root directory stays in place", root.isDirectory)
            assertEquals(0L, CacheStorage.directorySize(root))
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun formatBytesProducesReadableLabels() {
        assertEquals("0 B", CacheStorage.formatBytes(0))
        assertEquals("512 B", CacheStorage.formatBytes(512))
        assertEquals("2 KB", CacheStorage.formatBytes(2048))
        assertEquals("1.5 MB", CacheStorage.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("1.25 GB", CacheStorage.formatBytes((1.25 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun parseUpdateNotesSplitsMarkdownSectionsAndPlainFallback() {
        val grouped = parseUpdateNotes(
            """
            ### 新增
            - 支持存储设置缓存清理
            - 优化「我的」页面布局

            ### 修复
            * 修复线路切换问题
            """.trimIndent(),
        )
        assertEquals(2, grouped.size)
        assertEquals("新增", grouped[0].title)
        assertEquals(listOf("支持存储设置缓存清理", "优化「我的」页面布局"), grouped[0].items)
        assertEquals("修复", grouped[1].title)
        assertEquals(listOf("修复线路切换问题"), grouped[1].items)

        val plain = parseUpdateNotes("第一条说明\n第二条说明")
        assertEquals(1, plain.size)
        assertEquals(null, plain[0].title)
        assertEquals(listOf("第一条说明", "第二条说明"), plain[0].items)
    }

    @Test
    fun accountSettingsStatusPillAndSyncTimestampReflectAccountState() {
        assertEquals("未登录", accountSettingsStatusPill(AccountState(sessionStatus = AccountSessionStatus.SIGNED_OUT)))
        assertEquals("需清理", accountSettingsStatusPill(AccountState(sessionStatus = AccountSessionStatus.SIGNED_OUT, localClearFailed = true)))
        assertEquals("确认中", accountSettingsStatusPill(AccountState(sessionStatus = AccountSessionStatus.RESTORING)))
        assertEquals("待连接", accountSettingsStatusPill(AccountState(sessionStatus = AccountSessionStatus.WEBSITE_ONLY)))
        assertEquals("待验证", accountSettingsStatusPill(AccountState(sessionStatus = AccountSessionStatus.NEEDS_VERIFICATION)))
        assertEquals("已登录", accountSettingsStatusPill(AccountState(sessionStatus = AccountSessionStatus.LOGGED_IN, syncEnabled = false)))
        assertEquals("同步已开", accountSettingsStatusPill(AccountState(sessionStatus = AccountSessionStatus.LOGGED_IN, syncEnabled = true)))

        assertEquals("", formatAccountSyncTime(0L))
        assertTrue(formatAccountSyncTime(1_700_000_000_000L).matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

    @Test
    fun playbackSpeedsIncludeTwoPointFiveAndThreeXWithCleanLabels() {
        assertEquals(listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f), PlaybackSpeeds)
        assertEquals("2.5×", playbackSpeedLabel(2.5f))
        assertEquals("3×", playbackSpeedLabel(3f))
    }
}
