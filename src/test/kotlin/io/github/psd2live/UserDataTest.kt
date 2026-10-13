package io.github.psd2live

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserDataTest {
	@TempDir lateinit var temp: Path

	@Test fun clearsSavedDataWhileKeepingTheInstanceLocked() {
		val store = Files.createDirectories(temp.resolve("store"))
		val saved = Files.writeString(store.resolve("saved.json"), "saved")
		val cache = Files.createDirectories(temp.resolve("cache"))
		val cached = Files.writeString(cache.resolve("cached.bin"), "cached")
		var preferencesCleared = false
		assertTrue(UserData(store, listOf(cache)) {
			assertFalse(Files.exists(saved))
			assertFalse(Files.exists(cached))
			assertTrue(Files.exists(store.resolve(".instance.lock")))
			val second = AppInstanceLock.acquire(store)
			second?.close()
			assertNull(second, "Cleanup must hold the instance lock through preference cleanup")
			preferencesCleared = true
		}.clear())
		assertTrue(preferencesCleared)
		assertFalse(Files.exists(saved))
	}

	@Test fun runningEditorPreventsAllCleanup() {
		val store = Files.createDirectories(temp.resolve("store"))
		val saved = Files.writeString(store.resolve("saved.json"), "saved")
		val cache = Files.createDirectories(temp.resolve("cache"))
		var preferencesCleared = false
		AppInstanceLock.acquire(store)!!.use {
			assertFalse(UserData(store, listOf(cache)) { preferencesCleared = true }.clear())
		}
		assertTrue(Files.exists(saved))
		assertTrue(Files.exists(cache))
		assertFalse(preferencesCleared)
	}

	@Test fun missingStoreStillClearsCachesAndPreferences() {
		val cache = Files.createDirectories(temp.resolve("cache"))
		var preferencesCleared = false
		assertTrue(UserData(temp.resolve("store"), listOf(cache)) { preferencesCleared = true }.clear())
		assertFalse(Files.exists(cache))
		assertTrue(preferencesCleared)
	}
}
