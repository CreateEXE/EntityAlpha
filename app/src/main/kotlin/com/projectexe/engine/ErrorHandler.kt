package com.projectexe.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "ErrorHandler"

// ─────────────────────────────────────────────────────────────
//  TYPED ERROR HIERARCHY
//  Every error the app can produce maps to one of these.
//  UI renders actionable recovery suggestion per type.
// ─────────────────────────────────────────────────────────────

sealed class AppError(
    open val message: String,
    open val cause:   Throwable? = null,
) {
    // ── Model errors ──────────────────────────────────────────

    data class ModelNotLoaded(
        override val message: String = "No model loaded. Go to Settings and load a GGUF file.",
    ) : AppError(message)

    data class ModelFileNotFound(
        val path: String,
        override val message: String = "Model file not found: $path",
    ) : AppError(message)

    data class ModelFileCorrupt(
        val path:   String,
        val detail: String,
        override val message: String = "Model file corrupt or invalid: $detail",
    ) : AppError(message)

    data class ModelTooLarge(
        val modelGb:     Float,
        val availableGb: Float,
        override val message: String =
            "Model (${"%.1f".format(modelGb)}GB) exceeds available RAM (${"%.1f".format(availableGb)}GB).",
    ) : AppError(message)

    data class ModelLoadFailed(
        val path: String,
        override val message: String = "llama.cpp failed to load model. File may be corrupt.",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    // ── Inference / pipeline errors ───────────────────────────

    data class InferenceFailed(
        val stage: String,
        override val message: String = "Inference failed at stage $stage.",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    data class InferenceTimeout(
        val stage:   String,
        val limitMs: Long,
        override val message: String = "Stage $stage timed out after ${limitMs}ms.",
    ) : AppError(message)

    data class ContextOverflow(
        val used: Int,
        val max:  Int,
        override val message: String = "Context window full ($used/$max tokens). Clear chat to continue.",
    ) : AppError(message)

    data class AllStagesFailed(
        override val message: String = "All pipeline stages failed. Check model and try Quick mode.",
    ) : AppError(message)

    // ── Memory / system errors ────────────────────────────────

    data class OutOfMemory(
        override val message: String = "Out of memory. Try a smaller model (1.5B Q4_K_M recommended for Revvl 7).",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    data class JniCrash(
        val signal:  String,
        override val message: String = "Native crash ($signal). Engine has been reset.",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    // ── Storage / database errors ─────────────────────────────

    data class DatabaseError(
        override val message: String = "Database error. Memory may be unavailable this session.",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    data class StoragePermissionDenied(
        override val message: String = "Storage access denied. Grant permission in Android Settings.",
    ) : AppError(message)

    // ── Security / input errors ───────────────────────────────

    data class SecurityBlocked(
        val reason: String,
        override val message: String = "Input blocked by security guardian: $reason",
    ) : AppError(message)

    // ── Generic fallback ──────────────────────────────────────

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError(message, cause)
}

// ─────────────────────────────────────────────────────────────
//  RECOVERY ACTIONS — what the UI should offer the user
// ─────────────────────────────────────────────────────────────

enum class RecoveryAction {
    GO_TO_SETTINGS,       // Open settings screen
    SWITCH_TO_QUICK_MODE, // Switch pipeline to QUICK (P1 only)
    CLEAR_CHAT,           // Free context window
    RELOAD_MODEL,         // Re-run loadModel()
    RESTART_APP,          // Suggest app restart
    NONE,
}

data class ErrorWithRecovery(
    val error:    AppError,
    val recovery: RecoveryAction,
    val recoveryLabel: String,
)

fun AppError.withRecovery(): ErrorWithRecovery = when (this) {
    is AppError.ModelNotLoaded       -> ErrorWithRecovery(this, RecoveryAction.GO_TO_SETTINGS,       "Open Settings")
    is AppError.ModelFileNotFound    -> ErrorWithRecovery(this, RecoveryAction.GO_TO_SETTINGS,       "Open Settings")
    is AppError.ModelFileCorrupt     -> ErrorWithRecovery(this, RecoveryAction.GO_TO_SETTINGS,       "Open Settings")
    is AppError.ModelTooLarge        -> ErrorWithRecovery(this, RecoveryAction.GO_TO_SETTINGS,       "Open Settings")
    is AppError.ModelLoadFailed      -> ErrorWithRecovery(this, RecoveryAction.RELOAD_MODEL,         "Retry Load")
    is AppError.InferenceFailed      -> ErrorWithRecovery(this, RecoveryAction.SWITCH_TO_QUICK_MODE, "Try Quick Mode")
    is AppError.InferenceTimeout     -> ErrorWithRecovery(this, RecoveryAction.SWITCH_TO_QUICK_MODE, "Try Quick Mode")
    is AppError.ContextOverflow      -> ErrorWithRecovery(this, RecoveryAction.CLEAR_CHAT,           "Clear Chat")
    is AppError.AllStagesFailed      -> ErrorWithRecovery(this, RecoveryAction.SWITCH_TO_QUICK_MODE, "Try Quick Mode")
    is AppError.OutOfMemory          -> ErrorWithRecovery(this, RecoveryAction.GO_TO_SETTINGS,       "Load Smaller Model")
    is AppError.JniCrash             -> ErrorWithRecovery(this, RecoveryAction.RELOAD_MODEL,         "Reload Model")
    is AppError.DatabaseError        -> ErrorWithRecovery(this, RecoveryAction.NONE,                 "")
    is AppError.StoragePermissionDenied -> ErrorWithRecovery(this, RecoveryAction.RESTART_APP,       "Open App Settings")
    is AppError.SecurityBlocked      -> ErrorWithRecovery(this, RecoveryAction.NONE,                 "")
    is AppError.Unknown              -> ErrorWithRecovery(this, RecoveryAction.NONE,                 "")
}

// ─────────────────────────────────────────────────────────────
//  MODEL FILE VALIDATOR
//  Checks GGUF magic bytes and file integrity before load attempt.
//  Prevents llama.cpp from crashing on corrupt files.
// ─────────────────────────────────────────────────────────────

object ModelValidator {

    // GGUF magic: 0x47 0x47 0x55 0x46 = "GGUF"
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    // Minimum sane file size: 50MB (no real model is smaller)
    private const val MIN_SIZE_BYTES = 50L * 1024 * 1024

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val error: AppError) : ValidationResult()
    }

    fun validate(path: String, context: Context): ValidationResult {
        val file = File(path)

        // 1. File existence
        if (!file.exists()) {
            return ValidationResult.Invalid(AppError.ModelFileNotFound(path))
        }

        // 2. File size sanity
        val size = file.length()
        if (size < MIN_SIZE_BYTES) {
            return ValidationResult.Invalid(
                AppError.ModelFileCorrupt(path, "File too small (${size / 1024}KB). May be incomplete download.")
            )
        }

        // 3. GGUF magic bytes
        try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.read(magic)
                if (!magic.contentEquals(GGUF_MAGIC)) {
                    val hex = magic.joinToString(" ") { "%02X".format(it) }
                    return ValidationResult.Invalid(
                        AppError.ModelFileCorrupt(path, "Invalid GGUF magic: [$hex]. Expected [47 47 55 46].")
                    )
                }
            }
        } catch (e: Exception) {
            return ValidationResult.Invalid(
                AppError.ModelFileCorrupt(path, "Cannot read file: ${e.message}")
            )
        }

        // 4. Available RAM check
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availableGb = memInfo.availMem / (1024f * 1024f * 1024f)
        val modelGb     = size / (1024f * 1024f * 1024f)

        // Heuristic: model needs ~1.2x its file size in RAM
        if (modelGb * 1.2f > availableGb) {
            Log.w(TAG, "RAM warning: model=${modelGb}GB available=${availableGb}GB")
            // Warn but don't block — user may know what they're doing
            // Return valid but log the concern
        }

        Log.i(TAG, "Model validated: $path (${size / 1024 / 1024}MB, RAM available: ${"%.1f".format(availableGb)}GB)")
        return ValidationResult.Valid
    }

    fun availableRamGb(context: Context): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024f * 1024f * 1024f)
    }

    fun totalRamGb(context: Context): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024f * 1024f * 1024f)
    }
}

