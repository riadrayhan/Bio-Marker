package com.riad.bio_marker.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * TFLite-backed engine. Expects a model file at `assets/risk_model.tflite` whose:
 *  - input shape  = [1, FeatureSpec.SIZE], dtype float32
 *  - output shape = [1, 1],                 dtype float32 (score 0..1)
 *
 * If the asset is missing or fails to load, [tryCreate] returns null and the app
 * falls back to [HeuristicRiskEngine] — so this can ship before a model is trained.
 */
class TFLiteRiskEngine private constructor(
    private val interpreter: Interpreter,
    private val baseline: UserBaseline,
) : RiskInferenceEngine {

    override val name: String = "tflite-risk-v1"

    override fun infer(features: FeatureVector): RiskResult {
        // Personalisation: z-score normalise each feature against the user's own baseline
        // before feeding it into the model. Models trained on z-scored features generalise
        // far better across people than raw values.
        val normalised = FloatArray(FeatureSpec.SIZE) { i ->
            val key = FeatureSpec.NAMES[i]
            if (baseline.isWarm(key)) baseline.zScore(key, features.values[i])
            else features.values[i] // fall back to raw during warm-up
        }

        val input = ByteBuffer
            .allocateDirect(4 * FeatureSpec.SIZE)
            .order(ByteOrder.nativeOrder())
        normalised.forEach { input.putFloat(it) }
        input.rewind()

        val output = Array(1) { FloatArray(1) }
        interpreter.run(input, output)

        val score = output[0][0].coerceIn(0f, 1f)
        val phq9 = (score * 27f).toInt().coerceIn(0, 27)

        // Lightweight contribution proxy: largest |z-score| features.
        val top = FeatureSpec.NAMES
            .mapIndexed { i, n -> n to abs(normalised[i]) }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }

        return RiskResult(score, phq9, top, name)
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "TFLiteRiskEngine"
        private const val ASSET_NAME = "risk_model.tflite"

        fun tryCreate(context: Context, baseline: UserBaseline): TFLiteRiskEngine? = try {
            val model = loadAsset(context, ASSET_NAME)
            val opts = Interpreter.Options().apply { numThreads = 2 }
            val interpreter = Interpreter(model, opts)
            Log.i(TAG, "Loaded $ASSET_NAME — input=${interpreter.getInputTensor(0).shape().toList()}, " +
                "output=${interpreter.getOutputTensor(0).shape().toList()}")
            TFLiteRiskEngine(interpreter, baseline)
        } catch (t: Throwable) {
            // Loud failure: previously this was Log.i which silently dropped the
            // real cause (compressed asset, bad input shape, missing file, etc.).
            // Without the stack trace the dashboard just kept saying "heuristic-v0"
            // forever and there was no way to know why.
            Log.e(TAG, "Failed to load $ASSET_NAME — falling back to heuristic engine", t)
            null
        }

        /**
         * Try to memory-map the asset (fast, zero-copy). If that fails — typically
         * because AGP compressed the asset despite our `noCompress` rule, or the
         * asset lives inside a split APK — fall back to copying the bytes into a
         * direct [ByteBuffer]. Both are accepted by [Interpreter].
         */
        private fun loadAsset(context: Context, name: String): ByteBuffer {
            return try {
                val fd = context.assets.openFd(name)
                FileInputStream(fd.fileDescriptor).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            } catch (mmapFail: Throwable) {
                Log.w(TAG, "mmap of $name failed (${mmapFail.message}); reading bytes instead")
                context.assets.open(name).use { input ->
                    val bytes = input.readBytes()
                    val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                    buf.put(bytes)
                    buf.rewind()
                    buf
                }
            }
        }
    }
}

