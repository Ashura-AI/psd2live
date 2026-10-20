package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.render.*
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import java.nio.file.Files
import kotlin.test.*

/** Synthetic image insertion and GPU pixel check; PSD2LIVE_TOOLS=1 ./gradlew test --tests '*CanvasImageImportTool'. */
class CanvasImageImportTool {
    @Test fun importedImagePixels() = runBlocking {
        requireTools()
        val directory = Files.createTempDirectory(output("canvas-image-import").toPath(), "input-")
        val document = Characters.head(false)
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ error("Uses rebuildFrom") },
            rebuildFrom = { next, previous -> builder.build(next, previous) })
        var capture = runtime.install(runtime.state.value.state, "test", document, builder.build(document))
        val before = capture.model
        val path = Characters.disc(directory, "badge.png", 12).toAbsolutePath()
        val imported = WorkspaceImageLayerCommands(runtime).importImages(capture.projectId, capture.state, listOf(path),
            ImageLayerInsertion(centre = 8f to 32f), "Import", MutationAuthor.USER)
        capture = imported.commit.capture
        val layer = imported.mutation.affectedLayerIds.single()
        val model = capture.model
        val drawable = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == layer }
        val geometry = RigCanvasSupport.evaluateExact(model)
        val draws = ArtworkDrawList.build(model, geometry, ArtworkOptions())
        assertTrue(draws.any { it.drawableId == drawable.id })
        val reference = WorkspaceImageLayerCommands(runtime).importImages(capture.projectId, capture.state, listOf(path),
            ImageLayerInsertion(centre = 56f to 32f, reference = true), "Reference", MutationAuthor.USER).commit.capture.model
        assertTrue(glfwInit())
        try {
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            val window = glfwCreateWindow(64, 64, "Image import check", 0, 0)
            assertNotEquals(0L, window)
            try {
                glfwMakeContextCurrent(window)
                GL.createCapabilities()
                GlCanvasRenderer(mipmaps = false).use { renderer ->
                    val viewport = CanvasViewport(1.0, 0.0, 0.0, 64f, 64f)
                    for ((name, current) in listOf("before" to before, "after" to model, "reference" to reference)) {
                        val pose = RigCanvasSupport.evaluateExact(current)
                        renderer.render("edit", CanvasScene(64, 64, viewport, current, pose, ArtworkDrawList.build(current, pose, ArtworkOptions()))).use { pixels ->
                            Image.makeFromBitmap(pixels).use { image -> output("canvas-image-import").resolve("$name.png").writeBytes(image.encodeToData()!!.bytes) }
                            if (name != "before") assertTrue(pixels.getColor(8, 32) ushr 24 > 200, "Imported image must have opaque pixels")
                            if (name == "reference") assertTrue(pixels.getColor(56, 32) ushr 24 > 200, "Reference rectangle must have opaque pixels")
                        }
                    }
                    assertEquals(GL11.GL_NO_ERROR, GL11.glGetError())
                }
            } finally { glfwDestroyWindow(window); GL.setCapabilities(null) }
        } finally { glfwTerminate() }
    }
}
