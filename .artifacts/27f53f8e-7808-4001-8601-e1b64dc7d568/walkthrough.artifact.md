# Multi-Provider Image Generation Walkthrough

This update adds the foundational "base parts" for image generation (Text-to-Image and Image-to-Image) supporting multiple providers, including local on-device generation.

## Key Components

### 1. Data Models & API Clients
- **[ImageGenerationDtos.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/ImageGenerationDtos.kt)**: Contains DTOs for:
    - **Stable Diffusion WebUI**: `SdTxt2ImgRequest`, `SdImg2ImgRequest`, `SdResponse`.
    - **ComfyUI**: `ComfyPromptRequest` (flexible JSON workflow submission).
    - **Aliyun Bailian**: `BailianImageRequest` and polling DTOs.
- **[StableDiffusionGenerator.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/StableDiffusionGenerator.kt)**: Direct implementation of SD WebUI `/sdapi/v1` endpoints.
- **[ComfyUiGenerator.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/ComfyUiGenerator.kt)**: Basic submission to ComfyUI `/prompt`.
- **[BailianGenerator.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/BailianGenerator.kt)**: Asynchronous generation with automatic polling.
- **[LocalImageGenerator.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/LocalImageGenerator.kt)**: On-device generation placeholder (setup for MediaPipe).

### 2. Abstraction Layer
- **[ImageGenerator.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/ImageGenerator.kt)**: Defines a unified interface and a `Flow`-based state management system (`Idle`, `Loading`, `Success`, `Error`).
- **[ImageGenerationRepository.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/repository/ImageGenerationRepository.kt)**: The central entry point. It checks the currently active API profile and routes the request to the correct generator.

### 3. Integration
- **[ApiProfileEntity.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/db/ApiProfileEntity.kt)**: Added new provider types to support registering these services in the app's settings.
- **[AppContainer.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/AppContainer.kt)**: All generators and the repository are instantiated and ready for use via dependency injection.

> [!NOTE]
> Local generation is currently a placeholder. To enable it, you will need to add MediaPipe dependencies and model files.

## Verification
- Project compiles successfully.
- Code structure follows the existing repository patterns (hand-rolled DI, Flow-based data streaming).
