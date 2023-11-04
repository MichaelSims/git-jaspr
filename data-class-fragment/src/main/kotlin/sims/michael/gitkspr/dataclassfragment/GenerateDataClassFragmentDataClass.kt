package sims.michael.gitkspr.dataclassfragment

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateDataClassFragmentDataClass(
    val testDataDslFactoryFunctionName: String = "",
) {

    @Target(AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class TestDataDslName(val name: String = "")
}
