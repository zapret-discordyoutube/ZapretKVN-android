package io.github.zapretkvn.android.slots

/**
 * Service slots (owner decision 2026-11-14, see docs/SERVICE_SLOTS.md): YouTube, Telegram, AI apps and "everything
 * else" each get their own server and their own switching logic. This file is the pure part: which slot a connection
 * belongs to ([ServiceSlotRouter]) and which server a slot uses ([SlotServerAssigner]). It has no Android types and
 * no clock, so the decisions are tested on plain values; the config builder and the runtime only execute them.
 *
 * Direct is not a slot. An app that is not selected for the VPN never reaches this code (it stays outside the TUN);
 * an app that is selected always lands in a slot, `default` when nothing else matches.
 */
internal object SlotIds {
    const val AI = "ai"
    const val YOUTUBE = "youtube"
    const val TELEGRAM = "telegram"
    const val DEFAULT = "default"
}
