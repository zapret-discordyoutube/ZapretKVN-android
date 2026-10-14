package io.github.zapretkvn.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Иконки приложения. Базовые берём из material-icons-core (уже в сборке),
 * недостающие нарисованы здесь простыми контурами — без тяжёлого
 * material-icons-extended.
 */
object ZapretIcons {
    val Home = Icons.Filled.Home
    val Settings = Icons.Filled.Settings
    val Back = Icons.AutoMirrored.Filled.ArrowBack
    val Chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val ExpandMore = Icons.Filled.KeyboardArrowDown
    val ExpandLess = Icons.Filled.KeyboardArrowUp
    val Add = Icons.Filled.Add
    val Close = Icons.Filled.Close
    val Delete = Icons.Filled.Delete
    val Edit = Icons.Filled.Edit
    val Info = Icons.Filled.Info
    val More = Icons.Filled.MoreVert
    val Refresh = Icons.Filled.Refresh
    val Search = Icons.Filled.Search
    val Share = Icons.Filled.Share
    val Star = Icons.Filled.Star
    val Warning = Icons.Filled.Warning
    val Ok = Icons.Filled.CheckCircle

    /** Кнопка питания: дуга с разрывом сверху и вертикальная черта. */
    val Power: ImageVector by lazy {
        outline("Power") {
            moveTo(12f, 3f)
            lineTo(12f, 12f)
            moveTo(7.05f, 6.05f)
            arcToRelative(7f, 7f, 0f, true, false, 9.9f, 0f)
        }
    }

    /** Две стойки сервера с индикаторами. */
    val Servers: ImageVector by lazy {
        outline("Servers") {
            roundRect(4f, 4f, 20f, 10.5f)
            roundRect(4f, 13.5f, 20f, 20f)
            moveTo(7.5f, 7.25f); lineTo(8f, 7.25f)
            moveTo(7.5f, 16.75f); lineTo(8f, 16.75f)
            moveTo(11f, 7.25f); lineTo(16.5f, 7.25f)
            moveTo(11f, 16.75f); lineTo(16.5f, 16.75f)
        }
    }

    /** Развилка: часть трафика идёт прямо, часть — в сторону. */
    val Route: ImageVector by lazy {
        outline("Route") {
            moveTo(12f, 21f)
            lineTo(12f, 12f)
            lineTo(6f, 6f)
            moveTo(12f, 12f)
            lineTo(18f, 6f)
            moveTo(6f, 10f); lineTo(6f, 6f); lineTo(10f, 6f)
            moveTo(14f, 6f); lineTo(18f, 6f); lineTo(18f, 10f)
        }
    }

    /** Ползунки настроек. */
    val Tune: ImageVector by lazy {
        outline("Tune") {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(4f, 17f); lineTo(20f, 17f)
            moveTo(9f, 4.5f); lineTo(9f, 9.5f)
            moveTo(15f, 14.5f); lineTo(15f, 19.5f)
        }
    }

    /** Молния — «быстрые». */
    val Bolt: ImageVector by lazy {
        outline("Bolt") {
            moveTo(13f, 3f)
            lineTo(5f, 13.5f)
            lineTo(11.5f, 13.5f)
            lineTo(10.5f, 21f)
            lineTo(19f, 10.5f)
            lineTo(12.5f, 10.5f)
            close()
        }
    }

