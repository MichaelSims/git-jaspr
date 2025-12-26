package sims.michael.gitjaspr.dataclassfragment.codegen

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind.INTERFACE
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSDeclarationContainer
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Variance
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.Queue
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import kotlin.reflect.KClass
import kotlin.time.measureTimedValue
import sims.michael.gitjaspr.dataclassfragment.ArrayPropertyWithNullability
import sims.michael.gitjaspr.dataclassfragment.DataClassFragment
import sims.michael.gitjaspr.dataclassfragment.DataClassProperty
import sims.michael.gitjaspr.dataclassfragment.MapPropertyWithNullability
import sims.michael.gitjaspr.dataclassfragment.NestedPropertyWithNullability
import sims.michael.gitjaspr.dataclassfragment.Nullability
import sims.michael.gitjaspr.dataclassfragment.PropertyWithNullability

data class DataClassFragmentDescriptor(
    val classDeclaration: KSClassDeclaration,
    val classType: KSType,
    private val properties: List<DataClassFragmentPropertyDescriptor>,
) {
    context(environment: SymbolProcessorEnvironment)
    fun process(block: DataClassFragmentDescriptor.() -> Unit) {
        logDuration("Time to process ${classDeclaration.simpleName.asString()}") {
            try {
                block()
            } catch (e: Exception) {
                environment.logger.exception(
                    Exception(
                        "Exception handling fragment ${classDeclaration.toClassName().canonicalName}",
                        e,
                    )
                )
                environment.logger.error(
                    e.message ?: e::class.asClassName().canonicalName,
                    classDeclaration,
                )
            }
        }
    }

    context(environment: SymbolProcessorEnvironment)
    fun forEachProperty(block: DataClassFragmentPropertyDescriptor.() -> Unit) {
        for (property in properties) {
            logDuration(
                "Time to process property ${property.propertyName} of ${classDeclaration.simpleName.asString()}"
            ) {
                try {
                    property.block()
                } catch (e: Exception) {
                    environment.logger.exception(
                        Exception(
                            "Exception handling property ${property.propertyName} in class ${classDeclaration.toClassName().canonicalName}",
                            e,
                        )
                    )
                    environment.logger.error(
                        e.message ?: e::class.asClassName().canonicalName,
                        property.propertyDeclaration,
                    )
                }
            }
        }
    }
}

data class DataClassFragmentPropertyDescriptor(
    val propertyDeclaration: KSPropertyDeclaration,
    val propertyType: KSType,
) {
    val propertyName by lazy { propertyDeclaration.simpleName.asString() }
}

interface DataClassPropertyTypeSymbolVisitor<T> {
    fun visitProperty(nestedType: KSType, nullable: Boolean): T

    fun visitNestedProperty(nestedType: KSType, nullable: Boolean): T

    fun visitArrayProperty(nestedType: KSType, nullable: Boolean, iterableType: KSType): T

    fun visitMapProperty(nestedType: KSType, nullable: Boolean): T
}

context(resolver: Resolver)
fun <T> KSType.accept(visitor: DataClassPropertyTypeSymbolVisitor<T>): T {
    fun typeAt(idx: Int) =
        requireNotNull(this.arguments.getOrNull(idx)?.type?.resolve()) {
            "No type at index $idx for $this"
        }

    return when {
        resolver.columnTypes.propertyWithNullability.isAssignableFrom(this) ->
            visitor.visitProperty(typeAt(0), typeAt(1) == resolver.columnTypes.nullable)

        resolver.columnTypes.nestedPropertyWithNullability.isAssignableFrom(this) ->
            visitor.visitNestedProperty(typeAt(0), typeAt(1) == resolver.columnTypes.nullable)

        resolver.columnTypes.arrayPropertyWithNullability.isAssignableFrom(this) ->
            visitor.visitArrayProperty(
                typeAt(0),
                typeAt(1) == resolver.columnTypes.nullable,
                typeAt(2),
            )

        resolver.columnTypes.mapPropertyWithNullability.isAssignableFrom(this) ->
            visitor.visitMapProperty(typeAt(0), typeAt(1) == resolver.columnTypes.nullable)

        else -> error("Unexpected type $this")
    }
}

/**
 * Marker object representing an "untyped" column. Useful for ad-hoc columns that will not be
 * involved in schema generation/validation or Dataset encoders.
 */
object Untyped

