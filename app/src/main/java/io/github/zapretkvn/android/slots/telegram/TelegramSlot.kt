package io.github.zapretkvn.android.slots.telegram

import io.github.zapretkvn.android.slots.SlotDefinition
import io.github.zapretkvn.android.slots.SlotIds

/** Everything the Telegram slot matches: clients, their forks, the names and the datacentre ranges. */
internal object TelegramSlot {
    val packages: Set<String> = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.beta",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "org.zastogram.messenger",
        "org.zastogram.messenger.beta",
        "org.zastogram.messenger.web",
        "tw.nekomimi.nekogram",
        "tw.nekomimi.nekogram.beta",
        "nekox.messenger",
        "xyz.nextalone.nagram",
        "com.radolyn.ayugram",
        "com.exteragram.messenger",
        "it.belloworld.mercurygram",
        "it.belloworld.mercurygram.beta",
        "uz.unnarsx.cherrygram",
        "org.forkgram.messenger",
        "org.forkgram.classic",
        "org.vidogram.messenger",
        "org.monogram",
    )

    /** Builds of one fork share a prefix (`org.zastogram.messenger.dev` is not in the package list above). */
    val packagePrefixes: Set<String> = setOf("org.zastogram.messenger.")

    /** Telegram datacentre ranges: a fork nobody listed is still caught by where it connects. */
    val cidrs: Set<String> = setOf(
        "91.108.4.0/22",
        "91.108.8.0/22",
        "91.108.12.0/22",
        "91.108.16.0/22",
        "91.108.20.0/22",
        "91.108.56.0/22",
        "149.154.160.0/20",
        "185.76.151.0/24",
        "2001:67c:4e8::/48",
        "2001:b28:f23d::/48",
        "2001:b28:f23f::/48",
        "2a0a:f280::/32",
    )

    val domains: Set<String> = setOf("telegram.org", "t.me", "telegram.me", "telesco.pe", "tdesktop.com", "telegra.ph")

    val definition: SlotDefinition
        get() = SlotDefinition(
            SlotIds.TELEGRAM,
            "Telegram",
            packages = packages,
            packagePrefixes = packagePrefixes,
            domainSuffixes = domains,
            cidrs = cidrs,
        )
}
