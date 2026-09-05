package com.example.lapbot.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic timing replay based on Buckmore session 837888. Total lap times
 * are from the public result; sector arrival is synthesized to exercise sparse
 * live patches because the finished-session page does not expose every split.
 */
internal class DemoTimingReplay {
  private val accumulator = TimingAccumulator()
  private var sequence = 1L
  private var replayLap = INITIAL_LAPS + 1
  private var phase = DemoPhase.Sector1

  fun initialRows(): List<TimingRow> = accumulator.replace(initialSnapshot())

  fun initialDelayMs(): Long = sectorsFor(drivers.first().lapTime(replayLap), replayLap).first / REPLAY_SPEED

  fun advance(): DemoFrame {
    val activeLap = replayLap
    val activePhase = phase
    val patch =
      buildJsonObject {
        put("Sequence", sequence++)
        put(
          "Competitors",
          buildJsonArray {
            drivers.forEach { driver ->
              val lapTime = driver.lapTime(activeLap)
              val sectors = sectorsFor(lapTime, activeLap)
              add(
                buildJsonObject {
                  put("CompetitorId", driver.id)
                  put("CompetitorNumber", driver.number)
                  put("DriverName", driver.name)
                  put("Position", driver.position)
                  put(
                    "Laps",
                    buildJsonArray {
                      add(
                        buildJsonObject {
                          put("LapNumber", activeLap)
                          when (activePhase) {
                            DemoPhase.Sector1 -> put("Split1Time", sectors.first)
                            DemoPhase.Sector2 -> put("Split2Time", sectors.second)
                            DemoPhase.LapTime -> put("LapTime", lapTime)
                            DemoPhase.Sector3 -> put("Split3Time", sectors.third)
                          }
                        },
                      )
                    },
                  )
                },
              )
            }
          },
        )
      }
    val records = accumulator.apply(patch).map(JsonObject::toString)
    val referenceSectors = sectorsFor(drivers.first().lapTime(activeLap), activeLap)
    val nextDelayMs =
      when (activePhase) {
        DemoPhase.Sector1 -> referenceSectors.second / REPLAY_SPEED
        DemoPhase.Sector2 -> referenceSectors.third / REPLAY_SPEED
        DemoPhase.LapTime -> LAP_COMPLETION_PATCH_GAP_MS
        DemoPhase.Sector3 -> sectorsFor(drivers.first().lapTime(activeLap + 1), activeLap + 1).first / REPLAY_SPEED
      }
    phase = activePhase.next()
    if (activePhase == DemoPhase.Sector3) replayLap += 1
    return DemoFrame(accumulator.sortedRows(), records, activeLap, activePhase, nextDelayMs)
  }

  private fun initialSnapshot(): JsonObject =
    buildJsonObject {
      put("Sequence", 0)
      put(
        "Competitors",
        buildJsonArray {
          drivers.forEach { driver ->
            add(
              buildJsonObject {
                put("CompetitorId", driver.id)
                put("CompetitorNumber", driver.number)
                put("DriverName", driver.name)
                put("Position", driver.position)
                put(
                  "Laps",
                  buildJsonArray {
                    (1..INITIAL_LAPS).forEach { lap ->
                      val lapTime = driver.lapTime(lap)
                      val sectors = sectorsFor(lapTime, lap)
                      add(
                        buildJsonObject {
                          put("LapNumber", lap)
                          put("Split1Time", sectors.first)
                          put("Split2Time", sectors.second)
                          put("Split3Time", sectors.third)
                          put("LapTime", lapTime)
                        },
                      )
                    }
                    add(buildJsonObject { put("LapNumber", replayLap) })
                  },
                )
              },
            )
          }
        },
      )
    }

  private fun sectorsFor(lapTime: Long, lap: Int): DemoSectors {
    val sector1 = (lapTime * 407L) / 1_000L
    // Give the replay a deliberate, repeatable S2 opportunity so Race Engineer can
    // demonstrate objective selection from the very first snapshot.
    val sector2 =
      when (lap) {
        in 6..8 -> 15_800L
        9 -> 15_600L
        10 -> 15_580L
        in 11..15 -> 15_600L
        else -> 15_500L
      }
    return DemoSectors(sector1, sector2, lapTime - sector1 - sector2)
  }

  private companion object {
    const val INITIAL_LAPS = 8
    const val REPLAY_SPEED = 2L
    const val LAP_COMPLETION_PATCH_GAP_MS = 250L

    val drivers =
      listOf(
        DemoDriver(
          id = "demo-5",
          number = "5",
          name = "John Reeves",
          position = 4,
          lapTimes =
            listOf(
              55_242, 53_977, 53_182, 52_485, 52_322, 52_577, 52_361, 52_481,
              52_831, 52_041, 51_938, 52_299, 51_994, 52_420, 52_272, 51_781,
              52_395, 52_713, 51_834, 51_945, 53_545, 52_484, 52_944, 116_793,
              75_306, 69_037, 64_628, 62_617, 59_924, 59_671, 55_575, 57_252,
            ),
        ),
        DemoDriver(
          id = "demo-4",
          number = "4",
          name = "Robert Seaman",
          position = 1,
          lapTimes = listOf(54_591, 53_007, 52_553, 51_569, 52_811, 52_787, 51_091, 51_163, 52_511, 51_040),
        ),
        DemoDriver(
          id = "demo-1",
          number = "1",
          name = "Tom Searles",
          position = 2,
          lapTimes = listOf(55_388, 52_576, 52_588, 51_885, 52_597, 51_796, 51_867, 52_023, 52_200, 51_393),
        ),
        DemoDriver(
          id = "demo-6",
          number = "6",
          name = "Paul Hubbins",
          position = 3,
          lapTimes = listOf(55_727, 53_872, 53_812, 52_293, 53_819, 52_909, 52_438, 53_322, 52_559, 52_012),
        ),
      )
  }
}

internal data class DemoFrame(
  val rows: List<TimingRow>,
  val records: List<String>,
  val lap: Int,
  val phase: DemoPhase,
  val nextDelayMs: Long,
)

internal enum class DemoPhase {
  Sector1,
  Sector2,
  LapTime,
  Sector3;

  fun next(): DemoPhase = entries[(ordinal + 1) % entries.size]
}

private data class DemoDriver(
  val id: String,
  val number: String,
  val name: String,
  val position: Int,
  val lapTimes: List<Long>,
) {
  fun lapTime(lap: Int): Long = lapTimes[(lap - 1) % lapTimes.size]
}

private data class DemoSectors(val first: Long, val second: Long, val third: Long)
