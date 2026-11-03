package com.tulipskun.aixodia.term

import com.tulipskun.aixodia.data.remote.TerminalSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The terminal sockets this process has open.
 *
 * A foreground service keeps the process alive while the user switches apps,
 * so the normal window lifecycle is allowed to leave the sockets attached. The
 * one case that must still close them is the user removing the task from
 * recents: the service is the only component Android tells about that, and it
 * closes this registry before stopping itself.
 */
object TerminalConnections {
    private val sockets = CopyOnWriteArrayList<TerminalSocket>()

    fun attach(socket: TerminalSocket) {
        if (!sockets.contains(socket)) sockets.add(socket)
    }

    fun detach(socket: TerminalSocket) {
        sockets.remove(socket)
    }

    fun closeAll() {
        sockets.toList().forEach { socket ->
            runCatching { socket.close() }
        }
        sockets.clear()
    }
}
