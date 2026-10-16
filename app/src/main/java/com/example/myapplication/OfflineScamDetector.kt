
package com.example.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

object OfflineScamDetector {

    data class Result(
        val category: String,
        val confidence: Double,
        val scores: Map<String, Double>
    )

    private data class Model(
        val classes: List<String>,
        val vocabulary: Map<String, Int>,
        val idf: DoubleArray,
        val coefficients: Array<DoubleArray>,
        val intercepts: DoubleArray
    )

    @Volatile
    private var cachedModel: Model? = null

    // FIXED: Android-compatible regex with correct escaping.
    private val tokenPattern = Regex("\\b\\w\\w+\\b")

    fun predict(context: Context, message: String): Result {
        require(message.isNotBlank()) {
            "Please enter a message."
        }

        val model = cachedModel ?: synchronized(this) {
            cachedModel ?: loadModel(context).also {
                cachedModel = it
            }
        }

        val normalized = Normalizer.normalize(
            message.lowercase(Locale.ROOT),
            Normalizer.Form.NFKD
        ).replace(Regex("\\p{M}+"), "")

        val tokens = tokenPattern.findAll(normalized)
            .map { it.value }
            .toList()

        val counts = HashMap<Int, Double>()

        fun addFeature(feature: String) {
            val index = model.vocabulary[feature] ?: return
            counts[index] = (counts[index] ?: 0.0) + 1.0
        }

        // Single-word features
        for (token in tokens) {
            addFeature(token)
        }

        // Two-word features
        for (i in 0 until tokens.size - 1) {
            addFeature("${tokens[i]} ${tokens[i + 1]}")
        }

        // Apply TF-IDF weights
        val weighted = HashMap<Int, Double>()
        var normSquared = 0.0

        for ((index, count) in counts) {
            val value = count * model.idf[index]
            weighted[index] = value
            normSquared += value * value
        }

        // Normalize the feature vector
        val norm = sqrt(normSquared)

        if (norm > 0.0) {
            for ((index, value) in weighted.toMap()) {
                weighted[index] = value / norm
            }
        }

        // Calculate logistic regression scores
        val logits = DoubleArray(model.classes.size) { row ->
            var score = model.intercepts[row]

            for ((index, value) in weighted) {
                score += model.coefficients[row][index] * value
            }

            score
        }

        // Convert scores to probabilities
        val maxLogit = logits.maxOrNull() ?: 0.0

        val probabilities = logits.map { value ->
            exp(value - maxLogit)
        }

        val total = probabilities.sum().coerceAtLeast(1e-300)

        val scores = model.classes.indices.associate { index ->
            model.classes[index] to
                    (probabilities[index] / total * 100.0)
        }

        val bestIndex = probabilities.indices.maxByOrNull {
            probabilities[it]
        } ?: 0

        return Result(
            category = model.classes[bestIndex],
            confidence = probabilities[bestIndex] / total * 100.0,
            scores = scores
        )
    }

    private fun loadModel(context: Context): Model {
        val jsonText = context.assets
            .open("android_model.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val json = JSONObject(jsonText)

        val classesJson = json.getJSONArray("classes")
        val classes = List(classesJson.length()) { index ->
            classesJson.getString(index)
        }

        val vocabularyJson = json.getJSONObject("vocabulary")
        val vocabulary = mutableMapOf<String, Int>()

        val keys = vocabularyJson.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            vocabulary[key] = vocabularyJson.getInt(key)
        }

        val idf = json.getJSONArray("idf").toDoubleArray()

        val coefficientJson = json.getJSONArray("coefficients")
        val coefficients = Array(coefficientJson.length()) { row ->
            coefficientJson.getJSONArray(row).toDoubleArray()
        }

        val intercepts =
            json.getJSONArray("intercepts").toDoubleArray()

        require(
            classes.size == coefficients.size &&
                    classes.size == intercepts.size &&
                    vocabulary.size == idf.size &&
                    coefficients.all { it.size == idf.size }
        ) {
            "The exported model has inconsistent dimensions."
        }

        return Model(
            classes = classes,
            vocabulary = vocabulary,
            idf = idf,
            coefficients = coefficients,
            intercepts = intercepts
        )
    }

    private fun JSONArray.toDoubleArray(): DoubleArray {
        return DoubleArray(length()) { index ->
            getDouble(index)
        }
    }
}
