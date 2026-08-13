# Implementation Plan - Real-time Whisper Transcription

The user reported that Whisper transcription gets stuck in the "processing" state and requested real-time feedback similar to Google's transcription service. Currently, `WhisperEngine` performs a blocking synchronous inference only after detecting silence, which freezes the audio acquisition loop and provides no interim feedback beyond a simple "Hearing..." status.

## User Review Required

> [!IMPORTANT]
> To achieve a "real-time" feel, Whisper inference will be run periodically (every ~1.5 seconds) on the cumulative audio buffer. This increases CPU usage during speech. On lower-end devices or with larger Whisper models (e.g., Small), this might cause significant battery drain or thermal throttling.

## Proposed Changes

### Core Engine Improvements

#### [MODIFY] [WhisperEngine.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/stt/WhisperEngine.kt)
- **Asynchronous Inference**: Move `native.full` calls to a background coroutine using `Dispatchers.Default`.
- **Non-blocking `acceptAudio`**: Ensure the audio acquisition loop in `VoiceInput.kt` is never blocked by Whisper inference.
- **Partial Results**:
    - Trigger "partial" inferences every 1.5 seconds of accumulated audio while speech is detected.
    - Update `partialResult` with the actual transcribed text from these partial passes.
- **State Management**:
    - Use a `Mutex` to prevent concurrent access to the `whisper_context`.
    - Implement a mechanism to signal the final result to `acceptAudio` only after the final background inference completes.
- **Resource Cleanup**: Ensure all background jobs are cancelled and the context is released safely in `release()`.

### JNI Layer (Optional Enhancement)

#### [MODIFY] [whisper_jni.cpp](file:///C:/home/suzuri/projects/lm-droid/app/src/main/cpp/whisper_jni.cpp)
- Verify `n_threads` settings and ensure they are optimal for mobile (defaulting to 4).

## Verification Plan

### Automated Tests
- No specific automated tests exist for this component yet, but I will perform manual verification.

### Manual Verification
1.  **Launch the App**: Open the chat screen.
2.  **Voice Input**: Tap the microphone icon and speak a long sentence.
3.  **Real-time Feedback**: Observe if words start appearing while still speaking (the "partial result" area).
4.  **No Freezing**: Verify that the "Hearing..." animation (or partial text) pulses smoothly and the UI remains responsive.
5.  **Final Result**: Stop speaking and verify that the final text is correctly populated into the input field after a brief processing period.
6.  **Switch Models**: Test with "Whisper Tiny" and "Whisper Small" to observe performance differences.
