package io.github.zapretkvn.android.slots.ai

import io.github.zapretkvn.android.slots.SlotDefinition
import io.github.zapretkvn.android.slots.SlotIds

/** Everything the AI slot matches: assistant apps and the services' names. */
internal object AiSlot {
    /** The Gemini assistant runs inside the Google app, so that app belongs to the AI slot (as in the app picker). */
    val packages: Set<String> = setOf(
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
        "ai.perplexity.app.android",
        "com.microsoft.copilot",
        "com.deepseek.chat",
        "ai.x.grok",
    )

    val domains: Set<String> = setOf(
        "openai.com",
        "chatgpt.com",
        "oaistatic.com",
        "oaiusercontent.com",
        "anthropic.com",
        "claude.ai",
        "gemini.google.com",
        "generativelanguage.googleapis.com",
        "perplexity.ai",
        "deepseek.com",
        "x.ai",
        "grok.com",
        "copilot.microsoft.com",
    )

    val definition: SlotDefinition
        get() = SlotDefinition(SlotIds.AI, "AI", packages = packages, domainSuffixes = domains)
}
