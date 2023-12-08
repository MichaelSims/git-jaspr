package sims.michael.gitjaspr.dataclassfragment.codegen

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.toKmClass
import kotlinx.metadata.*
import sims.michael.gitjaspr.dataclassfragment.*
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.*
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

@OptIn(KotlinPoetMetadataPreview::class)
class DataClassFragmentProcessorUtilities(processingEnv: ProcessingEnvironment) {

    fun collectFragments(elements: Collection<Element>): List<TypeElement> =
        elements
            .flatMap { typeElement -> typeElement.collectEnclosedInterfaces() }
            .filterIsInstance<TypeElement>()
            .filter { typeElement -> typeUtils.isAssignable(typeElement.asType(), fragmentType) }
            // Schema fragments with type parameters are "abstract" and require some other declaration to provide
            // the arguments, so they are skipped here
            .filter { typeElement -> typeElement.typeParameters.isEmpty() }

    // We use TypeElement.getEnclosedElements to enforce ordering since it returns the elements in source code
    // declaration order. KmClass.getProperties has no such guarantee.
    fun getNamedColumnProperties(fragment: TypeElement): List<KmPropertyInfo> =
        collectInterfaces(fragment)
            .filter { elementInfo ->
                val typeMirror = elementInfo.typeElement.asType()
                typeUtils.isAssignable(typeMirror, fragmentType) &&
                    !typeUtils.isSameType(typeMirror, fragmentType)
            }
            .flatMap { (typeElement, kmClass, declaration) ->
                val kmPropertiesByName = kmClass.properties.associateBy { it.name.replaceFirstChar(Char::lowercase) }
                ElementFilter
                    .methodsIn(typeElement.enclosedElements)
                    .mapNotNull { element -> kmPropertiesByName[element.toPropertyName()] }
                    .mapNotNull { property ->
                        val typeArgTypes = declaration?.getTypesFromTypeArgs().orEmpty()
                        checkTypeArgCount(kmClass, typeArgTypes.size)
                        val returnType = property.returnType.asTypeName(typeArgTypes)
                        if (returnType is ParameterizedTypeName) {
                            KmPropertyInfo(property, returnType)
                        } else {
                            null
                        }
                    }
            }
            .distinctBy { info -> info.property.name to info.returnType }
            .filter { property -> property.returnType.rawType.canonicalName in namedColumnSubtypes }

    private fun KmType.getTypesFromTypeArgs(): List<KmType> =
        arguments.map { projection ->
            checkNotNull(projection.type) { "Unexpected star projection for $projection" }
        }

    private fun checkTypeArgCount(kmClass: KmClass, typeArgCount: Int) {
        val typeParamCount = kmClass.typeParameters.size
        check(typeParamCount == typeArgCount) {
            "Expected $typeParamCount arguments for $kmClass, got $typeArgCount instead"
        }
    }

    fun getPackageName(typeElement: TypeElement): String =
        elementUtils.getPackageOf(typeElement).qualifiedName.toString()

    fun getNameWithoutPackage(typeElement: TypeElement): String =
        getNameWithoutPackage(getPackageName(typeElement), typeElement.qualifiedName.toString())

    fun getGeneratedDataClass(typeElement: TypeElement): ClassName {
        val generatedDataClassName = getGeneratedDataClassName(getNameWithoutPackage(typeElement))
        val parts = listOf(getPackageName(typeElement), generatedDataClassName)
        val classNameString = parts.filter(String::isNotEmpty).joinToString(PACKAGE_DELIMITER)
        return ClassName.bestGuess(classNameString)
    }

    val generatedSourcesDir: File = File(requireNotNull(processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]))

    private val typeUtils: Types = processingEnv.typeUtils
    private val elementUtils: Elements = processingEnv.elementUtils

    private data class ElementInfo(
        val typeElement: TypeElement,
        val elementKmClass: KmClass,
        val referringDeclaration: KmType? = null,
    )

    // Interfaces are returned "top down", i.e. ancestors first.
    private fun collectInterfaces(typeElement: TypeElement, referringDeclaration: KmType? = null): List<ElementInfo> {
        val elementKmClass = typeElement.toKmClass()
        val declarationsByName = elementKmClass
            .supertypes
            .associateBy { supertype -> checkNotNull(supertype.jvmName) }
        val interfaces = typeElement
            .interfaces
            .map(typeUtils::asElement)
            .filterIsInstance<TypeElement>()
            .flatMap { element ->
                val declaration = checkNotNull(declarationsByName[element.qualifiedName.toString()]) {
                    "Missing declaration for interface $element"
                }
                collectInterfaces(element, declaration)
            }
        return interfaces + ElementInfo(typeElement, elementKmClass, referringDeclaration)
    }

    private val fragmentType: TypeMirror
        get() = elementUtils
            .getTypeElement(requireNotNull(DataClassFragment::class.qualifiedName))
            .asType()

    companion object {
        private const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"

        private val namedColumnSubtypes = listOf(
            PropertyWithNullability::class.qualifiedName,
            NestedPropertyWithNullability::class.qualifiedName,
            ArrayPropertyWithNullability::class.qualifiedName,
            MapPropertyWithNullability::class.qualifiedName,
        )
    }
}

