# Implementation Plan - Support Earphone AI Shortcut (Voice Command)

This plan enables the app to be launched via earphone AI shortcut keys (typically mapped to the "Voice Command" button on Bluetooth headsets). This is achieved by registering `AssistActivity` to handle the `VOICE_COMMAND` intent.

## User Review Required

> [!IMPORTANT]
> To use this feature, you must set **LM Droid** as your device's **Digital Assistant app**.
> 1. Go to **Settings** in the app.
> 2. Select **アシスタント** (Assistant).
> 3. Follow the instructions to set it as the system assistant.
> Once set, long-pressing your earphone button (or your phone's power/home button depending on the device) will trigger LM Droid's voice input.

## Proposed Changes

### Android Manifest

#### [MODIFY] [AndroidManifest.xml](file:///C:/home/suzuri/projects/lm-droid/app/src/main/AndroidManifest.xml)
- Add `android.intent.action.VOICE_COMMAND` to the `intent-filter` of `AssistActivity`.
- Add `android.intent.action.SEARCH_LONG_PRESS` (supported by some older Bluetooth headsets) to the same `intent-filter`.

### Assist Activity

#### [MODIFY] [AssistActivity.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/AssistActivity.kt)
- Add `onNewIntent` handling to ensure that if the assistant is triggered while already open, it resets and starts listening again. (Optional, but improves robustness).

## Verification Plan

### Automated Tests
- Run `gradle_build :app:assembleDebug` to ensure manifest changes are valid.

### Manual Verification
- Deploy to a device.
- Set LM Droid as the default assistant.
- Use a Bluetooth headset with an AI button and press it.
- Verify that `AssistActivity` launches and starts listening immediately.
