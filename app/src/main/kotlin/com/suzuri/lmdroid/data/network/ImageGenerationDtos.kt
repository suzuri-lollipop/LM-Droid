package com.suzuri.lmdroid.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Stable Diffusion WebUI (Automatic1111/Forge) txt2img request.
 */
@Serializable
data class SdTxt2ImgRequest(
    val prompt: String,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    val steps: Int = 20,
    val width: Int = 512,
    val height: Int = 512,
    @SerialName("cfg_scale") val cfgScale: Float = 7.0f,
    val seed: Long = -1,
)

/**
 * Stable Diffusion WebUI (Automatic1111/Forge) img2img request.
 */
@Serializable
data class SdImg2ImgRequest(
    @SerialName("init_images") val initImages: List<String>,
    val prompt: String,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    val steps: Int = 20,
    val width: Int = 512,
    val height: Int = 512,
    @SerialName("cfg_scale") val cfgScale: Float = 7.0f,
    @SerialName("denoising_strength") val denoisingStrength: Float = 0.75f,
    val seed: Long = -1,
)

@Serializable
data class SdResponse(
    val images: List<String>,
    val info: String? = null,
)

/**
 * ComfyUI simplified prompt request.
 * Real ComfyUI API usually requires a full workflow JSON.
 * This can be used to send a raw JSON element representing the prompt workflow.
 */
@Serializable
data class ComfyPromptRequest(
    val prompt: JsonElement,
    @SerialName("client_id") val clientId: String? = null,
)

@Serializable
data class ComfyResponse(
    @SerialName("prompt_id") val promptId: String,
)

/**
 * Aliyun Bailian (DashScope) Image Synthesis Request.
 */
@Serializable
data class BailianImageRequest(
    val model: String,
    val input: BailianImageInput,
    val parameters: BailianImageParameters? = null,
)

@Serializable
data class BailianImageInput(
    val prompt: String,
    @SerialName("ref_img") val refImg: String? = null,
)

@Serializable
data class BailianImageParameters(
    val n: Int = 1,
    val size: String = "1024*1024",
)

@Serializable
data class BailianImageResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("output") val output: BailianTaskOutput,
)

@Serializable
data class BailianTaskOutput(
    @SerialName("task_id") val taskId: String,
    @SerialName("task_status") val taskStatus: String,
)

@Serializable
data class BailianTaskResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("output") val output: BailianTaskResultOutput,
)

@Serializable
data class BailianTaskResultOutput(
    @SerialName("task_id") val taskId: String,
    @SerialName("task_status") val taskStatus: String,
    val results: List<BailianImageResult>? = null,
    @SerialName("task_metrics") val taskMetrics: BailianTaskMetrics? = null,
)

@Serializable
data class BailianImageResult(
    val url: String,
)

@Serializable
data class BailianTaskMetrics(
    val TOTAL: Int,
    val SUCCEEDED: Int,
    val FAILED: Int,
)
