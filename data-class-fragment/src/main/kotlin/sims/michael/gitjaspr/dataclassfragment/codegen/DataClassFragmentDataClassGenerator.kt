package sims.michael.gitjaspr.dataclassfragment.codegen

import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.dataclassfragment.GenerateDataClassFragmentDataClass
import java.io.File
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

@Suppress("unused")
@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_11)
class DataClassFragmentDataClassGenerator : AbstractProcessor() {

    private val processorUtilities by lazy { DataClassFragmentProcessorUtilities(processingEnv) }

    @Suppress("unused")
    private val logger = LoggerFactory.getLogger(DataClassFragmentDataClassGenerator::class.java)

    override fun getSupportedAnnotationTypes(): Set<String> = setOf(GenerateDataClassFragmentDataClass::class.java.name)

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val fragments = processorUtilities
            .collectFragments(
                roundEnv.getElementsAnnotatedWith(GenerateDataClassFragmentDataClass::class.java),
            )

        for (fragment: TypeElement in fragments) {
            try {
                generateCodeForDataClassFragment(fragment, processorUtilities.generatedSourcesDir)
            } catch (e: IllegalStateException) {
                processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, e.message, fragment)
            }
        }

        // Don't "claim" these elements since the test DSL generator may also need to process them
        return false
    }

    private fun generateCodeForDataClassFragment(fragment: TypeElement, generatedSourcesDir: File) {
        FileSpec
            .builder(
                processorUtilities.getPackageName(fragment),
                processorUtilities.getGeneratedDataClass(fragment).nameWithoutPackage,
            )
            .indent(INDENT)
            .addType(buildDataClassSpec(fragment))
            .build()
            .writeTo(generatedSourcesDir)
    }

    private fun buildDataClassSpec(fragment: TypeElement): TypeSpec {
        val ctorSpecBuilder = FunSpec.constructorBuilder()
        val dataClassSpecBuilder = TypeSpec
            .classBuilder(processorUtilities.getGeneratedDataClass(fragment))
            .addModifiers(KModifier.DATA)
            .addKdoc("Generated from [%T]", fragment.toClassName())

        for ((property, returnType) in processorUtilities.getNamedColumnProperties(fragment)) {
            val propertyTypeName = returnType.toDataClassPropertyType()

            // We have to add the property to the data class AND the constructor. Kotlin Poet will merge the
            // properties into the ctor
            val propertySpec = PropertySpec
                .builder(property.name, propertyTypeName)
                .addKdoc(
                    "Generated from DataClassFragment [%T.%L]",
                    fragment.toClassName(),
                    property.name,
                )
                .initializer(property.name)
                .build()

            ctorSpecBuilder.addParameter(property.name, propertyTypeName)
            dataClassSpecBuilder.addProperty(propertySpec)
        }

        return dataClassSpecBuilder
            .primaryConstructor(ctorSpecBuilder.build())
            .build()
    }

    private fun ParameterizedTypeName.toDataClassPropertyType(): TypeName {
        val thisType = this
        val typeArgument = typeArguments.first()
        return accept(object : NamedColumnTypeVisitor<TypeName> {
            override fun visitColumn(): TypeName {
                return typeArgument.copy(nullable = thisType.hasNullableTypeArgument)
            }

            override fun visitNestedColumn(): TypeName =
                (typeArgument as ClassName)
                    .generatedDataClass
                    .copy(nullable = thisType.hasNullableTypeArgument)

            override fun visitArrayColumn(): TypeName =
                checkNotNull(thisType.iterableType)
                    .parameterizedBy(
                        (typeArgument as ParameterizedTypeName)
                            .toDataClassPropertyType()
                            .copy(nullable = typeArgument.hasNullableTypeArgument),
                    )
                    .copy(nullable = thisType.hasNullableTypeArgument)

            override fun visitMapColumn(): TypeName =
                ClassNames.map
                    .parameterizedBy(
                        ClassNames.string,
                        (typeArgument as ParameterizedTypeName)
                            .toDataClassPropertyType()
                            .copy(nullable = typeArgument.hasNullableTypeArgument),
                    )
                    .copy(nullable = thisType.hasNullableTypeArgument)
        })
    }

    companion object {
        private val INDENT = " ".repeat(4)
    }
}
