package com.alalay.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Contract `alalay.action.v1` per spec 07-Agent-Specs §2–§3.
 * The model proposes exactly one of the 8 tools; SafetyGate disposes (D-1).
 * Internal-only tool names (confirm_send, emergency_stop, memory_write)
 * are hard-rejected and can never be constructed here.
 */

enum class Tool(val wireName: String) {
    OPEN_APP("open_app"),
    CLICK("click"),
    READ_TEXT("read_text"),
    SCROLL("scroll"),
    GO_BACK("go_back"),
    DRAFT_REPLY("draft_reply"),
    ASK_USER("ask_user"),
    FINISH("finish");

    companion object {
        private val byName = entries.associateBy { it.wireName }
        fun fromWire(name: String): Tool? = byName[name]

        /** Names that exist conceptually but are forbidden for the model (07-§2). */
        val INTERNAL_ONLY = setOf("confirm_send", "emergency_stop", "memory_write")
    }
}

enum class ScrollDirection(val wire: String) {
    UP("up"), DOWN("down");

    companion object {
        fun fromWire(s: String): ScrollDirection? = entries.firstOrNull { it.wire == s.lowercase() }
    }
}

sealed interface ActionArgs {
    data class OpenApp(val app: String) : ActionArgs
    data class Click(val target: String?, val x: Int?, val y: Int?) : ActionArgs
    data object ReadText : ActionArgs
    data class Scroll(val direction: ScrollDirection) : ActionArgs
    data object GoBack : ActionArgs
    data class DraftReply(val text: String) : ActionArgs
    data class AskUser(val question: String, val options: List<String>) : ActionArgs
    data class Finish(val summary: String) : ActionArgs
}

data class ActionV1(
    val tool: Tool,
    val args: ActionArgs,
    val say: String,
    val confidence: Double,
)

enum class ParseRejection {
    NOT_JSON,
    BAD_VERSION,
    INTERNAL_TOOL,
    UNKNOWN_TOOL,
    BAD_ARGS,
}

sealed interface ParseResult {
    data class Ok(val action: ActionV1) : ParseResult
    data class Rejected(val reason: ParseRejection, val detail: String) : ParseResult
}

object ActionContract {
    const val VERSION = "alalay.action.v1"
    const val MAX_SAY = 120
    const val MAX_CLICK_TARGET = 50
    const val MAX_DRAFT_TEXT = 500
    const val MAX_QUESTION = 120
    const val MAX_OPTIONS = 4
    const val MAX_OPTION_LEN = 40
    const val MAX_SUMMARY = 200

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Strict parse per 07-§3/§5.1: no trailing prose, exact version gate,
     * known tool, typed required args. Unknown arg keys are stripped.
     */
    fun parse(raw: String): ParseResult {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return ParseResult.Rejected(ParseRejection.NOT_JSON, "reply is not a lone JSON object")
        }
        val obj = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            return ParseResult.Rejected(ParseRejection.NOT_JSON, "malformed JSON")
        }
        return parseObject(obj)
    }

    fun parseObject(obj: JsonObject): ParseResult {
        val v = obj["v"]?.jsonPrimitive?.content
        if (v != VERSION) {
            return ParseResult.Rejected(ParseRejection.BAD_VERSION, "v must be \"$VERSION\"")
        }
        val toolName = obj["tool"]?.jsonPrimitive?.content
            ?: return ParseResult.Rejected(ParseRejection.BAD_ARGS, "missing tool")
        if (toolName in Tool.INTERNAL_ONLY) {
            return ParseResult.Rejected(ParseRejection.INTERNAL_TOOL, "tool \"$toolName\" is internal-only")
        }
        val tool = Tool.fromWire(toolName)
            ?: return ParseResult.Rejected(ParseRejection.UNKNOWN_TOOL, "unknown tool \"$toolName\"")

        val argsObj = obj["args"] as? JsonObject
            ?: return ParseResult.Rejected(ParseRejection.BAD_ARGS, "missing args")
        val say = obj["say"]?.jsonPrimitive?.content?.take(MAX_SAY) ?: ""
        val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull
            ?: return ParseResult.Rejected(ParseRejection.BAD_ARGS, "missing confidence")

        val args = when (tool) {
            Tool.OPEN_APP -> argsObj.str("app")
                ?.let { ActionArgs.OpenApp(it) }
                ?: return ParseResult.Rejected(ParseRejection.BAD_ARGS, "open_app needs app")

            Tool.CLICK -> {
                val target = argsObj.str("target")
                val x = argsObj["x"]?.jsonPrimitive?.intOrNull
                val y = argsObj["y"]?.jsonPrimitive?.intOrNull
                when {
                    target != null && target.length <= MAX_CLICK_TARGET ->
                        ActionArgs.Click(target, null, null)
                    target != null ->
                        return ParseResult.Rejected(ParseRejection.BAD_ARGS, "click target over $MAX_CLICK_TARGET chars")
                    x != null && y != null -> ActionArgs.Click(null, x, y)
                    else -> return ParseResult.Rejected(ParseRejection.BAD_ARGS, "click needs target or x,y")
                }
            }

            Tool.READ_TEXT -> ActionArgs.ReadText

            Tool.SCROLL -> argsObj.str("direction")
                ?.let(ScrollDirection::fromWire)
                ?.let { ActionArgs.Scroll(it) }
                ?: return ParseResult.Rejected(ParseRejection.BAD_ARGS, "scroll needs direction up|down")

            Tool.GO_BACK -> ActionArgs.GoBack

            Tool.DRAFT_REPLY -> {
                val text = argsObj.str("text")
                    ?: return ParseResult.Rejected(ParseRejection.BAD_ARGS, "draft_reply needs text")
                if (text.length > MAX_DRAFT_TEXT) {
                    return ParseResult.Rejected(ParseRejection.BAD_ARGS, "draft text over $MAX_DRAFT_TEXT chars")
                }
                ActionArgs.DraftReply(text)
            }

            Tool.ASK_USER -> {
                val q = argsObj.str("question")
                    ?: return ParseResult.Rejected(ParseRejection.BAD_ARGS, "ask_user needs question")
                if (q.length > MAX_QUESTION) {
                    return ParseResult.Rejected(ParseRejection.BAD_ARGS, "question over $MAX_QUESTION chars")
                }
                val opts = try {
                    (argsObj["options"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }
                } catch (e: Exception) {
                    return ParseResult.Rejected(ParseRejection.BAD_ARGS, "options must be strings")
                }
                if (opts.size > MAX_OPTIONS || opts.any { it.length > MAX_OPTION_LEN }) {
                    return ParseResult.Rejected(ParseRejection.BAD_ARGS, "options out of bounds")
                }
                ActionArgs.AskUser(q, opts)
            }

            Tool.FINISH -> {
                val s = argsObj.str("summary") ?: ""
                ActionArgs.Finish(s.take(MAX_SUMMARY))
            }
        }

        return ParseResult.Ok(ActionV1(tool, args, say, confidence))
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            if (it.isString) it.content else null
        }
}
