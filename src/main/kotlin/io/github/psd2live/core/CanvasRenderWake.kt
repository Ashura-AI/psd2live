package io.github.psd2live.core

import java.util.concurrent.atomic.AtomicBoolean

/** Coalesces GPU tasks without losing a request made while a task is running or finishing. */
internal class CanvasRenderWake {
	private val scheduled = AtomicBoolean(false)
	private val pending = AtomicBoolean(false)

	/** True when the caller must post a task. A running task retains the wake-up. */
	fun request(): Boolean {
		pending.set(true)
		return scheduled.compareAndSet(false, true)
	}

	fun begin() { pending.set(false) }

	/** True when the finishing task must post its successor. */
	fun finish(): Boolean {
		scheduled.set(false)
		return pending.get() && scheduled.compareAndSet(false, true)
	}
}
