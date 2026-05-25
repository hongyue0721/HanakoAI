package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.automation.AutomationResult
import `fun`.kirari.hanako.automation.automationToolsForChatCompletions
import `fun`.kirari.hanako.automation.automationToolsForAnthropic
import `fun`.kirari.hanako.automation.automationToolsForGoogle
import `fun`.kirari.hanako.automation.automationToolsForResponses
import `fun`.kirari.hanako.automation.extractJsonTextField
import `fun`.kirari.hanako.automation.validateAutomationAction
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val STREAM_TAG = "HanakoAiStream"

internal suspend fun AiGateway.streamText(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    onDelta: (String) -> Unit
): String {
    AppDebugLogStore.i(STREAM_TAG, "streamText provider=${provider.kind} model=$model")
    return when (provider.kind) {
        ProviderKind.OPENAI_RESPONSES -> streamResponses(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = null,
            onDelta = onDelta
        )

        ProviderKind.ANTHROPIC -> streamAnthropic(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = null,
            onDelta = onDelta
        )

        ProviderKind.GOOGLE -> streamGoogle(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = null,
            onDelta = onDelta
        )

        ProviderKind.OPENAI_COMPATIBLE -> streamOpenAiChat(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = null,
            onDelta = onDelta
        )
    }
}

internal suspend fun AiGateway.streamVision(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String,
    onDelta: (String) -> Unit
): String {
    AppDebugLogStore.i(STREAM_TAG, "streamVision provider=${provider.kind} model=$model hasImage=true")
    return when (provider.kind) {
        ProviderKind.OPENAI_RESPONSES -> streamResponses(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            onDelta = onDelta
        )

        ProviderKind.ANTHROPIC -> streamAnthropic(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            onDelta = onDelta
        )

        ProviderKind.GOOGLE -> streamGoogle(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            onDelta = onDelta
        )

        ProviderKind.OPENAI_COMPATIBLE -> streamOpenAiChat(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            onDelta = onDelta
        )
    }
}

internal suspend fun AiGateway.streamAutomation(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onThoughtDelta: (String) -> Unit
): AutomationResult {
    AppDebugLogStore.i(STREAM_TAG, "streamAutomation provider=${provider.kind} model=$model hasImage=${imageBase64 != null}")
    return when (provider.kind) {
        ProviderKind.OPENAI_RESPONSES -> streamResponsesAutomation(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            onThoughtDelta = onThoughtDelta
        )

        ProviderKind.OPENAI_COMPATIBLE -> streamOpenAiChatAutomation(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            onThoughtDelta = onThoughtDelta
        )

        ProviderKind.ANTHROPIC -> streamAnthropicAutomation(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            onThoughtDelta = onThoughtDelta
        )

        ProviderKind.GOOGLE -> streamGoogleAutomation(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBase64 = imageBase64,
            onThoughtDelta = onThoughtDelta
        )
    }
}

internal suspend fun AiGateway.streamOpenAiChat(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onDelta: (String) -> Unit
): String {
    AppDebugLogStore.i(STREAM_TAG, "streamOpenAiChat request model=$model")
    val payload = buildJsonObject {
        put("model", model)
        put("stream", true)
        put(
            "messages",
            buildJsonArray {
                add(openAiMessage("system", systemPrompt))
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", userPrompt)
                                })
                                imageBase64?.let {
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", "data:image/jpeg;base64,$it")
                                            put("detail", "high")
                                        })
                                    })
                                }
                            }
                        )
                    }
                )
            }
        )
    }

    return sseClient.stream(
        request = baseRequest(provider, "${provider.baseUrl.trimEnd('/')}/chat/completions", payload),
        onEvent = { _, _, _, data ->
            if (data == "[DONE]") {
                SseStreamClient.StreamEventResult(done = true)
            } else {
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: null
                SseStreamClient.StreamEventResult(delta = root?.let { extractOpenAiChatDelta(it) })
            }
        },
        onDelta = onDelta
    )
}

internal suspend fun AiGateway.streamResponses(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onDelta: (String) -> Unit
): String {
    AppDebugLogStore.i(STREAM_TAG, "streamResponses request model=$model")
    val payload = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("instructions", systemPrompt)
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", userPrompt)
                                })
                                imageBase64?.let {
                                    add(buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", "data:image/jpeg;base64,$it")
                                    })
                                }
                            }
                        )
                    }
                )
            }
        )
    }

    return sseClient.stream(
        request = baseRequest(provider, "${provider.baseUrl.trimEnd('/')}/responses", payload),
        onEvent = { _, type, _, data ->
            if (data == "[DONE]") {
                SseStreamClient.StreamEventResult(done = true)
            } else {
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: null
                SseStreamClient.StreamEventResult(delta = when (type) {
                    "response.output_text.delta" -> root?.get("delta")?.jsonPrimitive?.contentOrNull
                    else -> null
                })
            }
        },
        onDelta = onDelta
    )
}

