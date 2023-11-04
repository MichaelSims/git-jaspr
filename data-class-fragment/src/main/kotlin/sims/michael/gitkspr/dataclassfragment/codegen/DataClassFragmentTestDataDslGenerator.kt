package sims.michael.gitkspr.dataclassfragment.codegen

import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.metadata.KmProperty
import kotlinx.metadata.jvm.syntheticMethodForAnnotations
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.dataclassfragment.GenerateDataClassFragmentDataClass
import java.io.File
import java.time.Instant
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.ElementKind
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

@Suppress("unused")
@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_11)
class DataClassFragmentTestDataDslGenerator : AbstractProcessor() {

    private val processorUtilities by lazy { DataClassFragmentProcessorUtilities(processingEnv) }

    @Suppress("unused")
    private val logger = LoggerFactory.getLogger(DataClassFragmentTestDataDslGenerator::class.java)

    override fun getSupportedAnnotationTypes(): Set<String> = setOf(GenerateDataClassFragmentDataClass::class.java.name)

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val fragments = processorUtilities
            .collectFragments(
                roundEnv
                    .getElementsAnnotatedWith(GenerateDataClassFragmentDataClass::class.java),
            )

        for (fragment in fragments) {
            val namedColumnProperties = processorUtilities.getNamedColumnProperties(fragment)
            if (checkArrayColumnsHaveOnlySupportedIterableTypes(fragment, namedColumnProperties)) {
                generateCodeForDataClassFragment(
                    fragment,
                    namedColumnProperties,
                    processorUtilities.generatedSourcesDir,
                )
            }
        }

