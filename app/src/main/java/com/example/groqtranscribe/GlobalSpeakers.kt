package com.example.groqtranscribe

import java.util.PriorityQueue
import kotlin.math.sqrt

/** 거리 임계값: 높을수록 같은 화자로 더 쉽게 묶는다. */
enum class SpeakerClustering(val label: String, val distance: Double) {
    STRICT("0.3", 0.3), CAREFUL("0.4", 0.4), DEFAULT("0.5", 0.5), RELAXED("0.6", 0.6), LOOSE("0.7", 0.7);
    val similarity: Double get() = 1.0 - distance
    companion object {
        fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

data class SpeakerPrototype(val chunk: Int, val localSpeaker: Int, val vector: FloatArray, val weight: Double,
                            val cannotLink: Set<Int> = emptySet())

fun normalized(vector: FloatArray): FloatArray? {
    if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
    val norm = sqrt(vector.sumOf { it.toDouble() * it })
    if (norm < 1e-8) return null
    return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
}

/** 구간 번호가 아닌 음성 임베딩으로 녹음 전체를 묶는다. 실제 겹말이 있는 후보만 병합 금지. */
fun globalSpeakerIds(prototypes: List<SpeakerPrototype>, requestedCount: Int, threshold: Double = 0.5,
                     checkCancelled: () -> Unit = {}): IntArray {
    data class Cluster(val members: List<Int>, val keys: Set<Pair<Int, Int>>, val blocked: Set<Pair<Int, Int>>, val vector: FloatArray, val weight: Double)
    data class Candidate(val a: Int, val b: Int, val similarity: Double)
    val clusters = mutableMapOf<Int, Cluster>()
    prototypes.forEachIndexed { index, prototype ->
        // 짧은 후보는 독립 화자를 만들지 않고, 확립된 군집에 나중에 연결한다.
        if (prototype.weight < 2.0 || !prototype.weight.isFinite()) return@forEachIndexed
        normalized(prototype.vector)?.let { clusters[index] = Cluster(listOf(index), setOf(prototype.chunk to prototype.localSpeaker),
            prototype.cannotLink.map { prototype.chunk to it }.toSet(), it, prototype.weight.coerceIn(1.0, 15.0)) }
    }
    val queue = PriorityQueue<Candidate>(compareByDescending<Candidate> { it.similarity }.thenBy { it.a }.thenBy { it.b })
    fun offer(a: Int, b: Int) {
        val x = clusters.getValue(a); val y = clusters.getValue(b)
        if (x.vector.size != y.vector.size) return
        if (requestedCount <= 0 && (x.keys.any { it in y.blocked } || y.keys.any { it in x.blocked })) return
        val score = x.vector.indices.sumOf { x.vector[it].toDouble() * y.vector[it] }
        if (requestedCount > 0 || score >= threshold) queue.add(Candidate(a, b, score))
    }
    for (a in clusters.keys) { checkCancelled(); for (b in clusters.keys) if (b > a) offer(a, b) }
    var next = prototypes.size
    while (queue.isNotEmpty() && (requestedCount <= 0 || clusters.size > requestedCount)) {
        checkCancelled()
        val pair = queue.remove()
        val a = clusters[pair.a] ?: continue
        val b = clusters[pair.b] ?: continue
        val weight = a.weight + b.weight
        val vector = normalized(FloatArray(a.vector.size) { ((a.vector[it] * a.weight + b.vector[it] * b.weight) / weight).toFloat() }) ?: a.vector
        clusters.remove(pair.a); clusters.remove(pair.b)
        val id = next++
        clusters[id] = Cluster(a.members + b.members, a.keys + b.keys, a.blocked + b.blocked, vector, weight)
        for (other in clusters.keys) if (other != id) offer(other, id)
    }
    val result = IntArray(prototypes.size) { -1 }
    clusters.values.sortedBy { it.members.min() }.forEachIndexed { id, cluster -> cluster.members.forEach { result[it] = id } }
    prototypes.forEachIndexed { index, prototype ->
        checkCancelled()
        if (result[index] >= 0 || !prototype.weight.isFinite() || prototype.weight < 0.3) return@forEachIndexed
        val vector = normalized(prototype.vector) ?: return@forEachIndexed
        val candidates = clusters.values.filter { cluster ->
            cluster.vector.size == vector.size && (requestedCount > 0 ||
                ((prototype.chunk to prototype.localSpeaker) !in cluster.blocked &&
                    cluster.keys.none { it.first == prototype.chunk && it.second in prototype.cannotLink }))
        }.map { cluster -> cluster to vector.indices.sumOf { vector[it].toDouble() * cluster.vector[it] } }
            .sortedByDescending { it.second }
        val best = candidates.firstOrNull() ?: return@forEachIndexed
        // 약한 음성은 충분한 유사도와 2순위와의 차이가 있을 때만 연결한다.
        if (best.second >= threshold && (candidates.size == 1 || best.second - candidates[1].second >= 0.1)) {
            result[index] = result[best.first.members.first()]
        }
    }
    return result
}