// ─────────────────────────────────────────────────────────────
//  ERROR HANDLER — central error bus + OOM/JNI recovery
// ─────────────────────────────────────────────────────────────

class ErrorHandler {

    private val _errors = MutableStateFlow<ErrorWithRecovery?>(null)
    val errors: StateFlow<ErrorWithRecovery?> = _errors.asStateFlow()

    // Consecutive inference failure counter — triggers degradation after 2 failures
    private var consecutiveInferenceFailures = 0

    fun post(error: AppError) {
        Log.e(TAG, "[${error::class.simpleName}] ${error.message}", error.cause)
        _errors.value = error.withRecovery()
    }

    fun clear() { _errors.value = null }

    // ── OOM Recovery ─────────────────────────────────────────

    fun handleOom(cause: Throwable? = null): AppError.OutOfMemory {
        Log.e(TAG, "OOM detected — unloading model")
        runCatching { LlamaEngine.get().free() }
        return AppError.OutOfMemory(cause = cause).also { post(it) }
    }

    // ── JNI crash isolation ───────────────────────────────────
    // Called from a try-catch around JNI calls. UnsatisfiedLinkError,
    // IllegalStateException from dead engine, etc. are all caught here.

    fun handleJniException(e: Throwable, stage: String = "unknown"): AppError {
        val error = when (e) {
            is OutOfMemoryError ->
                handleOom(e)
            is UnsatisfiedLinkError ->
                AppError.JniCrash("LINK_ERROR", cause = e).also {
                    Log.e(TAG, "JNI link error — .so may be missing: ${e.message}")
                    runCatching { LlamaEngine.get().free() }
                }
            else -> {
                val msg = e.message ?: e::class.simpleName ?: "unknown"
                if (msg.contains("SIGSEGV", ignoreCase = true) ||
                    msg.contains("SIGABRT", ignoreCase = true)) {
                    Log.e(TAG, "Native signal caught — resetting engine")
                    runCatching { LlamaEngine.get().free() }
                    AppError.JniCrash(msg.take(20), cause = e)
                } else {
                    AppError.InferenceFailed(stage, cause = e)
                }
            }
        }
        post(error)
        return error
    }

