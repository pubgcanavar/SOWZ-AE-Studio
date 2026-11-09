package com.sowz.aestudio.core

import org.json.JSONArray
import org.json.JSONObject

data class AepProjectSummary(
    val compositionCount: Int,
    val layerCount: Int,
    val footageCount: Int,
    val effects: List<EffectInstanceValue>,
    val diagnostics: List<String>,
    val incompleteNodeCount: Int
) {
    fun reportText(): String = buildString {
        append("Compositions: ").append(compositionCount)
        append("\nLayers: ").append(layerCount)
        append("\nFootage references: ").append(footageCount)
        append("\nEffect instances: ").append(effects.size)
        append("\nIncomplete nodes: ").append(incompleteNodeCount)
        effects.take(18).forEach { effect ->
            append("\n• ").append(effect.layerName).append(" — ").append(effect.displayName)
            if (effect.parameters.any { it.animated }) append(" · animated")
        }
        if (effects.size > 18) append("\n… ").append(effects.size - 18).append(" more effects")
        diagnostics.take(5).forEach { append("\nNote: ").append(it) }
        append("\n\nThis reads AEP structure and effect data. It does not render the composition or load external media.")
    }
}

object AepProjectImporter {
    private const val MAX_JSON_CHARS = 96 * 1024 * 1024
    private const val MAX_EFFECTS = 10_000
    private const val MAX_NODES = 500_000

    fun parse(projectJson: String): AepProjectSummary {
        require(projectJson.length <= MAX_JSON_CHARS) { "AEP report exceeds the safety limit." }
        val root = JSONObject(projectJson)
        root.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }

        val compositions = root.optJSONArray("compositions") ?: JSONArray()
        val footage = root.optJSONArray("footage") ?: JSONArray()
        val diagnosticArray = root.optJSONArray("diagnostics") ?: JSONArray()
        val effectInstances = mutableListOf<EffectInstanceValue>()
        var layerCount = 0
        var incompleteNodes = 0
        var visitedNodes = 0

        fun visitNode() {
            visitedNodes++
            if (visitedNodes > MAX_NODES) error("AEP property tree exceeds the safety limit.")
        }

        for (compositionIndex in 0 until compositions.length()) {
            val composition = compositions.optJSONObject(compositionIndex) ?: continue
            val layers = composition.optJSONArray("layers") ?: JSONArray()
            for (layerIndex in 0 until layers.length()) {
                val layer = layers.optJSONObject(layerIndex) ?: continue
                layerCount++
                val layerName = layer.optString("name", "Layer ${layerIndex + 1}")
                val properties = layer.optJSONArray("properties") ?: JSONArray()
                incompleteNodes += countIncomplete(properties, ::visitNode)
                val parade = findNode(properties, "ADBE Effect Parade", ::visitNode)
                    ?.optJSONArray("children") ?: continue

                for (effectIndex in 0 until parade.length()) {
                    if (effectInstances.size >= MAX_EFFECTS) {
                        error("AEP contains too many effect instances.")
                    }
                    val effect = parade.optJSONObject(effectIndex) ?: continue
                    val matchName = effect.optString("match_name").trim()
                    if (matchName.isBlank() || matchName == "ADBE Effect Parade") continue
                    val displayName = effect.optString("name", matchName).trim().ifBlank { matchName }
                    val parameters = mutableListOf<EffectParameterValue>()
                    collectParameters(effect.optJSONArray("children"), parameters, ::visitNode)
                    effectInstances.add(
                        EffectInstanceValue(layerName, matchName, displayName, parameters)
                    )
                }
            }
        }

        val diagnostics = mutableListOf<String>()
        for (index in 0 until diagnosticArray.length()) {
            val item = diagnosticArray.optJSONObject(index) ?: continue
            val code = item.optString("code")
            val message = item.optString("message")
            diagnostics.add(listOf(code, message).filter { it.isNotBlank() }.joinToString(": "))
        }

        return AepProjectSummary(
            compositionCount = compositions.length(),
            layerCount = layerCount,
            footageCount = footage.length(),
            effects = effectInstances,
            diagnostics = diagnostics,
            incompleteNodeCount = incompleteNodes
        )
    }

    private fun findNode(
        nodes: JSONArray,
        matchName: String,
        visit: () -> Unit
    ): JSONObject? {
        for (index in 0 until nodes.length()) {
            visit()
            val node = nodes.optJSONObject(index) ?: continue
            if (node.optString("match_name") == matchName) return node
            findNode(node.optJSONArray("children") ?: JSONArray(), matchName, visit)?.let { return it }
        }
        return null
    }

    private fun collectParameters(
        nodes: JSONArray?,
        output: MutableList<EffectParameterValue>,
        visit: () -> Unit
    ) {
        if (nodes == null) return
        for (index in 0 until nodes.length()) {
            visit()
            val node = nodes.optJSONObject(index) ?: continue
            readParameter(node)?.let(output::add)
            collectParameters(node.optJSONArray("children"), output, visit)
        }
    }

    private fun readParameter(node: JSONObject): EffectParameterValue? {
        val property = node.optJSONObject("property") ?: return null
        val numberArray = property.optJSONObject("static_value")?.optJSONArray("Numbers")
        val values = mutableListOf<Double>()
        if (numberArray != null) {
            for (index in 0 until minOf(4, numberArray.length())) {
                val value = numberArray.optJSONObject(index)?.optDouble("value", Double.NaN)
                    ?: Double.NaN
                if (value.isFinite()) values.add(value)
            }
        }
        val keyframes = property.optJSONArray("keyframes")
        val animated = (keyframes?.length() ?: 0) > 0 ||
            property.optString("expression").isNotBlank()
        if (values.isEmpty() && !animated) return null
        return EffectParameterValue(
            name = node.optString("name", node.optString("match_name")),
            values = values,
            animated = animated
        )
    }

    private fun countIncomplete(nodes: JSONArray, visit: () -> Unit): Int {
        var count = 0
        for (index in 0 until nodes.length()) {
            visit()
            val node = nodes.optJSONObject(index) ?: continue
            val status = node.optString("decode_status")
            if (status.isNotBlank() && status != "Decoded") count++
            count += countIncomplete(node.optJSONArray("children") ?: JSONArray(), visit)
        }
        return count
    }
}
