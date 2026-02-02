package sims.michael.gitjaspr.dataclassfragment.codegen

import com.google.auto.service.AutoService
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import java.time.Instant
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.dataclassfragment.GenerateDataClassFragmentDataClass

@Suppress("unused")
@AutoService(SymbolProcessorProvider::class)
class DataClassFragmentTestDataDslGeneratorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        context(environment) { DataClassFragmentTestDataDslGenerator(environment) }
}

@Suppress("unused")
@OptIn(KspExperimental::class)
class DataClassFragmentTestDataDslGenerator(private val environment: SymbolProcessorEnvironment) :
    SymbolProcessor {

    @Suppress("unused")
    private val logger = LoggerFactory.getLogger(DataClassFragmentTestDataDslGenerator::class.java)

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        context(environment) {
            context(resolver) {
                resolver.collectFragments().forEach {
                    it.process {
                        generateTestDataDslSpec()
                            .writeTo(
                                environment.codeGenerator,
                                false,
                                listOfNotNull(classDeclaration.containingFile),
                            )
                    }
                }
            }
        }

        return emptyList()
    }

    context(_: Resolver)
    private fun DataClassFragmentDescriptor.generateTestDataDslSpec(): FileSpec =
        FileSpec.builder(generatedTestDataDslClassName)
            .indent(INDENT)
            .addFunction(buildFactoryFunctionSpec())
            .addType(buildTestModelBuilderClassSpec())
            .build()

    private val KSClassDeclaration.generatedBuilder: ClassName
        get() {
            val generatedBuilderName = getGeneratedTestBuilderName(toClassName().nameWithoutPackage)
            val parts = listOf(addDslPackageSuffix(packageName.asString()), generatedBuilderName)
            val classNameString = parts.filter(String::isNotEmpty).joinToString(PACKAGE_DELIMITER)
            return ClassName.bestGuess(classNameString)
        }

    /**
     * Builds the FunSpec for the main factory function for the given DataClassFragment. This is the
     * "entry point" into the DSL.
     *
     * Example:
     * ```
     * public fun foo(fn: FooDataBuilder<FooData>.() -> Unit): FooData =
     *     FooDataBuilder(ignoringIsNull(FooDataBuilder<FooData>::doBuild))
     *     .apply(fn)
     *     .build()
     * ```
     */
    context(_: Resolver)
    private fun DataClassFragmentDescriptor.buildFactoryFunctionSpec(): FunSpec {
        val generatedBuilderWithTypeArg =
            classDeclaration.generatedBuilder.parameterizedBy(generatedDataClassName)
        val lambdaType =
            LambdaTypeName.get(generatedBuilderWithTypeArg, returnType = ClassNames.unit)
        return FunSpec.builder(classDeclaration.generatedBuilderDslFunctionName)
            .addParameter(ParameterSpec.builder("fn", lambdaType).build())
            .returns(generatedDataClassName)
            .addCode(
                "return %T(%M(%T::doBuild)).apply(fn).build()",
                classDeclaration.generatedBuilder,
                MemberNames.ignoringIsNull,
                generatedBuilderWithTypeArg,
            )
            .addKdoc(
                "Creates an instance of [%T] (generated from [%T])",
                generatedDataClassName,
                classDeclaration.toClassName(),
            )
            .build()
    }

    @OptIn(KspExperimental::class)
    private val KSClassDeclaration.generatedBuilderDslFunctionName: String
        get() {
            val overrideName =
                getAnnotationsByType(GenerateDataClassFragmentDataClass::class)
                    .first()
                    .testDataDslFactoryFunctionName
            return getGeneratedTestBuilderDslFunctionName(
                if (overrideName.isBlank()) {
                    toClassName().nameWithoutPackage
                } else {
                    val nameParts =
                        toClassName()
                            .nameWithoutPackage
                            .split(PACKAGE_DELIMITER)
                            .filterNot(String::isBlank)
                            .dropLast(1) + overrideName
                    nameParts.joinToString(PACKAGE_DELIMITER)
                }
            )
        }

    /**
     * Builds the TypeSpec for the model builder class for the given SchemaFragment.
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
    context(_: Resolver)
    private fun DataClassFragmentDescriptor.buildTestModelBuilderClassSpec(): TypeSpec {
        val builderClassSpecBuilder = TypeSpec.classBuilder(classDeclaration.generatedBuilder)

        val typeVarT =
            TypeVariableName(
                name = "T",
                bounds = arrayOf(generatedDataClassName.copy(nullable = true)),
            )

        builderClassSpecBuilder
            .addTypeVariable(typeVarT)
            .addSuperinterface(ClassNames.builder.parameterizedBy(typeVarT))

        val buildFnName = "build"
        val buildFnType =
            LambdaTypeName.get(
                classDeclaration.generatedBuilder.parameterizedBy(typeVarT),
                ClassNames.boolean,
                returnType = typeVarT,
            )

        val primaryConstructor =
            FunSpec.constructorBuilder().addParameter(buildFnName, buildFnType).build()
        builderClassSpecBuilder.primaryConstructor(primaryConstructor)

        builderClassSpecBuilder.addProperty(
            PropertySpec.builder(buildFnName, buildFnType)
                .addModifiers(KModifier.PRIVATE)
                .initializer(buildFnName)
                .build()
        )

        context(environment) {
            forEachProperty {
                val builderType = propertyType.builderType()
                val propSpec =
                    PropertySpec.builder(propertyNameAlias(), builderType)
                        .mutable(isScalarProperty())
                        .initializer(builderType.buildInitializer())
                        .addKdoc(
                            "Builder property for [%T.%L] (derived from [%T.%L])",
                            generatedDataClassName,
                            propertyName,
                            classDeclaration.toClassName(),
                            propertyName,
                        )
                        .build()
                builderClassSpecBuilder.addProperty(propSpec)
            }
        }

        builderClassSpecBuilder.addProperty(
            PropertySpec.builder("isNull", Boolean::class)
                .addModifiers(KModifier.OVERRIDE)
                .mutable(true)
                .initializer("false")
                .build()
        )

        builderClassSpecBuilder.addFunction(
            FunSpec.builder("build")
                .addModifiers(KModifier.OVERRIDE)
                .returns(typeVarT)
                .addCode("return %L(isNull)", buildFnName)
                .build()
        )

        builderClassSpecBuilder.addFunction(buildTestModelBuilderBuildFunction())

        builderClassSpecBuilder.addFunction(buildFromFunction(typeVarT))

        builderClassSpecBuilder.addFunction(buildInvokeFunctionSpec(typeVarT))
        return builderClassSpecBuilder.build()
    }

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
    context(_: Resolver)
    private fun DataClassFragmentDescriptor.buildInvokeFunctionSpec(typeVarT: TypeName): FunSpec {
        return FunSpec.builder("invoke")
            .addParameter(
                ParameterSpec.builder(
                        "fn",
                        LambdaTypeName.get(
                            classDeclaration.generatedBuilder.parameterizedBy(typeVarT),
                            returnType = ClassNames.unit,
                        ),
                    )
                    .build()
            )
            .addModifiers(KModifier.OPERATOR)
            .addCode("apply(fn)")
            .build()
    }

    /**
     * Builds the "from" function that accepts a prototype instance of the data class and
     * initializers the builder with its values.
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
    context(_: Resolver)
    private fun DataClassFragmentDescriptor.buildFromFunction(typeVarT: TypeVariableName) =
        context(environment) {
            FunSpec.builder("from")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("prototype", typeVarT)
                .addCode(
                    buildCodeBlock {
                        add("if·(prototype·==·null)·{\n")
                        add("····isNull·=·true\n")
                        add("} else {\n")
                        forEachProperty {
                            if (isScalarProperty()) {
                                add("····%L·=·prototype.%L\n", propertyNameAlias(), propertyName)
                            } else {
                                add(
                                    "····%L.from(prototype.%L)\n",
                                    propertyNameAlias(),
                                    propertyName,
                                )
                            }
                        }
                        add("}\n")
                    }
                )
                .returns(ClassNames.unit)
                .build()
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
    context(_: Resolver)
    private fun DataClassFragmentDescriptor.buildTestModelBuilderBuildFunction() =
        context(environment) {
            FunSpec.builder("doBuild")
                .returns(generatedDataClassName)
                .addCode(
                    buildCodeBlock {
                        add("return %T(", generatedDataClassName)

                        forEachProperty {
                            add(if (isScalarProperty()) "%L" else "%L.build()", propertyNameAlias())
                            add(",")
                        }

                        add(")")
                    }
                )
                .build()
        }

    private fun DataClassFragmentPropertyDescriptor.propertyNameAlias() =
        propertyDeclaration
            .getAnnotationsByType(GenerateDataClassFragmentDataClass.TestDataDslName::class)
            .firstOrNull()
            ?.name ?: propertyName

    /**
     * For a given SchemaFragment property declared type, return the type that should be used for
     * the corresponding test data DSL builder property.
     */
    context(_: Resolver)
    private fun KSType.builderType(): TypeName {
        return with(ClassNames) {
            accept(
                object : DataClassPropertyTypeSymbolVisitor<TypeName> {
                    /*
                    Examples:
                    PropertyWithNullability<String, NotNull> -> String
                    PropertyWithNullability<String, Nullable> -> String?
                    */
                    override fun visitProperty(nestedType: KSType, nullable: Boolean): TypeName =
                        nestedType.toTypeName().copy(nullable = nullable)

                    /*
                    Examples:
                    NestedPropertyWithNullability<Egg, NotNull> -> EggDataBuilder<EggData>
                    NestedPropertyWithNullability<Egg, Nullable> -> EggDataBuilder<EggData?>
                    */
                    override fun visitNestedProperty(
                        nestedType: KSType,
                        nullable: Boolean,
                    ): TypeName =
                        (nestedType.declaration as KSClassDeclaration)
                            .toClassName()
                            .generatedBuilder
                            .parameterizedBy(
                                nestedType.generatedDataClassName.copy(nullable = nullable)
                            )

                    override fun visitArrayProperty(
                        nestedType: KSType,
                        nullable: Boolean,
                        iterableType: KSType,
                    ): TypeName =
                        if (nestedType.isScalarProperty()) {
                            /*
                            Examples:
                            ArrayPropertyWithNullability<PropertyWithNullability<String, NotNull>, NotNull, List<*>> -> ScalarIterableBuilder<List<String>, String>
                            ArrayPropertyWithNullability<PropertyWithNullability<String, Nullable>, NotNull, List<*>> -> ScalarIterableBuilder<List<String?>, String?>
                            ArrayPropertyWithNullability<PropertyWithNullability<String, NotNull>, Nullable, List<*>> -> ScalarIterableBuilder<List<String>?, String>
                            ArrayPropertyWithNullability<PropertyWithNullability<String, Nullable>, Nullable, List<*>> -> ScalarIterableBuilder<List<String?>?, String?>
                             */
                            val builderType = nestedType.builderType()
                            scalarIterableBuilder.parameterizedBy(
                                (iterableType.declaration as KSClassDeclaration)
                                    .toClassName()
                                    .parameterizedBy(nestedType.toDataClassPropertyType())
                                    .copy(nullable = nullable),
                                builderType,
                            )
                        } else {
                            /*
                            Examples:
                            ArrayPropertyWithNullability<NestedPropertyWithNullability<Egg, NotNull>, NotNull, List<*>> -> IterableBuilder<List<EggData>, EggData, EggDataBuilder<EggData>>
                            ArrayPropertyWithNullability<NestedPropertyWithNullability<Egg, Nullable>, NotNull, List<*>> -> IterableBuilder<List<EggData?>, EggData?, EggDataBuilder<EggData?>>
                            ArrayPropertyWithNullability<NestedPropertyWithNullability<Egg, NotNull>, Nullable, List<*>> -> IterableBuilder<List<EggData>?, EggData, EggDataBuilder<EggData>>
                            ArrayPropertyWithNullability<NestedPropertyWithNullability<Egg, Nullable>, Nullable, List<*>> -> IterableBuilder<List<EggData?>?, EggData?, EggDataBuilder<EggData?>>
                            */
                            val elementBuilderType =
                                nestedType.builderType() as ParameterizedTypeName
                            val whatItBuilds = elementBuilderType.typeArguments.first()
                            iterableBuilder.parameterizedBy(
                                (iterableType.declaration as KSClassDeclaration)
                                    .toClassName()
                                    .parameterizedBy(nestedType.toDataClassPropertyType())
                                    .copy(nullable = nullable),
                                whatItBuilds,
                                elementBuilderType,
                            )
                        }

                    override fun visitMapProperty(nestedType: KSType, nullable: Boolean): TypeName =
                        if (nestedType.isScalarProperty()) {
                            /*
                            Examples:
                            MapPropertyWithNullability<PropertyWithNullability<String, NotNull>, NotNull> -> ScalarMapBuilder<Map<String, String>, String>
                            MapPropertyWithNullability<PropertyWithNullability<String, Nullable>, NotNull> -> ScalarMapBuilder<Map<String, String?>, String?>
                            MapPropertyWithNullability<PropertyWithNullability<String, NotNull>, Nullable> -> ScalarMapBuilder<Map<String, String>?, String>
                            MapPropertyWithNullability<PropertyWithNullability<String, Nullable>, Nullable> -> ScalarMapBuilder<Map<String, String?>?, String?>
                             */
                            scalarMapBuilder.parameterizedBy(
                                map.parameterizedBy(string, nestedType.builderType())
                                    .copy(nullable = nullable),
                                nestedType.builderType(),
                            )
                        } else {
                            /*
                            Examples:
                            MapPropertyWithNullability<NestedPropertyWithNullability<Egg, NotNull>, NotNull> -> MapBuilder<Map<String, EggData>, EggData, EggDataBuilder<EggData>>
                            MapPropertyWithNullability<NestedPropertyWithNullability<Egg, Nullable>, NotNull> -> MapBuilder<Map<String, EggData?>, EggData?, EggDataBuilder<EggData?>>
                            MapPropertyWithNullability<NestedPropertyWithNullability<Egg, NotNull>, Nullable> -> MapBuilder<Map<String, EggData>?, EggData, EggDataBuilder<EggData>>
                            MapPropertyWithNullability<NestedPropertyWithNullability<Egg, Nullable>, Nullable> -> MapBuilder<Map<String, EggData?>?, EggData?, EggDataBuilder<EggData?>>
                            */
                            val mapValueBuilderType =
                                nestedType.builderType() as ParameterizedTypeName
                            val whatItBuilds = mapValueBuilderType.typeArguments.first()
                            mapBuilder.parameterizedBy(
                                map.parameterizedBy(string, whatItBuilds).copy(nullable = nullable),
                                whatItBuilds,
                                mapValueBuilderType,
                            )
                        }
                }
            )
        }
    }

    /**
     * For a given test data DSL builder property type, return an expression to initialize the
     * property.
     *
     * Note that the receiver type is the *builder* property type, not the original
     * DataClassFragment property type.
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
                                short,
                                int,
                                byte -> add("0")
                                long -> add("0L")
                                double -> add("0.0")
                                float -> add("0.0f")
                                boolean -> add("false")
                                bigDecimal,
                                bigInt -> add("%T(%S)", typeName, "0")
                                instant -> add("%M", MemberName(instant, Instant::EPOCH.name))
                                localDate -> add("%M(1970, 1, 1)", MemberName(localDate, "of"))
                                else -> add("%T()", typeName)
                            }
                        }
                    }

                    is ParameterizedTypeName ->
                        add(
                            when (typeName.rawType) {
                                iterableBuilder,
                                mapBuilder -> typeName.buildCollectionBuilderInitializer()
                                scalarIterableBuilder,
                                scalarMapBuilder ->
                                    typeName.buildScalarCollectionBuilderInitializer()
                                else -> typeName.buildGeneratedBuilderInitializer()
                            }
                        )

                    else -> throw IllegalStateException("Unexpected type name $typeName")
                }
            }
        }
    }

    /**
     * Builds an initializer expression for the given receiver parameterized type. Receiver should
     * be one of [IterableBuilder] or [MapBuilder]
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
                    }
                )
            },
        )
        add(")")
        add(")")
    }

    /**
     * Builds an initializer expression for the given receiver parameterized type. Receiver should
     * be one of [ScalarIterableBuilder] or [ScalarMapBuilder]
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
                    }
                )
            },
        )
        add(")")
        add(")")
    }

    /**
     * Builds an initializer expression for the given receiver parameterized type. Receiver should
     * be a generated model builder.
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

    private interface CollectionTypeVisitor<T> {
        fun visitList(): T

        fun visitSet(): T

        fun visitQueue(): T

        fun visitMap(): T
    }

    private fun <T> ClassName.accept(visitor: CollectionTypeVisitor<T>): T =
        when (this) {
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

    private fun getGeneratedTestBuilderName(fragmentNameWithoutPackage: String) =
        "${getGeneratedDataClassName(fragmentNameWithoutPackage)}Builder"

    private fun getGeneratedTestBuilderDslFunctionName(fragmentNameWithoutPackage: String) =
        fragmentNameWithoutPackage.replace('.', '_').replaceFirstChar(Char::lowercase)

    private val ClassName.generatedBuilder: ClassName
        get() {
            val generatedImplementationName = getGeneratedTestBuilderName(nameWithoutPackage)
            val parts = listOf(addDslPackageSuffix(packageName), generatedImplementationName)
            val classNameString = parts.filter(String::isNotEmpty).joinToString(PACKAGE_DELIMITER)
            return ClassName.bestGuess(classNameString)
        }

    private fun addDslPackageSuffix(prefix: String): String =
        listOf(prefix, TEST_DSL_PACKAGE_SUFFIX)
            .filter(String::isNotEmpty)
            .joinToString(PACKAGE_DELIMITER)

    private fun ParameterizedTypeName.collectParameterizedTypes(): List<ParameterizedTypeName> =
        listOf(this) +
            (typeArguments.filterIsInstance<ParameterizedTypeName>().flatMap {
                it.collectParameterizedTypes()
            })

    companion object {
        private val INDENT = " ".repeat(4)
        private const val PACKAGE_DELIMITER = "."
        const val TEST_DSL_PACKAGE_SUFFIX = "generatedtestdsl"
    }
}
