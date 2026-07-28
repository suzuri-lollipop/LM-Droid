# Walkthrough - Earphone AI Shortcut Support

I have updated the application to support launching via earphone AI shortcut keys (Voice Command) and long-press search gestures.

## Changes Made

### Android Manifest

#### [AndroidManifest.xml](file:///C:/home/suzuri/projects/lm-droid/app/src/main/AndroidManifest.xml)
- Registered `AssistActivity` to handle `android.intent.action.VOICE_COMMAND`. This is the standard intent triggered by Bluetooth headsets when their AI/Voice button is pressed.
- Added `android.intent.action.SEARCH_LONG_PRESS` for compatibility with older devices and headsets.

### Assist Activity

#### [AssistActivity.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/AssistActivity.kt)
- Added `onNewIntent` override to correctly handle the activity's lifecycle as a `singleInstance`. This ensures that if the assistant is triggered multiple times while the window is still open, the intent is properly updated.

## Verification Results

### Automated Tests
- Successfully ran `./gradlew :app:assembleDebug`.

```
Build finished successfully.
```

## How to use
1.  **Set as Default Assistant**: Ensure LM Droid is set as your device's "Digital Assistant App" (App Settings -> Assistant).
2.  **Use Earphones**: Long-press the "AI" or "Voice" button on your Bluetooth earphones.
3.  **Result**: The LM Droid assistant overlay should appear and start listening for your voice immediately.
