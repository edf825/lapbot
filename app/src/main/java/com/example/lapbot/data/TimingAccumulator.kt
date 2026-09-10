package com.example.lapbot.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class TimingAccumulator {
  private val drivers = mutableMapOf<String, DriverState>()
  var sequence: Long? = null
    private set

  fun replace(snapshot: JsonObject, preserveIfEmpty: Boolean = false): List<TimingRow> {
    val competitors = snapshot.array("Competitors")
    if (preserveIfEmpty && competitors.isEmpty() && drivers.isNotEmpty()) return sortedRows()
    drivers.clear()
    sequence = snapshot.long("Sequence")
    competitors.forEach { element ->
      val driverJson = element.jsonObject
      val id = driverJson.text("CompetitorId") ?: return@forEach
      val driver =
        DriverState(
          id = id,
          number = driverJson.text("CompetitorNumber").orEmpty(),
          name = driverJson.text("DriverName") ?: driverJson.text("CompetitorName").orEmpty(),
          position = driverJson.int("Position"),
          gapToLeaderMs = driverJson.long("Behind") ?: driverJson.long("GapToLeader"),
          gapToAheadMs = driverJson.long("Gap") ?: driverJson.long("GapToAhead"),
        )
      driverJson.array("Laps").forEach { lapElement ->
        val lapJson = lapElement.jsonObject
        val number = lapJson.int("LapNumber") ?: return@forEach
        driver.laps[number] = lapJson.toLap(number)
      }
      drivers[id] = driver
    }
    return sortedRows()
  }

  fun apply(patch: JsonObject): List<JsonObject> {
    sequence = patch.long("Sequence") ?: sequence
    val events = mutableListOf<JsonObject>()
    patch.array("Competitors").forEach { element ->
      val driverPatch = element.jsonObject
      val id = driverPatch.text("CompetitorId") ?: return@forEach
      val driver = drivers.getOrPut(id) { DriverState(id) }
      driver.number = driverPatch.text("CompetitorNumber") ?: driver.number
      driver.name = driverPatch.text("DriverName") ?: driverPatch.text("CompetitorName") ?: driver.name
      driver.position = driverPatch.int("Position") ?: driver.position
      if ("Behind" in driverPatch || "GapToLeader" in driverPatch) {
        driver.gapToLeaderMs = driverPatch.long("Behind") ?: driverPatch.long("GapToLeader")
      }
      if ("Gap" in driverPatch || "GapToAhead" in driverPatch) {
        driver.gapToAheadMs = driverPatch.long("Gap") ?: driverPatch.long("GapToAhead")
      }

      driverPatch.array("Laps").forEach lapLoop@{ lapElement ->
        val lapPatch = lapElement.jsonObject
        val lapNumber = lapPatch.int("LapNumber") ?: return@lapLoop
        val lap = driver.laps.getOrPut(lapNumber) { LapState(lapNumber) }
        val before = lap.copy()
        if ("Split1Time" in lapPatch) lap.sector1Ms = lapPatch.long("Split1Time")
        if ("Split2Time" in lapPatch) lap.sector2Ms = lapPatch.long("Split2Time")
        if ("Split3Time" in lapPatch) lap.sector3Ms = lapPatch.long("Split3Time")
        if ("LapTime" in lapPatch) lap.lapMs = lapPatch.long("LapTime")
        if (lap != before) events += driver.toLapUpdate(lap)
      }
    }
    return events
  }

  fun sortedRows(): List<TimingRow> =
    drivers.values
      .map { it.toTimingRow() }
      .sortedWith(compareBy<TimingRow> { it.position ?: Int.MAX_VALUE }.thenBy { it.number })

  private fun DriverState.toTimingRow(): TimingRow {
    val latestLap = laps.maxByOrNull { it.key }?.value
    val completedLaps = laps.values.filter(LapState::isComplete)
    val recentCompletedLap = completedLaps.maxByOrNull(LapState::number)
    val bestLap = completedLaps.minByOrNull { it.lapMs ?: Long.MAX_VALUE }
    val bestSector1 = completedLaps.mapNotNull(LapState::sector1Ms).minOrNull()
    val bestSector2 = completedLaps.mapNotNull(LapState::sector2Ms).minOrNull()
    val bestSector3 = completedLaps.mapNotNull(LapState::sector3Ms).minOrNull()
    return TimingRow(
      id = id,
      number = number,
      name = name,
      position = position,
      gapToLeaderMs = if (position == 1) 0L else gapToLeaderMs,
      gapToAheadMs = if (position == 1) 0L else gapToAheadMs,
      gapRecordedAtLap = latestLap?.number?.takeIf { position == 1 || gapToLeaderMs != null || gapToAheadMs != null },
      lap = latestLap?.number,
      sector1Ms = latestLap?.sector1Ms,
      sector2Ms = latestLap?.sector2Ms,
      sector3Ms = latestLap?.sector3Ms,
      lapMs = latestLap?.lapMs,
      recentCompletedLap = recentCompletedLap?.number,
      recentCompletedLapMs = recentCompletedLap?.lapMs,
      recentCompletedSector1Ms = recentCompletedLap?.sector1Ms,
      recentCompletedSector2Ms = recentCompletedLap?.sector2Ms,
      recentCompletedSector3Ms = recentCompletedLap?.sector3Ms,
      bestLap = bestLap?.number,
      bestLapMs = bestLap?.lapMs,
      bestLapSector1Ms = bestLap?.sector1Ms,
      bestLapSector2Ms = bestLap?.sector2Ms,
      bestLapSector3Ms = bestLap?.sector3Ms,
      bestSector1Ms = bestSector1,
      bestSector2Ms = bestSector2,
      bestSector3Ms = bestSector3,
      theoreticalBestMs =
        if (bestSector1 != null && bestSector2 != null && bestSector3 != null) {
          bestSector1 + bestSector2 + bestSector3
        } else {
          null
        },
      lapHistory =
        completedLaps
          .sortedByDescending(LapState::number)
          .map { lap ->
            LapHistoryEntry(
              lap = lap.number,
              lapMs = requireNotNull(lap.lapMs),
              sector1Ms = lap.sector1Ms,
              sector2Ms = lap.sector2Ms,
              sector3Ms = lap.sector3Ms,
            )
          },
      lapTimeline =
        laps.values
          .sortedByDescending(LapState::number)
          .map { lap ->
            LapTimelineEntry(
              lap = lap.number,
              lapMs = lap.lapMs,
              sector1Ms = lap.sector1Ms,
              sector2Ms = lap.sector2Ms,
              sector3Ms = lap.sector3Ms,
            )
          },
    )
  }

  private fun DriverState.toLapUpdate(lap: LapState): JsonObject =
    buildJsonObject {
      put("type", "lap_update")
      put("driver_id", id.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(id))
      put("number", number)
      put("name", name)
      put("lap", lap.number)
      putNullable("lap_ms", lap.lapMs)
      putNullable("sector_1_ms", lap.sector1Ms)
      putNullable("sector_2_ms", lap.sector2Ms)
      putNullable("sector_3_ms", lap.sector3Ms)
      put("complete", lap.lapMs != null)
    }
}

private data class DriverState(
  val id: String,
  var number: String = "",
  var name: String = "",
  var position: Int? = null,
  var gapToLeaderMs: Long? = null,
  var gapToAheadMs: Long? = null,
  val laps: MutableMap<Int, LapState> = mutableMapOf(),
)

private data class LapState(
  val number: Int,
  var sector1Ms: Long? = null,
  var sector2Ms: Long? = null,
  var sector3Ms: Long? = null,
  var lapMs: Long? = null,
) {
  val isComplete: Boolean
    get() = lapMs != null && sector1Ms != null && sector2Ms != null && sector3Ms != null
}

private fun JsonObject.toLap(number: Int) =
  LapState(
    number = number,
    sector1Ms = long("Split1Time"),
    sector2Ms = long("Split2Time"),
    sector3Ms = long("Split3Time"),
    lapMs = long("LapTime"),
  )

private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.text(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeUnless { it == "null" }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Long?) {
  put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}
