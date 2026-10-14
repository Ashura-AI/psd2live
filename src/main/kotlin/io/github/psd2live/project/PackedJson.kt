package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.nio.ByteBuffer

/**
 * A JSON value whose long number arrays are stored as float32 bits, losslessly.
 *
 * An array of at least [MIN_LENGTH] numbers, each written exactly as its float's own text (what a `Float` put into
 * JSON writes), is replaced in the JSON text by `{"$REF": i}` and its numbers go after the text as bits. Any other
 * number keeps its text, so [decode] gives back the value [encode] was given, element for element. A value that
 * already holds a `{"$REF": ...}` object is stored as plain text.
 *
 * Layout: magic `P2LJ`, version, a flag (1: the text holds no references), the text's length and UTF-8 bytes, the
 * array count, then each array's length and its floats as four byte planes (every float's first byte, then every
 * second byte, ...), which DEFLATE compresses far better than interleaved floats. Integers are big-endian.
 */
internal object PackedJson {
	const val VERSION = 1
	const val MIN_LENGTH = 8
	const val REF = "\$f32"
	private val MAGIC = "P2LJ".encodeToByteArray()

	fun isPacked(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

	fun encode(value: JsonElement): ByteArray {
		val arrays = ArrayList<FloatArray>()
		val plain = holdsReference(value)
		val text = (if (plain) value else pack(value, arrays)).toString().encodeToByteArray()
		val size = MAGIC.size + 2 + 4 + text.size + 4 + arrays.sumOf { 4 + it.size * 4 }
		val buffer = ByteBuffer.allocate(size)
		buffer.put(MAGIC).put(VERSION.toByte()).put(if (plain) 1 else 0)
		buffer.putInt(text.size).put(text).putInt(arrays.size)
		for (array in arrays) {
			buffer.putInt(array.size)
			for (shift in intArrayOf(24, 16, 8, 0)) for (v in array) buffer.put((v.toRawBits() ushr shift).toByte())
		}
		return buffer.array()
	}

	fun decode(bytes: ByteArray): JsonElement {
		require(isPacked(bytes)) { "Not a packed JSON value" }
		val buffer = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size)
		require(buffer.get().toInt() == VERSION) { "Unsupported packed JSON version" }
		val plain = when (buffer.get().toInt()) { 0 -> false; 1 -> true; else -> throw IllegalArgumentException("Invalid packed JSON flag") }
		val textLength = buffer.int
		require(textLength in 0..buffer.remaining()) { "Truncated packed JSON" }
		val text = ByteArray(textLength).also(buffer::get).decodeToString()
		val count = buffer.int
		require(count >= 0 && (!plain || count == 0)) { "Invalid packed JSON array count" }
		val arrays = List(count) {
			val length = buffer.int
			require(length >= 0 && length.toLong() * 4 <= buffer.remaining()) { "Truncated packed JSON" }
			val bits = IntArray(length)
			for (shift in intArrayOf(24, 16, 8, 0)) for (i in 0 until length) bits[i] = bits[i] or ((buffer.get().toInt() and 255) shl shift)
			JsonArray(bits.map { JsonPrimitive(Float.fromBits(it)) })
		}
		require(!buffer.hasRemaining()) { "Trailing bytes after packed JSON" }
		val skeleton = Json.parseToJsonElement(text)
		return if (plain) skeleton else unpack(skeleton, arrays)
	}

	private fun pack(value: JsonElement, arrays: MutableList<FloatArray>): JsonElement = when (value) {
		is JsonObject -> JsonObject(value.mapValues { pack(it.value, arrays) })
		is JsonArray -> floats(value)?.let { floats ->
			arrays.add(floats)
			buildJsonObject { put(REF, arrays.size - 1) }
		} ?: JsonArray(value.map { pack(it, arrays) })
		else -> value
	}

	/** [array]'s numbers when it is long enough and each is written as its float's own text, else null. */
	private fun floats(array: JsonArray): FloatArray? {
		if (array.size < MIN_LENGTH) return null
		val out = FloatArray(array.size)
		for ((i, element) in array.withIndex()) {
			val primitive = element as? JsonPrimitive ?: return null
			if (primitive.isString || primitive is JsonNull) return null
			val value = primitive.content.toFloatOrNull() ?: return null
			if (value.toString() != primitive.content) return null
			out[i] = value
		}
		return out
	}

	private fun unpack(value: JsonElement, arrays: List<JsonArray>): JsonElement = when (value) {
		is JsonObject -> reference(value)?.let { index ->
			require(index in arrays.indices) { "Invalid packed JSON reference" }
			arrays[index]
		} ?: JsonObject(value.mapValues { unpack(it.value, arrays) })
		is JsonArray -> JsonArray(value.map { unpack(it, arrays) })
		else -> value
	}

	private fun reference(value: JsonObject): Int? =
		if (value.size == 1 && REF in value) requireNotNull(value.getValue(REF).jsonPrimitive.intOrNull) { "Invalid packed JSON reference" } else null

	private fun holdsReference(value: JsonElement): Boolean = when (value) {
		is JsonObject -> (value.size == 1 && REF in value) || value.values.any(::holdsReference)
		is JsonArray -> value.any(::holdsReference)
		else -> false
	}
}