internal suspend fun AiGateway.streamOpenAiChatAutomation(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onThoughtDelta: (String) -> Unit
): AutomationResult {
    AppDebugLogStore.i(STREAM_TAG, "streamOpenAiChatAutomation request model=$model")
    val payload = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("tool_choice", "required")
        put("tools", automationToolsForChatCompletions())
        put(
            "messages",
            buildJsonArray {
                add(openAiMessage("system", systemPrompt))
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", userPrompt)
                                })
                                imageBase64?.let {
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", "data:image/jpeg;base64,$it")
                                            put("detail", "high")
                                        })
                                    })
                                }
                            }
                        )
                    }
                )
            }
        )
    }
    AppDebugLogStore.d(STREAM_TAG, "streamOpenAiChatAutomation payload tools=chat_completions toolChoice=required hasImage=${imageBase64 != null}")

    val thought = StringBuilder()
    var toolName: String? = null
    val toolArguments = StringBuilder()
    var loggedThoughtDelta = false
    sseClient.stream(
        request = baseRequest(provider, "${provider.baseUrl.trimEnd('/')}/chat/completions", payload),
        onEvent = { _, _, _, data ->
            if (data == "[DONE]") return@stream SseStreamClient.StreamEventResult(done = true)
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@stream null
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@stream null
            val delta = choice["delta"]?.jsonObject ?: return@stream null
            val textDelta = extractOpenAiContent(delta["content"])
            if (textDelta.isNotBlank()) {
                thought.append(textDelta)
                if (!loggedThoughtDelta) {
                    loggedThoughtDelta = true
                    AppDebugLogStore.i(STREAM_TAG, "streamOpenAiChatAutomation first thought delta received")
                }
            }
            delta["tool_calls"]?.jsonArray?.forEach { item ->
                val toolCall = item.jsonObject
                val function = toolCall["function"]?.jsonObject ?: return@forEach
                val deltaName = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (deltaName.isNotEmpty()) {
                    toolName = deltaName
                }
                toolArguments.append(function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty())
                AppDebugLogStore.d(
                    STREAM_TAG,
                    "streamOpenAiChatAutomation tool delta rawName=$deltaName resolvedName=$toolName argsLength=${toolArguments.length}"
                )
            }
            SseStreamClient.StreamEventResult(delta = textDelta.ifBlank { null })
        },
        onDelta = onThoughtDelta
    )
    val actionName = toolName ?: error("自动模式未调用工具")
    val args = runCatching { json.parseToJsonElement(toolArguments.toString()).jsonObject }.getOrElse {
        AppDebugLogStore.e(STREAM_TAG, "streamOpenAiChatAutomation tool args parse failed raw=$toolArguments", it)
        error("自动模式工具参数解析失败")
    }
    AppDebugLogStore.i(STREAM_TAG, "streamOpenAiChatAutomation completed tool=$actionName thoughtLength=${thought.length}")
    return AutomationResult(
        thought = thought.toString().trim(),
        action = validateAutomationAction(actionName, extractJsonTextField(args))
    )
}

internal suspend fun AiGateway.streamResponsesAutomation(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onThoughtDelta: (String) -> Unit
): AutomationResult {
    AppDebugLogStore.i(STREAM_TAG, "streamResponsesAutomation request model=$model")
    val payload = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("instructions", systemPrompt)
        put("tool_choice", "required")
        put("tools", automationToolsForResponses())
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", userPrompt)
                                })
                                imageBase64?.let {
                                    add(buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", "data:image/jpeg;base64,$it")
                                    })
                                }
                            }
                        )
                    }
                )
            }
        )
    }

    val thought = StringBuilder()
    var toolName: String? = null
    val toolArguments = StringBuilder()
    var loggedThoughtDelta = false
    sseClient.stream(
        request = baseRequest(provider, "${provider.baseUrl.trimEnd('/')}/responses", payload),
        onEvent = { _, type, _, data ->
            if (data == "[DONE]") return@stream SseStreamClient.StreamEventResult(done = true)
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@stream null
            when (type) {
                "response.output_text.delta" -> {
                    val delta = root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    thought.append(delta)
                    if (!loggedThoughtDelta && delta.isNotBlank()) {
                        loggedThoughtDelta = true
                        AppDebugLogStore.i(STREAM_TAG, "streamResponsesAutomation first thought delta received")
                    }
                    SseStreamClient.StreamEventResult(delta = delta)
                }

                "response.function_call_arguments.delta" -> {
                    toolArguments.append(root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    AppDebugLogStore.d(STREAM_TAG, "streamResponsesAutomation tool args length=${toolArguments.length}")
                    null
                }

                "response.output_item.added" -> {
                    val item = root["item"]?.jsonObject ?: return@stream null
                    if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                        toolName = item["name"]?.jsonPrimitive?.contentOrNull
                        AppDebugLogStore.i(STREAM_TAG, "streamResponsesAutomation tool declared name=$toolName")
                    }
                    null
                }

                else -> null
            }
        },
        onDelta = onThoughtDelta
    )
    val actionName = toolName ?: error("自动模式未调用工具")
    val args = runCatching { json.parseToJsonElement(toolArguments.toString()).jsonObject }.getOrElse {
        AppDebugLogStore.e(STREAM_TAG, "streamResponsesAutomation tool args parse failed raw=$toolArguments", it)
        error("自动模式工具参数解析失败")
    }
    AppDebugLogStore.i(STREAM_TAG, "streamResponsesAutomation completed tool=$actionName thoughtLength=${thought.length}")
    return AutomationResult(
        thought = thought.toString().trim(),
        action = validateAutomationAction(actionName, extractJsonTextField(args))
    )
}

