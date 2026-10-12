package io.github.psd2live.core

import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import kotlin.test.Test
import kotlin.test.assertContentEquals

class VertexWeightMigrationTest {
    @Test fun barycentricToleranceCannotPutMigratedGroupWeightsOutsideTheirRange() {
        val group = VertexGroup("pin", DrawableId("mesh"), VertexGroupKind.PIN, floatArrayOf(1f, 0f, 0.5f))
        val sources = listOf(
            VertexSource.BarycentricOf(0, 1, 2, 1.00001f, -0.00001f, 0f),
            VertexSource.BarycentricOf(0, 1, 2, -0.00001f, 1.00001f, 0f),
            VertexSource.BarycentricOf(0, 1, 2, 0.25f, 0.25f, 0.5f),
        )
        val migrated = group.copy(weights = PrimitiveResidual.transferScalars(group.weights, sources))
        assertContentEquals(floatArrayOf(1f, 0f, 0.5f), migrated.weights)
    }
}
