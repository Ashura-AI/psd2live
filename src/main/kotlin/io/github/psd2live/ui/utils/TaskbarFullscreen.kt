package io.github.psd2live.ui.utils

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

/**
 * Tells the Windows shell that a window is full screen, so the taskbar goes behind it while it is active
 * (ITaskbarList2::MarkFullscreenWindow). The shell guesses this only for a window exactly the monitor's size; a
 * window with its native frame covers the monitor with its invisible resize edges past it, which it does not count.
 */
internal object TaskbarFullscreen {

	private val isWindows = System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

	@Structure.FieldOrder("data1", "data2", "data3", "data4")
	class Guid(data1: Int = 0, data2: Short = 0, data3: Short = 0, data4: ByteArray = ByteArray(8)) : Structure() {
		@JvmField var data1 = data1
		@JvmField var data2 = data2
		@JvmField var data3 = data3
		@JvmField var data4 = data4
	}

	@Suppress("FunctionName")
	private interface Ole32 : Library {
		fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
		fun CoCreateInstance(clsid: Guid, outer: Pointer?, context: Int, iid: Guid, result: PointerByReference): Int
	}

	private fun guid(d1: Int, d2: Int, d3: Int, vararg d4: Int) =
		Guid(d1, d2.toShort(), d3.toShort(), ByteArray(8) { d4[it].toByte() })

	// CLSID_TaskbarList and IID_ITaskbarList2.
	private val taskbarClass = guid(0x56FDF344, 0xFD6D, 0x11D0, 0x95, 0x8A, 0x00, 0x60, 0x97, 0xC9, 0xA0, 0x90)
	private val taskbarList2 = guid(0x602D4995, 0xB13A, 0x429B, 0xA6, 0x6E, 0x19, 0x35, 0xE4, 0x4F, 0x43, 0x17)

	// The vtable: IUnknown's three, then ITaskbarList's HrInit, AddTab, DeleteTab, ActivateTab, SetActiveAlt.
	private const val HR_INIT = 3
	private const val MARK_FULLSCREEN_WINDOW = 8

	/** The shell's taskbar list, made on the event thread the first time it is needed; null when unavailable. */
	private val taskbar: Pointer? by lazy {
		if (!isWindows) return@lazy null
		runCatching {
			val ole32 = Native.load("ole32", Ole32::class.java)
			ole32.CoInitializeEx(null, 2) // COINIT_APARTMENTTHREADED; already initialized is fine
			val result = PointerByReference()
			if (ole32.CoCreateInstance(taskbarClass, null, 1 /* CLSCTX_INPROC_SERVER */, taskbarList2, result) != 0) return@runCatching null
			result.value.also { if (call(it, HR_INIT) != 0) return@runCatching null }
		}.getOrNull()
	}

	private fun call(target: Pointer, index: Int, vararg arguments: Any): Int {
		val method = target.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)
		return Function.getFunction(method).invokeInt(arrayOf(target, *arguments))
	}

	/** Marks [window] full screen or not; does nothing off Windows or where the shell refuses. Event thread only. */
	fun mark(window: Window, fullscreen: Boolean) {
		val list = taskbar ?: return
		runCatching { call(list, MARK_FULLSCREEN_WINDOW, Native.getWindowPointer(window), if (fullscreen) 1 else 0) }
	}
}
