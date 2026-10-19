package io.github.psd2live.tools

import io.github.psd2live.core.CubismRuntimeAsset
import io.github.psd2live.core.CubismRuntimeBundle
import io.github.psd2live.core.CubismSdkPreviewSession
import io.github.psd2live.core.CubismNativeApi
import io.github.psd2live.core.CubismNativeBinding
import io.github.psd2live.core.PreviewBackend
import io.github.psd2live.core.PreviewSessions
import io.github.psd2live.render.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import java.util.concurrent.atomic.AtomicReference
import java.lang.reflect.Proxy
import com.sun.jna.Pointer
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.*

/** Hidden GL profile checks and renderer replacement on small test windows; never moves the system pointer.
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*GpuCompatibilityTool'
 */
class GpuCompatibilityTool {
    @Test fun profilesCachesAndBackendReplacement() {
        requireTools()
        SkiaGpu.requestOpenGl()
        val report = StringBuilder()
        assertTrue(glfwInit())
        try {
            checkProfile(GLFW_OPENGL_CORE_PROFILE, report)
            checkProfile(GLFW_OPENGL_COMPAT_PROFILE, report)
        } finally { glfwTerminate() }
        checkBackendReplacement(report)
        output("gpu-compatibility").resolve("report.txt").writeText(report.toString())
        println(report)
    }

    private class ContextHolder(@JvmField val context: DirectContext)

