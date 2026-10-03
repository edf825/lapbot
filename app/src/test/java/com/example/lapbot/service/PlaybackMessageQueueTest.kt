package com.example.lapbot.service

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class PlaybackMessageQueueTest {
  @Test
  fun `sector lap and command responses play in arrival order without replacing active speech`() {
    val queue = PlaybackMessageQueue<String>()
    queue.enqueue("sector")
    queue.enqueue("lap")

    assertEquals("sector", queue.startNext())
    queue.enqueue("voice command response")
    assertEquals("sector", queue.startNext())

    assertEquals("sector", queue.completeActive())
    assertEquals("lap", queue.startNext())
    assertEquals("lap", queue.completeActive())
    assertEquals("voice command response", queue.startNext())
    assertEquals("voice command response", queue.completeActive())
    assertEquals(true, queue.isEmpty)
  }

  @Test
  fun `clear removes active and waiting messages for explicit disconnect`() {
    val queue = PlaybackMessageQueue<String>()
    queue.enqueue("lap")
    queue.enqueue("gaps")
    queue.startNext()

    queue.clear()

    assertNull(queue.startNext())
    assertEquals(true, queue.isEmpty)
  }
}
