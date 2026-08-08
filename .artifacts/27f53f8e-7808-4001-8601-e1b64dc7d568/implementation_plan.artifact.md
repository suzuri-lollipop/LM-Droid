# Multi-Provider Image Generation Base Integration

This plan implements the "base parts" for Text-to-Image and Image-to-Image functionality across multiple providers: Stable Diffusion (WebUI), ComfyUI, Aliyun Bailian (DashScope), and On-device Local generation.

## Proposed Changes

### [Data Layer]

#### [MODIFY] [ApiProfileEntity.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/db/ApiProfileEntity.kt)
- Added constants for `PROVIDER_STABLE_DIFFUSION`, `PROVIDER_COMFYUI`, `PROVIDER_DASHSCOPE`, and `PROVIDER_LOCAL`.

#### [NEW] [ImageGenerationDtos.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/ImageGenerationDtos.kt)
- Define generic and provider-specific DTOs:
    - **Stable Diffusion**: `SdTxt2ImgRequest`, `SdImg2ImgRequest`, `SdResponse`.
    - **ComfyUI**: `ComfyPromptRequest`, `ComfyResponse`.
    - **Bailian**: `BailianImageRequest`, `BailianTaskResponse`.

#### [NEW] [ImageGenerator.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/ImageGenerator.kt)
- Interface `ImageGenerator` defining:
    - `generate(prompt: String, negativePrompt: String?, width: Int, height: Int, baseImage: String?): Flow<ImageGenerationState>`
- Implementations:
    - `StableDiffusionGenerator`
    - `ComfyUiGenerator`
    - `BailianGenerator`
    - `LocalImageGenerator` (On-device implementation via MediaPipe or placeholder)

#### [NEW] [ImageGenerationRepository.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/repository/ImageGenerationRepository.kt)
- Coordinates multiple generators.
- Selects the active generator based on the user's selected API profile.

### [Dependency Injection]

#### [MODIFY] [AppContainer.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/AppContainer.kt)
- Instantiate and provide `ImageGenerationRepository` and its underlying generators.

## Verification Plan

### Automated Tests
- Unit tests for each generator's request/response serialization.
- Mocked OkHttp tests for SD and ComfyUI.

### Manual Verification
- Compile and verify the project.
- Validate that different provider types can be registered in the database.
