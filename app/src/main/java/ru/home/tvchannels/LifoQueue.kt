package ru.home.tvchannels

import java.util.concurrent.LinkedBlockingDeque

/** Очередь задач, в которой новая задача встаёт первой. */
class LifoQueue : LinkedBlockingDeque<Runnable>() {
    override fun offer(e: Runnable) = offerFirst(e)
    override fun add(e: Runnable) = offerFirst(e)
    override fun put(e: Runnable) = putFirst(e)
}