internal suspend fun AiGateway.streamAnthropic(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onDelta: (String) -> Unit
): String {
    AppDebugLogStore.i(STREAM_TAG, "streamAnthropic request model=$model")
    val payload = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("max_tokens", 4096)
        put("system", systemPrompt)
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", userPrompt)
                                })
                                imageBase64?.let {
                                    add(buildJsonObject {
                                        put("type", "image")
                                        put("source", buildJsonObject {
                                            put("type", "base64")
                                            put("media_type", "image/jpeg")
                                            put("data", it)
                                        })
                                    })
                                }
                            }
                        )
                    }
                )
            }
        )
    }

    return sseClient.stream(
        request = baseRequest(
            provider,
            "${provider.baseUrl.trimEnd('/')}/messages",
            payload,
            headers = mapOf("anthropic-version" to "2023-06-01")
        ),
        onEvent = { _, type, _, data ->
            if (data == "[DONE]") {
                SseStreamClient.StreamEventResult(done = true)
            } else {
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: null
                SseStreamClient.StreamEventResult(delta = when (type) {
                    "content_block_delta" -> root?.get("delta")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    else -> null
                })
            }
        },
        onDelta = onDelta
    )
}

internal suspend fun AiGateway.streamAnthropicAutomation(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onThoughtDelta: (String) -> Unit
): AutomationResult {
    AppDebugLogStore.i(STREAM_TAG, "streamAnthropicAutomation request model=$model")
    val payload = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("max_tokens", 4096)
        put("system", systemPrompt)
        put("tool_choice", buildJsonObject { put("type", "any") })
        put("tools", automationToolsForAnthropic())
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", userPrompt)
                                })
                                imageBase64?.let {
                                    add(buildJsonObject {
                                        put("type", "image")
                                        put("source", buildJsonObject {
                                            put("type", "base64")
                                            put("media_type", "image/jpeg")
                                            put("data", it)
                                        })
                                    })
                                }
                            }
                        )
                    }
                )
            }
        )
    }

    val thought = StringBuilder()
    var toolName: String? = null
    val toolInput = StringBuilder()
    var loggedThoughtDelta = false
    sseClient.stream(
        request = baseRequest(
            provider,
            "${provider.baseUrl.trimEnd('/')}/messages",
            payload,
            headers = mapOf("anthropic-version" to "2023-06-01")
        ),
        onEvent = { _, type, _, data ->
            if (data == "[DONE]") return@stream SseStreamClient.StreamEventResult(done = true)
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@stream null
            when (type) {
                "content_block_start" -> {
                    val block = root["content_block"]?.jsonObject ?: return@stream null
                    if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                        toolName = block["name"]?.jsonPrimitive?.contentOrNull
                        AppDebugLogStore.i(STREAM_TAG, "streamAnthropicAutomation tool declared name=$toolName")
                    }
                    null
                }

                "content_block_delta" -> {
                    val delta = root["delta"]?.jsonObject ?: return@stream null
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            thought.append(text)
                            if (!loggedThoughtDelta && text.isNotBlank()) {
                                loggedThoughtDelta = true
                                AppDebugLogStore.i(STREAM_TAG, "streamAnthropicAutomation first thought delta received")
                            }
                            SseStreamClient.StreamEventResult(delta = text)
                        }

                        "input_json_delta" -> {
                            toolInput.append(delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            AppDebugLogStore.d(STREAM_TAG, "streamAnthropicAutomation tool args length=${toolInput.length}")
                            null
                        }

                        else -> null
                    }
                }

                else -> null
            }
        },
        onDelta = onThoughtDelta
    )
    val actionName = toolName ?: error("自动模式未调用工具")
    val args = runCatching { json.parseToJsonElement(toolInput.toString()).jsonObject }.getOrElse {
        AppDebugLogStore.e(STREAM_TAG, "streamAnthropicAutomation tool args parse failed raw=$toolInput", it)
        error("自动模式工具参数解析失败")
    }
    AppDebugLogStore.i(STREAM_TAG, "streamAnthropicAutomation completed tool=$actionName thoughtLength=${thought.length}")
    return AutomationResult(
        thought = thought.toString().trim(),
        action = validateAutomationAction(actionName, extractJsonTextField(args))
    )
}

