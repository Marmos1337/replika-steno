package ru.replika.steno.diar

import kotlin.math.sqrt

/**
 * Average-linkage agglomerative clustering on cosine distance. The number of speakers is not
 * known in advance: merging stops when the closest two clusters are farther apart than [threshold].
 */
object Clustering {

    class Result(
        /** cluster index per input vector, numbered by first appearance (0, 1, 2...). */
        val labels: IntArray,
        /** cosine similarity of each vector to its own cluster centroid, in [-1, 1]. */
        val confidence: FloatArray,
    )

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val inv = if (n > 0) (1.0 / sqrt(n)).toFloat() else 0f
        return FloatArray(v.size) { v[it] * inv }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun cluster(vectors: List<FloatArray>, threshold: Float): Result {
        val n = vectors.size
        if (n == 0) return Result(IntArray(0), FloatArray(0))
        val e = vectors.map { normalize(it) }
        // pairwise average distance between clusters, updated with the Lance-Williams formula
        val dist = Array(n) { i -> FloatArray(n) { j -> if (i == j) 0f else 1f - dot(e[i], e[j]) } }
        val size = IntArray(n) { 1 }
        val alive = BooleanArray(n) { true }
        val owner = IntArray(n) { it } // representative cluster of each point
        while (true) {
            var best = Float.MAX_VALUE
            var bi = -1
            var bj = -1
            for (i in 0 until n) {
                if (!alive[i]) continue
                val row = dist[i]
                for (j in i + 1 until n) {
                    if (alive[j] && row[j] < best) { best = row[j]; bi = i; bj = j }
                }
            }
            if (bi < 0 || best > threshold) break
            val si = size[bi].toFloat()
            val sj = size[bj].toFloat()
            for (k in 0 until n) {
                if (!alive[k] || k == bi || k == bj) continue
                val d = (dist[bi][k] * si + dist[bj][k] * sj) / (si + sj)
                dist[bi][k] = d; dist[k][bi] = d
            }
            size[bi] += size[bj]
            alive[bj] = false
            for (p in 0 until n) if (owner[p] == bj) owner[p] = bi
        }
        // relabel by first appearance
        val map = HashMap<Int, Int>()
        val labels = IntArray(n) { p -> map.getOrPut(owner[p]) { map.size } }
        // centroids and per-vector similarity to its own centroid
        val dim = e[0].size
        val centroids = Array(map.size) { FloatArray(dim) }
        for (p in 0 until n) { val c = centroids[labels[p]]; for (d in 0 until dim) c[d] += e[p][d] }
        val normC = centroids.map { normalize(it) }
        val conf = FloatArray(n) { p -> dot(e[p], normC[labels[p]]) }
        return Result(labels, conf)
    }
}
