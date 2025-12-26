package sims.michael.gitjaspr.dataclassfragment.codegen

import arrow.core.memoize
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSType
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

val lookupType: Resolver.(type: KClass<*>) -> KSType = Resolver::lookupTypeRoutine.memoize()

private fun Resolver.lookupTypeRoutine(type: KClass<*>): KSType {
    val name =
        checkNotNull(type.qualifiedName) { "$type doesn't have a name, so we can't look it up" }
    val declaration = checkNotNull(getClassDeclarationByName(name)) { "Can't resolve $name" }
    return declaration.asStarProjectedType()
}

fun <T, V> memoized(factory: T.() -> V) =
    object : ReadOnlyProperty<T, V> {
        private val memoized = factory.memoize()

        override fun getValue(thisRef: T, property: KProperty<*>): V = memoized(thisRef)
    }