@Suppress("unused")
object ClassNames {
    val string = String::class.asClassName()
    val short = Short::class.asClassName()
    val int = Int::class.asClassName()
    val long = Long::class.asClassName()
    val double = Double::class.asClassName()
    val float = Float::class.asClassName()
    val boolean = Boolean::class.asClassName()
    val byte = Byte::class.asClassName()
    val bigDecimal = BigDecimal::class.asClassName()
    val bigInt = BigInteger::class.asClassName()

    val localDate = LocalDate::class.asClassName()
    val instant = Instant::class.asClassName()

    val unit = Unit::class.asClassName()

    val list = List::class.asClassName()
    val set = Set::class.asClassName()
    val queue = Queue::class.asClassName()
    val map = Map::class.asClassName()

    val property = PropertyWithNullability::class.asClassName()
    val nestedProperty = NestedPropertyWithNullability::class.asClassName()
    val arrayProperty = ArrayPropertyWithNullability::class.asClassName()
    val mapProperty = MapPropertyWithNullability::class.asClassName()

    val nullable = Nullability.Nullable::class.asClassName()
    val untypedColumn = Untyped::class.asClassName()

    val builder = Builder::class.asClassName()
    val iterableBuilder = IterableBuilder::class.asClassName()
    val scalarIterableBuilder = ScalarIterableBuilder::class.asClassName()
    val mapBuilder = MapBuilder::class.asClassName()
    val scalarMapBuilder = ScalarMapBuilder::class.asClassName()
    val builderFunctions = BuilderFunctions::class.asClassName()
}

fun getGeneratedDataClassName(fragmentNameWithoutPackage: String): String =
    "${fragmentNameWithoutPackage.replace('.', '_')}Data"

val ClassName.nameWithoutPackage: String
    get() = getNameWithoutPackage(packageName, canonicalName)

context(_: Resolver)
fun DataClassFragmentPropertyDescriptor.isScalarProperty(): Boolean =
    propertyType.isScalarProperty()

context(resolver: Resolver)
fun KSType.isScalarProperty() = resolver.columnTypes.propertyWithNullability.isAssignableFrom(this)

class PropertyTypes internal constructor(private val resolver: Resolver) {
    val propertyWithNullability = PropertyWithNullability::class.toKSType()
    val nestedPropertyWithNullability = NestedPropertyWithNullability::class.toKSType()
    val arrayPropertyWithNullability = ArrayPropertyWithNullability::class.toKSType()
    val mapPropertyWithNullability = MapPropertyWithNullability::class.toKSType()

    val nullable = Nullability.Nullable::class.toKSType()

    private fun KClass<*>.toKSType(): KSType {
        val name =
            requireNotNull(this.qualifiedName) {
                "$this doesn't have a name, so we can't look it up"
            }
        val declaration =
            requireNotNull(resolver.getClassDeclarationByName(name)) { "Can't resolve $name" }
        return declaration.asStarProjectedType()
    }
}

val Resolver.columnTypes: PropertyTypes by memoized { PropertyTypes(this) }

context(_: Resolver)
fun KSType.toDataClassPropertyType(): TypeName {
    return this.accept(
        object : DataClassPropertyTypeSymbolVisitor<TypeName> {
            override fun visitProperty(nestedType: KSType, nullable: Boolean): TypeName {
                failIfColumnIsUntyped(nestedType)
                return nestedType.toTypeName().copy(nullable = nullable)
            }

            override fun visitNestedProperty(nestedType: KSType, nullable: Boolean): TypeName =
                nestedType.generatedDataClassName.copy(nullable = nullable)

            override fun visitArrayProperty(
                nestedType: KSType,
                nullable: Boolean,
                iterableType: KSType,
            ): TypeName =
                (iterableType.declaration as KSClassDeclaration)
                    .toClassName()
                    .parameterizedBy(nestedType.toDataClassPropertyType())
                    .copy(nullable = nullable)

            override fun visitMapProperty(nestedType: KSType, nullable: Boolean): TypeName =
                ClassNames.map
                    .parameterizedBy(ClassNames.string, nestedType.toDataClassPropertyType())
                    .copy(nullable = nullable)
        }
    )
}

private fun failIfColumnIsUntyped(columnType: KSType) {
    check(columnType.toClassName() != ClassNames.untypedColumn) {
        "Data class generation for untyped columns is not supported"
    }
}

