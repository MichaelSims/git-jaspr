@file:Suppress("unused")

package sims.michael.gitkspr.dataclassfragment

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

interface DataClassFragment

sealed class DataClassProperty

sealed class Nullability {
    data object Nullable : Nullability()
    data object NotNull : Nullability()
}

class PropertyWithNullability<T, N : Nullability> : DataClassProperty()
class NestedPropertyWithNullability<T : DataClassFragment, N : Nullability> : DataClassProperty()
class ArrayPropertyWithNullability<T : DataClassProperty, N : Nullability, I : Iterable<*>> : DataClassProperty()
class MapPropertyWithNullability<T : DataClassProperty, N : Nullability> : DataClassProperty()

// region Type Aliases for convenience
typealias Property<T> = PropertyWithNullability<T, Nullability.Nullable>
typealias PropertyNotNull<T> = PropertyWithNullability<T, Nullability.NotNull>

typealias StringProperty = Property<String>
typealias StringPropertyNotNull = PropertyNotNull<String>
typealias ShortProperty = Property<Short>
typealias ShortPropertyNotNull = PropertyNotNull<Short>
typealias IntProperty = Property<Int>
typealias IntPropertyNotNull = PropertyNotNull<Int>
typealias LongProperty = Property<Long>
typealias LongPropertyNotNull = PropertyNotNull<Long>
typealias DoubleProperty = Property<Double>
typealias DoublePropertyNotNull = PropertyNotNull<Double>
typealias FloatProperty = Property<Float>
typealias FloatPropertyNotNull = PropertyNotNull<Float>
typealias BooleanProperty = Property<Boolean>
typealias BooleanPropertyNotNull = PropertyNotNull<Boolean>
typealias ByteProperty = Property<Byte>
typealias BytePropertyNotNull = PropertyNotNull<Byte>
typealias BigDecimalProperty = Property<BigDecimal>
typealias BigDecimalPropertyNotNull = PropertyNotNull<BigDecimal>
typealias BigIntProperty = Property<BigInteger>
typealias BigIntPropertyNotNull = PropertyNotNull<BigInteger>
typealias DateProperty = Property<LocalDate>
typealias DatePropertyNotNull = PropertyNotNull<LocalDate>
typealias TimestampProperty = Property<Instant>
typealias TimestampPropertyNotNull = PropertyNotNull<Instant>

typealias NestedProperty<T> = NestedPropertyWithNullability<T, Nullability.Nullable>
typealias NestedPropertyNotNull<T> = NestedPropertyWithNullability<T, Nullability.NotNull>

typealias ListProperty<T> = ArrayPropertyWithNullability<T, Nullability.Nullable, List<*>>
typealias ListOfNestedProperty<T> = ListProperty<NestedProperty<T>>
typealias ListOfNestedPropertyNotNull<T> = ListPropertyNotNull<NestedPropertyNotNull<T>>
typealias ListPropertyNotNull<T> = ArrayPropertyWithNullability<T, Nullability.NotNull, List<*>>

typealias ListOfStringPropertyNotNull = ListPropertyNotNull<StringPropertyNotNull>

typealias SetProperty<T> = ArrayPropertyWithNullability<T, Nullability.Nullable, Set<*>>
typealias SetPropertyNotNull<T> = ArrayPropertyWithNullability<T, Nullability.NotNull, Set<*>>

typealias MapProperty<T> = MapPropertyWithNullability<T, Nullability.Nullable>
typealias MapPropertyNotNull<T> = MapPropertyWithNullability<T, Nullability.NotNull>
// endregion
