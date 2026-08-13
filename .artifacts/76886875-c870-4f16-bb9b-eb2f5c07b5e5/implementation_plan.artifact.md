# Implementation Plan - Fix Whisper Local Speech Recognition

The user reported that Whisper local speech recognition is not working in the assistant overlay. Investigation revealed several potential issues:
1.  **Energy Threshold**: The current RMS threshold (0.01f) for detecting speech might be too high for some devices or environments, preventing the engine from ever triggering inference.
2.  **Lack of Feedback**: `WhisperEngine` does not provide partial results, so the UI stays on the "Listening..." hint without any changes while the user speaks, making it appear as if nothing is happening.
3.  **Error Handling**: The recognition job only catches `Exception`, missing potential `UnsatisfiedLinkError` if the native library fails to load (e.g., due to 16KB page alignment issues or missing dependencies).
4.  **Initialization**: `LocalVoiceInputState` does not check if the engine is successfully initialized before starting.
5.  **No Speech Timeout**: There is no mechanism to stop listening if speech is never detected, leading to a "stuck" UI.

## User Review Required

> [!IMPORTANT]
> Whisper is inherently slower than Vosk and cannot provide real-time partial transcripts without significant performance overhead. This plan adds "pseudo-partial" feedback (showing sound activity) to improve perceived responsiveness, but it will still only show the final transcript after speech ends.

## Proposed Changes

### Core STT Engine

#### [MODIFY] [WhisperEngine.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/stt/WhisperEngine.kt)
- Lower `energyThreshold` to `0.005f` for better sensitivity.
- Implement a simple "pseudo-partial" result by returning a dynamic string (e.g., "..." with varying number of dots) when sound is detected, giving the user visual feedback that the app is "hearing" them.
- Add a `noSpeechTimeout` mechanism: if no speech is detected after 5 seconds of listening, `acceptAudio` will return `true` with an empty result, allowing `LocalVoiceInputState` to handle it as "no speech detected".
- Optimize `audioBuffer` usage (consider using a `FloatArray` or just keeping the `MutableList` but reducing allocations if possible).
- Add more robust logging for initialization and inference.

### Voice Input UI State

#### [MODIFY] [VoiceInput.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/chat/components/VoiceInput.kt)
- Change `catch (e: Exception)` to `catch (e: Throwable)` in `LocalVoiceInputState.start` to catch `UnsatisfiedLinkError`.
- Check `engine.isReady` before starting the `AudioRecord` and error out if the engine failed to initialize.
- Ensure that an empty result from the engine (due to timeout) is handled appropriately (e.g., by stopping the listener).

### Native JNI (Verification)

#### [MODIFY] [whisper_jni.cpp](file:///C:/home/suzuri/projects/lm-droid/app/src/main/cpp/whisper_jni.cpp)
- Add more logging to `Java_com_suzuri_lmdroid_data_stt_WhisperNative_init` and `full` to help debug if native code is reached.

## Verification Plan

### Manual Verification
1.  Deploy the app to a device/emulator.
2.  Go to Settings -> Voice and select a Whisper model (download if necessary).
3.  Open the Assistant overlay (long-press power button or swipe from corner).
4.  Verify that the UI shows "Listening..." and responds with some visual change when you speak (the pseudo-partial results).
5.  Verify that speaking a short phrase results in a transcript after a short silence.
6.  Verify that if you stay silent for >5 seconds, the overlay eventually shows "No speech detected" instead of being stuck.
7.  Check Logcat for "WhisperEngine" tags to verify initialization and inference triggers.