fun Element.collectEnclosedInterfaces(): List<Element> =
    (listOf(this) + enclosedElements.flatMap { it.collectEnclosedInterfaces() }).filter {
        typeElement ->
        typeElement.kind == ElementKind.INTERFACE
    }

context(_: SymbolProcessorEnvironment)
fun Resolver.collectFragments() =
    logDuration("Time to collect schema fragments") {
        getNewFiles().flatMap { it.collectWithEnclosed() }.mapNotNull { it.toDescriptor() }.toList()
    }

private val Resolver.dataClassPropertyType: KSType
    get() = lookupType(DataClassProperty::class)

private val Resolver.dataClassFragmentType: KSType
    get() = lookupType(DataClassFragment::class)

private fun KSDeclarationContainer.collectWithEnclosed(): Sequence<KSClassDeclaration> =
    declarations
        .filterIsInstance<KSDeclarationContainer>()
        .flatMap { it.collectWithEnclosed() }
        .plus(this)
        .filterIsInstance<KSClassDeclaration>()

context(resolver: Resolver)
private fun KSClassDeclaration.toDescriptor(): DataClassFragmentDescriptor? {
    if (classKind != INTERFACE) return null
    val thisType = asStarProjectedType()
    if (!resolver.dataClassFragmentType.isAssignableFrom(thisType)) return null
    // Schema fragments with type parameters are "abstract" and require some other declaration to
    // provide the arguments, so they are skipped here
    if (typeParameters.isNotEmpty()) return null

    val properties = getOrderedNamedColumnProperties().map { it.toDescriptor(thisType) }

    return DataClassFragmentDescriptor(this, thisType, properties)
}

context(resolver: Resolver)
@OptIn(KspExperimental::class)
private fun KSClassDeclaration.getOrderedNamedColumnProperties(): List<KSPropertyDeclaration> {
    val superTypeProperties =
        superTypes
            .map { it.resolve().declaration }
            .filterIsInstance<KSClassDeclaration>()
            .filter {
                resolver.dataClassFragmentType.isAssignableFrom(it.asStarProjectedType()) &&
                    it.asStarProjectedType() != resolver.dataClassFragmentType
            }
            .flatMap { it.getOrderedNamedColumnProperties() }
            .toList()
    val thisProperties =
        resolver
            .getDeclarationsInSourceOrder(this)
            .filterIsInstance<KSPropertyDeclaration>()
            .filter { resolver.dataClassPropertyType.isAssignableFrom(it.type.resolve()) }
    return (superTypeProperties + thisProperties).distinct()
}

context(_: Resolver)
private fun KSPropertyDeclaration.toDescriptor(
    enclosingType: KSType
): DataClassFragmentPropertyDescriptor {
    val unaliasedEnclosingType = enclosingType.resolveAliases()
    val typeResolvedPropertyType = asMemberOf(unaliasedEnclosingType)
    val propertyType = typeResolvedPropertyType.resolveAliases()
    return DataClassFragmentPropertyDescriptor(
        propertyDeclaration = this,
        propertyType = propertyType,
    )
}

context(environment: SymbolProcessorEnvironment)
fun <T> logDuration(message: String, body: () -> T): T {
    val result = measureTimedValue { body() }
    environment.logger.info("$message: ${result.duration}")
    return result.value
}

/**
 * This method is intended to remove all type aliases from the receiving KSType and return a new
 * "concrete type". It will replace both aliases in the primary type and aliases in the type
 * parameters.
 *
 * Implementation note -- these methods are complicated, but they work (we're pretty sure). They are
 * necessary because `KSType.replace(...)` does not appear to work on type aliases that "change" the
 * number of type parameters. And for some reason ksp doesn't give us a simple mechanism for
 * resolving typealiases to their concrete types.
 */
context(resolver: Resolver)
fun KSType.resolveAliases(): KSType = declaration.withArgs(arguments).resolveConcreteType()

sealed class DeclarationWithArgs {
    context(resolver: Resolver)
    abstract fun resolveConcreteType(): KSType
}

private fun KSDeclaration.withArgs(args: List<KSTypeArgument>) =
    when (this) {
        is KSClassDeclaration -> ClassDeclarationWithArgs(this, mapParameterValues(args))
        is KSTypeAlias -> TypeAliasDeclarationWithArgs(this, mapParameterValues(args))
        else -> error("Invalid declaration type: $this")
    }

