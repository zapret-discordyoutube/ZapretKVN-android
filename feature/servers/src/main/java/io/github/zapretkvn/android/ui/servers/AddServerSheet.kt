package io.github.zapretkvn.android.ui.servers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.core.designsystem.R as DesignR
import io.github.zapretkvn.core.designsystem.Spacing

/**
 * Карточка протокола на листе «Добавить сервер» (владелец, 2026-11-27: «у каждого протокола своя карточка с логотипом,
 * нажал — предлагает вставить ссылку»). Логотип — официальный SVG проекта, где он есть и лицензия позволяет; у остальных
 * монограмма в том же круге (замена на SVG позже не меняет ничего вокруг).
 */
@Immutable
data class ProtocolCardSpec(
    val id: String,
    val title: String,
    val hint: String,
    /** Ссылка обязана начинаться с одной из схем; пусто — карточка не про ссылку (подписка). */
    val schemes: List<String>,
    @DrawableRes val logo: Int?,
    val monogram: String,
    /** Вставляется текст конфигурации (WireGuard/AmneziaWG `.conf`), а не ссылка. */
    val config: Boolean = false,
)

object ProtocolCards {
    const val SUBSCRIPTION_ID = "subscription"

    val all: List<ProtocolCardSpec> = listOf(
        ProtocolCardSpec(SUBSCRIPTION_ID, "Подписка", "Адрес https://…", emptyList(), null, "URL"),
        ProtocolCardSpec("vless", "VLESS", "vless://…", listOf("vless://"), DesignR.drawable.ic_proto_xray, "VL"),
        ProtocolCardSpec("vmess", "VMess", "vmess://…", listOf("vmess://"), null, "VM"),
        ProtocolCardSpec("trojan", "Trojan", "trojan://…", listOf("trojan://"), null, "TR"),
        ProtocolCardSpec("shadowsocks", "Shadowsocks", "ss://…", listOf("ss://"), null, "SS"),
        ProtocolCardSpec(
            "hysteria2", "Hysteria 2", "hysteria2://… или hy2://…",
            listOf("hysteria2://", "hy2://"), DesignR.drawable.ic_proto_hysteria, "HY",
        ),
        ProtocolCardSpec("tuic", "TUIC", "tuic://…", listOf("tuic://"), null, "TU"),
        ProtocolCardSpec("naive", "NaiveProxy", "naive+https://…", listOf("naive+https://"), null, "NP"),
        ProtocolCardSpec(
            "wireguard", "WireGuard / AmneziaWG", "Текст файла .conf", emptyList(),
            DesignR.drawable.ic_proto_wireguard, "WG", config = true,
        ),
    )

    /** Подходит ли вставленный текст этой карточке: хотя бы одна строка с её схемой (или `[Interface]` у конфигурации). */
    fun accepts(card: ProtocolCardSpec, text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (card.config) return value.contains("[Interface]", ignoreCase = true)
        return value.lineSequence().any { line ->
            val start = line.trim()
            card.schemes.any { start.startsWith(it, ignoreCase = true) }
        }
    }

    fun mismatchMessage(card: ProtocolCardSpec): String = if (card.config) {
        "Нужен текст конфигурации с разделом [Interface]."
    } else {
        "Ссылка должна начинаться с ${card.schemes.joinToString(" или ")}"
    }
}

/**
 * Лист «Добавить сервер»: быстрые источники сверху, ниже сетка протоколов. Нажатие на протокол открывает диалог вставки
 * ссылки именно этого протокола. Один и тот же лист открывает и «Серверы» (кнопка «+» всегда на экране), и «Профили».
 */
@Composable
fun AddServerSheet(
    enabled: Boolean,
    importer: ProfileImporter,
    onLink: (String) -> Unit,
    onChosen: () -> Unit,
) {
    var asking by remember { mutableStateOf<ProtocolCardSpec?>(null) }
    Column(
        modifier = Modifier.navigationBarsPadding().padding(bottom = Spacing.l).testTag("add-server-sheet"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            "Добавить сервер",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            QuickSource("Из буфера", enabled) { onChosen(); importer.pasteClipboard() }
            QuickSource("QR", enabled) { onChosen(); importer.askQr() }
            QuickSource("Файл", enabled) { onChosen(); importer.pickFile() }
        }
        Text(
            "Или выберите протокол",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            items(ProtocolCards.all, key = { it.id }) { card ->
                ProtocolCard(card, enabled) {
                    if (card.id == ProtocolCards.SUBSCRIPTION_ID) {
                        onChosen()
                        importer.askUrl()
                    } else {
                        asking = card
                    }
                }
            }
        }
    }
    asking?.let { card ->
        LinkPasteDialog(
            card = card,
            onDismiss = { asking = null },
            onSubmit = { text ->
                asking = null
                onChosen()
                onLink(text)
            },
        )
    }
}

@Composable
private fun QuickSource(title: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(title) })
}

@Composable
private fun ProtocolCard(card: ProtocolCardSpec, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("protocol-card-${card.id}"),
    ) {
        Column(
            modifier = Modifier.padding(vertical = Spacing.m, horizontal = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            ProtocolLogo(card)
            Text(
                card.title,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** Логотип в светлом круге: официальные знаки чёрные, на тёмной теме без подложки их не видно. */
@Composable
private fun ProtocolLogo(card: ProtocolCardSpec) {
    val logo = card.logo
    if (logo != null) {
        // Most official marks are wordmarks (Hysteria, WireGuard): a wide light tile lets them use the whole card width.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                card.monogram,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun LinkPasteDialog(card: ProtocolCardSpec, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val valid = ProtocolCards.accepts(card, text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(card.hint) },
                    minLines = if (card.config) 5 else 3,
                    isError = text.isNotBlank() && !valid,
                    supportingText = {
                        if (text.isNotBlank() && !valid) Text(ProtocolCards.mismatchMessage(card))
                    },
                    modifier = Modifier.fillMaxWidth().testTag("protocol-link-field"),
                )
                TextButton(
                    onClick = { clipboard.getText()?.text?.let { text = it } },
                    modifier = Modifier.testTag("protocol-paste"),
                ) { Text("Вставить из буфера") }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(text.trim()) }, enabled = valid, modifier = Modifier.testTag("protocol-add")) {
                Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
