package com.sowz.aestudio

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.sowz.aestudio.core.EditLayer

/**
 * SOWZ native layer transform.
 *
 * Position, scale, rotation and opacity are evaluated from the same composition model
 * at each frame timestamp. The effect is used for both CompositionPlayer and Transformer.
 */
@UnstableApi
internal class SowzLayerTransformEffect(
    private val layer: EditLayer
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        layer.opacity.keyframes.isEmpty() &&
            layer.positionX.keyframes.isEmpty() &&
            layer.positionY.keyframes.isEmpty() &&
            layer.scaleX.keyframes.isEmpty() &&
            layer.scaleY.keyframes.isEmpty() &&
            layer.rotationDegrees.keyframes.isEmpty() &&
            layer.opacity.initialValue >= 0.9999f &&
            layer.positionX.initialValue == 0f &&
            layer.positionY.initialValue == 0f &&
            layer.scaleX.initialValue == 1f &&
            layer.scaleY.initialValue == 1f &&
            layer.rotationDegrees.initialValue == 0f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LayerShaderProgram(useHdr, layer)

    @UnstableApi
    private class LayerShaderProgram(
        useHdr: Boolean,
        private val layer: EditLayer
    ) : BaseGlShaderProgram(useHdr, 1) {
        private var program: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).also { shader ->
                shader.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
            }
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val shader = program ?: throw VideoFrameProcessingException(
                "SOWZ layer transform shader was not configured.",
                presentationTimeUs
            )
            val compositionTimeMs = presentationTimeUs.coerceAtLeast(0L) / 1_000L
            val localTimeMs = (compositionTimeMs - layer.timelineStartMs)
                .coerceIn(0L, layer.durationMs)
            val opacity = layer.opacity.valueAt(localTimeMs).coerceIn(0f, 1f)
            val positionX = layer.positionX.valueAt(localTimeMs).coerceIn(-1f, 1f)
            val positionY = layer.positionY.valueAt(localTimeMs).coerceIn(-1f, 1f)
            val scaleX = layer.scaleX.valueAt(localTimeMs).coerceIn(0.05f, 8f)
            val scaleY = layer.scaleY.valueAt(localTimeMs).coerceIn(0.05f, 8f)
            val rotationRadians = layer.rotationDegrees.valueAt(localTimeMs)
                .coerceIn(-3600f, 3600f) * (Math.PI.toFloat() / 180f)

            try {
                shader.use()
                shader.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                shader.setFloatUniform("uOpacity", opacity)
                shader.setFloatsUniform("uPosition", floatArrayOf(positionX, positionY))
                shader.setFloatsUniform("uScale", floatArrayOf(scaleX, scaleY))
                shader.setFloatUniform("uRotation", rotationRadians)
                shader.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program?.delete()
                program = null
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private companion object {
            const val VERTEX_SHADER = """
                attribute vec4 aFramePosition;
                varying vec2 vTextureCoord;
                void main() {
                    gl_Position = aFramePosition;
                    vTextureCoord = (aFramePosition.xy + vec2(1.0)) * 0.5;
                }
            """

            const val FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform float uOpacity;
                uniform vec2 uPosition;
                uniform vec2 uScale;
                uniform float uRotation;
                varying vec2 vTextureCoord;

                void main() {
                    vec2 centered = vTextureCoord - vec2(0.5) - (uPosition * 0.5);
                    float c = cos(uRotation);
                    float s = sin(uRotation);
                    mat2 rotation = mat2(c, -s, s, c);
                    vec2 uv = rotation * centered;
                    uv = uv / uScale + vec2(0.5);
                    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                        gl_FragColor = vec4(0.0);
                    } else {
                        vec4 color = texture2D(uTexSampler, uv);
                        gl_FragColor = vec4(color.rgb, color.a * uOpacity);
                    }
                }
            """
        }
    }
}