private data class ClassDeclarationWithArgs(
    val declaration: KSClassDeclaration,
    val args: Map<String, KSTypeArgument>,
) : DeclarationWithArgs() {
    init {
        check(declaration.typeParameters.size == args.size) {
            "Class ${declaration.simpleName.asString()} has ${declaration.typeParameters.size} type " +
                "parameters, but ${args.size} arguments were provided"
        }
    }

    context(resolver: Resolver)
    override fun resolveConcreteType(): KSType {
        val resolvedArguments =
            declaration.typeParameters
                .map {
                    requireNotNull(args[it.name.asString()]) {
                        "No argument provided for type parameter ${it.name.asString()} in class" +
                            " ${declaration.simpleName.asString()}"
                    }
                }
                .map { it.resolveArgsAndAliases(args) }
        val type = declaration.asType(resolvedArguments)
        return type
    }
}

private data class TypeAliasDeclarationWithArgs(
    val declaration: KSTypeAlias,
    val args: Map<String, KSTypeArgument>,
) : DeclarationWithArgs() {
    init {
        check(declaration.typeParameters.size == args.size) {
            "Class ${declaration.simpleName.asString()} has ${declaration.typeParameters.size} type " +
                "parameters, but ${args.size} arguments were provided"
        }
    }

    context(resolver: Resolver)
    override fun resolveConcreteType(): KSType {
        val resolvedType = declaration.type.resolve()
        val resolvedArgs = resolvedType.resolveArgs(args)
        val declarationWithArgs = resolvedType.declaration.withArgs(resolvedArgs)
        val type = declarationWithArgs.resolveConcreteType()
        return type
    }
}

private fun mapParameterValues(
    params: List<KSTypeParameter>,
    args: List<KSTypeArgument>,
): Map<String, KSTypeArgument> =
    params.zip(args).associate { (param, arg) -> param.name.asString() to arg }

private fun KSDeclaration.mapParameterValues(
    args: List<KSTypeArgument>
): Map<String, KSTypeArgument> = mapParameterValues(typeParameters, args)

context(resolver: Resolver)
private fun KSTypeArgument.name(): String =
    resolveTypeOrHandleStar().declaration.simpleName.asString()

context(resolver: Resolver)
private fun KSType.resolveArgs(args: Map<String, KSTypeArgument>) =
    arguments.map { args[it.name()] ?: it }.map { it.resolveArgsAndAliases(args) }

context(resolver: Resolver)
private fun KSTypeArgument.resolveArgsAndAliases(
    argMap: Map<String, KSTypeArgument>
): KSTypeArgument {
    val type = resolveTypeOrHandleStar()
    val declaration = type.declaration
    val args = type.resolveArgs(argMap)
    val resolvedType = declaration.withArgs(args).resolveConcreteType()
    val ref = resolver.createKSTypeReferenceFromKSType(resolvedType)
    return resolver.getTypeArgument(ref, variance)
}

context(resolver: Resolver)
private fun KSTypeArgument.resolveTypeOrHandleStar(): KSType {
    val resolvedType = type?.resolve()
    return when {
        resolvedType == null && variance == Variance.STAR -> resolver.builtIns.anyType
        resolvedType != null -> resolvedType
        else -> error("Could not resolve type for $this")
    }
}

fun ClassName.derivedName(transform: (String) -> String): ClassName {
    val generatedImplementationName = transform(nameWithoutPackage)
    return ClassName(packageName, generatedImplementationName)
}

fun ClassName.subPackage(subPackageName: String): ClassName {
    val newPackageName =
        listOf(packageName, subPackageName).filter(String::isNotEmpty).joinToString(".")
    return ClassName(newPackageName, simpleNames)
}

val ClassName.generatedDataClassName: ClassName
    get() = derivedName { getGeneratedDataClassName(it) }

val KSType.generatedDataClassName: ClassName
    get() = toClassName().generatedDataClassName

val DataClassFragmentDescriptor.generatedDataClassName: ClassName
    get() = classDeclaration.toClassName().generatedDataClassName

val DataClassFragmentDescriptor.generatedTestDataDslClassName: ClassName
    get() =
        classDeclaration
            .toClassName()
            .derivedName { getGeneratedDataClassName(it) + "Builder" }
            .subPackage(DataClassFragmentTestDataDslGenerator.TEST_DSL_PACKAGE_SUFFIX)
