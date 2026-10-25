package com.sowz.aestudio.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("sowz_ae_recent_v1", Context.MODE_PRIVATE)

    fun list(): List<SavedProject> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val uri = item.optString("uri")
                    if (uri.isBlank()) continue
                    add(SavedProject(
                        fileName = item.optString("fileName", "Untitled project"),
                        uri = uri,
                        format = item.optString("format", "Unknown"),
                        summary = item.optString("summary", ""),
                        openedAt = item.optLong("openedAt", 0L)
                    ))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun add(report: ProjectReport, uri: String) {
        val next = list().filterNot { it.uri == uri }.let {
            listOf(SavedProject(report.fileName, uri, report.format, report.summary, System.currentTimeMillis())) + it
        }
        val array = JSONArray()
        next.take(30).forEach { saved ->
            array.put(JSONObject()
                .put("fileName", saved.fileName)
                .put("uri", saved.uri)
                .put("format", saved.format)
                .put("summary", saved.summary)
                .put("openedAt", saved.openedAt))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun clear() = prefs.edit().remove("items").apply()
}
