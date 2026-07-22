package eu.ydiaeresis.filmasonde

import android.content.Context
import android.os.Environment
import android.util.Log
// Delete any 'import dev...' lines and use this exact package line
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.util.Locale

class TelemetrySubtitleBuilder {
    private val frames = mutableListOf<TelemetrySnapshot>()

    data class TelemetrySnapshot(
        val elapsedMillis: Long,
        val frameNum: Int,
        val height: Double,
        val lat: Double,
        val lon: Double
    )

    fun addFrame(elapsedMillis: Long, frameNum: Int, lat: Double, lon: Double, height: Double) {
        frames.add(TelemetrySnapshot(elapsedMillis, frameNum, height, lat, lon))
    }

    /**
     * Call this inside VideoRecordEvent.Finalize to render safe data
     * @param videoDurationMs Pass (recordEvent.recordingStats.recordedDurationNanos / 1_000_000)
     */
    fun build(videoDurationMs: Long): String {
        Log.i("SUBS","videoDurationMs:${videoDurationMs}")
        if (frames.isEmpty()) return "1\n00:00:00,200 --> ${formatMillisToSrt(videoDurationMs)}\nNo Telemetry Data\n\n"

        val sb = StringBuilder()

        // 1. Sort the frames chronologically just in case threads delivered them out of order
        val sortedFrames = frames.sortedBy { it.elapsedMillis }

        var explicitIndex = 1

        for ((i, current) in sortedFrames.withIndex()) {

            // 2. Set the end time to the next frame's start time, or 1 second later if it's the last frame
            val targetEndMillis = if (i < sortedFrames.size - 1) {
                sortedFrames[i + 1].elapsedMillis
            } else {
                current.elapsedMillis + 1000
            }

            // 3. CRITICAL FFmpeg SAFETY CAP: Clamp the timestamps so they never exceed the video length
            val startTimeMillis = minOf(current.elapsedMillis, videoDurationMs - 100)
            val endTimeMillis = minOf(targetEndMillis, videoDurationMs) - 1

            // 4. Skip writing the block if the recording was stopped before this packet even occurred
            if (targetEndMillis>=videoDurationMs) break

            val startTimeStr = formatMillisToSrt(startTimeMillis)
            val endTimeStr = formatMillisToSrt(endTimeMillis)

            sb.append("$explicitIndex\n")
            sb.append("$startTimeStr --> $endTimeStr\n")
            sb.append("Frame: %d | Alt: %.1fm\n".format(Locale.US, current.frameNum, current.height))
            sb.append("GPS: %.6f, %.6f\n\n".format(Locale.US, current.lat,current.lon))

            explicitIndex++
        }

        if (sb.isEmpty()) return "1\n00:00:00,200 --> ${formatMillisToSrt(videoDurationMs)}\nNo Telemetry Data\n\n"
        return sb.toString()
    }

    private fun formatMillisToSrt(totalMillis: Long): String {
        val h = totalMillis / 3600000
        val m = (totalMillis % 3600000) / 60000
        val s = (totalMillis % 60000) / 1000
        val ms = totalMillis % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    companion object {
        fun createTempRecordingFile(context: Context): File {
            val timestamp = System.currentTimeMillis()
            // Generates a single unique raw video file path inside the safe app cache
            return File(context.cacheDir, "raw_record_$timestamp.mp4")
        }

        fun finaliseVideoWithSubtitles(
            context: Context,
            tempVideoFile: File,
            inMemorySrtText: String,
            onResult: (Result<File>) -> Unit
        ) {
            if (!tempVideoFile.exists()) {
                onResult(Result.failure(IllegalArgumentException("Source temporary video file missing.")))
                return
            }

            // 1. Create a safe temporary subtitle file in the cache directory
            val tempSrtFile = File(context.cacheDir, "temp_subs_${System.currentTimeMillis()}.srt")

            try {
                tempSrtFile.writeText(inMemorySrtText)

                // 2. Target public directory configuration
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "Filmasonde"
                )
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }
                val finalPublicFile =
                    File(publicDir, "FilmaSonde_${System.currentTimeMillis()}.mp4")

                // 3. FIX: Build an explicit Argument Array instead of a raw String command.
                // This stops whitespaces like "Internal shared storage" from crashing the engine.
                val commandArguments = arrayOf(
                    "-fflags", "+genpts",            // 1. Force regenerate presentation timestamps to handle camera lag
                    "-i", tempVideoFile.absolutePath,
                    "-i", tempSrtFile.absolutePath,
                    "-c:v", "copy",
                    "-c:a", "copy",
                    "-c:s", "mov_text",
                    "-y",
                    finalPublicFile.absolutePath
                )

                // 4. Pass the Array directly into the compiler engine
                FFmpegKit.executeAsync(commandArguments.joinToString(" ")) { session ->
                    // Always delete the temporary subtitle file
                    tempSrtFile.delete()

                    if (session.returnCode.isValueSuccess) {
                        // Success: Delete the temporary video cache file
                        tempVideoFile.delete()
                        onResult(Result.success(finalPublicFile))
                    } else {
                        // Extract logs to trace exact issues if a crash persists
                        val logs = session.logsAsString ?: "No log output available"
                        onResult(Result.failure(RuntimeException("FFmpeg failed. Logs: $logs")))
                    }
                }
            } catch (e: Exception) {
                tempSrtFile.delete()
                onResult(Result.failure(e))
            }
        }
    }
}