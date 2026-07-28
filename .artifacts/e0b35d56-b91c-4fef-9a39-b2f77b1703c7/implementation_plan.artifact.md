# Implementation Plan - Support Assistant Retry via External Triggers

This plan enables the assistant to restart listening whenever the earphone AI shortcut (or any external intent) is triggered, even if the activity is already visible or in an error state.

## User Review Required

> [!NOTE]
> This change ensures that pressing the AI button on your earphones while the assistant is already open (e.g., after it failed to hear you) will immediately reset the UI and start listening again.

## Proposed Changes

### UI State

#### [MODIFY] [AssistUiState.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/AssistUiState.kt)
- Add `triggerCount: Int = 0` to track how many times the assistant has been externally triggered.

### View Model

#### [MODIFY] [AssistViewModel.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/AssistViewModel.kt)
- Add `onRetry()` function:
    - Resets `transcript`, `hasSent`, `assistantText`, and `errorMessage`.
    - Increments `triggerCount`.

### Assist Activity

#### [MODIFY] [AssistActivity.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/AssistActivity.kt)
- Store a reference to `AssistViewModel`.
- Initialize it using `ViewModelProvider` in `onCreate`.
- Call `viewModel.onRetry()` in `onNewIntent` (and optionally `onCreate` to standardize the first trigger).

### Assist Screen

#### [MODIFY] [AssistScreen.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/assist/AssistScreen.kt)
- Change `LaunchedEffect(Unit)` to `LaunchedEffect(uiState.triggerCount)`.
- (Optional) Call `voiceInputState.stop()` before `start()` in `beginListening()` to ensure a clean state.

## Verification Plan

### Automated Tests
- Run `gradle_build :app:assembleDebug`.

### Manual Verification
- Trigger the assistant via earphone button.
- Wait for it to show "聞き取れませんでした" (don't speak).
- Trigger the assistant again via earphone button.
- Verify that it immediately clears the error and shows "お話しください".