private fun ExecutableElement.toPropertyName(): String? =
    PROPERTY_GETTER_METHOD_REGEX
        .matchEntire(simpleName)
        ?.groupValues
        ?.get(1)
        ?.replaceFirstChar(Char::lowercase)

private val PROPERTY_GETTER_METHOD_REGEX = "(?:get)?([A-Za-z_].*)".toRegex()

interface NamedColumnTypeVisitor<T> {
    fun visitColumn(): T
    fun visitNestedColumn(): T
    fun visitArrayColumn(): T
    fun visitMapColumn(): T
}

fun <T> ParameterizedTypeName.accept(visitor: NamedColumnTypeVisitor<T>): T =
    when (rawType.canonicalName) {
        PropertyWithNullability::class.qualifiedName -> visitor.visitColumn()
        NestedPropertyWithNullability::class.qualifiedName -> visitor.visitNestedColumn()
        ArrayPropertyWithNullability::class.qualifiedName -> visitor.visitArrayColumn()
        MapPropertyWithNullability::class.qualifiedName -> visitor.visitMapColumn()
        else -> throw UnsupportedOperationException()
    }

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

    val column = PropertyWithNullability::class.asClassName()
    val nestedColumn = NestedPropertyWithNullability::class.asClassName()
    val arrayColumn = ArrayPropertyWithNullability::class.asClassName()
    val mapColumn = MapPropertyWithNullability::class.asClassName()

    val nullable = Nullability.Nullable::class.asClassName()

    val builder = Builder::class.asClassName()
    val iterableBuilder = IterableBuilder::class.asClassName()
    val scalarIterableBuilder = ScalarIterableBuilder::class.asClassName()
    val mapBuilder = MapBuilder::class.asClassName()
    val scalarMapBuilder = ScalarMapBuilder::class.asClassName()
    val builderFunctions = BuilderFunctions::class.asClassName()
}

fun KmType.asTypeName(typeArguments: List<KmType> = emptyList()): TypeName {
    fun KmType.asTypeNameForResolvedType(typeArguments: List<KmType>): TypeName {
        val retType = classifier as KmClassifier.Class
        val typeArgs = arguments.mapNotNull { argument ->
            if (argument == KmTypeProjection.STAR) STAR else argument.type?.asTypeName(typeArguments)
        }
        val className = ClassName.bestGuess(getJvmName(retType.name))
        return if (typeArgs.isNotEmpty()) {
            className.parameterizedBy(typeArgs)
        } else {
            className
        }
    }

    return when (val c = classifier) {
        is KmClassifier.Class -> asTypeNameForResolvedType(typeArguments)
        is KmClassifier.TypeParameter -> typeArguments[c.id].asTypeName(typeArguments)
        // This case appears only when dereferencing KmType.abbreviatedType, so this should be unreachable
        is KmClassifier.TypeAlias -> throw UnsupportedOperationException("$c is not supported")
    }
}

data class KmPropertyInfo(val property: KmProperty, val returnType: ParameterizedTypeName)

fun TypeElement.toClassName(): ClassName = ClassName.bestGuess(qualifiedName.toString())

val ParameterizedTypeName.hasNullableTypeArgument: Boolean
    get() = typeArguments[1] == ClassNames.nullable

fun getGeneratedDataClassName(fragmentNameWithoutPackage: String): String =
    "${fragmentNameWithoutPackage.replace('.', '_')}Data"

val ClassName.nameWithoutPackage: String
    get() = getNameWithoutPackage(packageName, canonicalName)

val ParameterizedTypeName.iterableType: ClassName?
    get() = (typeArguments.getOrNull(2) as? ParameterizedTypeName)?.rawType

val ParameterizedTypeName.isScalarColumn: Boolean get() = rawType == ClassNames.column
val ClassName.generatedDataClass: ClassName
    get() {
        val generatedDataClassName = getGeneratedDataClassName(nameWithoutPackage)
        val parts = listOf(packageName, generatedDataClassName)
        val classNameString = parts.filter(String::isNotEmpty).joinToString(PACKAGE_DELIMITER)
        return ClassName.bestGuess(classNameString)
    }

fun Element.collectEnclosedInterfaces(): List<Element> =
    (listOf(this) + enclosedElements.flatMap { it.collectEnclosedInterfaces() })
        .filter { typeElement -> typeElement.kind == ElementKind.INTERFACE }

val KmType.jvmName: String? get() = (classifier as? KmClassifier.Class)?.name?.let(::getJvmName)

private fun getJvmName(kmClassifierName: String): String = kmClassifierName.replace('/', '.')
