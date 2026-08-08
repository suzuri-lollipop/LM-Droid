# Aliyun Bailian (DashScope) Image Generation Base Integration

This plan implements the "base parts" for Text-to-Image and Image-to-Image functionality using Aliyun Bailian (DashScope) APIs within the Android app.

## Proposed Changes

### [Data Layer]

#### [MODIFY] [ApiProfileEntity.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/db/ApiProfileEntity.kt)
- Add `PROVIDER_DASHSCOPE` constant to support a dedicated DashScope profile type.

#### [NEW] [BailianDtos.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/BailianDtos.kt)
- Define DTOs for DashScope Image Synthesis API:
    - `BailianImageRequest`: Request body for text-to-image and image-to-image.
    - `BailianImageResponse`: Initial response containing `task_id`.
    - `BailianTaskResponse`: Polling response containing `task_status` and `results`.

#### [NEW] [BailianApiClient.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/network/BailianApiClient.kt)
- Implement `BailianApiClient` to handle:
    - `submitImageGeneration`: Starts the generation task.
    - `checkTaskStatus`: Polls for task progress.

#### [NEW] [BailianImageRepository.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/data/repository/BailianImageRepository.kt)
- Create a repository to coordinate the generation flow:
    - `generateImage`: High-level method that submits the task, polls until completion, and returns the resulting image URL or error.

### [Dependency Injection]

#### [MODIFY] [AppContainer.kt](file:///C:/home/suzuri/projects/lm-droid/app/src/main/kotlin/com/suzuri/lmdroid/AppContainer.kt)
- Instantiate and provide `BailianApiClient` and `BailianImageRepository`.

## Verification Plan

### Automated Tests
- I will create a unit test for `BailianApiClient` to verify DTO serialization/deserialization.
- Since I don't have a real API key for testing during development, I'll mock the OkHttp responses.

### Manual Verification
- Verify that the project compiles with the new classes.
- (Future) The user can then use these base parts to implement a UI or a Tool for the LLM.
