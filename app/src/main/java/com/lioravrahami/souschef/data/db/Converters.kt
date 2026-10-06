package com.lioravrahami.souschef.data.db

import androidx.room.TypeConverter
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room converters: complex fields are stored as JSON text. */
class Converters {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @TypeConverter
    fun stepsToJson(value: List<Step>): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToSteps(value: String): List<Step> =
        runCatching { json.decodeFromString<List<Step>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun axesToJson(value: List<RatingAxis>): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToAxes(value: String): List<RatingAxis> =
        runCatching { json.decodeFromString<List<RatingAxis>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun doublesToJson(value: List<Double>): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToDoubles(value: String): List<Double> =
        runCatching { json.decodeFromString<List<Double>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun doubleMapToJson(value: Map<String, Double>): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToDoubleMap(value: String): Map<String, Double> =
        runCatching { json.decodeFromString<Map<String, Double>>(value) }.getOrDefault(emptyMap())
}
