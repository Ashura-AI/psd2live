package io.github.psd2live.render

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Atlas page images as premultiplied textures, sampled as the settings ask ([TextureFiltering]); the editing
 * canvas's renderer and the p2lrt preview's upload through here. Runs with the context current.
 */
internal object PageTextures {
	/**
	 * [image] as a premultiplied texture, with mipmaps unless [mipmaps] is off. Java's ARGB ints go up as they are,
	 * as BGRA in 8-8-8-8 reversed: the order of an ARGB int's bytes in memory, and the one Windows and NVIDIA drivers
	 * keep textures in. A premultiplied image then needs no pass over its pixels, others only the premultiply.
	 */
	fun upload(image: BufferedImage, mipmaps: Boolean, filtering: TextureFiltering): Int {
		val width = image.width
		val height = image.height
		val raster = (image.raster.dataBuffer as? DataBufferInt)?.data?.takeIf { it.size == width * height }
		val premultipliedInput = raster != null && image.type == BufferedImage.TYPE_INT_ARGB_PRE
		val source = if (raster != null && (premultipliedInput || image.type == BufferedImage.TYPE_INT_ARGB)) raster
			else image.getRGB(0, 0, width, height, null, 0, width)
		val pixels = if (premultipliedInput) null else MemoryUtil.memAllocInt(width * height)
		try {
			if (pixels != null) {
				for (i in 0 until width * height) {
					val c = source[i]
					val a = c ushr 24
					// Rounded like Skia's own premultiply, so the GPU and software painters sample the same texels.
					pixels.put(when (a) {
						255 -> c
						0 -> 0
						else -> (a shl 24) or ((((c ushr 16 and 0xff) * a + 127) / 255) shl 16) or
							((((c ushr 8 and 0xff) * a + 127) / 255) shl 8) or (((c and 0xff) * a + 127) / 255)
					})
				}
				pixels.flip()
			}
			val texture = GL11.glGenTextures()
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
			clearGlErrors()
			if (pixels != null) GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels)
			else GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, source)
			if (mipmaps) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
			checkTextureUpload(texture, width, height)
			applyFiltering(filtering, mipmaps)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
			return texture
		} finally {
			pixels?.let(MemoryUtil::memFree)
		}
	}

	/** Sets the bound texture's minification for [filtering]; [mipmapped] is whether it has mipmaps to filter between. */
	fun applyFiltering(filtering: TextureFiltering, mipmapped: Boolean) {
		val trilinear = mipmapped && filtering.mipmaps
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, if (trilinear) GL11.GL_LINEAR_MIPMAP_LINEAR else GL11.GL_LINEAR)
		val most = maxAnisotropy()
		if (most > 1f) GL11.glTexParameterf(GL11.GL_TEXTURE_2D, TEXTURE_MAX_ANISOTROPY,
			if (trilinear) filtering.anisotropy.toFloat().coerceAtMost(most) else 1f)
	}

	/** The capabilities [anisotropyLimit] was read for: one context in the app, one per tool. */
	private var anisotropyCapabilities: GLCapabilities? = null
	private var anisotropyLimit = 1f

	/** The most anisotropy the current context filters with; 1 without the extension (core in GL 4.6). */
	private fun maxAnisotropy(): Float {
		val caps = GL.getCapabilities()
		if (caps !== anisotropyCapabilities) {
			anisotropyCapabilities = caps
			anisotropyLimit = if (caps.OpenGL46 || caps.GL_ARB_texture_filter_anisotropic || caps.GL_EXT_texture_filter_anisotropic)
				GL11.glGetFloat(MAX_TEXTURE_MAX_ANISOTROPY).coerceAtLeast(1f) else 1f
		}
		return anisotropyLimit
	}

	/** GL 4.6's anisotropic filtering, the same values as the ARB and EXT extensions'. */
	private const val TEXTURE_MAX_ANISOTROPY = 0x84FE
	private const val MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF
}

/**
 * Keeps a renderer's textures on the filtering the settings ask for: [follow] at the start of each draw re-filters
 * them when the setting changed, and [current] is what new textures upload with.
 */
internal class FilteringFollower(private val mipmaps: Boolean) {
	var current: TextureFiltering = wanted()
		private set

	private fun wanted() = if (mipmaps) GraphicsSettings.current.textureFiltering else TextureFiltering.BILINEAR

	fun follow(textures: () -> Sequence<Int>) {
		val wanted = wanted()
		if (wanted == current) return
		current = wanted
		for (texture in textures()) {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
			PageTextures.applyFiltering(wanted, mipmaps)
		}
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
	}
}
