package sims.michael.gitjaspr.dataclassfragment.codegen

import com.google.auto.service.AutoService
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.dataclassfragment.GenerateDataClassFragmentDataClass

@Suppress("unused")
@AutoService(SymbolProcessorProvider::class)
class DataClassFragmentDataClassGeneratorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        DataClassFragmentDataClassGenerator(environment)
}

class DataClassFragmentDataClassGenerator(private val environment: SymbolProcessorEnvironment) :
    SymbolProcessor {

    @Suppress("unused")
    private val logger = LoggerFactory.getLogger(DataClassFragmentDataClassGenerator::class.java)

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        context(environment, resolver) {
            resolver
                .collectFragments()
                .filter {
                    it.classDeclaration.isAnnotationPresent(
                        GenerateDataClassFragmentDataClass::class
                    )
                }
                .forEach {
                    it.process {
                        generateDataClassSpec()
                            .writeTo(
                                environment.codeGenerator,
                                false,
                                listOfNotNull(classDeclaration.containingFile),
                            )
                    }
                }
        }
        return emptyList()
    }

    context(_: Resolver)
    private fun DataClassFragmentDescriptor.generateDataClassSpec(): FileSpec =
        FileSpec.builder(generatedDataClassName)
            .indent(INDENT)
            .addType(buildDataClassSpec())
            .build()

    context(_: Resolver)
    private fun DataClassFragmentDescriptor.buildDataClassSpec(): TypeSpec {
        val ctorSpecBuilder = FunSpec.constructorBuilder()
        val dataClassSpecBuilder =
            TypeSpec.classBuilder(generatedDataClassName)
                .addModifiers(KModifier.DATA)
                .addKdoc("Generated from [%T]", classDeclaration.toClassName())

        context(environment) {
            forEachProperty {
                val propertyTypeName = propertyType.toDataClassPropertyType()

                // We have to add the property to the data class AND the constructor. Kotlin Poet
                // will merge the properties into the ctor
                val propertySpec =
                    PropertySpec.builder(propertyName, propertyTypeName)
                        .addKdoc(
                            "Generated from DataClassFragment [%T.%L]",
                            this@buildDataClassSpec.classDeclaration.toClassName(),
                            propertyName,
                        )
                        .initializer(propertyName)
                        .build()

                ctorSpecBuilder.addParameter(propertyName, propertyTypeName)
                dataClassSpecBuilder.addProperty(propertySpec)
            }
        }

        return dataClassSpecBuilder.primaryConstructor(ctorSpecBuilder.build()).build()
    }

    companion object {
        private val INDENT = " ".repeat(4)
    }
}
