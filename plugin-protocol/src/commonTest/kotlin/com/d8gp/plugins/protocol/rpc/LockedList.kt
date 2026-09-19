package com.d8gp.plugins.protocol.rpc

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// Common stand-in for CopyOnWriteArrayList: peer callbacks append from
// Dispatchers.Default threads while the test polls.
class LockedList<T> : AbstractList<T>() {
    private val lock = SynchronizedObject()
    private val items = mutableListOf<T>()

    operator fun plusAssign(item: T) {
        synchronized(lock) { items.add(item) }
    }

    override val size: Int get() = synchronized(lock) { items.size }

    override fun get(index: Int): T = synchronized(lock) { items[index] }
}