        // Don't "claim" these elements since the data class generator also needs to process them
        return false
    }

    private fun generateCodeForDataClassFragment(
        fragment: TypeElement,
        namedColumnProperties: List<KmPropertyInfo>,
        generatedSourcesDir: File,
    ) {
        FileSpec
            .builder(
                addDslPackageSuffix(processorUtilities.getPackageName(fragment)),
                fragment.generatedBuilder.nameWithoutPackage,
            )
            .indent(INDENT)
            .addFunction(buildFactoryFunctionSpec(fragment))
            .addType(buildTestModelBuilderClassSpec(fragment, namedColumnProperties))
            .build()
            .writeTo(generatedSourcesDir)
    }

    /**
     * Builds the FunSpec for the main factory function for the given DataClassFragment. This is the "entry point"
     * into the DSL.
     *
     * Example:
     * ```
     * public fun foo(fn: FooDataBuilder<FooData>.() -> Unit): FooData =
     *     FooDataBuilder(ignoringIsNull(FooDataBuilder<FooData>::doBuild))
     *     .apply(fn)
     *     .build()
     * ```
     */
    private fun buildFactoryFunctionSpec(fragment: TypeElement): FunSpec {
        val generatedBuilderWithTypeArg = fragment
            .generatedBuilder
            .parameterizedBy(processorUtilities.getGeneratedDataClass(fragment))
        val lambdaType = LambdaTypeName.get(generatedBuilderWithTypeArg, returnType = ClassNames.unit)
        return FunSpec
            .builder(fragment.generatedBuilderDslFunctionName)
            .addParameter(ParameterSpec.builder("fn", lambdaType).build())
            .returns(processorUtilities.getGeneratedDataClass(fragment))
            .addCode(
                "return %T(%M(%T::doBuild)).apply(fn).build()",
                fragment.generatedBuilder,
                MemberNames.ignoringIsNull,
                generatedBuilderWithTypeArg,
            )
            .addKdoc(
                "Creates an instance of [%T] (generated from [%T])",
                processorUtilities.getGeneratedDataClass(fragment),
                fragment.toClassName(),
            )
            .build()
    }

    /**
     * Builds the TypeSpec for the model builder class for the given DataClassFragment.
     *
     * Example:
     * ```
     * class FooBuilder<T : Foo?>(private val build: FooBuilder<T>.(Boolean) -> T) : Builder<T> {
     *     public var name: String? = ""
     *
     *     public override var isNull: Boolean = false
     *
     *     public override fun build(): T = build(isNull)
     *
     *     public fun doBuild(): Foo = Foo(name, ...)
     *
     *     public override fun from(prototype: T) {
     *         ...
     *     }
     *
     *     public operator fun invoke(fn: FooBuilder<T>.() -> Unit) {
     *         apply(fn)
     *     }
     * }
     * ```
     */
    private fun buildTestModelBuilderClassSpec(
        fragment: TypeElement,
        namedColumnProperties: List<KmPropertyInfo>,
    ): TypeSpec {
        val builderClassSpecBuilder = TypeSpec.classBuilder(fragment.generatedBuilder)

        val typeVarT = TypeVariableName(
            name = "T",
            bounds = arrayOf(processorUtilities.getGeneratedDataClass(fragment).copy(nullable = true)),
        )

        builderClassSpecBuilder
            .addTypeVariable(typeVarT)
            .addSuperinterface(ClassNames.builder.parameterizedBy(typeVarT))

        val buildFnName = "build"
        val buildFnType = LambdaTypeName.get(
            fragment.generatedBuilder.parameterizedBy(typeVarT),
            ClassNames.boolean,
            returnType = typeVarT,
        )

        val primaryConstructor = FunSpec.constructorBuilder().addParameter(buildFnName, buildFnType).build()
        builderClassSpecBuilder.primaryConstructor(primaryConstructor)

        builderClassSpecBuilder.addProperty(
            PropertySpec
                .builder(buildFnName, buildFnType)
                .addModifiers(KModifier.PRIVATE)
                .initializer(buildFnName)
                .build(),
        )

        val propertyAndAliases = namedColumnProperties
            .map { propertyInfo -> AliasedKmProperty(propertyInfo, findAlias(fragment, propertyInfo.property)) }

        for ((propertyInfo, propertyAlias) in propertyAndAliases) {
            val (namedColumnProperty, returnType) = propertyInfo
            val builderType = returnType.builderType()
            val propSpec = PropertySpec
                .builder(propertyAlias, builderType)
                .mutable(returnType.isScalarColumn)
                .initializer(builderType.buildInitializer())
                .addKdoc(
                    "Builder property for [%T.%L] (derived from [%T.%L])",
                    processorUtilities.getGeneratedDataClass(fragment),
                    namedColumnProperty.name,
                    fragment.toClassName(),
                    namedColumnProperty.name,
                )
                .build()
            builderClassSpecBuilder.addProperty(propSpec)
        }

        builderClassSpecBuilder.addProperty(
            PropertySpec
                .builder("isNull", Boolean::class)
                .addModifiers(KModifier.OVERRIDE)
                .mutable(true)
                .initializer("false")
                .build(),
        )

        builderClassSpecBuilder.addFunction(
            FunSpec
                .builder("build")
                .addModifiers(KModifier.OVERRIDE)
                .returns(typeVarT)
                .addCode("return %L(isNull)", buildFnName)
                .build(),
        )

        builderClassSpecBuilder.addFunction(buildTestModelBuilderBuildFunction(fragment, propertyAndAliases))

        builderClassSpecBuilder.addFunction(buildFromFunction(typeVarT, propertyAndAliases))

        builderClassSpecBuilder.addFunction(buildInvokeFunctionSpec(fragment, typeVarT))
        return builderClassSpecBuilder.build()
    }

    /**
     * For a given DataClassFragment property declared type, return the type that should be used for the corresponding
     * test data DSL builder property.
     */
    private fun TypeName.builderType(): TypeName {
        val namedColumnType = this as ParameterizedTypeName
        val firstTypeArg = typeArguments.first()
        return with(ClassNames) {
            accept(object : NamedColumnTypeVisitor<TypeName> {
                // Examples:
                //   ColumnWithNullability<String, NotNull> -> String
                //   ColumnWithNullability<String, Nullable> -> String?
                override fun visitColumn(): TypeName {
                    val columnType = typeArguments.first()
                    return columnType.copy(nullable = namedColumnType.hasNullableTypeArgument)
                }

                // Examples:
                //   NestedColumnWithNullability<Egg, NotNull> -> EggDataBuilder<EggData>
                //   NestedColumnWithNullability<Egg, Nullable> -> EggDataBuilder<EggData?>
                override fun visitNestedColumn(): ParameterizedTypeName {
                    val nestedFragment = firstTypeArg as ClassName
                    val generatedBuilder = nestedFragment.generatedBuilder
                    val generatedDataClass = nestedFragment.generatedDataClass
                    return generatedBuilder
                        .parameterizedBy(generatedDataClass.copy(nullable = namedColumnType.hasNullableTypeArgument))
                }

                override fun visitArrayColumn(): ParameterizedTypeName {
                    val iterableType = checkNotNull(namedColumnType.iterableType)
                    val arrayElementType = firstTypeArg as ParameterizedTypeName
                    return if (arrayElementType.isScalarColumn) {
                        val arrayElementBuilderType = arrayElementType.builderType()

                        // Examples:
                        //   ArrayColumnWithNullability<ColumnWithNullability<String, NotNull>, NotNull, List<*>> ->
                        //     ScalarIterableBuilder<List<String>, String>
                        //   ArrayColumnWithNullability<ColumnWithNullability<String, Nullable>, NotNull, List<*>> ->
                        //     ScalarIterableBuilder<List<String?>, String?>
                        //   ArrayColumnWithNullability<ColumnWithNullability<String, NotNull>, Nullable, List<*>> ->
                        //     ScalarIterableBuilder<List<String>?, String>
                        //   ArrayColumnWithNullability<ColumnWithNullability<String, Nullable>, Nullable, List<*>> ->
                        //     ScalarIterableBuilder<List<String?>?, String?>
                        scalarIterableBuilder
                            .parameterizedBy(
                                iterableType
                                    .parameterizedBy(arrayElementBuilderType)
                                    .copy(nullable = namedColumnType.hasNullableTypeArgument),
                                arrayElementBuilderType,
                            )
                    } else {
                        val arrayElementBuilderType = arrayElementType.builderType() as ParameterizedTypeName
                        val whatItBuilds = arrayElementBuilderType.typeArguments.first()

                        // Examples:
                        //   ArrayColumnWithNullability<NestedColumnWithNullability<Egg, NotNull>, NotNull, List<*>> ->
                        //     IterableBuilder<List<EggData>, EggData, EggDataBuilder<EggData>>
                        //   ArrayColumnWithNullability<NestedColumnWithNullability<Egg, Nullable>, NotNull, List<*>> ->
                        //     IterableBuilder<List<EggData?>, EggData?, EggDataBuilder<EggData?>>
                        //   ArrayColumnWithNullability<NestedColumnWithNullability<Egg, NotNull>, Nullable, List<*>> ->
                        //     IterableBuilder<List<EggData>?, EggData, EggDataBuilder<EggData>>
                        //   ArrayColumnWithNullability<NestedColumnWithNullability<Egg, Nullable>, Nullable, List<*>> ->
                        //     IterableBuilder<List<EggData?>?, EggData?, EggDataBuilder<EggData?>>
                        iterableBuilder
                            .parameterizedBy(
                                iterableType
                                    .parameterizedBy(whatItBuilds)
                                    .copy(nullable = namedColumnType.hasNullableTypeArgument),
                                whatItBuilds,
                                arrayElementBuilderType,
                            )
                    }
                }

                override fun visitMapColumn(): ParameterizedTypeName {
                    val mapValueType = firstTypeArg as ParameterizedTypeName
                    return if (mapValueType.isScalarColumn) {
                        val mapValueBuilderType = mapValueType.builderType()

                        // Examples:
                        //   MapColumnWithNullability<ColumnWithNullability<String, NotNull>, NotNull> ->
                        //     ScalarMapBuilder<Map<String, String>, String>
                        //   MapColumnWithNullability<ColumnWithNullability<String, Nullable>, NotNull> ->
                        //     ScalarMapBuilder<Map<String, String?>, String?>
                        //   MapColumnWithNullability<ColumnWithNullability<String, NotNull>, Nullable> ->
                        //     ScalarMapBuilder<Map<String, String>?, String>
                        //   MapColumnWithNullability<ColumnWithNullability<String, Nullable>, Nullable> ->
                        //     ScalarMapBuilder<Map<String, String?>?, String?>
                        scalarMapBuilder
                            .parameterizedBy(
                                map
                                    .parameterizedBy(string, mapValueBuilderType)
                                    .copy(nullable = namedColumnType.hasNullableTypeArgument),
                                mapValueBuilderType,
                            )
                    } else {
                        val mapValueBuilderType = mapValueType.builderType() as ParameterizedTypeName
                        val whatItBuilds = mapValueBuilderType.typeArguments.first()

                        // Examples:
                        //   MapColumnWithNullability<NestedColumnWithNullability<Egg, NotNull>, NotNull> ->
                        //     MapBuilder<Map<String, EggData>, EggData, EggDataBuilder<EggData>>
                        //   MapColumnWithNullability<NestedColumnWithNullability<Egg, Nullable>, NotNull> ->
                        //     MapBuilder<Map<String, EggData?>, EggData?, EggDataBuilder<EggData?>>
                        //   MapColumnWithNullability<NestedColumnWithNullability<Egg, NotNull>, Nullable> ->
                        //     MapBuilder<Map<String, EggData>?, EggData, EggDataBuilder<EggData>>
                        //   MapColumnWithNullability<NestedColumnWithNullability<Egg, Nullable>, Nullable> ->
                        //     MapBuilder<Map<String, EggData?>?, EggData?, EggDataBuilder<EggData?>>
                        mapBuilder
                            .parameterizedBy(
                                map
                                    .parameterizedBy(string, whatItBuilds)
                                    .copy(nullable = namedColumnType.hasNullableTypeArgument),
                                whatItBuilds,
                                mapValueBuilderType,
                            )
                    }
                }
            })
        }
    }

    /**
     * For a given test data DSL builder property type, return an expression to initialize the property.
     *
     * Note that the receiver type is the *builder* property type, not the original DataClassFragment property type.
     */
    private fun TypeName.buildInitializer(): CodeBlock {
        val typeName = this
        return buildCodeBlock {
            with(ClassNames) {
                when (typeName) {
                    is ClassName -> {
                        if (typeName.isNullable) {
                            add("null")
                        } else {
                            when (typeName) {
                                string -> add("%S", "")
                                short, int, byte -> add("0")
                                long -> add("0L")
                                double -> add("0.0")
                                float -> add("0.0f")
                                boolean -> add("false")
                                bigDecimal, bigInt -> add("%T(%S)", typeName, "0")
                                instant -> add("%M", MemberName(instant, Instant::EPOCH.name))
                                localDate -> add("%M(1970, 1, 1)", MemberName(localDate, "of"))
                                else -> add("%T()", typeName)
                            }
                        }
                    }
                    is ParameterizedTypeName -> add(
                        when (typeName.rawType) {
                            iterableBuilder, mapBuilder -> typeName.buildCollectionBuilderInitializer()
                            scalarIterableBuilder, scalarMapBuilder -> typeName.buildScalarCollectionBuilderInitializer()
                            else -> typeName.buildGeneratedBuilderInitializer()
                        },
                    )
                    else -> throw IllegalStateException("Unexpected type name $typeName")
                }
            }
        }
    }

    /**
     * Builds an initializer expression for the given receiver parameterized type. Receiver should be one of
     * [IterableBuilder] or [MapBuilder]
     *
     * Examples:
     * ```
     * IterableBuilder<List<EggData>, EggData, EggDataBuilder<EggData>> ->
     *   IterableBuilder(createBuilder = {...},build = ignoringIsNull(::buildList))
     * MapBuilder<Map<String, EggData>, EggData, EggDataBuilder<EggData>> ->
     *   MapBuilder(createBuilder = {...},build = ignoringIsNull(::buildMap))
     *
     * ```
     */
    @Suppress("DuplicatedCode")
    private fun ParameterizedTypeName.buildCollectionBuilderInitializer() = buildCodeBlock {
        add("%T(", rawType)
        add("createBuilder·=·{")
        add(builderType.buildInitializer())
        add("},")
        add("build = ")
        add("%M(", typeArguments.first().nullabilityWrapperFunction)
        add(
            "::%M",
            with(MemberNames) {
                collectionBuilderIterableType.accept(
                    object : CollectionTypeVisitor<MemberName> {
                        override fun visitList() = buildList
                        override fun visitSet() = buildSet
                        override fun visitQueue() = buildQueue
                        override fun visitMap() = buildMap
                    },
                )
            },
        )
        add(")")
        add(")")
    }

    /**
     * Builds an initializer expression for the given receiver parameterized type. Receiver should be one of
     * [ScalarIterableBuilder] or [ScalarMapBuilder]
     *
     * Examples:
     * ```
     * ScalarIterableBuilder<List<String>, String> -> ScalarIterableBuilder(build = ignoringIsNull(::buildScalarList))
     * ScalarMapBuilder<Map<String, String>, String> -> ScalarMapBuilder(build = ignoringIsNull(::identity))
     *
     * ```
     */
    @Suppress("DuplicatedCode")
    private fun ParameterizedTypeName.buildScalarCollectionBuilderInitializer() = buildCodeBlock {
        add("%T(", rawType)
        add("build = ")
        add("%M(", typeArguments.first().nullabilityWrapperFunction)
        add(
            "::%M",
            with(MemberNames) {
                collectionBuilderIterableType.accept(
                    object : CollectionTypeVisitor<MemberName> {
                        override fun visitList() = buildScalarList
                        override fun visitSet() = buildScalarSet
                        override fun visitQueue() = buildScalarQueue
                        override fun visitMap() = identity
                    },
                )
            },
        )
        add(")")
        add(")")
    }

    /**
     * Builds an initializer expression for the given receiver parameterized type. Receiver should be a generated
     * model builder.
     *
     * Example:
     * ```
     * EggDataBuilder<EggData> -> EggDataBuilder<EggData>(ignoringIsNull(EggDataBuilder<EggData>::doBuild))
     *
     * ```
     */
    private fun ParameterizedTypeName.buildGeneratedBuilderInitializer(): CodeBlock {
        val typeName = this
        return buildCodeBlock {
            add("%T(", typeName)
            add("%M(", typeArguments.first().nullabilityWrapperFunction)
            add("%T::doBuild", typeName)
            add(")")
            add(")")
        }
    }

    /**
     * Builds the "doBuild" function of the builder, which actually creates the data model class.
     *
     * Example:
     * ```
     * public fun doBuild(): FooData =
     *     FooData(
     *         someString,
     *         someNested.build(),
     *         someList.build(),
     *         ...
     *     )
     * ````
     */
    private fun buildTestModelBuilderBuildFunction(
        fragment: TypeElement,
        propertyAndAliases: List<AliasedKmProperty>,
    ) = FunSpec
        .builder("doBuild")
        .returns(processorUtilities.getGeneratedDataClass(fragment))
        .addCode(
            buildCodeBlock {
                add("return %T(", processorUtilities.getGeneratedDataClass(fragment))

                for ((propertyInfo, propertyAlias) in propertyAndAliases) {
                    add(if (propertyInfo.returnType.isScalarColumn) "%L" else "%L.build()", propertyAlias)
                    add(",")
                }

                add(")")
            },
        )
        .build()

    /**
     * Builds the "from" function that accepts a prototype instance of the data class and initializers the builder
     * with its values.
     *
     * Example:
     * ```
     * public override fun from(prototype: T) {
     *     if (prototype == null) {
     *         isNull = true
     *     } else {
     *         nullableStrings.from(prototype.nullableStrings)
     *         ...
     *         egg.from(prototype.eggs)
     *     }
     * }
     * ```
     */
    private fun buildFromFunction(
        typeVarT: TypeVariableName,
        propertyAndAliases: List<AliasedKmProperty>,
    ) = FunSpec
        .builder("from")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("prototype", typeVarT)
        .addCode(
            buildCodeBlock {
                add("if·(prototype·==·null)·{\n")
                add("····isNull·=·true\n")
                add("} else {\n")
                for ((propertyInfo, propertyAlias) in propertyAndAliases) {
                    val (property, returnType) = propertyInfo
                    if (returnType.isScalarColumn) {
                        add("····%L·=·prototype.%L\n", propertyAlias, property.name)
                    } else {
                        add("····%L.from(prototype.%L)\n", propertyAlias, property.name)
                    }
                }
                add("}\n")
            },
        )
        .returns(ClassNames.unit)
        .build()

    /**
     * Builds the "invoke" function of the builder.
     *
     * Example:
     * ```
     * public operator fun invoke(fn: FooBuilder<T>.() -> Unit) {
     *     apply(fn)
     * }
     * ```
     */
    private fun buildInvokeFunctionSpec(fragment: TypeElement, typeVarT: TypeName): FunSpec {
        return FunSpec
            .builder("invoke")
            .addParameter(
                ParameterSpec
                    .builder(
                        "fn",
                        LambdaTypeName.get(
                            fragment.generatedBuilder.parameterizedBy(typeVarT),
                            returnType = ClassNames.unit,
                        ),
                    )
                    .build(),
            )
            .addModifiers(KModifier.OPERATOR)
            .addCode("apply(fn)")
            .build()
    }

    private interface CollectionTypeVisitor<T> {
        fun visitList(): T
        fun visitSet(): T
        fun visitQueue(): T
        fun visitMap(): T
    }

    private fun <T> ClassName.accept(visitor: CollectionTypeVisitor<T>): T = when (this) {
        ClassNames.list -> visitor.visitList()
        ClassNames.set -> visitor.visitSet()
        ClassNames.queue -> visitor.visitQueue()
        ClassNames.map -> visitor.visitMap()
        else -> throw UnsupportedOperationException("Collection type $this is not supported")
    }

    private val ParameterizedTypeName.builderType: TypeName
        get() = typeArguments[2]

    private val TypeName.nullabilityWrapperFunction: MemberName
        get() = if (isNullable) MemberNames.ifNotNull else MemberNames.ignoringIsNull

    private val ParameterizedTypeName.collectionBuilderIterableType: ClassName
        get() = (typeArguments.first().copy(nullable = false) as ParameterizedTypeName).rawType

    private object MemberNames {
        val ignoringIsNull = MemberName(ClassNames.builderFunctions, "ignoringIsNull")
        val ifNotNull = MemberName(ClassNames.builderFunctions, "ifNotNull")
        val identity = MemberName(ClassNames.builderFunctions, "identity")
        val buildScalarList = MemberName(ClassNames.builderFunctions, "buildScalarList")
        val buildScalarQueue = MemberName(ClassNames.builderFunctions, "buildScalarQueue")
        val buildScalarSet = MemberName(ClassNames.builderFunctions, "buildScalarSet")
        val buildMap = MemberName(ClassNames.builderFunctions, "buildMap")
        val buildList = MemberName(ClassNames.builderFunctions, "buildList")
        val buildQueue = MemberName(ClassNames.builderFunctions, "buildQueue")
        val buildSet = MemberName(ClassNames.builderFunctions, "buildSet")
    }

    private val TypeElement.generatedBuilderDslFunctionName: String
        get() {
            val overrideName = getAnnotation(GenerateDataClassFragmentDataClass::class.java).testDataDslFactoryFunctionName
            return getGeneratedTestBuilderDslFunctionName(
                if (overrideName.isBlank()) {
                    processorUtilities.getNameWithoutPackage(this)
                } else {
                    val nameParts = processorUtilities
                        .getNameWithoutPackage(this)
                        .split(PACKAGE_DELIMITER)
                        .filterNot(String::isBlank)
                        .dropLast(1) + overrideName
                    nameParts.joinToString(PACKAGE_DELIMITER)
                },
            )
        }

    private fun getGeneratedTestBuilderName(fragmentNameWithoutPackage: String) =
        "${getGeneratedDataClassName(fragmentNameWithoutPackage)}Builder"

    private fun getGeneratedTestBuilderDslFunctionName(fragmentNameWithoutPackage: String) =
        fragmentNameWithoutPackage.replace('.', '_').replaceFirstChar(Char::lowercase)

    private val TypeElement.generatedBuilder: ClassName
        get() {
            val generatedBuilderName = getGeneratedTestBuilderName(processorUtilities.getNameWithoutPackage(this))
            val parts = listOf(addDslPackageSuffix(processorUtilities.getPackageName(this)), generatedBuilderName)
            val classNameString = parts.filter(String::isNotEmpty).joinToString(PACKAGE_DELIMITER)
            return ClassName.bestGuess(classNameString)
        }

    private val ClassName.generatedBuilder: ClassName
        get() {
            val generatedImplementationName = getGeneratedTestBuilderName(nameWithoutPackage)
            val parts = listOf(addDslPackageSuffix(packageName), generatedImplementationName)
            val classNameString = parts.filter(String::isNotEmpty).joinToString(PACKAGE_DELIMITER)
            return ClassName.bestGuess(classNameString)
        }

    private fun addDslPackageSuffix(prefix: String): String = listOf(prefix, TEST_DSL_PACKAGE_SUFFIX)
        .filter(String::isNotEmpty)
        .joinToString(PACKAGE_DELIMITER)

    private data class AliasedKmProperty(val propertyInfo: KmPropertyInfo, val alias: String)

    private fun TypeElement.findDslNameAnnotation(property: KmProperty): GenerateDataClassFragmentDataClass.TestDataDslName? {
        val elementName = property.syntheticMethodForAnnotations?.name ?: return null
        return enclosedElements
            .find { it.kind == ElementKind.CLASS && it.simpleName.toString() == "DefaultImpls" }
            ?.enclosedElements
            ?.first { it.simpleName.toString() == elementName }
            ?.getAnnotation(GenerateDataClassFragmentDataClass.TestDataDslName::class.java)
    }

    private fun findAlias(fragment: TypeElement, property: KmProperty): String =
        fragment.findDslNameAnnotation(property)?.name?.takeUnless(String::isBlank) ?: property.name

    private fun ParameterizedTypeName.collectParameterizedTypes(): List<ParameterizedTypeName> =
        listOf(this) + (
            typeArguments
                .filterIsInstance<ParameterizedTypeName>()
                .flatMap { it.collectParameterizedTypes() }
            )

    private fun checkArrayColumnsHaveOnlySupportedIterableTypes(
        fragment: TypeElement,
        namedColumnProperties: List<KmPropertyInfo>,
    ): Boolean {
        val propertiesWithUnsupportedTypes = with(ClassNames) {
            namedColumnProperties
                .filter { (_, returnType) ->
                    returnType
                        .collectParameterizedTypes()
                        .any { type -> type.rawType == arrayColumn && type.iterableType !in listOf(list, set, queue) }
                }
        }

        return if (propertiesWithUnsupportedTypes.isNotEmpty()) {
            val message = "${this::class.simpleName} cannot generate a test DSL for $fragment " +
                "which has array columns that map to unsupported iterable types " +
                "(only List and Set are supported): " +
                propertiesWithUnsupportedTypes.toString()
            processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, message, fragment)
            false
        } else {
            true
        }
    }

    companion object {
        private val INDENT = " ".repeat(4)
        private const val PACKAGE_DELIMITER = "."
        const val TEST_DSL_PACKAGE_SUFFIX = "generatedtestdsl"
    }
}
