@file:Suppress("unused")

package sims.michael.gitjaspr.dataclassfragment.codegen

import java.util.*

@DslMarker
annotation class TestDslMarker

@TestDslMarker
interface Builder<T> {
    var isNull: Boolean
    fun from(prototype: T)
    fun build(): T
}

class ScalarIterableBuilder<I : Iterable<T>?, T>(private val build: (Iterable<T>, Boolean) -> I) : Builder<I> {
    override var isNull = false
    override fun build(): I = build(delegate.toSortedMap().values, isNull)
    private val delegate = mutableMapOf<Int, T>()
    operator fun get(index: Int): T? = delegate[index]
    operator fun set(index: Int, value: T) {
        delegate[index] = value
    }

    operator fun plusAssign(elements: Iterable<T>) = elements.forEach(::add)
    operator fun plusAssign(element: T) {
        add(element)
    }

    private fun add(element: T) = delegate.put((delegate.keys.maxOrNull() ?: -1) + 1, element)

    override fun from(prototype: I) {
        if (prototype == null) {
            isNull = true
        } else {
            prototype.forEach(::add)
        }
    }
}

class IterableBuilder<I : Iterable<T>?, T, B : Builder<T>>(
    private val createBuilder: () -> B,
    private val build: (Iterable<B>, Boolean) -> I,
) : Builder<I> {
    override var isNull = false
    override fun build(): I {
        return build(delegate.toSortedMap().values, isNull)
    }

    private val delegate = mutableMapOf<Int, B>()
    operator fun get(index: Int): B = delegate.getOrPut(index, createBuilder)
    operator fun invoke(fn: B.() -> Unit) = delegate.put((delegate.keys.maxOrNull() ?: -1) + 1, createBuilder().apply(fn))

    override fun from(prototype: I) {
        if (prototype == null) {
            isNull = true
        } else {
            prototype.forEach { e -> invoke { from(e) } }
        }
    }
}

class ScalarMapBuilder<M : Map<String, T>?, T> private constructor(
    private val delegate: MutableMap<String, T>,
    private val build: (Map<String, T>, Boolean) -> M,
) : Builder<M>, MutableMap<String, T> by delegate {
    constructor(build: (Map<String, T>, Boolean) -> M) : this(mutableMapOf(), build)

    override var isNull = false
    override fun build(): M = build(delegate, isNull)

    override fun from(prototype: M) {
        if (prototype == null) {
            isNull = true
        } else {
            delegate += prototype
        }
    }
}

class MapBuilder<M : Map<String, T>?, T, B : Builder<T>>(
    private val createBuilder: () -> B,
    private val build: (Map<String, B>, Boolean) -> M,
) : Builder<M> {
    private val delegate: MutableMap<String, B> = mutableMapOf()

    override var isNull = false
    override fun build(): M = build(delegate, isNull)
    operator fun get(key: String): B = delegate.getOrPut(key, createBuilder)

    override fun from(prototype: M) {
        if (prototype == null) {
            isNull = true
        } else {
            for ((k, v) in prototype) {
                this[k].from(v)
            }
        }
    }
}

@Suppress("MemberVisibilityCanBePrivate")
object BuilderFunctions {

    inline fun <T, R> ignoringIsNull(crossinline fn: (T) -> R): (T, Boolean) -> R = { src, _ -> fn(src) }
    inline fun <T, R> ifNotNull(crossinline fn: (T) -> R): (T, Boolean) -> R? = { src, isNull ->
        if (isNull) null else fn(src)
    }

    fun <T> identity(src: T): T = src
    fun <T> buildScalarList(src: Iterable<T>): List<T> = src.toList()
    fun <T> buildScalarQueue(src: Iterable<T>): Queue<T> = LinkedList(buildScalarList(src))
    fun <T> buildScalarSet(src: Iterable<T>): Set<T> = src.toSet()
    fun <T, B : Builder<T>> buildMap(src: Map<String, B>): Map<String, T> = src.mapValues { (_, v) -> v.build() }
    fun <T, B : Builder<T>> buildList(src: Iterable<B>): List<T> = src.map(Builder<T>::build)
    fun <T, B : Builder<T>> buildQueue(src: Iterable<B>): Queue<T> = LinkedList(buildList(src))
    fun <T, B : Builder<T>> buildSet(src: Iterable<B>): Set<T> = buildList(src).toSet()
}
