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
import kotlin.math.PI

/**
 * Original SOWZ camera shake effect.
 * Uses frame presentation timestamps to keep preview and export deterministic.
 */
@UnstableApi
internal class SowzShakeEffect(
    private val amplitudePixels: Float,
    private val frequencyHz: Float,
    private val rotationDegrees: Float,
    private val seed: Int = 1
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        amplitudePixels <= 0.001f && rotationDegrees <= 0.001f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ShakeShaderProgram(useHdr, amplitudePixels, frequencyHz, rotationDegrees, seed)

    private class ShakeShaderProgram(
        useHdr: Boolean,
        amplitudePixels: Float,
        frequencyHz: Float,
        rotationDegrees: Float,
        seed: Int
    ) : BaseGlShaderProgram(useHdr, 1) {
        private val amplitude = amplitudePixels.coerceIn(0f, 64f)
        private val frequency = frequencyHz.coerceIn(0.25f, 30f)
        private val rotation = rotationDegrees.coerceIn(0f, 20f) * (PI.toFloat() / 180f)
        private val randomSeed = seed.toFloat()
        private var program: GlProgram? = null
        private var frameWidth = 1
        private var frameHeight = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            frameWidth = inputWidth.coerceAtLeast(1)
            frameHeight = inputHeight.coerceAtLeast(1)
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
                "SOWZ Shake shader was not configured.",
                presentationTimeUs
            )
            val timeSeconds = presentationTimeUs.coerceAtLeast(0L).toFloat() / 1_000_000f
            try {
                shader.use()
                shader.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                shader.setFloatsUniform(
                    "uAmplitude",
                    floatArrayOf(
                        2f * amplitude / frameWidth.toFloat(),
                        2f * amplitude / frameHeight.toFloat()
                    )
                )
                shader.setFloatUniform("uTime", timeSeconds)
                shader.setFloatUniform("uFrequency", frequency)
                shader.setFloatUniform("uRotation", rotation)
                shader.setFloatUniform(
                    "uZoom",
                    1f + (2f * amplitude / minOf(frameWidth, frameHeight)).coerceAtMost(0.18f)
                )
                shader.setFloatUniform("uSeed", randomSeed)
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
                uniform vec2 uAmplitude;
                uniform float uTime;
                uniform float uFrequency;
                uniform float uRotation;
                uniform float uZoom;
                uniform float uSeed;
                varying vec2 vTextureCoord;

                float hashValue(float value) {
                    return fract(sin(value * 127.1 + uSeed * 17.0) * 43758.5453) * 2.0 - 1.0;
                }

                float smoothNoise(float t, float offset) {
                    float cell = floor(t + offset);
                    float f = fract(t + offset);
                    f = f * f * (3.0 - 2.0 * f);
                    return mix(hashValue(cell), hashValue(cell + 1.0), f);
                }

                void main() {
                    float t = uTime * uFrequency;
                    vec2 shake = vec2(
                        smoothNoise(t, 0.0) * uAmplitude.x,
                        smoothNoise(t, 31.7) * uAmplitude.y
                    );
                    float angle = smoothNoise(t, 67.4) * uRotation;
                    float c = cos(angle);
                    float s = sin(angle);
                    mat2 turn = mat2(c, -s, s, c);
                    vec2 uv = turn * (vTextureCoord - vec2(0.5));
                    uv = uv / uZoom - shake + vec2(0.5);
                    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    } else {
                        gl_FragColor = texture2D(uTexSampler, uv);
                    }
                }
            """
        }
    }
}
