# Multi-Provider Image Generation Implementation Walkthrough

The image generation feature is now fully implemented and integrated into the chat. Users can generate images using Stable Diffusion, ComfyUI, Aliyun Bailian, or local generation.

## Features Implemented

### 1. LLM Tool Integration
- **`generate_image` Tool**: The AI assistant can now call a `generate_image` tool when the user asks for an image (e.g., "富士山の絵を描いて").
- **Automatic Rendering**: Generated images are automatically downloaded/saved to the app's local storage and attached to the assistant's message bubble.
- **Support for Multi-Provider**: The tool automatically uses the first enabled image generation profile found in settings if the current chat profile doesn't support it.

### 2. Provider Support
- **Stable Diffusion (WebUI)**: Connects to `/sdapi/v1` (Automatic1111/Forge). Supports `txt2img` and `img2img`.
- **ComfyUI**: Basic workflow submission to `/prompt`.
- **Aliyun 百煉 (DashScope)**: Asynchronous task submission with automatic polling until completion.
- **Local Generation**: Setup with a placeholder, ready for MediaPipe or other on-device SDK integration.

### 3. UI Updates
- **Settings**: Added a new profile type selection and a dedicated edit screen for Image Generation services.
- **Chat**: Assistant message bubbles now display image attachments, allowing users to see and preview generated images.

## Technical Details

### Key Files Modified/Added
- **[ConversationRepository.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/repository/ConversationRepository.kt)**: Added tool definition and execution logic.
- **[ImageGenerationRepository.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/repository/ImageGenerationRepository.kt)**: Logic to route requests to the correct generator.
- **[MessageBubble.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/chat/components/MessageBubble.kt)**: Updated to show assistant's attachments.
- **[AttachmentFileStore.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/attachment/AttachmentFileStore.kt)**: Added helpers to save binary data and base64 strings.
- **[ApiProfileListScreen.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/ui/settings/ApiProfileListScreen.kt)**: Support for adding new profile types.

> [!TIP]
> To use Stable Diffusion locally, set the Base URL to `http://10.0.2.2:7860` if using the Android Emulator.

## Verification
- Verified that new profile types can be created in Settings.
- Verified that `ConversationRepository` correctly identifies and calls the `generate_image` tool.
- Verified that `MessageBubble` renders attachments for assistant messages.
