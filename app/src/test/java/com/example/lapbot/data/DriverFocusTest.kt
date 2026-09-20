package com.example.lapbot.data

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class DriverFocusTest {
  @Test
  fun `name fragment identifies a driver case insensitively`() {
    val rows =
      listOf(
        TimingRow(id = "12", number = "12", name = "Jonny Reeves"),
        TimingRow(id = "27", number = "27", name = "Alex Morgan"),
      )

    assertEquals("12", findDriverByNameFragment(rows, "  REEVES ")?.number)
  }

  @Test
  fun `ambiguous name fragment does not select a kart`() {
    val rows =
      listOf(
        TimingRow(id = "12", number = "12", name = "Jonny Reeves"),
        TimingRow(id = "27", number = "27", name = "Sam Reeves"),
      )

    assertNull(findDriverByNameFragment(rows, "Reeves"))
  }

  @Test
  fun `driver name matching ignores rows without a kart assignment`() {
    val rows = listOf(TimingRow(id = "driver", name = "Jonny Reeves"))

    assertNull(findDriverByNameFragment(rows, "Reeves"))
  }
}