    private fun checkProfile(profile: Int, report: StringBuilder) {
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, profile)
        val window = glfwCreateWindow(32, 32, "GPU compatibility check", 0, 0)
        assertNotEquals(0L, window)
        try {
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            val core = GL11.glGetInteger(0x9126) and 1 != 0
            clearGlErrors()
            cleanDefaultVertexArray(core)
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "VAO cleanup in profile $profile")
            assertEquals(0, GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING))
            report.appendLine("${if (core) "core" else "compatibility"}: ${GL11.glGetString(GL11.GL_VERSION)}; VAO cleanup passed")
            DirectContext.makeGL().use { context ->
                val layer = SkiaLayer()
                try {
                    val gpu = WindowGpu(layer, Any(), 0L, 0L, ContextHolder(context),
                        ContextHolder::class.java.getDeclaredField("context").apply { isAccessible = true }, SkiaGpu.Platform.WGL)
                    WindowGpu::class.java.getDeclaredField("coreProfile").apply { isAccessible = true }.setBoolean(gpu, core)
                    if (core) checkCubismRejectedBeforeLoading(gpu)
                    else checkCubismDoesNotReuseReplacedContext(gpu)
                    checkLayerCache(gpu, context, report)
                } finally { SwingUtilities.invokeAndWait { layer.dispose() } }
            }
        } finally {
            glfwDestroyWindow(window)
            GL.setCapabilities(null)
        }
    }

    private fun checkCubismRejectedBeforeLoading(gpu: WindowGpu) {
        var nativeLoads = 0
        val statuses = ArrayList<String?>()
        val executor = object : GpuExecutor {
            override val providesContext = true
            override fun execute(task: (GpuResources?) -> Unit): Boolean { task(gpu.resources); return true }
        }
        CubismSdkPreviewSession({}, { statuses += it }, null, { nativeLoads++; error("Native loading must not run") }, executor).use { session ->
            session.load(CubismRuntimeBundle("model.model3.json", listOf(CubismRuntimeAsset("model.model3.json", "{}".encodeToByteArray()))), emptyList())
            SwingUtilities.invokeAndWait { }
            assertEquals(0, nativeLoads)
            assertNull(session.renderPath)
            assertContains(assertNotNull(statuses.last()), "compatibility OpenGL context")
        }
    }

    private fun checkLayerCache(gpu: WindowGpu, context: DirectContext, report: StringBuilder) {
        val supported = MultisampledWindow::class.java.getDeclaredField("supported").apply { isAccessible = true }
        val original = supported.getBoolean(null)
        try {
            for (msaa in listOf(true, false)) {
                supported.setBoolean(null, msaa)
                val compositor = WindowCompositor(gpu, object : SkikoRenderDelegate {
                    override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {}
                })
                var paints = 0
                var stale = false
                var color = 0xffff0000.toInt()
                val view = CompositedView(0f, 0f, 32f, 32f, layer = "edit", stale = { stale }) { canvas, _ ->
                    paints++
                    canvas.clear(color)
                    stale = false
                }
                val members = listOf(java.util.AbstractMap.SimpleImmutableEntry("art", view))
                val method = WindowCompositor::class.java.getDeclaredMethod("layerImage", String::class.java, List::class.java, DirectContext::class.java)
                    .apply { isAccessible = true }
                fun frame(): Image = assertNotNull(method.invoke(compositor, "edit", members, context) as? Image)
                try {
                    repeat(100) { frame() }
                    assertEquals(1, paints, "Unchanged layer must stay cached without MSAA too")
                    assertPixel(context, frame(), color)
                    color = 0xff0000ff.toInt()
                    stale = true
                    assertPixel(context, frame(), color)
                    assertEquals(2, paints)
                    report.appendLine("MSAA=$msaa: 100 unchanged frames, 1 paint; invalidated frame and pixels passed")
                } finally {
                    val layers = WindowCompositor::class.java.getDeclaredField("layers").apply { isAccessible = true }.get(compositor) as Map<*, *>
                    layers.values.forEach { layer -> layer!!::class.java.getDeclaredMethod("close").apply { isAccessible = true }.invoke(layer) }
                    val recorder = WindowCompositor::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(compositor) as AutoCloseable
                    recorder.close()
                }
            }
        } finally { supported.setBoolean(null, original) }
    }

    private fun checkCubismDoesNotReuseReplacedContext(gpu: WindowGpu) {
        var calls = 0
        val native = Proxy.newProxyInstance(CubismNativeApi::class.java.classLoader, arrayOf(CubismNativeApi::class.java)) { _, method, _ ->
            calls++
            when (method.name) {
                "Live2D_Init" -> 1
                "Live2D_CreateModelFromMemory" -> Pointer(1)
                else -> null
            }
        } as CubismNativeApi
        var resources = gpu.resources
        val executor = object : GpuExecutor {
            override val providesContext = true
            override fun execute(task: (GpuResources?) -> Unit): Boolean { task(resources); return true }
        }
        PreviewSessions({}, {}, cubismFactory = { frames, status ->
            CubismSdkPreviewSession(frames, status, null,
                { CubismNativeBinding(native, memoryModels = true, textureReplace = false, drawCurrent = true, callerContextModels = true) }, executor)
        }, p2lrtAvailable = { true }).use { preview ->
            val bundle = CubismRuntimeBundle("model.model3.json", listOf(CubismRuntimeAsset("model.model3.json", "{}".encodeToByteArray())))
            preview.cubism.load(bundle, emptyList())
            SwingUtilities.invokeAndWait { }
            assertEquals(PreviewBackend.CUBISM, preview.active)
            assertEquals("texture", preview.cubism.renderPath)
            val before = calls
            resources = GpuResources(gpu)
            preview.cubism.load(bundle, emptyList())
            SwingUtilities.invokeAndWait { }
            assertEquals(PreviewBackend.P2LRT, preview.active)
            assertContains(assertNotNull(preview.cubismFailure), "context was replaced")
            assertEquals(before, calls, "No old native handle may be touched on a replacement context")
        }
    }

    private fun assertPixel(context: DirectContext, image: Image, expected: Int) = Bitmap().use { bitmap ->
        assertTrue(bitmap.allocN32Pixels(image.width, image.height))
        assertTrue(image.readPixels(context, bitmap))
        assertEquals(expected, bitmap.getColor(8, 8))
    }

    private fun checkBackendReplacement(report: StringBuilder) {
        val main = AtomicReference<JFrame>()
        val floating = AtomicReference<JFrame>()
        val mainLayer = AtomicReference<SkiaLayer>()
        val floatingLayer = AtomicReference<SkiaLayer>()
        fun makeWindow(frame: AtomicReference<JFrame>, layer: AtomicReference<SkiaLayer>) {
            SwingUtilities.invokeAndWait {
                val made = SkiaLayer().apply {
                    renderDelegate = object : SkikoRenderDelegate {
                        override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) { canvas.clear(0xff333333.toInt()) }
                    }
                }
                layer.set(made)
                frame.set(JFrame("GPU backend check").apply {
                    isUndecorated = true
                    isAutoRequestFocus = false
                    contentPane.add(made)
                    setSize(96, 96)
                    isVisible = true
                })
            }
        }
        try {
            makeWindow(main, mainLayer)
            SkiaGpu.capturePrimary(main.get())
            await("main OpenGL") { SkiaGpu.primary != null }
            val first = assertNotNull(SkiaGpu.primary)
            replaceRenderer(mainLayer.get(), GraphicsApi.SOFTWARE_FAST)
            await("main software fallback") { SkiaGpu.primary == null && SkiaGpu.status.value is SkiaGpu.Status.Unavailable }
            assertNull(SkiaGpu.of(main.get()))
            assertFalse(first.valid())
            replaceRenderer(mainLayer.get(), GraphicsApi.OPENGL)
            await("main OpenGL recovery") { SkiaGpu.primary != null }
            assertNotSame(first, SkiaGpu.primary)
            val recovered = SkiaGpu.primary
            makeWindow(floating, floatingLayer)
            await("floating OpenGL") { SkiaGpu.of(floating.get()) != null }
            val changes = SkiaGpu.contextChanges.value
            replaceRenderer(floatingLayer.get(), GraphicsApi.SOFTWARE_FAST)
            await("floating software fallback") { SkiaGpu.contextChanges.value > changes && SkiaGpu.of(floating.get()) == null }
            assertSame(recovered, SkiaGpu.primary)
            report.appendLine("main OpenGL -> software -> OpenGL and floating-window software fallback passed")
        } finally {
            SwingUtilities.invokeAndWait { floating.get()?.dispose(); main.get()?.dispose() }
        }
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!ready()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $label" }
            Thread.sleep(20)
        }
    }

    private fun replaceRenderer(layer: SkiaLayer, api: GraphicsApi) = SwingUtilities.invokeAndWait {
        // Setting renderApi only changes the reported API. Exercise the manager's real fallback/recreation path.
        val manager = SkiaLayer::class.java.getDeclaredField("redrawerManager").apply { isAccessible = true }.get(layer)
        layer.renderApi = api
        manager.javaClass.getDeclaredMethod("findNextWorkingRenderApi", Boolean::class.javaPrimitiveType).invoke(manager, true)
        layer.needRender()
    }
}
