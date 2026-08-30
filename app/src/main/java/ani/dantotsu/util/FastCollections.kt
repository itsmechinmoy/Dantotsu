package ani.dantotsu.util

/**
 * High-performance, zero-allocation collection extensions based on AndroidX / Compose internals.
 * Iterates directly by index on [List] and [ArrayList] instances without allocating an [Iterator].
 */

inline fun <T> List<T>.fastForEach(action: (T) -> Unit) {
    for (index in 0 until size) {
        action(get(index))
    }
}

inline fun <T> List<T>.fastForEachIndexed(action: (index: Int, T) -> Unit) {
    for (index in 0 until size) {
        action(index, get(index))
    }
}

inline fun <T, R> List<T>.fastMap(transform: (T) -> R): List<R> {
    val target = ArrayList<R>(size)
    for (index in 0 until size) {
        target.add(transform(get(index)))
    }
    return target
}

inline fun <T, R> List<T>.fastMapIndexed(transform: (index: Int, T) -> R): List<R> {
    val target = ArrayList<R>(size)
    for (index in 0 until size) {
        target.add(transform(index, get(index)))
    }
    return target
}

inline fun <T> List<T>.fastFilter(predicate: (T) -> Boolean): List<T> {
    val target = ArrayList<T>()
    for (index in 0 until size) {
        val item = get(index)
        if (predicate(item)) {
            target.add(item)
        }
    }
    return target
}

inline fun <T, R : Any> List<T>.fastMapNotNull(transform: (T) -> R?): List<R> {
    val target = ArrayList<R>(size)
    for (index in 0 until size) {
        val transformed = transform(get(index))
        if (transformed != null) {
            target.add(transformed)
        }
    }
    return target
}

inline fun <T> List<T>.fastFirstOrNull(predicate: (T) -> Boolean): T? {
    for (index in 0 until size) {
        val item = get(index)
        if (predicate(item)) {
            return item
        }
    }
    return null
}

inline fun <T> List<T>.fastAny(predicate: (T) -> Boolean): Boolean {
    for (index in 0 until size) {
        if (predicate(get(index))) {
            return true
        }
    }
    return false
}

inline fun <T> List<T>.fastAll(predicate: (T) -> Boolean): Boolean {
    for (index in 0 until size) {
        if (!predicate(get(index))) {
            return false
        }
    }
    return true
}
