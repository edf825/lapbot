package com.example.lapbot.service

/** Small deterministic FIFO; enqueueing never replaces the message currently playing. */
internal class PlaybackMessageQueue<T> {
  private val waiting = ArrayDeque<T>()
  var active: T? = null
    private set

  fun enqueue(message: T) {
    waiting.addLast(message)
  }

  fun startNext(): T? {
    if (active != null) return active
    return waiting.removeFirstOrNull()?.also { active = it }
  }

  fun completeActive(): T? = active.also { active = null }

  fun clear() {
    waiting.clear()
    active = null
  }

  val isEmpty: Boolean
    get() = waiting.isEmpty() && active == null
}
