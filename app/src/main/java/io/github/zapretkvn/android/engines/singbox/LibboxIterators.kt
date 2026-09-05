package io.github.zapretkvn.android.engines.singbox

import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator

internal class ListStringIterator(values: Collection<String>) : StringIterator {
    private val items = values.toList()
    private var index = 0

    override fun len(): Int = items.size
    override fun hasNext(): Boolean = index < items.size
    override fun next(): String = items[index++]
}

internal class ListNetworkInterfaceIterator(
    values: Collection<NetworkInterface>,
) : NetworkInterfaceIterator {
    private val iterator = values.iterator()

    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): NetworkInterface = iterator.next()
}

internal fun StringIterator?.consume(): List<String> = buildList {
    val source = this@consume ?: return@buildList
    while (source.hasNext()) add(source.next())
}

internal fun RoutePrefixIterator.consume(): List<RouteSpec> = buildList {
    while (this@consume.hasNext()) {
        val prefix = this@consume.next()
        add(RouteSpec(prefix.address(), prefix.prefix()))
    }
}

internal data class RouteSpec(val address: String, val prefix: Int)
