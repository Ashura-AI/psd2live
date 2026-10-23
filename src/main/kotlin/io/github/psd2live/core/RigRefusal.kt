package io.github.psd2live.core

/**
 * A refused edit whose kind a caller can tell without reading the message: [code] names it and [target] is the
 * `kind:id` it is about, when there is one. Adapters report [code] as the failure category.
 */
class RigRefusal(val code: Code, message: String, val target: String? = null) : IllegalArgumentException(message) {
	enum class Code(val json: String) {
		/** The edit names an object, key or entry the model does not have. */
		NOT_FOUND("not_found"),
		/** A new or converted ID is already taken in its kind. */
		ID_CONFLICT("id_conflict"),
		/** The object belongs to a swing, simulation, interior effect or the skeleton, which make or read it under its ID. */
		OWNED_BY_GENERATOR("owned_by_generator"),
		/** A keyform edit names a key without every axis the target is directly bound to. */
		KEY_INCOMPLETE("key_incomplete"),
		/** A mesh topology action does not apply to this mesh and selection. */
		TOPOLOGY_REFUSED("topology_refused"),
	}

	companion object {
		/** Throws a [Code.NOT_FOUND] refusal for [target] unless [present]. */
		inline fun found(present: Boolean, target: String, message: () -> String) {
			if (!present) throw RigRefusal(Code.NOT_FOUND, message(), target)
		}

		/** Throws a [Code.ID_CONFLICT] refusal for [target] unless [free]. */
		inline fun free(free: Boolean, target: String, message: () -> String) {
			if (!free) throw RigRefusal(Code.ID_CONFLICT, message(), target)
		}
	}
}