    // ── Inference failure tracking ────────────────────────────

    fun recordInferenceFailure(stage: String, cause: Throwable? = null): AppError {
        consecutiveInferenceFailures++
        val error = if (consecutiveInferenceFailures >= 3)
            AppError.AllStagesFailed()
        else
            AppError.InferenceFailed(stage, cause = cause)
        post(error)
        return error
    }

    fun recordInferenceSuccess() { consecutiveInferenceFailures = 0 }

    // ── Wrap any suspend call with typed error handling ───────

    suspend fun <T> runSafe(
        stage: String = "unknown",
        fallback: T? = null,
        block: suspend () -> T,
    ): Result<T> = try {
        val result = block()
        recordInferenceSuccess()
        Result.success(result)
    } catch (e: OutOfMemoryError) {
        Result.failure(handleOom(e))
    } catch (e: Exception) {
        Result.failure(handleJniException(e, stage))
    }
}

// ─────────────────────────────────────────────────────────────
//  PIPELINE DEGRADATION LADDER
//  When Full mode fails, auto-step down through modes.
// ─────────────────────────────────────────────────────────────

enum class DegradationLevel {
    FULL,            // P1→F1→P2→F2→P3  — all stages
    PERSONA_ONLY,    // P1→P3            — skip factual
    QUICK,           // P1 only          — fastest
    FAILED,          // All modes failed
}

object DegradationLadder {
    fun next(current: DegradationLevel): DegradationLevel = when (current) {
        DegradationLevel.FULL         -> DegradationLevel.PERSONA_ONLY
        DegradationLevel.PERSONA_ONLY -> DegradationLevel.QUICK
        DegradationLevel.QUICK        -> DegradationLevel.FAILED
        DegradationLevel.FAILED       -> DegradationLevel.FAILED
    }

    fun label(level: DegradationLevel): String = when (level) {
        DegradationLevel.FULL         -> "Full Pipeline"
        DegradationLevel.PERSONA_ONLY -> "Persona Only (factual skipped)"
        DegradationLevel.QUICK        -> "Quick Mode (P1 only)"
        DegradationLevel.FAILED       -> "All modes failed"
    }
}
