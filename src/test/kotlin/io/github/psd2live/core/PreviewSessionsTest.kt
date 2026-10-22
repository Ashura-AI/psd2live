package io.github.psd2live.core

import com.sun.jna.Pointer
import io.github.psd2live.render.GpuExecutor
import io.github.psd2live.render.GpuResources
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.test.*

class PreviewSessionsTest {
    @Test fun modelFailureAfterNativeInitializationFallsBackToP2lrt() {
        session(failCreatingModel = true).use { preview ->
            preview.cubism.load(bundle, emptyList())
            flushStatus()
            assertEquals("offscreen", preview.cubism.renderPath)
            assertEquals(PreviewBackend.P2LRT, preview.active)
            assertContains(assertNotNull(preview.cubismFailure), "model unsupported")
        }
    }

    @Test fun drawingFailureAfterReadyFallsBackToP2lrt() {
        session().use { preview ->
            preview.cubism.load(bundle, emptyList())
            flushStatus()
            assertEquals(PreviewBackend.CUBISM, preview.active)
            preview.render(request)
            flushStatus()
            assertEquals(PreviewBackend.P2LRT, preview.active)
            assertContains(assertNotNull(preview.cubismFailure), "drawing unsupported")
        }
    }

    private fun session(failCreatingModel: Boolean = false, p2lrtAvailable: Boolean = true,
                        statuses: MutableList<String?> = ArrayList()): PreviewSessions {
        val native = Proxy.newProxyInstance(CubismNativeApi::class.java.classLoader, arrayOf(CubismNativeApi::class.java)) { _, method, _ ->
            when (method.name) {
                "Live2D_InitOffscreen" -> 1
                "Live2D_CreateModelFromMemory" -> {
                    if (failCreatingModel) error("model unsupported")
                    Pointer(1)
                }
                "Live2D_Draw" -> error("drawing unsupported")
                "Live2D_GetParameterCount", "Live2D_CopyParameterValues" -> 0
                else -> null
            }
        } as CubismNativeApi
        val executor = object : GpuExecutor {
            override val providesContext = false
            override fun execute(task: (GpuResources?) -> Unit): Boolean { task(null); return true }
        }
        return PreviewSessions({}, { statuses += it }, cubismFactory = { frames, status ->
            CubismSdkPreviewSession(frames, status, null,
                { CubismNativeBinding(native, memoryModels = true, textureReplace = false) }, executor)
        }, p2lrtAvailable = { p2lrtAvailable })
    }

    private fun flushStatus() = SwingUtilities.invokeAndWait { }

    private val bundle = CubismRuntimeBundle("model.model3.json", listOf(CubismRuntimeAsset("model.model3.json", "{}".encodeToByteArray())))
    private val request = PreviewRenderRequest(32, 32, 1f, 0f, 0f, 0f, 0f, 0f,
        animationEnabled = false, parameterOverrides = emptyMap())
}
