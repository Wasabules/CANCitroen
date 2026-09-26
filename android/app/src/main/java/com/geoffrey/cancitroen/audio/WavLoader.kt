package com.geoffrey.cancitroen.audio

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sample audio chargé en mémoire : PCM mono normalisé en Float ([-1..1]).
 */
data class LoadedSample(
    val pcm: FloatArray,
    val sampleRate: Int,
)

/**
 * Loader WAV minimal — supporte PCM 16-bit mono ou stereo (downmix L+R/2).
 * Lit le sample depuis assets/<path>.
 *
 * Format WAV attendu :
 *   - 44 bytes header standard
 *   - chunk "fmt " avec audioFormat = 1 (PCM), bitsPerSample = 16
 *   - chunk "data" suivi des samples PCM little-endian
 *
 * Les WAV générés par ffmpeg / Audacity / OpenGameArt sont compatibles.
 * Pour les FLAC / OGG / MP3, convertis en WAV PCM 16-bit en amont.
 */
object WavLoader {
    private const val TAG = "WavLoader"

    fun loadFromAssets(context: Context, assetPath: String): LoadedSample? {
        return try {
            context.assets.open(assetPath).use { stream ->
                val bytes = stream.readBytes()
                parse(bytes)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Asset introuvable : $assetPath")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Erreur parsing $assetPath", e)
            null
        }
    }

    private fun parse(bytes: ByteArray): LoadedSample? {
        if (bytes.size < 44) return null
        // "RIFF"...."WAVE"
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null

        // Cherche les chunks "fmt " et "data"
        var idx = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (idx + 8 <= bytes.size) {
            val chunkId = String(bytes, idx, 4)
            val chunkSize = readInt32LE(bytes, idx + 4)
            // Garde-fou : un WAV corrompu peut avoir chunkSize négatif, ce qui
            // ferait `idx += 8 + chunkSize` reculer → boucle infinie.
            if (chunkSize < 0) {
                Log.w(TAG, "WAV corrompu (chunkSize négatif sur '$chunkId')")
                return null
            }
            when (chunkId) {
                "fmt " -> {
                    val audioFormat = readInt16LE(bytes, idx + 8).toInt() and 0xFFFF
                    if (audioFormat != 1) {
                        Log.w(TAG, "Format non-PCM : $audioFormat")
                        return null
                    }
                    channels = (readInt16LE(bytes, idx + 10).toInt() and 0xFFFF)
                    sampleRate = readInt32LE(bytes, idx + 12)
                    bitsPerSample = (readInt16LE(bytes, idx + 22).toInt() and 0xFFFF)
                }
                "data" -> {
                    dataOffset = idx + 8
                    dataSize = chunkSize
                }
            }
            idx += 8 + chunkSize
            if (chunkSize % 2 != 0) idx++   // padding
        }

        if (dataOffset < 0 || sampleRate == 0 || bitsPerSample != 16) {
            Log.w(TAG, "WAV non supporté (data=$dataOffset sr=$sampleRate bps=$bitsPerSample)")
            return null
        }

        val frameCount = dataSize / (2 * channels)
        val pcm = FloatArray(frameCount)
        val bb = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frameCount) {
            // Mix down si stereo
            var sum = 0
            for (c in 0 until channels) {
                sum += bb.short.toInt()
            }
            pcm[f] = (sum.toFloat() / channels) / 32768f
        }
        return LoadedSample(pcm, sampleRate)
    }

    private fun readInt16LE(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)).toShort()

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)
}