    /** Глобус — сеть, IP выхода. */
    val Globe: ImageVector by lazy {
        outline("Globe") {
            moveTo(21f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
            arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
            moveTo(3.5f, 9f); lineTo(20.5f, 9f)
            moveTo(3.5f, 15f); lineTo(20.5f, 15f)
            moveTo(12f, 3f)
            curveTo(9.5f, 6f, 9.5f, 18f, 12f, 21f)
            moveTo(12f, 3f)
            curveTo(14.5f, 6f, 14.5f, 18f, 12f, 21f)
        }
    }

    /** Стрелки вниз/вверх — трафик. */
    val Traffic: ImageVector by lazy {
        outline("Traffic") {
            moveTo(8f, 4f); lineTo(8f, 20f)
            moveTo(4.5f, 16.5f); lineTo(8f, 20f); lineTo(11.5f, 16.5f)
            moveTo(16f, 20f); lineTo(16f, 4f)
            moveTo(12.5f, 7.5f); lineTo(16f, 4f); lineTo(19.5f, 7.5f)
        }
    }

    /** Сетка приложений. */
    val Apps: ImageVector by lazy {
        outline("Apps") {
            roundRect(4f, 4f, 10.5f, 10.5f)
            roundRect(13.5f, 4f, 20f, 10.5f)
            roundRect(4f, 13.5f, 10.5f, 20f)
            roundRect(13.5f, 13.5f, 20f, 20f)
        }
    }

    /** Щит — скрытие VPN. */
    val Shield: ImageVector by lazy {
        outline("Shield") {
            moveTo(12f, 3f)
            lineTo(19f, 6f)
            lineTo(19f, 11.5f)
            curveTo(19f, 16f, 16f, 19.5f, 12f, 21f)
            curveTo(8f, 19.5f, 5f, 16f, 5f, 11.5f)
            lineTo(5f, 6f)
            close()
        }
    }

    /** Волны Wi-Fi — автоматизация по сети. */
    val Wifi: ImageVector by lazy {
        outline("Wifi") {
            moveTo(3f, 9.5f)
            curveTo(8f, 4.8f, 16f, 4.8f, 21f, 9.5f)
            moveTo(6f, 12.8f)
            curveTo(9.4f, 9.8f, 14.6f, 9.8f, 18f, 12.8f)
            moveTo(9f, 16f)
            curveTo(10.8f, 14.6f, 13.2f, 14.6f, 15f, 16f)
            moveTo(12f, 19.5f); lineTo(12f, 19.6f)
        }
    }

    /** Стрелка вниз в лоток — обновления. */
    val Download: ImageVector by lazy {
        outline("Download") {
            moveTo(12f, 4f); lineTo(12f, 15f)
            moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
            moveTo(5f, 19.5f); lineTo(19f, 19.5f)
        }
    }

    /** Жук — диагностика. */
    val Bug: ImageVector by lazy {
        outline("Bug") {
            roundRect(7.5f, 8f, 16.5f, 20f)
            moveTo(9.5f, 8f)
            curveTo(9.5f, 5f, 14.5f, 5f, 14.5f, 8f)
            moveTo(4f, 12f); lineTo(7.5f, 12f)
            moveTo(16.5f, 12f); lineTo(20f, 12f)
            moveTo(4f, 17f); lineTo(7.5f, 16f)
            moveTo(16.5f, 16f); lineTo(20f, 17f)
            moveTo(12f, 11f); lineTo(12f, 17f)
        }
    }

    /** Облако с сообщением — сообщество. */
    val Chat: ImageVector by lazy {
        outline("Chat") {
            moveTo(5f, 5f)
            lineTo(19f, 5f)
            lineTo(19f, 16f)
            lineTo(10f, 16f)
            lineTo(6f, 19.5f)
            lineTo(6f, 16f)
            lineTo(5f, 16f)
            close()
        }
    }

    /** Код — исходный JSON. */
    val Code: ImageVector by lazy {
        outline("Code") {
            moveTo(8.5f, 7f); lineTo(3.5f, 12f); lineTo(8.5f, 17f)
            moveTo(15.5f, 7f); lineTo(20.5f, 12f); lineTo(15.5f, 17f)
        }
    }

    /** Палитра — оформление. */
    val Palette: ImageVector by lazy {
        outline("Palette") {
            moveTo(12f, 3f)
            curveTo(7f, 3f, 3f, 7f, 3f, 12f)
            curveTo(3f, 17f, 7f, 21f, 12f, 21f)
            curveTo(13.5f, 21f, 14f, 19.8f, 13.2f, 18.6f)
            curveTo(12.4f, 17.4f, 13.2f, 16f, 14.6f, 16f)
            lineTo(16.5f, 16f)
            curveTo(19f, 16f, 21f, 14f, 21f, 11.5f)
            curveTo(21f, 6.8f, 17f, 3f, 12f, 3f)
            close()
            moveTo(7.5f, 11.5f); lineTo(7.5f, 11.6f)
            moveTo(10.5f, 7.5f); lineTo(10.5f, 7.6f)
            moveTo(15f, 8f); lineTo(15f, 8.1f)
        }
    }

    /** Замок — заблокированные приложения. */
    val Block: ImageVector by lazy {
        outline("Block") {
            moveTo(21f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
            arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
            moveTo(5.7f, 5.7f); lineTo(18.3f, 18.3f)
        }
    }

    /** Песочные часы — ожидание. */
    val Hourglass: ImageVector by lazy {
        outline("Hourglass") {
            moveTo(6f, 3.5f); lineTo(18f, 3.5f)
            moveTo(6f, 20.5f); lineTo(18f, 20.5f)
            moveTo(7f, 3.5f)
            curveTo(7f, 9f, 17f, 9f, 17f, 3.5f)
            moveTo(7f, 20.5f)
            curveTo(7f, 15f, 17f, 15f, 17f, 20.5f)
            moveTo(9f, 7f); lineTo(15f, 17f)
        }
    }
}

private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, r: Float = 2f) {
    moveTo(left + r, top)
    lineTo(right - r, top)
    arcTo(r, r, 0f, false, true, right, top + r)
    lineTo(right, bottom - r)
    arcTo(r, r, 0f, false, true, right - r, bottom)
    lineTo(left + r, bottom)
    arcTo(r, r, 0f, false, true, left, bottom - r)
    lineTo(left, top + r)
    arcTo(r, r, 0f, false, true, left + r, top)
    close()
}

private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    ).build()