internal suspend fun AiGateway.streamGoogle(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onDelta: (String) -> Unit
): String {
    AppDebugLogStore.i(STREAM_TAG, "streamGoogle request model=$model")
    val payload = buildJsonObject {
        put(
            "systemInstruction",
            buildJsonObject {
                put(
                    "parts",
                    buildJsonArray {
                        add(buildJsonObject {
                            put("text", systemPrompt)
                        })
                    }
                )
            }
        )
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("text", userPrompt)
                                })
                                imageBase64?.let {
                                    add(buildJsonObject {
                                        put("inlineData", buildJsonObject {
                                            put("mimeType", "image/jpeg")
                                            put("data", it)
                                        })
                                    })
                                }
                            }
                        )
                    }
                )
            }
        )
    }

    val url = "${provider.baseUrl.trimEnd('/')}/models/$model:streamGenerateContent?alt=sse"
    return sseClient.stream(
        request = baseRequest(
            provider,
            url,
            payload,
            googleApiKeyHeader = true
        ),
        onEvent = { _, _, _, data ->
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: null
            val candidates = root?.get("candidates")?.jsonArray ?: null
            SseStreamClient.StreamEventResult(
                delta = candidates?.firstOrNull()
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
                    ?.get("parts")
                    ?.jsonArray
                    ?.joinToString(separator = "") { part ->
                        part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
            )
        },
        onDelta = onDelta
    )
}

internal suspend fun AiGateway.streamGoogleAutomation(
    provider: ModelProviderConfig,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    imageBase64: String?,
    onThoughtDelta: (String) -> Unit
): AutomationResult {
    AppDebugLogStore.i(STREAM_TAG, "streamGoogleAutomation request model=$model")
    val payload = buildJsonObject {
        put(
            "systemInstruction",
            buildJsonObject {
                put(
                    "parts",
                    buildJsonArray {
                        add(buildJsonObject { put("text", systemPrompt) })
                    }
                )
            }
        )
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", userPrompt) })
                                imageBase64?.let {
                                    add(buildJsonObject {
                                        put("inlineData", buildJsonObject {
                                            put("mimeType", "image/jpeg")
                                            put("data", it)
                                        })
                                    })
                                }
                            }
                        )
                    }
                )
            }
        )
        put("tools", buildJsonArray { add(automationToolsForGoogle()) })
        put(
            "toolConfig",
            buildJsonObject {
                put(
                    "functionCallingConfig",
                    buildJsonObject {
                        put("mode", "ANY")
                    }
                )
            }
        )
    }

    val url = "${provider.baseUrl.trimEnd('/')}/models/$model:streamGenerateContent?alt=sse"
    val thought = StringBuilder()
    var toolName: String? = null
    var toolText: String? = null
    var loggedThoughtDelta = false
    sseClient.stream(
        request = baseRequest(provider, url, payload, googleApiKeyHeader = true),
        onEvent = { _, _, _, data ->
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@stream null
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@stream null
            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            var deltaText = ""
            parts.forEach { part ->
                val obj = part.jsonObject
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { deltaText += it }
                obj["functionCall"]?.jsonObject?.let { call ->
                    toolName = call["name"]?.jsonPrimitive?.contentOrNull
                    toolText = call["args"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    AppDebugLogStore.i(STREAM_TAG, "streamGoogleAutomation tool declared name=$toolName text=$toolText")
                }
            }
            if (deltaText.isNotBlank()) {
                thought.append(deltaText)
                if (!loggedThoughtDelta) {
                    loggedThoughtDelta = true
                    AppDebugLogStore.i(STREAM_TAG, "streamGoogleAutomation first thought delta received")
                }
                SseStreamClient.StreamEventResult(delta = deltaText)
            } else {
                null
            }
        },
        onDelta = onThoughtDelta
    )
    val actionName = toolName ?: error("自动模式未调用工具")
    AppDebugLogStore.i(STREAM_TAG, "streamGoogleAutomation completed tool=$actionName thoughtLength=${thought.length}")
    return AutomationResult(
        thought = thought.toString().trim(),
        action = validateAutomationAction(actionName, toolText.orEmpty())
    )
}
